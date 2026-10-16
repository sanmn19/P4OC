@file:Suppress("DEPRECATION") // LocalClipboardManager matches the rest of the app's copy actions.

package dev.blazelight.p4oc.ui.tabs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.blazelight.p4oc.core.datastore.SettingsDataStore
import dev.blazelight.p4oc.core.log.AppLog
import dev.blazelight.p4oc.domain.server.ServerRef
import dev.blazelight.p4oc.domain.server.WorkspaceKey
import dev.blazelight.p4oc.domain.workspace.ServerPath
import dev.blazelight.p4oc.domain.workspace.UserWorkspaceEdit
import dev.blazelight.p4oc.domain.workspace.UserWorkspaceState
import dev.blazelight.p4oc.ui.screens.home.HomeWorkspaceActions
import dev.blazelight.p4oc.ui.screens.home.ScopedHomeRepositoryState
import dev.blazelight.p4oc.ui.screens.home.WorkspaceSummary
import dev.blazelight.p4oc.ui.workspace.AddRootOutcome
import dev.blazelight.p4oc.ui.workspace.AddWorkspaceDependencies
import dev.blazelight.p4oc.ui.workspace.ServerDirectoryBrowser
import dev.blazelight.p4oc.ui.workspace.WorkspaceLabelIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val WORKSPACE_MANAGEMENT_TAG = "WorkspaceManagement"

/**
 * Directories that name workspaces on each server endpoint: every loaded session directory plus
 * every open tab's directory. Used only for display names, never for routing.
 */
internal fun workspaceDirectoriesByEndpoint(
    repositories: List<ScopedHomeRepositoryState>,
    tabs: List<TabInstance>,
): Map<String, List<String>> {
    val directories = LinkedHashMap<String, LinkedHashSet<String>>()
    fun add(endpointKey: String, directory: String) {
        if (directory.isNotBlank()) directories.getOrPut(endpointKey) { LinkedHashSet() } += directory
    }
    repositories.forEach { repository ->
        val endpointKey = repository.serverRef.endpointKey
        repository.state.snapshot.sessions.values.forEach { add(endpointKey, it.session.directory) }
    }
    tabs.forEach { tab ->
        val server = tab.serverRef
        val key = tab.workspaceKey
        if (server != null && key is WorkspaceKey.Directory) add(server.endpointKey, key.value)
    }
    return directories.mapValues { it.value.toList() }
}

@Composable
internal fun rememberWorkspaceLabels(
    userWorkspaces: UserWorkspaceState,
    repositories: List<ScopedHomeRepositoryState>,
    tabs: List<TabInstance>,
): WorkspaceLabelIndex = remember(userWorkspaces, repositories, tabs) {
    WorkspaceLabelIndex.build(userWorkspaces, workspaceDirectoriesByEndpoint(repositories, tabs))
}

/** Server endpoints whose set of user roots differs between [before] and [after]. */
internal fun changedRootEndpoints(before: UserWorkspaceState, after: UserWorkspaceState): Set<String> =
    (before.byEndpoint.keys + after.byEndpoint.keys).filterTo(HashSet()) { endpointKey ->
        before.rootsFor(endpointKey).mapTo(HashSet()) { it.compareKey } !=
            after.rootsFor(endpointKey).mapTo(HashSet()) { it.compareKey }
    }

/**
 * Refreshes a server's repositories once when its root set changes, so statuses for new roots are
 * hydrated. Grouping itself is reactive and needs no refresh. The first loaded value is the baseline.
 */
@Composable
internal fun userRootsRefreshEffect(
    userWorkspaces: UserWorkspaceState?,
    refreshServer: (endpointKey: String) -> Unit,
) {
    var baseline by remember { mutableStateOf<UserWorkspaceState?>(null) }
    val currentRefresh by rememberUpdatedState(refreshServer)
    LaunchedEffect(userWorkspaces) {
        val current = userWorkspaces ?: return@LaunchedEffect
        val previous = baseline
        baseline = current
        if (previous != null) changedRootEndpoints(previous, current).forEach { currentRefresh(it) }
    }
}

/**
 * UI-only starting folder for browsing on [endpointKey]: the parent of the most recently updated
 * session directory, else of an open tab's directory. Never used as a routing directory.
 */
internal fun browseStartFor(
    endpointKey: String,
    repositories: List<ScopedHomeRepositoryState>,
    tabs: List<TabInstance>,
): ServerPath? {
    val sessionDirectories = repositories.asSequence()
        .filter { it.serverRef.endpointKey == endpointKey }
        .flatMap { it.state.snapshot.sessions.values.asSequence() }
        .sortedByDescending { it.session.updatedAt }
        .map { it.session.directory }
    val tabDirectories = tabs.asSequence()
        .filter { it.serverRef?.endpointKey == endpointKey }
        .mapNotNull { (it.workspaceKey as? WorkspaceKey.Directory)?.value }
    return (sessionDirectories + tabDirectories)
        .mapNotNull(ServerPath::parseOrNull)
        .firstOrNull()
        ?.let { it.parent() ?: it }
}

