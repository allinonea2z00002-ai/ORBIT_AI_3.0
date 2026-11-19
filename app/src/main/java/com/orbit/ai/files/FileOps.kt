package com.orbit.ai.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * File operations on documents the user authorised through the folder picker.
 *
 * Safety rules built in:
 *  - There is deliberately NO delete function. Organising only moves, copies or renames.
 *  - Name clashes never overwrite: a numeric suffix is added to copies.
 *  - Moves are remembered for the session so the last batch can be undone.
 *
 * All "folder" parameters are document URIs inside a user-picked tree
 * (see [rootDocumentUri]).
 */
class FileOps(private val context: Context) {

    private val resolver get() = context.contentResolver

    /** Last executed batch, kept in memory only (undo is lost when the app is closed). */
    var lastBatch: List<MoveRecord> = emptyList()
        private set

    /** Turns the URI returned by the folder picker into the document URI of the picked folder itself. */
    fun rootDocumentUri(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private data class Child(val name: String, val uri: Uri, val isDir: Boolean)

    private fun listChildren(folder: Uri): List<Child> {
        val tree = DocumentsContract.buildTreeDocumentUri(folder.authority, DocumentsContract.getTreeDocumentId(folder))
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(folder))
        val out = mutableListOf<Child>()
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ), null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                out += Child(
                    c.getString(1) ?: "",
                    DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)),
                    c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                )
            }
        }
        return out
    }

    private fun findFolder(parent: Uri, name: String): Uri? =
        runCatching { listChildren(parent).firstOrNull { it.isDir && it.name == name }?.uri }.getOrNull()

    /** Finds a sub-folder or creates it. */
    fun ensureFolder(parent: Uri, name: String): Uri? {
        val clean = cleanName(name)
        findFolder(parent, clean)?.let { return it }
        runCatching {
            DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, clean)
        }.getOrNull() ?: return null
        return findFolder(parent, clean)
    }

    fun rename(file: Uri, newName: String): Uri? =
        runCatching { DocumentsContract.renameDocument(resolver, file, cleanName(newName)) }.getOrNull()

    /** Copy [src] into [targetFolder]; the original stays where it is. */
    suspend fun copy(src: FileItem, targetFolder: Uri): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val name = uniqueName(targetFolder, src.name)
            val dst = DocumentsContract.createDocument(
                resolver, targetFolder, src.mime ?: "application/octet-stream", name
            ) ?: error("Could not create the copy")
            resolver.openInputStream(src.uri)!!.use { i -> resolver.openOutputStream(dst)!!.use { o -> i.copyTo(o) } }
            dst
        }
    }

    /**
     * Executes a reviewed plan: files are moved into sub-folders of [root] named after their category.
     * Files that cannot be moved are left untouched and counted in the summary.
     */
    suspend fun executePlan(root: Uri, plan: List<MovePlanEntry>): String = withContext(Dispatchers.IO) {
        val done = mutableListOf<MoveRecord>()
        var skipped = 0
        val folderCache = mutableMapOf<String, Uri?>()
        for (entry in plan) {
            val f = entry.file
            val oldParent = f.parentUri
            if (!f.editable || oldParent == null) { skipped++; continue }
            val target = folderCache.getOrPut(entry.targetFolder) { ensureFolder(root, entry.targetFolder) }
            if (target == null || sameDoc(target, oldParent)) { skipped++; continue }
            val moved = runCatching { DocumentsContract.moveDocument(resolver, f.uri, oldParent, target) }.getOrNull()
            if (moved != null) done += MoveRecord(moved, target, oldParent, f.name) else skipped++
        }
        lastBatch = done
        buildString {
            append("Moved ${done.size} file(s).")
            if (skipped > 0) append(" $skipped left untouched (already in place or not movable).")
            if (done.isNotEmpty()) append(" You can undo this batch.")
        }
    }

    /** Moves the last batch back to where each file came from. */
    suspend fun undoLastBatch(): String = withContext(Dispatchers.IO) {
        if (lastBatch.isEmpty()) return@withContext "Nothing to undo."
        var ok = 0
        val total = lastBatch.size
        lastBatch.forEach { r ->
            val back = runCatching {
                DocumentsContract.moveDocument(resolver, r.movedUri, r.newParent, r.oldParent)
            }.getOrNull()
            if (back != null) ok++
        }
        lastBatch = emptyList()
        "Restored $ok of $total file(s)."
    }

    /** Only loose files sitting directly in [root] are proposed, so nested folders are never reshuffled. */
    fun buildOrganizePlan(root: Uri, files: List<FileItem>): List<MovePlanEntry> =
        files.filter { it.editable && it.parentUri != null && sameDoc(it.parentUri, root) }
            .map { MovePlanEntry(it, it.category.folderName) }

    fun shareIntent(item: FileItem): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = item.mime ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, item.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share ${item.name}"
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Preview = open the file in the phone's own viewer app. */
    fun viewIntent(item: FileItem): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, item.mime ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun sameDoc(a: Uri, b: Uri): Boolean =
        runCatching { DocumentsContract.getDocumentId(a) == DocumentsContract.getDocumentId(b) }.getOrDefault(false)

    private fun uniqueName(folder: Uri, name: String): String {
        val existing = runCatching { listChildren(folder).map { it.name }.toSet() }.getOrDefault(emptySet())
        if (name !in existing) return name
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (true) {
            val candidate = if (ext.isEmpty() || base == name) "$base ($i)" else "$base ($i).$ext"
            if (candidate !in existing) return candidate
            i++
        }
    }

    private fun cleanName(n: String) = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Untitled" }
}
