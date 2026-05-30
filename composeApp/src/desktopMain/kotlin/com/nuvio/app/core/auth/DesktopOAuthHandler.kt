package com.nuvio.app.core.auth

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import java.net.ServerSocket

/**
 * Desktop OAuth localhost redirect handler using Ktor embedded server.
 * 
 * This enables OAuth flows on desktop by providing a localhost endpoint
 * for auth providers to redirect to after user authorization.
 * 
 * Usage:
 * 1. Create handler: `val handler = DesktopOAuthHandler()`
 * 2. Start server: `val redirectUri = handler.start()`  // "http://localhost:8080/callback"
 * 3. Use redirectUri in OAuth initialization
 * 4. Handler automatically captures callback and provides authorization code
 * 5. Stop server: `handler.stop()`
 */
object DesktopOAuthHandler {
    private val redirectUri = CompletableDeferred<String>()
    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    private var port: Int = 8080

    /**
     * Finds an available port on localhost.
     */
    private fun findAvailablePort(startPort: Int = 8080): Int {
        var currentPort = startPort
        while (currentPort < 9000) {
            try {
                ServerSocket(currentPort).use { socket ->
                    return currentPort
                }
            } catch (e: Exception) {
                currentPort++
            }
        }
        throw RuntimeException("No available ports found between $startPort and 9000")
    }

    /**
     * Starts the localhost OAuth redirect server.
     * Returns the redirect URI to use in OAuth provider configuration.
     */
    suspend fun start(): String {
        if (server != null) {
            return redirectUri.await()
        }

        port = findAvailablePort()
        val callbackDeferred = CompletableDeferred<Unit>()

        server = embeddedServer(
            Netty,
            port = port,
            host = "127.0.0.1",
            module = { setupOAuthRoutes(callbackDeferred) }
        ).start()

        val uri = "http://127.0.0.1:$port/callback"
        redirectUri.complete(uri)
        return uri
    }

    /**
     * Waits for OAuth callback and returns the authorization code and state.
     */
    suspend fun waitForCallback(): OAuthCallbackData {
        if (!redirectUri.isCompleted) {
            throw RuntimeException("OAuth handler not started. Call start() first.")
        }
        return oauthCallbackData.await()
    }

    /**
     * Stops the localhost server.
     */
    fun stop() {
        server?.stop(gracePeriodMillis = 100, timeoutMillis = 1000)
        server = null
    }

    /**
     * Gets the current redirect URI if server is running.
     */
    fun getRedirectUri(): String? {
        return if (redirectUri.isCompleted) {
            runCatching { redirectUri.getCompleted() }.getOrNull()
        } else {
            null
        }
    }

    private val oauthCallbackData = CompletableDeferred<OAuthCallbackData>()

    private fun Application.setupOAuthRoutes(callbackDeferred: CompletableDeferred<Unit>) {
        routing {
            get("/callback") {
                val code = call.request.queryParameters["code"]
                val state = call.request.queryParameters["state"]
                val error = call.request.queryParameters["error"]
                val errorDescription = call.request.queryParameters["error_description"]

                if (!code.isNullOrEmpty() && !state.isNullOrEmpty()) {
                    // Success
                    oauthCallbackData.complete(
                        OAuthCallbackData(
                            code = code,
                            state = state,
                            error = null
                        )
                    )
                    call.respondText(
                        """
                        <!DOCTYPE html>
                        <html>
                        <head><title>Authorization Successful</title></head>
                        <body style="font-family: sans-serif; text-align: center; margin-top: 50px;">
                        <h1>✓ Authorization Successful</h1>
                        <p>You can close this window and return to Nuvio.</p>
                        </body>
                        </html>
                        """.trimIndent(),
                        ContentType.Text.Html,
                        HttpStatusCode.OK
                    )
                } else {
                    // Error
                    val errorMsg = error ?: "Unknown error"
                    val errorDesc = errorDescription ?: "No additional information"
                    oauthCallbackData.complete(
                        OAuthCallbackData(
                            code = null,
                            state = state,
                            error = "$errorMsg: $errorDesc"
                        )
                    )
                    call.respondText(
                        """
                        <!DOCTYPE html>
                        <html>
                        <head><title>Authorization Failed</title></head>
                        <body style="font-family: sans-serif; text-align: center; margin-top: 50px;">
                        <h1>✗ Authorization Failed</h1>
                        <p><strong>Error:</strong> $errorMsg</p>
                        <p>$errorDesc</p>
                        <p>You can close this window and try again.</p>
                        </body>
                        </html>
                        """.trimIndent(),
                        ContentType.Text.Html,
                        HttpStatusCode.BadRequest
                    )
                }
                callbackDeferred.complete(Unit)
            }
        }
    }
}

data class OAuthCallbackData(
    val code: String?,
    val state: String?,
    val error: String? = null,
)
