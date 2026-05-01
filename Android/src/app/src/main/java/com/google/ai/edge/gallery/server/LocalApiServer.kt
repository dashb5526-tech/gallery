package com.google.ai.edge.gallery.server

import android.util.Log
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.http.ContentType
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

class LocalApiServer(
    private val inferenceBridge: InferenceBridge,
    private val apiKey: String? = null
) {
    private var server: EmbeddedServer<*, *>? = null

    fun start(port: Int = 8080) {
        if (server != null) return

        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(CORS) {
                anyHost()
                allowHeader(io.ktor.http.HttpHeaders.ContentType)
                allowHeader(io.ktor.http.HttpHeaders.Authorization)
            }
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    prettyPrint = true
                    encodeDefaults = true
                    coerceInputValues = true
                })
            }

            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    Log.e("LocalApiServer", "Error processing request", cause)
                    val errorMsg = cause.message ?: "Unknown error"
                    val errorResponse = ErrorResponse(
                        OpenAiError(message = errorMsg, type = "invalid_request_error")
                    )
                    // Use a 400 for parsing errors, 500 for others
                    val status = if (cause is kotlinx.serialization.SerializationException) 
                        HttpStatusCode.BadRequest else HttpStatusCode.InternalServerError
                    call.respond(status, errorResponse)
                }
            }

            routing {
                // Manual Auth Interceptor for better control and error reporting
                intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
                    val call = this.call
                    if (!apiKey.isNullOrBlank()) {
                        val authHeader = call.request.headers[io.ktor.http.HttpHeaders.Authorization] ?: ""
                        
                        if (authHeader.isBlank()) {
                            Log.w("LocalApiServer", "Auth Failed: Missing Authorization header")
                            val errorResponse = ErrorResponse(OpenAiError("Missing authorization header", type = "auth_error"))
                            call.respond(io.ktor.http.HttpStatusCode.Unauthorized, errorResponse)
                            return@intercept finish()
                        }
                        
                        val trimmedHeader = authHeader.trim()
                        // Robust token extraction: handle both 'Bearer <token>' and raw '<token>'
                        val token = if (trimmedHeader.startsWith("Bearer ", ignoreCase = true)) {
                            val parts = trimmedHeader.split(Regex("\\s+"))
                            if (parts.size >= 2) parts[1].trim() else ""
                        } else {
                            trimmedHeader
                        }
                        
                        if (token != apiKey?.trim()) {
                            Log.w("LocalApiServer", "Auth Failed: Invalid token received. Length: ${token.length}")
                            val errorResponse = ErrorResponse(OpenAiError("Invalid API Key", type = "auth_error"))
                            call.respond(io.ktor.http.HttpStatusCode.Unauthorized, errorResponse)
                            return@intercept finish()
                        }
                        
                        Log.d("LocalApiServer", "Auth Success")
                    }
                }

                apiRoutes()
            }
        }
        server?.start(wait = false)
        Log.i("LocalApiServer", "Server started on port $port")
    }

    fun stop(gracePeriodMillis: Long = 1000, timeoutMillis: Long = 2000) {
        server?.stop(gracePeriodMillis, timeoutMillis)
        server = null
        Log.i("LocalApiServer", "Server stopped")
    }

    private fun Route.apiRoutes() {
        get("/v1/models") {
            val models = inferenceBridge.getAvailableModels()
            call.respond(ModelListResponse(data = models))
        }

        post("/v1/chat/completions") {
            handleChatRequest(call)
        }

        // Alias for easier integration
        post("/v1/chat") {
            handleChatRequest(call)
        }
    }

    private suspend fun handleChatRequest(context: io.ktor.server.application.ApplicationCall) {
        try {
            val request = context.receive<ChatCompletionRequest>()
            Log.i("LocalApiServer", "Request: model=${request.model}, stream=${request.stream}, msgCount=${request.messages.size}")

            if (request.stream == true) {
                context.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    try {
                        inferenceBridge.handleChatCompletion(request) { chunk ->
                            val json = kotlinx.serialization.json.Json.encodeToString(ChatCompletionChunk.serializer(), chunk)
                            write("data: $json\n\n")
                            flush()
                        }
                        write("data: [DONE]\n\n")
                        flush()
                    } catch (e: Exception) {
                        Log.e("LocalApiServer", "Streaming error", e)
                        write("data: {\"error\": {\"message\": \"Streaming failed: ${e.message}\", \"type\": \"server_error\"}}\n\n")
                        flush()
                    }
                }
            } else {
                val buffer = StringBuilder()
                var lastChunk: ChatCompletionChunk? = null
                inferenceBridge.handleChatCompletion(request) { chunk ->
                    chunk.choices.firstOrNull()?.delta?.content?.let { buffer.append(it) }
                    lastChunk = chunk
                }

                val finalResponse = ChatCompletionResponse(
                    id = lastChunk?.id ?: "null",
                    created = lastChunk?.created ?: (System.currentTimeMillis() / 1000),
                    model = lastChunk?.model ?: request.model ?: "default",
                    choices = listOf(
                        Choice(
                            index = 0,
                            message = Message(role = "assistant", content = kotlinx.serialization.json.JsonPrimitive(buffer.toString())),
                            finishReason = "stop"
                        )
                    )
                )
                context.respond(finalResponse)
            }
        } catch (e: Exception) {
            Log.e("LocalApiServer", "API Error", e)
            val status = if (e is IllegalArgumentException) HttpStatusCode.BadRequest else HttpStatusCode.InternalServerError
            val errorResponse = ErrorResponse(
                error = OpenAiError(
                    message = e.message ?: "Unknown internal server error",
                    type = "invalid_request_error"
                )
            )
            context.respond(status, errorResponse)
        }
    }
}
