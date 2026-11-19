package com.orbit.ai.ui.screens

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.orbit.ai.productivity.LOCAL_SEARCH_LABEL
import com.orbit.ai.productivity.ToolKind
import com.orbit.ai.ui.components.*
import com.orbit.ai.viewmodel.ProductivityViewModel
import com.orbit.ai.viewmodel.orbitViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProductivityScreen(onBack: () -> Unit) {
    val vm = orbitViewModel { ProductivityViewModel(it) }
    val ui by vm.ui.collectAsState()
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current

    fun nameOf(uri: android.net.Uri): String =
        ctx.contentResolver.query(uri, null, null, null, null)?.use {
            val i = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && it.moveToFirst()) it.getString(i) else null
        } ?: "file"

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { vm.attachPdf(it, nameOf(it)) } }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> u?.let { vm.attachImage(it, nameOf(it)) } }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        OrbitTopBar("Productivity", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ToolKind.entries.forEach { t ->
                    FilterChip(ui.tool == t, { vm.selectTool(t) }, { Text(t.title) })
                }
            }
            Spacer(Modifier.height(8.dp))
            InfoNote(ui.tool.note)

            if (!ui.tool.implemented) {
                Spacer(Modifier.height(12.dp))
                GlowCard(Modifier.fillMaxWidth()) {
                    Text("Not implemented yet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    InfoNote("Web Intelligence needs a search provider added to the backend. Until then ORBIT does not search the web, and its answers come from the AI model's own knowledge, which may be out of date.")
                }
                return@Column
            }

            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ui.tool.actions.forEach { a ->
                    FilterChip(ui.action == a, { vm.selectAction(a) }, { Text(a.label) })
                }
            }

            if (ui.tool == ToolKind.PDF) {
                Spacer(Modifier.height(8.dp))
                OrbitOutlinedButton("Choose PDF", { pickPdf.launch(arrayOf("application/pdf")) })
            }
            if (ui.tool == ToolKind.IMAGE) {
                Spacer(Modifier.height(8.dp))
                OrbitOutlinedButton("Choose image", { pickImage.launch("image/*") })
            }
            ui.attachment?.let { InfoNote("Attached: $it") }

            val inputHint = when {
                ui.action?.label == LOCAL_SEARCH_LABEL -> "Word or phrase to find"
                ui.tool == ToolKind.PDF -> "Your question or the passage (optional for summary)"
                ui.tool == ToolKind.IMAGE -> "Your question about the image (optional)"
                ui.tool == ToolKind.CODE -> "Paste code or the error message"
                else -> "Type or paste here"
            }
            OutlinedTextField(
                ui.input, vm::setInput, Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 120.dp),
                placeholder = { Text(inputHint) }
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrbitButton(if (ui.busy) "Working…" else "Run", { vm.run() }, enabled = !ui.busy)
            }
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }

            if (ui.result.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                GlowCard(Modifier.fillMaxWidth()) {
                    Row {
                        Text("Result", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { clipboard.setText(AnnotatedString(ui.result)) }) { Icon(Icons.Filled.ContentCopy, "Copy result") }
                    }
                    if (ui.engineNote.isNotBlank()) InfoNote(ui.engineNote)
                    Spacer(Modifier.height(6.dp))
                    MarkdownText(ui.result)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
