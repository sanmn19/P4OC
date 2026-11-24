package dev.blazelight.p4oc.core.voice

import kotlinx.coroutines.flow.StateFlow

/** Speech-to-text engines the user can pick in Settings -> Voice. */
enum class VoiceAssistant(val id: String, val label: String) {
    Whisper("whisper", "Whisper (on-device)"),
    Sherpa("sherpa", "Sherpa-onnx streaming");

    companion object {
        val DEFAULT = Whisper
        fun fromId(id: String?): VoiceAssistant = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

enum class VoicePhase { Idle, Preparing, Listening, Error }

data class VoiceInputState(val phase: VoicePhase = VoicePhase.Idle, val message: String? = null)

/** Common surface of the dictation engines so the composer does not care which one is selected. */
interface VoiceController {
    val state: StateFlow<VoiceInputState>

    fun start(onText: (String) -> Unit)

    /** Stops and keeps the text in the field. */
    fun stop(onText: (String) -> Unit)

    /** Stops, then hands the final text to the caller (long-press send). */
    fun stopAndSend(onText: (String) -> Unit)

    fun cancel()

    fun release()
}
