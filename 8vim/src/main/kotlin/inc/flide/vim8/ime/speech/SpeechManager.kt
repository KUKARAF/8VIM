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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Offline speech-to-text with a downloaded Whisper model. While the dictation key is held the
 * microphone is recorded (16 kHz mono) and transcribed on-device, either phrase by phrase at
 * pauses (live) or in one go on release. [level] exposes the live microphone loudness (0..1) for the UI.
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
     * Starts recording. With [live] the audio is cut at pauses and every finished phrase is
     * transcribed and delivered to [onText] while the key is still held; otherwise the whole
     * recording is delivered once, after [stop]. Returns false when dictation cannot start
     * (missing permission or model, or busy).
     */
    fun start(modelId: String, live: Boolean, onText: (String) -> Unit): Boolean {
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
        job = scope.launch { run(modelId, live, onText) }
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

    private suspend fun run(modelId: String, live: Boolean, onText: (String) -> Unit) {
        val segments = Channel<FloatArray>(Channel.UNLIMITED)
        val consumer = scope.launch {
            try {
                for (segment in segments) {
                    isTranscribing = true
                    val text = transcribe(modelId, segment)
                    if (text.isNotEmpty()) onText(text)
                }
            } catch (e: InvalidModelException) {
                stopRequested = true
                toast(R.string.speech__model_invalid)
            } finally {
                isTranscribing = false
            }
        }
        try {
            withContext(Dispatchers.IO) { record(live, segments) }
        } catch (e: IOException) {
            // Nothing more to deliver; fall through and finish what was already queued.
        } finally {
            isListening = false
            level = 0f
            segments.close()
        }
        consumer.join()
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
    private suspend fun record(live: Boolean, segments: Channel<FloatArray>) {
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
        val maxSegment = if (live) MAX_LIVE_SEGMENT_SAMPLES else MAX_SAMPLES
        val segment = FloatArray(maxSegment)
        var count = 0
        var speechSamples = 0
        var silenceRun = 0
        var noise = INITIAL_NOISE
        val chunk = ShortArray(SAMPLE_RATE / CHUNKS_PER_SECOND)
        try {
            recorder.startRecording()
            while (scopeActive() && !stopRequested) {
                val read = recorder.read(chunk, 0, chunk.size)
                if (read < 0) throw IOException("read failed")
                if (read == 0) continue
                if (count + read > maxSegment) {
                    if (!live) break
                    segments.trySend(segment.copyOf(count))
                    count = 0
                    speechSamples = 0
                    silenceRun = 0
                }
                var sum = 0.0
                for (i in 0 until read) {
                    val v = chunk[i] / PCM_MAX
                    segment[count + i] = v
                    sum += v * v
                }
                count += read
                val rms = sqrt(sum / read).toFloat()
                publishLevel(rms)
                if (!live) continue
                val threshold = maxOf(MIN_SPEECH_RMS, noise * SPEECH_OVER_NOISE)
                if (rms > threshold) {
                    speechSamples += read
                    silenceRun = 0
                } else {
                    silenceRun += read
                    noise = noise * NOISE_DECAY + rms * (1 - NOISE_DECAY)
                }
                if (speechSamples == 0) {
                    // Still waiting for speech: keep only a short lead-in.
                    if (count > PREROLL_SAMPLES) {
                        segment.copyInto(segment, 0, count - PREROLL_SAMPLES, count)
                        count = PREROLL_SAMPLES
                    }
                } else if (silenceRun >= PAUSE_SAMPLES) {
                    if (speechSamples >= MIN_SPEECH_SAMPLES) {
                        segments.trySend(segment.copyOf(count - (silenceRun - TAIL_SAMPLES)))
                    }
                    val keep = minOf(PREROLL_SAMPLES, count)
                    segment.copyInto(segment, 0, count - keep, count)
                    count = keep
                    speechSamples = 0
                    silenceRun = 0
                }
            }
        } finally {
            recorder.stop()
            recorder.release()
        }
        val worthSending = if (live) speechSamples >= MIN_SPEECH_SAMPLES else count >= MIN_SAMPLES
        if (worthSending) segments.trySend(segment.copyOf(count))
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
        const val MAX_LIVE_SEGMENT_SAMPLES = SAMPLE_RATE * 25
        const val PAUSE_SAMPLES = SAMPLE_RATE * 7 / 10
        const val TAIL_SAMPLES = SAMPLE_RATE / 4
        const val PREROLL_SAMPLES = SAMPLE_RATE * 3 / 10
        const val MIN_SPEECH_SAMPLES = SAMPLE_RATE / 5
        const val MIN_SPEECH_RMS = 0.012f
        const val SPEECH_OVER_NOISE = 2.5f
        const val INITIAL_NOISE = 0.004f
        const val NOISE_DECAY = 0.95f
        const val LEVEL_GAIN = 3.5f
        const val LEVEL_DECAY = 0.7f
    }
}
