package dev.blazelight.p4oc.ui.share

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.Sizing
import dev.blazelight.p4oc.ui.theme.Spacing

/**
 * Chat picker shown after Android hands content to the app: the shared image is attached to whichever
 * chat is chosen (or to a fresh chat in that workspace).
 */
@Suppress("FunctionNaming")
@Composable
fun ShareTargetSheetContent(
    targets: List<ShareTarget>,
    onPick: (ShareTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalOpenCodeTheme.current
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Share to chat",
            style = MaterialTheme.typography.titleMedium,
            color = theme.text,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
        )
        Text(
            text = "Pick a chat. The shared content is attached automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = theme.textMuted,
            modifier = Modifier.padding(horizontal = Spacing.lg)
        )
        LazyColumn(modifier = Modifier.heightIn(max = Sizing.embeddedScrollMaxHeight)) {
            items(targets, key = { "${it.serverRef.endpointKey}|${it.workspaceKey}|${it.sessionId}" }) { target ->
                ShareTargetRow(target, onClick = { onPick(target) })
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun ShareTargetRow(target: ShareTarget, onClick: () -> Unit) {
    val theme = LocalOpenCodeTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("share_target_${target.sessionId ?: "new"}")
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = target.title,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = target.workspaceLabel,
                style = MaterialTheme.typography.bodySmall,
                color = theme.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(text = "→", style = MaterialTheme.typography.bodyMedium, color = theme.textMuted)
    }
    HorizontalDivider(thickness = Sizing.dividerThickness, color = theme.borderSubtle)
}
