package com.orbit.ai.ui.screens

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.orbit.ai.ui.components.*
import com.orbit.ai.viewmodel.AutomationViewModel
import com.orbit.ai.viewmodel.orbitViewModel
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AutomationScreen(onBack: () -> Unit) {
    val vm = orbitViewModel { AutomationViewModel(it) }
    val log by vm.log.collectAsState(initial = emptyList())
    val commands by vm.commands.collectAsState(initial = emptyList())
    val routines by vm.routines.collectAsState(initial = emptyList())
    val message by vm.message.collectAsState()
    val ctx = LocalContext.current
    var typed by remember { mutableStateOf("") }
    var showCommandDialog by remember { mutableStateOf(false) }
    var showRoutineDialog by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    val requestNotif = rememberPermissionRequest(Manifest.permission.POST_NOTIFICATIONS) { }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    // Ask for notification permission once, with an explanation, because reminders need it.
    var asked by remember { mutableStateOf(false) }
    val needsNotifPermission = Build.VERSION.SDK_INT >= 33 && !hasPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        topBar = { OrbitTopBar("Automation", onBack) },
        modifier = Modifier.statusBarsPadding().navigationBarsPadding()
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            if (needsNotifPermission && !asked) item {
                GlowCard(Modifier.fillMaxWidth()) {
                    Text("Notifications", style = MaterialTheme.typography.titleMedium)
                    InfoNote("Reminders, timers and routines show a notification. Android needs your permission for that.")
                    Spacer(Modifier.height(6.dp))
                    OrbitButton("Allow notifications", { asked = true; requestNotif() })
                }
            }

            item {
                GlowCard(Modifier.fillMaxWidth()) {
                    Text("Run a command", style = MaterialTheme.typography.titleMedium)
                    InfoNote("Same commands as in chat, e.g. \"open youtube\", \"timer 25 minutes\", \"turn on the flashlight\", \"remind me in 10 minutes to stretch\".")
                    OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth().padding(top = 6.dp), singleLine = true, placeholder = { Text("Type a command") })
                    Spacer(Modifier.height(6.dp))
                    OrbitButton("Run", { vm.run(typed); typed = "" }, enabled = typed.isNotBlank())
                }
            }

            item {
                GlowCard(Modifier.fillMaxWidth()) {
                    Text("Quick actions", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            "Flashlight on" to "turn on the flashlight",
                            "Flashlight off" to "turn off the flashlight",
                            "25 min focus" to "start a study timer for 25 minutes",
                            "45 min study" to "start a study timer for 45 minutes",
                            "Battery" to "show battery information",
                            "Storage" to "tell me my available storage",
                            "Wi-Fi settings" to "open wifi settings",
                            "Bluetooth settings" to "open bluetooth settings"
                        ).forEach { (label, cmd) -> AssistChip(onClick = { vm.run(cmd) }, label = { Text(label) }) }
                    }
                }
            }

            item {
                GlowCard(Modifier.fillMaxWidth()) {
                    Text("Daily routines", style = MaterialTheme.typography.titleMedium)
                    InfoNote("A notification at the same time every day. Android may delay it a little in battery saver.")
                    routines.forEach { r ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("%02d:%02d  %s".format(r.hour, r.minute, r.text), Modifier.weight(1f))
                            IconButton(onClick = { vm.deleteRoutine(r) }) { Icon(Icons.Filled.Delete, "Delete routine") }
                        }
                    }
                    OrbitOutlinedButton("Add routine", { showRoutineDialog = true })
                }
            }

            item {
                GlowCard(Modifier.fillMaxWidth()) {
                    Text("Custom voice commands", style = MaterialTheme.typography.titleMedium)
                    InfoNote("Say or type your own phrase to run a built-in command. Example: \"study mode\" runs \"start a study timer for 45 minutes\".")
                    commands.forEach { c ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text("“${c.phrase}”"); InfoNote("→ ${c.actionText}") }
                            IconButton(onClick = { vm.deleteCommand(c) }) { Icon(Icons.Filled.Delete, "Delete command") }
                        }
                    }
                    OrbitOutlinedButton("Add command", { showCommandDialog = true })
                }
            }

            item { SectionTitle("History") }
            if (log.isEmpty()) item { InfoNote("No automation history yet.") }
            items(log, key = { it.id }) { l ->
                ListItem(
                    headlineContent = { Text(l.command, maxLines = 1) },
                    supportingContent = { Text(l.result, maxLines = 2) },
                    overlineContent = { Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(l.createdAt)) + if (l.success) "" else " · failed") },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                )
            }
            if (log.isNotEmpty()) item { TextButton(onClick = { vm.clearLog() }) { Text("Clear history") } }
        }
    }

    if (showCommandDialog) {
        var phrase by remember { mutableStateOf("") }
        var action by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCommandDialog = false }, title = { Text("New command") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(phrase, { phrase = it }, label = { Text("When I say") }, singleLine = true)
                    OutlinedTextField(action, { action = it }, label = { Text("Run this command") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { vm.addCommand(phrase, action); showCommandDialog = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showCommandDialog = false }) { Text("Cancel") } }
        )
    }

    if (showRoutineDialog) {
        val time = rememberTimePickerState(8, 0, true)
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showRoutineDialog = false }, title = { Text("New daily routine") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TimePicker(time)
                    OutlinedTextField(text, { text = it }, label = { Text("Notification text") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { vm.addRoutine(time.hour, time.minute, text); showRoutineDialog = false }, enabled = text.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showRoutineDialog = false }) { Text("Cancel") } }
        )
    }
}
