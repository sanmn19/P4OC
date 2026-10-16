package dev.blazelight.p4oc.ui.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import dev.blazelight.p4oc.R
import dev.blazelight.p4oc.domain.server.ServerRef
import dev.blazelight.p4oc.domain.workspace.PathRejection
import dev.blazelight.p4oc.domain.workspace.ServerPath
import dev.blazelight.p4oc.domain.workspace.ServerPathParse
import dev.blazelight.p4oc.domain.workspace.UserWorkspaceLimits
import dev.blazelight.p4oc.ui.components.TuiButton
import dev.blazelight.p4oc.ui.components.TuiTextButton
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.Sizing
import dev.blazelight.p4oc.ui.theme.Spacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A server available to the add-workspace flow. */
data class AddWorkspaceServerOption(val server: ServerRef, val connected: Boolean)

data class AddWorkspaceRequest(
    val servers: List<AddWorkspaceServerOption>,
    val preselectedEndpointKey: String?,
    val prefilledPath: String?,
)

enum class AddRootOutcome { Added, Duplicate, LimitReached, Failed }

class AddWorkspaceDependencies(
    val browserFor: (ServerRef) -> ServerDirectoryBrowser?,
    val rootsFor: (ServerRef) -> List<ServerPath>,
    val generatedNameFor: (ServerRef, ServerPath) -> String,
    val browseStartFor: (ServerRef) -> ServerPath?,
    val addRoot: suspend (ServerRef, ServerPath, String?) -> AddRootOutcome,
    /**
     * Server-side folder creation for a validated-but-missing absolute path (workspace root
     * creation, e.g. ofish `mkdir -p`). Returns null on success or a human-readable problem.
     * Null-capability servers (e.g. browse-only) report themselves through the returned text.
     */
    val createFolder: suspend (ServerRef, ServerPath) -> String? = { _, _ ->
        "Folder creation is not available on this server"
    },
)

private data class AddWorkspaceValidationRequest(
    val raw: String,
    val allowUnverified: Boolean,
    val option: AddWorkspaceServerOption,
    val dependencies: AddWorkspaceDependencies,
    val onDismiss: () -> Unit,
    val onAdded: (ServerRef, ServerPath) -> Unit,
) {
    val endpointKey: String get() = option.server.endpointKey
}

@Stable
internal class AddWorkspaceState(val request: AddWorkspaceRequest) {
    var selectedEndpointKey by mutableStateOf(initialEndpointKey(request))
        private set
    var pathText by mutableStateOf(request.prefilledPath.orEmpty())
        private set
    var nameText by mutableStateOf("")
        private set
    var browsePath by mutableStateOf<ServerPath?>(null)
        private set
    var browseResult by mutableStateOf<BrowseResult?>(null)
        private set
    var isListing by mutableStateOf(false)
        private set
    var isValidating by mutableStateOf(false)
        private set
    var pathProblem by mutableStateOf<WorkspacePathProblem?>(null)
        private set
    var addOutcome by mutableStateOf<AddRootOutcome?>(null)
        private set
    var allowUnverified by mutableStateOf(false)
        private set
    var isCreating by mutableStateOf(false)
        private set
    var createProblem by mutableStateOf<String?>(null)
        private set

    private var browseJob: Job? = null
    private var validationJob: Job? = null

    fun selectedOption(): AddWorkspaceServerOption? =
        request.servers.firstOrNull { it.server.endpointKey == selectedEndpointKey }

    fun selectServer(endpointKey: String) {
        browseJob?.cancel()
        validationJob?.cancel()
        selectedEndpointKey = endpointKey
        browsePath = null
        browseResult = null
        isListing = false
        isValidating = false
        clearFeedback()
    }

    fun updatePath(value: String) {
        validationJob?.cancel()
        isValidating = false
        pathText = value
        clearFeedback()
    }

    fun updateName(value: String) {
        nameText = value.take(MAX_WORKSPACE_ALIAS_LENGTH)
    }

