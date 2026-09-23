package dev.blazelight.p4oc.ui.components.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import dev.blazelight.p4oc.R
import dev.blazelight.p4oc.core.filetype.FileTypeCategory
import dev.blazelight.p4oc.core.filetype.FileTypeClassifier
import dev.blazelight.p4oc.ui.components.TuiButton
import dev.blazelight.p4oc.ui.components.TuiDivider
import dev.blazelight.p4oc.ui.components.TuiTextButton
import dev.blazelight.p4oc.ui.screens.chat.PhoneAttachment
import dev.blazelight.p4oc.ui.screens.chat.PhoneAttachmentIssue
import dev.blazelight.p4oc.ui.screens.chat.PhoneAttachmentReview
import dev.blazelight.p4oc.ui.screens.files.upload.formatFileSize
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.SemanticColors
import dev.blazelight.p4oc.ui.theme.Sizing
import dev.blazelight.p4oc.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming")
fun AttachmentSourceSheet(
    onPhotos: () -> Unit,
    onPhoneFiles: () -> Unit,
    onWorkspace: () -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = LocalOpenCodeTheme.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = theme.background,
        shape = RectangleShape,
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = Spacing.xl)) {
            AttachmentSheetHeader(
                title = stringResource(R.string.attach_sheet_title),
                onDismiss = onDismiss,
            )
            HorizontalDivider(color = theme.border)
            Column(Modifier.fillMaxWidth().padding(vertical = Spacing.sm)) {
                AttachmentSourceRow(
                    icon = Icons.Default.PhotoLibrary,
                    label = stringResource(R.string.attach_source_photos),
                    testTag = "attach_photos",
                    onClick = onPhotos,
                )
                TuiDivider(Modifier.padding(horizontal = Spacing.md))
                AttachmentSourceRow(
                    icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                    label = stringResource(R.string.attach_source_phone_files),
                    testTag = "attach_phone_files",
                    onClick = onPhoneFiles,
                )
                TuiDivider(Modifier.padding(horizontal = Spacing.md))
                AttachmentSourceRow(
                    icon = Icons.Default.FolderOpen,
                    label = stringResource(R.string.attach_source_workspace),
                    testTag = "attach_workspace",
                    onClick = onWorkspace,
                )
                AttachmentSheetNote(stringResource(R.string.attach_sheet_privacy_note))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming", "LongParameterList")
fun PhoneAttachmentSheet(
    review: PhoneAttachmentReview,
    destination: String,
    destinationError: String?,
    loadingDestination: Boolean,
    onChangeFolder: () -> Unit,
    onRemove: (String) -> Unit,
    onUpload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = LocalOpenCodeTheme.current
    val canUpload = review.canUpload && !loadingDestination && destinationError == null
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = theme.background,
        shape = RectangleShape,
        dragHandle = null,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("phone_attachment_review"),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = Spacing.xl)) {
            AttachmentSheetHeader(
                title = stringResource(R.string.attach_review_title),
                trailing = reviewCountLabel(review),
            )
            HorizontalDivider(color = theme.border)
            ReviewBody(
                review = review,
                destination = destination,
                destinationError = destinationError,
                loadingDestination = loadingDestination,
                onChangeFolder = onChangeFolder,
                onRemove = onRemove,
            )
            HorizontalDivider(color = theme.border)
            ReviewActionBar(canUpload = canUpload, onDismiss = onDismiss, onUpload = onUpload)
        }
    }
}

