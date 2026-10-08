package dev.blazelight.p4oc.data.session

import dev.blazelight.p4oc.core.log.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * File-backed [SessionMessageStore]: one JSON file per (server, workspace, session), written
 * atomically through a temp+rename. Saves are fire-and-forget and serialized by an internal
 * worker; loads are small bounded reads. Per-session payloads are capped by serialized size
 * (newest messages kept), and the store keeps a bounded number of sessions, evicting the
 * least-recently-written ones after each write.
 */
@OptIn(ExperimentalEncodingApi::class)
class FileSessionMessageStore(
    rootDir: File,
    private val json: Json,
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    private val maxSessionBytes: Int = DEFAULT_MAX_SESSION_BYTES,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SessionMessageStore {

    private val root: File = rootDir
    private val writeSerializer: KSerializer<CachedSessionMessages> = CachedSessionMessages.serializer()

    // Serialized saves funnel through one channel so writes never interleave on disk.
    private val writes = Channel<Write>(capacity = Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private sealed interface Write {
        data class Messages(val file: File, val payload: String) : Write
        data class Drain(val done: kotlinx.coroutines.CompletableDeferred<Unit>) : Write
    }

    init {
        root.mkdirs()
        scope.launch {
            writes.consumeEach { write ->
                val payload = write as? Write.Messages
                if (payload != null) writeNow(payload)
                (write as? Write.Drain)?.done?.complete(Unit)
            }
        }
    }

    /**
     * Blocks until every queued write has reached disk. Test/flush support for callers that must
     * observe durability before asserting.
     */
    internal suspend fun drainWrites() {
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        writes.trySend(Write.Drain(done))
        done.await()
    }

    override suspend fun load(
        serverKey: String,
        workspaceKey: String,
        sessionId: String,
    ): CachedSessionMessages? {
        val file = fileFor(serverKey, workspaceKey, sessionId)
        return runCatching { loadCapped(file) }
            .getOrElse { error ->
                AppLog.w(TAG, "Session cache decode failed: ${error.javaClass.simpleName}")
                null
            }
    }

    private fun loadCapped(file: File): CachedSessionMessages? =
        file.takeIf(File::exists)
            ?.let(File::readText)
            ?.takeUnless { text -> text.length > maxSessionBytes }
            ?.let { text -> json.decodeFromString(writeSerializer, text) }

    override fun save(serverKey: String, workspaceKey: String, sessionId: String, entry: CachedSessionMessages) {
        if (entry.messages.isEmpty()) return
        val payload = trimToBudget(entry)
        val encoded = runCatching { json.encodeToString(writeSerializer, payload) }.getOrNull()
        if (encoded == null) {
            AppLog.w(TAG, "Session cache encode failed")
            return
        }
        writes.trySend(Write.Messages(fileFor(serverKey, workspaceKey, sessionId), encoded))
    }

    /** Drops oldest messages until the serialized payload fits [maxSessionBytes]. */
    private fun trimToBudget(payload: CachedSessionMessages): CachedSessionMessages {
        var current = payload
        while (current.messages.size > 1) {
            val length = runCatching { json.encodeToString(writeSerializer, current) }.getOrNull()?.length
            if (length != null && length <= maxSessionBytes) return current
            current = current.copy(messages = current.messages.drop(1))
        }
        // A single oversized message is still stored; load treats an oversized file as a miss.
        return current
    }

    private fun fileFor(serverKey: String, workspaceKey: String, sessionId: String): File {
        val dir = File(File(root, namespace(serverKey)), namespace(workspaceKey))
        return File(dir, "${namespace(sessionId)}.json")
    }

    /** Deterministic, filesystem-safe directory/file name for a cache namespace segment. */
    private fun namespace(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return Base64.UrlSafe.encode(digest).dropLast(1) // strip '=' padding
    }

    private fun writeNow(write: Write.Messages) {
        val dir = write.file.parentFile
        if (!dir.isDirectory && !dir.mkdirs()) {
            AppLog.w(TAG, "Session cache directory could not be created: ${dir.path}")
            return
        }
        val tmp = File(dir, write.file.name + ".tmp")
        tmp.writeText(write.payload)
        if (!tmp.renameTo(write.file)) {
            write.file.delete()
            if (!tmp.renameTo(write.file)) {
                AppLog.w(TAG, "Session cache rename failed; keeping previous content")
                tmp.delete()
            }
        }
        evictIfOverBudget()
    }

    /** Bounded store: on overflow evict the least-recently-written session files. */
    private fun evictIfOverBudget() {
        val files = sessionFiles()
        if (files.size <= maxSessions) return
        files.sortedBy(File::lastModified)
            .take(files.size - maxSessions)
            .forEach { overflow ->
                runCatching { overflow.delete() }
                    .onFailure {
                        AppLog.w(TAG, "Session cache eviction failed for ${overflow.name}: ${it.javaClass.simpleName}")
                    }
            }
    }

    private fun sessionFiles(): List<File> {
        val serverDirs = root.listFiles { file -> file.isDirectory }?.toList() ?: emptyList()
        return serverDirs
            .flatMap { serverDir -> serverDir.listFiles { file -> file.isDirectory }?.toList() ?: emptyList() }
            .flatMap { workspaceDir ->
                workspaceDir.listFiles { file -> file.isFile && file.name.endsWith(".json") }?.toList()
                    ?: emptyList()
            }
    }

    private companion object {
        const val TAG = "FileSessionMessageStore"
        const val DEFAULT_MAX_SESSIONS = 200
        const val DEFAULT_MAX_SESSION_BYTES = 256 * 1024
    }
}
