package com.orbit.ai.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "orbit_settings")

enum class AiMode { AUTO, ONLINE, OFFLINE }
enum class ThemeMode { DARK, LIGHT, SYSTEM }
enum class AppLanguage(val tag: String, val label: String) {
    ENGLISH("en-US", "English"),
    BANGLA("bn-BD", "বাংলা (Bangla)")
}

data class OrbitSettings(
    val aiMode: AiMode = AiMode.AUTO,
    val provider: String = "gemini",           // gemini | groq | anthropic (chosen by the proxy)
    val proxyUrl: String = "",                 // your deployed backend, e.g. https://orbit-proxy.xyz.workers.dev
    val theme: ThemeMode = ThemeMode.DARK,
    val language: AppLanguage = AppLanguage.ENGLISH,
    val speechRate: Float = 0.95f,
    val speechPitch: Float = 0.8f,             // lower pitch approximates a deeper voice
    val voiceName: String = "",                // empty = engine default
    val speakReplies: Boolean = true,
    val continuousConversation: Boolean = false,
    val wakeWordEnabled: Boolean = false,
    val saveHistory: Boolean = true,
    val useMemoryInPrompts: Boolean = true,
    val customInstructions: String = "",
    val userName: String = "Boss",
    val activeModelFile: String = ""
)

class SettingsRepository(private val context: Context) {

    private object K {
        val aiMode = stringPreferencesKey("ai_mode")
        val provider = stringPreferencesKey("provider")
        val proxyUrl = stringPreferencesKey("proxy_url")
        val theme = stringPreferencesKey("theme")
        val language = stringPreferencesKey("language")
        val rate = floatPreferencesKey("rate")
        val pitch = floatPreferencesKey("pitch")
        val voice = stringPreferencesKey("voice")
        val speak = booleanPreferencesKey("speak")
        val continuous = booleanPreferencesKey("continuous")
        val wake = booleanPreferencesKey("wake")
        val history = booleanPreferencesKey("history")
        val memory = booleanPreferencesKey("memory")
        val instructions = stringPreferencesKey("instructions")
        val userName = stringPreferencesKey("user_name")
        val model = stringPreferencesKey("model_file")
    }

    val settings: Flow<OrbitSettings> = context.dataStore.data.map { p ->
        val d = OrbitSettings()
        OrbitSettings(
            aiMode = p[K.aiMode]?.let { runCatching { AiMode.valueOf(it) }.getOrNull() } ?: d.aiMode,
            provider = p[K.provider] ?: d.provider,
            proxyUrl = p[K.proxyUrl] ?: d.proxyUrl,
            theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            language = p[K.language]?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() } ?: d.language,
            speechRate = p[K.rate] ?: d.speechRate,
            speechPitch = p[K.pitch] ?: d.speechPitch,
            voiceName = p[K.voice] ?: d.voiceName,
            speakReplies = p[K.speak] ?: d.speakReplies,
            continuousConversation = p[K.continuous] ?: d.continuousConversation,
            wakeWordEnabled = p[K.wake] ?: d.wakeWordEnabled,
            saveHistory = p[K.history] ?: d.saveHistory,
            useMemoryInPrompts = p[K.memory] ?: d.useMemoryInPrompts,
            customInstructions = p[K.instructions] ?: d.customInstructions,
            userName = p[K.userName] ?: d.userName,
            activeModelFile = p[K.model] ?: d.activeModelFile
        )
    }

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun setAiMode(v: AiMode) = set(K.aiMode, v.name)
    suspend fun setProvider(v: String) = set(K.provider, v)
    suspend fun setProxyUrl(v: String) = set(K.proxyUrl, v.trim().trimEnd('/'))
    suspend fun setTheme(v: ThemeMode) = set(K.theme, v.name)
    suspend fun setLanguage(v: AppLanguage) = set(K.language, v.name)
    suspend fun setSpeechRate(v: Float) = set(K.rate, v)
    suspend fun setSpeechPitch(v: Float) = set(K.pitch, v)
    suspend fun setVoiceName(v: String) = set(K.voice, v)
    suspend fun setSpeakReplies(v: Boolean) = set(K.speak, v)
    suspend fun setContinuous(v: Boolean) = set(K.continuous, v)
    suspend fun setWakeWord(v: Boolean) = set(K.wake, v)
    suspend fun setSaveHistory(v: Boolean) = set(K.history, v)
    suspend fun setUseMemory(v: Boolean) = set(K.memory, v)
    suspend fun setCustomInstructions(v: String) = set(K.instructions, v)
    suspend fun setUserName(v: String) = set(K.userName, v.ifBlank { "Boss" })
    suspend fun setActiveModelFile(v: String) = set(K.model, v)
}

/**
 * Encrypted storage for secrets the user types into the app (proxy access token,
 * optional model-download token). Values never leave the device except as an
 * Authorization header to the URL the user configured.
 */
class SecureStore(context: Context) {
    private val prefs: SharedPreferences = run {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "orbit_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var proxyToken: String
        get() = prefs.getString("proxy_token", "") ?: ""
        set(v) = prefs.edit().putString("proxy_token", v.trim()).apply()

    var modelDownloadToken: String
        get() = prefs.getString("model_token", "") ?: ""
        set(v) = prefs.edit().putString("model_token", v.trim()).apply()
}
