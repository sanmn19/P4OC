package dev.blazelight.p4oc.core.notification

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.blazelight.p4oc.core.datastore.NotificationRoutingMode
import dev.blazelight.p4oc.core.datastore.NotificationSettings
import dev.blazelight.p4oc.core.datastore.SettingsDataStore
import dev.blazelight.p4oc.core.haptic.HapticFeedback
import dev.blazelight.p4oc.core.log.AppLog
import dev.blazelight.p4oc.core.network.ServerConnectionRegistry
import dev.blazelight.p4oc.domain.model.OpenCodeEvent
import dev.blazelight.p4oc.domain.model.SessionStatus
import dev.blazelight.p4oc.domain.server.ScopedEvent
import dev.blazelight.p4oc.domain.server.ServerRef
import dev.blazelight.p4oc.domain.server.WorkspaceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Best-effort background notifications for permission/question events.
 *
 * Notifications fire only while the app process is alive in the background.
 * There is no foreground service, so the OS (Doze, App Standby, OEM killers)
 * may kill the process within minutes of backgrounding. Long-lived background
 * notifications are NOT guaranteed.
 */
class NotificationEventObserver constructor(
    private val serverConnectionRegistry: ServerConnectionRegistry,
    private val notificationHelper: NotificationHelper,
    private val settingsDataStore: SettingsDataStore,
    private val hapticFeedback: HapticFeedback,
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "NotificationEventObserver"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @Volatile
    private var cachedSettings = NotificationSettings()

    private val completionTracker = CompletionTracker()

    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        observeEvents()
        observeSettings()
    }

    fun stop() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        scope.cancel()
    }

    override fun onStart(owner: LifecycleOwner) {
        // Coming back to the app means the user has seen the tray. Reset the busy
        // tracker and drop stale entries; notifications raised while the app stays open
        // are untouched (there is no lifecycle transition while it is visible).
        completionTracker.clear()
        notificationHelper.clearNotifications()
    }

    private fun observeSettings() {
        scope.launch {
            settingsDataStore.notificationSettings.collect { settings ->
                cachedSettings = settings
            }
        }
    }

    private fun observeEvents() {
        scope.launch {
            serverConnectionRegistry.scopedEvents.collect { event ->
                handleEvent(event)
            }
        }
    }

    private fun handleEvent(scopedEvent: ScopedEvent) {
        val event = scopedEvent.event
        recordDiagnostic(event)
        when (event) {
            is OpenCodeEvent.PermissionRequested -> handlePermission(scopedEvent, event)
            is OpenCodeEvent.QuestionAsked -> handleQuestion(scopedEvent, event)
            is OpenCodeEvent.SessionStatusChanged -> handleStatus(scopedEvent, event)
            is OpenCodeEvent.SessionIdle -> complete(scopedEvent.route(event.sessionID))
            is OpenCodeEvent.Connected -> catchUpMissedCompletions()
            is OpenCodeEvent.Disconnected -> completionTracker.clearServer(scopedEvent.serverRef)
            OpenCodeEvent.GlobalDisposed -> completionTracker.clearServer(scopedEvent.serverRef)
            is OpenCodeEvent.ServerInstanceDisposed -> completionTracker.clearWorkspace(
                scopedEvent.serverRef,
                scopedEvent.workspaceKey,
            )
            else -> {}
        }
    }

    private fun recordDiagnostic(event: OpenCodeEvent) {
        when (event) {
            is OpenCodeEvent.PermissionRequested,
            is OpenCodeEvent.QuestionAsked,
            is OpenCodeEvent.SessionStatusChanged,
            is OpenCodeEvent.SessionIdle,
            -> NotificationDiagnostics.record("event ${event::class.simpleName}")
            else -> Unit
        }
    }

    /** Per-server routing gate; absent entry defaults to [NotificationRoutingMode.All]. */
    private fun routingModeFor(serverRef: ServerRef): NotificationRoutingMode =
        cachedSettings.serverRouting[serverRef.endpointKey] ?: NotificationRoutingMode.All

    private fun handlePermission(scopedEvent: ScopedEvent, event: OpenCodeEvent.PermissionRequested) {
        if (!cachedSettings.enabled || !cachedSettings.permissionRequests) {
            NotificationDiagnostics.record("permission skipped: setting off")
            return
        }
        // Awaiting-input events are "mentions": All and Mentions deliver, Off suppresses.
        if (!shouldDeliverAwaitingInput(routingModeFor(scopedEvent.serverRef))) {
            NotificationDiagnostics.record("permission skipped: routing off")
            return
        }
        AppLog.d(TAG, "Permission requested")
        notificationHelper.showPermissionNotification(
            sessionId = event.permission.sessionID,
            serverRef = scopedEvent.serverRef,
            workspaceKey = scopedEvent.workspaceKey,
            permission = event.permission,
        )
    }

    private fun handleQuestion(scopedEvent: ScopedEvent, event: OpenCodeEvent.QuestionAsked) {
        if (!cachedSettings.enabled || !cachedSettings.questions) {
            NotificationDiagnostics.record("question skipped: setting off")
            return
        }
        if (!shouldDeliverAwaitingInput(routingModeFor(scopedEvent.serverRef))) {
            NotificationDiagnostics.record("question skipped: routing off")
            return
        }
        AppLog.d(TAG, "Question asked")
        notificationHelper.showQuestionNotification(
            sessionId = event.request.sessionID,
            serverRef = scopedEvent.serverRef,
            workspaceKey = scopedEvent.workspaceKey,
            question = event.request.questions.firstOrNull()?.question,
        )
    }

    private fun handleStatus(scopedEvent: ScopedEvent, event: OpenCodeEvent.SessionStatusChanged) {
        val route = scopedEvent.route(event.sessionID)
        if (event.status is SessionStatus.Busy || event.status is SessionStatus.Retry) {
            completionTracker.markBusy(route)
        } else {
            complete(route)
        }
    }

    private fun complete(route: NotificationRoute) {
        if (completionTracker.complete(route)) {
            showCompletionFeedback(route)
        } else {
            NotificationDiagnostics.record("completion skipped: no busy run tracked")
        }
    }

    private fun showCompletionFeedback(route: NotificationRoute) {
        // Turn-complete is not a "mention": only the All routing mode delivers it.
        if (!shouldDeliverCompletion(routingModeFor(route.serverRef))) {
            NotificationDiagnostics.record("completion skipped: routing mode")
            return
        }
        if (cachedSettings.notifyOnCompletion) {
            hapticFeedback.vibrate(cachedSettings.vibrationPattern)
            notificationHelper.showCompletionNotification(
                sessionId = route.sessionId,
                serverRef = route.serverRef,
                workspaceKey = route.workspaceKey,
                sessionTitle = null,
            )
        }
    }

    /**
     * A run can finish while the SSE stream is down (network drop, server restart). The live
     * event never arrives, so on reconnect ask the server for the status of every session that
     * was busy and post the completion for those that settled in the meantime.
     */
    private fun catchUpMissedCompletions() {
        val routes = completionTracker.busyRoutes()
        if (routes.isEmpty()) return
        scope.launch {
            routes.forEach { route ->
                val statuses = serverConnectionRegistry.sessionStatuses(route.serverRef, route.workspaceKey)
                    ?: return@forEach
                val status = statuses[route.sessionId]
                if (status == null || status.type == "idle") {
                    NotificationDiagnostics.record("catch-up completion for ${route.sessionId}")
                    if (completionTracker.complete(route)) showCompletionFeedback(route)
                }
            }
        }
    }

    private fun ScopedEvent.route(sessionId: String) = NotificationRoute(
        sessionId = sessionId,
        serverRef = serverRef,
        workspaceKey = workspaceKey,
    )
}

