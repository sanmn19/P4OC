package dev.blazelight.p4oc.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
sealed class Message {
    abstract val id: String
    abstract val sessionID: String
    abstract val createdAt: Long

    @Serializable
    data class User(
        override val id: String,
        override val sessionID: String,
        override val createdAt: Long,
        val agent: String,
        val model: ModelRef,
        val summary: MessageSummary? = null,
        val system: String? = null,
        val tools: Map<String, Boolean>? = null
    ) : Message()

    @Serializable
    data class Assistant(
        override val id: String,
        override val sessionID: String,
        override val createdAt: Long,
        val completedAt: Long? = null,
        val parentID: String,
        val providerID: String,
        val modelID: String,
        val mode: String,
        val agent: String,
        val cost: Double,
        val tokens: TokenUsage,
        val path: MessagePath? = null,
        val error: MessageError? = null,
        val finish: String? = null,
        val summary: Boolean? = null
    ) : Message()
}

@Serializable
data class ModelRef(
    val providerID: String,
    val modelID: String
)

@Serializable
data class TokenUsage(
    val input: Long,
    val output: Long,
    val reasoning: Long = 0L,
    val cacheRead: Long = 0L,
    val cacheWrite: Long = 0L
)

@Serializable
data class MessageError(
    val name: String,
    val message: String? = null,
    val statusCode: Int? = null,
    val isRetryable: Boolean = false,
    val providerID: String? = null,
    val responseHeaders: Map<String, String>? = null,
    val responseBody: String? = null
)

fun MessageError.isAborted(): Boolean =
    name == "MessageAbortedError" || message.equals("Aborted", ignoreCase = true)

@Serializable
data class ApiError(
    val message: String,
    val statusCode: Int? = null,
    val isRetryable: Boolean = false,
    val responseHeaders: Map<String, String>? = null,
    val responseBody: String? = null
)

@Immutable
data class MessageWithParts(
    val message: Message,
    val parts: List<Part>
)

@Serializable
data class MessageSummary(
    val title: String? = null,
    val body: String? = null,
    val diffs: List<FileDiff> = emptyList()
)

@Serializable
data class MessagePath(
    val cwd: String,
    val root: String
)
