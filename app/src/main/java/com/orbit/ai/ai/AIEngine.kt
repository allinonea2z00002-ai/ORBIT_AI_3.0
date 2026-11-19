package com.orbit.ai.ai

enum class EngineKind { ONLINE, OFFLINE }

data class ChatTurn(val role: String, val content: String) // role = "user" | "assistant"

data class ImageInput(val mimeType: String, val base64: String)

/** Final answer handed to the UI, together with which engine really produced it. */
data class AiReply(val text: String, val engine: EngineKind, val note: String? = null)

/** One AI backend (cloud proxy or on-device model). */
interface AIEngine {
    val kind: EngineKind

    /** Cheap check: could this engine plausibly answer right now? */
    suspend fun isAvailable(): Boolean

    /** Human-readable reason shown to the user when [isAvailable] is false. */
    suspend fun unavailableReason(): String

    suspend fun generate(system: String, history: List<ChatTurn>, image: ImageInput? = null): Result<String>
}

/** Builds the system prompt: persona + user's custom instructions + user-approved memory. */
object PromptBuilder {
    fun system(
        userName: String,
        languageTag: String,
        customInstructions: String,
        memories: List<String>
    ): String = buildString {
        append("You are ORBIT, a calm, intelligent and polite personal AI assistant in the style of a ")
        append("professional butler-AI. Address the user as \"$userName\". Be concise: voice replies should be ")
        append("one to three short sentences unless the user asks for detail. ")
        append("Never invent facts, files, or actions. If you do not know, say so. ")
        append("You cannot operate the phone yourself; phone actions are handled by the app before your reply. ")
        if (languageTag.startsWith("bn")) {
            append("Reply in Bangla (Bengali script) unless the user writes in English. ")
        } else {
            append("Reply in English unless the user writes in Bangla, then reply in Bangla. ")
        }
        if (customInstructions.isNotBlank()) append("\nUser's custom instructions: ${customInstructions.trim()}")
        if (memories.isNotEmpty()) {
            append("\nThings the user asked you to remember:\n")
            memories.take(40).forEach { append("- ").append(it).append('\n') }
        }
    }
}
