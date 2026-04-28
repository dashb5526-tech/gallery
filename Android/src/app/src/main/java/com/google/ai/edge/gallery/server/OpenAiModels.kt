package com.google.ai.edge.gallery.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Requests ---

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<Message>,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean? = false,
    val stop: List<String>? = null,
    @SerialName("has_image") val hasImage: Boolean? = false
)

@Serializable
data class Message(
    val role: String, // system, user, assistant
    val content: String
)

// --- Responses ---

@Serializable
data class ChatCompletionResponse(
    val id: String,
    val `object`: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage? = null
)

@Serializable
data class Choice(
    val index: Int,
    val message: Message,
    @SerialName("finish_reason") val finishReason: String?
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int
)

// --- Streaming Responses (SSE) ---

@Serializable
data class ChatCompletionChunk(
    val id: String,
    val `object`: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<ChunkChoice>
)

@Serializable
data class ChunkChoice(
    val index: Int,
    val delta: Delta,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class Delta(
    val role: String? = null,
    val content: String? = null
)

// --- Models Endpoint ---

@Serializable
data class ModelListResponse(
    val `object`: String = "list",
    val data: List<OpenAiModel>
)

@Serializable
data class OpenAiModel(
    val id: String,
    val `object`: String = "model",
    val created: Long,
    @SerialName("owned_by") val ownedBy: String
)

// --- Error Responses ---

@Serializable
data class OpenAiErrorResponse(
    val error: OpenAiError
)

@Serializable
data class OpenAiError(
    val message: String,
    val type: String,
    val code: String? = null
)
