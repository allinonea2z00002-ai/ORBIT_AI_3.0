package com.orbit.ai.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.orbit.ai.database.ConversationEntity
import com.orbit.ai.ui.components.*
import com.orbit.ai.viewmodel.ChatViewModel
import com.orbit.ai.viewmodel.UiMessage
import com.orbit.ai.voice.VoiceState
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val messages by vm.messages.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val voice by vm.voiceState.collectAsState()
    val partial by vm.partial.collectAsState()
    val attachment by vm.attachmentLabel.collectAsState()
    val online by vm.online.collectAsState()
    val settings by vm.settings.collectAsState()
    var input by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    var rememberTarget by remember { mutableStateOf<UiMessage?>(null) }
    var attachMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }

    val requestMic = rememberPermissionRequest(Manifest.permission.RECORD_AUDIO) { if (it) vm.startVoice() else vm.setError("Microphone permission is needed for voice.") }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let(vm::attachImage) }
    val pickDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::attachDocument) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        OrbitTopBar("Conversation", onBack, actions = {
            val label = if (online) "Online" else "Offline"
            StatusChip(label, if (online) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { showHistory = true }) { Icon(Icons.Filled.History, "History") }
            IconButton(onClick = { vm.newConversation() }) { Icon(Icons.Filled.Add, "New chat") }
        })

        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Filled.Forum, "Say hello, ask a question, or give a command like \"open youtube\".")
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(messages, key = { it.id }) { m ->
                    MessageBubble(
                        m,
                        isLastAssistant = m.id == messages.lastOrNull { it.role == "assistant" }?.id,
                        onCopy = { clipboard.setText(AnnotatedString(m.text)) },
                        onSpeak = { vm.speakMessage(m.text) },
                        onRegenerate = { vm.regenerate(m.id) },
                        onRemember = { rememberTarget = m },
                        onDelete = { vm.deleteMessage(m.id) }
                    )
                }
            }
        }

        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            Text("Thinking…", Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        }
        error?.let {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { vm.clearError() }) { Text("Dismiss") }
            }
        }
        if (voice == VoiceState.SPEAKING) {
            OrbitOutlinedButton("Stop speaking", { vm.stopSpeaking() }, Modifier.padding(horizontal = 12.dp))
        }
        if (voice == VoiceState.LISTENING) {
            Text("Listening… ${partial}", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.secondary)
        }
        attachment?.let {
            AssistChip(onClick = { vm.clearAttachment() }, label = { Text(it) }, trailingIcon = { Icon(Icons.Filled.Close, "Remove") }, modifier = Modifier.padding(horizontal = 12.dp))
        }

        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { attachMenu = true }) { Icon(Icons.Filled.AttachFile, "Attach") }
                DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                    DropdownMenuItem(text = { Text("Image") }, onClick = { attachMenu = false; pickImage.launch("image/*") })
                    DropdownMenuItem(text = { Text("PDF or text file") }, onClick = { attachMenu = false; pickDoc.launch(arrayOf("application/pdf", "text/*")) })
                }
            }
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Message ORBIT") }, maxLines = 4, shape = RoundedCornerShape(24.dp)
            )
            IconButton(onClick = {
                if (hasPermission(ctx, Manifest.permission.RECORD_AUDIO)) vm.toggleMic() else requestMic()
            }) { Icon(if (voice == VoiceState.LISTENING) Icons.Filled.Stop else Icons.Filled.Mic, "Voice") }
            IconButton(onClick = { vm.send(input); input = "" }, enabled = !busy && (input.isNotBlank() || attachment != null)) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
    }

    if (showHistory) {
        val query by vm.query.collectAsState()
        val list by vm.conversations.collectAsState(initial = emptyList())
        ModalBottomSheet(onDismissRequest = { showHistory = false }) {
            HistorySheet(
                list, query, vm::setQuery,
                onOpen = { vm.open(it); showHistory = false },
                onDelete = { vm.deleteConversation(it) },
                onDeleteAll = { vm.deleteAllConversations() },
                saving = settings.saveHistory
            )
        }
    }

    rememberTarget?.let { m ->
        ConfirmDialog(
            "Save to memory?",
            "ORBIT will remember this text on your phone and, if \"use memory\" is on, include it in future online requests:\n\n“${m.text.take(200)}”",
            "Remember",
            onConfirm = { vm.remember(m.text); rememberTarget = null },
            onDismiss = { rememberTarget = null }
        )
    }
}

