package com.orbit.ai

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.orbit.ai.navigation.OrbitNavHost
import com.orbit.ai.settings.OrbitSettings
import com.orbit.ai.ui.components.hasPermission
import com.orbit.ai.ui.theme.OrbitCyan
import com.orbit.ai.ui.theme.OrbitPurple
import com.orbit.ai.ui.theme.OrbitTheme
import com.orbit.ai.viewmodel.ChatViewModel
import com.orbit.ai.viewmodel.orbitViewModel
import com.orbit.ai.voice.WakeWordService

class MainActivity : ComponentActivity() {

    private val container get() = (application as OrbitApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleWakeIntent(intent)
        setContent {
            val settings by container.settings.settings.collectAsState(initial = OrbitSettings())
            val ctx = LocalContext.current

            // Keep the optional wake-word service in sync with the setting.
            LaunchedEffect(settings.wakeWordEnabled) {
                if (settings.wakeWordEnabled) {
                    if (hasPermission(ctx, Manifest.permission.RECORD_AUDIO)) WakeWordService.start(ctx)
                } else WakeWordService.stop(ctx)
            }

            OrbitTheme(settings.theme) {
                val chat = orbitViewModel { ChatViewModel(it) }
                val bg = MaterialTheme.colorScheme.background
                Box(
                    Modifier.fillMaxSize().background(bg)
                        .background(Brush.radialGradient(listOf(OrbitPurple.copy(alpha = 0.10f), Color.Transparent), radius = 1100f))
                        .background(Brush.radialGradient(listOf(OrbitCyan.copy(alpha = 0.05f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(900f, 2000f), radius = 900f))
                ) {
                    OrbitNavHost(chat, container.wake)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeIntent(intent)
    }

    /** Tapping the "ORBIT heard you" notification asks the home screen to start listening. */
    private fun handleWakeIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(WakeWordService.EXTRA_WAKE, false) == true) {
            container.wake.emit()
            intent.removeExtra(WakeWordService.EXTRA_WAKE)
        }
    }
}
