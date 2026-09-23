package dev.blazelight.p4oc.ui.tabs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import dev.blazelight.p4oc.R
import dev.blazelight.p4oc.domain.model.SessionConnectionState
import dev.blazelight.p4oc.domain.model.SessionPresence
import dev.blazelight.p4oc.domain.server.WorkspaceKey
import dev.blazelight.p4oc.ui.components.status.SessionStatusDot
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.Sizing
import dev.blazelight.p4oc.ui.theme.Spacing

/**
 * Tab bar showing all open tabs with indicators. Closeable tabs carry a compact trailing
 * close glyph; long press on the tab body remains an additional close gesture.
 * Reuses visual language from SessionStatusBar.
 */
private data class TabIndicatorState(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val serverBadge: String?,
    val accessibilityLabel: String,
    val connectionState: SessionConnectionState?,
    val isActive: Boolean,
    val closeable: Boolean = true,
    val onClick: () -> Unit,
    val onClose: () -> Unit,
)

@Composable
fun TabBar(
    tabs: List<TabInstance>,
    activeTabId: String?,
    tabTitles: Map<String, String>,
    tabIcons: Map<String, ImageVector>,
    tabConnectionStates: Map<String, SessionConnectionState>,
    onTabClick: (String) -> Unit,
    onTabClose: (String) -> Unit,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalOpenCodeTheme.current
    val listState = rememberLazyListState()
    val newWorkLabel = stringResource(R.string.cd_new_work)

    // Home is pinned outside the scrolling work-tab list.
    LaunchedEffect(activeTabId, tabs) {
        val activeIndex = tabs.filterNot { it.isPinnedHome }.indexOfFirst { it.id == activeTabId }
        if (activeIndex >= 0) {
            listState.animateScrollToItem(activeIndex)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth().testTag("tab_bar"),
        color = theme.backgroundPanel,
        tonalElevation = Spacing.none
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Sizing.tabBarHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.firstOrNull { it.isPinnedHome }?.let { home ->
                    tabIndicator(
                        state = TabIndicatorState(
                            id = home.id,
                            title = tabTitles.getValue(home.id),
                            icon = tabIcons.getValue(home.id),
                            serverBadge = null,
                            accessibilityLabel = tabTitles.getValue(home.id),
                            connectionState = null,
                            isActive = home.id == activeTabId,
                            closeable = false,
                            onClick = { onTabClick(home.id) },
                            onClose = {},
                        ),
                        modifier = Modifier.testTag("tab_home"),
                    )
                }

                LazyRow(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(
                        items = tabs.filterNot { it.isPinnedHome },
                        key = { it.id },
                    ) { tab ->
                        val title = tabTitles.getValue(tab.id)
                        val workspaceIdentity = when (val key = tab.workspaceKey) {
                            is WorkspaceKey.Directory -> key.value
                            WorkspaceKey.Global -> stringResource(R.string.tab_workspace_global)
                            is WorkspaceKey.SessionScoped -> stringResource(
                                R.string.tab_accessibility_session_context,
                                key.sessionId.value,
                            )
                            null -> null
                        }
                        val serverIdentity = tab.serverRef?.displayName
                        val accessibilityLabel = when {
                            serverIdentity != null && workspaceIdentity != null -> stringResource(
                                R.string.tab_accessibility_identity,
                                serverIdentity,
                                workspaceIdentity,
                                title,
                            )
                            else -> listOfNotNull(serverIdentity, workspaceIdentity, title).joinToString(", ")
                        }

                        tabIndicator(
                            state = TabIndicatorState(
                                id = tab.id,
                                title = title,
                                icon = tabIcons.getValue(tab.id),
                                serverBadge = tab.serverRef?.badgeLabel,
                                accessibilityLabel = accessibilityLabel,
                                connectionState = tabConnectionStates[tab.id],
                                isActive = tab.id == activeTabId,
                                onClick = { onTabClick(tab.id) },
                                onClose = { onTabClose(tab.id) },
                            ),
                            modifier = Modifier.testTag("work_tab_${tab.id}"),
                        )
                    }
                }

                // Add button — mono glyph with a left divider (design)
                VerticalDivider(
                    modifier = Modifier.height(Sizing.tabBarHeight),
                    color = theme.border,
                    thickness = Sizing.strokeThin,
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clickable(onClick = onAddClick, role = Role.Button)
                        .padding(horizontal = Spacing.lg)
                        .semantics { contentDescription = newWorkLabel }
                        .testTag("tab_bar_add_button"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "+",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        color = theme.textMuted,
                    )
                }
            }
            HorizontalDivider(color = theme.border, thickness = Sizing.strokeThin)
        }
    }
}

