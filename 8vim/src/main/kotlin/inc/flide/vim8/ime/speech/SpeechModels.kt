package inc.flide.vim8.ime.speech

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** A downloadable Whisper model (ggml format). Models are fetched on demand, never bundled. */
data class SpeechModel(
    val id: String,
    val label: String,
    val url: String,
    val sizeMb: Int,
    val sha256: String,
    val multilingual: Boolean
) {
    val fileName get() = "$id.bin"
}

object SpeechModels {
    private const val BASE = "https://keyboard.futo.org/voice-input-"

    val catalog = listOf(
        SpeechModel(
            "english-39",
            "English – tiny (fastest)",
            BASE + "english-39.bin",
            42,
            "4b5480aa1b14a7efc5b578ef176510970a898049671c3cd237285b3e3f6bfbfc",
            false
        ),
        SpeechModel(
            "english-74",
            "English – base",
            BASE + "english-74.bin",
            78,
            "e9b4b7b81b8a28769e8aa9962aa39bb9f21b622cf6a63982e93f065ed5caf1c8",
            false
        ),
        SpeechModel(
            "english-244",
            "English – small (most accurate)",
            BASE + "english-244.bin",
            252,
            "58fbe949992dafed917590d58bc12ca577b08b9957f0b3e0d7ee71b64bed3aa8",
            false
        ),
        SpeechModel(
            "multilingual-39",
            "Multilingual – tiny",
            BASE + "multilingual-39.bin",
            42,
            "07aa4d514144deacf5ffec5cacb36c93dee272fda9e64ac33a801f8cd5cbd953",
            true
        ),
        SpeechModel(
            "multilingual-74",
            "Multilingual – base",
            BASE + "multilingual-74.bin",
            78,
            "e44f352c9aa2c3609dece20c733c4ad4a75c28cd9ab07d005383df55fa96efc4",
            true
        ),
        SpeechModel(
            "multilingual-244",
            "Multilingual – small",
            BASE + "multilingual-244.bin",
            252,
            "15ef255465a6dc582ecf1ec651a4618c7ee2c18c05570bbe46493d248d465ac4",
            true
        )
    )

    fun byId(id: String) = catalog.firstOrNull { it.id == id } ?: catalog.first()
}

sealed interface DownloadState {
    data class Downloading(val modelId: String, val percent: Int) : DownloadState
    data class Failed(val modelId: String) : DownloadState
}

/** Stores models in `filesDir/speech-models` and tracks download progress. */
class SpeechModelStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "speech-models")

    private val _state = MutableStateFlow<DownloadState?>(null)
    val state: StateFlow<DownloadState?> = _state

    private val _version = MutableStateFlow(0)

    /** Changes whenever the set of installed models changes. */
    val version: StateFlow<Int> = _version

    fun file(model: SpeechModel) = File(root, model.fileName)

    fun isInstalled(id: String) = file(SpeechModels.byId(id)).exists()

    fun delete(id: String) {
        file(SpeechModels.byId(id)).delete()
        _version.update { it + 1 }
    }

    /** Downloads [model] and verifies its checksum. Returns true on success. */
    suspend fun download(model: SpeechModel): Boolean = withContext(Dispatchers.IO) {
        if (_state.value is DownloadState.Downloading) return@withContext false
        root.mkdirs()
        val part = File(root, model.fileName + ".part")
        try {
            _state.value = DownloadState.Downloading(model.id, 0)
            val digest = fetch(model, part)
            if (!digest.equals(model.sha256, ignoreCase = true)) throw IOException("bad checksum")
            if (!part.renameTo(file(model))) throw IOException("rename failed")
            _state.value = null
            _version.update { it + 1 }
            true
        } catch (e: IOException) {
            Log.w("SpeechModels", "download of ${model.id} failed", e)
            part.delete()
            _state.value = DownloadState.Failed(model.id)
            false
        }
    }

    /** Streams the model to [target] and returns the SHA-256 of what was written. */
    private fun fetch(model: SpeechModel, target: File): String {
        val connection = URL(model.url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        if (total > 0) {
                            _state.value = DownloadState.Downloading(
                                model.id,
                                (done * PERCENT / total).toInt()
                            )
                        }
                    }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000
        const val BUFFER_SIZE = 64 * 1024
        const val PERCENT = 100
    }
}
