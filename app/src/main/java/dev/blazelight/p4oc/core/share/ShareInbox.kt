package dev.blazelight.p4oc.core.share

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Carries content handed to the app by Android's share sheet from the receiving activity to the
 * chat screen that will upload it.
 *
 * Two steps: [offer] holds the payload until the user picks a chat, [deliver] hands it to that
 * chat's ViewModel, which consumes it once.
 */
class ShareInbox {

    data class Payload(val uris: List<String>, val text: String?)

    data class Delivery(val sessionId: String, val payload: Payload)

    private val _pending = MutableStateFlow<Payload?>(null)
    val pending: StateFlow<Payload?> = _pending.asStateFlow()

    private val _delivery = MutableStateFlow<Delivery?>(null)
    val delivery: StateFlow<Delivery?> = _delivery.asStateFlow()

    fun offer(payload: Payload) {
        if (payload.uris.isEmpty()) return
        _pending.value = payload
    }

    fun dismiss() {
        _pending.value = null
    }

    fun deliver(sessionId: String, payload: Payload) {
        _delivery.value = Delivery(sessionId, payload)
        _pending.value = null
    }

    /** Returns the payload queued for [sessionId] and clears it, so it is attached once. */
    @Suppress("ReturnCount")
    fun consume(sessionId: String): Payload? {
        val current = _delivery.value ?: return null
        if (current.sessionId != sessionId) return null
        _delivery.value = null
        return current.payload
    }
}
