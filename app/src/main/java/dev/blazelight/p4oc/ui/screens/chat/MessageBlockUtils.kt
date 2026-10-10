package dev.blazelight.p4oc.ui.screens.chat

import androidx.compose.runtime.Composable
import dev.blazelight.p4oc.domain.model.Message
import dev.blazelight.p4oc.domain.model.MessageWithParts
import dev.blazelight.p4oc.domain.model.Part
import dev.blazelight.p4oc.domain.model.Permission
import dev.blazelight.p4oc.domain.model.ToolState
import dev.blazelight.p4oc.ui.components.chat.AssistantMessages
import dev.blazelight.p4oc.ui.components.chat.ChatMessage
import dev.blazelight.p4oc.ui.components.toolwidgets.ToolWidgetState

/**
 * Sealed class representing a block of messages for display.
 * User messages are their own block. Consecutive assistant messages are merged.
 */
internal sealed class MessageBlock {
    data class UserBlock(
        val message: MessageWithParts,
        val revertMessageId: String? = null,
        val isQueued: Boolean = false,
    ) : MessageBlock()
    data class AssistantBlock(val messages: List<MessageWithParts>) : MessageBlock()
}

/**
 * Group messages into blocks: user messages standalone, consecutive assistant messages merged.
 */
internal fun groupMessagesIntoBlocks(messages: List<MessageWithParts>, isBusy: Boolean = false): List<MessageBlock> {
    if (messages.isEmpty()) return emptyList()

    val queuedUserMessageIds = queuedUserMessageIds(messages, isBusy)
    val revertTargetsByUserId = revertTargetsByUserId(messages)
    val blocks = mutableListOf<MessageBlock>()
    var i = 0

    while (i < messages.size) {
        val current = messages[i]

        if (current.message is Message.User) {
            blocks.add(
                MessageBlock.UserBlock(
                    message = current,
                    revertMessageId = revertTargetsByUserId[current.message.id],
                    isQueued = current.message.id in queuedUserMessageIds,
                )
            )
            i++
        } else {
            // Collect consecutive assistant messages
            val assistantMessages = mutableListOf<MessageWithParts>()
            while (i < messages.size && messages[i].message is Message.Assistant) {
                assistantMessages.add(messages[i])
                i++
            }
            blocks.add(MessageBlock.AssistantBlock(assistantMessages))
        }
    }

    return blocks
}

private fun queuedUserMessageIds(messages: List<MessageWithParts>, isBusy: Boolean): Set<String> {
    if (!isBusy) return emptySet()

    val assistantParentIds = messages
        .mapNotNull { (it.message as? Message.Assistant)?.parentID }
        .toSet()
    var hasActiveAssistantBefore = false
    val queuedIds = mutableSetOf<String>()

    messages.forEach { messageWithParts ->
        when (val message = messageWithParts.message) {
            is Message.Assistant -> {
                if (message.completedAt == null) hasActiveAssistantBefore = true
            }
            is Message.User -> {
                val hasAssistantChild = message.id in assistantParentIds
                if (hasActiveAssistantBefore && !hasAssistantChild) queuedIds += message.id
            }
        }
    }

    return queuedIds
}

private fun revertTargetsByUserId(messages: List<MessageWithParts>): Map<String, String> = buildMap {
    messages.forEach { messageWithParts ->
        val message = messageWithParts.message as? Message.Assistant ?: return@forEach
        val hasCompletedTools = messageWithParts.parts.any { it is Part.Tool && it.state is ToolState.Completed }
        if (hasCompletedTools && !containsKey(message.parentID)) put(message.parentID, message.id)
    }
}

@Composable
@Suppress("LongParameterList", "FunctionNaming")
internal fun MessageBlockView(
    block: MessageBlock,
    onToolApprove: (String) -> Unit,
    onToolDeny: (String) -> Unit,
    onToolAlways: (String) -> Unit,
    onOpenSubSession: ((String) -> Unit)? = null,
    onProviderAuthRequired: ((String) -> Unit)? = null,
    defaultToolWidgetState: ToolWidgetState = ToolWidgetState.COMPACT,
    pendingPermissionsByCallId: Map<String, Permission> = emptyMap(),
    onRevert: ((String) -> Unit)? = null,
    onForceSendQueued: ((String) -> Unit)? = null,
) {
    when (block) {
        is MessageBlock.UserBlock -> {
            ChatMessage(
                messageWithParts = block.message,
                onToolApprove = onToolApprove,
                onToolDeny = onToolDeny,
                onToolAlways = onToolAlways,
                onOpenSubSession = onOpenSubSession,
                onProviderAuthRequired = onProviderAuthRequired,
                defaultToolWidgetState = defaultToolWidgetState,
                pendingPermissionsByCallId = pendingPermissionsByCallId,
                onRevert = block.revertMessageId?.let { messageId ->
                    onRevert?.let { revert -> { revert(messageId) } }
                },
                isQueued = block.isQueued,
                onForceSend = onForceSendQueued
                    ?.takeIf { block.isQueued }
                    ?.let { callback -> { callback(block.message.message.id) } },
            )
        }
        is MessageBlock.AssistantBlock -> {
            AssistantMessages(
                messagesWithParts = block.messages,
                onToolApprove = onToolApprove,
                onToolDeny = onToolDeny,
                onToolAlways = onToolAlways,
                onOpenSubSession = onOpenSubSession,
                onProviderAuthRequired = onProviderAuthRequired,
                defaultToolWidgetState = defaultToolWidgetState,
                pendingPermissionsByCallId = pendingPermissionsByCallId,
            )
        }
    }
}
