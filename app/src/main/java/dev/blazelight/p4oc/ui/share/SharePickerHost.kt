package dev.blazelight.p4oc.ui.share

import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.blazelight.p4oc.core.share.ShareInbox
import dev.blazelight.p4oc.data.remote.dto.CreateSessionRequest
import dev.blazelight.p4oc.ui.components.TuiSheetContent
import dev.blazelight.p4oc.ui.navigation.Screen
import dev.blazelight.p4oc.ui.tabs.TabManager
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.workspace.WorkspaceRepositoryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val SHARE_RECENT_SESSION_LIMIT = 30

/**
 * Hosts the "share to chat" flow: Android handed content to the activity, the user picks a chat, the
 * content is attached to it and uploaded by that chat's composer.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
fun SharePickerHost(
    owners: Collection<WorkspaceRepositoryOwner>,
    tabManager: TabManager,
    shareInbox: ShareInbox,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val pendingShare by shareInbox.pending.collectAsStateWithLifecycle()
    // Not remembered: the owners map is a snapshot map that fills in as tabs restore.
    val distinctOwners = owners.distinctBy { it.workspace.server.endpointKey to it.workspace.key }
    val ownerStates = distinctOwners.map { owner ->
        owner to owner.sessionRepository.state.collectAsStateWithLifecycle()
    }
    val targets = ownerStates.flatMap { (owner, state) ->
        shareTargetsFor(owner, state.value.snapshot.sessions.values)
    }

    if (pendingShare == null) return

    ModalBottomSheet(
        onDismissRequest = shareInbox::dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LocalOpenCodeTheme.current.background,
        modifier = modifier.testTag("share_target_sheet"),
    ) {
        TuiSheetContent {
            ShareTargetSheetContent(
                targets = targets,
                onPick = { target ->
                    val payload = pendingShare ?: return@ShareTargetSheetContent
                    openShareTarget(
                        target,
                        payload,
                        ShareHost(distinctOwners, tabManager, shareInbox, scope),
                    )
                },
            )
        }
    }
}

private fun shareTargetsFor(
    owner: WorkspaceRepositoryOwner,
    sessions: Collection<dev.blazelight.p4oc.domain.session.WorkspaceSession>,
): List<ShareTarget> {
    val label = owner.workspace.directory ?: owner.workspace.server.displayName
    return buildList {
        add(
            ShareTarget(
                serverRef = owner.workspace.server,
                workspaceKey = owner.workspace.key,
                workspaceLabel = label,
                sessionId = null,
                title = "New chat",
            )
        )
        sessions.sortedByDescending { it.session.updatedAt }
            .take(SHARE_RECENT_SESSION_LIMIT)
            .forEach { session ->
                add(
                    ShareTarget(
                        serverRef = owner.workspace.server,
                        workspaceKey = owner.workspace.key,
                        workspaceLabel = label,
                        sessionId = session.session.id,
                        title = session.session.title,
                    )
                )
            }
    }
}

private class ShareHost(
    val owners: List<WorkspaceRepositoryOwner>,
    val tabManager: TabManager,
    val shareInbox: ShareInbox,
    val scope: CoroutineScope,
)

private fun openShareTarget(
    target: ShareTarget,
    payload: ShareInbox.Payload,
    host: ShareHost,
) {
    val owners = host.owners
    val tabManager = host.tabManager
    val shareInbox = host.shareInbox
    val scope = host.scope
    val sessionId = target.sessionId
    if (sessionId != null) {
        val existing = tabManager.findSessionTab(target.serverRef, target.workspaceKey, sessionId)
        if (existing != null) {
            tabManager.focusTab(existing.id)
        } else {
            tabManager.createTab(
                startRoute = Screen.Chat.createRoute(sessionId),
                workspaceKey = target.workspaceKey,
                serverRef = target.serverRef,
                focus = true,
            )
        }
        shareInbox.deliver(sessionId, payload)
        return
    }

    val owner = owners.firstOrNull {
        it.workspace.key == target.workspaceKey && it.workspace.server == target.serverRef
    } ?: return
    scope.launch {
        val created = runCatching { owner.workspaceClient.createSession(CreateSessionRequest()) }.getOrNull()
        if (created == null) return@launch
        tabManager.createTab(
            startRoute = Screen.Chat.createRoute(created.id, focusInput = true),
            workspaceKey = target.workspaceKey,
            serverRef = target.serverRef,
            focus = true,
        )
        shareInbox.deliver(created.id, payload)
    }
}
