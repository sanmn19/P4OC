package dev.blazelight.p4oc.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.blazelight.p4oc.MainActivity
import dev.blazelight.p4oc.R
import dev.blazelight.p4oc.core.log.AppLog
import dev.blazelight.p4oc.domain.model.Permission
import dev.blazelight.p4oc.domain.server.ServerRef
import dev.blazelight.p4oc.domain.server.WorkspaceKey
import dev.blazelight.p4oc.ui.permission.PermissionDisplayFormatter

private const val TAG = "NotificationHelper"

internal const val MAX_NOTIFICATION_TEXT_CODE_POINTS = 512
private const val NOTIFICATION_TEXT_ELLIPSIS = "…"

internal fun boundedNotificationText(text: String?, fallback: String): String {
    val resolved = text ?: fallback
    var index = 0
    var codePoints = 0
    var lastCodePointStart = 0

    while (index < resolved.length && codePoints < MAX_NOTIFICATION_TEXT_CODE_POINTS) {
        lastCodePointStart = index
        index += Character.charCount(Character.codePointAt(resolved, index))
        codePoints++
    }

    return if (index == resolved.length) {
        resolved
    } else {
        resolved.substring(0, lastCodePointStart) + NOTIFICATION_TEXT_ELLIPSIS
    }
}

class NotificationHelper constructor(
    private val context: Context
) {
    companion object {
        const val CHANNEL_ID = "user_input_required"
        const val COMPLETION_CHANNEL_ID = "assistant_completed"
        const val LIVE_CONNECTION_CHANNEL_ID = "live_connection"

        const val PERMISSION_NOTIFICATION_ID = 0x40000000
        const val QUESTION_NOTIFICATION_ID = 0x20000000
        const val COMPLETION_NOTIFICATION_ID = 0x10000000
        const val LIVE_CONNECTION_NOTIFICATION_ID = 0x7FFFFFFE

        /** Idempotent channel creation, shared by NotificationHelper instances and the FGS. */
        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager = context.getSystemService(NotificationManager::class.java)
                notificationManager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.notification_channel_user_input_name),
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = context.getString(R.string.notification_channel_user_input_desc)
                        enableVibration(true)
                    }
                )
                notificationManager.createNotificationChannel(
                    NotificationChannel(
                        COMPLETION_CHANNEL_ID,
                        context.getString(R.string.notification_channel_completion_name),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description = context.getString(R.string.notification_channel_completion_desc)
                        enableVibration(false)
                        setSound(null, null)
                    }
                )
                notificationManager.createNotificationChannel(
                    NotificationChannel(
                        LIVE_CONNECTION_CHANNEL_ID,
                        context.getString(R.string.live_connection_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = context.getString(R.string.live_connection_channel_desc)
                        enableVibration(false)
                        setSound(null, null)
                    }
                )
            }
        }

        /**
         * The persistent "live connection" notification body for the foreground service that
         * keeps the SSE stream flowing while the app is backgrounded. LOW importance: silent,
         * docks in the tray's ongoing section. No content intent: minimizing/starting the app is
         * the everyday gesture; an extra tap target would duplicate the launcher.
         */
        fun liveConnectionNotification(context: Context, serverLabel: String?): android.app.Notification =
            NotificationCompat.Builder(context, LIVE_CONNECTION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.live_connection_notification_title))
                .setContentText(
                    boundedNotificationText(
                        serverLabel,
                        context.getString(R.string.live_connection_notification_fallback),
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setShowWhen(false)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()

        fun dismissLiveConnection(context: Context) {
            NotificationManagerCompat.from(context).cancel("live_connection", LIVE_CONNECTION_NOTIFICATION_ID)
        }
    }

    init {
        ensureChannels(context)
    }

    fun showPermissionNotification(
        sessionId: String,
        serverRef: ServerRef,
        workspaceKey: WorkspaceKey,
        permission: Permission,
    ) {
        val route = NotificationRoute(sessionId, serverRef, workspaceKey)
        val identity = NotificationRouteCodec.identity(NotificationKind.Permission, route)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse(identity)
            NotificationRouteCodec.write(this, route)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            PERMISSION_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = boundedNotificationText(
            PermissionDisplayFormatter.title(context, permission),
            context.getString(R.string.notification_permission_required),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_permission_required))
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(identity, PERMISSION_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            AppLog.w(TAG, "Notification post failed (${e::class.simpleName})")
        }
    }

    fun showQuestionNotification(
        sessionId: String,
        serverRef: ServerRef,
        workspaceKey: WorkspaceKey,
        question: String?,
    ) {
        val route = NotificationRoute(sessionId, serverRef, workspaceKey)
        val identity = NotificationRouteCodec.identity(NotificationKind.Question, route)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse(identity)
            NotificationRouteCodec.write(this, route)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            QUESTION_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val questionText = boundedNotificationText(
            question,
            context.getString(R.string.notification_question_fallback),
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_question_title))
            .setContentText(questionText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(questionText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(identity, QUESTION_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            AppLog.w(TAG, "Notification post failed (${e::class.simpleName})")
        }
    }

    fun showCompletionNotification(
        sessionId: String,
        serverRef: ServerRef,
        workspaceKey: WorkspaceKey,
        sessionTitle: String?,
    ) {
        val route = NotificationRoute(sessionId, serverRef, workspaceKey)
        val identity = NotificationRouteCodec.identity(NotificationKind.Completion, route)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse(identity)
            NotificationRouteCodec.write(this, route)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            COMPLETION_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sessionText = boundedNotificationText(
            sessionTitle,
            context.getString(R.string.notification_completion_fallback),
        )
        val notification = NotificationCompat.Builder(context, COMPLETION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_completion_title))
            .setContentText(sessionText)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(identity, COMPLETION_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            AppLog.w(TAG, "Notification post failed (${e::class.simpleName})")
        }
    }

    fun clearNotifications() {
        NotificationManagerCompat.from(context).cancelAll()
    }
}
