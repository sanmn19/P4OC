package dev.blazelight.p4oc.ui.screens.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.blazelight.p4oc.R
import dev.blazelight.p4oc.core.datastore.NotificationRoutingMode
import dev.blazelight.p4oc.core.datastore.NotificationSettings
import dev.blazelight.p4oc.core.datastore.SavedServer
import dev.blazelight.p4oc.core.datastore.SettingsDataStore
import dev.blazelight.p4oc.core.datastore.VibrationPattern
import dev.blazelight.p4oc.core.haptic.HapticFeedback
import dev.blazelight.p4oc.ui.components.TuiAlertDialog
import dev.blazelight.p4oc.ui.components.TuiButton
import dev.blazelight.p4oc.ui.components.TuiDropdownMenuItem
import dev.blazelight.p4oc.ui.components.TuiSwitch
import dev.blazelight.p4oc.ui.components.TuiTextButton
import dev.blazelight.p4oc.ui.components.TuiTopBar
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.Sizing
import dev.blazelight.p4oc.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

private const val DISABLED_ROUTING_ALPHA = 0.4f

class NotificationSettingsViewModel constructor(
    private val settingsDataStore: SettingsDataStore,
    private val hapticFeedback: HapticFeedback,
) : ViewModel() {

    private val _settings = MutableStateFlow(NotificationSettings())
    val settings: StateFlow<NotificationSettings> = _settings.asStateFlow()

    private val _savedServers = MutableStateFlow<List<SavedServer>>(emptyList())
    val savedServers: StateFlow<List<SavedServer>> = _savedServers.asStateFlow()

    private val _connectionSettings = MutableStateFlow(
        dev.blazelight.p4oc.core.datastore.ConnectionSettings()
    )
    val connectionSettings: StateFlow<dev.blazelight.p4oc.core.datastore.ConnectionSettings> =
        _connectionSettings.asStateFlow()

    init {
        viewModelScope.launch {
            settingsDataStore.notificationSettings.collect { saved ->
                _settings.value = saved
            }
        }
        viewModelScope.launch {
            settingsDataStore.connectionSettings.collect { saved ->
                _connectionSettings.value = saved
            }
        }
        viewModelScope.launch {
            settingsDataStore.savedServers.collect { servers ->
                _savedServers.value = servers
            }
        }
    }

    fun setServerRouting(endpointKey: String, mode: NotificationRoutingMode) {
        val updatedRouting = _settings.value.serverRouting.toMutableMap().apply { put(endpointKey, mode) }
        val new = _settings.value.copy(serverRouting = updatedRouting)
        _settings.value = new
        viewModelScope.launch { settingsDataStore.updateNotificationSettings(new) }
    }

    fun setEnabled(enabled: Boolean) {
        val new = _settings.value.copy(enabled = enabled)
        _settings.value = new
        viewModelScope.launch { settingsDataStore.updateNotificationSettings(new) }
    }

    fun togglePermissionRequests() {
        val new = _settings.value.copy(permissionRequests = !_settings.value.permissionRequests)
        _settings.value = new
        viewModelScope.launch { settingsDataStore.updateNotificationSettings(new) }
    }

    fun toggleQuestions() {
        val new = _settings.value.copy(questions = !_settings.value.questions)
        _settings.value = new
        viewModelScope.launch { settingsDataStore.updateNotificationSettings(new) }
    }

    fun toggleNotifyOnCompletion() {
        val new = _settings.value.copy(notifyOnCompletion = !_settings.value.notifyOnCompletion)
        _settings.value = new
        viewModelScope.launch { settingsDataStore.updateNotificationSettings(new) }
    }

    fun setKeepLiveInBackground(keep: Boolean) {
        val new = _connectionSettings.value.copy(keepLiveInBackground = keep)
        _connectionSettings.value = new
        viewModelScope.launch { settingsDataStore.updateConnectionSettings(new) }
    }

    fun setVibrationPattern(pattern: VibrationPattern) {
        val new = _settings.value.copy(vibrationPattern = pattern)
        _settings.value = new
        viewModelScope.launch { settingsDataStore.updateNotificationSettings(new) }
    }

    fun previewVibration(pattern: VibrationPattern) {
        hapticFeedback.preview(pattern)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    viewModel: NotificationSettingsViewModel = koinViewModel(),
    onNavigateBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connectionSettings by viewModel.connectionSettings.collectAsStateWithLifecycle()
    val savedServers by viewModel.savedServers.collectAsStateWithLifecycle()
    val theme = LocalOpenCodeTheme.current
    val context = LocalContext.current

    var hasPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    var showPermissionDeniedDialog by remember { mutableStateOf(false) }
    var showVibrationPatternDialog by remember { mutableStateOf(false) }

    // Re-check permission when screen resumes (e.g., returning from system settings)
    LifecycleResumeEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) {
            viewModel.setEnabled(true)
        } else {
            viewModel.setEnabled(false)
            showPermissionDeniedDialog = true
        }
    }

    Scaffold(
        containerColor = theme.background,
        topBar = {
            TuiTopBar(
                title = stringResource(R.string.settings_notifications),
                onNavigateBack = onNavigateBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Permission warning banner (when denied on API 33+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission) {
                PermissionWarningBanner(
                    onRequestPermission = {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                )
            }

            // General section
            SectionHeader(title = stringResource(R.string.notification_general))

            NotificationSwitch(
                title = stringResource(R.string.notification_enable),
                subtitle = stringResource(R.string.notification_enable_desc),
                icon = Icons.Default.Notifications,
                checked = settings.enabled,
                onCheckedChange = { shouldEnable ->
                    if (!shouldEnable) {
                        viewModel.setEnabled(false)
                        return@NotificationSwitch
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.setEnabled(true)
                    }
                },
                enabled = true,
                testTag = "notification_enable_switch"
            )

            // Types section
            SectionHeader(title = stringResource(R.string.notification_types))

            NotificationSwitch(
                title = stringResource(R.string.notification_permission_requests),
                subtitle = stringResource(R.string.notification_permission_requests_desc),
                icon = Icons.Default.Security,
                checked = settings.permissionRequests,
                onCheckedChange = { viewModel.togglePermissionRequests() },
                enabled = settings.enabled,
                testTag = "notification_permissions_switch"
            )

            NotificationSwitch(
                title = stringResource(R.string.notification_questions),
                subtitle = stringResource(R.string.notification_questions_desc),
                icon = Icons.Default.QuestionAnswer,
                checked = settings.questions,
                onCheckedChange = { viewModel.toggleQuestions() },
                enabled = settings.enabled,
                testTag = "notification_questions_switch"
            )

            NotificationSwitch(
                title = stringResource(R.string.notification_completion),
                subtitle = stringResource(R.string.notification_completion_desc),
                icon = Icons.Default.DoneAll,
                checked = settings.notifyOnCompletion,
                onCheckedChange = { viewModel.toggleNotifyOnCompletion() },
                enabled = settings.enabled,
                testTag = "notify_on_completion_switch"
            )

            NotificationSwitch(
                title = stringResource(R.string.live_connection_keep_alive),
                subtitle = stringResource(R.string.live_connection_keep_alive_desc),
                icon = Icons.Default.Sync,
                checked = connectionSettings.keepLiveInBackground,
                onCheckedChange = { keep -> viewModel.setKeepLiveInBackground(keep) },
                enabled = true,
                testTag = "live_connection_switch"
            )

            VibrationPatternRow(
                pattern = settings.vibrationPattern,
                onClick = { showVibrationPatternDialog = true }
            )

            NotificationRoutingSection(savedServers, settings, viewModel::setServerRouting)
        }
    }

    if (showPermissionDeniedDialog) {
        TuiAlertDialog(
            onDismissRequest = { showPermissionDeniedDialog = false },
            title = stringResource(R.string.notification_permission_required),
            icon = Icons.Default.NotificationsOff,
            confirmButton = {
                TuiButton(
                    onClick = {
                        showPermissionDeniedDialog = false
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                        context.startActivity(intent)
                    }
                ) {
                    Text(stringResource(R.string.notification_open_settings))
                }
            },
            dismissButton = {
                TuiTextButton(onClick = { showPermissionDeniedDialog = false }) {
                    Text(stringResource(R.string.button_cancel))
                }
            }
        ) {
            Text(
                text = stringResource(R.string.notification_permission_denied_message),
                style = MaterialTheme.typography.bodyMedium,
                color = theme.textMuted
            )
        }
    }

    if (showVibrationPatternDialog) {
        VibrationPatternDialog(
            currentPattern = settings.vibrationPattern,
            onPreview = viewModel::previewVibration,
            onConfirm = { pattern ->
                viewModel.setVibrationPattern(pattern)
                showVibrationPatternDialog = false
            },
            onDismiss = { showVibrationPatternDialog = false }
        )
    }
}

@Composable
@Suppress("FunctionNaming")
private fun NotificationRoutingSection(
    savedServers: List<SavedServer>,
    settings: NotificationSettings,
    onSelect: (String, NotificationRoutingMode) -> Unit,
) {
    val theme = LocalOpenCodeTheme.current
    SectionHeader(title = stringResource(R.string.notification_routing_section))
    if (savedServers.isEmpty()) {
        Text(
            text = stringResource(R.string.notification_routing_empty),
            style = MaterialTheme.typography.bodySmall,
            color = theme.textMuted,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
        )
        return
    }
    savedServers.forEach { server ->
        ServerRoutingRow(
            name = server.displayName,
            endpoint = server.endpointKey,
            mode = settings.serverRouting[server.endpointKey] ?: NotificationRoutingMode.All,
            enabled = settings.enabled,
            onSelect = { onSelect(server.endpointKey, it) },
        )
    }
}

@Composable
private fun PermissionWarningBanner(
    onRequestPermission: () -> Unit
) {
    val theme = LocalOpenCodeTheme.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        color = theme.warning.copy(alpha = 0.1f),
        shape = RectangleShape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = theme.warning,
                modifier = Modifier.size(Sizing.iconMd)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.notification_permission_not_granted),
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.text
                )
                Text(
                    text = stringResource(R.string.notification_permission_tap_to_grant),
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.textMuted
                )
            }
            TuiButton(onClick = onRequestPermission) {
                Text(stringResource(R.string.notification_grant))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    dev.blazelight.p4oc.ui.components.TuiSectionHeader(title)
}

