package dev.blazelight.p4oc.ui.share

import dev.blazelight.p4oc.domain.server.ServerRef
import dev.blazelight.p4oc.domain.server.WorkspaceKey

/** One row in the share picker: an existing chat, or a new chat in [workspaceKey]. */
data class ShareTarget(
    val serverRef: ServerRef,
    val workspaceKey: WorkspaceKey,
    val workspaceLabel: String,
    val sessionId: String?,
    val title: String,
)
