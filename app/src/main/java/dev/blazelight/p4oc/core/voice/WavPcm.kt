@file:Suppress("MagicNumber")

package dev.blazelight.p4oc.core.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal RIFF/WAVE reader for 16-bit PCM, used by the debug sample transcription check. */
object WavPcm {

    fun toFloatSamples(bytes: ByteArray): FloatArray {
        require(bytes.size > 44) { "not a wav" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var channels = 1
        var sampleRate = 16_000
        var bitsPerSample = 16
        var dataOffset = -1
        var dataLength = 0
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val length = buffer.getInt(offset + 4)
            val body = offset + 8
            when (id) {
                "fmt " -> {
                    // Absolute indices are relative to the whole array, not to the chunk body.
                    channels = buffer.getShort(body + 2).toInt()
                    sampleRate = buffer.getInt(body + 4)
                    bitsPerSample = buffer.getShort(body + 14).toInt()
                }
                "data" -> {
                    dataOffset = body
                    dataLength = length.coerceAtMost(bytes.size - body)
                }
            }
            offset = body + length + (length and 1)
        }
        require(dataOffset >= 0 && bitsPerSample == 16) { "unsupported wav" }
        val sampleCount = dataLength / 2
        val samples = FloatArray(sampleCount / channels.coerceAtLeast(1))
        var cursor = dataOffset
        for (i in samples.indices) {
            var sum = 0
            repeat(channels) {
                sum += buffer.getShort(cursor)
                cursor += 2
            }
            samples[i] = (sum / channels.toFloat()) / 32768f
        }
        // Only 16 kHz mono is used by whisper; the bundled sample already matches.
        check(sampleRate == 16_000) { "sample must be 16 kHz" }
        return samples
    }
}