internal fun shouldEmitCompletionFeedback(settings: NotificationSettings): Boolean =
    settings.enabled && settings.notifyOnCompletion

/** Awaiting-input (permission/question) notifications: delivered unless the server is routed Off. */
internal fun shouldDeliverAwaitingInput(mode: NotificationRoutingMode): Boolean =
    mode != NotificationRoutingMode.Off

/** Turn-complete notifications: only the All routing mode delivers them (Mentions/Off suppress). */
internal fun shouldDeliverCompletion(mode: NotificationRoutingMode): Boolean =
    mode == NotificationRoutingMode.All

internal class CompletionTracker {
    private val busySessions = mutableSetOf<NotificationRoute>()

    fun markBusy(route: NotificationRoute) {
        busySessions.add(route)
    }

    fun complete(route: NotificationRoute): Boolean = busySessions.remove(route)

    fun busyRoutes(): List<NotificationRoute> = busySessions.toList()

    fun clear() {
        busySessions.clear()
    }

    fun clearServer(serverRef: ServerRef) {
        busySessions.removeAll { it.serverRef == serverRef }
    }

    fun clearWorkspace(
        serverRef: ServerRef,
        workspaceKey: WorkspaceKey,
    ) {
        busySessions.removeAll { it.serverRef == serverRef && it.workspaceKey == workspaceKey }
    }
}
