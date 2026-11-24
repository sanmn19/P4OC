@file:Suppress("MagicNumber", "TooGenericExceptionCaught")

package dev.blazelight.p4oc.core.voice

import android.content.Context
import dev.blazelight.p4oc.core.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads the sherpa-onnx streaming Zipformer (English, int8) model files into app-private storage.
 * Unlike whisper this is a small bundle of ONNX files rather than one .bin.
 */
class SherpaModelStore(private val context: Context) {

    sealed interface State {
        data object NotDownloaded : State
        data class Downloading(val bytes: Long, val total: Long) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private companion object {
        const val TAG = "SherpaModelStore"
        const val DIR = "sherpa"
        const val BASE =
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06/resolve/main/"
        const val ENCODER = "encoder.onnx"
        const val DECODER = "decoder.onnx"
        const val JOINER = "joiner.onnx"
        const val TOKENS = "tokens.txt"
        val FILES = listOf(ENCODER, DECODER, JOINER, TOKENS)
        const val APPROX_BYTES = 70L * 1024 * 1024
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<State>(State.NotDownloaded)
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        _state.value = if (isReady()) State.Ready else State.NotDownloaded
    }

    fun modelDir(): File = File(context.filesDir, DIR)

    fun isReady(): Boolean = FILES.all { File(modelDir(), it).length() > 0 }

    fun tokensFile(): File = File(modelDir(), TOKENS)
    fun encoderFile(): File = File(modelDir(), ENCODER)
    fun decoderFile(): File = File(modelDir(), DECODER)
    fun joinerFile(): File = File(modelDir(), JOINER)

    @Suppress("CyclomaticComplexMethod")
    suspend fun download(): Boolean = withContext(Dispatchers.IO) {
        val dir = modelDir().apply { mkdirs() }
        _state.value = State.Downloading(0, APPROX_BYTES)
        try {
            var copied = 0L
            for (name in FILES) {
                val target = File(dir, name)
                val partial = File(dir, "$name.part")
                val request = Request.Builder().url(BASE + name).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code} for $name")
                    val body = response.body ?: error("empty response for $name")
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                copied += read
                                _state.value = State.Downloading(copied, APPROX_BYTES)
                            }
                        }
                    }
                }
                if (partial.length() <= 0L) error("empty file $name")
                if (target.exists()) target.delete()
                if (!partial.renameTo(target)) error("could not move $name into place")
            }
            _state.value = State.Ready
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.w(TAG, "Sherpa model download failed: ${error.javaClass.simpleName}")
            _state.value = State.Failed("Download failed. Check the connection and try again.")
            false
        }
    }

    fun delete() {
        modelDir().listFiles()?.forEach { it.delete() }
        _state.value = State.NotDownloaded
    }
}
