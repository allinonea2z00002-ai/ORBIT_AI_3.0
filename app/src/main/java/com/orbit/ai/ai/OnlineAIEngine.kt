package com.orbit.ai.ai

import com.orbit.ai.settings.SecureStore
import com.orbit.ai.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks to YOUR backend proxy (see /backend). The APK never contains a provider API key;
 * the proxy holds the keys as environment secrets and forwards to Gemini, Groq or Claude.
 */
class OnlineAIEngine(
    private val settings: SettingsRepository,
    private val secure: SecureStore,
    private val http: OkHttpClient,
    private val network: NetworkChecker
) : AIEngine {

    override val kind = EngineKind.ONLINE

    private val client = http.newBuilder()
        .callTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun isAvailable(): Boolean {
        val url = settings.settings.first().proxyUrl
        return url.startsWith("https://") && network.isOnline()
    }

    override suspend fun unavailableReason(): String {
        val url = settings.settings.first().proxyUrl
        return when {
            url.isBlank() -> "Online AI is not set up yet. Add your backend proxy URL in Settings."
            !url.startsWith("https://") -> "The proxy URL must start with https://"
            !network.isOnline() -> "No internet connection."
            else -> "Online AI is unavailable."
        }
    }

    override suspend fun generate(system: String, history: List<ChatTurn>, image: ImageInput?): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val s = settings.settings.first()
                val body = JSONObject().apply {
                    put("provider", s.provider)
                    put("system", system)
                    put("messages", JSONArray().also { arr ->
                        history.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
                    })
                    if (image != null) {
                        put("image", JSONObject().put("mimeType", image.mimeType).put("data", image.base64))
                    }
                }
                val req = Request.Builder()
                    .url(s.proxyUrl.trimEnd('/') + "/chat")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .apply {
                        val token = secure.proxyToken
                        if (token.isNotBlank()) header("Authorization", "Bearer $token")
                    }
                    .build()
                client.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    val json = runCatching { JSONObject(raw) }.getOrNull()
                    if (!resp.isSuccessful) {
                        val msg = json?.optString("error").takeUnless { it.isNullOrBlank() } ?: "HTTP ${resp.code}"
                        error("Online AI error: $msg")
                    }
                    val text = json?.optString("text").orEmpty()
                    if (text.isBlank()) error("Online AI returned an empty answer.")
                    text
                }
            }
        }
}
