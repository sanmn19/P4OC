package dev.blazelight.p4oc.core.voice

import java.io.Closeable
import java.io.File

/** One loaded whisper.cpp context; calls are serialized by the caller. */
class WhisperTranscriber(modelFile: File) : Closeable {
    private val handle: Long = WhisperBridge.nativeInit(modelFile.absolutePath)

    val isReady: Boolean get() = handle != 0L

    fun transcribe(samples: FloatArray, translate: Boolean = false): String {
        if (handle == 0L || samples.isEmpty()) return ""
        return WhisperBridge.nativeTranscribe(handle, samples, samples.size, translate).trim()
    }

    override fun close() {
        if (handle != 0L) WhisperBridge.nativeRelease(handle)
    }
}
