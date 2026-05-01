package com.google.ai.edge.gallery.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

const val MAX_FILE_SIZE_BYTES = 3 * 1024 * 1024 // 3MB limit

// --- Requests ---

@Serializable
data class ChatCompletionRequest(
    val model: String? = "default",
    val messages: List<Message> = emptyList(),
    val temperature: Float? = 1.0f,
    @SerialName("top_p") val topP: Float? = 1.0f,
    val n: Int? = 1,
    val stream: Boolean? = false,
    val stop: List<String>? = null,
    @SerialName("max_tokens") val maxTokens: Int? = 10000,
    @SerialName("has_image") val hasImage: Boolean? = false,
    val attachments: List<Attachment>? = null
)

@Serializable
data class Attachment(
    val name: String,
    val type: String, // "pdf", "text", "image"
    val data: String // base64
)

@Serializable
data class Message(
    val role: String = "user", // system, user, assistant
    val content: JsonElement, // Can be String or List of Content objects
    val name: String? = null
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
    @SerialName("owned_by") val ownedBy: String = "local"
)

@Serializable
data class OpenAiError(
    val message: String,
    val type: String? = "invalid_request_error",
    val param: String? = null,
    val code: String? = null
)

@Serializable
data class ErrorResponse(
    val error: OpenAiError
)