@Composable
@Suppress("FunctionNaming")
private fun routingModeColor(mode: NotificationRoutingMode): Color {
    val theme = LocalOpenCodeTheme.current
    return when (mode) {
        NotificationRoutingMode.All -> theme.success
        NotificationRoutingMode.Mentions -> theme.warning
        NotificationRoutingMode.Off -> theme.textMuted
    }
}

@Composable
@Suppress("FunctionNaming")
private fun routingModeLabel(mode: NotificationRoutingMode): String = stringResource(
    when (mode) {
        NotificationRoutingMode.All -> R.string.notification_routing_all
        NotificationRoutingMode.Mentions -> R.string.notification_routing_mentions
        NotificationRoutingMode.Off -> R.string.notification_routing_off
    }
)

@Composable
@Suppress("FunctionNaming")
private fun ServerRoutingRow(
    name: String,
    endpoint: String,
    mode: NotificationRoutingMode,
    enabled: Boolean,
    onSelect: (NotificationRoutingMode) -> Unit,
) {
    val theme = LocalOpenCodeTheme.current
    val contentAlpha = if (enabled) 1f else DISABLED_ROUTING_ALPHA
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = theme.text.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = endpoint,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = theme.textMuted.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RoutingModePicker(endpoint, mode, enabled, contentAlpha, onSelect)
    }
    HorizontalDivider(thickness = Sizing.dividerThickness, color = theme.borderSubtle)
}

