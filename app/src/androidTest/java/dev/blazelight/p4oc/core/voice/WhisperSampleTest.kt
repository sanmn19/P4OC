package dev.blazelight.p4oc.core.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end check of the native whisper.cpp path with a real 16 kHz speech sample: the model must be
 * downloaded through Settings -> Voice first (debug builds keep it in the app's files dir).
 */
@RunWith(AndroidJUnit4::class)
class WhisperSampleTest {

    @Test
    fun transcribesBundledSpeechSample() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(context.filesDir, "whisper/${WhisperModel.Tiny.fileName}")
        assumeTrue("model not downloaded", model.length() > 0)

        val wav = context.assets.open("jfk.wav").use { it.readBytes() }
        val samples = WavPcm.toFloatSamples(wav)

        val text = WhisperTranscriber(model).use { it.transcribe(samples) }
        println("WHISPER_SAMPLE_TEXT=$text")

        assertTrue("expected a transcript, got '$text'", text.length > 10)
        assertTrue(
            "expected the sample's words, got '$text'",
            text.lowercase().contains("ask not") || text.lowercase().contains("country"),
        )
    }
}
