package com.orbit.ai.viewmodel

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orbit.ai.AppContainer
import com.orbit.ai.files.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FileSort(val label: String) { DATE("Newest"), SIZE("Largest"), NAME("Name") }

data class FilesUi(
    val items: List<FileItem> = emptyList(),
    val loading: Boolean = false,
    val category: FileCategory? = null,
    val downloadsOnly: Boolean = false,
    val minSizeMb: Int = 0,
    val sort: FileSort = FileSort.DATE,
    val query: String = "",
    val folders: List<Uri> = emptyList(),
    val message: String? = null,
    val duplicates: List<DuplicateGroup>? = null,
    /** Reviewed-before-moving plan. Nothing moves until the user confirms it. */
    val plan: List<MovePlanEntry>? = null,
    val planRoot: Uri? = null,
    val selection: Set<Uri> = emptySet(),
    val canUndo: Boolean = false
) {
    val visible: List<FileItem>
        get() {
            var l = items
            category?.let { c -> l = l.filter { it.category == c } }
            if (downloadsOnly) l = l.filter { it.inDownloads }
            if (minSizeMb > 0) l = l.filter { it.sizeBytes >= minSizeMb * 1_048_576L }
            if (query.isNotBlank()) l = l.filter { it.name.contains(query.trim(), ignoreCase = true) }
            return when (sort) {
                FileSort.DATE -> l.sortedByDescending { it.modifiedMs }
                FileSort.SIZE -> l.sortedByDescending { it.sizeBytes }
                FileSort.NAME -> l.sortedBy { it.name.lowercase() }
            }
        }

    val bytesByCategory: Map<FileCategory, Long>
        get() = items.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.sizeBytes } }

    val countByCategory: Map<FileCategory, Int>
        get() = items.groupingBy { it.category }.eachCount()
}

class FilesViewModel(private val c: AppContainer) : ViewModel() {

    private val _ui = MutableStateFlow(FilesUi(folders = c.fileScanner.savedFolders()))
    val ui: StateFlow<FilesUi> = _ui.asStateFlow()

    fun refresh() { viewModelScope.launch { load() } }

    private suspend fun load() {
        _ui.update { it.copy(loading = true, folders = c.fileScanner.savedFolders()) }
        val items = c.fileScanner.scanAll()
        _ui.update { it.copy(items = items, loading = false, duplicates = null) }
    }

    fun message(text: String?) = _ui.update { it.copy(message = text) }

    // ---- folders the user authorises ----

    fun addFolder(tree: Uri) {
        runCatching {
            c.context.contentResolver.takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        c.fileScanner.saveFolder(tree)
        refresh()
    }

    fun removeFolder(tree: Uri) {
        c.fileScanner.forgetFolder(tree)
        runCatching {
            c.context.contentResolver.releasePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        refresh()
    }

    // ---- filters ----

    fun setCategory(cat: FileCategory?) = _ui.update { it.copy(category = cat, downloadsOnly = false) }
    fun setDownloadsOnly(v: Boolean) = _ui.update { it.copy(downloadsOnly = v, category = null) }
    fun setSort(s: FileSort) = _ui.update { it.copy(sort = s) }
    fun setQuery(q: String) = _ui.update { it.copy(query = q) }
    fun setMinSize(mb: Int) = _ui.update { it.copy(minSizeMb = mb) }

    fun findDuplicates() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            val groups = c.fileScanner.findDuplicates(_ui.value.items)
            _ui.update { it.copy(duplicates = groups, loading = false, message = if (groups.isEmpty()) "No duplicates found." else null) }
        }
    }

    fun closeDuplicates() = _ui.update { it.copy(duplicates = null) }

    // ---- selection ----

    fun toggleSelect(item: FileItem) = _ui.update {
        it.copy(selection = if (item.uri in it.selection) it.selection - item.uri else it.selection + item.uri)
    }

    fun clearSelection() = _ui.update { it.copy(selection = emptySet()) }

    // ---- organise (always previewed, always confirmed) ----

    private fun rootFor(item: FileItem): Uri? {
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(item.uri) }.getOrNull() ?: return null
        return _ui.value.folders.firstOrNull { runCatching { DocumentsContract.getTreeDocumentId(it) }.getOrNull() == treeId }
    }

    /** "Organize my downloads": sorts loose files in the chosen folder into category sub-folders. */
    fun startOrganize() {
        viewModelScope.launch {
            if (_ui.value.items.isEmpty()) load()
            val folders = _ui.value.folders
            if (folders.isEmpty()) {
                message("Choose your Downloads folder first (Add folder), then try again.")
                return@launch
            }
            val root = folders.firstOrNull { it.toString().contains("Download", ignoreCase = true) } ?: folders.first()
            val rootDoc = c.fileOps.rootDocumentUri(root)
            val plan = c.fileOps.buildOrganizePlan(rootDoc, _ui.value.items)
            if (plan.isEmpty()) message("Nothing to organise: no loose files directly inside that folder.")
            else _ui.update { it.copy(plan = plan, planRoot = rootDoc) }
        }
    }

    /** "Move selected files into a folder named X". */
    fun previewMoveSelected(folderName: String) {
        val name = folderName.trim()
        if (name.isEmpty()) { message("Type a folder name."); return }
        val chosen = _ui.value.items.filter { it.uri in _ui.value.selection }
        val movable = chosen.filter { it.editable }
        if (movable.isEmpty()) { message("Only files from folders you added can be moved."); return }
        val roots = movable.mapNotNull { rootFor(it) }.distinct()
        if (roots.size != 1) { message("Select files from one added folder at a time."); return }
        _ui.update {
            it.copy(plan = movable.map { f -> MovePlanEntry(f, name) }, planRoot = c.fileOps.rootDocumentUri(roots.first()))
        }
    }

    fun cancelPlan() = _ui.update { it.copy(plan = null, planRoot = null) }

    fun confirmPlan() {
        val plan = _ui.value.plan ?: return
        val root = _ui.value.planRoot ?: return
        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            val summary = c.fileOps.executePlan(root, plan)
            _ui.update { it.copy(plan = null, planRoot = null, selection = emptySet(), message = summary, canUndo = c.fileOps.lastBatch.isNotEmpty()) }
            load()
        }
    }

    fun undo() {
        viewModelScope.launch {
            val msg = c.fileOps.undoLastBatch()
            _ui.update { it.copy(message = msg, canUndo = false) }
            load()
        }
    }

    // ---- single-file actions ----

    fun rename(item: FileItem, newName: String) {
        if (!item.editable) { message("Only files from folders you added can be renamed."); return }
        val ok = c.fileOps.rename(item.uri, newName) != null
        message(if (ok) "Renamed." else "Could not rename that file.")
        if (ok) refresh()
    }

    fun copyTo(item: FileItem, destTree: Uri) {
        runCatching {
            c.context.contentResolver.takePersistableUriPermission(
                destTree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        viewModelScope.launch {
            val r = c.fileOps.copy(item, c.fileOps.rootDocumentUri(destTree))
            message(if (r.isSuccess) "Copied. The original is unchanged." else "Copy failed: ${r.exceptionOrNull()?.message}")
        }
    }

    fun newFolder(name: String) {
        val root = _ui.value.folders.firstOrNull()?.let { c.fileOps.rootDocumentUri(it) }
        if (root == null) { message("Add a folder first."); return }
        message(if (c.fileOps.ensureFolder(root, name) != null) "Folder \"$name\" is ready." else "Could not create the folder.")
    }

    fun shareIntent(item: FileItem) = c.fileOps.shareIntent(item)
    fun viewIntent(item: FileItem) = c.fileOps.viewIntent(item)
}
