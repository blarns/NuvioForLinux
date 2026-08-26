package com.nuvio.app.core.network

import com.nuvio.app.core.build.AppVersionConfig
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import com.nuvio.app.core.auth.provideSessionManager
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.realtime.Realtime
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders

object SupabaseProvider {
    // Shared by every request through this client, so a 429 on one call backs off the rest.
    private val rateLimitCoordinator = BackendRateLimitCoordinator()

    @OptIn(SupabaseInternal::class)
    val client by lazy {
        val userAgent = "NuvioMobile/${AppVersionConfig.VERSION_NAME.ifBlank { "dev" }}"
        createSupabaseClient(
            supabaseUrl = SupabaseConfig.URL,
            supabaseKey = SupabaseConfig.ANON_KEY,
        ) {
            httpConfig {
                // Honour the backend's own back-off instead of retrying straight into it. The
                // plugin parks outgoing requests until any cooldown a 429/503 asked for has
                // elapsed; the retry below only ever repeats requests that are safe to repeat.
                install(BackendRateLimitPlugin) {
                    coordinator = rateLimitCoordinator
                }
                install(HttpRequestRetry) {
                    retryIf(maxRetries = 1) { request, response ->
                        isSafeBackendRetryRequest(
                            method = request.method.value,
                            encodedPath = request.url.encodedPath,
                        ) && isRetryableBackendResponse(response.status.value)
                    }
                    delayMillis { retry ->
                        backendRetryDelayMillis(
                            retryCount = retry,
                            retryAfterHeader = response?.headers?.get(HttpHeaders.RetryAfter),
                        )
                    }
                }
                defaultRequest {
                    headers.append(HttpHeaders.UserAgent, userAgent)
                }
            }
            install(Auth) {
                provideSessionManager()?.let { sessionManager = it }
            }
            install(Postgrest)
            install(Functions)
            install(Storage)
            install(Realtime)
        }
    }
}