@Composable
private fun MessageBubble(
    m: UiMessage, isLastAssistant: Boolean,
    onCopy: () -> Unit, onSpeak: () -> Unit, onRegenerate: () -> Unit, onRemember: () -> Unit, onDelete: () -> Unit
) {
    val isUser = m.role == "user"
    val isError = m.role == "error"
    val time = remember(m.time) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.time)) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        val bg = when {
            isUser -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
            isError -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
            else -> MaterialTheme.colorScheme.surface
        }
        Column(
            Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(18.dp)).background(bg).padding(12.dp)
        ) {
            if (isUser || isError) Text(m.text, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            else MarkdownText(m.text)
        }
        val label = when (m.engine) {
            "ONLINE" -> "Online AI"
            "OFFLINE" -> "Offline model"
            "ACTION" -> "Phone action"
            else -> null
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(label, time, m.note).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
        }
        Row {
            SmallIcon(Icons.Filled.ContentCopy, "Copy", onCopy)
            if (!isError) SmallIcon(Icons.Filled.BookmarkAdd, "Remember", onRemember)
            if (m.role == "assistant") SmallIcon(Icons.Filled.VolumeUp, "Speak", onSpeak)
            if (m.role == "assistant" && isLastAssistant && m.engine != "ACTION") SmallIcon(Icons.Filled.Refresh, "Regenerate", onRegenerate)
            SmallIcon(Icons.Filled.DeleteOutline, "Delete message", onDelete)
        }
    }
}

@Composable
private fun SmallIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(icon, desc, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HistorySheet(
    list: List<ConversationEntity>, query: String, onQuery: (String) -> Unit,
    onOpen: (Long) -> Unit, onDelete: (Long) -> Unit, onDeleteAll: () -> Unit, saving: Boolean
) {
    var confirmAll by remember { mutableStateOf(false) }
    var confirmOne by remember { mutableStateOf<Long?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
        Text("Conversations", style = MaterialTheme.typography.headlineMedium)
        if (!saving) InfoNote("History saving is off in Settings, so new chats are not stored.")
        OutlinedTextField(query, onQuery, Modifier.fillMaxWidth().padding(vertical = 8.dp), placeholder = { Text("Search conversations") }, singleLine = true)
        if (list.isEmpty()) EmptyState(Icons.Filled.History, "No saved conversations.")
        LazyColumn(Modifier.heightIn(max = 380.dp)) {
            items(list, key = { it.id }) { c ->
                ListItem(
                    headlineContent = { Text(c.title, maxLines = 1) },
                    supportingContent = { Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(c.updatedAt))) },
                    trailingContent = { IconButton(onClick = { confirmOne = c.id }) { Icon(Icons.Filled.DeleteOutline, "Delete") } },
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onOpen(c.id) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                )
            }
        }
        if (list.isNotEmpty()) TextButton(onClick = { confirmAll = true }) { Text("Delete all conversations", color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(16.dp))
    }
    if (confirmAll) ConfirmDialog("Delete all conversations?", "This cannot be undone.", "Delete all", { onDeleteAll(); confirmAll = false }, { confirmAll = false })
    confirmOne?.let { id -> ConfirmDialog("Delete this conversation?", "This cannot be undone.", "Delete", { onDelete(id); confirmOne = null }, { confirmOne = null }) }
}
