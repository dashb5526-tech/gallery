package com.google.ai.edge.gallery.server

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.MessageCallback
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect

class InferenceBridge(private val context: Context) {

    companion object {
        private const val TAG = "InferenceBridge"
    }

    init {
        try {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
            Log.d(TAG, "PDFBox initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PDFBox", e)
        }
    }

    private var currentModelInstance: LlmModelInstance? = null

    suspend fun handleChatCompletion(
        request: ChatCompletionRequest,
        onChunk: suspend (ChatCompletionChunk) -> Unit
    ) {
        val requestedModel = request.model
        var model = ModelProvider.getModel(requestedModel ?: "")
        if (model == null) {
            val activeModelName = ModelProvider.serverActiveModelName
            if (!activeModelName.isNullOrBlank()) {
                model = ModelProvider.getModel(activeModelName)
            }
        }
        if (model == null) {
            model = ModelProvider.getActiveModels().firstOrNull()
        }

        if (model == null) {
            Log.e(TAG, "No active models found for request: $requestedModel")
            throw IllegalArgumentException("No initialized models available. Please load a model in the app first.")
        }

        val modelName = model.name
        val id = "chatcmpl-${System.currentTimeMillis()}"
        
        currentModelInstance = model.instance as? LlmModelInstance

        try {
            createConversationFlow(model, request).collect { chunkText ->
                val chunk = ChatCompletionChunk(
                    id = id,
                    created = System.currentTimeMillis() / 1000,
                    model = modelName,
                    choices = listOf(
                        ChunkChoice(
                            index = 0,
                            delta = Delta(content = chunkText)
                        )
                    )
                )
                onChunk(chunk)
            }
        } finally {
            stopCurrentSession()
        }
    }

    suspend fun getAvailableModels(): List<OpenAiModel> {
        return ModelProvider.getActiveModels().map { model ->
            OpenAiModel(
                id = model.name,
                created = System.currentTimeMillis() / 1000
            )
        }
    }

    private suspend fun processDocumentAttachments(attachments: List<Attachment>?): String {
        if (attachments == null) return ""
        val sb = StringBuilder()
        for (attachment in attachments) {
            try {
                if (attachment.type == "pdf") {
                    val data = Base64.decode(attachment.data, Base64.DEFAULT)
                    if (data.size > MAX_FILE_SIZE_BYTES) {
                        sb.append("\n[Error: Attachment '${attachment.name}' exceeds 3MB limit]\n")
                        continue
                    }
                    val text = extractTextFromPdf(data)
                    sb.append("\n[Document Content: ${attachment.name}]\n$text\n[End Document]\n")
                } else if (attachment.type == "text") {
                    val data = Base64.decode(attachment.data, Base64.DEFAULT)
                    val text = String(data)
                    sb.append("\n[File Content: ${attachment.name}]\n$text\n[End File]\n")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing attachment '${attachment.name}'", e)
            }
        }
        return sb.toString()
    }

    private fun extractTextFromPdf(data: ByteArray): String {
        return try {
            ByteArrayInputStream(data).use { inputStream ->
                PDDocument.load(inputStream).use { document ->
                    PDFTextStripper().getText(document)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "PDF extraction failed", e)
            ""
        }
    }

    private fun createConversationFlow(model: Model, request: ChatCompletionRequest) = callbackFlow<String> {
        val instance = model.instance as? LlmModelInstance
        if (instance == null) {
            close(Exception("Model instance not available"))
            return@callbackFlow
        }

        val conversation = instance.conversation
        
        val contents = mutableListOf<Content>()
        val textBuilder = StringBuilder()

        PendingImageStore.take()?.let { data ->
            Log.i(TAG, "Found image in PendingImageStore (${data.size} bytes)")
            contents.add(Content.ImageBytes(data))
        }

        request.messages.forEach { msg ->
            val role = msg.role
            val contentJson = msg.content
            
            if (contentJson is JsonPrimitive && contentJson.isString) {
                val text = contentJson.content
                when (role) {
                    "system" -> textBuilder.insert(0, "System Instruction: $text\n\n")
                    "user" -> textBuilder.append("User: $text\n")
                    "assistant" -> textBuilder.append("Assistant: $text\n")
                }
            } else if (contentJson is JsonArray) {
                contentJson.forEach { element ->
                    val obj = element.jsonObject
                    val type = obj["type"]?.jsonPrimitive?.content
                    if (type == "text") {
                        val text = obj["text"]?.jsonPrimitive?.content ?: ""
                        textBuilder.append("${role.replaceFirstChar { it.uppercase() }}: $text\n")
                    } else if (type == "image_url") {
                        val url = obj["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content ?: ""
                        if (url.startsWith("data:image")) {
                            try {
                                val base64Data = url.substringAfter("base64,")
                                val data = Base64.decode(base64Data, Base64.DEFAULT)
                                if (data.size <= MAX_FILE_SIZE_BYTES) {
                                    contents.add(Content.ImageBytes(ensurePng(data)))
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to decode image_url base64", e)
                            }
                        }
                    }
                }
            }
        }

        request.attachments?.filter { it.type == "image" }?.forEach { attachment ->
            try {
                val data = Base64.decode(attachment.data, Base64.DEFAULT)
                if (data.size <= MAX_FILE_SIZE_BYTES) {
                    contents.add(Content.ImageBytes(ensurePng(data)))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode image attachment", e)
            }
        }

        val docContext = processDocumentAttachments(request.attachments)
        val finalPrompt = if (docContext.isNotEmpty()) {
            "$docContext\n\nFull Conversation History:\n${textBuilder}\nContinue the conversation based on the history above."
        } else {
            textBuilder.toString()
        }

        if (finalPrompt.trim().isNotEmpty()) {
            contents.add(Content.Text(finalPrompt))
        }

        if (contents.isEmpty()) {
            close(Exception("No content to send"))
            return@callbackFlow
        }

        try {
            conversation.sendMessageAsync(
                Contents.of(contents),
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
                        if (throwable is java.util.concurrent.CancellationException) {
                            Log.i(TAG, "Native inference cancelled")
                        } else {
                            Log.e(TAG, "Inference error", throwable)
                            trySend("Error: ${throwable.message}")
                        }
                        close()
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start inference", e)
            close(e)
        }

        awaitClose {
            Log.d(TAG, "Closing conversation flow, cancelling native process if running")
            conversation.cancelProcess()
        }
    }

    fun stopCurrentSession() {
        Log.i(TAG, "Stopping current inference session")
        currentModelInstance?.conversation?.cancelProcess()
        currentModelInstance = null
    }

    private fun ensurePng(data: ByteArray): ByteArray {
        return try {
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
            val stream = ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        } catch (e: Exception) {
            Log.e("InferenceBridge", "Failed to ensure PNG format", e)
            data
        }
    }
}
