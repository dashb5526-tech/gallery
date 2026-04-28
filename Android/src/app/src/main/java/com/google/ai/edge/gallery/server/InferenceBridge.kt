package com.google.ai.edge.gallery.server

import android.util.Log
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.MessageCallback
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private const val TAG = "InferenceBridge"

class InferenceBridge {
    private val json = Json { encodeDefaults = true }
    private val inferenceMutex = Mutex()

    fun getAvailableModels(): List<OpenAiModel> {
        return ModelProvider.getActiveModels().map {
            OpenAiModel(
                id = it.name,
                created = System.currentTimeMillis() / 1000,
                ownedBy = "google-ai-edge"
            )
        }
    }

    suspend fun handleChatCompletion(call: ApplicationCall, request: ChatCompletionRequest) {
        val modelName = request.model
        val localModel = ModelProvider.getActiveModels().find { it.name == modelName }

        if (localModel == null) {
            call.respond(
                HttpStatusCode.NotFound,
                OpenAiErrorResponse(
                    OpenAiError(message = "Model '$modelName' not found or not initialized.", type = "invalid_request_error")
                )
            )
            return
        }

        if (localModel.instance == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                OpenAiErrorResponse(
                    OpenAiError(message = "Model '$modelName' is not currently loaded in memory. Please select it in the app first.", type = "invalid_request_error")
                )
            )
            return
        }

        if (!inferenceMutex.tryLock()) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                OpenAiErrorResponse(
                    OpenAiError(message = "The inference engine is currently busy.", type = "server_error")
                )
            )
            return
        }

        try {
            val isStreaming = request.stream ?: false

            if (isStreaming) {
                handleStreamingInference(call, request, localModel)
            } else {
                handleBlockingInference(call, request, localModel)
            }
        } finally {
            inferenceMutex.unlock()
        }
    }

    /**
     * Simple inference flow: send ONLY the last user message to the model's existing conversation.
     *
     * The LiteRT Conversation object maintains its own internal history — exactly like the
     * default AI Chat screen. We do NOT need to replay prior messages; the engine remembers
     * the conversation context between calls.
     *
     * This matches how the chat UI works (see LlmChatModelHelper.runInference):
     *   conversation.sendMessageAsync(contents, callback, emptyMap())
     */
    private fun createConversationFlow(model: Model, request: ChatCompletionRequest) = callbackFlow<String> {
        val instance = model.instance as? LlmModelInstance
        if (instance == null) {
            close(Exception("Model instance not available"))
            return@callbackFlow
        }

        val conversation = instance.conversation

        // Extract only the LAST user message — the conversation already has prior context
        val lastUserMessage = request.messages.lastOrNull { it.role == "user" }
        if (lastUserMessage == null) {
            close(Exception("No user message provided"))
            return@callbackFlow
        }

        // Build content list: image first (if any), then text — same as LlmChatModelHelper
        val contents = mutableListOf<Content>()

        // Check for in-memory image from PendingImageStore (set by test chat UI)
        if (request.hasImage == true) {
            val bitmap = PendingImageStore.take()
            if (bitmap != null) {
                val stream = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, stream)
                contents.add(Content.ImageBytes(stream.toByteArray()))
                Log.d(TAG, "Using in-memory image: ${stream.size()} bytes")
            } else {
                Log.w(TAG, "has_image=true but no image found in PendingImageStore")
            }
        }

        // Add text content after image (same order as Ask Image feature)
        val textContent = lastUserMessage.content.trim()
        if (textContent.isNotEmpty()) {
            contents.add(Content.Text(textContent))
        }

        if (contents.isEmpty()) {
            close(Exception("No content to send"))
            return@callbackFlow
        }

        val contentsObj = Contents.of(contents)

        Log.d(TAG, "Sending message to conversation: '${textContent.take(50)}...' (hasImage=${request.hasImage})")

        conversation.sendMessageAsync(
            contentsObj,
            object : MessageCallback {
                override fun onMessage(message: com.google.ai.edge.litertlm.Message) {
                    val text = message.toString()
                    if (text.isNotEmpty()) {
                        trySend(text)
                    }
                }
                override fun onDone() {
                    close()
                }
                override fun onError(throwable: Throwable) {
                    if (throwable is CancellationException) {
                        close()
                    } else {
                        Log.e(TAG, "Inference error", throwable)
                        close(Exception(throwable.message))
                    }
                }
            },
            emptyMap()
        )

        awaitClose {
            // Don't close or cancel the conversation — it's the model's persistent conversation.
            // Just let the flow complete naturally.
            Log.d(TAG, "Flow closed")
        }
    }

    private suspend fun handleBlockingInference(call: ApplicationCall, request: ChatCompletionRequest, model: Model) {
        val responseBuilder = StringBuilder()
        try {
            val flow = createConversationFlow(model, request)
            flow.collect { chunk ->
                responseBuilder.append(chunk)
            }

            val response = ChatCompletionResponse(
                id = "chatcmpl-${UUID.randomUUID()}",
                created = System.currentTimeMillis() / 1000,
                model = request.model,
                choices = listOf(
                    Choice(
                        index = 0,
                        message = Message(role = "assistant", content = responseBuilder.toString()),
                        finishReason = "stop"
                    )
                )
            )
            call.respond(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Blocking inference error", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                OpenAiErrorResponse(
                    OpenAiError(message = "Inference error: ${e.message}", type = "server_error")
                )
            )
        }
    }

    private suspend fun handleStreamingInference(call: ApplicationCall, request: ChatCompletionRequest, model: Model) {
        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            try {
                val flow = createConversationFlow(model, request)
                flow.collect { chunk ->
                    val sseChunk = ChatCompletionChunk(
                        id = "chatcmpl-${UUID.randomUUID()}",
                        created = System.currentTimeMillis() / 1000,
                        model = request.model,
                        choices = listOf(
                            ChunkChoice(
                                index = 0,
                                delta = Delta(content = chunk)
                            )
                        )
                    )
                    write("data: ${json.encodeToString(sseChunk)}\n\n")
                    flush()
                }
                write("data: [DONE]\n\n")
                flush()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Streaming inference error", e)
            }
        }
    }
}
