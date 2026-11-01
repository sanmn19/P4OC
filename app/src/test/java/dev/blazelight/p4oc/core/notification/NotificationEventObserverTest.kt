package dev.blazelight.p4oc.core.notification

import dev.blazelight.p4oc.core.datastore.NotificationRoutingMode
import dev.blazelight.p4oc.core.datastore.NotificationSettings
import dev.blazelight.p4oc.domain.server.ServerRef
import dev.blazelight.p4oc.domain.server.WorkspaceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationEventObserverTest {
    private val serverA = ServerRef.fromEndpointKey("https://a.example:4096")
    private val serverB = ServerRef.fromEndpointKey("https://b.example:4096")
    private val workspaceA = WorkspaceKey.Directory("/workspace/a")
    private val workspaceB = WorkspaceKey.Directory("/workspace/b")

    @Test
    fun `completion feedback including haptic is disabled by completion setting`() {
        val disabled = NotificationSettings(enabled = true, notifyOnCompletion = false)
        val enabled = disabled.copy(notifyOnCompletion = true)

        assertFalse(shouldEmitCompletionFeedback(disabled))
        assertTrue(shouldEmitCompletionFeedback(enabled))
    }

    @Test
    fun `completion feedback does not depend on the app being backgrounded`() {
        // The chat can be on screen when a run finishes; the user still wants the notification.
        val enabled = NotificationSettings(enabled = true, notifyOnCompletion = true)

        assertTrue(shouldEmitCompletionFeedback(enabled))
    }

    @Test
    fun `routing All delivers awaiting-input and completion`() {
        assertTrue(shouldDeliverAwaitingInput(NotificationRoutingMode.All))
        assertTrue(shouldDeliverCompletion(NotificationRoutingMode.All))
    }

    @Test
    fun `routing Mentions delivers awaiting-input but suppresses completion`() {
        assertTrue(shouldDeliverAwaitingInput(NotificationRoutingMode.Mentions))
        assertFalse(shouldDeliverCompletion(NotificationRoutingMode.Mentions))
    }

    @Test
    fun `routing Off suppresses everything`() {
        assertFalse(shouldDeliverAwaitingInput(NotificationRoutingMode.Off))
        assertFalse(shouldDeliverCompletion(NotificationRoutingMode.Off))
    }

    @Test
    fun `completion consumes busy state exactly once`() {
        val tracker = CompletionTracker()
        val route = route("session", serverA, workspaceA)

        tracker.markBusy(route)

        assertTrue(tracker.complete(route))
        assertFalse(tracker.complete(route))
    }

    @Test
    fun `foreground transition clears all tracked work`() {
        val tracker = CompletionTracker()
        val first = route("first", serverA, workspaceA)
        val second = route("second", serverB, workspaceB)
        tracker.markBusy(first)
        tracker.markBusy(second)

        tracker.clear()

        assertFalse(tracker.complete(first))
        assertFalse(tracker.complete(second))
    }

    @Test
    fun `disconnect clears only disconnected server`() {
        val tracker = CompletionTracker()
        val disconnected = route("session-a", serverA, workspaceA)
        val stillConnected = route("session-b", serverB, workspaceA)
        tracker.markBusy(disconnected)
        tracker.markBusy(stillConnected)

        tracker.clearServer(serverA)

        assertFalse(tracker.complete(disconnected))
        assertTrue(tracker.complete(stillConnected))
    }

    @Test
    fun `global teardown clears every workspace owned by disposed server`() {
        val tracker = CompletionTracker()
        val firstWorkspace = route("session-a", serverA, workspaceA)
        val secondWorkspace = route("session-b", serverA, workspaceB)
        val replacementServer = route("session-c", serverB, workspaceA)
        tracker.markBusy(firstWorkspace)
        tracker.markBusy(secondWorkspace)
        tracker.markBusy(replacementServer)

        tracker.clearServer(serverA)

        assertFalse(tracker.complete(firstWorkspace))
        assertFalse(tracker.complete(secondWorkspace))
        assertTrue(tracker.complete(replacementServer))
    }

    @Test
    fun `workspace teardown preserves other work on same server`() {
        val tracker = CompletionTracker()
        val disposed = route("session-a", serverA, workspaceA)
        val retained = route("session-b", serverA, workspaceB)
        tracker.markBusy(disposed)
        tracker.markBusy(retained)

        tracker.clearWorkspace(serverA, workspaceA)

        assertFalse(tracker.complete(disposed))
        assertTrue(tracker.complete(retained))
    }

    @Test
    fun `group key is deterministic for the same chat`() {
        val first = route("session-a", serverA, workspaceA)
        val second = route("session-a", serverA, workspaceA)

        assertEquals(chatGroupKey(first), chatGroupKey(second))
    }

    @Test
    fun `different chats get different group keys`() {
        val first = route("session-a", serverA, workspaceA)
        val second = route("session-b", serverA, workspaceA)
        val otherWorkspace = route("session-a", serverA, workspaceB)
        val otherServer = route("session-a", serverB, workspaceA)

        val keys = setOf(
            chatGroupKey(first),
            chatGroupKey(second),
            chatGroupKey(otherWorkspace),
            chatGroupKey(otherServer),
        )

        assertEquals(4, keys.size)
    }

    private fun route(sessionId: String, serverRef: ServerRef, workspaceKey: WorkspaceKey) =
        NotificationRoute(sessionId, serverRef, workspaceKey)
}
