package com.orbit.ai.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orbit.ai.AppContainer
import com.orbit.ai.ai.ChatTurn
import com.orbit.ai.ai.ImageInput
import com.orbit.ai.ai.LocalModel
import com.orbit.ai.ai.ModelLoadState
import com.orbit.ai.ai.PromptBuilder
import com.orbit.ai.ai.TransferState
import com.orbit.ai.automation.ActionOutcome
import com.orbit.ai.automation.CommandParser
import com.orbit.ai.automation.ReminderWorker
import com.orbit.ai.database.CustomCommandEntity
import com.orbit.ai.database.MemoryEntity
import com.orbit.ai.database.RoutineEntity
import com.orbit.ai.files.FileCategory
import com.orbit.ai.files.formatBytes
import com.orbit.ai.intelligence.*
import com.orbit.ai.productivity.DocumentTools
import com.orbit.ai.productivity.LOCAL_SEARCH_LABEL
import com.orbit.ai.productivity.ToolAction
import com.orbit.ai.productivity.ToolKind
import com.orbit.ai.settings.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ======================= Phone intelligence =======================

data class IntelUi(
    val loading: Boolean = true,
    val storage: StorageInfo? = null,
    val battery: BatteryInfo? = null,
    val device: List<Pair<String, String>> = emptyList(),
    val apps: List<AppEntry> = emptyList(),
    val usage: List<AppUsage> = emptyList(),
    val usageAccess: Boolean = false,
    val suggestions: List<String> = emptyList(),
    val categoryCounts: Map<String, Int> = emptyMap()
)

class IntelViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(IntelUi())
    val ui: StateFlow<IntelUi> = _ui.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            val storage = c.intel.storage()
            val battery = c.intel.battery()
            val device = c.intel.device().rows
            val apps = c.intel.launchableApps()
            val access = c.intel.hasUsageAccess()
            val usage = c.intel.usage(7)
            _ui.value = IntelUi(false, storage, battery, device, apps, usage, access, _ui.value.suggestions,
                apps.groupingBy { it.category }.eachCount())
            // Cleanup suggestions need a file scan, which can take a while, so they arrive second.
            val files = c.fileScanner.scanAll()
            val tips = buildList {
                val big = files.filter { it.sizeBytes >= 100L * 1024 * 1024 }.sortedByDescending { it.sizeBytes }
                if (big.isNotEmpty()) add("${big.size} files are over 100 MB (largest: ${big.first().name}, ${formatBytes(big.first().sizeBytes)}).")
                val apk = files.filter { it.category == FileCategory.APK }
                if (apk.isNotEmpty()) add("${apk.size} APK installer files (${formatBytes(apk.sumOf { it.sizeBytes })}) are probably no longer needed.")
                val dups = c.fileScanner.findDuplicates(files)
                if (dups.isNotEmpty()) add("${dups.size} sets of duplicate files waste about ${formatBytes(dups.sumOf { it.sizeBytes * (it.files.size - 1) })}.")
                if (isEmpty()) add(if (files.isEmpty()) "Grant media access (and add a folder in Files) to get cleanup suggestions." else "Nothing obvious to clean up.")
            }
            _ui.update { it.copy(suggestions = tips) }
        }
    }

    fun launchIntent(pkg: String) = c.intel.launchIntentFor(pkg)
}

// ======================= Automation =======================

class AutomationViewModel(private val c: AppContainer) : ViewModel() {
    val log = c.db.automation().observeLog()
    val commands = c.db.automation().observeCommands()
    val routines = c.db.automation().observeRoutines()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    fun run(text: String) {
        val cmd = CommandParser.parse(text)
        if (cmd == null) { _message.value = "I did not recognise that command. Try \"open youtube\" or \"timer 10 minutes\"."; return }
        viewModelScope.launch {
            _message.value = when (val o = c.actions.execute(text, cmd)) {
                is ActionOutcome.Done -> o.message
                is ActionOutcome.Remembered -> { c.memory.add(o.text); "Saved to memory: ${o.text}" }
                is ActionOutcome.NeedsFiles -> "File commands work in the chat or in Files."
            }
        }
    }

