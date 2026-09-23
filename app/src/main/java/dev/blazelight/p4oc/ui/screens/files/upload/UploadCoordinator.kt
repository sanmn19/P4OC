package dev.blazelight.p4oc.ui.screens.files.upload

import dev.blazelight.p4oc.data.files.FileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class UploadCoordinator(
    private val scope: CoroutineScope,
    private val repositoryFactory: () -> FileRepository,
) {
    private data class UploadCallbacks(
        val destinationPath: String?,
        val onComplete: suspend (List<UploadItem>) -> Unit,
    )

    private val _state = MutableStateFlow(UploadQueueState())
    val state: StateFlow<UploadQueueState> = _state

    private var uploadJob: Job? = null
    private var orchestrator: UploadOrchestrator? = null
    private var callbacks: UploadCallbacks? = null
    private val generation = AtomicLong()

    fun upload(
        source: UploadSource,
        sourceIds: List<String>,
        destinationPath: String?,
        onComplete: suspend (List<UploadItem>) -> Unit = {},
    ) {
        if (sourceIds.isEmpty()) return
        if (_state.value.isActive) {
            _state.value = _state.value.copy(
                notice = "Upload already in progress; wait for it to finish before adding more files",
            )
            return
        }

        val currentGeneration = generation.incrementAndGet()
        val currentOrchestrator = UploadOrchestrator(
            fileRepository = repositoryFactory(),
            source = source,
        ).also { orchestrator = it }
        val currentCallbacks = UploadCallbacks(destinationPath, onComplete).also { callbacks = it }

        uploadJob?.cancel()
        _state.value = UploadQueueState(isActive = true)
        uploadJob = scope.launch(Dispatchers.IO) {
            val mirrorJob = launch {
                currentOrchestrator.state.collect {
                    if (generation.get() == currentGeneration) _state.value = it.copy(isActive = true)
                }
            }
            try {
                val plans = sourceIds.map { id ->
                    val metaResult = runCatching { source.probe(id) }
                    (metaResult.exceptionOrNull() as? CancellationException)?.let { throw it }
                    val meta = metaResult.getOrNull()
                    UploadOrchestrator.Plan(
                        sourceId = id,
                        displayName = meta?.displayName,
                        sizeBytes = meta?.sizeBytes ?: -1L,
                        mimeType = meta?.mimeType,
                        probeFailure = metaResult.exceptionOrNull()?.message,
                    )
                }
                val finalState = currentOrchestrator.run(currentCallbacks.destinationPath, plans)
                mirrorJob.cancelAndJoin()
                if (generation.get() == currentGeneration) {
                    currentCallbacks.onComplete(finalState.successes)
                    _state.value = finalState
                }
            } finally {
                mirrorJob.cancel()
            }
        }
    }

    fun retryFailed() {
        val currentOrchestrator = orchestrator ?: return
        if (_state.value.failures.isEmpty() || _state.value.isActive) return
        val currentGeneration = generation.incrementAndGet()
        uploadJob?.cancel()
        _state.value = _state.value.copy(isActive = true)
        uploadJob = scope.launch(Dispatchers.IO) {
            val mirrorJob = launch {
                currentOrchestrator.state.collect {
                    if (generation.get() == currentGeneration) _state.value = it.copy(isActive = true)
                }
            }
            try {
                currentOrchestrator.retryFailed()
                val finalState = currentOrchestrator.state.value
                mirrorJob.cancelAndJoin()
                if (generation.get() == currentGeneration) {
                    callbacks?.onComplete(finalState.successes)
                    _state.value = finalState
                }
            } finally {
                mirrorJob.cancel()
            }
        }
    }

    fun cancel() {
        if (!_state.value.isActive) return
        val currentJob = uploadJob
        val currentOrchestrator = orchestrator
        if (currentJob == null || currentOrchestrator == null) return
        generation.incrementAndGet()
        currentOrchestrator.markCancelled()
        val completed = currentOrchestrator.state.value.successes
        val completion = callbacks?.onComplete
        val finalState = currentOrchestrator.state.value
        _state.value = finalState.copy(isActive = true)
        uploadJob = null
        scope.launch {
            currentJob.cancelAndJoin()
            completion?.invoke(completed)
            _state.value = finalState
        }
    }

    fun dismiss() {
        if (_state.value.isActive) return
        generation.incrementAndGet()
        _state.value = UploadQueueState()
        orchestrator = null
        callbacks = null
    }
}
