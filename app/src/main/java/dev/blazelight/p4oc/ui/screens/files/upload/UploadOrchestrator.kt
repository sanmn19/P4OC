package dev.blazelight.p4oc.ui.screens.files.upload

import dev.blazelight.p4oc.data.files.FileOperationResult
import dev.blazelight.p4oc.data.files.FileRepository
import dev.blazelight.p4oc.data.files.FileUploadRequest
import dev.blazelight.p4oc.data.files.ofish.MAX_UPLOAD_SOURCE_BYTES
import dev.blazelight.p4oc.data.files.ofish.UPLOAD_TOO_LARGE_MESSAGE
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * Drives a serial whole-file upload batch and exposes per-item progress as
 * [UploadQueueState].
 *
 * Retry policy: only [FileOperationResult.Failed] outcomes are retried (up to
 * [maxAttempts] total attempts). [FileOperationResult.Conflict] is treated as
 * a terminal user-actionable failure for that item — we do not blindly retry
 * past hash conflicts. Retry reopens the source stream; the OFISH layer aborts
 * its temp file on every failed chunk, so there is no remote partial state to
 * preserve.
 */
class UploadOrchestrator(
    private val fileRepository: FileRepository,
    private val source: UploadSource,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val retryDelayMillis: (attempt: Int) -> Long = ::defaultBackoff,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(UploadQueueState())
    val state: StateFlow<UploadQueueState> = _state.asStateFlow()
    private val operationGeneration = AtomicLong()

    data class Plan(
        val sourceId: String,
        val displayName: String?,
        val sizeBytes: Long,
        val mimeType: String?,
        val probeFailure: String? = null,
    )

    /**
     * Run the batch. Returns when finished or cancelled. Suspends per item;
     * caller controls the scope/dispatcher. Safe to cancel via the calling
     * coroutine.
     */
    @Suppress("ReturnCount")
    suspend fun run(currentPath: String?, plans: List<Plan>): UploadQueueState {
        val generation = operationGeneration.incrementAndGet()
        val items = plans.map { plan ->
            val sanitized = sanitizeUploadName(plan.displayName, now())
            UploadItem(
                sourceId = plan.sourceId,
                displayName = sanitized,
                destinationPath = joinDestinationPath(currentPath, sanitized),
                mimeType = plan.mimeType ?: DEFAULT_MIME,
                bytesTotal = plan.sizeBytes,
                probeFailure = plan.probeFailure,
            )
        }
        _state.update { state ->
            if (operationGeneration.get() != generation) {
                state
            } else {
                UploadQueueState(items = items, currentIndex = 0, isActive = items.isNotEmpty())
            }
        }
        if (operationGeneration.get() != generation) return _state.value

        items.forEachIndexed { index, _ ->
            if (operationGeneration.get() != generation) return _state.value
            mutate(generation) { it.copy(currentIndex = index) }
            uploadOne(index, generation)
        }

        mutate(generation) { it.copy(isActive = false) }
        return _state.value
    }

    /**
     * Re-run only the items currently in [UploadPhase.Failed], preserving
     * already-completed entries in the queue so the UI keeps showing
     * successful uploads.
     */
    suspend fun retryFailed() {
        val generation = operationGeneration.incrementAndGet()
        val snapshot = _state.value
        val failedIndices = snapshot.items.mapIndexedNotNull { i, item ->
            if (item.phase is UploadPhase.Failed) i else null
        }
        if (failedIndices.isEmpty()) return
        // Reset failed items to Pending and mark active; preserve done items.
        _state.update { state ->
            val items = state.items.toMutableList()
            failedIndices.forEach { i ->
                items[i] = items[i].copy(phase = UploadPhase.Pending, attempts = 0)
            }
            state.copy(items = items, isActive = true, cancelled = false)
        }
        for (idx in failedIndices) {
            mutate(generation) { it.copy(currentIndex = idx) }
            uploadOne(idx, generation)
        }
        mutate(generation) { it.copy(isActive = false) }
    }

    /**
     * Mark the queue cancelled and convert any in-flight item (Reading /
     * Uploading / Pending) into a terminal Failed(CANCELLED_MESSAGE) so the UI
     * doesn't keep displaying it as active forever after the job is killed.
     */
    fun markCancelled() {
        operationGeneration.incrementAndGet()
        _state.update { state ->
            val items = state.items.map { item ->
                when (item.phase) {
                    UploadPhase.Pending,
                    UploadPhase.Reading,
                    UploadPhase.Uploading -> item.copy(phase = UploadPhase.Failed(CANCELLED_MESSAGE))
                    else -> item
                }
            }
            state.copy(items = items, isActive = false, cancelled = true)
        }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private suspend fun uploadOne(index: Int, generation: Long) {
        if (operationGeneration.get() != generation) return
        val item = _state.value.items.getOrNull(index) ?: return
        if (item.bytesTotal > MAX_UPLOAD_SOURCE_BYTES) {
            updateItem(index, generation) { it.copy(phase = UploadPhase.Failed(UPLOAD_TOO_LARGE_MESSAGE)) }
            return
        }
        var lastFailure: String? = null
        for (attempt in 1..maxAttempts) {
            if (operationGeneration.get() != generation) return
            updateItem(index, generation) {
                it.copy(
                    phase = UploadPhase.Reading,
                    attempts = attempt,
                    bytesUploaded = 0L,
                )
            }
            val request = uploadRequest(item, index, generation)
            when (val result = fileRepository.uploadFile(request)) {
                is FileOperationResult.Ok -> {
                    if (operationGeneration.get() != generation) return
                    updateItem(index, generation) {
                        val total = if (it.bytesTotal < 0) it.bytesUploaded else it.bytesTotal
                        it.copy(phase = UploadPhase.Done, bytesTotal = total, bytesUploaded = total)
                    }
                    return
                }
                is FileOperationResult.Conflict -> {
                    if (operationGeneration.get() != generation) return
                    updateItem(index, generation) {
                        it.copy(phase = UploadPhase.Failed(CONFLICT_MESSAGE))
                    }
                    return
                }
                is FileOperationResult.Failed -> {
                    if (operationGeneration.get() != generation) return
                    lastFailure = if (result.message == UPLOAD_TOO_LARGE_MESSAGE) {
                        UPLOAD_TOO_LARGE_MESSAGE
                    } else {
                        FAILURE_MESSAGE
                    }
                    if (attempt < maxAttempts) delay(retryDelayMillis(attempt))
                }
            }
        }
        updateItem(index, generation) {
            it.copy(phase = UploadPhase.Failed(lastFailure ?: FAILURE_MESSAGE))
        }
    }

    private fun uploadRequest(item: UploadItem, index: Int, generation: Long) = FileUploadRequest(
        path = item.destinationPath,
        contentLength = item.bytesTotal,
        openStream = { source.openStream(item.sourceId) },
        expectedHash = null,
        createOnly = true,
        onBytesUploaded = { uploaded ->
            updateItem(index, generation) { current ->
                current.copy(phase = UploadPhase.Uploading, bytesUploaded = uploaded)
            }
        },
    )

    private fun updateItem(index: Int, generation: Long, transform: (UploadItem) -> UploadItem) {
        _state.update { state ->
            if (operationGeneration.get() != generation) return@update state
            val current = state.items.getOrNull(index) ?: return@update state
            state.copy(items = state.items.toMutableList().also { it[index] = transform(current) })
        }
    }

    private inline fun mutate(generation: Long, crossinline transform: (UploadQueueState) -> UploadQueueState) {
        _state.update { state ->
            if (operationGeneration.get() != generation) state else transform(state)
        }
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3

        const val DEFAULT_MIME = "application/octet-stream"
        const val CANCELLED_MESSAGE =
            "Upload cancelled. Retry or remove this entry; completed workspace files were kept."
        private const val CONFLICT_MESSAGE =
            "A file with this name already exists, or this folder cannot accept a new file. " +
                "Choose another folder; existing files were not replaced."
        private const val FAILURE_MESSAGE =
            "Could not upload this file. Check the connection and folder permissions, then retry. " +
                "If the phone file is no longer accessible, remove it and choose it again."

        private fun defaultBackoff(attempt: Int): Long = when (attempt) {
            1 -> 200L
            2 -> 600L
            else -> 1_000L
        }
    }
}
