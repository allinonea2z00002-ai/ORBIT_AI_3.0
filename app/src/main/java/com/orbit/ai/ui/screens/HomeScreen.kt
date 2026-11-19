package com.orbit.ai.ui.screens

import android.Manifest
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.orbit.ai.settings.AiMode
import com.orbit.ai.ui.components.*
import com.orbit.ai.ui.theme.LocalOrbitColors
import com.orbit.ai.ui.theme.OrbitRed
import com.orbit.ai.viewmodel.ChatViewModel
import com.orbit.ai.voice.VoiceState
import com.orbit.ai.voice.WakeBus

@Composable
fun HomeScreen(vm: ChatViewModel, wake: WakeBus, go: (String) -> Unit) {
    val orb by vm.orbState.collectAsState()
    val level by vm.micLevel.collectAsState()
    val last by vm.lastReply.collectAsState()
    val partial by vm.partial.collectAsState()
    val voice by vm.voiceState.collectAsState()
    val settings by vm.settings.collectAsState()
    val error by vm.error.collectAsState()
    var text by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val colors = LocalOrbitColors.current

    val requestMic = rememberPermissionRequest(Manifest.permission.RECORD_AUDIO) { granted ->
        if (granted) vm.startVoice() else vm.setError("Microphone permission is needed for voice. You can still type.")
    }
    val onMic: () -> Unit = {
        if (hasPermission(ctx, Manifest.permission.RECORD_AUDIO)) vm.toggleMic() else requestMic()
    }

    // Wake word: only act while the app is visible (Android blocks microphone use from the background).
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (wake.consumePending() && hasPermission(ctx, Manifest.permission.RECORD_AUDIO)) vm.startVoice()
            wake.events.collect {
                wake.consumePending()
                if (hasPermission(ctx, Manifest.permission.RECORD_AUDIO)) vm.startVoice()
            }
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))
        Text("ORBIT AI", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)

        Spacer(Modifier.height(8.dp))
        val (statusText, statusColor) = when (orb) {
            OrbState.LISTENING -> "Listening" to MaterialTheme.colorScheme.secondary
            OrbState.PROCESSING -> "Thinking" to MaterialTheme.colorScheme.primary
            OrbState.SPEAKING -> "Speaking" to MaterialTheme.colorScheme.primary
            OrbState.OFFLINE -> "Offline" to colors.softText
            OrbState.ERROR -> "Error" to OrbitRed
            OrbState.IDLE -> "Online" to colors.success
        }
        StatusChip(statusText, statusColor)

        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AiMode.entries.forEach { m ->
                FilterChip(
                    selected = settings.aiMode == m, onClick = { vm.setAiMode(m) },
                    label = { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) }
                )
            }
        }

        OrbView(orb, level, Modifier.fillMaxWidth(0.72f))

        val caption = when {
            voice == VoiceState.LISTENING && partial.isNotBlank() -> partial
            error != null -> error!!
            last.isNotBlank() -> last
            else -> "Tap the mic or type a command."
        }
        Text(
            caption.take(280), textAlign = TextAlign.Center, maxLines = 5,
            color = if (error != null && voice != VoiceState.LISTENING) OrbitRed else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.heightIn(min = 60.dp).padding(horizontal = 8.dp)
        )
        if (voice == VoiceState.SPEAKING) {
            OrbitOutlinedButton("Stop speaking", { vm.stopSpeaking() })
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Ask ORBIT or give a command") }, singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
            )
            IconButton(
                onClick = { if (text.isNotBlank()) { vm.send(text); text = ""; go("chat") } },
                enabled = text.isNotBlank()
            ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            FilledIconButton(onClick = onMic, shape = CircleShape, modifier = Modifier.size(52.dp)) {
                Icon(if (voice == VoiceState.LISTENING) Icons.Filled.Stop else Icons.Filled.Mic, "Voice input")
            }
        }

        Spacer(Modifier.height(14.dp))
        val items = listOf(
            Shortcut("Conversation", Icons.Filled.Forum, "chat"),
            Shortcut("Files", Icons.Filled.Folder, "files"),
            Shortcut("Phone", Icons.Filled.PhoneAndroid, "intel"),
            Shortcut("Automation", Icons.Filled.Bolt, "automation"),
            Shortcut("Productivity", Icons.Filled.School, "productivity"),
            Shortcut("Settings", Icons.Filled.Settings, "settings")
        )
        items.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { s ->
                    GlowCard(Modifier.weight(1f), onClick = { go(s.route) }) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(s.icon, null, tint = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.height(4.dp))
                            Text(s.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private data class Shortcut(val label: String, val icon: ImageVector, val route: String)
