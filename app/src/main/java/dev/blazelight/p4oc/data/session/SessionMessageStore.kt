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
     * Persists [entry] for later [load]. Implementations own their write dispatch and may bound
     * or trim the stored payload. Newest saves win.
     */
    fun save(serverKey: String, workspaceKey: String, sessionId: String, entry: CachedSessionMessages)
}

/** One cached session snapshot: its live message window and the remembered pagination bound. */
@kotlinx.serialization.Serializable
data class CachedSessionMessages(
    val messages: List<MessageWithParts>,
    val cachedAtMs: Long,
    /** Largest history window the client had actually loaded for this session when cached. */
    val loadedLimit: Int = 100,
    val hasOlderMessages: Boolean = false,
)