@Composable
@Suppress("FunctionNaming", "LongParameterList")
private fun ColumnScope.ReviewBody(
    review: PhoneAttachmentReview,
    destination: String,
    destinationError: String?,
    loadingDestination: Boolean,
    onChangeFolder: () -> Unit,
    onRemove: (String) -> Unit,
) {
    val theme = LocalOpenCodeTheme.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        ReviewDestinationPanel(destination = destination, onChangeFolder = onChangeFolder)
        if (review.isLoading || loadingDestination) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = theme.accent,
                trackColor = theme.backgroundElement,
            )
        }
        destinationError?.let {
            Text(it, color = theme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (review.files.isEmpty() && !review.isLoading) {
            Text(
                text = stringResource(R.string.attach_review_no_files),
                color = theme.textMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    ReviewFileRows(files = review.files, onRemove = onRemove)
    Text(
        text = stringResource(R.string.attach_review_limits),
        color = theme.textMuted,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
    )
}

@Composable
@Suppress("FunctionNaming")
private fun ColumnScope.ReviewFileRows(
    files: List<PhoneAttachment>,
    onRemove: (String) -> Unit,
) {
    if (files.isEmpty()) {
        return
    }
    LazyColumn(
        Modifier
            .fillMaxWidth()
            .weight(1f, fill = false)
            .heightIn(max = Sizing.embeddedScrollMaxHeight)
            .padding(horizontal = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        items(files, key = { it.sourceId }) { file ->
            PhoneAttachmentRow(file, onRemove)
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun ReviewActionBar(
    canUpload: Boolean,
    onDismiss: () -> Unit,
    onUpload: () -> Unit,
) {
    val theme = LocalOpenCodeTheme.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TuiTextButton(onClick = onDismiss) {
            Text(stringResource(R.string.upload_sheet_cancel), color = theme.textMuted)
        }
        TuiButton(
            onClick = onUpload,
            enabled = canUpload,
            colors = ButtonDefaults.buttonColors(
                containerColor = theme.primary,
                contentColor = theme.background,
                disabledContainerColor = theme.backgroundPanel,
                disabledContentColor = theme.textMuted,
            ),
            modifier = Modifier.testTag("upload_and_attach"),
        ) {
            Text(stringResource(R.string.attach_review_upload_action))
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun AttachmentSheetHeader(
    title: String,
    trailing: String? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val theme = LocalOpenCodeTheme.current
    Surface(color = theme.backgroundElement, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = theme.text)
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                trailing?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = theme.accent)
                }
                onDismiss?.let { dismiss ->
                    IconButton(onClick = dismiss, modifier = Modifier.size(Sizing.iconButtonSm)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.cd_close_dialog),
                            tint = theme.textMuted,
                            modifier = Modifier.size(Sizing.iconSm),
                        )
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun AttachmentSheetNote(text: String) {
    Text(
        text = text,
        color = LocalOpenCodeTheme.current.textMuted,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
    )
}

@Composable
@Suppress("FunctionNaming")
private fun AttachmentSourceRow(
    icon: ImageVector,
    label: String,
    testTag: String,
    onClick: () -> Unit,
) {
    val theme = LocalOpenCodeTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Sizing.minTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = theme.accent,
            modifier = Modifier.size(Sizing.iconMd),
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.text,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = theme.textMuted,
            modifier = Modifier.size(Sizing.iconSm),
        )
    }
}

@Composable
@Suppress("FunctionNaming")
private fun ReviewDestinationPanel(
    destination: String,
    onChangeFolder: () -> Unit,
) {
    val theme = LocalOpenCodeTheme.current
    val serverName = destination.substringBefore('\n').trim()
    val folderPath = destination.substringAfter('\n', "").trim()
    Column(
        Modifier.fillMaxWidth().border(Sizing.strokeThin, theme.border, RectangleShape)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.attach_review_destination),
                style = MaterialTheme.typography.labelMedium,
                color = theme.textMuted,
            )
            TuiTextButton(
                onClick = onChangeFolder,
                modifier = Modifier.testTag("upload_change_folder"),
            ) {
                Text(stringResource(R.string.attach_review_change_folder), color = theme.accent)
            }
        }
        Text(
            text = folderPath,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = theme.text,
            maxLines = 2,
            overflow = TextOverflow.MiddleEllipsis,
        )
        Text(serverName, style = MaterialTheme.typography.labelSmall, color = theme.textMuted)
    }
}

@Composable
@Suppress("FunctionNaming")
private fun PhoneAttachmentRow(
    file: PhoneAttachment,
    onRemove: (String) -> Unit,
) {
    val theme = LocalOpenCodeTheme.current
    val displayName =
        file.metadata.displayName?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.chat_attachment_unnamed)
    val (icon, iconTint) = attachmentFileIcon(displayName)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(R.string.cd_file_icon),
            tint = iconTint,
            modifier = Modifier.size(Sizing.iconMd),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            AttachmentFilename(displayName)
            Text(
                text = "${file.metadata.mimeType
                    ?: stringResource(R.string.chat_attachment_unknown_mime)} · " +
                    formatFileSize(file.metadata.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = theme.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            file.issue?.let {
                Text(
                    text = stringResource(
                        when (it) {
                            PhoneAttachmentIssue.TooLarge -> R.string.upload_too_large_message
                            PhoneAttachmentIssue.Unreadable -> R.string.attach_review_unreadable_file
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.error,
                )
            }
        }
        IconButton(onClick = { onRemove(file.sourceId) }) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(
                    R.string.chat_action_remove_attachment,
                    displayName,
                ),
                tint = theme.textMuted,
                modifier = Modifier.size(Sizing.iconSm),
            )
        }
    }
}

/**
 * Filename with its extension held outside the ellipsis, so long names stay
 * identifiable instead of collapsing to a shared prefix. Names whose last dot
 * is not a real extension stay whole, so the stem can never be squeezed to
 * zero width by the preceding unweighted extension.
 */
@Composable
@Suppress("FunctionNaming")
private fun AttachmentFilename(displayName: String) {
    val theme = LocalOpenCodeTheme.current
    val dot = displayName.lastIndexOf('.')
    val extensionLength = displayName.length - dot - 1
    val split = dot > 0 && extensionLength in 1..MAX_EXTENSION_CHARS
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = if (split) displayName.substring(0, dot) else displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (split) {
            Text(
                text = displayName.substring(dot),
                style = MaterialTheme.typography.bodyMedium,
                color = theme.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val MAX_EXTENSION_CHARS = 8

@Composable
private fun reviewCountLabel(review: PhoneAttachmentReview): String? =
    if (review.files.isEmpty()) {
        null
    } else {
        pluralStringResource(
            R.plurals.attach_review_file_count,
            review.files.size,
            review.files.size,
        )
    }

@Composable
private fun attachmentFileIcon(name: String): Pair<ImageVector, Color> {
    val theme = LocalOpenCodeTheme.current
    return when (FileTypeClassifier.classify(name).category) {
        FileTypeCategory.Code -> Icons.Default.Code to SemanticColors.Status.success
        FileTypeCategory.Config -> Icons.Default.Settings to SemanticColors.MimeType.data
        FileTypeCategory.Document -> Icons.Default.Description to SemanticColors.Reason.info
        FileTypeCategory.Image -> Icons.Default.Image to SemanticColors.MimeType.image
        else -> Icons.AutoMirrored.Filled.InsertDriveFile to theme.textMuted
    }
}
