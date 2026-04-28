package com.google.ai.edge.gallery.server

import android.util.Log
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
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
import io.ktor.server.request.receive
import io.ktor.server.response.respond
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
                })
            }

            if (!apiKey.isNullOrBlank()) {
                install(Authentication) {
                    bearer("auth-bearer") {
                        realm = "Access to the local LLM server"
                        authenticate { tokenCredential ->
                            if (tokenCredential.token == apiKey) {
                                UserIdPrincipal("user")
                            } else {
                                null
                            }
                        }
                    }
                }
            }

            routing {
                if (!apiKey.isNullOrBlank()) {
                    authenticate("auth-bearer") {
                        apiRoutes()
                    }
                } else {
                    apiRoutes()
                }
            }
        }
        server?.start(wait = false)
        Log.i("LocalApiServer", "Server started on port $port")
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
        Log.i("LocalApiServer", "Server stopped")
    }

    private fun Route.apiRoutes() {
        get("/v1/models") {
            val models = inferenceBridge.getAvailableModels()
            call.respond(ModelListResponse(data = models))
        }

        post("/v1/chat/completions") {
            try {
                val request = call.receive<ChatCompletionRequest>()
                inferenceBridge.handleChatCompletion(call, request)
            } catch (e: Exception) {
                Log.e("LocalApiServer", "Error parsing request", e)
                call.respond(
                    HttpStatusCode.BadRequest,
                    OpenAiErrorResponse(
                        OpenAiError(message = "Invalid request format: ${e.message}", type = "invalid_request_error")
                    )
                )
            }
        }
    }
}
