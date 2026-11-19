package com.orbit.ai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.orbit.ai.ai.AIRouter
import com.orbit.ai.ai.ModelManager
import com.orbit.ai.ai.NetworkChecker
import com.orbit.ai.ai.OfflineAIEngine
import com.orbit.ai.ai.OnlineAIEngine
import com.orbit.ai.automation.ActionExecutor
import com.orbit.ai.database.OrbitDatabase
import com.orbit.ai.files.FileOps
import com.orbit.ai.files.FileScanner
import com.orbit.ai.intelligence.DeviceIntelligence
import com.orbit.ai.memory.MemoryRepository
import com.orbit.ai.productivity.DocumentTools
import com.orbit.ai.settings.SecureStore
import com.orbit.ai.settings.SettingsRepository
import com.orbit.ai.voice.VoiceManager
import com.orbit.ai.voice.WakeBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/** Hand-written dependency container: one instance of each service for the whole app. */
class AppContainer(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient by lazy { OkHttpClient() }
    val db: OrbitDatabase by lazy { OrbitDatabase.create(context) }
    val settings = SettingsRepository(context)
    val secure: SecureStore by lazy { SecureStore(context) }
    val network = NetworkChecker(context)

    val models: ModelManager by lazy { ModelManager(context, http, secure) }
    val online: OnlineAIEngine by lazy { OnlineAIEngine(settings, secure, http, network) }
    val offline: OfflineAIEngine by lazy { OfflineAIEngine(context, models, settings) }
    val router: AIRouter by lazy { AIRouter(settings, online, offline) }

    val voice: VoiceManager by lazy { VoiceManager(context) }
    val wake = WakeBus()

    val memory: MemoryRepository by lazy { MemoryRepository(db.memories()) }
    val fileScanner: FileScanner by lazy { FileScanner(context) }
    val fileOps: FileOps by lazy { FileOps(context) }
    val intel: DeviceIntelligence by lazy { DeviceIntelligence(context) }
    val actions: ActionExecutor by lazy { ActionExecutor(context, db.automation(), intel) }
    val documents: DocumentTools by lazy { DocumentTools(context) }
}

class OrbitApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WAKE, getString(R.string.channel_wake), NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL_REMINDERS = "orbit_reminders"
        const val CHANNEL_WAKE = "orbit_wake"
    }
}
