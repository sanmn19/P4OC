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
internal const val COMPLETION_BODY_CODE_POINTS = 160
private const val NOTIFICATION_TEXT_ELLIPSIS = "…"

/** First line of [text], trimmed to a short notification body. */
internal fun String.shortExcerpt(): String {
    val line = lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: trim()
    return if (line.length <= COMPLETION_BODY_CODE_POINTS) {
        line
    } else {
        line.take(COMPLETION_BODY_CODE_POINTS - 1) + NOTIFICATION_TEXT_ELLIPSIS
    }
}

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
        const val CHAT_SUMMARY_NOTIFICATION_ID = 0x04000000
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

    /** Context for callers that need to inspect notification permission/channels. */
    fun context(): Context = context

    private data class ChatUpdate(val title: String, val text: String)

    /**
     * Everything that happened in one chat, newest last. One notification per chat means the shade
     * collapses all of a chat's updates into a single card instead of one card per event.
     */
    private val chatUpdates = mutableMapOf<String, MutableMap<String, ChatUpdate>>()

    fun showPermissionNotification(
        sessionId: String,
        serverRef: ServerRef,
        workspaceKey: WorkspaceKey,
        permission: Permission,
    ) {
        val route = NotificationRoute(sessionId, serverRef, workspaceKey)
        val title = boundedNotificationText(
            PermissionDisplayFormatter.title(context, permission),
            context.getString(R.string.notification_permission_required),
        )
        postChatNotification(
            route = route,
            identity = NotificationRouteCodec.identity(NotificationKind.Permission, route),
            notificationId = PERMISSION_NOTIFICATION_ID,
            update = ChatUpdate(context.getString(R.string.notification_permission_required), title),
            silent = false,
        )
    }

    fun showQuestionNotification(
        sessionId: String,
        serverRef: ServerRef,
        workspaceKey: WorkspaceKey,
        question: String?,
        sessionTitle: String? = null,
    ) {
        val route = NotificationRoute(sessionId, serverRef, workspaceKey)
        val questionText = boundedNotificationText(
            question,
            context.getString(R.string.notification_question_fallback),
        )
        val titleText = boundedNotificationText(
            sessionTitle,
            context.getString(R.string.notification_question_title),
        )
        postChatNotification(
            route = route,
            identity = NotificationRouteCodec.identity(NotificationKind.Question, route),
            notificationId = QUESTION_NOTIFICATION_ID,
            update = ChatUpdate(titleText, questionText),
            silent = false,
        )
    }

    fun showCompletionNotification(
        sessionId: String,
        serverRef: ServerRef,
        workspaceKey: WorkspaceKey,
        sessionTitle: String?,
        body: String? = null,
    ) {
        val route = NotificationRoute(sessionId, serverRef, workspaceKey)
        val titleText = boundedNotificationText(
            sessionTitle,
            context.getString(R.string.notification_completion_title),
        )
        val sessionText = boundedNotificationText(
            body?.shortExcerpt(),
            context.getString(R.string.notification_completion_fallback),
        )
        postChatNotification(
            route = route,
            identity = NotificationRouteCodec.identity(NotificationKind.Completion, route),
            notificationId = COMPLETION_NOTIFICATION_ID,
            update = ChatUpdate(titleText, sessionText),
            silent = true,
        )
    }

    /**
     * Posts (or updates) the one notification card that belongs to [route]'s chat. All events from a
     * chat share a tag, so a second event replaces the card and the inbox style lists what happened,
     * instead of stacking one card per event.
     */
    private fun postChatNotification(
        route: NotificationRoute,
        identity: String,
        notificationId: Int,
        update: ChatUpdate,
        silent: Boolean,
    ) {
        val chatKey = chatGroupKey(route)
        val updates = synchronized(chatUpdates) {
            val perChat = chatUpdates.getOrPut(chatKey) { linkedMapOf() }
            perChat[identity] = update
            perChat.values.toList()
        }
        val latest = updates.last()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(latest.title)
            .setContentText(latest.text)
            .setSubText(
                boundedNotificationText(
                    route.serverRef.displayName,
                    context.getString(R.string.notification_group_fallback),
                )
            )
            .apply {
                if (updates.size > 1) {
                    setStyle(
                        NotificationCompat.InboxStyle().also { style ->
                            updates.forEach { style.addLine("${it.title}: ${it.text}") }
                        }
                    )
                }
            }
            .setPriority(if (silent) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setSilent(silent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setGroup(chatKey)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setContentIntent(contentIntent(route, notificationId))
            .build()

        try {
            val manager = NotificationManagerCompat.from(context)
            manager.notify(identity, notificationId, notification)
            manager.notify("$chatKey:summary", CHAT_SUMMARY_NOTIFICATION_ID, chatSummary(route, chatKey, updates))
            NotificationDiagnostics.record("posted ${update.title}")
        } catch (e: SecurityException) {
            NotificationDiagnostics.record("post failed: ${e::class.simpleName}")
            AppLog.w(TAG, "Notification post failed (${e::class.simpleName})")
        }
    }

    /**
     * Group header for one chat. Without it Android auto-groups every notification the app posts
     * into a single app-wide section, which merges different chats into one card.
     */
    private fun chatSummary(
        route: NotificationRoute,
        chatKey: String,
        updates: List<ChatUpdate>,
    ): android.app.Notification {
        val style = NotificationCompat.InboxStyle()
        updates.forEach { style.addLine("${it.title}: ${it.text}") }
        return NotificationCompat.Builder(context, COMPLETION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                boundedNotificationText(
                    route.serverRef.displayName,
                    context.getString(R.string.notification_group_fallback),
                )
            )
            .setContentText(
                context.resources.getQuantityString(
                    R.plurals.notification_group_updates,
                    updates.size,
                    updates.size,
                )
            )
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setGroup(chatKey)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setContentIntent(contentIntent(route, CHAT_SUMMARY_NOTIFICATION_ID))
            .build()
    }

    private fun contentIntent(route: NotificationRoute, requestCode: Int): PendingIntent {
        val identity = NotificationRouteCodec.identity(NotificationKind.Completion, route)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse(identity)
            NotificationRouteCodec.write(this, route)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun clearNotifications() {
        synchronized(chatUpdates) {
            chatUpdates.clear()
        }
        NotificationManagerCompat.from(context).cancelAll()
    }
}

/** One card per chat: every update from a session replaces the same notification. */
internal fun chatGroupKey(route: NotificationRoute): String =
    "chat:" + NotificationRouteCodec.identity(NotificationKind.Completion, route)
