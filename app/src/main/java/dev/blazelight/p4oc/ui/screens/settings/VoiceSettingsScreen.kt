package dev.blazelight.p4oc.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.blazelight.p4oc.R
import dev.blazelight.p4oc.core.voice.WhisperModel
import dev.blazelight.p4oc.core.voice.WhisperModelStore
import dev.blazelight.p4oc.ui.components.TuiButton
import dev.blazelight.p4oc.ui.components.TuiTextButton
import dev.blazelight.p4oc.ui.components.TuiTopBar
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.Sizing
import dev.blazelight.p4oc.ui.theme.Spacing
import org.koin.androidx.compose.koinViewModel
import java.util.Locale

@Suppress("LongMethod", "FunctionNaming")
@Composable
fun VoiceSettingsScreen(
    viewModel: VoiceSettingsViewModel = koinViewModel(),
    onNavigateBack: () -> Unit,
) {
    val theme = LocalOpenCodeTheme.current
    val selected by viewModel.selectedModel.collectAsStateWithLifecycle()
    val states by viewModel.modelStates.collectAsStateWithLifecycle()
    val sampleResult by viewModel.sampleResult.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    androidx.compose.material3.Scaffold(
        containerColor = theme.background,
        topBar = {
            TuiTopBar(
                title = stringResource(R.string.settings_voice),
                onNavigateBack = onNavigateBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(title = stringResource(R.string.voice_assistant_section))
            VoiceRow(
                title = stringResource(R.string.voice_assistant_whisper),
                subtitle = stringResource(R.string.voice_assistant_whisper_desc),
                selected = true,
                onClick = {},
            )
            SectionHeader(title = stringResource(R.string.voice_models_section))
            WhisperModel.entries.forEach { model ->
                VoiceModelRow(
                    model = model,
                    state = states[model.id] ?: WhisperModelStore.State.NotDownloaded,
                    selected = model == selected,
                    onSelect = { viewModel.selectModel(model) },
                    onDownload = { viewModel.download(model) },
                    onDelete = { viewModel.delete(model) },
                )
            }
            Text(
                text = stringResource(R.string.voice_models_note),
                style = MaterialTheme.typography.bodySmall,
                color = theme.textMuted,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            )
            if (dev.blazelight.p4oc.BuildConfig.DEBUG) {
                SectionHeader(title = "Debug")
                TuiButton(
                    onClick = {
                        val bytes = runCatching {
                            context.assets.open("jfk.wav").use { it.readBytes() }
                        }.getOrNull()
                        if (bytes != null) viewModel.transcribeSample(bytes)
                    },
                    modifier = Modifier
                        .padding(horizontal = Spacing.lg)
                        .testTag("voice_sample_transcribe"),
                ) { Text("Transcribe sample") }
                sampleResult?.let { result ->
                    Text(
                        text = result,
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.text,
                        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    )
                }
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun VoiceRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val theme = LocalOpenCodeTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, color = theme.text)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = theme.textMuted)
        }
        if (selected) {
            Text(text = "●", color = theme.primary, style = MaterialTheme.typography.bodyMedium)
        }
    }
    HorizontalDivider(thickness = Sizing.dividerThickness, color = theme.borderSubtle)
}

@Suppress("LongMethod", "LongParameterList", "FunctionNaming")
@Composable
private fun VoiceModelRow(
    model: WhisperModel,
    state: WhisperModelStore.State,
    selected: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val theme = LocalOpenCodeTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = state is WhisperModelStore.State.Ready, role = Role.RadioButton, onClick = onSelect)
            .testTag("voice_model_${model.id}")
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.label + if (selected) "  ●" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) theme.primary else theme.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatBytes(model.approxBytes) + " · on-device whisper.cpp",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.textMuted,
                )
            }
            when (state) {
                is WhisperModelStore.State.Ready -> TuiTextButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("voice_model_delete_${model.id}"),
                ) { Text(stringResource(R.string.voice_model_delete)) }
                is WhisperModelStore.State.Downloading -> Text(
                    text = "${(state.bytes * 100 / state.total.coerceAtLeast(1))}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.textMuted,
                )
                is WhisperModelStore.State.Failed -> Column {
                    Text(text = state.message, style = MaterialTheme.typography.bodySmall, color = theme.error)
                    TuiButton(onClick = onDownload) { Text(stringResource(R.string.voice_model_retry)) }
                }
                WhisperModelStore.State.NotDownloaded -> TuiButton(
                    onClick = onDownload,
                    modifier = Modifier.testTag("voice_model_download_${model.id}"),
                ) { Text(stringResource(R.string.voice_model_download)) }
            }
        }
        if (state is WhisperModelStore.State.Downloading) {
            val fraction = (state.bytes.toFloat() / state.total.coerceAtLeast(1)).coerceIn(0f, 1f)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Sizing.progressBarHeight)
                    .background(theme.backgroundPanel),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(Sizing.progressBarHeight)
                        .background(theme.primary),
                ) {}
            }
        }
    }
    HorizontalDivider(thickness = Sizing.dividerThickness, color = theme.borderSubtle)
}

@Suppress("FunctionNaming")
@Composable
private fun SectionHeader(title: String) {
    val theme = LocalOpenCodeTheme.current
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = theme.textMuted,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    )
}

private const val BYTES_PER_MB = 1024.0 * 1024
private const val BYTES_PER_GB = 1024.0 * 1024 * 1024

@Suppress("MagicNumber")
private fun formatBytes(bytes: Long): String = when {
    bytes >= BYTES_PER_GB -> String.format(Locale.US, "%.1f GB", bytes / BYTES_PER_GB)
    else -> String.format(Locale.US, "%.0f MB", bytes / BYTES_PER_MB)
}
