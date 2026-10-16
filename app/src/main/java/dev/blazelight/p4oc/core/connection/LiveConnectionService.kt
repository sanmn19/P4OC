package dev.blazelight.p4oc.core.connection

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.blazelight.p4oc.core.notification.NotificationHelper

/**
 * Foreground service whose only duty is to elevate the process so the existing in-process SSE
 * connection keeps running while the app is backgrounded (Doze/App Standby/OEM throttling
 * otherwise stall network work and the OS kills the process within minutes — especially on
 * Samsung). The connection stack itself lives in the application process; this service holds a
 * persistent, LOW-importance "live connection" notification for as long as the user has a
 * server connection and background liveness enabled.
 *
 * Uses the `dataSync` foreground service type. Android 14+ requires starting it while the app
 * is still visible; the coordinator starts it during the foreground→background transition (a
 * permitted window) and swallows FGSStartNotAllowedException for resilience.
 */
class LiveConnectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serverLabel = intent?.getStringExtra(EXTRA_SERVER_LABEL)
        NotificationHelper.ensureChannels(this)
        val notification = NotificationHelper.liveConnectionNotification(this, serverLabel)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NotificationHelper.LIVE_CONNECTION_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NotificationHelper.LIVE_CONNECTION_NOTIFICATION_ID, notification)
            }
        } catch (_: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (an IllegalStateException): observed when
            // START_STICKY resurrected this service in a fully-backgrounded process (null intent)
            // after the OS killed the app — no window exists to grant elevation, and the uncaught
            // throw crashed the app. A sessionless background resurrection serves no purpose
            // anyway: end quietly; the coordinator starts the service fresh on the next real
            // foreground->background transition.
            stopSelf()
            return START_NOT_STICKY
        }
        // Not sticky: the service's purpose is a live connection for an app session in use. If
        // the process dies the session is gone; a silent background restart would either crash
        // or idle-float with a stale tray entry. The coordinator owns the lifecycle instead.
        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_SERVER_LABEL = "live_connection.server_label"

        fun intent(context: Context, serverLabel: String?): Intent =
            Intent(context, LiveConnectionService::class.java).apply {
                putExtra(EXTRA_SERVER_LABEL, serverLabel)
            }
    }
}
