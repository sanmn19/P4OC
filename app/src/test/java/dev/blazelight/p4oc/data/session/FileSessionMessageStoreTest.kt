package dev.blazelight.p4oc.data.session

import dev.blazelight.p4oc.domain.model.Message
import dev.blazelight.p4oc.domain.model.MessageWithParts
import dev.blazelight.p4oc.domain.model.TokenUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileSessionMessageStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val serverKey = "http://test.local"
    private val workspaceKey = "directory:/repo"

    private fun store(maxSessions: Int = 200, maxSessionBytes: Int = 256 * 1024) =
        FileSessionMessageStore(
            rootDir = tmp.newFolder("cache"),
            json = json,
            maxSessions = maxSessions,
            maxSessionBytes = maxSessionBytes,
            ioDispatcher = Dispatchers.Default,
        )

    @Test
    fun `save then load round trips the message window`() = runTest {
        val store = store()
        store.save(
            serverKey,
            workspaceKey,
            "s1",
            CachedSessionMessages(listOf(message("m1"), message("m2")), 7L),
        )
        store.drainWrites()

        val loaded = store.load(serverKey, workspaceKey, "s1")

        assertEquals(listOf("m1", "m2"), loaded?.messages?.map { it.message.id })
        assertEquals(7L, loaded?.cachedAtMs)
    }

    @Test
    fun `missing entry loads as null`() = runTest {
        assertNull(store().load(serverKey, workspaceKey, "s-absent"))
    }

    @Test
    fun `oversized window trims oldest messages until it fits`() = runTest {
        val store = store(maxSessionBytes = 1_200)
        val big = (1..40).map { message("m$it") }
        store.save(
            serverKey,
            workspaceKey,
            "s1",
            CachedSessionMessages(big, 3L),
        )
        store.drainWrites()

        val loaded = store.load(serverKey, workspaceKey, "s1")

        // Trimmed window is not empty and only retains the newest tail.
        assertTrue(loaded != null && loaded.messages.isNotEmpty() && loaded.messages.size < big.size)
        assertTrue(loaded?.messages?.any { it.message.id == "m40" } == true)
        assertTrue(loaded?.messages?.any { it.message.id == "m1" } != true)
    }

    @Test
    fun `store budget evicts least recently written sessions`() = runTest {
        val store = store(maxSessions = 2)
        store.save(
            serverKey,
            workspaceKey,
            "s1",
            CachedSessionMessages(listOf(message("m1")), 1L),
        )
        store.drainWrites()
        Thread.sleep(2)
        store.save(
            serverKey,
            workspaceKey,
            "s2",
            CachedSessionMessages(listOf(message("m2")), 1L),
        )
        store.drainWrites()
        Thread.sleep(2)
        store.save(
            serverKey,
            workspaceKey,
            "s3",
            CachedSessionMessages(listOf(message("m3")), 1L),
        )
        store.drainWrites()

        assertTrue(store.load(serverKey, workspaceKey, "s3") != null)
        // Budget retains the two most recent sessions (s2, s3); s1 was evicted.
        assertTrue(store.load(serverKey, workspaceKey, "s2") != null)
        assertFalse(store.load(serverKey, workspaceKey, "s1") != null)
    }

    @Test
    fun `dbg encode decode`() = runTest {
        val payload = CachedSessionMessages(listOf(message("m1")), 5L)
        val encoded = runCatching { json.encodeToString(CachedSessionMessages.serializer(), payload) }
        println("ENC_OK=" + encoded.getOrNull().orEmpty().take(200) + " ERR=" + encoded.exceptionOrNull())
        val decoded = encoded.getOrNull()?.let { content ->
            runCatching { json.decodeFromString(CachedSessionMessages.serializer(), content) }
        }?.getOrNull()
        println("DEC=" + decoded)
    }

    @Test
    fun `part payloads round trip byte for byte`() = runTest {
        val store = store()
        val message = MessageWithParts(
            message = assistant(),
            parts = listOf(
                dev.blazelight.p4oc.domain.model.Part.Text(
                    id = "p1",
                    sessionID = "s1",
                    messageID = "m1",
                    text = "partial",
                    isStreaming = true,
                ),
            ),
        )
        store.save(
            serverKey,
            workspaceKey,
            "s1",
            CachedSessionMessages(listOf(message), 1L),
        )
        store.drainWrites()

        val part = store.load(serverKey, workspaceKey, "s1")?.messages?.single()?.parts?.single()
            as dev.blazelight.p4oc.domain.model.Part.Text
        assertEquals("partial", part.text)
        // The store is a faithful container; streaming-flag cleanup belongs to the repository.
        assertTrue(part.isStreaming)
    }

    private fun assistant(
        id: String = "m1",
    ): Message = Message.Assistant(
        id = id,
        sessionID = "s1",
        createdAt = 1L,
        parentID = "",
        providerID = "provider",
        modelID = "model",
        mode = "chat",
        agent = "assistant",
        cost = 0.0,
        tokens = TokenUsage(input = 0, output = 0),
    )

    private fun message(id: String): MessageWithParts = MessageWithParts(assistant(id), emptyList())
}
