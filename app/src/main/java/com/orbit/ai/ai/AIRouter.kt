package com.orbit.ai.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.orbit.ai.settings.AiMode
import com.orbit.ai.settings.SettingsRepository
import kotlinx.coroutines.flow.first

class NetworkChecker(private val context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun isOnline(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Live connectivity for the status chip and the orb. */
    val online: kotlinx.coroutines.flow.StateFlow<Boolean> by lazy {
        val state = kotlinx.coroutines.flow.MutableStateFlow(isOnline())
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { state.value = isOnline() }
            override fun onLost(network: android.net.Network) { state.value = false }
            override fun onCapabilitiesChanged(network: android.net.Network, caps: NetworkCapabilities) {
                state.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        })
        state
    }
}

/**
 * Chooses the engine for each request.
 *  AUTO    : online when set up and reachable, otherwise offline; online failure falls back to offline.
 *  ONLINE  : online only; failure is reported, never silently replaced.
 *  OFFLINE : on-device only.
 */
class AIRouter(
    private val settings: SettingsRepository,
    private val online: OnlineAIEngine,
    private val offline: OfflineAIEngine
) {
    suspend fun ask(
        system: String,
        history: List<ChatTurn>,
        image: ImageInput? = null
    ): Result<AiReply> {
        val mode = settings.settings.first().aiMode
        return when (mode) {
            AiMode.ONLINE -> runOnline(system, history, image)
            AiMode.OFFLINE -> runOffline(system, history, image, note = null)
            AiMode.AUTO -> {
                if (online.isAvailable()) {
                    val r = runOnline(system, history, image)
                    if (r.isSuccess) r
                    else if (image == null && offline.isAvailable())
                        runOffline(system, history, null, note = "Online AI failed, so the offline model answered.")
                    else r
                } else if (offline.isAvailable()) {
                    runOffline(system, history, image, note = null)
                } else {
                    Result.failure(
                        IllegalStateException(
                            online.unavailableReason() + " " + offline.unavailableReason()
                        )
                    )
                }
            }
        }
    }

    private suspend fun runOnline(system: String, h: List<ChatTurn>, img: ImageInput?): Result<AiReply> {
        if (!online.isAvailable()) return Result.failure(IllegalStateException(online.unavailableReason()))
        return online.generate(system, h, img).map { AiReply(it, EngineKind.ONLINE) }
    }

    private suspend fun runOffline(system: String, h: List<ChatTurn>, img: ImageInput?, note: String?): Result<AiReply> {
        if (!offline.isAvailable()) return Result.failure(IllegalStateException(offline.unavailableReason()))
        return offline.generate(system, h, img).map { AiReply(it, EngineKind.OFFLINE, note) }
    }
}
