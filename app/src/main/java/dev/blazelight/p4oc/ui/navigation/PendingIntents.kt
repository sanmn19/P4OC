package dev.blazelight.p4oc.ui.navigation

import dev.blazelight.p4oc.core.notification.NotificationRoute
import kotlinx.coroutines.flow.StateFlow

/**
 * Intents the activity received before the UI was ready: a notification tap's chat route, or the
 * persistent live-connection notification asking for the most recently active chat.
 */
data class PendingIntents(
    val notificationRoute: StateFlow<NotificationRoute?>,
    val onNotificationRouteConsumed: (NotificationRoute) -> Unit,
    val openLatestChat: StateFlow<Boolean>,
    val onOpenLatestChatConsumed: () -> Unit,
)
