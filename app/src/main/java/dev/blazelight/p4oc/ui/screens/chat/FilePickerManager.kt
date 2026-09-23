package dev.blazelight.p4oc.ui.screens.chat

import dev.blazelight.p4oc.core.datastore.SettingsDataStore
import dev.blazelight.p4oc.core.log.AppLog
import dev.blazelight.p4oc.core.mime.FilenameMimeType
import dev.blazelight.p4oc.core.network.ApiResult
import dev.blazelight.p4oc.core.network.safeApiCall
import dev.blazelight.p4oc.data.files.ofish.MAX_UPLOAD_SOURCE_BYTES
import dev.blazelight.p4oc.data.workspace.WorkspaceClient
import dev.blazelight.p4oc.domain.model.FileNode
import dev.blazelight.p4oc.ui.components.chat.SelectedFile
import dev.blazelight.p4oc.ui.screens.files.upload.UploadCoordinator
import dev.blazelight.p4oc.ui.screens.files.upload.UploadQueueState
import dev.blazelight.p4oc.ui.screens.files.upload.UploadSource
import dev.blazelight.p4oc.ui.screens.files.upload.UploadSourceMetadata
import dev.blazelight.p4oc.ui.screens.files.upload.sanitizeUploadName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Manages file browser navigation and attachment list.
 */
