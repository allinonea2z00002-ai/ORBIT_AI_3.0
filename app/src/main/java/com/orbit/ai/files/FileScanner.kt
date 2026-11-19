package com.orbit.ai.files

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finds files the app is allowed to see:
 *  1. Photos, videos and audio through MediaStore (needs the READ_MEDIA_* permissions).
 *  2. Everything else (PDF, documents, APK, archives...) inside folders the user picked with the
 *     system folder picker (Storage Access Framework). Android does not let apps list other
 *     apps' private files, and ORBIT does not pretend otherwise.
 */
class FileScanner(private val context: Context) {

    private val resolver get() = context.contentResolver
    private val prefs = context.getSharedPreferences("orbit_files", Context.MODE_PRIVATE)

    fun savedFolders(): List<Uri> =
        prefs.getStringSet("folders", emptySet())!!.map { Uri.parse(it) }

    fun saveFolder(tree: Uri) {
        val set = prefs.getStringSet("folders", emptySet())!!.toMutableSet()
        set += tree.toString()
        prefs.edit().putStringSet("folders", set).apply()
    }

    fun forgetFolder(tree: Uri) {
        val set = prefs.getStringSet("folders", emptySet())!!.toMutableSet()
        set -= tree.toString()
        prefs.edit().putStringSet("folders", set).apply()
    }

    /** Scan MediaStore plus all saved folders. */
    suspend fun scanAll(): List<FileItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<FileItem>()
        out += scanMedia()
        savedFolders().forEach { out += scanTree(it) }
        out.distinctBy { it.uri }
    }

    suspend fun scanMedia(): List<FileItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<FileItem>()
        out += queryCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, FileCategory.PHOTOS)
        out += queryCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, FileCategory.VIDEOS)
        out += queryCollection(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null)
        out
    }

    private fun queryCollection(collection: Uri, fixedCategory: FileCategory?): List<FileItem> {
        val isAudio = fixedCategory == null
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.RELATIVE_PATH)
            if (isAudio) add(MediaStore.Audio.AudioColumns.IS_MUSIC)
        }.toTypedArray()
        val out = mutableListOf<FileItem>()
        try {
            resolver.query(collection, projection, null, null, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val iPath = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val iMusic = if (isAudio) c.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.IS_MUSIC) else -1
                while (c.moveToNext()) {
                    val cat = fixedCategory
                        ?: if (c.getInt(iMusic) != 0) FileCategory.MUSIC else FileCategory.AUDIO
                    out += FileItem(
                        uri = ContentUris.withAppendedId(collection, c.getLong(iId)),
                        name = c.getString(iName) ?: "unnamed",
                        sizeBytes = c.getLong(iSize),
                        modifiedMs = c.getLong(iDate) * 1000,
                        mime = c.getString(iMime),
                        category = cat,
                        inDownloads = c.getString(iPath)?.startsWith("Download", ignoreCase = true) == true
                    )
                }
            }
        } catch (_: SecurityException) {
            // Permission not granted yet: the UI asks for it and rescans.
        }
        return out
    }

    /** Fast recursive listing of a user-picked folder using DocumentsContract queries. */
    suspend fun scanTree(tree: Uri, maxFiles: Int = 40_000): List<FileItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<FileItem>()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootIsDownloads = rootId.substringAfterLast(':').startsWith("Download", ignoreCase = true)
        val stack = ArrayDeque<Pair<String, Boolean>>() // docId, inDownloads
        stack.addLast(rootId to rootIsDownloads)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        while (stack.isNotEmpty() && out.size < maxFiles) {
            val (parentId, inDl) = stack.removeLast()
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            try {
                resolver.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext() && out.size < maxFiles) {
                        val id = c.getString(0)
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            stack.addLast(id to (inDl || name.equals("Download", true) || name.equals("Downloads", true)))
                        } else {
                            out += FileItem(
                                uri = DocumentsContract.buildDocumentUriUsingTree(tree, id),
                                name = name,
                                sizeBytes = c.getLong(3),
                                modifiedMs = c.getLong(4),
                                mime = mime,
                                category = FileClassifier.classify(name, mime),
                                inDownloads = inDl,
                                parentUri = parentUri,
                                editable = true
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                // A folder that vanished or denies access is skipped.
            }
        }
        out
    }

    /** Groups files with identical size and identical leading content (first 1 MB hashed). */
    suspend fun findDuplicates(items: List<FileItem>, category: FileCategory? = null): List<DuplicateGroup> =
        withContext(Dispatchers.IO) {
            val candidates = items.filter { it.sizeBytes > 0 && (category == null || it.category == category) }
                .groupBy { it.sizeBytes }.filter { it.value.size > 1 }
            val groups = mutableListOf<DuplicateGroup>()
            for ((size, same) in candidates) {
                same.groupBy { quickHash(it.uri, size) }.forEach { (hash, files) ->
                    if (hash != null && files.size > 1) groups += DuplicateGroup(size, files)
                }
            }
            groups.sortedByDescending { it.sizeBytes * it.files.size }
        }

    private fun quickHash(uri: Uri, size: Long): String? = try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (total < 1_048_576) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                total += n
            }
        } ?: return null
        md.digest().joinToString("") { "%02x".format(it) } + size
    } catch (_: Exception) { null }
}
