package dev.blazelight.p4oc.data.session

import dev.blazelight.p4oc.domain.model.MessageWithParts

/**
 * Persistent cache of one session chat history for a single workspace-scoped repository.
 *
 * Keys are opaque, stable identifiers: [serverKey] (server endpoint key),
 * [workspaceKey] (workspace stable key), [sessionId] (session id).
 *
 * [save] is fire-and-forget: implementations own their write dispatch and must not block the
 * caller; [load] is a small bounded read executed on the caller's context. Store failures are
 * never allowed to break repository lifecycle work.
 */
interface SessionMessageStore {
    suspend fun load(serverKey: String, workspaceKey: String, sessionId: String): CachedSessionMessages?

    /**
     * Persists [messages] for later [load]. Implementations own their write dispatch (must not
     * block the caller) and may bound or trim the stored payload. Newest saves win.
     */
    fun save(
        serverKey: String,
        workspaceKey: String,
        sessionId: String,
        messages: List<MessageWithParts>,
        cachedAtMs: Long,
    )
}

@kotlinx.serialization.Serializable
data class CachedSessionMessages(
    val messages: List<MessageWithParts>,
    val cachedAtMs: Long,
)
