package dev.blazelight.p4oc.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilePathValidatorTest {
    @Test
    fun `read list root variants normalize to empty`() {
        listOf("", " ", ".", "/", "///").forEach { path ->
            assertEquals("", FilePathValidator.normalizeForReadOrList(path).getOrThrow())
        }
    }

    @Test
    fun `absolute mutation keeps validated absolute paths verbatim`() {
        assertEquals(
            "/home/zan/Code/new-app",
            FilePathValidator.normalizeForAbsoluteMutation("/home/zan/Code/new-app").getOrThrow(),
        )
    }

    @Test
    fun `absolute mutation rejects traversal, scheme, drive, home and whitespace variants`() {
        listOf(
            "/etc/../secret",
            "/etc/dot/./x",
            "/doubles//slash",
            "/",
            "/trailing//",
            "relative/path",
            "~/home/escaped",
            "/windows\\backslash",
            "C:/Users/file.txt",
            "http://evil/path",
            "/path ", // trailing whitespace
            " /path", // leading whitespace
            "/" + "x".repeat(301),
        ).forEach { path ->
            assertTrue(
                "expected rejection for $path",
                FilePathValidator.normalizeForAbsoluteMutation(path).isFailure,
            )
        }
    }

    @Test
    fun `read list normalizes harmless relative paths`() {
        assertEquals("src/Main.kt", FilePathValidator.normalizeForReadOrList(" src//./Main.kt ").getOrThrow())
    }

    @Test
    fun `read list rejects unsafe paths`() {
        listOf(
            "/etc/passwd",
            "//server/path",
            "..",
            "../secret",
            "src/..",
            "src/../secret",
            "src\\..\\secret",
            "C:/Users/file.txt",
            "file:/tmp/file.txt",
            "http://example.test/file.txt",
            "~/secret",
            "safe\u0000path",
        ).forEach { path ->
            assertTrue("Expected path to be rejected: $path", FilePathValidator.normalizeForReadOrList(path).isFailure)
        }
    }

    @Test
    fun `mutation rejects root variants`() {
        listOf("", " ", ".", "/", "///").forEach { path ->
            assertTrue(
                "Expected mutation root to be rejected: $path",
                FilePathValidator.normalizeForMutation(path).isFailure
            )
        }
    }

    @Test
    fun `mutation accepts safe relative paths`() {
        assertEquals("src/Main.kt", FilePathValidator.normalizeForMutation("src//./Main.kt").getOrThrow())
        assertEquals("report:v2.txt", FilePathValidator.normalizeForMutation("report:v2.txt").getOrThrow())
    }

    @Test
    fun `mutation rejects file paths with surrounding whitespace`() {
        listOf(" file.txt", "file.txt ", "dir/ file.txt ").forEach { path ->
            assertTrue(
                "Expected path with surrounding whitespace to be rejected: <$path>",
                FilePathValidator.normalizeForMutation(path).isFailure
            )
        }
    }
}
