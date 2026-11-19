package com.orbit.ai.viewmodel

import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orbit.ai.AppContainer
import com.orbit.ai.ai.ChatTurn
import com.orbit.ai.ai.ImageInput
import com.orbit.ai.ai.PromptBuilder
import com.orbit.ai.automation.ActionOutcome
import com.orbit.ai.automation.Command
import com.orbit.ai.automation.CommandParser
import com.orbit.ai.database.ConversationEntity
import com.orbit.ai.database.MessageEntity
import com.orbit.ai.files.FileItem
import com.orbit.ai.files.formatBytes
import com.orbit.ai.productivity.DocumentTools
import com.orbit.ai.settings.AiMode
import com.orbit.ai.settings.OrbitSettings
import com.orbit.ai.ui.components.OrbState
import com.orbit.ai.voice.VoiceState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * One chat bubble.
 * [engine]: "ONLINE", "OFFLINE", "ACTION" (a phone action, not AI) or null.
 * [aiText] is what is actually sent to the AI (it may include attached document text).
 * role "error" bubbles are shown to the user but never sent to the AI.
 */
data class UiMessage(
    val id: Long,
    val role: String,
    val text: String,
    val engine: String?,
    val time: Long,
    val note: String? = null,
    val aiText: String? = null,
    val dbId: Long? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(private val c: AppContainer) : ViewModel() {

    val settings: StateFlow<OrbitSettings> =
        c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, OrbitSettings())

    private val localIds = AtomicLong(1)

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    private val _conversationId = MutableStateFlow<Long?>(null)
    val conversationId: StateFlow<Long?> = _conversationId.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    val conversations: Flow<List<ConversationEntity>> = _query.flatMapLatest { q ->
        if (q.isBlank()) c.db.conversations().observeAll() else c.db.conversations().search(q.trim())
    }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _lastReply = MutableStateFlow("")
    val lastReply: StateFlow<String> = _lastReply.asStateFlow()

    private val _attachmentLabel = MutableStateFlow<String?>(null)
    val attachmentLabel: StateFlow<String?> = _attachmentLabel.asStateFlow()
    private var pendingImage: ImageInput? = null
    private var pendingDoc: Pair<String, String>? = null

    val voiceState = c.voice.state
    val micLevel = c.voice.level
    val partial = c.voice.partial
    val online = c.network.online

    /** Navigation requests from commands such as "organize my downloads". */
    val navEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)

    val orbState: StateFlow<OrbState> = combine(c.voice.state, _busy, _error, settings, c.network.online) { v, busy, err, s, net ->
        when {
            v == VoiceState.LISTENING -> OrbState.LISTENING
            v == VoiceState.SPEAKING -> OrbState.SPEAKING
            busy -> OrbState.PROCESSING
            err != null -> OrbState.ERROR
            s.aiMode == AiMode.OFFLINE || !net -> OrbState.OFFLINE
            else -> OrbState.IDLE
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, OrbState.IDLE)

    // ---------------- conversations ----------------

    fun newConversation() { _conversationId.value = null; _messages.value = emptyList(); _error.value = null }

    fun open(id: Long) {
        viewModelScope.launch {
            val rows = c.db.messages().getAll(id)
            _conversationId.value = id
            _messages.value = rows.map {
                UiMessage(localIds.getAndIncrement(), it.role, it.content, it.engine, it.createdAt, dbId = it.id)
            }
        }
    }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            c.db.conversations().delete(id)
            if (_conversationId.value == id) newConversation()
        }
    }

    fun deleteAllConversations() {
        viewModelScope.launch { c.db.conversations().deleteAll(); newConversation() }
    }

    fun setQuery(q: String) { _query.value = q }
    fun setAiMode(m: AiMode) { viewModelScope.launch { c.settings.setAiMode(m) } }
    fun clearError() { _error.value = null }
    fun setError(message: String?) { _error.value = message }

    // ---------------- sending ----------------

    fun send(raw: String, viaVoice: Boolean = false) {
        val text = raw.trim()
        if (_busy.value) return
        if (text.isEmpty() && pendingImage == null && pendingDoc == null) return
        viewModelScope.launch {
            _error.value = null
            val doc = pendingDoc
            val img = pendingImage
            pendingDoc = null; pendingImage = null; _attachmentLabel.value = null

            val shown = buildString {
                if (doc != null) append("📎 ").append(doc.first).append('\n')
                if (img != null) append("🖼 image attached\n")
                append(text)
            }.trim()
            val aiText = if (doc != null)
                "Document \"${doc.first}\":\n${doc.second}\n\n" + text.ifBlank { "Summarise this document." }
            else null
            append(UiMessage(localIds.getAndIncrement(), "user", shown, null, System.currentTimeMillis(), aiText = aiText))

            _busy.value = true
            try {
                if (doc == null && img == null && handleCommand(text, viaVoice)) return@launch
                askAi(viaVoice, img)
            } finally {
                _busy.value = false
            }
        }
    }

    /** Returns true if the text was a phone command that was handled here. */
    private suspend fun handleCommand(text: String, viaVoice: Boolean): Boolean {
        val custom = c.db.automation().getCommands()
            .firstOrNull { CommandParser.normalize(it.phrase) == CommandParser.normalize(text) }
        val cmd = CommandParser.parse(custom?.actionText ?: text)
        if (cmd == null) {
            if (custom != null) {
                reply("I do not understand the action saved for \"${custom.phrase}\": ${custom.actionText}", "ACTION", viaVoice)
                return true
            }
            return false
        }
        when (val outcome = c.actions.execute(text, cmd)) {
            is ActionOutcome.Done -> reply(outcome.message, "ACTION", viaVoice)
            is ActionOutcome.Remembered -> {
                c.memory.add(outcome.text)
                reply("Noted. I will remember: ${outcome.text}", "ACTION", viaVoice)
            }
            is ActionOutcome.NeedsFiles -> handleFiles(outcome.command, viaVoice)
        }
        return true
    }

    private suspend fun handleFiles(cmd: Command, viaVoice: Boolean) {
        if (cmd == Command.OrganizeDownloads) {
            reply("Opening the organiser. You will see a preview and must confirm before anything moves.", "ACTION", viaVoice)
            navEvents.tryEmit("files?organize=true")
            return
        }
        val all = c.fileScanner.scanAll()
        val hint = if (all.isEmpty())
            " I found no files yet. Open Files to grant media access and choose a folder for documents and PDFs."
        else ""
        val text = when (cmd) {
            is Command.FindByCategory -> {
                val l = all.filter { it.category == cmd.category }.sortedByDescending { it.modifiedMs }
                describe("${cmd.category.label}: ${l.size} found", l) + hint
            }
            is Command.FindLarger -> {
                val l = all.filter { it.sizeBytes >= cmd.minBytes }.sortedByDescending { it.sizeBytes }
                describe("Files over ${formatBytes(cmd.minBytes)}: ${l.size} found", l) + hint
            }
            Command.LargestVideo -> {
                val v = all.filter { it.category == com.orbit.ai.files.FileCategory.VIDEOS }.maxByOrNull { it.sizeBytes }
                if (v == null) "I found no videos." + hint else "Your largest video is ${v.name}, ${formatBytes(v.sizeBytes)}."
            }
            Command.FindRecent -> {
                val dl = all.filter { it.inDownloads }.sortedByDescending { it.modifiedMs }
                if (dl.isNotEmpty()) describe("Recent downloads", dl)
                else describe("No download folder is visible, so here are your most recent files", all.sortedByDescending { it.modifiedMs }) + hint
            }
            Command.FindDuplicates -> {
                val groups = c.fileScanner.findDuplicates(all)
                if (groups.isEmpty()) "No duplicate files found."
                else {
                    val wasted = groups.sumOf { it.sizeBytes * (it.files.size - 1) }
                    "Found ${groups.size} sets of duplicates, wasting about ${formatBytes(wasted)}. Largest: " +
                        groups.take(3).joinToString("; ") { g -> "${g.files.first().name} x${g.files.size}" } +
                        ". Open Files > Duplicates to review. Nothing is deleted automatically."
                }
            }
            else -> "I cannot do that file request yet."
        }
        reply(text, "ACTION", viaVoice)
    }

    private fun describe(title: String, l: List<FileItem>): String =
        if (l.isEmpty()) title else title + "\n" +
            l.take(10).joinToString("\n") { "• ${it.name} (${formatBytes(it.sizeBytes)})" } +
            if (l.size > 10) "\n…and ${l.size - 10} more in Files." else ""

    private suspend fun askAi(viaVoice: Boolean, image: ImageInput?) {
        val s = settings.value
        val memories = if (s.useMemoryInPrompts) c.memory.texts() else emptyList()
        val system = PromptBuilder.system(s.userName, s.language.tag, s.customInstructions, memories)
        val history = _messages.value
            .filter { it.role == "user" || it.role == "assistant" }
            .takeLast(20)
            .map { ChatTurn(it.role, it.aiText ?: it.text) }
            .dropWhile { it.role != "user" }
        if (history.isEmpty()) return
        c.router.ask(system, history, image)
            .onSuccess { reply(it.text, it.engine.name, viaVoice, it.note) }
            .onFailure {
                val msg = it.message ?: "Something went wrong."
                _error.value = msg
                append(UiMessage(localIds.getAndIncrement(), "error", msg, null, System.currentTimeMillis()))
            }
    }

    private suspend fun reply(text: String, engine: String?, viaVoice: Boolean, note: String? = null) {
        append(UiMessage(localIds.getAndIncrement(), "assistant", text, engine, System.currentTimeMillis(), note))
        _lastReply.value = text
        if (viaVoice && settings.value.speakReplies) speak(text, continueAfter = true)
    }

    private suspend fun append(m: UiMessage) {
        _messages.value = _messages.value + m
        val dbId = persist(m)
        if (dbId != null) _messages.value = _messages.value.map { if (it.id == m.id) it.copy(dbId = dbId) else it }
    }

    private suspend fun persist(m: UiMessage): Long? {
        if (!settings.value.saveHistory || m.role == "error") return null
        var cid = _conversationId.value
        if (cid == null) {
            cid = c.db.conversations().insert(ConversationEntity(title = m.text.take(48).ifBlank { "New chat" }))
            _conversationId.value = cid
        }
        c.db.conversations().touch(cid, System.currentTimeMillis())
        return c.db.messages().insert(
            MessageEntity(conversationId = cid, role = m.role, content = m.text, engine = m.engine, createdAt = m.time)
        )
    }

    fun regenerate(messageId: Long) {
        if (_busy.value) return
        viewModelScope.launch {
            val msg = _messages.value.firstOrNull { it.id == messageId } ?: return@launch
            if (msg.role != "assistant") return@launch
            _messages.value = _messages.value.filter { it.id != messageId }
            msg.dbId?.let { c.db.messages().delete(it) }
            _busy.value = true
            try { askAi(viaVoice = false, image = null) } finally { _busy.value = false }
        }
    }

    fun deleteMessage(messageId: Long) {
        viewModelScope.launch {
            val msg = _messages.value.firstOrNull { it.id == messageId } ?: return@launch
            _messages.value = _messages.value.filter { it.id != messageId }
            msg.dbId?.let { c.db.messages().delete(it) }
        }
    }

    /** Only called after the user confirms in a dialog. */
    fun remember(text: String) { viewModelScope.launch { c.memory.add(text) } }

    // ---------------- attachments ----------------

    private fun displayName(uri: Uri): String =
        c.context.contentResolver.query(uri, null, null, null, null)?.use {
            val i = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && it.moveToFirst()) it.getString(i) else null
        } ?: "attachment"

    fun attachImage(uri: Uri) {
        viewModelScope.launch {
            c.documents.imageToBase64Jpeg(uri)
                .onSuccess { pendingImage = ImageInput("image/jpeg", it); _attachmentLabel.value = "Image: ${displayName(uri)}" }
                .onFailure { _error.value = it.message }
        }
    }

    fun attachDocument(uri: Uri) {
        viewModelScope.launch {
            val name = displayName(uri)
            val isPdf = c.context.contentResolver.getType(uri) == "application/pdf" || name.endsWith(".pdf", true)
            val r = if (isPdf) c.documents.extractPdfText(uri) else c.documents.readTextFile(uri)
            r.onSuccess {
                val limit = if (settings.value.aiMode == AiMode.OFFLINE) 5_000 else 20_000
                val (clipped, cut) = DocumentTools.clip(it, limit)
                pendingDoc = name to clipped
                _attachmentLabel.value = "Document: $name" + if (cut) " (first ${limit / 1000}k characters only)" else ""
            }.onFailure { _error.value = it.message }
        }
    }

    fun clearAttachment() { pendingImage = null; pendingDoc = null; _attachmentLabel.value = null }

    // ---------------- voice ----------------

    fun toggleMic() {
        when (c.voice.state.value) {
            VoiceState.LISTENING -> c.voice.stopListening()
            else -> startVoice()
        }
    }

    fun startVoice() {
        _error.value = null
        c.voice.startListening(
            settings.value.language.tag,
            onFinalResult = { send(it, viaVoice = true) },
            onFail = { _error.value = it }
        )
    }

    fun stopSpeaking() = c.voice.stopSpeaking()

    fun speakMessage(text: String) = speak(text, continueAfter = false)

    private fun speak(text: String, continueAfter: Boolean) {
        val s = settings.value
        c.voice.speak(
            text, s.language.tag, s.speechRate, s.speechPitch, s.voiceName,
            onUnavailable = { _error.value = it },
            onFinished = { if (continueAfter && s.continuousConversation) startVoice() }
        )
    }

    override fun onCleared() {
        c.voice.stopListening()
        super.onCleared()
    }
}
