package com.orbit.ai.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.orbit.ai.ai.ModelLoadState
import com.orbit.ai.ai.TransferState
import com.orbit.ai.files.formatBytes
import com.orbit.ai.settings.AiMode
import com.orbit.ai.settings.AppLanguage
import com.orbit.ai.settings.ThemeMode
import com.orbit.ai.ui.components.*
import com.orbit.ai.viewmodel.SettingsViewModel
import com.orbit.ai.viewmodel.orbitViewModel
import com.orbit.ai.voice.WakeWordService

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, openMemory: () -> Unit) {
    val vm = orbitViewModel { SettingsViewModel(it) }
    val s by vm.settings.collectAsState()
    val models by vm.models.collectAsState()
    val transfer by vm.transfer.collectAsState()
    val load by vm.modelLoad.collectAsState()
    val loadError by vm.modelError.collectAsState()
    val voices by vm.voices.collectAsState()
    val ctx = LocalContext.current

    var proxyUrl by remember(s.proxyUrl) { mutableStateOf(s.proxyUrl) }
    var token by remember { mutableStateOf(vm.proxyToken) }
    var modelUrl by remember { mutableStateOf("") }
    var modelName by remember { mutableStateOf("") }
    var hfToken by remember { mutableStateOf(vm.modelToken) }
    var instructions by remember(s.customInstructions) { mutableStateOf(s.customInstructions) }
    var userName by remember(s.userName) { mutableStateOf(s.userName) }
    var rate by remember(s.speechRate) { mutableFloatStateOf(s.speechRate) }
    var pitch by remember(s.speechPitch) { mutableFloatStateOf(s.speechPitch) }
    var volume by remember { mutableFloatStateOf(vm.mediaVolume()) }
    var voiceMenu by remember { mutableStateOf(false) }
    var confirmChats by remember { mutableStateOf(false) }
    var confirmMem by remember { mutableStateOf(false) }

    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importModel) }

    fun enableWake() {
        vm.set { setWakeWord(true) }
        WakeWordService.start(ctx)
    }
    val requestNotif = rememberPermissionRequest(Manifest.permission.POST_NOTIFICATIONS) { }
    val requestMic = rememberPermissionRequest(Manifest.permission.RECORD_AUDIO) { granted ->
        if (granted) enableWake()
    }

    LaunchedEffect(s.language) { vm.loadVoices() }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        OrbitTopBar("Settings", onBack)
        LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            // ---------- Appearance ----------
            item {
                GlowCard(Modifier.fillMaxWidth()) {
                    Text("Appearance", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ThemeMode.entries.forEach { t ->
                            FilterChip(s.theme == t, { vm.set { setTheme(t) } }, { Text(t.name.lowercase().replaceFirstChar { it.uppercase() }) })
                        }
                    }
                    SectionTitle("Language (voice and replies)")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AppLanguage.entries.forEach { l -> FilterChip(s.language == l, { vm.set { setLanguage(l) } }, { Text(l.label) }) }
                    }
                    OutlinedTextField(userName, { userName = it; vm.set { setUserName(it) } }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("What should ORBIT call you?") }, singleLine = true)
                }
            }

            // ---------- AI ----------
            item {
                GlowCard(Modifier.fillMaxWidth()) {
                    Text("AI engine", style = MaterialTheme.typography.titleMedium)
                    InfoNote("Auto uses online AI when it is set up and reachable, otherwise the offline model. If online fails, Auto falls back to offline. Online and Offline never switch silently.")
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AiMode.entries.forEach { m -> FilterChip(s.aiMode == m, { vm.set { setAiMode(m) } }, { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
                    }
                    SectionTitle("Online provider")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("gemini", "groq", "anthropic").forEach { p ->
                            FilterChip(s.provider == p, { vm.set { setProvider(p) } }, { Text(p.replaceFirstChar { it.uppercase() }) })
                        }
                    }
                    InfoNote("The provider API keys live on your backend proxy, never in this app. See the README to deploy it in a few minutes.")
                    OutlinedTextField(proxyUrl, { proxyUrl = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Backend proxy URL (https://…)") }, singleLine = true)
                    OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Proxy access token") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    Spacer(Modifier.height(6.dp))
                    OrbitButton("Save online settings", { vm.set { setProxyUrl(proxyUrl) }; vm.proxyToken = token })
                    InfoNote("The token is stored encrypted on this phone and is sent only to the URL above. Messages you send to online AI go through that server to the provider you chose.")
                }
            }

            // ---------- Offline model ----------
            item {
                GlowCard(Modifier.fillMaxWidth()) {
                    Text("Offline model", style = MaterialTheme.typography.titleMedium)
                    InfoNote(
                        "Runs fully on the phone with Google's LiteRT-LM runtime. It needs a model file in .litertlm format. " +
                            "Pick a small model (about 1 to 2 GB, such as a Gemma or Qwen 1 to 2 billion parameter build); bigger ones may not fit in free RAM " +
                            "even on a 12 GB phone, and ORBIT will tell you if so. Offline answers are weaker than online ones, especially in Bangla."
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("Status: " + when (load) {
                        ModelLoadState.NONE -> "not loaded"
                        ModelLoadState.LOADING -> "loading…"
                        ModelLoadState.READY -> "ready"
                        ModelLoadState.FAILED -> "failed"
                    })
                    if (load == ModelLoadState.LOADING) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                    if (models.isEmpty()) InfoNote("No model installed.")
                    models.forEach { m ->
                        val active = s.activeModelFile == m.fileName
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(m.fileName + if (active) "  (active)" else "", maxLines = 2)
                            InfoNote(formatBytes(m.sizeBytes))
                            Row {
                                TextButton(onClick = { vm.useModel(m.fileName) }) { Text("Use and load") }
                                TextButton(onClick = { vm.deleteModel(m.fileName) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                    InfoNote("Model storage used: ${formatBytes(models.sumOf { it.sizeBytes })}")

                    SectionTitle("Add a model")
                    OrbitOutlinedButton("Import from phone storage", { pickModel.launch(arrayOf("*/*")) })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(modelUrl, { modelUrl = it }, Modifier.fillMaxWidth(), label = { Text("Or download from an https:// URL") }, singleLine = true)
                    OutlinedTextField(modelName, { modelName = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Save as (e.g. model.litertlm)") }, singleLine = true)
                    OutlinedTextField(hfToken, { hfToken = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Access token (only for gated models, optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    Spacer(Modifier.height(6.dp))
                    OrbitButton("Download", { vm.modelToken = hfToken; vm.downloadModel(modelUrl, modelName) }, enabled = modelUrl.startsWith("https://") && transfer !is TransferState.Running)
                    when (val t = transfer) {
                        is TransferState.Running -> {
                            Spacer(Modifier.height(6.dp))
                            Text(t.label)
                            if (t.totalBytes > 0) LinearProgressIndicator(progress = { t.doneBytes.toFloat() / t.totalBytes }, Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                            InfoNote(formatBytes(t.doneBytes) + if (t.totalBytes > 0) " of ${formatBytes(t.totalBytes)}" else "")
                            TextButton(onClick = { vm.cancelTransfer() }) { Text("Cancel") }
                        }
                        is TransferState.Failed -> {
                            Text(t.message, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { vm.dismissTransfer() }) { Text("Dismiss") }
                        }
                        is TransferState.Done -> {
                            Text("Saved ${t.fileName}. Tap \"Use and load\" above.", color = MaterialTheme.colorScheme.secondary)
                            TextButton(onClick = { vm.dismissTransfer() }) { Text("OK") }
                        }
                        TransferState.Idle -> {}
                    }
                }
            }

            // ---------- Voice ----------
            item {
                GlowCard(Modifier.fillMaxWidth()) {
                    Text("Voice", style = MaterialTheme.typography.titleMedium)
                    SwitchRow("Speak replies after voice input", s.speakReplies) { vm.set { setSpeakReplies(it) } }
                    SwitchRow("Continuous conversation (listen again after replying)", s.continuousConversation) { vm.set { setContinuous(it) } }
                    InfoNote("Speech recognition uses your phone's speech service. It works offline only if the language pack is installed in your phone's Google speech settings; otherwise it needs internet.")
                    Text("Speech speed: ${"%.2f".format(rate)}", Modifier.padding(top = 8.dp))
                    Slider(rate, { rate = it }, valueRange = 0.5f..1.6f, onValueChangeFinished = { vm.set { setSpeechRate(rate) } })
                    Text("Voice depth (pitch): ${"%.2f".format(pitch)}")
                    Slider(pitch, { pitch = it }, valueRange = 0.5f..1.5f, onValueChangeFinished = { vm.set { setSpeechPitch(pitch) } })
                    InfoNote("A truly deep male voice depends on the voices installed on your phone. Lower pitch helps, and you can pick a voice below.")
                    Text("Media volume: ${(volume * 100).toInt()}%")
                    Slider(volume, { volume = it; vm.setMediaVolume(it) })
                    Box {
                        OrbitOutlinedButton("Voice: " + (s.voiceName.ifBlank { "default" }), { vm.loadVoices(); voiceMenu = true })
                        DropdownMenu(voiceMenu, { voiceMenu = false }) {
                            DropdownMenuItem(text = { Text("Default") }, onClick = { vm.set { setVoiceName("") }; voiceMenu = false })
                            voices.forEach { v -> DropdownMenuItem(text = { Text(v) }, onClick = { vm.set { setVoiceName(v) }; voiceMenu = false }) }
                        }
                    }
                    if (voices.isEmpty()) InfoNote("No voices listed for this language yet. Install a voice in your phone's text-to-speech settings.")
                }
            }

            // ---------- Wake word ----------
            item {
                GlowCard(Modifier.fillMaxWidth()) {
                    Text("Wake word \"ORBIT\" (optional)", style = MaterialTheme.typography.titleMedium)
                    InfoNote(
                        "When on, ORBIT keeps the microphone open in the background to hear its name. A notification is always shown with a Stop button. " +
                            "Nothing is recorded or stored. This uses Android's normal speech service, not a dedicated low-power chip, so it uses noticeably more battery " +
                            "and may need internet. Android does not let apps open themselves from the background: when ORBIT hears its name while hidden, it shows a notification to tap. " +
                            "If you prefer, just use the mic button (push-to-talk)."
                    )
                    SwitchRow("Listen for the wake word", s.wakeWordEnabled) { on ->
                        if (on) {
                            if (Build.VERSION.SDK_INT >= 33 && !hasPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)) requestNotif()
                            if (hasPermission(ctx, Manifest.permission.RECORD_AUDIO)) enableWake() else requestMic()
                        } else {
                            vm.set { setWakeWord(false) }
                            WakeWordService.stop(ctx)
                        }
                    }
                }
            }

            // ---------- Privacy ----------
            item {
                GlowCard(Modifier.fillMaxWidth()) {
                    Text("Privacy and memory", style = MaterialTheme.typography.titleMedium)
                    InfoNote(
                        "Stored on this phone only: chat history, memories, settings, automation history. " +
                            "Sent to your online AI provider (through your proxy) only when online AI answers: your message, recent chat, memories if enabled, " +
                            "and any image or document text you attach. Offline AI sends nothing anywhere."
                    )
                    SwitchRow("Save chat history", s.saveHistory) { vm.set { setSaveHistory(it) } }
                    SwitchRow("Use memory in AI prompts", s.useMemoryInPrompts) { vm.set { setUseMemory(it) } }
                    OutlinedTextField(instructions, { instructions = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Custom instructions for ORBIT") }, maxLines = 4)
                    TextButton(onClick = { vm.set { setCustomInstructions(instructions) } }) { Text("Save instructions") }
                    Row {
                        TextButton(onClick = openMemory) { Text("Review and edit memory") }
                    }
                    Row {
                        TextButton(onClick = { confirmChats = true }) { Text("Delete all chats", color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = { confirmMem = true }) { Text("Delete all memory", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }

            // ---------- Notifications / permissions ----------
            item {
                GlowCard(Modifier.fillMaxWidth()) {
                    Text("Notifications and permissions", style = MaterialTheme.typography.titleMedium)
                    InfoNote("Microphone: voice input. Notifications: reminders, timers, wake-word status. Media: scanning photos, videos and audio. Usage access: app usage stats (optional). Folders: only ones you pick. ORBIT asks only when you use the related feature.")
                    Row {
                        TextButton(onClick = {
                            ctx.startActivitySafe(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }) { Text("Notification settings") }
                        TextButton(onClick = {
                            ctx.startActivitySafe(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }) { Text("App permissions") }
                    }
                }
            }

            // ---------- About ----------
            item {
                val version = remember {
                    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
                }
                GlowCard(Modifier.fillMaxWidth()) {
                    Text("About ORBIT AI", style = MaterialTheme.typography.titleMedium)
                    Text("Version $version")
                    InfoNote("A personal AI assistant inspired by JARVIS. Not affiliated with Marvel or Disney. AI answers can be wrong; check important information yourself.")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmChats) ConfirmDialog("Delete all chats?", "All saved conversations will be removed from this phone.", "Delete", { vm.deleteAllChats(); confirmChats = false }, { confirmChats = false })
    if (confirmMem) ConfirmDialog("Delete all memory?", "Everything ORBIT remembers about you will be removed.", "Delete", { vm.deleteAllMemory(); confirmMem = false }, { confirmMem = false })
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}
