package dev.blazelight.p4oc.core.voice

/** On-device whisper.cpp models the app can download. */
enum class WhisperModel(
    val id: String,
    val label: String,
    val fileName: String,
    val approxBytes: Long,
    val url: String,
) {
    Tiny(
        id = "tiny.en",
        label = "Tiny (English)",
        fileName = "ggml-tiny.en.bin",
        approxBytes = 75L * 1024 * 1024,
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin",
    ),
    Base(
        id = "base.en",
        label = "Base (English)",
        fileName = "ggml-base.en.bin",
        approxBytes = 142L * 1024 * 1024,
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin",
    ),
    Small(
        id = "small.en",
        label = "Small (English)",
        fileName = "ggml-small.en.bin",
        approxBytes = 466L * 1024 * 1024,
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.en.bin",
    );

    companion object {
        val DEFAULT = Tiny

        fun fromId(id: String?): WhisperModel = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
