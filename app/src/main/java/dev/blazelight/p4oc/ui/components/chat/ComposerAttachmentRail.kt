package dev.blazelight.p4oc.ui.components.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import dev.blazelight.p4oc.R
import dev.blazelight.p4oc.core.filetype.FileTypeCategory
import dev.blazelight.p4oc.core.filetype.FileTypeClassifier
import dev.blazelight.p4oc.core.mime.FilenameMimeType
import dev.blazelight.p4oc.domain.model.Part
import dev.blazelight.p4oc.ui.screens.files.upload.formatFileSize
import dev.blazelight.p4oc.ui.screens.files.upload.getMimeTypeLabel
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.SemanticColors
import dev.blazelight.p4oc.ui.theme.Sizing
import dev.blazelight.p4oc.ui.theme.Spacing
import dev.blazelight.p4oc.ui.theme.TuiShapes
import java.util.Locale

private const val METADATA_SEPARATOR = " · "
private const val TYPE_LABEL_MAX_CHARS = 6

@Composable
@Suppress("FunctionNaming")
fun ComposerAttachmentRail(
    files: List<SelectedFile>,
    workspaceDirectory: String?,
    onRemove: (String) -> Unit,
) {
    var detail by remember { mutableStateOf<SelectedFile?>(null) }
    LazyRow(
        Modifier.fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
            .testTag("composer_attachments"),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        items(files, key = { it.path }) { file ->
            ComposerAttachmentItem(file, workspaceDirectory, { detail = file }, onRemove)
        }
    }
    detail?.let { AttachmentDetails(it, onDismiss = { detail = null }) }
}

