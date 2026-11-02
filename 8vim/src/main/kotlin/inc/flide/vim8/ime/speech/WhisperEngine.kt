package inc.flide.vim8.ime.speech

import java.io.File
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext

@OptIn(DelicateCoroutinesApi::class)
private val inferenceContext = newSingleThreadContext("whisper-inference")

class InvalidModelException : Exception("The speech model could not be loaded")

/** Thin wrapper over a whisper.cpp context. All inference runs on one dedicated thread. */
class WhisperEngine(modelFile: File) {
    private var handle: Long = openNative(modelFile.absolutePath)

    init {
        if (handle == 0L) throw InvalidModelException()
    }

    /** [samples] are 16 kHz mono floats in [-1, 1]. [language] is an ISO code or "auto". */
    suspend fun infer(samples: FloatArray, language: String): String =
        withContext(inferenceContext) {
            check(handle != 0L) { "engine already closed" }
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            inferNative(handle, samples, language, threads).stripNonSpeechMarkers()
        }

    suspend fun close() = withContext(inferenceContext) {
        if (handle != 0L) closeNative(handle)
        handle = 0L
    }

    private external fun openNative(path: String): Long
    private external fun inferNative(
        handle: Long,
        samples: FloatArray,
        language: String,
        threads: Int
    ): String
    private external fun closeNative(handle: Long)

    companion object {
        init {
            System.loadLibrary("vim8whisper")
        }
    }
}

private val NON_SPEECH_MARKER = Regex("""\[[A-Za-z_ ]+]|\([A-Za-z_ ]+\)|\*[A-Za-z_ ]+\*""")

/** Whisper emits tags such as [BLANK_AUDIO] or (music) for non-speech; they are not text. */
internal fun String.stripNonSpeechMarkers(): String =
    replace(NON_SPEECH_MARKER, "").replace(Regex("\\s+"), " ").trim()
