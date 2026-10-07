package dev.blazelight.p4oc.domain.model

import kotlinx.serialization.Serializable

sealed class OpenCodeEvent {
    data class MessageUpdated(val message: Message) : OpenCodeEvent()
    data class MessagePartUpdated(val part: Part, val delta: String?) : OpenCodeEvent()
    data class MessagePartDelta(
        val sessionID: String?,
        val messageID: String,
        val partID: String,
        val field: String,
        val delta: String,
    ) : OpenCodeEvent()
    data class MessageRemoved(val sessionID: String, val messageID: String) : OpenCodeEvent()
    data class PartRemoved(val sessionID: String, val messageID: String, val partID: String) : OpenCodeEvent()
    data class SessionCreated(val session: Session) : OpenCodeEvent()
    data class SessionUpdated(val session: Session) : OpenCodeEvent()
    data class SessionDeleted(val session: Session) : OpenCodeEvent()
    data class SessionStatusChanged(val sessionID: String, val status: SessionStatus) : OpenCodeEvent()
    data class SessionDiff(val sessionID: String, val diffs: List<FileDiff>) : OpenCodeEvent()
    data class SessionError(val sessionID: String?, val error: MessageError?) : OpenCodeEvent()
    data class SessionCompacted(val sessionID: String) : OpenCodeEvent()
    data class SessionIdle(val sessionID: String) : OpenCodeEvent()
    data class PermissionRequested(val permission: Permission) : OpenCodeEvent()
    data class PermissionReplied(val sessionID: String, val requestID: String, val reply: String) : OpenCodeEvent()
    data class QuestionAsked(val request: QuestionRequest) : OpenCodeEvent()
    data class QuestionReplied(
        val sessionID: String,
        val requestID: String,
        val answers: List<List<String>>,
    ) : OpenCodeEvent()
    data class QuestionRejected(val sessionID: String, val requestID: String) : OpenCodeEvent()
    data class TodoUpdated(val sessionID: String, val todos: List<Todo>) : OpenCodeEvent()
    data class CommandExecuted(
        val name: String,
        val sessionID: String,
        val arguments: String,
        val messageID: String
    ) : OpenCodeEvent()
    data class FileEdited(val file: String) : OpenCodeEvent()
    data class FileWatcherUpdated(val file: String, val event: String) : OpenCodeEvent()
    data class VcsBranchUpdated(val branch: String?) : OpenCodeEvent()

    // Project and catalog events (aligned with SDK)
    data class ProjectUpdated(val project: Project) : OpenCodeEvent()
    data class ProjectDirectoriesUpdated(val projectID: String) : OpenCodeEvent()
    data object ModelsRefreshed : OpenCodeEvent()
    data object CatalogUpdated : OpenCodeEvent()
    data class McpToolsChanged(val server: String) : OpenCodeEvent()

    // Global lifecycle events (aligned with SDK)
    data object GlobalDisposed : OpenCodeEvent()

    data object Connected : OpenCodeEvent()
    data class Disconnected(val reason: String?) : OpenCodeEvent()
    data class Error(val throwable: Throwable) : OpenCodeEvent()

    // Installation events (aligned with SDK)
    data class InstallationUpdated(val version: String) : OpenCodeEvent()
    data class InstallationUpdateAvailable(val version: String) : OpenCodeEvent()

    // LSP events (aligned with SDK)
    data class LspClientDiagnostics(val serverID: String, val path: String) : OpenCodeEvent()
    data object LspUpdated : OpenCodeEvent()

    // PTY events (aligned with SDK)
    data class PtyCreated(val pty: Pty) : OpenCodeEvent()
    data class PtyUpdated(val pty: Pty) : OpenCodeEvent()
    data class PtyExited(val id: String, val exitCode: Int) : OpenCodeEvent()
    data class PtyDeleted(val id: String) : OpenCodeEvent()

    // Server events (aligned with SDK)
    data class ServerInstanceDisposed(val directory: String) : OpenCodeEvent()
}

/**
 * One authority for which events carry or mutate per-session state. Consumers that fan events
 * out to workspace-scoped repositories use this instead of reimplementing type lists, so a
 * new session-bearing event automatically extends the same delivery rule.
 */
fun OpenCodeEvent.isSessionScopedEvent(): Boolean = when (this) {
    is OpenCodeEvent.MessageUpdated -> true
    is OpenCodeEvent.MessagePartUpdated -> true
    is OpenCodeEvent.MessagePartDelta -> true
    is OpenCodeEvent.MessageRemoved -> true
    is OpenCodeEvent.PartRemoved -> true
    is OpenCodeEvent.SessionCreated -> true
    is OpenCodeEvent.SessionUpdated -> true
    is OpenCodeEvent.SessionDeleted -> true
    is OpenCodeEvent.SessionStatusChanged -> true
    is OpenCodeEvent.SessionDiff -> true
    is OpenCodeEvent.SessionError -> true
    is OpenCodeEvent.SessionCompacted -> true
    is OpenCodeEvent.SessionIdle -> true
    is OpenCodeEvent.PermissionRequested -> true
    is OpenCodeEvent.PermissionReplied -> true
    is OpenCodeEvent.QuestionAsked -> true
    is OpenCodeEvent.QuestionReplied -> true
    is OpenCodeEvent.QuestionRejected -> true
    is OpenCodeEvent.TodoUpdated -> true
    is OpenCodeEvent.CommandExecuted -> true
    else -> false
}

@Serializable
sealed class SessionStatus {
    @Serializable
    data object Idle : SessionStatus()

    @Serializable
    data object Busy : SessionStatus()

    @Serializable
    data class Retry(val attempt: Int, val message: String, val next: Long) : SessionStatus()
}
