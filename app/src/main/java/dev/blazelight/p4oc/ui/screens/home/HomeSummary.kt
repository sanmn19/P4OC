package dev.blazelight.p4oc.ui.screens.home

import dev.blazelight.p4oc.core.datastore.SavedServer
import dev.blazelight.p4oc.core.network.ConnectionState
import dev.blazelight.p4oc.data.session.RepoState
import dev.blazelight.p4oc.domain.model.SessionPresence
import dev.blazelight.p4oc.domain.model.resolveSessionPresence
import dev.blazelight.p4oc.domain.server.ServerRef
import dev.blazelight.p4oc.domain.server.WorkspaceKey
import dev.blazelight.p4oc.domain.session.SessionId
import dev.blazelight.p4oc.domain.workspace.ServerPath
import dev.blazelight.p4oc.domain.workspace.UserWorkspaceState
import dev.blazelight.p4oc.domain.workspace.WorkspaceAttribution
import dev.blazelight.p4oc.ui.tabs.TabInstance
import dev.blazelight.p4oc.ui.workspace.WorkspaceLabel
import dev.blazelight.p4oc.ui.workspace.WorkspaceLabelIndex

/** A repository snapshot whose immutable owner is known by Home. */
data class ScopedHomeRepositoryState(
    val serverRef: ServerRef,
    val state: RepoState,
)

data class ServerSummary(
    val serverRef: ServerRef,
    val displayName: String,
    val connectionState: ConnectionState,
    val sessionCount: Int,
    val openTabCount: Int,
    val isLoading: Boolean,
    val failure: String? = null,
)

/**
 * One Home workspace row. [workspaceKey] is the grouping key: a user workspace root for every
 * session inside it, otherwise the exact session or tab directory.
 */
data class WorkspaceSummary(
    val serverRef: ServerRef,
    val workspaceKey: WorkspaceKey,
    val sessionCount: Int,
    val openTabCount: Int,
    val mostRecentAt: Long,
    val isUserRoot: Boolean = false,
    /** Display name; null for non-directory workspaces, which keep their fixed copy. */
    val label: WorkspaceLabel? = null,
)

enum class OpenWorkType { Chat, Files, Terminal }

data class OpenWorkSummary(
    val tabId: String,
    val serverRef: ServerRef,
    val workspaceKey: WorkspaceKey,
    val type: OpenWorkType,
    val title: String,
    val status: SessionPresence? = null,
    /** Home workspace this tab is listed under; the tab's own [workspaceKey] is unchanged. */
    val groupKey: WorkspaceKey = workspaceKey,
)

data class SessionPreview(
    val sessionId: SessionId,
    val serverRef: ServerRef,
    /** Exact routing key used to resume the session. */
    val workspaceKey: WorkspaceKey,
    val title: String,
    val updatedAt: Long,
    val status: SessionPresence,
    val directory: String,
    val childCount: Int,
    val additions: Int,
    val deletions: Int,
    val isShared: Boolean,
    /** Parent session id when this is a subagent session. */
    val parentId: String? = null,
    /** Home workspace this session is grouped under (deepest user root, else its directory). */
    val groupKey: WorkspaceKey = workspaceKey,
)

data class HomeSummaryState(
    val servers: List<ServerSummary>,
    val workspaces: List<WorkspaceSummary>,
    val openWork: List<OpenWorkSummary>,
    val sessions: List<SessionPreview>,
    val isLoading: Boolean,
    val partialFailures: List<String> = emptyList(),
    val labels: WorkspaceLabelIndex = WorkspaceLabelIndex.Empty,
)

data class HomeSummaryInput(
    val savedServers: List<SavedServer>,
    val connectionStates: Map<String, ConnectionState>,
    val tabs: List<TabInstance>,
    val repositories: List<ScopedHomeRepositoryState> = emptyList(),
    /** Caps discovered workspaces only; user workspaces are always listed. */
    val workspaceLimit: Int = 12,
    val openWorkLimit: Int = 24,
    val userWorkspaces: UserWorkspaceState = UserWorkspaceState.Empty,
    val labels: WorkspaceLabelIndex = WorkspaceLabelIndex.Empty,
)

