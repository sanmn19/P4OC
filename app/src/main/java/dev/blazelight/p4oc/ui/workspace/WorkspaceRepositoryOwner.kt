package dev.blazelight.p4oc.ui.workspace

import dev.blazelight.p4oc.core.log.AppLog
import dev.blazelight.p4oc.data.files.FileRepository
import dev.blazelight.p4oc.data.files.FileRepositoryFactory
import dev.blazelight.p4oc.data.session.SessionRepositoryImpl
import dev.blazelight.p4oc.data.session.SessionRepositoryProvider
import dev.blazelight.p4oc.data.vcs.WorkspaceChangesRepository
import dev.blazelight.p4oc.data.vcs.WorkspaceChangesRepositoryImpl
import dev.blazelight.p4oc.data.workspace.WorkspaceClient
import dev.blazelight.p4oc.domain.server.ServerGeneration
import dev.blazelight.p4oc.domain.workspace.Workspace
import dev.blazelight.p4oc.ui.screens.files.upload.UploadCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class WorkspaceRepositoryOwner(
    val tabId: String,
    val workspace: Workspace,
    val generation: ServerGeneration,
    private val sessionRepositoryProvider: SessionRepositoryProvider,
) {
    private val repositoryLease = sessionRepositoryProvider.acquire(workspace, generation)
    val workspaceClient: WorkspaceClient = repositoryLease.workspaceClient
    val sessionRepository: SessionRepositoryImpl = repositoryLease.repository
    val fileRepository: FileRepository = FileRepositoryFactory.create(workspaceClient)
    val workspaceChangesRepository: WorkspaceChangesRepository = WorkspaceChangesRepositoryImpl(workspaceClient)
    private val uploadScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val uploadCoordinator = UploadCoordinator(
        scope = uploadScope,
        repositoryFactory = { fileRepository },
    )

    val identityHash: Int = System.identityHashCode(this)
    private var closed = false

    init {
        AppLog.i(TAG, "WorkspaceRepositoryOwner.init")
        // The initial snapshot refresh is best-effort duty: the repository surfaces failures
        // through its state (Stale). A network timeout or any hydrate rethrow must never kill
        // the whole app from a background launch.
        @Suppress("TooGenericExceptionCaught") // resilience guard: refresh must never kill the app
        uploadScope.launch {
            try {
                sessionRepository.refresh()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                AppLog.w(TAG, "Initial workspace refresh failed: ${e.javaClass.simpleName}")
            }
        }
    }

    fun touch(@Suppress("UNUSED_PARAMETER") destinationRoute: String?) {
        AppLog.d(TAG, "WorkspaceRepositoryOwner.touch")
    }

    fun close() {
        if (closed) return
        closed = true
        uploadCoordinator.cancel()
        uploadScope.cancel()
        sessionRepositoryProvider.release(workspace, generation)
        AppLog.i(TAG, "WorkspaceRepositoryOwner.close")
    }

    private companion object {
        const val TAG = "WorkspaceRepositoryOwner"
    }
}

/**
 * Launches [WorkspaceRepositoryOwner.sessionRepository.refresh][SessionRepositoryImpl.refresh] on
 * [scope] as best-effort duty. The repository surfaces refresh failures through its own state
 * (RepoState.Stale); a network timeout rethrown from hydrate must only be logged here, never
 * crash the app.
 */
private const val REFRESH_TAG = "WorkspaceRepositoryOwner"

@Suppress("TooGenericExceptionCaught") // resilience guard: refresh must never kill the app
fun WorkspaceRepositoryOwner.launchRefresh(scope: CoroutineScope): Job = scope.launch {
    try {
        sessionRepository.refresh()
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        AppLog.w(REFRESH_TAG, "Workspace refresh failed: ${e.javaClass.simpleName}")
    }
}
