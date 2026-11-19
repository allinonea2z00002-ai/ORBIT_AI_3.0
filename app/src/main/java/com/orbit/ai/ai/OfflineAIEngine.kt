package com.orbit.ai.ai

import android.app.ActivityManager
import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.orbit.ai.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

enum class ModelLoadState { NONE, LOADING, READY, FAILED }

/**
 * On-device language model using Google's LiteRT-LM runtime.
 *
 * Honest limits: a 12 GB phone does not guarantee a given model runs well. Before loading,
 * we compare the model file size with currently free RAM and refuse with an explanation
 * instead of letting Android kill the app. Local models are small, so answers are weaker than
 * the online AI, especially in Bangla.
 */
class OfflineAIEngine(
    private val context: Context,
    private val models: ModelManager,
    private val settings: SettingsRepository
) : AIEngine {

    override val kind = EngineKind.OFFLINE

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var loadedFile: String? = null

    private val _loadState = MutableStateFlow(ModelLoadState.NONE)
    val loadState: StateFlow<ModelLoadState> = _loadState.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private suspend fun activeFile(): File? {
        val name = settings.settings.first().activeModelFile
        val f = if (name.isNotBlank()) models.modelFile(name) else null
        return f?.takeIf { it.exists() }
            ?: models.list().firstOrNull()?.let { models.modelFile(it.fileName) }
    }

    override suspend fun isAvailable(): Boolean = activeFile() != null

    override suspend fun unavailableReason(): String =
        if (activeFile() == null)
            "No offline model is installed. Open Settings > Offline model to download or import one."
        else "Offline model is not available."

    private fun freeRamBytes(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem
    }

    /** Loads the model if needed. Heavy: runs off the main thread. */
    suspend fun load(): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.Default) {
            runCatching {
                val file = activeFile() ?: error(unavailableReason())
                if (engine != null && loadedFile == file.name) return@runCatching
                unloadLocked()
                // Rough rule: model weights + runtime overhead need about 1.3x the file size in free RAM.
                val needed = (file.length() * 1.3).toLong()
                val free = freeRamBytes()
                if (free < needed) {
                    error(
                        "Not enough free memory for this model (needs about ${needed / 1_048_576} MB, " +
                            "${free / 1_048_576} MB free). Close other apps or choose a smaller model."
                    )
                }
                _loadState.value = ModelLoadState.LOADING
                val e = Engine(EngineConfig(modelPath = file.absolutePath, backend = Backend.CPU()))
                e.initialize()
                engine = e
                loadedFile = file.name
                _loadState.value = ModelLoadState.READY
                _lastError.value = null
            }.onFailure {
                _loadState.value = ModelLoadState.FAILED
                _lastError.value = it.message
            }
        }
    }

    override suspend fun generate(system: String, history: List<ChatTurn>, image: ImageInput?): Result<String> {
        if (image != null) return Result.failure(IllegalStateException("The offline model cannot analyse images."))
        load().onFailure { return Result.failure(it) }
        return mutex.withLock {
            withContext(Dispatchers.Default) {
                runCatching {
                    val e = engine ?: error("Offline model is not loaded.")
                    // Small models handle one plain prompt best: persona, short transcript, then the question.
                    val prompt = buildString {
                        append(system).append("\n\n")
                        history.dropLast(1).takeLast(6).forEach {
                            append(if (it.role == "user") "User: " else "Assistant: ").append(it.content).append('\n')
                        }
                        append("User: ").append(history.last().content).append("\nAssistant:")
                    }
                    e.createConversation().use { conv -> conv.sendMessage(prompt).toString().trim() }
                }
            }
        }
    }

    suspend fun unload() { mutex.withLock { unloadLocked() } }

    private fun unloadLocked() {
        runCatching { engine?.close() }
        engine = null
        loadedFile = null
        _loadState.value = ModelLoadState.NONE
    }
}