@Composable
@Suppress("FunctionNaming")
private fun RoutingModePicker(
    endpoint: String,
    mode: NotificationRoutingMode,
    enabled: Boolean,
    contentAlpha: Float,
    onSelect: (NotificationRoutingMode) -> Unit,
) {
    val theme = LocalOpenCodeTheme.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier
                .heightIn(min = Sizing.minTouchTarget)
                .testTag("notification_routing_$endpoint"),
            enabled = enabled,
            shape = RectangleShape,
            color = Color.Transparent,
            border = BorderStroke(Sizing.strokeMd, theme.border),
        ) {
            Text(
                text = "${routingModeLabel(mode)} ▾",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = routingModeColor(mode).copy(alpha = contentAlpha),
                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            NotificationRoutingMode.entries.forEach { option ->
                TuiDropdownMenuItem(
                    text = routingModeLabel(option),
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                    modifier = Modifier.semantics { selected = option == mode },
                    trailingIcon = Icons.Default.Check.takeIf { option == mode },
                )
            }
        }
    }
}

@Composable
private fun VibrationPatternRow(
    pattern: VibrationPattern,
    onClick: () -> Unit,
) {
    val theme = LocalOpenCodeTheme.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("vibration_pattern_row")
            .padding(horizontal = Spacing.lg, vertical = Spacing.mdLg),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Vibration,
                contentDescription = null,
                tint = theme.textMuted
            )
            Column {
                Text(
                    text = stringResource(R.string.notification_vibration_pattern),
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.text
                )
                Text(
                    text = stringResource(pattern.labelRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.textMuted
                )
            }
        }
        Text(
            text = "→",
            style = MaterialTheme.typography.bodyMedium,
            color = theme.textMuted
        )
    }
    HorizontalDivider(
        thickness = Sizing.dividerThickness,
        color = theme.borderSubtle
    )
}

