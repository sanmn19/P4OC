package dev.blazelight.p4oc.core.voice

/** JNI surface of the vendored whisper.cpp build (arm64 only). */
internal object WhisperBridge {
    init {
        System.loadLibrary("whisper_jni")
    }

    external fun nativeInit(modelPath: String): Long

    external fun nativeTranscribe(handle: Long, samples: FloatArray, sampleCount: Int, translate: Boolean): String

    external fun nativeRelease(handle: Long)
}