object HomeSummaryBuilder {
    fun build(input: HomeSummaryInput): HomeSummaryState {
        val workTabs = input.tabs.filterNot { it.isPinnedHome }
        val failures = mutableListOf<String>()
        val sessions = buildSessionPreviews(input.repositories, input.userWorkspaces)
        val serverSummaries = buildServerSummaries(input, sessions, workTabs, failures)
        appendRepositoryFailures(input.repositories, failures)
        val openWork = buildOpenWork(workTabs, sessions, input.openWorkLimit, input.userWorkspaces)
        val workspaces = buildWorkspaces(input, sessions, openWork)

        return HomeSummaryState(
            servers = serverSummaries,
            workspaces = workspaces,
            openWork = openWork,
            sessions = sessions,
            isLoading = input.repositories.any { it.state is RepoState.Hydrating },
            partialFailures = failures.distinct(),
            labels = input.labels,
        )
    }
}

/** Grouping key for a directory on [serverRef]: its deepest user root, else the directory itself. */
private fun UserWorkspaceState.groupKeyFor(serverRef: ServerRef, directory: String): WorkspaceKey =
    WorkspaceAttribution.groupKeyFor(directory, rootsFor(serverRef.endpointKey))

private fun buildSessionPreviews(
    repositories: List<ScopedHomeRepositoryState>,
    userWorkspaces: UserWorkspaceState,
): List<SessionPreview> = repositories.flatMap { scoped ->
    val sessions = scoped.state.snapshot.sessions.values
    val childCounts = sessions.mapNotNull { it.session.parentID }
        .groupingBy { it }
        .eachCount()
    sessions.map { workspaceSession ->
        val session = workspaceSession.session
        SessionPreview(
            sessionId = workspaceSession.id,
            serverRef = scoped.serverRef,
            workspaceKey = workspaceSession.workspace.key,
            title = session.title.ifBlank { "Untitled session" },
            updatedAt = session.updatedAt,
            status = resolveSessionPresence(scoped.state.snapshot.statuses[session.id]),
            directory = session.directory,
            childCount = childCounts[session.id] ?: 0,
            additions = session.summary?.additions ?: 0,
            deletions = session.summary?.deletions ?: 0,
            isShared = session.shareUrl != null,
            parentId = session.parentID,
            // Attribution reads the server-reported directory, never the loading repository's workspace.
            groupKey = userWorkspaces.groupKeyFor(scoped.serverRef, session.directory),
        )
    }
}.distinctBy { it.serverRef.endpointKey to it.sessionId.value }
    .sortedByDescending { it.updatedAt }

private fun buildServerSummaries(
    input: HomeSummaryInput,
    sessions: List<SessionPreview>,
    workTabs: List<TabInstance>,
    failures: MutableList<String>,
): List<ServerSummary> {
    val repositoryByServer = input.repositories
        .associateBy { it.serverRef.endpointKey }
    return input.savedServers.mapNotNull { saved ->
        runCatching {
            val repository = repositoryByServer[saved.endpointKey]
            ServerSummary(
                serverRef = ServerRef.fromEndpointKey(saved.endpointKey, saved.displayName),
                displayName = saved.displayName,
                connectionState = input.connectionStates[saved.endpointKey] ?: ConnectionState.Disconnected,
                sessionCount = sessions.count { it.serverRef.endpointKey == saved.endpointKey },
                openTabCount = workTabs.count { it.serverEndpointKey == saved.endpointKey },
                isLoading = repository?.state is RepoState.Hydrating,
                failure = (repository?.state as? RepoState.Stale)?.reason,
            )
        }.getOrElse {
            failures += saved.displayName
            null
        }
    }
}

private fun appendRepositoryFailures(
    repositories: List<ScopedHomeRepositoryState>,
    failures: MutableList<String>,
) {
    repositories.mapNotNullTo(failures) { scoped ->
        (scoped.state as? RepoState.Stale)?.let {
            "${scoped.serverRef.displayName}: session data unavailable"
        }
    }
}

private fun buildOpenWork(
    tabs: List<TabInstance>,
    sessions: List<SessionPreview>,
    limit: Int,
    userWorkspaces: UserWorkspaceState,
): List<OpenWorkSummary> = tabs.mapNotNull { it.toOpenWorkSummary(sessions, userWorkspaces) }.take(limit)