/** Everything workspace management needs from the hosting screen. */
internal class WorkspaceManagementHost(
    val settingsDataStore: SettingsDataStore,
    val scope: CoroutineScope,
    val labels: () -> WorkspaceLabelIndex,
    val userWorkspaces: () -> UserWorkspaceState,
    val showMessage: (String) -> Unit,
    val messages: WorkspaceManagementMessages,
)

internal data class WorkspaceManagementMessages(
    val pathCopied: String,
    val saveFailed: String,
    val aliasLimit: String,
)

@Composable
internal fun rememberHomeWorkspaceActions(
    host: WorkspaceManagementHost,
    onAddRequested: (ServerRef?, String?) -> Unit,
): HomeWorkspaceActions {
    val clipboard = LocalClipboardManager.current
    val currentOnAdd by rememberUpdatedState(onAddRequested)
    return remember(host, clipboard) {
        HomeWorkspaceActions(
            onAddWorkspace = { server, prefilledPath -> currentOnAdd(server, prefilledPath) },
            onRename = { workspace, name -> host.setAlias(workspace, name) },
            onResetName = { workspace -> host.setAlias(workspace, null) },
            onCopyPath = { workspace ->
                workspace.label?.path?.let { path ->
                    clipboard.setText(AnnotatedString(path))
                    host.showMessage(host.messages.pathCopied)
                }
            },
            onRemove = { workspace -> host.removeRoot(workspace) },
        )
    }
}

private fun WorkspaceSummary.serverPath(): ServerPath? =
    (workspaceKey as? WorkspaceKey.Directory)?.value?.let(ServerPath::parseOrNull)

private fun WorkspaceManagementHost.setAlias(workspace: WorkspaceSummary, alias: String?) {
    val path = workspace.serverPath() ?: return
    val generated = labels().directoryLabel(workspace.serverRef.endpointKey, path.value).generated
    launchEdit {
        val result = settingsDataStore.setWorkspaceAlias(workspace.serverRef.endpointKey, path, alias, generated)
        if (result is UserWorkspaceEdit.LimitReached) showMessage(messages.aliasLimit)
    }
}

private fun WorkspaceManagementHost.removeRoot(workspace: WorkspaceSummary) {
    val path = workspace.serverPath() ?: return
    launchEdit { settingsDataStore.removeUserWorkspaceRoot(workspace.serverRef.endpointKey, path) }
}

@Suppress("TooGenericExceptionCaught") // Any storage failure becomes one readable message.
private fun WorkspaceManagementHost.launchEdit(edit: suspend () -> Unit) {
    scope.launch {
        try {
            edit()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.w(WORKSPACE_MANAGEMENT_TAG, "Workspace change not saved: ${error.javaClass.simpleName}")
            showMessage(messages.saveFailed)
        }
    }
}

/** Add-sheet collaborators over the store, labels, and each connected server's Home client. */
internal fun WorkspaceManagementHost.addWorkspaceDependencies(
    browserFor: (ServerRef) -> ServerDirectoryBrowser?,
    browseStart: (ServerRef) -> ServerPath?,
    createFolder: suspend (ServerRef, ServerPath) -> String? = { _, _ -> null },
    nowMs: () -> Long = System::currentTimeMillis,
): AddWorkspaceDependencies = AddWorkspaceDependencies(
    browserFor = browserFor,
    rootsFor = { server -> userWorkspaces().rootsFor(server.endpointKey) },
    generatedNameFor = { server, path -> labels().directoryLabel(server.endpointKey, path.value).generated },
    browseStartFor = browseStart,
    addRoot = { server, path, alias -> addRoot(server, path, alias, nowMs()) },
    createFolder = createFolder,
)

@Suppress("TooGenericExceptionCaught") // Any storage failure becomes the sheet's readable failure state.
private suspend fun WorkspaceManagementHost.addRoot(
    server: ServerRef,
    path: ServerPath,
    alias: String?,
    addedAt: Long,
): AddRootOutcome = try {
    when (settingsDataStore.addUserWorkspaceRoot(server.endpointKey, path, addedAt)) {
        UserWorkspaceEdit.Duplicate -> AddRootOutcome.Duplicate
        UserWorkspaceEdit.LimitReached -> AddRootOutcome.LimitReached
        is UserWorkspaceEdit.Updated -> {
            if (!alias.isNullOrBlank()) {
                val generated = labels().directoryLabel(server.endpointKey, path.value).generated
                settingsDataStore.setWorkspaceAlias(server.endpointKey, path, alias, generated)
            }
            AddRootOutcome.Added
        }
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    AppLog.w(WORKSPACE_MANAGEMENT_TAG, "Workspace not added: ${error.javaClass.simpleName}")
    AddRootOutcome.Failed
}
