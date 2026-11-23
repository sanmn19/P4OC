package dev.blazelight.p4oc.core.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Streaming sherpa-onnx path with real speech: the model must be downloaded through
 * Settings -> Voice (or copied into the app's files dir) before running.
 */
@RunWith(AndroidJUnit4::class)
class SherpaSampleTest {

    @Test
    fun transcribesBundledSpeechSample() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SherpaModelStore(context)
        if (!store.isReady()) {
            // Self-contained: download the streaming model once for this test run.
            kotlinx.coroutines.runBlocking { store.download() }
        }
        assumeTrue("sherpa model unavailable", store.isReady())

        val wav = context.assets.open("jfk.wav").use { it.readBytes() }
        val samples = WavPcm.toFloatSamples(wav)

        val text = SherpaStreamingRecognizer(store).use { recognizer ->
            var offset = 0
            while (offset < samples.size) {
                val end = minOf(offset + 1_600, samples.size)
                recognizer.accept(samples.copyOfRange(offset, end))
                offset = end
            }
            recognizer.text()
        }
        println("SHERPA_SAMPLE_TEXT=$text")

        assertTrue("expected a transcript, got '$text'", text.length > 10)
        assertTrue(
            "expected the sample's words, got '$text'",
            text.lowercase().contains("ask not") || text.lowercase().contains("country"),
        )
    }
}
