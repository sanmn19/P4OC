package dev.blazelight.p4oc.ui.screens.chat

import dev.blazelight.p4oc.domain.model.Message
import dev.blazelight.p4oc.domain.model.MessageWithParts
import dev.blazelight.p4oc.domain.model.TokenUsage
import dev.blazelight.p4oc.ui.components.chat.AssistantAttribution
import dev.blazelight.p4oc.ui.components.chat.assistantAttribution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPresentationBehaviorTest {

    @Test
    fun latestAssistantContextUsage_retainsPreviousNonZeroUsageDuringStreaming() {
        val completed = assistantMessage(
            id = "completed",
            providerID = "anthropic",
            modelID = "claude",
            tokens = TokenUsage(input = 80, output = 20),
        )
        val streaming = assistantMessage(
            id = "streaming",
            providerID = "openai",
            modelID = "gpt",
            tokens = TokenUsage(input = 0, output = 0),
        )

        assertEquals(
            AssistantContextUsage(tokens = 100L, providerID = "anthropic", modelID = "claude"),
            latestAssistantContextUsage(listOf(completed, streaming)),
        )
    }

    @Test
    fun latestAssistantContextUsage_usesNewestMeaningfulUsageAndItsModel() {
        val older = assistantMessage(
            id = "older",
            providerID = "anthropic",
            modelID = "claude",
            tokens = TokenUsage(input = 80, output = 20),
        )
        val current = assistantMessage(
            id = "current",
            providerID = "openai",
            modelID = "gpt",
            tokens = TokenUsage(input = 30, output = 5, reasoning = 7, cacheRead = 8),
        )

        assertEquals(
            AssistantContextUsage(tokens = 50L, providerID = "openai", modelID = "gpt"),
            latestAssistantContextUsage(listOf(older, current)),
        )
    }

    @Test
    fun latestAssistantContextUsage_retains64BitCumulativeTotal() {
        val completed = assistantMessage(
            id = "completed",
            providerID = "openai",
            modelID = "gpt",
            tokens = TokenUsage(
                input = 2_147_483_648L,
                output = 2_147_483_649L,
                reasoning = 2_147_483_650L,
                cacheRead = 2_371_688_120L,
                cacheWrite = 2_147_483_651L,
            ),
        )

        assertEquals(
            AssistantContextUsage(tokens = 10_961_622_718L, providerID = "openai", modelID = "gpt"),
            latestAssistantContextUsage(listOf(completed)),
        )
    }

    @Test
    fun latestAssistantContextUsage_omitsAllZeroUsage() {
        val streaming = assistantMessage(
            id = "streaming",
            providerID = "openai",
            modelID = "gpt",
            tokens = TokenUsage(input = 0, output = 0),
        )

        assertNull(latestAssistantContextUsage(listOf(streaming)))
    }

    @Test
    fun assistantAttribution_omitsBlankFieldsAndSeparatorInputs() {
        assertNull(assistantAttribution(" ", ""))
        val agentOnly = assistantAttribution(" build ", " ")
        val modelOnly = assistantAttribution("", " claude ")
        val complete = assistantAttribution("build", "claude")

        assertEquals(AssistantAttribution(agent = "build", modelID = null), agentOnly)
        assertEquals(AssistantAttribution(agent = null, modelID = "claude"), modelOnly)
        assertFalse(agentOnly!!.showSeparator)
        assertFalse(modelOnly!!.showSeparator)
        assertTrue(complete!!.showSeparator)
    }

    @Test
    fun chatLoading_loadingMessagesWithoutContent_isOpaque() {
        assertEquals(
            ChatLoadingOverlay.Opaque,
            chatLoadingOverlay(isMessageLoading = true, hasActiveLoadSteps = false, hasContent = false),
        )
        assertEquals(
            ChatLoadingOverlay.Opaque,
            chatLoadingOverlay(isMessageLoading = true, hasActiveLoadSteps = true, hasContent = false),
        )
    }

    @Test
    fun chatLoading_loadingMessagesWithContent_isTransparent() {
        assertEquals(
            ChatLoadingOverlay.Transparent,
            chatLoadingOverlay(isMessageLoading = true, hasActiveLoadSteps = false, hasContent = true),
        )
        assertEquals(
            ChatLoadingOverlay.Transparent,
            chatLoadingOverlay(isMessageLoading = true, hasActiveLoadSteps = true, hasContent = true),
        )
    }

    @Test
    fun chatLoading_emptyHistoryWithPendingQuestion_isTransparent() {
        val hasContent = hasChatContent(
            hasMessages = false,
            isBusy = false,
            hasPendingQuestion = true,
            hasSessionPendingPermissions = false,
        )

        assertEquals(
            ChatLoadingOverlay.Transparent,
            chatLoadingOverlay(isMessageLoading = true, hasActiveLoadSteps = false, hasContent = hasContent),
        )
    }

    @Test
    fun chatLoading_incidentalStep_isTransparent() {
        assertEquals(
            ChatLoadingOverlay.Transparent,
            chatLoadingOverlay(isMessageLoading = false, hasActiveLoadSteps = true, hasContent = false),
        )
        assertEquals(
            ChatLoadingOverlay.Transparent,
            chatLoadingOverlay(isMessageLoading = false, hasActiveLoadSteps = true, hasContent = true),
        )
    }

    @Test
    fun chatLoading_noActivity_isNone() {
        assertEquals(
            ChatLoadingOverlay.None,
            chatLoadingOverlay(isMessageLoading = false, hasActiveLoadSteps = false, hasContent = false),
        )
        assertEquals(
            ChatLoadingOverlay.None,
            chatLoadingOverlay(isMessageLoading = false, hasActiveLoadSteps = false, hasContent = true),
        )
    }

    private fun assistantMessage(
        id: String,
        providerID: String,
        modelID: String,
        tokens: TokenUsage,
    ): MessageWithParts = MessageWithParts(
        message = Message.Assistant(
            id = id,
            sessionID = "session",
            createdAt = 1L,
            parentID = "parent",
            providerID = providerID,
            modelID = modelID,
            mode = "build",
            agent = "build",
            cost = 0.0,
            tokens = tokens,
        ),
        parts = emptyList(),
    )
}