class FilePickerManager(
    private val workspaceClient: WorkspaceClient,
    private val scope: CoroutineScope,
    private val uploadCoordinator: UploadCoordinator,
    private val settingsDataStore: SettingsDataStore,
) {
    private companion object {
        const val TAG = "FilePickerManager"
    }

    val workspace = workspaceClient.workspace
    private var pickerJob: Job? = null
    private var reviewJob: Job? = null
    private val _phoneReview = MutableStateFlow<PhoneAttachmentReview?>(null)
    val phoneReview = _phoneReview.asStateFlow()

    val hasUnresolvedUploads: Boolean
        get() =
            _phoneReview.value != null ||
                uploadState.value.isActive ||
                uploadState.value.failures.isNotEmpty()

    fun reviewPhoneFiles(source: UploadSource, sourceIds: List<String>) {
        if (sourceIds.isEmpty() ||
            uploadState.value.isActive ||
            uploadState.value.failures.isNotEmpty()
        ) {
            return
        }
        reviewJob?.cancel()
        _phoneReview.value = PhoneAttachmentReview(isLoading = true)
        loadPickerFiles()
        reviewJob =
            scope.launch {
                val files =
                    sourceIds.distinct().map { id ->
                        try {
                            val meta = source.probe(id)
                            PhoneAttachment(
                                sourceId = id,
                                metadata =
                                meta.copy(
                                    displayName =
                                    sanitizeUploadName(
                                        meta.displayName,
                                        System.currentTimeMillis(),
                                    )
                                ),
                                issue =
                                if (meta.sizeBytes > MAX_UPLOAD_SOURCE_BYTES) {
                                    PhoneAttachmentIssue.TooLarge
                                } else {
                                    null
                                },
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            PhoneAttachment(
                                id,
                                UploadSourceMetadata(null, -1, null),
                                PhoneAttachmentIssue.Unreadable,
                            )
                        }
                    }
                _phoneReview.value = PhoneAttachmentReview(files = files)
            }
    }

    fun removePhoneFile(sourceId: String) {
        _phoneReview.update { review ->
            review?.copy(files = review.files.filterNot { it.sourceId == sourceId })
        }
    }

    fun cancelPhoneReview() {
        reviewJob?.cancel()
        _phoneReview.value = null
    }

    fun confirmPhoneUpload(source: UploadSource) {
        val review = _phoneReview.value ?: return
        val destinationReady =
            !_isPickerLoading.value && _pickerError.value == null && workspace.directory != null
        val queueReady = !uploadState.value.isActive && uploadState.value.failures.isEmpty()
        if (!review.canUpload || !destinationReady || !queueReady) return
        val metadata = review.files.associate { it.sourceId to it.metadata }
        val reviewedSource =
            object : UploadSource by source {
                override suspend fun probe(sourceId: String) = metadata.getValue(sourceId)
            }
        uploadAndAttach(reviewedSource, review.files.map { it.sourceId })
        _phoneReview.value = null
    }

    private val _pickerFiles = MutableStateFlow<List<FileNode>>(emptyList())
    val pickerFiles: StateFlow<List<FileNode>> = _pickerFiles.asStateFlow()

    private val _pickerCurrentPath = MutableStateFlow("")
    val pickerCurrentPath: StateFlow<String> = _pickerCurrentPath.asStateFlow()

    private val _isPickerLoading = MutableStateFlow(false)
    val isPickerLoading: StateFlow<Boolean> = _isPickerLoading.asStateFlow()

    private val _pickerError = MutableStateFlow<String?>(null)
    val pickerError: StateFlow<String?> = _pickerError.asStateFlow()

    private val _attachedFiles = MutableStateFlow<List<SelectedFile>>(emptyList())
    val attachedFiles: StateFlow<List<SelectedFile>> = _attachedFiles.asStateFlow()

    val uploadState: StateFlow<UploadQueueState> = uploadCoordinator.state

    fun loadPickerFiles(path: String? = null) {
        pickerJob?.cancel()
        _isPickerLoading.value = true
        pickerJob = scope.launch {
            val workspaceKey = uploadDirectoryWorkspaceKey()
            val rememberedPath = settingsDataStore.lastUploadDirectoriesByWorkspace.first()[workspaceKey]
            val effectivePath = path ?: rememberedPath?.ifBlank { null } ?: "."
            val result = loadPickerFilesForPath(workspaceKey, effectivePath)
            if (result is ApiResult.Error && path == null && effectivePath != ".") {
                AppLog.w(TAG, "Remembered upload folder unavailable; falling back to root")
                settingsDataStore.setLastUploadDirectory(workspaceKey, null)
                loadPickerFilesForPath(workspaceKey, ".")
            }
        }
    }

    private suspend fun loadPickerFilesForPath(workspaceKey: String, path: String): ApiResult<Unit> {
        val result = safeApiCall { workspaceClient.listFiles(path) }
        when (result) {
            is ApiResult.Success -> {
                _pickerError.value = null
                val files = result.data.map { dto ->
                    FileNode(
                        name = dto.name,
                        path = dto.path,
                        absolute = dto.absolute,
                        type = dto.type,
                        ignored = dto.ignored
                    )
                }
                _pickerFiles.value = files
                val resolved = if (path == ".") "" else path
                _pickerCurrentPath.value = resolved
                _isPickerLoading.value = false
                settingsDataStore.setLastUploadDirectory(workspaceKey, resolved)
                return ApiResult.Success(Unit)
            }
            is ApiResult.Error -> {
                AppLog.w(TAG, "Failed to load files")
                _pickerError.value = "Could not load files. Check the connection and try again."
                _isPickerLoading.value = false
                return result
            }
        }
    }

    private fun uploadDirectoryWorkspaceKey(): String = buildString {
        append(workspaceClient.workspace.server.endpointKey)
        append('|')
        append(workspaceClient.workspace.key.toString())
    }

    fun attachFile(file: FileNode) {
        val selected = SelectedFile(path = file.path, name = file.name, mimeType = FilenameMimeType.resolve(file.name))
        _attachedFiles.update { current ->
            if (current.none { it.path == file.path }) {
                current + selected
            } else {
                current
            }
        }
    }

    fun detachFile(path: String) {
        _attachedFiles.update { current ->
            current.filter { it.path != path }
        }
    }

    fun clearAttachedFiles() {
        _attachedFiles.value = emptyList()
    }

    suspend fun validateAttachedFiles(): List<SelectedFile> {
        val current = _attachedFiles.value
        if (current.isEmpty()) return current

        val validated = current.map { file ->
            file.copy(available = isWorkspaceFileAvailable(file.path))
        }
        _attachedFiles.value = validated
        return validated
    }

    private suspend fun isWorkspaceFileAvailable(path: String): Boolean {
        val parentPath = path.substringBeforeLast('/', missingDelimiterValue = "")
        return safeApiCall { workspaceClient.listFiles(parentPath) }
            .getOrNull()
            ?.any { it.path == path && it.type == "file" }
            ?: false
    }

    private fun uploadAndAttach(source: UploadSource, sourceIds: List<String>) {
        val currentPath = _pickerCurrentPath.value.ifBlank { null }
        val deliveredPaths = mutableSetOf<String>()
        uploadCoordinator.upload(
            source = source,
            sourceIds = sourceIds,
            destinationPath = currentPath,
            onComplete = { uploadedFiles ->
                val newlyUploaded = uploadedFiles.filter { deliveredPaths.add(it.destinationPath) }
                if (newlyUploaded.isNotEmpty()) {
                    _attachedFiles.update { current ->
                        newlyUploaded.fold(current) { acc, item ->
                            if (acc.none { it.path == item.destinationPath }) {
                                acc + SelectedFile(
                                    path = item.destinationPath,
                                    name = item.displayName,
                                    mimeType = item.mimeType,
                                    sizeBytes = item.bytesTotal,
                                )
                            } else {
                                acc
                            }
                        }
                    }
                }
            },
        )
    }

    fun cancelUploads() {
        uploadCoordinator.cancel()
    }

    fun retryFailedUploads() {
        uploadCoordinator.retryFailed()
    }

    fun dismissUploadResult() {
        uploadCoordinator.dismiss()
    }

    /**
     * Restore attached files after a failed send — puts them back in the list.
     */
    fun restoreAttachedFiles(files: List<SelectedFile>) {
        _attachedFiles.update { current ->
            files.fold(current) { acc, file ->
                if (acc.none { it.path == file.path }) acc + file else acc
            }
        }
    }
}

/**
 * A pre-upload problem that blocks the reviewed batch. Display text is resolved
 * at the Compose boundary so the message stays localized.
 */
enum class PhoneAttachmentIssue {
    TooLarge,
    Unreadable,
}

data class PhoneAttachment(
    val sourceId: String,
    val metadata: UploadSourceMetadata,
    val issue: PhoneAttachmentIssue? = null,
)

data class PhoneAttachmentReview(
    val files: List<PhoneAttachment> = emptyList(),
    val isLoading: Boolean = false,
) {
    val canUpload: Boolean
        get() = !isLoading && files.isNotEmpty() && files.none { it.issue != null }
}
