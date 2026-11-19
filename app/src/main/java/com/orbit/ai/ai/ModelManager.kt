package com.orbit.ai.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.orbit.ai.settings.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

data class LocalModel(val fileName: String, val sizeBytes: Long)

sealed interface TransferState {
    data object Idle : TransferState
    data class Running(val label: String, val doneBytes: Long, val totalBytes: Long) : TransferState
    data class Failed(val message: String) : TransferState
    data class Done(val fileName: String) : TransferState
}

/**
 * Stores on-device model files in the app's private storage and manages them:
 * download from a URL, import from a file the user picked, list, delete.
 * Model files are large, so nothing is downloaded unless the user starts it.
 */
class ModelManager(
    private val context: Context,
    private val http: OkHttpClient,
    private val secure: SecureStore
) {
    private val dir: File get() = File(context.filesDir, "models").apply { mkdirs() }

    private val _state = MutableStateFlow<TransferState>(TransferState.Idle)
    val state: StateFlow<TransferState> = _state.asStateFlow()

    @Volatile private var cancelled = false

    fun cancel() { cancelled = true }
    fun resetState() { _state.value = TransferState.Idle }

    fun modelFile(name: String): File = File(dir, name)

    fun list(): List<LocalModel> =
        dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.map { LocalModel(it.name, it.length()) }?.sortedBy { it.fileName } ?: emptyList()

    fun delete(name: String): Boolean = modelFile(name).delete()

    fun storageUsedBytes(): Long = list().sumOf { it.sizeBytes }

    /** Copy a model the user selected with the system file picker into private storage. */
    suspend fun importFrom(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            cancelled = false
            var name = "model.litertlm"
            var total = -1L
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni)
                    if (si >= 0) total = c.getLong(si)
                }
            }
            name = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val part = File(dir, "$name.part")
            _state.value = TransferState.Running("Importing $name", 0, total)
            context.contentResolver.openInputStream(uri)!!.use { input ->
                part.outputStream().use { out -> copyWithProgress(input, out, "Importing $name", total) }
            }
            finish(part, name)
        }.onFailure { _state.value = TransferState.Failed(it.message ?: "Import failed") }
    }

    /** Download a model over HTTPS. [bearerToken] is optional (e.g. Hugging Face token for gated models). */
    suspend fun download(url: String, fileName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(url.startsWith("https://")) { "Model URL must start with https://" }
            cancelled = false
            val name = fileName.ifBlank { url.substringAfterLast('/').substringBefore('?') }
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            val part = File(dir, "$name.part")
            val req = Request.Builder().url(url).apply {
                val token = secure.modelDownloadToken
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }.build()
            _state.value = TransferState.Running("Downloading $name", 0, -1)
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Download failed: HTTP ${resp.code}" +
                    if (resp.code == 401 || resp.code == 403) " (the model may need a token or licence acceptance)" else "")
                val body = resp.body ?: error("Empty response")
                body.byteStream().use { input ->
                    part.outputStream().use { out -> copyWithProgress(input, out, "Downloading $name", body.contentLength()) }
                }
            }
            finish(part, name)
        }.onFailure { _state.value = TransferState.Failed(it.message ?: "Download failed") }
    }

    private fun copyWithProgress(input: java.io.InputStream, out: java.io.OutputStream, label: String, total: Long) {
        val buf = ByteArray(256 * 1024)
        var done = 0L
        var lastEmit = 0L
        while (true) {
            if (cancelled) error("Cancelled")
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            if (done - lastEmit > 2_000_000) {
                lastEmit = done
                _state.value = TransferState.Running(label, done, total)
            }
        }
    }

    private fun finish(part: File, name: String): String {
        val target = modelFile(name)
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) error("Could not save the model file")
        _state.value = TransferState.Done(name)
        return name
    }
}
