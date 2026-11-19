package com.orbit.ai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.orbit.ai.database.MemoryEntity
import com.orbit.ai.ui.components.*
import com.orbit.ai.viewmodel.MemoryViewModel
import com.orbit.ai.viewmodel.orbitViewModel

@Composable
fun MemoryScreen(onBack: () -> Unit) {
    val vm = orbitViewModel { MemoryViewModel(it) }
    val list by vm.memories.collectAsState(initial = emptyList())
    val settings by vm.settings.collectAsState()
    var newText by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MemoryEntity?>(null) }
    var confirmAll by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        OrbitTopBar("Memory", onBack)
        LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                GlowCard(Modifier.fillMaxWidth()) {
                    Text("What this is", style = MaterialTheme.typography.titleMedium)
                    InfoNote(
                        "ORBIT only remembers what you approve: text you add here, a message you save with the bookmark button " +
                            "and confirm, or something you tell it with \"remember that…\". Nothing is saved silently. " +
                            "Memories are stored on this phone. When the switch below is on, they are also sent with your " +
                            "requests to the online AI provider so it can use them. Offline AI keeps them on the phone."
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Use memory in AI prompts", Modifier.weight(1f))
                        Switch(settings.useMemoryInPrompts, { vm.setUseInPrompts(it) })
                    }
                    InfoNote("Avoid saving passwords, card numbers or other secrets.")
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newText, { newText = it }, Modifier.weight(1f), placeholder = { Text("Add something to remember") }, maxLines = 3)
                    Spacer(Modifier.width(8.dp))
                    OrbitButton("Save", { vm.add(newText); newText = "" }, enabled = newText.isNotBlank())
                }
            }
            if (list.isEmpty()) item { EmptyState(Icons.Filled.Psychology, "No memories yet.") }
            items(list, key = { it.id }) { m ->
                GlowCard(Modifier.fillMaxWidth()) {
                    Text(m.text)
                    Row {
                        TextButton(onClick = { editing = m }) { Icon(Icons.Filled.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Edit") }
                        TextButton(onClick = { vm.delete(m) }) { Icon(Icons.Filled.Delete, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Delete") }
                    }
                }
            }
            if (list.isNotEmpty()) item { TextButton(onClick = { confirmAll = true }) { Text("Delete all memories", color = MaterialTheme.colorScheme.error) } }
        }
    }

    editing?.let { m ->
        var t by remember(m) { mutableStateOf(m.text) }
        AlertDialog(
            onDismissRequest = { editing = null }, title = { Text("Edit memory") },
            text = { OutlinedTextField(t, { t = it }) },
            confirmButton = { TextButton(onClick = { vm.edit(m, t); editing = null }, enabled = t.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } }
        )
    }
    if (confirmAll) ConfirmDialog("Delete all memories?", "This cannot be undone.", "Delete all", { vm.deleteAll(); confirmAll = false }, { confirmAll = false })
}