    /** The typed path's parent checks out and only the leaf folder is missing on disk. */
    fun canCreateMissingFolder(): Boolean =
        !isCreating && pathProblem is WorkspacePathProblem.Missing &&
            ServerPath.parseUserInput(pathText) is ServerPathParse.Valid

    internal fun createMissingFolder(env: AddWorkspaceEnv, option: AddWorkspaceServerOption) {
        if (!canCreateMissingFolder()) return
        val parsed = (ServerPath.parseUserInput(pathText) as ServerPathParse.Valid).path
        createProblem = null
        isCreating = true
        env.scope.launch {
            val problem = runCatching { env.dependencies.createFolder(option.server, parsed) }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    error.message ?: error.javaClass.simpleName
                }
            if (!isCurrentForCreate(option)) return@launch
            isCreating = false
            if (problem == null) {
                createProblem = null
                // The folder now exists: re-validate (becomes verified) and add the root.
                env.submit(state = this@AddWorkspaceState, option)
            } else {
                createProblem = problem
            }
        }
    }

    private fun isCurrentForCreate(option: AddWorkspaceServerOption): Boolean =
        selectedEndpointKey == option.server.endpointKey

    fun startInitialBrowse(
        scope: CoroutineScope,
        server: ServerRef,
        dependencies: AddWorkspaceDependencies,
    ) {
        val prefilled = request.prefilledPath?.let(::parsePathOrNull)
        val start = prefilled?.parent() ?: prefilled ?: dependencies.browseStartFor(server) ?: defaultServerRoot()
        startBrowse(scope, server, start, dependencies)
    }

    fun startBrowse(
        scope: CoroutineScope,
        server: ServerRef,
        path: ServerPath,
        dependencies: AddWorkspaceDependencies,
    ) {
        browseJob?.cancel()
        browsePath = path
        browseResult = null
        isListing = true
        val browser = dependencies.browserFor(server)
        if (browser == null) {
            browseResult = BrowseResult.Offline
            isListing = false
            return
        }
        browseJob = scope.launch {
            // Browsers map expected failures to BrowseResult themselves (see workspaceClientDirectoryBrowser).
            val result = browser.list(path)
            if (browsePath == path) {
                browseResult = result
                isListing = false
            }
        }
    }

    fun retryBrowse(scope: CoroutineScope, server: ServerRef, dependencies: AddWorkspaceDependencies) {
        browsePath?.let { startBrowse(scope, server, it, dependencies) }
    }

    fun validateAndAdd(
        scope: CoroutineScope,
        option: AddWorkspaceServerOption,
        dependencies: AddWorkspaceDependencies,
        onDismiss: () -> Unit,
        onAdded: (ServerRef, ServerPath) -> Unit,
    ) {
        if (isValidating || !option.connected) return
        validationJob?.cancel()
        val submission = AddWorkspaceValidationRequest(
            raw = pathText,
            allowUnverified = allowUnverified,
            option = option,
            dependencies = dependencies,
            onDismiss = onDismiss,
            onAdded = onAdded,
        )
        isValidating = true
        clearFeedback()
        validationJob = scope.launch {
            try {
                validateAndPersist(submission)
            } finally {
                if (isCurrent(submission.raw, submission.endpointKey)) {
                    isValidating = false
                    validationJob = null
                }
            }
        }
    }

    private suspend fun validateAndPersist(submission: AddWorkspaceValidationRequest) {
        val server = submission.option.server
        val validation = validateWorkspacePath(
            raw = submission.raw,
            existingRoots = submission.dependencies.rootsFor(server),
            browser = submission.dependencies.browserFor(server),
            allowUnverified = submission.allowUnverified,
        )
        when (validation) {
            is WorkspacePathValidation.Invalid -> {
                if (isCurrent(submission.raw, submission.endpointKey)) {
                    pathProblem = validation.problem
                    allowUnverified = validation.problem == WorkspacePathProblem.Unverifiable
                }
            }
            is WorkspacePathValidation.Valid -> persistValidatedPath(validation.path, submission)
        }
    }

    private suspend fun persistValidatedPath(path: ServerPath, submission: AddWorkspaceValidationRequest) {
        val server = submission.option.server
        val outcome = try {
            submission.dependencies.addRoot(server, path, nameText.trim().takeIf(String::isNotEmpty))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            AddRootOutcome.Failed
        }
        if (!isCurrent(submission.raw, submission.endpointKey)) return
        when (outcome) {
            AddRootOutcome.Added -> {
                submission.onAdded(server, path)
                submission.onDismiss()
            }
            AddRootOutcome.Duplicate -> pathProblem = WorkspacePathProblem.Duplicate
            AddRootOutcome.LimitReached, AddRootOutcome.Failed -> addOutcome = outcome
        }
    }

    private fun isCurrent(raw: String, endpointKey: String): Boolean =
        pathText == raw && selectedEndpointKey == endpointKey

    private fun clearFeedback() {
        pathProblem = null
        addOutcome = null
        allowUnverified = false
        createProblem = null
    }

    private fun initialEndpointKey(request: AddWorkspaceRequest): String? =
        request.preselectedEndpointKey?.takeIf { key -> request.servers.any { it.server.endpointKey == key } }
            ?: request.servers.singleOrNull()?.server?.endpointKey
}