private fun TabInstance.toOpenWorkSummary(
    sessions: List<SessionPreview>,
    userWorkspaces: UserWorkspaceState,
): OpenWorkSummary? {
    val ownedServer = serverRef
    val ownedWorkspace = workspaceKey
    val type = openWorkType()
    if (ownedServer == null || ownedWorkspace == null || type == null) return null
    val matchingSession = sessionId?.let { sessionId ->
        sessions.firstOrNull { session ->
            session.sessionId.value == sessionId &&
                session.serverRef.endpointKey == ownedServer.endpointKey &&
                session.workspaceKey == ownedWorkspace
        }
    }
    return OpenWorkSummary(
        tabId = id,
        serverRef = ownedServer,
        workspaceKey = ownedWorkspace,
        type = type,
        title = openWorkTitle(type, matchingSession),
        status = matchingSession?.status,
        groupKey = (ownedWorkspace as? WorkspaceKey.Directory)
            ?.let { userWorkspaces.groupKeyFor(ownedServer, it.value) }
            ?: ownedWorkspace,
    )
}

private fun TabInstance.openWorkType(): OpenWorkType? = when {
    sessionId != null || startRoute.startsWith("chat/") -> OpenWorkType.Chat
    startRoute.startsWith("files") -> OpenWorkType.Files
    startRoute.startsWith("terminal/") -> OpenWorkType.Terminal
    else -> null
}

private fun TabInstance.openWorkTitle(
    type: OpenWorkType,
    matchingSession: SessionPreview?,
): String = when (type) {
    OpenWorkType.Chat -> sessionTitle ?: matchingSession?.title ?: "Chat"
    OpenWorkType.Files -> "Files"
    OpenWorkType.Terminal -> "Terminal"
}

private val workspaceOrder = compareByDescending<WorkspaceSummary> { it.mostRecentAt }
    .thenBy { it.workspaceKey.toString() }

private fun buildWorkspaces(
    input: HomeSummaryInput,
    sessions: List<SessionPreview>,
    openWork: List<OpenWorkSummary>,
): List<WorkspaceSummary> {
    val userRoots = input.savedServers.flatMap { saved ->
        val serverRef = ServerRef.fromEndpointKey(saved.endpointKey, saved.displayName)
        input.userWorkspaces.forServer(saved.endpointKey).roots.map { root ->
            workspaceSummary(
                WorkspaceGroup(serverRef, WorkspaceKey.Directory(root.path.value), root.path, root.addedAt),
                sessions,
                openWork,
                input.labels,
            )
        }
    }
    val userKeys = userRoots.mapTo(HashSet()) { it.serverRef.endpointKey to it.workspaceKey }
    val discovered = (
        sessions.map { it.serverRef to it.groupKey } +
            openWork.map { it.serverRef to it.groupKey }
        )
        .distinctBy { it.first.endpointKey to it.second }
        .filterNot { (it.first.endpointKey to it.second) in userKeys }
        .map { (serverRef, key) -> workspaceSummary(WorkspaceGroup(serverRef, key), sessions, openWork, input.labels) }
        .sortedWith(workspaceOrder)
        .take(input.workspaceLimit)
    return (userRoots + discovered).sortedWith(workspaceOrder)
}

private class WorkspaceGroup(
    val serverRef: ServerRef,
    val key: WorkspaceKey,
    val userRoot: ServerPath? = null,
    val addedAt: Long = 0L,
)

private fun workspaceSummary(
    group: WorkspaceGroup,
    sessions: List<SessionPreview>,
    openWork: List<OpenWorkSummary>,
    labels: WorkspaceLabelIndex,
): WorkspaceSummary {
    val endpointKey = group.serverRef.endpointKey
    val scopedSessions = sessions.filter { it.serverRef.endpointKey == endpointKey && it.groupKey == group.key }
    val latestSession = scopedSessions.maxOfOrNull { it.updatedAt } ?: 0L
    return WorkspaceSummary(
        serverRef = group.serverRef,
        workspaceKey = group.key,
        sessionCount = scopedSessions.size,
        openTabCount = openWork.count { it.serverRef.endpointKey == endpointKey && it.groupKey == group.key },
        mostRecentAt = maxOf(latestSession, group.addedAt),
        isUserRoot = group.userRoot != null,
        label = (group.key as? WorkspaceKey.Directory)?.let { labels.directoryLabel(endpointKey, it.value) },
    )
}
