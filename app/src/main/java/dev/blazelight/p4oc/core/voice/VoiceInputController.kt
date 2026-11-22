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
import kotlin.math.sqrt

/**
 * Push-to-talk dictation. Audio is transcribed in bounded windows (not by re-reading a growing
 * buffer, which made every pass slower than the last): each window is noise-gated, transcribed,
 * merged with the committed text and emitted. Latency per update stays roughly constant.
 */
class VoiceInputController(
    private val modelStore: WhisperModelStore,
) {
    enum class Phase { Idle, Preparing, Listening, Error }

    data class State(val phase: Phase = Phase.Idle, val message: String? = null)

    private companion object {
        const val TAG = "VoiceInput"
        const val SAMPLE_RATE = 16_000
        const val FRAME_SIZE = 480
        const val MIN_WINDOW_SECONDS = 2.5f
        const val MAX_WINDOW_SECONDS = 9f
        const val OVERLAP_SECONDS = 0.6f
        const val PASS_INTERVAL_MS = 1_500L
        const val SILENCE_TAIL_MS = 350
        const val NOISE_FLOOR_PERCENTILE = 0.1f
        const val NOISE_MULTIPLIER = 2.5f
        const val MIN_FRAME_RMS = 0.004f
        const val MAX_OVERLAP_WORDS = 12
        val WHITESPACE = Regex("\\s+")
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

    @Suppress("TooGenericExceptionCaught")
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
                audio.startRecording()
                _state.value = State(Phase.Listening)
                runSession(engine, audio, onText)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.w(TAG, "Voice input failed: ${error.javaClass.simpleName}")
                _state.value = State(Phase.Error, "Voice input failed. Try again.")
            } finally {
                recorder?.runCatching { stop() }
                recorder?.runCatching { release() }
                recorder = null
            }
        }
    }

    /** Stops recording, transcribes the tail once and keeps the text in the field. */
    fun stop(onText: (String) -> Unit) = finish(onText, send = false)

    /** Stops recording, transcribes the tail and hands the text to the caller for sending. */
    fun stopAndSend(onText: (String) -> Unit) = finish(onText, send = true)

    fun cancel() {
        stopping = true
        sessionJob?.cancel()
        sessionJob = null
        recorder?.runCatching { stop() }
        recorder?.runCatching { release() }
        recorder = null
        _state.value = State()
    }

    fun release() {
        cancel()
        transcriber?.close()
        transcriber = null
        loadedModelId = null
        scope.cancel()
    }

    private fun finish(onText: (String) -> Unit, send: Boolean) {
        val job = sessionJob ?: return
        stopping = true
        recorder?.runCatching { stop() }
        scope.launch {
            try {
                val tail = synchronized(windowLock) { window.toSamples() }
                val engine = transcriber
                if (engine != null && tail.isNotEmpty()) {
                    val gated = denoise(tail)
                    if (gated.isNotEmpty()) {
                        val text = transcribe(engine, gated)
                        if (text.isNotBlank()) {
                            committed = mergeTranscript(committed, text)
                            onText(committed)
                        }
                    }
                }
                if (send) onText(committed)
            } finally {
                job.cancel()
                sessionJob = null
                _state.value = State()
            }
        }
    }

    // --- session ---

    private val windowLock = Any()
    private val window = ArrayList<Float>(SAMPLE_RATE * 10)
    private var committed: String = ""

    private suspend fun CoroutineScope.runSession(
        engine: WhisperTranscriber,
        audio: AudioRecord,
        onText: (String) -> Unit,
    ) {
        synchronized(windowLock) { window.clear() }
        committed = ""
        val chunk = ShortArray(recorderBufferSize())
        var lastPassAt = 0L
        while (isActive && !stopping) {
            val read = audio.read(chunk, 0, chunk.size)
            if (read < 0) break
            if (read == 0) continue
            synchronized(windowLock) {
                for (i in 0 until read) window.add(chunk[i] / 32768f)
            }
            val now = System.currentTimeMillis()
            val size = synchronized(windowLock) { window.size }
            if (!shouldTranscribe(size, lastPassAt, now)) continue
            lastPassAt = now
            val pending = synchronized(windowLock) {
                val copy = window.toSamples()
                val keep = (SAMPLE_RATE * OVERLAP_SECONDS).toInt()
                if (copy.size > keep) window.subList(0, copy.size - keep).clear()
                copy
            }
            val gated = denoise(pending)
            if (gated.isEmpty()) continue
            val text = transcribe(engine, gated)
            if (text.isNotBlank()) {
                committed = mergeTranscript(committed, text)
                onText(committed)
            }
        }
    }

    private fun List<Float>.toSamples(): FloatArray = FloatArray(size) { this[it] }

    @Suppress("ReturnCount")
    private fun shouldTranscribe(size: Int, lastPassAt: Long, now: Long): Boolean {
        val minimum = (SAMPLE_RATE * MIN_WINDOW_SECONDS).toInt()
        if (size < minimum) return false
        if (size >= (SAMPLE_RATE * MAX_WINDOW_SECONDS).toInt()) return true
        return now - lastPassAt >= PASS_INTERVAL_MS || hasSilenceTail()
    }

    private fun hasSilenceTail(): Boolean {
        val samples = synchronized(windowLock) {
            val tail = (SAMPLE_RATE * SILENCE_TAIL_MS / 1000).toInt()
            if (window.size < tail) return false
            window.subList(window.size - tail, window.size).toSamples()
        }
        var sum = 0f
        for (sample in samples) sum += sample * sample
        return sqrt(sum / samples.size) < MIN_FRAME_RMS
    }

    // --- model ---

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

    private suspend fun transcribe(engine: WhisperTranscriber, samples: FloatArray): String =
        transcribeLock.withLock {
            runCatching { engine.transcribe(samples) }
                .getOrElse {
                    AppLog.w(TAG, "Transcription failed: ${it.javaClass.simpleName}")
                    ""
                }
                .cleanTranscript()
        }

    // --- text ---

    /** Appends [segment], dropping the words already covered by the window overlap. */
    @Suppress("ReturnCount")
    private fun mergeTranscript(committed: String, segment: String): String {
        if (committed.isBlank()) return segment
        val previous = committed.trim().split(WHITESPACE)
        val next = segment.trim().split(WHITESPACE)
        val maxOverlap = minOf(previous.size, next.size, MAX_OVERLAP_WORDS)
        for (size in maxOverlap downTo 1) {
            val tail = previous.takeLast(size).joinToString(" ")
            val head = next.take(size).joinToString(" ")
            if (tail.equals(head, ignoreCase = true)) {
                return (previous + next.drop(size)).joinToString(" ")
            }
        }
        return previous.joinToString(" ") + " " + next.joinToString(" ")
    }

    /** Drops whisper's non-speech markers such as "[BLANK_AUDIO]" or "(silence)". */
    private fun String.cleanTranscript(): String =
        replace(Regex("\\[[A-Z_ ]+\\]|\\([a-z ]+\\)"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    // --- audio ---

    /**
     * Energy gate: estimates the noise floor from the quietest frames, zeroes everything below a
     * multiple of it and trims the silent edges. Whisper hallucinates words on background noise, so the
     * quiet parts never reach the model.
     */
    @Suppress("ReturnCount")
    private fun denoise(samples: FloatArray): FloatArray {
        if (samples.size < FRAME_SIZE * 4) return samples
        val frames = samples.size / FRAME_SIZE
        val energies = FloatArray(frames)
        for (frame in 0 until frames) {
            var sum = 0f
            val start = frame * FRAME_SIZE
            for (i in start until start + FRAME_SIZE) sum += samples[i] * samples[i]
            energies[frame] = sqrt(sum / FRAME_SIZE)
        }
        val floor = energies.sorted()[(frames * NOISE_FLOOR_PERCENTILE).toInt().coerceIn(0, frames - 1)]
        val threshold = maxOf(MIN_FRAME_RMS, floor * NOISE_MULTIPLIER)
        var firstFrame = -1
        var lastFrame = -1
        val gated = FloatArray(samples.size)
        for (frame in 0 until frames) {
            if (energies[frame] < threshold) continue
            if (firstFrame < 0) firstFrame = frame
            lastFrame = frame
            val start = frame * FRAME_SIZE
            for (i in start until start + FRAME_SIZE) gated[i] = samples[i]
        }
        if (firstFrame < 0) return FloatArray(0)
        return gated.copyOfRange(firstFrame * FRAME_SIZE, (lastFrame + 1) * FRAME_SIZE)
    }

    /** Platform noise suppression / echo cancellation when the device offers them. */
    private fun attachAudioEffects(record: AudioRecord) {
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
        attachAudioEffects(record)
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