private const val MAX_WORKSPACE_ALIAS_LENGTH = UserWorkspaceLimits.MAX_ALIAS_LENGTH

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun addWorkspaceSheet(
    request: AddWorkspaceRequest,
    dependencies: AddWorkspaceDependencies,
    onDismiss: () -> Unit,
    onAdded: (ServerRef, ServerPath) -> Unit,
) {
    val state = remember(request) { AddWorkspaceState(request) }
    val scope = rememberCoroutineScope()
    val env = AddWorkspaceEnv(dependencies, scope, onDismiss, onAdded)
    val selectedOption = state.selectedOption()
    LaunchedEffect(selectedOption?.server?.endpointKey) {
        selectedOption?.let { state.startInitialBrowse(scope, it.server, dependencies) }
    }
    val theme = LocalOpenCodeTheme.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = theme.background,
        shape = RectangleShape,
        dragHandle = null,
        modifier = Modifier.testTag("add_workspace_sheet"),
    ) {
        addWorkspaceSheetContent(state, selectedOption, env)
    }
}

/** Collaborators shared by the sheet's composables. */
internal class AddWorkspaceEnv(
    val dependencies: AddWorkspaceDependencies,
    val scope: CoroutineScope,
    val onDismiss: () -> Unit,
    val onAdded: (ServerRef, ServerPath) -> Unit,
) {
    fun browse(state: AddWorkspaceState, server: ServerRef, path: ServerPath) {
        state.startBrowse(scope, server, path, dependencies)
    }

    fun createMissingFolder(state: AddWorkspaceState, option: AddWorkspaceServerOption) {
        state.createMissingFolder(env = this, option)
    }

    fun submit(state: AddWorkspaceState, option: AddWorkspaceServerOption) {
        state.validateAndAdd(scope, option, dependencies, onDismiss, onAdded)
    }
}

@Composable
private fun addWorkspaceSheetContent(
    state: AddWorkspaceState,
    selectedOption: AddWorkspaceServerOption?,
    env: AddWorkspaceEnv,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sectionSpacing),
    ) {
        Text(stringResource(R.string.add_workspace_title), style = MaterialTheme.typography.titleMedium)
        if (state.request.servers.isEmpty()) {
            Text(stringResource(R.string.start_work_no_servers), color = LocalOpenCodeTheme.current.textMuted)
        }
        if (state.request.servers.size > 1 && state.selectedEndpointKey == null) {
            addWorkspaceServerOptions(state, state.request.servers)
        }
        selectedOption?.let { option ->
            Text(option.server.displayName, color = LocalOpenCodeTheme.current.textMuted)
            addWorkspacePathEditor(state, option, env)
            addWorkspaceBrowser(state, option.server, env)
            addWorkspaceNameField(state, option.server, env.dependencies)
        }
        addWorkspaceFeedback(state)
        addWorkspaceFooter(state, selectedOption, env)
    }
}

