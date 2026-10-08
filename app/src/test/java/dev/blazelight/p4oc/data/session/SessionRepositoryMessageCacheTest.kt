package dev.blazelight.p4oc.data.session

import dev.blazelight.p4oc.core.network.ConnectionState
import dev.blazelight.p4oc.core.network.OpenCodeApi
import dev.blazelight.p4oc.data.remote.mapper.MessageMapper
import dev.blazelight.p4oc.data.server.ActiveServerApiProvider
import dev.blazelight.p4oc.data.workspace.WorkspaceClient
import dev.blazelight.p4oc.domain.model.Message
import dev.blazelight.p4oc.domain.model.MessageWithParts
import dev.blazelight.p4oc.domain.model.OpenCodeEvent
import dev.blazelight.p4oc.domain.model.Part
import dev.blazelight.p4oc.domain.model.TokenUsage
import dev.blazelight.p4oc.domain.server.ServerGeneration
import dev.blazelight.p4oc.domain.server.ServerRef
import dev.blazelight.p4oc.domain.session.SessionId
import dev.blazelight.p4oc.domain.workspace.Workspace
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Contract for the persistent session message cache behind [SessionRepository]:
 * the final lease release writes the session's live window to disk and the next
 * [SessionRepository.acquireSession] restores it immediately, before any REST fetch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRepositoryMessageCacheTest {

    private val sessionId = SessionId("s1")
    private val mapper = MessageMapper()

    @Test
    fun `acquire seeds cached messages before any REST fetch`() = runTest {
        val fixture = repository(
            testScheduler,
            store = FakeSessionMessageStore(
                initial = CachedSessionMessages(
                    messages = listOf(MessageWithParts(assistantMessage("m1"), emptyList())),
                    cachedAtMs = 42L,
                ),
            ),
        )
        val repo = fixture.repo
        val api = fixture.api

        val lease = repo.acquireSession(sessionId)
        advanceUntilIdle()

        assertEquals(
            listOf("m1"),
            repo.messages(sessionId).value.map { it.message.id },
        )
        // Cache seeding alone must not trigger a history fetch; the open flow fetches explicitly.
        coVerify(exactly = 0) { api.getMessages(any(), any(), any(), any(), any()) }
        lease.close()
    }

    @Test
    fun `final release persists live message window to the store`() = runTest {
        val fake = FakeSessionMessageStore()
        val fixture = repository(testScheduler, store = fake)
        val repo = fixture.repo

        val lease = repo.acquireSession(sessionId)
        repo.acceptEvent(OpenCodeEvent.MessageUpdated(assistantMessage("m1", createdAt = 1)))
        repo.acceptEvent(
            OpenCodeEvent.MessagePartUpdated(
                Part.Text(id = "p1", sessionID = "s1", messageID = "m1", text = "streaming", isStreaming = true),
                delta = null,
            )
        )
        repo.acceptEvent(OpenCodeEvent.MessageUpdated(assistantMessage("m2", createdAt = 2)))
        advanceUntilIdle()

        lease.close()

        val ids = fake.saved["s1"]?.messages?.map { it.message.id }
        assertEquals(listOf("m1", "m2"), ids)
        // Streaming flags must be cleaned on the way into the cache.
        val streamingPart = fake.saved["s1"]?.messages
            ?.first { it.message.id == "m1" }?.parts?.single() as Part.Text
        assertEquals("streaming", streamingPart.text)
        assertFalse(streamingPart.isStreaming)
    }

    @Test
    fun `store load failure does not break the lease`() = runTest {
        val fixture = repository(
            testScheduler,
            store = ThrowingSessionMessageStore(),
        )
        val repo = fixture.repo

        val lease = repo.acquireSession(sessionId)
        advanceUntilIdle()

        assertEquals(emptyList<MessageWithParts>(), repo.messages(sessionId).value)
        lease.close()
    }

    @Test
    fun `fetched window repairs server-side deletions inside the range but keeps older tail`() = runTest {
        val fixture = repository(
            testScheduler,
            store = FakeSessionMessageStore(
                initial = CachedSessionMessages(
                    messages = listOf(
                        MessageWithParts(assistantMessage("m-old", createdAt = 1L), emptyList()),
                        MessageWithParts(assistantMessage("m-deleted", createdAt = 50L), emptyList()),
                        MessageWithParts(assistantMessage("m-new", createdAt = 100L), emptyList()),
                    ),
                    cachedAtMs = 42L,
                ),
            ),
        )

        val repo = fixture.repo
        val api = fixture.api
        val lease = repo.acquireSession(sessionId)
        advanceUntilIdle()

        coEvery { api.getMessages("s1", 100, null, "/test", null) } returns listOf(
            assistantWrapper("m-old", createdAt = 1L),
            assistantWrapper("m-new", createdAt = 100L),
        )

        repo.loadMessages(sessionId, limit = 100)
        advanceUntilIdle()

        // The server deleted m-deleted, which lived inside the fetched window: the merge must not
        // resurrect it. m-old predates the fetched window and legitimately remains.
        val ids = repo.messages(sessionId).value.map { it.message.id }
        assertEquals(listOf("m-old", "m-new"), ids)
        coVerify(exactly = 1) { api.getMessages("s1", 100, null, "/test", null) }
        lease.close()
    }

    @Test
    fun `cache restores remembered window bound for recovery`() = runTest {
        val fixture = repository(
            testScheduler,
            store = FakeSessionMessageStore(
                initial = CachedSessionMessages(
                    messages = listOf(MessageWithParts(assistantMessage("m1"), emptyList())),
                    cachedAtMs = 42L,
                    loadedLimit = 200,
                    hasOlderMessages = true,
                ),
            ),
        )
        val repo = fixture.repo
        val api = fixture.api

        repo.acquireSession(sessionId)
        advanceUntilIdle()

        coEvery { api.getMessages("s1", 200, null, "/test", null) } returns (1L..200L)
            .associate { it to assistantWrapper("m$it", createdAt = it) }.entries
            .map { it.value }
        repo.acceptEvent(OpenCodeEvent.Connected)
        advanceUntilIdle()

        // Recovery must reuse the bound the cache restored, not fall back to the default
        // 100-window (which would silently drop previously loaded history).
        coVerify(exactly = 1) { api.getMessages("s1", 200, null, "/test", null) }
        coVerify(exactly = 0) { api.getMessages("s1", 100, null, "/test", null) }
        assertEquals(200, repo.messages(sessionId).value.size)
    }

    private fun repository(
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        store: SessionMessageStore? = null,
    ): RepositoryFixture {
        val api = mockk<OpenCodeApi>(relaxed = true)
        val client = WorkspaceClient(
            workspace = Workspace(
                server = ServerRef.fromEndpointKey("http://test.local"),
                directory = "/test",
            ),
            generation = ServerGeneration(0L),
            apiProvider = ActiveServerApiProvider { _, _ -> api },
            connectionState = MutableStateFlow(ConnectionState.Disconnected),
        )
        val repo = SessionRepositoryImpl(
            client,
            messageMapper = mapper,
            messageStore = store,
            dispatcher = StandardTestDispatcher(scheduler),
        )
        return RepositoryFixture(repo, api, client, store)
    }

    private data class RepositoryFixture(
        val repo: SessionRepositoryImpl,
        val api: OpenCodeApi,
        val client: WorkspaceClient,
        val store: SessionMessageStore?,
    )

    private fun assistantMessage(id: String, createdAt: Long = 1L): Message.Assistant = Message.Assistant(
        id = id,
        sessionID = "s1",
        createdAt = createdAt,
        parentID = "",
        providerID = "provider",
        modelID = "model",
        mode = "chat",
        agent = "assistant",
        cost = 0.0,
        tokens = TokenUsage(input = 0, output = 0),
    )

    private fun assistantWrapper(
        id: String,
        createdAt: Long,
    ): dev.blazelight.p4oc.data.remote.dto.MessageWrapperDto =
        dev.blazelight.p4oc.data.remote.dto.MessageWrapperDto(
            info = dev.blazelight.p4oc.data.remote.dto.MessageInfoDto(
                id = id,
                sessionID = "s1",
                time = dev.blazelight.p4oc.data.remote.dto.MessageTimeDto(created = createdAt),
                role = "assistant",
                parentID = "",
                providerID = "provider",
                modelID = "model",
                agent = "assistant",
                mode = "chat",
            ),
            parts = emptyList(),
        )
}

private class FakeSessionMessageStore(
    val initial: CachedSessionMessages? = null,
) : SessionMessageStore {
    val saved = mutableMapOf<String, CachedSessionMessages>()

    override suspend fun load(
        serverKey: String,
        workspaceKey: String,
        sessionId: String,
    ): CachedSessionMessages? = initial

    override fun save(
        serverKey: String,
        workspaceKey: String,
        sessionId: String,
        entry: CachedSessionMessages,
    ) {
        saved[sessionId] = entry
    }
}

private class ThrowingSessionMessageStore : SessionMessageStore {
    override suspend fun load(
        serverKey: String,
        workspaceKey: String,
        sessionId: String,
    ): CachedSessionMessages? {
        throw NotImplementedError("store broken")
    }

    override fun save(
        serverKey: String,
        workspaceKey: String,
        sessionId: String,
        entry: CachedSessionMessages,
    ) {
        throw NotImplementedError("store broken")
    }
}
