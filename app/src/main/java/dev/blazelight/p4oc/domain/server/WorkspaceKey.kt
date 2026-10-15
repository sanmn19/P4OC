package dev.blazelight.p4oc.domain.server

import dev.blazelight.p4oc.domain.session.SessionId

sealed interface WorkspaceKey {
    @JvmInline
    value class Directory(val value: String) : WorkspaceKey {
        init {
            require(value.isNotBlank()) { "Workspace directory must not be blank" }
        }
    }

    data object Global : WorkspaceKey

    @JvmInline
    value class SessionScoped(val sessionId: SessionId) : WorkspaceKey
}

/** Stable, filesystem/bundle-safe string identity shared by cache and provider key structures. */
fun WorkspaceKey.stableCacheKey(): String = when (this) {
    WorkspaceKey.Global -> "global"
    is WorkspaceKey.Directory -> "directory:${value.trimEnd('/')}"
    is WorkspaceKey.SessionScoped -> "session:${sessionId.value}"
}