@Composable
@Suppress("FunctionNaming")
private fun ComposerAttachmentItem(
    file: SelectedFile,
    workspaceDirectory: String?,
    onDetails: () -> Unit,
    onRemove: (String) -> Unit,
) {
    val theme = LocalOpenCodeTheme.current
    val removeDescription = stringResource(R.string.cd_remove_draft_attachment, file.name)
    val part =
        remember(file, workspaceDirectory) {
            workspaceDirectory?.let {
                Part.File(
                    id = file.path,
                    sessionID = "",
                    messageID = "",
                    filename = file.name,
                    mime = file.mimeType ?: FilenameMimeType.resolveOrOctetStream(file.name),
                    url = file.toOpenCodeFileUrl(it),
                )
            }
        }
    Surface(
        color = theme.backgroundPanel,
        shape = TuiShapes.extraSmall,
        border = BorderStroke(Sizing.strokeThin, theme.border),
    ) {
        Row(
            Modifier.height(Sizing.listItemHeightLg)
                .clickable(role = Role.Button, onClick = onDetails)
                .testTag("composer_attachment_${file.path}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The leading square is the same footprint for every file: a bounded
            // preview when the image is safely loadable, otherwise the type icon.
            if (file.available && part?.mime?.startsWith("image/") == true) {
                ChatAttachment(part, Modifier.size(Sizing.listItemHeightLg), compact = true)
            } else {
                ComposerAttachmentTypeIcon(file)
            }
            ComposerAttachmentLabels(file)
            IconButton(
                onClick = { onRemove(file.path) },
                modifier = Modifier
                    .testTag("composer_attachment_remove_${file.path}")
                    .semantics {
                        contentDescription = removeDescription
                    },
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = theme.textMuted,
                    modifier = Modifier.size(Sizing.iconMd),
                )
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun ComposerAttachmentLabels(file: SelectedFile) {
    val theme = LocalOpenCodeTheme.current
    Column(
        Modifier.width(Sizing.panelWidthMd)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.hairline),
    ) {
        Text(
            file.name,
            color = if (file.available) theme.text else theme.warning,
            maxLines = 1,
            // Middle-ellipsis keeps the extension and the differentiating tail
            // of long, similar names visible inside the fixed chip width.
            overflow = TextOverflow.MiddleEllipsis,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            attachmentMetadata(file),
            color = theme.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
            ),
        )
    }
}

@Composable
@Suppress("FunctionNaming")
private fun ComposerAttachmentTypeIcon(file: SelectedFile) {
    val theme = LocalOpenCodeTheme.current
    val (icon, tint) = attachmentTypeIcon(file.name)
    Box(
        modifier = Modifier
            .size(Sizing.listItemHeightLg)
            .background(theme.backgroundElement)
            .border(Sizing.strokeThin, theme.border),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(Sizing.iconLg),
        )
    }
}

@Composable
private fun attachmentTypeIcon(name: String): Pair<ImageVector, Color> {
    val theme = LocalOpenCodeTheme.current
    return when (FileTypeClassifier.classify(name).category) {
        FileTypeCategory.Code -> Icons.Default.Code to SemanticColors.Status.success
        FileTypeCategory.Config -> Icons.Default.Settings to SemanticColors.MimeType.data
        FileTypeCategory.Document -> Icons.Default.Description to SemanticColors.Reason.info
        FileTypeCategory.Image -> Icons.Default.Image to SemanticColors.MimeType.image
        else -> Icons.AutoMirrored.Filled.InsertDriveFile to theme.textMuted
    }
}

/**
 * Second line of a draft chip: known type and size, an explicit unavailable
 * state, or a neutral label when the draft carries no metadata at all.
 */
@Composable
private fun attachmentMetadata(file: SelectedFile): String {
    if (!file.available) return stringResource(R.string.attachment_detail_unavailable)
    val known = listOfNotNull(
        attachmentTypeLabel(file),
        file.sizeBytes?.let { formatFileSize(it) },
    )
    return known.joinToString(separator = METADATA_SEPARATOR)
        .ifEmpty { stringResource(R.string.attachment_detail_attached) }
}

/**
 * Type label for a file: its extension when usable and short, else the
 * classified category, else the MIME-derived label.
 */
private fun attachmentTypeLabel(file: SelectedFile): String? {
    val extension = file.name.substringAfterLast('.', missingDelimiterValue = "")
    val category = FileTypeClassifier.classify(file.name).category
    return when {
        extension.isNotBlank() && extension.length <= TYPE_LABEL_MAX_CHARS ->
            extension.uppercase(Locale.US)
        category != FileTypeCategory.Unknown -> category.name.uppercase(Locale.US)
        else -> file.mimeType?.takeIf { it.isNotBlank() }?.let { getMimeTypeLabel(it) }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun AttachmentDetails(file: SelectedFile, onDismiss: () -> Unit) {
    val theme = LocalOpenCodeTheme.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("composer_attachment_details_dialog"),
            color = theme.background,
            shape = TuiShapes.extraSmall,
            border = BorderStroke(Sizing.strokeThin, theme.border),
        ) {
            Column(
                Modifier.padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(
                    stringResource(R.string.attachment_detail_title),
                    color = theme.text,
                    style = MaterialTheme.typography.titleSmall,
                )
                AttachmentDetailRow(stringResource(R.string.attachment_detail_name), file.name)
                AttachmentDetailRow(stringResource(R.string.attachment_detail_path), file.path)
                AttachmentDetailRow(
                    stringResource(R.string.attachment_detail_type),
                    file.mimeType?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.attachment_detail_unknown_type),
                )
                AttachmentDetailRow(
                    stringResource(R.string.attachment_detail_size),
                    file.sizeBytes?.let { formatFileSize(it) }
                        ?: stringResource(R.string.attachment_detail_unknown_size),
                )
                AttachmentDetailRow(
                    stringResource(R.string.attachment_detail_status),
                    if (file.available) {
                        stringResource(R.string.attachment_detail_attached)
                    } else {
                        stringResource(R.string.attachment_detail_unavailable)
                    },
                    valueColor = if (file.available) theme.text else theme.warning,
                )
                Text(
                    stringResource(R.string.attachment_detail_remove_note),
                    color = theme.textMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.close), color = theme.accent)
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun AttachmentDetailRow(
    label: String,
    value: String,
    valueColor: Color? = null,
) {
    val theme = LocalOpenCodeTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.hairline)) {
        Text(
            label,
            color = theme.textMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            value,
            color = valueColor ?: theme.text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
    }
}
