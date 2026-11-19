package com.orbit.ai.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.orbit.ai.AppContainer
import com.orbit.ai.files.FileCategory
import com.orbit.ai.files.FileItem
import com.orbit.ai.files.formatBytes
import com.orbit.ai.ui.components.*
import com.orbit.ai.viewmodel.FileSort
import com.orbit.ai.viewmodel.FilesViewModel
import com.orbit.ai.viewmodel.orbitViewModel
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilesScreen(organize: Boolean, onBack: () -> Unit) {
    val vm = orbitViewModel { FilesViewModel(it) }
    val ui by vm.ui.collectAsState()
    val ctx = LocalContext.current
    var hasMedia by remember { mutableStateOf(mediaPermissions().all { hasPermission(ctx, it) }) }
    var menuFor by remember { mutableStateOf<FileItem?>(null) }
    var renameFor by remember { mutableStateOf<FileItem?>(null) }
    var copyFor by remember { mutableStateOf<FileItem?>(null) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    val requestMedia = rememberPermissionsRequest(mediaPermissions()) { ok -> hasMedia = ok; vm.refresh() }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::addFolder) }
    val pickCopyTarget = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val f = copyFor
        if (uri != null && f != null) vm.copyTo(f, uri)
        copyFor = null
    }

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(organize) { if (organize) vm.startOrganize() }
    LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it); vm.message(null) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            OrbitTopBar("Files", onBack, actions = {
                if (ui.canUndo) TextButton(onClick = { vm.undo() }) { Text("Undo move") }
                IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "Rescan") }
            })
        },
        modifier = Modifier.statusBarsPadding().navigationBarsPadding()
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
            // ---- access ----
            item {
                GlowCard(Modifier.fillMaxWidth()) {
                    Text("Access", style = MaterialTheme.typography.titleMedium)
                    InfoNote(
                        "Photos, videos and music come from the media library (needs permission). " +
                            "Android does not let apps see other files on their own, so for PDFs, documents, APKs and archives " +
                            "add the folders you want ORBIT to manage, such as Downloads."
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!hasMedia) OrbitButton("Allow media access", { requestMedia() })
                        OrbitOutlinedButton("Add folder", { pickFolder.launch(null) })
                        if (ui.selection.isNotEmpty()) OrbitOutlinedButton("Move ${ui.selection.size} selected…", { showMoveDialog = true })
                        if (ui.folders.isNotEmpty()) OrbitOutlinedButton("Organise downloads", { vm.startOrganize() })
                        if (ui.folders.isNotEmpty()) OrbitOutlinedButton("New folder", { showFolderDialog = true })
                    }
                    ui.folders.forEach { f ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                            Text(
                                " " + android.net.Uri.decode(f.lastPathSegment ?: f.toString()), Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium, maxLines = 1
                            )
                            IconButton(onClick = { vm.removeFolder(f) }) { Icon(Icons.Filled.Close, "Remove folder") }
                        }
                    }
                }
            }

            // ---- analysis ----
            if (ui.items.isNotEmpty()) item {
                GlowCard(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Text("Storage analysis", style = MaterialTheme.typography.titleMedium)
                    val total = ui.items.sumOf { it.sizeBytes }.coerceAtLeast(1)
                    Text("${ui.items.size} files, ${formatBytes(total)} indexed", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    FileCategory.entries.forEach { cat ->
                        val bytes = ui.bytesByCategory[cat] ?: 0L
                        if (bytes > 0) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(cat.label, Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium)
                                LinearProgressIndicator(progress = { bytes.toFloat() / total }, Modifier.weight(1f).height(6.dp))
                                Text(" ${ui.countByCategory[cat]} · ${formatBytes(bytes)}", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    OrbitOutlinedButton("Find duplicates", { vm.findDuplicates() })
                }
            }

            // ---- filters ----
            item {
                OutlinedTextField(
                    ui.query, vm::setQuery, Modifier.fillMaxWidth().padding(top = 10.dp),
                    placeholder = { Text("Search files") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) }
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(ui.category == null && !ui.downloadsOnly, { vm.setCategory(null) }, { Text("All") })
                    FilterChip(ui.downloadsOnly, { vm.setDownloadsOnly(!ui.downloadsOnly) }, { Text("Downloads") })
                    FileCategory.entries.forEach { cat ->
                        FilterChip(ui.category == cat, { vm.setCategory(if (ui.category == cat) null else cat) }, { Text(cat.label) })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FileSort.entries.forEach { s -> FilterChip(ui.sort == s, { vm.setSort(s) }, { Text(s.label) }) }
                    FilterChip(ui.minSizeMb > 0, { vm.setMinSize(if (ui.minSizeMb > 0) 0 else 100) }, { Text("Over 100 MB") })
                }
                if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
            }

            // ---- list ----
            val visible = ui.visible
            if (visible.isEmpty() && !ui.loading) item {
                EmptyState(Icons.Filled.FolderOff, if (ui.items.isEmpty()) "No files yet. Allow media access and add a folder above." else "No files match these filters.")
            }
            items(visible.take(300), key = { it.uri.toString() }) { f ->
                val selected = f.uri in ui.selection
                ListItem(
                    headlineContent = { Text(f.name, maxLines = 1) },
                    supportingContent = {
                        Text("${f.category.label} · ${formatBytes(f.sizeBytes)} · " +
                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(f.modifiedMs)))
                    },
                    leadingContent = {
                        if (f.editable) Checkbox(selected, { vm.toggleSelect(f) })
                        else Icon(iconFor(f.category), null, tint = MaterialTheme.colorScheme.secondary)
                    },
                    trailingContent = { IconButton(onClick = { menuFor = f }) { Icon(Icons.Filled.MoreVert, "Actions") } },
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { ctx.startActivitySafe(vm.viewIntent(f)) },
                    colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
                )
            }
            if (visible.size > 300) item { InfoNote("Showing the first 300 of ${visible.size}. Use search or filters to narrow down.") }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // ---- per-file menu ----
    menuFor?.let { f ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(f.name, maxLines = 2) },
            text = {
                Column {
                    TextButton(onClick = { ctx.startActivitySafe(vm.viewIntent(f)); menuFor = null }) { Text("Preview / open") }
                    TextButton(onClick = { ctx.startActivitySafe(vm.shareIntent(f)); menuFor = null }) { Text("Share") }
                    if (f.editable) {
                        TextButton(onClick = { renameFor = f; menuFor = null }) { Text("Rename") }
                        TextButton(onClick = { copyFor = f; menuFor = null; pickCopyTarget.launch(null) }) { Text("Copy to folder…") }
                    } else InfoNote("Rename, move and copy need the file to be inside a folder you added.")
                }
            },
            confirmButton = { TextButton(onClick = { menuFor = null }) { Text("Close") } }
        )
    }

    renameFor?.let { f ->
        var name by remember(f) { mutableStateOf(f.name) }
        AlertDialog(
            onDismissRequest = { renameFor = null }, title = { Text("Rename") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(f, name); renameFor = null }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("Cancel") } }
        )
    }

    if (showMoveDialog) {
        var folder by remember { mutableStateOf("Study") }
        AlertDialog(
            onDismissRequest = { showMoveDialog = false }, title = { Text("Move selected files") },
            text = {
                Column {
                    Text("Folder name (created if needed). You will see a preview before anything moves.")
                    OutlinedTextField(folder, { folder = it }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { vm.previewMoveSelected(folder); showMoveDialog = false }) { Text("Preview") } },
            dismissButton = { TextButton(onClick = { showMoveDialog = false }) { Text("Cancel") } }
        )
    }

    if (showFolderDialog) {
        var folder by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showFolderDialog = false }, title = { Text("New folder") },
            text = { OutlinedTextField(folder, { folder = it }, singleLine = true, placeholder = { Text("Folder name") }) },
            confirmButton = { TextButton(onClick = { vm.newFolder(folder); showFolderDialog = false }, enabled = folder.isNotBlank()) { Text("Create") } },
            dismissButton = { TextButton(onClick = { showFolderDialog = false }) { Text("Cancel") } }
        )
    }

    // ---- preview + confirmation of a move plan ----
    ui.plan?.let { plan ->
        val grouped = plan.groupBy { it.targetFolder }
        AlertDialog(
            onDismissRequest = { vm.cancelPlan() },
            title = { Text("Preview: move ${plan.size} files") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    grouped.forEach { (folder, entries) ->
                        item { Text("→ $folder (${entries.size})", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary) }
                        items(entries.take(8)) { e -> Text("• ${e.file.name}", style = MaterialTheme.typography.bodyMedium, maxLines = 1) }
                        if (entries.size > 8) item { InfoNote("…and ${entries.size - 8} more") }
                    }
                    item { InfoNote("Files are only moved, never deleted. You can undo the last batch.") }
                }
            },
            confirmButton = { TextButton(onClick = { vm.confirmPlan() }) { Text("Move files") } },
            dismissButton = { TextButton(onClick = { vm.cancelPlan() }) { Text("Cancel") } }
        )
    }

    // ---- duplicates ----
    ui.duplicates?.takeIf { it.isNotEmpty() }?.let { groups ->
        AlertDialog(
            onDismissRequest = { vm.closeDuplicates() },
            title = { Text("${groups.size} duplicate sets") },
            text = {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(groups.take(40)) { g ->
                        Text("${formatBytes(g.sizeBytes)} × ${g.files.size}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
                        g.files.forEach { f -> Text("• ${f.name}", maxLines = 1, style = MaterialTheme.typography.bodyMedium) }
                        Spacer(Modifier.height(8.dp))
                    }
                    item { InfoNote("ORBIT never deletes files. Review these in your gallery or file app.") }
                }
            },
            confirmButton = { TextButton(onClick = { vm.closeDuplicates() }) { Text("Close") } }
        )
    }
}

private fun iconFor(c: FileCategory) = when (c) {
    FileCategory.PHOTOS -> Icons.Filled.Image
    FileCategory.VIDEOS -> Icons.Filled.Movie
    FileCategory.MUSIC, FileCategory.AUDIO -> Icons.Filled.MusicNote
    FileCategory.PDF -> Icons.Filled.PictureAsPdf
    FileCategory.DOCUMENTS -> Icons.Filled.Description
    FileCategory.APK -> Icons.Filled.Android
    FileCategory.ARCHIVES -> Icons.Filled.FolderZip
    FileCategory.OTHER -> Icons.Filled.InsertDriveFile
}

fun android.content.Context.startActivitySafe(intent: android.content.Intent) {
    try { startActivity(intent) } catch (e: Exception) {
        android.widget.Toast.makeText(this, "No app can open this: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}
