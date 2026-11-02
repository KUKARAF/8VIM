package inc.flide.vim8.ime.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns the [TextToSpeech] engine. The engine is created lazily on the first request and all
 * engine access happens on the main thread, so callers can invoke the public API from any thread.
 */
class SpeechManager(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager by lazy { appContext.getSystemService(AudioManager::class.java) }
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val active = AtomicBoolean(false)
    private val utteranceCounter = AtomicInteger(0)

    // Only touched on the main thread.
    private var engine: TextToSpeech? = null
    private var engineReady = false
    private var pendingText: String? = null
    private var currentUtteranceId: String? = null
    private var focusRequest: AudioFocusRequest? = null

    /** True from the moment a speak request is accepted until speech finishes or is stopped. */
    val isActive: Boolean get() = active.get()

    /** Starts speaking [text]. Ignored when blank or when speech is already in progress. */
    fun speak(text: String): Boolean {
        if (text.isBlank() || !active.compareAndSet(false, true)) return false
        scope.launch { startSpeaking(text) }
        return true
    }

    fun stop() {
        active.set(false)
        scope.launch {
            pendingText = null
            currentUtteranceId = null
            engine?.stop()
            abandonAudioFocus()
        }
    }

    fun shutdown() {
        active.set(false)
        scope.launch {
            pendingText = null
            currentUtteranceId = null
            engine?.stop()
            engine?.shutdown()
            engine = null
            engineReady = false
            abandonAudioFocus()
        }
    }

    private fun startSpeaking(text: String) {
        if (engine == null) {
            pendingText = text
            engine = TextToSpeech(appContext) { status -> scope.launch { onEngineInit(status) } }
        } else if (engineReady) {
            speakNow(text)
        } else {
            pendingText = text
        }
    }

    private fun onEngineInit(status: Int) {
        val tts = engine ?: return
        if (status != TextToSpeech.SUCCESS) {
            tts.shutdown()
            engine = null
            pendingText = null
            active.set(false)
            return
        }
        engineReady = true
        tts.setAudioAttributes(audioAttributes)
        tts.setOnUtteranceProgressListener(progressListener)
        val languageResult = tts.setLanguage(Locale.getDefault())
        if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            // Fall back to the engine's own default voice.
            tts.defaultVoice?.locale?.let { tts.setLanguage(it) }
        }
        pendingText?.let { text ->
            pendingText = null
            if (active.get()) speakNow(text)
        }
    }

    private fun speakNow(text: String) {
        val tts = engine ?: return
        val utteranceId = "vim8-speech-${utteranceCounter.incrementAndGet()}"
        currentUtteranceId = utteranceId
        requestAudioFocus()
        val limit = TextToSpeech.getMaxSpeechInputLength()
        val result = tts.speak(text.take(limit), TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) finish(utteranceId)
    }

    private fun finish(utteranceId: String) {
        if (utteranceId != currentUtteranceId) return
        currentUtteranceId = null
        active.set(false)
        abandonAudioFocus()
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            utteranceId?.let { scope.launch { finish(it) } }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            utteranceId?.let { scope.launch { finish(it) } }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            utteranceId?.let { scope.launch { finish(it) } }
        }
    }

    private fun requestAudioFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = focusRequest ?: AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .build()
                .also { focusRequest = it }
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(null)
        }
    }
}