    fun addCommand(phrase: String, action: String) {
        if (phrase.isBlank() || action.isBlank()) return
        if (CommandParser.parse(action) == null) { _message.value = "That action is not a command I understand yet."; return }
        viewModelScope.launch { c.db.automation().insertCommand(CustomCommandEntity(phrase = phrase.trim(), actionText = action.trim())) }
    }

    fun deleteCommand(cmd: CustomCommandEntity) { viewModelScope.launch { c.db.automation().deleteCommand(cmd) } }

    fun addRoutine(hour: Int, minute: Int, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val id = c.db.automation().insertRoutine(RoutineEntity(hour = hour, minute = minute, text = text.trim()))
            ReminderWorker.scheduleDaily(c.context, id, hour, minute, text.trim())
        }
    }

    fun deleteRoutine(r: RoutineEntity) {
        viewModelScope.launch {
            ReminderWorker.cancelRoutine(c.context, r.id)
            c.db.automation().deleteRoutine(r)
        }
    }

    fun clearLog() { viewModelScope.launch { c.db.automation().clearLog() } }
}

// ======================= Productivity =======================

data class ProductivityUi(
    val tool: ToolKind = ToolKind.STUDY,
    val action: ToolAction? = null,
    val input: String = "",
    val attachment: String? = null,
    val docText: String? = null,
    val image: ImageInput? = null,
    val result: String = "",
    val engineNote: String = "",
    val busy: Boolean = false,
    val error: String? = null
)

class ProductivityViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(ProductivityUi(action = ToolKind.STUDY.actions.first()))
    val ui: StateFlow<ProductivityUi> = _ui.asStateFlow()

    fun selectTool(t: ToolKind) = _ui.update {
        ProductivityUi(tool = t, action = t.actions.firstOrNull())
    }
    fun selectAction(a: ToolAction) = _ui.update { it.copy(action = a, error = null) }
    fun setInput(s: String) = _ui.update { it.copy(input = s) }

    fun attachPdf(uri: Uri, name: String) {
        viewModelScope.launch {
            c.documents.extractPdfText(uri)
                .onSuccess { t ->
                    val limit = if (c.settings.settings.first().aiMode == AiMode.OFFLINE) 5_000 else 30_000
                    val (clipped, cut) = DocumentTools.clip(t, limit)
                    _ui.update { it.copy(docText = clipped, attachment = name + if (cut) " (first ${limit / 1000}k characters used)" else "", error = null) }
                }
                .onFailure { e -> _ui.update { it.copy(error = e.message) } }
        }
    }

    fun attachImage(uri: Uri, name: String) {
        viewModelScope.launch {
            c.documents.imageToBase64Jpeg(uri)
                .onSuccess { b -> _ui.update { it.copy(image = ImageInput("image/jpeg", b), attachment = name, error = null) } }
                .onFailure { e -> _ui.update { it.copy(error = e.message) } }
        }
    }

    fun run() {
        val s = _ui.value
        val action = s.action ?: return
        if (action.label == LOCAL_SEARCH_LABEL) {
            val doc = s.docText
            if (doc == null) { _ui.update { it.copy(error = "Pick a PDF first.") }; return }
            val hits = c.documents.searchText(doc, s.input)
            _ui.update { it.copy(result = if (hits.isEmpty()) "No matches." else "${hits.size} match(es):\n\n" + hits.joinToString("\n\n"), engineNote = "Searched on this phone, no AI used.", error = null) }
            return
        }
        if (s.tool == ToolKind.PDF && s.docText == null) { _ui.update { it.copy(error = "Pick a PDF first.") }; return }
        if (s.tool == ToolKind.IMAGE && s.image == null) { _ui.update { it.copy(error = "Pick an image first.") }; return }
        if (s.tool != ToolKind.IMAGE && s.input.isBlank() && s.docText == null) { _ui.update { it.copy(error = "Type or paste something first.") }; return }

        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null, result = "") }
            val st = c.settings.settings.first()
            val prompt = buildString {
                append(action.instruction).append("\n\n")
                s.docText?.let { append("Document:\n").append(it).append("\n\n") }
                if (s.input.isNotBlank()) append("User input:\n").append(s.input)
            }
            val system = PromptBuilder.system(st.userName, st.language.tag, st.customInstructions, emptyList()) +
                " Follow the task instruction exactly. Use plain text with short headings or bullets where helpful."
            c.router.ask(system, listOf(ChatTurn("user", prompt)), s.image)
                .onSuccess { r ->
                    _ui.update { it.copy(busy = false, result = r.text, engineNote = (if (r.engine.name == "ONLINE") "Online AI" else "Offline model") + (r.note?.let { n -> " · $n" } ?: "")) }
                }
                .onFailure { e -> _ui.update { it.copy(busy = false, error = e.message) } }
        }
    }
}

