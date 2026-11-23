@file:Suppress("MagicNumber")

package dev.blazelight.p4oc.core.voice

import android.content.Context
import dev.blazelight.p4oc.core.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Downloads and tracks the whisper models stored in the app's private files. */
class WhisperModelStore(private val context: Context) {

    sealed interface State {
        data object NotDownloaded : State
        data class Downloading(val bytes: Long, val total: Long) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private companion object {
        const val TAG = "WhisperModelStore"
        const val DIR = "whisper"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    init {
        refresh()
    }

    fun modelFile(model: WhisperModel): File = File(modelsDir(), model.fileName)

    /** Currently selected model, kept in sync by the settings screen. */
    @Volatile
    private var selected: WhisperModel = WhisperModel.DEFAULT

    fun selectedModel(): WhisperModel = selected

    fun select(model: WhisperModel) {
        selected = model
    }

    fun isReady(model: WhisperModel): Boolean = modelFile(model).length() > 0

    private fun modelsDir(): File = File(context.filesDir, DIR).apply { mkdirs() }

    fun refresh() {
        _states.update { current ->
            current + WhisperModel.entries.associate { model ->
                model.id to if (isReady(model)) State.Ready else State.NotDownloaded
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")
    suspend fun download(model: WhisperModel): Boolean = withContext(Dispatchers.IO) {
        val target = modelFile(model)
        val partial = File(target.path + ".part")
        _states.update { it + (model.id to State.Downloading(0, model.approxBytes)) }
        try {
            val request = Request.Builder().url(model.url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("empty response")
                val total = body.contentLength().takeIf { it > 0 } ?: model.approxBytes
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            _states.update { it + (model.id to State.Downloading(copied, total)) }
                        }
                    }
                }
            }
            if (partial.length() <= 0L) error("empty model")
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) error("could not move the model into place")
            _states.update { it + (model.id to State.Ready) }
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.w(TAG, "Model download failed: ${error.javaClass.simpleName}")
            partial.delete()
            _states.update { it + (model.id to State.Failed("Download failed. Check the connection and try again.")) }
            false
        }
    }

    fun delete(model: WhisperModel) {
        modelFile(model).delete()
        _states.update { it + (model.id to State.NotDownloaded) }
    }
}
