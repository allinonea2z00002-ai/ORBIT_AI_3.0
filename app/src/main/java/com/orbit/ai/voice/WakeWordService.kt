package com.orbit.ai.voice

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.orbit.ai.MainActivity
import com.orbit.ai.OrbitApp
import com.orbit.ai.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** Lets the service tell the UI that the wake word was heard. */
class WakeBus {
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    /** Set when the wake word was heard while the app was hidden; the UI consumes it when it comes back. */
    @Volatile var pending: Boolean = false
        private set

    fun emit() { pending = true; _events.tryEmit(Unit) }
    fun consumePending(): Boolean { val p = pending; pending = false; return p }
}

/**
 * OPTIONAL wake-word listener ("ORBIT").
 *
 * How it works and what to expect:
 *  - It is a visible foreground service: a notification is always shown while the microphone is on,
 *    with a Stop button. It never records to storage; speech is only matched against the wake word.
 *  - It reuses Android's SpeechRecognizer in a loop. That is NOT a low-power dedicated wake-word
 *    engine, so it uses noticeably more battery, may click or beep on some phones, and may need the
 *    internet unless an offline language pack is installed.
 *  - Android does not allow apps to open themselves from the background. When the wake word is heard
 *    while the app is hidden, ORBIT posts a notification you tap to start talking.
 *  - It pauses while the app itself is listening or speaking.
 */
class WakeWordService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var paused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val app = application as OrbitApp
            app.container.scope.launch { app.container.settings.setWakeWord(false) }
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        if (!running) {
            running = true
            val app = application as OrbitApp
            // Pause while the app itself uses the microphone or speaker.
            scope.launch {
                app.container.voice.state.collect { s ->
                    paused = s != VoiceState.IDLE
                    if (paused) destroyRecognizer() else if (running) startLoop()
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = NotificationCompat.Builder(this, OrbitApp.CHANNEL_WAKE)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.wake_notification_title))
            .setContentText(getString(R.string.wake_notification_text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.wake_stop), stop)
            .build()
        ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    private fun startLoop() {
        if (!running || paused || recognizer != null) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { stopSelf(); return }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) { heardWakeWord(partialResults) }
            override fun onResults(results: Bundle?) { if (!heardWakeWord(results)) restartSoon(600) }
            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { stopSelf(); return }
                restartSoon(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 2500 else 900)
            }
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
    }

    /** Returns true if the wake word was found. */
    private fun heardWakeWord(b: Bundle?): Boolean {
        val heard = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.joinToString(" ")?.lowercase().orEmpty()
        if (WAKE_WORDS.none { heard.contains(it) }) return false
        destroyRecognizer()
        (application as OrbitApp).container.wake.emit()
        notifyHeard()
        restartSoon(4000)
        return true
    }

    private fun notifyHeard() {
        val open = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java).putExtra(EXTRA_WAKE, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, OrbitApp.CHANNEL_WAKE)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("ORBIT heard you")
            .setContentText("Tap to start talking")
            .setAutoCancel(true)
            .setTimeoutAfter(15_000)
            .setContentIntent(open)
            .build()
        getSystemService(Context.NOTIFICATION_SERVICE).let {
            (it as android.app.NotificationManager).notify(NOTIF_HEARD_ID, n)
        }
    }

    private fun restartSoon(delayMs: Long) {
        destroyRecognizer()
        main.postDelayed({ startLoop() }, delayMs)
    }

    private fun destroyRecognizer() {
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        destroyRecognizer()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.orbit.ai.WAKE_STOP"
        const val EXTRA_WAKE = "wake"
        private const val NOTIF_ID = 4001
        private const val NOTIF_HEARD_ID = 4002
        private val WAKE_WORDS = listOf("orbit", "অরবিট", "অর্বিট", "ওরবিট")

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, WakeWordService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