// ======================= Memory =======================

class MemoryViewModel(private val c: AppContainer) : ViewModel() {
    val memories: Flow<List<MemoryEntity>> = c.memory.all
    val settings: StateFlow<OrbitSettings> =
        c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, OrbitSettings())

    fun add(text: String) { viewModelScope.launch { c.memory.add(text) } }
    fun edit(m: MemoryEntity, text: String) { viewModelScope.launch { c.memory.edit(m, text) } }
    fun delete(m: MemoryEntity) { viewModelScope.launch { c.memory.delete(m) } }
    fun deleteAll() { viewModelScope.launch { c.memory.deleteAll() } }
    fun setUseInPrompts(v: Boolean) { viewModelScope.launch { c.settings.setUseMemory(v) } }
}

// ======================= Settings =======================

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<OrbitSettings> =
        c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, OrbitSettings())

    val transfer: StateFlow<TransferState> = c.models.state
    val modelLoad: StateFlow<ModelLoadState> = c.offline.loadState
    val modelError: StateFlow<String?> = c.offline.lastError

    private val _models = MutableStateFlow<List<LocalModel>>(emptyList())
    val models: StateFlow<List<LocalModel>> = _models.asStateFlow()

    private val _voices = MutableStateFlow<List<String>>(emptyList())
    val voices: StateFlow<List<String>> = _voices.asStateFlow()

    var proxyToken: String
        get() = c.secure.proxyToken
        set(v) { c.secure.proxyToken = v }

    var modelToken: String
        get() = c.secure.modelDownloadToken
        set(v) { c.secure.modelDownloadToken = v }

    init { refreshModels(); c.voice.warmUp() }

    fun refreshModels() { _models.value = c.models.list() }

    fun loadVoices() { _voices.value = c.voice.voiceNames(settings.value.language.tag) }
    fun mediaVolume(): Float = c.voice.mediaVolume()
    fun setMediaVolume(v: Float) = c.voice.setMediaVolume(v)

    fun set(block: suspend SettingsRepository.() -> Unit) { viewModelScope.launch { c.settings.block() } }

    fun importModel(uri: Uri) {
        viewModelScope.launch { c.models.importFrom(uri); refreshModels() }
    }

    fun downloadModel(url: String, fileName: String) {
        viewModelScope.launch { c.models.download(url.trim(), fileName.trim()); refreshModels() }
    }

    fun cancelTransfer() = c.models.cancel()
    fun dismissTransfer() = c.models.resetState()

    fun useModel(name: String) {
        viewModelScope.launch {
            c.settings.setActiveModelFile(name)
            c.offline.unload()
            c.offline.load() // loads now so the user sees real success or the memory error
        }
    }

    fun deleteModel(name: String) {
        viewModelScope.launch {
            if (settings.value.activeModelFile == name) { c.offline.unload(); c.settings.setActiveModelFile("") }
            withContext(Dispatchers.IO) { c.models.delete(name) }
            refreshModels()
        }
    }

    fun modelStorageUsed(): Long = c.models.storageUsedBytes()

    fun deleteAllChats() { viewModelScope.launch { c.db.conversations().deleteAll() } }
    fun deleteAllMemory() { viewModelScope.launch { c.memory.deleteAll() } }
}
