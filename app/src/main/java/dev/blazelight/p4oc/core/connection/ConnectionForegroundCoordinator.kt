package dev.blazelight.p4oc.core.connection

import android.content.Context
import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.blazelight.p4oc.core.connection.LiveConnectionService.Companion.intent
import dev.blazelight.p4oc.core.datastore.SettingsDataStore
import dev.blazelight.p4oc.core.log.AppLog
import dev.blazelight.p4oc.core.network.ConnectionState
import dev.blazelight.p4oc.core.network.ServerConnectionRegistry
import dev.blazelight.p4oc.core.notification.NotificationHelper
import dev.blazelight.p4oc.domain.server.ServerRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps a connected server's SSE stream alive while the app is backgrounded by hosting a
 * [LiveConnectionService] foreground service during the background window, so chat
 * completion / awaiting-input notifications keep flowing (Doze/App Standby/OEM process
 * killers otherwise stop the process within minutes — Samsung in particular).
 *
 * Connected-server identification uses the registry's per-server connection states; the
 * service starts during the foreground→background transition (a permitted FGS-start window on
 * Android 14+) and stops as soon as the app returns to the foreground.
 */
class ConnectionForegroundCoordinator constructor(
    private val context: Context,
    private val registry: ServerConnectionRegistry,
    private val settingsDataStore: SettingsDataStore,
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "ConnectionForeground"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @Volatile
    private var keepLiveInBackground = true

    @Volatile
    private var savedServers: List<ServerRef> = emptyList()

    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        scope.launch {
            settingsDataStore.connectionSettings.collect { settings ->
                keepLiveInBackground = settings.keepLiveInBackground
            }
        }
        scope.launch {
            settingsDataStore.savedServers.collect { saved ->
                savedServers = saved.map { ServerRef.fromEndpointKey(it.endpointKey, it.displayName) }
            }
        }
    }

    fun stop() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        scope.cancel()
    }

    override fun onStart(owner: LifecycleOwner) {
        context.stopService(Intent(context, LiveConnectionService::class.java))
        NotificationHelper.dismissLiveConnection(context)
    }

    override fun onStop(owner: LifecycleOwner) {
        if (!keepLiveInBackground) return
        val connected = savedServers.firstOrNull { server ->
            registry.connectionState(server).value is ConnectionState.Connected
        } ?: return
        try {
            context.startForegroundService(intent(context, connected.displayName))
            AppLog.d(TAG, "Started live connection service for ${connected.displayName}")
        } catch (_: IllegalStateException) {
            // On Android 12+ this is ForegroundServiceStartNotAllowedException (a
            // RuntimeException); the start window can be closed on some OEM transitions and
            // the process continues best-effort exactly as it did before this coordinator.
            AppLog.w(TAG, "Foreground service start not allowed at background transition")
        }
    }
}