/**
 * Individual tab indicator showing icon, title, state, and close button.
 */
@Composable
private fun tabIndicator(
    state: TabIndicatorState,
    modifier: Modifier = Modifier,
) {
    val theme = LocalOpenCodeTheme.current
    val needsAttention = state.connectionState == SessionPresence.AWAITING_INPUT
    val backgroundColor = when {
        needsAttention && !state.isActive -> theme.warning.copy(alpha = 0.15f)
        state.isActive -> theme.backgroundElement
        else -> Color.Transparent
    }
    // Active tab gets a 2px primary top-border strip — the design's key tab signature.
    val topStripColor = if (state.isActive) theme.primary else Color.Transparent
    Column(
        modifier = modifier
            .height(Sizing.tabBarHeight)
            .background(backgroundColor)
            .drawBehind {
                drawRect(
                    color = topStripColor,
                    size = androidx.compose.ui.geometry.Size(
                        width = size.width,
                        height = Sizing.strokeThick.toPx(),
                    ),
                )
            },
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(top = Sizing.strokeThick),
            contentAlignment = Alignment.Center,
        ) {
            tabIndicatorRow(
                state = state,
                needsAttention = needsAttention,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun tabIndicatorRow(
    state: TabIndicatorState,
    needsAttention: Boolean,
) {
    val theme = LocalOpenCodeTheme.current
    val haptic = LocalHapticFeedback.current
    val closeLabel = stringResource(R.string.cd_close_tab)
    val selectModifier = if (state.closeable) {
        Modifier.combinedClickable(
            role = Role.Tab,
            onClick = state.onClick,
            onLongClickLabel = closeLabel,
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                state.onClose()
            },
        )
    } else {
        Modifier.clickable(onClick = state.onClick, role = Role.Tab)
    }
    Row(
        modifier = Modifier
            .fillMaxHeight()
            .then(selectModifier)
            .semantics {
                contentDescription = state.accessibilityLabel
                selected = state.isActive
            }
            .testTag(if (state.closeable) "work_tab_${state.id}_body" else "tab_home_body")
            .padding(start = Spacing.md, end = if (state.closeable) Spacing.xxs else Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabIndicatorIcon(state)
        Text(
            text = state.title,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = when {
                needsAttention -> theme.warning
                state.isActive -> theme.text
                else -> theme.textMuted
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = Sizing.panelWidthSm),
        )
        if (state.closeable) {
            tabCloseTarget(state, closeLabel)
        }
    }
}

@Composable
private fun tabCloseTarget(state: TabIndicatorState, closeLabel: String) {
    val theme = LocalOpenCodeTheme.current
    // Own click target: closes without ever selecting the tab underneath.
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .clickable(role = Role.Button, onClick = state.onClose)
            .padding(horizontal = Spacing.sm)
            .semantics { contentDescription = closeLabel }
            .testTag("work_tab_${state.id}_close"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = null,
            modifier = Modifier.size(Sizing.iconXs),
            tint = if (state.isActive) theme.text else theme.textMuted,
        )
    }
}

@Composable
private fun tabIndicatorIcon(state: TabIndicatorState) {
    val theme = LocalOpenCodeTheme.current
    if (state.connectionState != null) {
        SessionStatusDot(
            presence = state.connectionState,
            size = if (state.isActive) Sizing.indicatorDotActive else Sizing.indicatorDot,
        )
    } else {
        Icon(
            imageVector = state.icon,
            contentDescription = null,
            modifier = Modifier.size(Sizing.iconXs),
            tint = if (state.isActive) theme.text else theme.textMuted,
        )
    }
}

/**
 * Helper to get appropriate icon for a screen route.
 */
fun getIconForRoute(route: String?): ImageVector {
    return when {
        route == null -> Icons.Default.Tab
        route == "sessions" -> Icons.AutoMirrored.Filled.List
        route.startsWith("chat/") -> Icons.AutoMirrored.Filled.Chat
        route == "files" || route.startsWith("files/") -> Icons.Default.Folder
        route.startsWith("terminal/") -> Icons.Default.Terminal
        route.startsWith("settings") -> Icons.Default.Settings
        route == "projects" -> Icons.Default.FolderOpen
        else -> Icons.Default.Tab
    }
}

/**
 * Helper to get appropriate title for a screen route.
 */
data class TabTitleLabels(
    val fallbackTab: String,
    val home: String,
    val sessions: String,
    val chat: String,
    val files: String,
    val file: String,
    val terminal: String,
    val settings: String,
    val projects: String,
    val globalWorkspace: String,
    val sessionWorkspace: String,
)

@Composable
fun rememberTabTitleLabels(): TabTitleLabels = TabTitleLabels(
    fallbackTab = stringResource(R.string.tab_title_fallback),
    home = stringResource(R.string.home_title),
    sessions = stringResource(R.string.sessions_title),
    chat = stringResource(R.string.tab_title_chat),
    files = stringResource(R.string.tab_title_files),
    file = stringResource(R.string.tab_title_file),
    terminal = stringResource(R.string.terminal_title),
    settings = stringResource(R.string.settings_title),
    projects = stringResource(R.string.tab_title_projects),
    globalWorkspace = stringResource(R.string.tab_workspace_global),
    sessionWorkspace = stringResource(R.string.tab_workspace_session),
)

fun getTitleForTab(tab: TabInstance, labels: TabTitleLabels): String {
    if (tab.isPinnedHome) return labels.home
    val objectTitle = when {
        tab.sessionId != null -> tab.sessionTitle?.takeIf { it.isNotBlank() } ?: labels.chat
        else -> getTitleForRoute(tab.startRoute, labels, workspaceKey = null)
    }
    return withWorkspaceSuffix(objectTitle, tab.workspaceKey, labels)
}

fun getIconForTab(tab: TabInstance): ImageVector = when {
    tab.isPinnedHome -> Icons.Default.Home
    tab.sessionId != null -> Icons.AutoMirrored.Filled.Chat
    else -> getIconForRoute(tab.startRoute)
}

fun getTitleForRoute(
    route: String?,
    labels: TabTitleLabels,
    sessionTitle: String? = null,
    workspaceKey: WorkspaceKey? = null,
): String {
    return when {
        route == null -> labels.fallbackTab
        route == "home" -> labels.home
        route == "sessions" -> withWorkspaceSuffix(labels.sessions, workspaceKey, labels)
        route.startsWith("sessions?") -> withWorkspaceSuffix(labels.sessions, workspaceKey, labels)
        route.startsWith("chat/") -> withWorkspaceSuffix(sessionTitle ?: labels.chat, workspaceKey, labels)
        route == "files" -> workspaceLabel(workspaceKey, labels) ?: labels.files
        route.startsWith("files/") -> workspaceLabel(workspaceKey, labels) ?: labels.file
        route.startsWith("terminal/") -> withWorkspaceSuffix(sessionTitle ?: labels.terminal, workspaceKey, labels)
        route == "settings" -> labels.settings
        route.startsWith("settings/") -> labels.settings
        route == "projects" -> labels.projects
        else -> labels.fallbackTab
    }
}

private fun withWorkspaceSuffix(title: String, workspaceKey: WorkspaceKey?, labels: TabTitleLabels): String {
    val workspace = workspaceLabel(workspaceKey, labels) ?: return title
    return "$title · $workspace"
}

fun workspaceLabel(workspaceKey: WorkspaceKey?, labels: TabTitleLabels): String? = when (workspaceKey) {
    is WorkspaceKey.Directory -> workspaceKey.value.trimEnd('/').substringAfterLast('/').ifBlank { workspaceKey.value }
    WorkspaceKey.Global -> labels.globalWorkspace
    is WorkspaceKey.SessionScoped -> labels.sessionWorkspace
    null -> null
}
