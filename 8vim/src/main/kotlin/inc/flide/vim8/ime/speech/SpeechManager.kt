package inc.flide.vim8.ime.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import inc.flide.vim8.R
import java.io.IOException
import java.util.Locale
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Offline speech-to-text with a downloaded Whisper model. While the dictation key is held the
 * microphone is recorded (16 kHz mono); on release the audio is transcribed on-device and
 * delivered to the caller. [level] exposes the live microphone loudness (0..1) for the UI.
 */
class SpeechManager(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val models = SpeechModelStore(appContext)

    /** Smoothed microphone loudness in 0..1; only meaningful while [isListening]. */
    var level by mutableFloatStateOf(0f)
        private set

    /** True from key press until the recording has ended. */
    var isListening by mutableStateOf(false)
        private set

    /** True while a recording is being transcribed. */
    var isTranscribing by mutableStateOf(false)
        private set

    // Only touched on the main thread.
    private var engine: WhisperEngine? = null
    private var engineModelId: String? = null
    private var job: Job? = null

    @Volatile
    private var stopRequested = false

    /**
     * Starts recording. [final] receives the transcript (empty when nothing was recognised).
     * Returns false when dictation cannot start (missing permission or model, or busy).
     */
    fun start(modelId: String, final: (String) -> Unit): Boolean {
        if (isListening || isTranscribing) return false
        if (!MicPermissionActivity.hasPermission(appContext)) {
            MicPermissionActivity.request(appContext)
            toast(R.string.speech__permission_denied)
            return false
        }
        if (!models.isInstalled(modelId)) {
            toast(R.string.speech__model_missing)
            return false
        }
        stopRequested = false
        isListening = true
        job = scope.launch { run(modelId, final) }
        return true
    }

    /** Ends the recording; the transcript is then delivered. */
    fun stop() {
        stopRequested = true
    }

    fun shutdown() {
        stopRequested = true
        job?.cancel()
        isListening = false
        isTranscribing = false
        level = 0f
        val old = engine
        engine = null
        engineModelId = null
        if (old != null) scope.launch { old.close() }
    }

    private suspend fun run(modelId: String, final: (String) -> Unit) {
        var text = ""
        try {
            val samples = withContext(Dispatchers.IO) { record() }
            isListening = false
            level = 0f
            if (samples.size >= MIN_SAMPLES) {
                isTranscribing = true
                text = transcribe(modelId, samples)
            }
        } catch (e: IOException) {
            text = ""
        } catch (e: InvalidModelException) {
            toast(R.string.speech__model_invalid)
        } finally {
            isListening = false
            isTranscribing = false
            level = 0f
        }
        final(text)
    }

    private suspend fun transcribe(modelId: String, samples: FloatArray): String {
        val model = SpeechModels.byId(modelId)
        if (engineModelId != modelId) {
            engine?.close()
            engine = null
            engineModelId = null
            engine = withContext(Dispatchers.IO) { WhisperEngine(models.file(model)) }
            engineModelId = modelId
        }
        val language = if (model.multilingual) Locale.getDefault().language else "en"
        return engine?.infer(samples, language).orEmpty()
    }

    @SuppressLint("MissingPermission")
    private suspend fun record(): FloatArray {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) throw IOException("no audio input")
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, SAMPLE_RATE)
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IOException("microphone unavailable")
        }
        val out = FloatArray(MAX_SAMPLES)
        var count = 0
        val chunk = ShortArray(SAMPLE_RATE / CHUNKS_PER_SECOND)
        try {
            recorder.startRecording()
            while (scopeActive() && !stopRequested && count < MAX_SAMPLES) {
                val read = recorder.read(chunk, 0, chunk.size)
                if (read < 0) throw IOException("read failed")
                var sum = 0.0
                for (i in 0 until read) {
                    val v = chunk[i] / PCM_MAX
                    if (count < MAX_SAMPLES) out[count++] = v
                    sum += v * v
                }
                if (read > 0) publishLevel(sqrt(sum / read).toFloat())
            }
        } finally {
            recorder.stop()
            recorder.release()
        }
        return out.copyOf(count)
    }

    private fun scopeActive() = job?.isActive != false

    private suspend fun publishLevel(rms: Float) {
        // Speech sits around 0.02..0.2 RMS; compress so quiet talking still moves the ring.
        val target = (sqrt(rms) * LEVEL_GAIN).coerceIn(0f, 1f)
        withContext(Dispatchers.Main.immediate) {
            level = if (target > level) target else level * LEVEL_DECAY + target * (1 - LEVEL_DECAY)
        }
    }

    private fun toast(resId: Int) {
        scope.launch { Toast.makeText(appContext, resId, Toast.LENGTH_SHORT).show() }
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNKS_PER_SECOND = 20
        const val PCM_MAX = 32768f
        const val MAX_SAMPLES = SAMPLE_RATE * 30
        const val MIN_SAMPLES = SAMPLE_RATE / 2
        const val LEVEL_GAIN = 3.5f
        const val LEVEL_DECAY = 0.7f
    }
}
