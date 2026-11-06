package dev.blazelight.p4oc.core.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareInboxTest {
    @Test
    fun `payload is offered until a chat is picked`() {
        val inbox = ShareInbox()

        inbox.offer(ShareInbox.Payload(listOf("content://image/1"), null))

        assertEquals(listOf("content://image/1"), inbox.pending.value?.uris)
    }

    @Test
    fun `delivery is consumed once for the matching session only`() {
        val inbox = ShareInbox()
        val payload = ShareInbox.Payload(listOf("content://image/1"), "look at this")

        inbox.deliver("ses_a", payload)

        assertNull(inbox.pending.value)
        assertNull(inbox.consume("ses_b"))
        assertEquals(payload, inbox.consume("ses_a"))
        assertNull(inbox.consume("ses_a"))
    }

    @Test
    fun `empty payloads are ignored`() {
        val inbox = ShareInbox()

        inbox.offer(ShareInbox.Payload(emptyList(), null))

        assertNull(inbox.pending.value)
    }
}
