package com.nuvio.app.features.trakt

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.addons.httpPostJsonWithHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object TraktAuthRepository {
    private const val BASE_URL = "https://api.trakt.tv"
    private const val API_VERSION = "2"

    private val log = Logger.withTag("TraktAuth")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(TraktAuthUiState())
    val uiState: StateFlow<TraktAuthUiState> = _uiState.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private var hasLoaded = false
    private var authState = TraktAuthState()
    private var pollJob: Job? = null

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        pollJob?.cancel()
        pollJob = null
        loadFromDisk()
    }

    fun clearLocalState() {
        pollJob?.cancel()
        pollJob = null
        hasLoaded = false
        authState = TraktAuthState()
        publish()
    }

    fun snapshot(): TraktAuthUiState {
        ensureLoaded()
        return _uiState.value
    }

    fun hasRequiredCredentials(): Boolean =
        TraktConfig.CLIENT_ID.isNotBlank() && TraktConfig.CLIENT_SECRET.isNotBlank()

    fun onConnectRequested() {
        ensureLoaded()
        scope.launch {
            startDeviceAuth()
        }
    }

    fun onCancelDeviceFlow() {
        ensureLoaded()
        pollJob?.cancel()
        pollJob = null
        authState = authState.copy(
            deviceCode = null,
            userCode = null,
            verificationUrl = null,
            expiresAtMillis = null,
            pollIntervalSeconds = null,
        )
        persist()
        publish(statusMessage = null, errorMessage = null)
    }

    fun onDisconnectRequested() {
        ensureLoaded()
        scope.launch {
            disconnect()
        }
    }

    suspend fun authorizedHeaders(): Map<String, String>? {
        ensureLoaded()
        if (!authState.isAuthenticated) return null

        val hasValidToken = refreshTokenIfNeeded(force = false)
        if (!hasValidToken) return null

        val accessToken = authState.accessToken?.trim().orEmpty()
        if (accessToken.isBlank()) return null

        return mapOf(
            "trakt-api-version" to API_VERSION,
            "trakt-api-key" to TraktConfig.CLIENT_ID,
            "Authorization" to "Bearer $accessToken",
        )
    }

    suspend fun refreshUserSettings(): String? {
        ensureLoaded()
        val headers = authorizedHeaders() ?: return null
        val response = runCatching {
            httpGetTextWithHeaders(
                url = "$BASE_URL/users/settings",
                headers = headers,
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "Failed to fetch Trakt user settings: ${error.message}" }
        }.getOrNull() ?: return null

        val parsed = runCatching {
            json.decodeFromString<TraktUserSettingsResponse>(response)
        }.getOrNull() ?: return null

        authState = authState.copy(
            username = parsed.user?.username,
            userSlug = parsed.user?.ids?.slug,
        )
        persist()
        publish()
        return authState.username
    }

    private suspend fun startDeviceAuth() {
        if (!hasRequiredCredentials()) {
            publish(errorMessage = "Missing Trakt credentials")
            return
        }

        publish(isLoading = true, errorMessage = null, statusMessage = null)

        val body = json.encodeToString(
            TraktDeviceCodeRequest(clientId = TraktConfig.CLIENT_ID),
        )

        val response = runCatching {
            httpPostJsonWithHeaders(
                url = "$BASE_URL/oauth/device/code",
                body = body,
                headers = emptyMap(),
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "Failed to start Trakt device auth: ${error.message}" }
        }.getOrNull()

        if (response == null) {
            publish(isLoading = false, errorMessage = "Network error, please try again")
            return
        }

        val parsed = runCatching {
            json.decodeFromString<TraktDeviceCodeResponse>(response)
        }.getOrNull()

        if (parsed == null) {
            publish(isLoading = false, errorMessage = "Invalid response from Trakt")
            return
        }

        val now = TraktPlatformClock.nowEpochMs()
        authState = authState.copy(
            deviceCode = parsed.deviceCode,
            userCode = parsed.userCode,
            verificationUrl = parsed.verificationUrl,
            expiresAtMillis = now + parsed.expiresIn * 1_000L,
            pollIntervalSeconds = parsed.interval,
        )
        persist()
        publish(
            isLoading = false,
            statusMessage = "Enter your code on trakt.tv/activate",
            errorMessage = null,
        )
        startPollingIfNeeded(force = true)
    }

    private fun startPollingIfNeeded(force: Boolean) {
        if (!force && pollJob?.isActive == true) return
        val hasDeviceFlow = !authState.deviceCode.isNullOrBlank()
        if (!hasDeviceFlow) return

        pollJob?.cancel()
        pollJob = scope.launch {
            publish(isPolling = true, errorMessage = null)
            while (isActive) {
                when (val result = pollDeviceToken()) {
                    DeviceTokenPollResult.Pending -> {
                        val interval = (authState.pollIntervalSeconds ?: 5).coerceAtLeast(1)
                        delay(interval * 1_000L)
                    }
                    DeviceTokenPollResult.SlowDown -> {
                        val nextInterval = ((authState.pollIntervalSeconds ?: 5) + 5).coerceAtMost(60)
                        authState = authState.copy(pollIntervalSeconds = nextInterval)
                        persist()
                        publish()
                        delay(nextInterval * 1_000L)
                    }
                    DeviceTokenPollResult.Approved -> {
                        publish(
                            isPolling = false,
                            statusMessage = "Connected to Trakt",
                            errorMessage = null,
                        )
                        return@launch
                    }
                    DeviceTokenPollResult.AlreadyUsed -> {
                        clearDeviceFlowOnly()
                        publish(
                            isPolling = false,
                            errorMessage = "This device code was already used. Start again.",
                        )
                        return@launch
                    }
                    DeviceTokenPollResult.Expired -> {
                        clearDeviceFlowOnly()
                        publish(
                            isPolling = false,
                            errorMessage = "The Trakt code expired. Start again.",
                        )
                        return@launch
                    }
                    DeviceTokenPollResult.Denied -> {
                        clearDeviceFlowOnly()
                        publish(
                            isPolling = false,
                            errorMessage = "Authorization denied.",
                        )
                        return@launch
                    }
                    is DeviceTokenPollResult.Failed -> {
                        clearDeviceFlowOnly()
                        publish(
                            isPolling = false,
                            errorMessage = result.reason,
                        )
                        return@launch
                    }
                }
            }
        }
    }

    private suspend fun pollDeviceToken(): DeviceTokenPollResult {
        val deviceCode = authState.deviceCode?.takeIf { it.isNotBlank() }
            ?: return DeviceTokenPollResult.Failed("No active Trakt code")

        val body = json.encodeToString(
            TraktDeviceTokenRequest(
                code = deviceCode,
                clientId = TraktConfig.CLIENT_ID,
                clientSecret = TraktConfig.CLIENT_SECRET,
            ),
        )

        val response = runCatching {
            httpPostJsonWithHeaders(
                url = "$BASE_URL/oauth/device/token",
                body = body,
                headers = emptyMap(),
            )
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            val code = extractHttpCode(error)
            return when (code) {
                400 -> DeviceTokenPollResult.Pending
                409 -> DeviceTokenPollResult.AlreadyUsed
                410 -> DeviceTokenPollResult.Expired
                418 -> DeviceTokenPollResult.Denied
                429 -> DeviceTokenPollResult.SlowDown
                else -> DeviceTokenPollResult.Failed("Failed to finish Trakt auth")
            }
        }

        val tokenResponse = runCatching {
            json.decodeFromString<TraktTokenResponse>(response)
        }.getOrNull() ?: return DeviceTokenPollResult.Failed("Invalid Trakt token response")

        authState = authState.copy(
            accessToken = tokenResponse.accessToken,
            refreshToken = tokenResponse.refreshToken,
            tokenType = tokenResponse.tokenType,
            createdAt = tokenResponse.createdAt,
            expiresIn = tokenResponse.expiresIn,
            deviceCode = null,
            userCode = null,
            verificationUrl = null,
            expiresAtMillis = null,
            pollIntervalSeconds = null,
        )
        persist()
        refreshUserSettings()
        publish()
        return DeviceTokenPollResult.Approved
    }

    private suspend fun disconnect() {
        publish(isLoading = true, errorMessage = null)

        val token = authState.accessToken?.takeIf { it.isNotBlank() }
        if (!token.isNullOrBlank() && hasRequiredCredentials()) {
            val body = json.encodeToString(
                TraktRevokeRequest(
                    token = token,
                    clientId = TraktConfig.CLIENT_ID,
                    clientSecret = TraktConfig.CLIENT_SECRET,
                ),
            )
            runCatching {
                httpPostJsonWithHeaders(
                    url = "$BASE_URL/oauth/revoke",
                    body = body,
                    headers = emptyMap(),
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                log.w { "Failed to revoke Trakt token: ${error.message}" }
            }
        }

        authState = TraktAuthState()
        pollJob?.cancel()
        pollJob = null
        persist()
        publish(
            isLoading = false,
            isPolling = false,
            statusMessage = "Disconnected from Trakt",
            errorMessage = null,
        )
    }

    private suspend fun refreshTokenIfNeeded(force: Boolean): Boolean {
        if (!hasRequiredCredentials()) return false
        val refreshToken = authState.refreshToken?.takeIf { it.isNotBlank() } ?: return false

        if (!force && !isTokenExpiredOrExpiring(authState)) {
            return true
        }

        val body = json.encodeToString(
            TraktRefreshTokenRequest(
                refreshToken = refreshToken,
                clientId = TraktConfig.CLIENT_ID,
                clientSecret = TraktConfig.CLIENT_SECRET,
                redirectUri = "urn:ietf:wg:oauth:2.0:oob",
            ),
        )

        val response = runCatching {
            httpPostJsonWithHeaders(
                url = "$BASE_URL/oauth/token",
                body = body,
                headers = emptyMap(),
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "Trakt token refresh failed: ${error.message}" }
        }.getOrNull() ?: return false

        val parsed = runCatching {
            json.decodeFromString<TraktTokenResponse>(response)
        }.getOrNull() ?: return false

        authState = authState.copy(
            accessToken = parsed.accessToken,
            refreshToken = parsed.refreshToken,
            tokenType = parsed.tokenType,
            createdAt = parsed.createdAt,
            expiresIn = parsed.expiresIn,
        )
        persist()
        publish()
        return true
    }

    private fun loadFromDisk() {
        hasLoaded = true
        val payload = TraktAuthStorage.loadPayload().orEmpty().trim()
        authState = if (payload.isBlank()) {
            TraktAuthState()
        } else {
            runCatching { json.decodeFromString<TraktAuthState>(payload) }
                .getOrElse {
                    log.w { "Failed to parse Trakt auth payload: ${it.message}" }
                    TraktAuthState()
                }
        }
        publish(statusMessage = null, errorMessage = null)

        if (!authState.deviceCode.isNullOrBlank() && !authState.isAuthenticated) {
            startPollingIfNeeded(force = false)
        }
    }

    private fun clearDeviceFlowOnly() {
        authState = authState.copy(
            deviceCode = null,
            userCode = null,
            verificationUrl = null,
            expiresAtMillis = null,
            pollIntervalSeconds = null,
        )
        persist()
    }

    private fun publish(
        isLoading: Boolean = _uiState.value.isLoading,
        isPolling: Boolean = _uiState.value.isPolling,
        statusMessage: String? = _uiState.value.statusMessage,
        errorMessage: String? = _uiState.value.errorMessage,
    ) {
        val tokenExpiresAtMillis = authState.createdAt
            ?.let { createdAtSeconds ->
                authState.expiresIn?.let { expiresInSeconds ->
                    (createdAtSeconds + expiresInSeconds) * 1_000L
                }
            }

        val mode = when {
            authState.isAuthenticated -> TraktConnectionMode.CONNECTED
            !authState.deviceCode.isNullOrBlank() -> TraktConnectionMode.AWAITING_APPROVAL
            else -> TraktConnectionMode.DISCONNECTED
        }

        _isAuthenticated.value = authState.isAuthenticated
        _uiState.value = TraktAuthUiState(
            mode = mode,
            credentialsConfigured = hasRequiredCredentials(),
            isLoading = isLoading,
            isPolling = if (mode == TraktConnectionMode.CONNECTED) false else isPolling,
            username = authState.username,
            tokenExpiresAtMillis = tokenExpiresAtMillis,
            deviceUserCode = authState.userCode,
            verificationUrl = authState.verificationUrl,
            pollIntervalSeconds = authState.pollIntervalSeconds ?: 5,
            deviceCodeExpiresAtMillis = authState.expiresAtMillis,
            statusMessage = statusMessage,
            errorMessage = errorMessage,
        )
    }

    private fun persist() {
        TraktAuthStorage.savePayload(json.encodeToString(authState))
    }

    private fun isTokenExpiredOrExpiring(state: TraktAuthState): Boolean {
        val createdAt = state.createdAt ?: return true
        val expiresIn = state.expiresIn ?: return true
        val expiresAtSeconds = createdAt + expiresIn
        val nowSeconds = TraktPlatformClock.nowEpochMs() / 1_000L
        return nowSeconds >= (expiresAtSeconds - 60)
    }

    private fun extractHttpCode(error: Throwable): Int? {
        val message = error.message ?: return null
        val match = Regex("HTTP\\s+(\\d{3})").find(message) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()
    }
}

