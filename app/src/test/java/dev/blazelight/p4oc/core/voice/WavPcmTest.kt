package dev.blazelight.p4oc.core.voice

import org.junit.Assert.assertTrue
import org.junit.Test

class WavPcmTest {
    @Test
    fun `parses the bundled 16k mono sample`() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("jfk.wav")!!.use { it.readBytes() }
        val samples = WavPcm.toFloatSamples(bytes)
        assertTrue("samples=${samples.size}", samples.size > 100_000)
    }
}
