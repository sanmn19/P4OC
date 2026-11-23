@file:Suppress("MagicNumber", "LoopWithTooManyJumpStatements", "TooGenericExceptionCaught")

package dev.blazelight.p4oc.core.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.blazelight.p4oc.core.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Continuous dictation with sherpa-onnx streaming Zipformer: audio is fed as it arrives and the decoder
 * emits partial text for the current utterance, so latency stays flat and nothing is re-transcribed.
 */
class SherpaVoiceController(
    private val modelStore: SherpaModelStore,
) : VoiceController {

    private companion object {
        const val TAG = "SherpaVoice"
        const val SAMPLE_RATE = 16_000
        const val CHUNK_SAMPLES = 1_600 // 100 ms
        const val EMIT_INTERVAL_MS = 250L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val _state = MutableStateFlow(VoiceInputState())
    override val state: StateFlow<VoiceInputState> = _state.asStateFlow()

    private var sessionJob: Job? = null

    @Volatile
    private var stopping = false
    private var recorder: AudioRecord? = null
    private var recognizer: SherpaStreamingRecognizer? = null

    @SuppressLint("MissingPermission")
    override fun start(onText: (String) -> Unit) {
        if (_state.value.phase == VoicePhase.Listening || _state.value.phase == VoicePhase.Preparing) return
        if (!modelStore.isReady()) {
            _state.value = VoiceInputState(VoicePhase.Error, "Download the Sherpa model in Settings → Voice first.")
            return
        }
        _state.value = VoiceInputState(VoicePhase.Preparing)
        stopping = false
        sessionJob?.cancel()
        sessionJob = scope.launch {
            try {
                val engine = recognizer ?: SherpaStreamingRecognizer(modelStore).also { recognizer = it }
                val audio = openRecorder() ?: run {
                    _state.value = VoiceInputState(VoicePhase.Error, "Microphone unavailable.")
                    return@launch
                }
                audio.startRecording()
                _state.value = VoiceInputState(VoicePhase.Listening)
                runSession(engine, audio, onText)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.w(TAG, "Sherpa dictation failed: ${error.javaClass.simpleName}")
                _state.value = VoiceInputState(VoicePhase.Error, "Voice input failed. Try again.")
            } finally {
                recorder?.runCatching { stop() }
                recorder?.runCatching { release() }
                recorder = null
            }
        }
    }

    override fun stop(onText: (String) -> Unit) = finish(onText, send = false)

    override fun stopAndSend(onText: (String) -> Unit) = finish(onText, send = true)

    override fun cancel() {
        stopping = true
        sessionJob?.cancel()
        sessionJob = null
        recorder?.runCatching { stop() }
        recorder?.runCatching { release() }
        recorder = null
        _state.value = VoiceInputState()
    }

    override fun release() {
        cancel()
        recognizer?.close()
        recognizer = null
        scope.cancel()
    }

    private fun finish(onText: (String) -> Unit, send: Boolean) {
        val job = sessionJob ?: return
        stopping = true
        recorder?.runCatching { stop() }
        scope.launch {
            try {
                val engine = recognizer
                if (engine != null) {
                    val finalText = withLock { engine.text() }
                    val combined = (committed + " " + finalText).trim()
                    if (combined.isNotBlank()) onText(combined)
                    if (send) onText(combined)
                } else if (send) {
                    onText(committed)
                }
            } finally {
                job.cancel()
                sessionJob = null
                _state.value = VoiceInputState()
            }
        }
    }

    private suspend fun <T> withLock(block: () -> T): T = lock.withLock { block() }

    private var committed = ""
    private var lastEmitAt = 0L

    private suspend fun CoroutineScope.runSession(
        engine: SherpaStreamingRecognizer,
        audio: AudioRecord,
        onText: (String) -> Unit,
    ) {
        committed = ""
        val chunk = ShortArray(CHUNK_SAMPLES)
        while (isActive && !stopping) {
            val read = audio.read(chunk, 0, chunk.size)
            if (read < 0) break
            if (read == 0) continue
            val samples = FloatArray(read) { chunk[it] / 32768f }
            val partial = withLock {
                engine.accept(samples)
                engine.text()
            }
            val now = System.currentTimeMillis()
            if (partial.isNotBlank() && now - lastEmitAt >= EMIT_INTERVAL_MS) {
                lastEmitAt = now
                onText((committed + " " + partial).trim())
            }
            if (withLock { engine.isEndpoint() }) {
                committed = (committed + " " + partial).trim()
                withLock { engine.reset() }
                onText(committed)
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("ReturnCount")
    private fun openRecorder(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2,
            )
        }.getOrNull() ?: return null
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        runCatching {
            if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                android.media.audiofx.NoiseSuppressor.create(record.audioSessionId)?.enabled = true
            }
        }
        runCatching {
            if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                android.media.audiofx.AcousticEchoCanceler.create(record.audioSessionId)?.enabled = true
            }
        }
        recorder = record
        return record
    }
}