private sealed interface DeviceTokenPollResult {
    data object Pending : DeviceTokenPollResult
    data object AlreadyUsed : DeviceTokenPollResult
    data object Expired : DeviceTokenPollResult
    data object Denied : DeviceTokenPollResult
    data object SlowDown : DeviceTokenPollResult
    data object Approved : DeviceTokenPollResult
    data class Failed(val reason: String) : DeviceTokenPollResult
}

@Serializable
private data class TraktDeviceCodeRequest(
    @SerialName("client_id") val clientId: String,
)

@Serializable
private data class TraktDeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_url") val verificationUrl: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("interval") val interval: Int,
)

@Serializable
private data class TraktDeviceTokenRequest(
    @SerialName("code") val code: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String,
)

@Serializable
private data class TraktRefreshTokenRequest(
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String,
    @SerialName("redirect_uri") val redirectUri: String,
    @SerialName("grant_type") val grantType: String = "refresh_token",
)

@Serializable
private data class TraktRevokeRequest(
    @SerialName("token") val token: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String,
)

@Serializable
private data class TraktTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("created_at") val createdAt: Long,
)

@Serializable
private data class TraktUserSettingsResponse(
    val user: TraktUserDto? = null,
)

@Serializable
private data class TraktUserDto(
    val username: String? = null,
    val ids: TraktUserIdsDto? = null,
)

@Serializable
private data class TraktUserIdsDto(
    val slug: String? = null,
)
