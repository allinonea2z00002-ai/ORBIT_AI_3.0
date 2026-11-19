package com.orbit.ai.files

import android.net.Uri

enum class FileCategory(val label: String, val folderName: String) {
    PHOTOS("Photos", "Photos"),
    VIDEOS("Videos", "Videos"),
    MUSIC("Music", "Music"),
    AUDIO("Audio", "Audio"),
    PDF("PDF", "PDF"),
    DOCUMENTS("Documents", "Documents"),
    APK("APK files", "APK"),
    ARCHIVES("Archives", "Archives"),
    OTHER("Other files", "Other");
}

data class FileItem(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val mime: String?,
    val category: FileCategory,
    /** True when the file lives in the Downloads folder (MediaStore path or the picked folder is named Download). */
    val inDownloads: Boolean,
    /** Parent folder document, only known for files found through a user-picked folder (needed to move them). */
    val parentUri: Uri? = null,
    /** True when ORBIT can move/rename/copy it (it came from a folder the user authorised). */
    val editable: Boolean = false
)

data class DuplicateGroup(val sizeBytes: Long, val files: List<FileItem>)

data class MovePlanEntry(val file: FileItem, val targetFolder: String)

data class MoveRecord(val movedUri: Uri, val newParent: Uri, val oldParent: Uri, val name: String)

fun formatBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = b / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return "%.1f %s".format(v, units[i])
}

object FileClassifier {
    private val doc = setOf("doc", "docx", "txt", "rtf", "odt", "xls", "xlsx", "csv", "ppt", "pptx", "md", "epub")
    private val arch = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
    private val img = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")
    private val vid = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v")
    private val aud = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "amr")

    fun classify(name: String, mime: String?): FileCategory {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext == "pdf" || mime == "application/pdf" -> FileCategory.PDF
            ext == "apk" || mime == "application/vnd.android.package-archive" -> FileCategory.APK
            ext in arch || mime?.contains("zip") == true -> FileCategory.ARCHIVES
            ext in img || mime?.startsWith("image/") == true -> FileCategory.PHOTOS
            ext in vid || mime?.startsWith("video/") == true -> FileCategory.VIDEOS
            ext in aud || mime?.startsWith("audio/") == true -> FileCategory.AUDIO
            ext in doc || mime?.startsWith("text/") == true || mime?.contains("document") == true ||
                mime?.contains("sheet") == true || mime?.contains("presentation") == true -> FileCategory.DOCUMENTS
            else -> FileCategory.OTHER
        }
    }
}
