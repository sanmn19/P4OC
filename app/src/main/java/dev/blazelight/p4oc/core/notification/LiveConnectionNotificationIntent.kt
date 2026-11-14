package dev.blazelight.p4oc.core.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.blazelight.p4oc.MainActivity

/**
 * Content intent of the persistent "live connection" notification: opens the chat that changed most
 * recently. The activity resolves which chat that is, because only the UI knows the open workspaces.
 */
object LiveConnectionNotificationIntent {
    const val ACTION_OPEN_LATEST_CHAT = "dev.blazelight.p4oc.action.OPEN_LATEST_CHAT"

    private const val REQUEST_CODE = 0x7FFFFFFD

    fun isOpenLatestChat(intent: Intent?): Boolean = intent?.action == ACTION_OPEN_LATEST_CHAT

    fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_LATEST_CHAT
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