@Composable
private fun VibrationPatternDialog(
    currentPattern: VibrationPattern,
    onPreview: (VibrationPattern) -> Unit,
    onConfirm: (VibrationPattern) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedPattern by remember(currentPattern) { mutableStateOf(currentPattern) }

    TuiAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.notification_vibration_pattern),
        icon = Icons.Default.Vibration,
        confirmButton = {
            TuiButton(onClick = { onConfirm(selectedPattern) }) {
                Text(stringResource(R.string.button_done))
            }
        },
        dismissButton = {
            TuiTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_cancel))
            }
        },
        modifier = Modifier.testTag("vibration_pattern_dialog"),
    ) {
        VibrationPattern.entries.forEach { pattern ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selectedPattern == pattern,
                        role = Role.RadioButton,
                    ) {
                        selectedPattern = pattern
                        onPreview(pattern)
                    }
                    .testTag("vibration_pattern_${pattern.storageValue}")
                    .padding(vertical = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selectedPattern == pattern,
                    onClick = null,
                )
                Text(
                    text = stringResource(pattern.labelRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalOpenCodeTheme.current.text
                )
            }
        }
    }
}

private fun VibrationPattern.labelRes(): Int = when (this) {
    VibrationPattern.None -> R.string.vibration_pattern_none
    VibrationPattern.Tick -> R.string.vibration_pattern_tick
    VibrationPattern.Click -> R.string.vibration_pattern_click
    VibrationPattern.HeavyClick -> R.string.vibration_pattern_heavy_click
    VibrationPattern.DoubleClick -> R.string.vibration_pattern_double_click
    VibrationPattern.LongPulse -> R.string.vibration_pattern_long_pulse
    VibrationPattern.DoubleLongPulse -> R.string.vibration_pattern_double_long_pulse
}

@Composable
private fun NotificationSwitch(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
    testTag: String
) {
    val theme = LocalOpenCodeTheme.current
    val contentAlpha = if (enabled) 1f else 0.5f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(contentAlpha)
            .padding(horizontal = Spacing.lg, vertical = Spacing.mdLg),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = theme.textMuted
            )
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.text
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.textMuted
                )
            }
        }
        TuiSwitch(
            checked = checked,
            onCheckedChange = { onCheckedChange(!checked) },
            enabled = enabled,
            modifier = Modifier.testTag(testTag)
        )
    }
    HorizontalDivider(
        thickness = Sizing.dividerThickness,
        color = theme.borderSubtle
    )
}