@Composable
private fun addWorkspaceServerOptions(
    state: AddWorkspaceState,
    servers: List<AddWorkspaceServerOption>,
) {
    Text(stringResource(R.string.add_workspace_choose_server), style = MaterialTheme.typography.titleSmall)
    val orderedServers = remember(servers) { servers.sortedByDescending { it.connected } }
    orderedServers.forEach { option ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizing.minTouchTarget)
                .clickable(
                    enabled = option.connected,
                    role = Role.Button,
                    onClick = { state.selectServer(option.server.endpointKey) },
                )
                .testTag("add_workspace_server_${option.server.endpointKey}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = state.selectedEndpointKey == option.server.endpointKey,
                onClick = null,
                enabled = option.connected,
            )
            Text(option.server.displayName, modifier = Modifier.weight(1f))
            if (!option.connected) {
                Text(
                    stringResource(R.string.add_workspace_server_offline),
                    color = LocalOpenCodeTheme.current.textMuted,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

@Composable
private fun addWorkspacePathEditor(
    state: AddWorkspaceState,
    option: AddWorkspaceServerOption,
    env: AddWorkspaceEnv,
) {
    val theme = LocalOpenCodeTheme.current
    val parsed = ServerPath.parseUserInput(state.pathText)
    val errorText = state.pathProblem?.let { workspacePathProblemText(it, option.server) }
        ?: when (state.addOutcome) {
            AddRootOutcome.LimitReached -> stringResource(
                R.string.add_workspace_root_limit,
                UserWorkspaceLimits.MAX_ROOTS_PER_SERVER,
            )
            else -> (parsed as? ServerPathParse.Invalid)
                ?.takeUnless { it.reason == PathRejection.Blank && state.pathText.isEmpty() }
                ?.let { pathRejectionText(it.reason) }
        }
    val visibleError = errorText?.takeUnless {
        state.pathProblem == WorkspacePathProblem.Offline && state.browseResult == BrowseResult.Offline
    }
    val canCreateMissing = state.canCreateMissingFolder()
    val onGo = { env.submit(state, option) }
    OutlinedTextField(
        value = state.pathText,
        onValueChange = state::updatePath,
        modifier = Modifier.fillMaxWidth().testTag("add_workspace_path_field"),
        label = { Text(stringResource(R.string.add_workspace_path_label)) },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        singleLine = true,
        isError = visibleError != null && state.pathProblem != WorkspacePathProblem.Unverifiable,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = theme.text,
            unfocusedTextColor = theme.text,
            focusedBorderColor = theme.accent,
            unfocusedBorderColor = theme.border,
            cursorColor = theme.accent,
        ),
    )
    Text(stringResource(R.string.sessions_custom_directory_desc), color = theme.textMuted)
    if (visibleError != null) {
        val isWarning = state.pathProblem == WorkspacePathProblem.Unverifiable
        Text(
            visibleError,
            color = if (isWarning) theme.textMuted else theme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    addWorkspaceCreateAction(state, option, env, canCreateMissing)
}

@Composable
private fun addWorkspaceCreateAction(
    state: AddWorkspaceState,
    option: AddWorkspaceServerOption,
    env: AddWorkspaceEnv,
    canCreateMissing: Boolean,
) {
    val theme = LocalOpenCodeTheme.current
    if (canCreateMissing) {
        Text(
            stringResource(R.string.add_workspace_create_hint),
            color = theme.textMuted,
        )
        TuiButton(
            onClick = { env.createMissingFolder(state, option) },
            enabled = !state.isCreating && !state.isValidating,
            modifier = Modifier.testTag("add_workspace_create_folder"),
        ) {
            if (state.isCreating) {
                CircularProgressIndicator(modifier = Modifier.size(Sizing.iconSm))
                Spacer(Modifier.width(Spacing.sm))
            }
            Text(stringResource(R.string.add_workspace_create_folder))
        }
    }
    state.createProblem?.let { problem ->
        Text(
            problem,
            color = theme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun addWorkspaceBrowser(
    state: AddWorkspaceState,
    server: ServerRef,
    env: AddWorkspaceEnv,
) {
    when (state.browseResult) {
        BrowseResult.Unsupported -> Text(
            stringResource(R.string.add_workspace_browse_unavailable),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        BrowseResult.NotReadable -> addWorkspaceBrowseFailure(state, server, env)
        BrowseResult.Offline -> Text(
            stringResource(R.string.add_workspace_offline),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        is BrowseResult.Listed, null -> addWorkspaceBrowserContent(state, server, env)
    }
}

@Composable
private fun addWorkspaceBrowseFailure(
    state: AddWorkspaceState,
    server: ServerRef,
    env: AddWorkspaceEnv,
) {
    val theme = LocalOpenCodeTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        state.browsePath?.let { path ->
            addWorkspaceCurrentPath(path)
            path.parent()?.let { parent -> addWorkspaceUpRow(server, parent, env, state) }
        }
        Text(
            stringResource(R.string.add_workspace_browse_failed),
            color = theme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        TuiTextButton(
            onClick = { state.retryBrowse(env.scope, server, env.dependencies) },
            modifier = Modifier.testTag("add_workspace_retry"),
        ) {
            Text(stringResource(R.string.add_workspace_retry))
        }
    }
}

@Composable
private fun addWorkspaceBrowserContent(
    state: AddWorkspaceState,
    server: ServerRef,
    env: AddWorkspaceEnv,
) {
    val path = state.browsePath ?: return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        addWorkspaceCurrentPath(path)
        path.parent()?.let { parent ->
            addWorkspaceUpRow(server, parent, env, state)
        }
        addWorkspaceFolderList(state, server, env)
        TuiTextButton(
            onClick = { state.updatePath(path.value) },
            modifier = Modifier.testTag("add_workspace_use_folder"),
        ) {
            Text(stringResource(R.string.add_workspace_use_folder))
        }
    }
}

@Composable
private fun addWorkspaceCurrentPath(path: ServerPath) {
    Text(
        stringResource(R.string.add_workspace_current_folder, path.value),
        fontFamily = FontFamily.Monospace,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun addWorkspaceUpRow(
    server: ServerRef,
    parent: ServerPath,
    env: AddWorkspaceEnv,
    state: AddWorkspaceState,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Sizing.minTouchTarget)
            .clickable(role = Role.Button) { env.browse(state, server, parent) }
            .testTag("add_workspace_up"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("↑", modifier = Modifier.padding(end = Spacing.md))
        Text(parent.value, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun addWorkspaceFolderList(
    state: AddWorkspaceState,
    server: ServerRef,
    env: AddWorkspaceEnv,
) {
    when {
        state.isListing -> addWorkspaceProgress(stringResource(R.string.add_workspace_loading))
        state.browseResult is BrowseResult.Listed -> {
            val listing = state.browseResult as BrowseResult.Listed
            if (listing.totalFolders > listing.folders.size) {
                Text(
                    stringResource(
                        R.string.add_workspace_showing_folders,
                        listing.folders.size,
                        listing.totalFolders,
                    ),
                    color = LocalOpenCodeTheme.current.textMuted,
                )
            }
            LazyColumn(modifier = Modifier.heightIn(max = Sizing.embeddedScrollMaxHeight)) {
                itemsIndexed(listing.folders, key = { index, folder -> "$index:$folder" }) { index, folder ->
                    addWorkspaceFolderRow(index, folder, state, server, env)
                }
            }
        }
        else -> Unit
    }
}

@Composable
private fun addWorkspaceFolderRow(
    index: Int,
    folder: String,
    state: AddWorkspaceState,
    server: ServerRef,
    env: AddWorkspaceEnv,
) {
    val child = requireNotNull(state.browsePath).child(folder)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Sizing.minTouchTarget)
            .semantics { contentDescription = child.value }
            .clickable(role = Role.Button) { env.browse(state, server, child) }
            .testTag("add_workspace_dir_$index"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(folder, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun addWorkspaceNameField(
    state: AddWorkspaceState,
    server: ServerRef,
    dependencies: AddWorkspaceDependencies,
) {
    val parsed = (ServerPath.parseUserInput(state.pathText) as? ServerPathParse.Valid)?.path
    val placeholder = parsed?.let { dependencies.generatedNameFor(server, it) }
    OutlinedTextField(
        value = state.nameText,
        onValueChange = state::updateName,
        modifier = Modifier.fillMaxWidth().testTag("add_workspace_name_field"),
        label = { Text(stringResource(R.string.add_workspace_name_label)) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
    )
}

@Composable
private fun addWorkspaceFeedback(state: AddWorkspaceState) {
    val theme = LocalOpenCodeTheme.current
    val text = when {
        state.isValidating -> stringResource(R.string.add_workspace_checking)
        state.addOutcome == AddRootOutcome.Failed -> stringResource(R.string.add_workspace_save_failed)
        else -> null
    }
    if (text != null) {
        val color = if (state.isValidating) theme.textMuted else theme.error
        Text(text, color = color, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    if (state.isValidating) addWorkspaceProgress(null)
}

@Composable
private fun addWorkspaceProgress(label: String?) {
    Row(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(Spacing.inlineSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(Sizing.iconSm))
        if (label != null) Text(label, color = LocalOpenCodeTheme.current.textMuted)
    }
}

@Composable
private fun addWorkspaceFooter(
    state: AddWorkspaceState,
    option: AddWorkspaceServerOption?,
    env: AddWorkspaceEnv,
) {
    val canAdd = option?.connected == true &&
        ServerPath.parseUserInput(state.pathText) is ServerPathParse.Valid &&
        !state.isValidating
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TuiTextButton(onClick = env.onDismiss, modifier = Modifier.testTag("add_workspace_cancel")) {
            Text(stringResource(R.string.button_cancel))
        }
        TuiButton(
            onClick = { option?.let { env.submit(state, it) } },
            enabled = canAdd,
            modifier = Modifier.testTag("add_workspace_confirm"),
        ) {
            Text(stringResource(R.string.add_workspace_confirm))
        }
    }
}

@Composable
private fun workspacePathProblemText(problem: WorkspacePathProblem, server: ServerRef): String = when (problem) {
    is WorkspacePathProblem.Syntax -> pathRejectionText(problem.reason)
    WorkspacePathProblem.Duplicate -> stringResource(R.string.add_workspace_path_duplicate)
    is WorkspacePathProblem.ParentNotListable -> stringResource(
        R.string.add_workspace_path_parent_not_listable,
        problem.parent,
        server.displayName,
    )
    is WorkspacePathProblem.Missing -> stringResource(R.string.add_workspace_path_missing, problem.name, problem.parent)
    is WorkspacePathProblem.IsFile -> stringResource(R.string.add_workspace_path_is_file, problem.name)
    WorkspacePathProblem.NotReadable -> stringResource(R.string.add_workspace_path_not_readable)
    WorkspacePathProblem.Offline -> stringResource(R.string.add_workspace_offline)
    WorkspacePathProblem.Unverifiable -> stringResource(R.string.add_workspace_unverified_warning)
}

@Composable
private fun pathRejectionText(reason: PathRejection): String = when (reason) {
    PathRejection.Blank -> stringResource(R.string.add_workspace_path_blank)
    PathRejection.NotAbsolute, PathRejection.HomeShorthand, PathRejection.FileUri ->
        stringResource(R.string.add_workspace_path_absolute)
    PathRejection.DotSegments -> stringResource(R.string.add_workspace_path_dots)
    PathRejection.ControlCharacters -> stringResource(R.string.add_workspace_path_control)
}

private fun parsePathOrNull(value: String): ServerPath? =
    (ServerPath.parseUserInput(value) as? ServerPathParse.Valid)?.path

private fun defaultServerRoot(): ServerPath =
    (ServerPath.parseUserInput("/") as ServerPathParse.Valid).path
