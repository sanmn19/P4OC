@file:Suppress("MagicNumber", "LoopWithTooManyJumpStatements")

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
import java.io.File

/**
 * Push-to-talk dictation: records 16 kHz mono audio and re-transcribes the buffer with whisper.cpp
 * every [TRANSCRIBE_INTERVAL_MS], so the composer shows text while the user is still speaking.
 */
class VoiceInputController(
    private val modelStore: WhisperModelStore,
) {
    enum class Phase { Idle, Preparing, Listening, Error }

    data class State(val phase: Phase = Phase.Idle, val message: String? = null)

    private companion object {
        const val TAG = "VoiceInput"
        const val SAMPLE_RATE = 16_000
        const val TRANSCRIBE_INTERVAL_MS = 1_200L
        const val MAX_BUFFER_SECONDS = 40
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transcribeLock = Mutex()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var sessionJob: Job? = null

    @Volatile
    private var stopping = false

    private var recorder: AudioRecord? = null
    private var transcriber: WhisperTranscriber? = null
    private var loadedModelId: String? = null

    @Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")
    fun start(model: WhisperModel, onText: (String) -> Unit) {
        if (_state.value.phase == Phase.Listening || _state.value.phase == Phase.Preparing) return
        if (!modelStore.isReady(model)) {
            _state.value = State(Phase.Error, "Download a whisper model in Settings → Voice first.")
            return
        }
        _state.value = State(Phase.Preparing)
        stopping = false
        sessionJob?.cancel()
        sessionJob = scope.launch {
            try {
                val engine = engineFor(model) ?: run {
                    _state.value = State(Phase.Error, "Could not load the whisper model.")
                    return@launch
                }
                val audio = openRecorder() ?: run {
                    _state.value = State(Phase.Error, "Microphone unavailable.")
                    return@launch
                }
                val samples = ArrayList<Float>(SAMPLE_RATE * 10)
                val chunk = ShortArray(recorderBufferSize())
                var lastTranscribed = 0
                var lastPassAt = 0L
                audio.startRecording()
                _state.value = State(Phase.Listening)
                while (isActive && !stopping) {
                    val read = audio.read(chunk, 0, chunk.size)
                    if (read < 0) break
                    if (read == 0) continue
                    for (i in 0 until read) samples.add(chunk[i] / 32768f)
                    trimToWindow(samples)
                    val now = System.currentTimeMillis()
                    if (samples.size - lastTranscribed >= SAMPLE_RATE / 2 &&
                        now - lastPassAt >= TRANSCRIBE_INTERVAL_MS
                    ) {
                        lastTranscribed = samples.size
                        lastPassAt = now
                        val text = transcribe(engine, samples)
                        if (text.isNotBlank()) onText(text)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.w(TAG, "Voice input failed: ${error.javaClass.simpleName}")
                _state.value = State(Phase.Error, "Voice input failed. Try again.")
            } finally {
                recorder?.runCatching { stop() }
                recorder?.release()
                recorder = null
            }
        }
    }

    /** Stops recording and runs one last pass over the full buffer. */
    fun stop(onText: (String) -> Unit) {
        val job = sessionJob ?: return
        stopping = true
        recorder?.runCatching { stop() }
        scope.launch {
            try {
                val engine = transcriber
                if (engine != null) {
                    val samples = lastSamples()
                    if (samples.isNotEmpty()) {
                        val text = transcribe(engine, samples)
                        if (text.isNotBlank()) onText(text)
                    }
                }
            } finally {
                job.cancel()
                sessionJob = null
                recorder?.runCatching { release() }
                recorder = null
                _state.value = State(Phase.Idle)
            }
        }
    }

    fun cancel() {
        sessionJob?.cancel()
        sessionJob = null
        recorder?.runCatching { stop() }
        recorder?.runCatching { release() }
        recorder = null
        _state.value = State(Phase.Idle)
    }

    fun release() {
        cancel()
        transcriber?.close()
        transcriber = null
        loadedModelId = null
        scope.cancel()
    }

    @Suppress("ReturnCount")
    private suspend fun engineFor(model: WhisperModel): WhisperTranscriber? {
        val current = transcriber
        if (current != null && loadedModelId == model.id && current.isReady) return current
        current?.close()
        transcriber = null
        loadedModelId = null
        val file: File = modelStore.modelFile(model)
        val created = WhisperTranscriber(file)
        if (!created.isReady) {
            created.close()
            return null
        }
        transcriber = created
        loadedModelId = model.id
        return created
    }

    private suspend fun transcribe(engine: WhisperTranscriber, samples: List<Float>): String =
        transcribeLock.withLock {
            if (samples.isEmpty()) return@withLock ""
            runCatching { engine.transcribe(samples.toFloatArray()) }
                .getOrElse {
                    AppLog.w(TAG, "Transcription failed: ${it.javaClass.simpleName}")
                    ""
                }
                .cleanTranscript()
        }

    /** Drops whisper's non-speech markers such as "[BLANK_AUDIO]" or "(silence)". */
    private fun String.cleanTranscript(): String =
        replace(Regex("\\[[A-Z_ ]+\\]|\\([a-z ]+\\)"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun lastSamples(): List<Float> = synchronized(samplesLock) { samplesSnapshot.toList() }

    private val samplesLock = Any()
    private var samplesSnapshot: List<Float> = emptyList()

    private fun trimToWindow(samples: ArrayList<Float>) {
        val max = SAMPLE_RATE * MAX_BUFFER_SECONDS
        if (samples.size > max) samples.subList(0, samples.size - max).clear()
        synchronized(samplesLock) { samplesSnapshot = ArrayList(samples) }
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
        recorder = record
        return record
    }

    private fun recorderBufferSize(): Int =
        AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(SAMPLE_RATE / 2)
}
