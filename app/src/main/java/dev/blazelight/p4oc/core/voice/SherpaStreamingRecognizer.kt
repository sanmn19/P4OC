package dev.blazelight.p4oc.core.voice

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.Closeable

/**
 * True streaming transducer decoder: feed audio as it arrives, decode what is ready and read the partial
 * result. No re-transcription of earlier audio, so latency does not grow with dictation length.
 */
class SherpaStreamingRecognizer(
    private val modelStore: SherpaModelStore,
) : Closeable {

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FEATURE_DIM = 80
        const val NUM_THREADS = 2
    }

    // Absolute file paths: sherpa-onnx requires a null AssetManager in that mode.
    private val recognizer = OnlineRecognizer(
        null,
        OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = modelStore.encoderFile().absolutePath,
                    decoder = modelStore.decoderFile().absolutePath,
                    joiner = modelStore.joinerFile().absolutePath,
                ),
                tokens = modelStore.tokensFile().absolutePath,
                numThreads = NUM_THREADS,
                // Empty = detect the transducer family from the model metadata (Kroko zipformer2).
                modelType = "",
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
            enableEndpoint = true,
        ),
    )

    private val stream: OnlineStream = recognizer.createStream()

    fun accept(samples: FloatArray) {
        if (samples.isEmpty()) return
        stream.acceptWaveform(samples, SAMPLE_RATE)
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
        }
    }

    fun text(): String = recognizer.getResult(stream).text.trim()

    fun isEndpoint(): Boolean = recognizer.isEndpoint(stream)

    fun reset() {
        recognizer.reset(stream)
    }

    override fun close() {
        runCatching { stream.release() }
        runCatching { recognizer.release() }
    }
}
