package com.orbit.ai.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

enum class VoiceState { IDLE, LISTENING, SPEAKING }

/**
 * Wraps Android's own SpeechRecognizer and TextToSpeech.
 * All calls must happen on the main thread.
 *
 * Offline recognition works only if the device's speech service has the language pack installed.
 * When it needs the network we say so instead of failing silently.
 */
class VoiceManager(private val context: Context) {

    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    /** 0..1 microphone loudness for the circular visualizer. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeak: (() -> Unit)? = null

    val recognitionAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    // ---------------- Listening ----------------

    fun startListening(
        languageTag: String,
        onFinalResult: (String) -> Unit,
        onFail: (String) -> Unit
    ) {
        stopSpeaking()
        stopListening()
        if (!recognitionAvailable) {
            onFail("This phone has no speech recognition service. Install or enable Google speech services.")
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { _state.value = VoiceState.LISTENING }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { _level.value = 0f }
            override fun onPartialResults(partialResults: Bundle?) {
                _partial.value = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                finishListening()
                if (text.isBlank()) onFail("I did not catch that.") else onFinalResult(text)
            }
            override fun onError(error: Int) {
                finishListening()
                onFail(describeError(error))
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        _partial.value = ""
        _state.value = VoiceState.LISTENING
        r.startListening(intent)
    }

    fun stopListening() {
        recognizer?.let {
            runCatching { it.stopListening() }
            runCatching { it.destroy() }
        }
        recognizer = null
        if (_state.value == VoiceState.LISTENING) _state.value = VoiceState.IDLE
        _level.value = 0f
    }

    private fun finishListening() {
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
        _level.value = 0f
        if (_state.value == VoiceState.LISTENING) _state.value = VoiceState.IDLE
    }

    private fun describeError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I did not hear anything."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            "Speech recognition needs the internet for this language. For offline use, install the offline " +
                "language pack in your phone's Google speech settings."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "This language is not available for speech recognition on this phone."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech service is busy. Try again."
        else -> "Speech recognition failed (code $code)."
    }

    // ---------------- Speaking ----------------

    private fun ensureTts(onReady: () -> Unit) {
        if (ttsReady) { onReady(); return }
        pendingSpeak = onReady
        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) pendingSpeak?.invoke()
                pendingSpeak = null
            }
        }
    }

    /** Returns false (via [onUnavailable]) when the language voice is not installed. */
    fun speak(
        text: String,
        languageTag: String,
        rate: Float,
        pitch: Float,
        voiceName: String,
        onUnavailable: (String) -> Unit = {},
        onFinished: () -> Unit = {}
    ) {
        if (text.isBlank()) { onFinished(); return }
        ensureTts {
            val engine = tts ?: return@ensureTts
            val locale = Locale.forLanguageTag(languageTag)
            val avail = engine.isLanguageAvailable(locale)
            if (avail < TextToSpeech.LANG_AVAILABLE) {
                onUnavailable("No ${locale.displayLanguage} text-to-speech voice is installed on this phone.")
                onFinished()
                return@ensureTts
            }
            engine.language = locale
            if (voiceName.isNotBlank()) {
                engine.voices?.firstOrNull { it.name == voiceName }?.let { engine.voice = it }
            }
            engine.setSpeechRate(rate)
            engine.setPitch(pitch)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { _state.value = VoiceState.SPEAKING }
                override fun onDone(utteranceId: String?) {
                    if (utteranceId?.endsWith(LAST_SUFFIX) == true) {
                        _state.value = VoiceState.IDLE
                        onFinished()
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { _state.value = VoiceState.IDLE; onFinished() }
            })
            val base = UUID.randomUUID().toString()
            val chunks = chunk(text)
            chunks.forEachIndexed { i, part ->
                val id = if (i == chunks.lastIndex) base + LAST_SUFFIX else "$base-$i"
                engine.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
            }
            _state.value = VoiceState.SPEAKING
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        if (_state.value == VoiceState.SPEAKING) _state.value = VoiceState.IDLE
    }

    /** Names of installed voices for the chosen language, for the voice picker. */
    fun voiceNames(languageTag: String): List<String> {
        val lang = Locale.forLanguageTag(languageTag).language
        return runCatching {
            tts?.voices?.filter { it.locale.language == lang }?.map { it.name }?.sorted() ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** Make sure the TTS engine is initialised so voices can be listed. */
    fun warmUp() { ensureTts {} }

    // ---------------- Volume ----------------

    private val audio get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun mediaVolume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    fun setMediaVolume(fraction: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (fraction * max).toInt().coerceIn(0, max), 0)
    }

    fun shutdown() {
        stopListening()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    private fun chunk(text: String, max: Int = 3000): List<String> {
        if (text.length <= max) return listOf(text)
        val out = mutableListOf<String>()
        var rest = text
        while (rest.length > max) {
            var cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', '।', '\n'), max)
            if (cut < max / 2) cut = max
            out += rest.substring(0, cut + 1)
            rest = rest.substring(cut + 1)
        }
        if (rest.isNotBlank()) out += rest
        return out
    }

    private companion object { const val LAST_SUFFIX = "-last" }
}
