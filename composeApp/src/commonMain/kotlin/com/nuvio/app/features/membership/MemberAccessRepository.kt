package com.nuvio.app.features.membership

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.features.watchprogress.WatchProgressClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val VerificationIntervalMs = 15L * 60L * 1_000L
private val RetryDelaysMs = listOf(1_000L, 2_000L, 4_000L)

object MemberAccessRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("MemberAccessRepository")
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshGeneration = MutableStateFlow(0L)
    private val _access = MutableStateFlow(MemberAccess.None)
    val access: StateFlow<MemberAccess> = _access.asStateFlow()

    /**
     * False until [access] reflects an actual answer — the cached payload, the server, or a
     * signed-out account — rather than the `MemberAccess.None` this flow is merely initialised
     * with. Callers that gate a *saved user choice* on an entitlement must wait for this, or
     * they resolve that choice against "no entitlements" during the first frames of a cold
     * start and visibly undo it. Upstream has no such signal; see
     * `ThemeSettingsRepository.applyEffectiveTheme` for the flash it causes there.
     */
    private val _accessResolved = MutableStateFlow(false)
    val accessResolved: StateFlow<Boolean> = _accessResolved.asStateFlow()

    private var started = false
    private var verifiedUserId: String? = null
    private var verifiedAtMs = 0L

    fun ensureStarted() {
        if (started) return
        started = true
        scope.launch {
            combine(AuthRepository.state, refreshGeneration) { auth, _ -> auth }
                .collectLatest(::loadAccess)
        }
    }

    fun refresh() {
        ensureStarted()
        refreshGeneration.value += 1L
    }

    fun refreshIfStale() {
        ensureStarted()
        val auth = AuthRepository.state.value as? AuthState.Authenticated ?: return
        if (auth.isAnonymous) return
        val now = WatchProgressClock.nowEpochMs()
        if (verifiedUserId != auth.userId || now - verifiedAtMs >= VerificationIntervalMs) refresh()
    }

    fun clearLocalState() {
        _access.value = MemberAccess.None
        _accessResolved.value = false
        verifiedUserId = null
        verifiedAtMs = 0L
        MemberAssetStorage.clearAccess()
        ProfileBackgroundRepository.invalidate()
    }

    private suspend fun loadAccess(auth: AuthState) {
        val account = auth as? AuthState.Authenticated
        if (account == null || account.isAnonymous) {
            // Still "resolved": a signed-out or anonymous account definitively has no perks, and
            // leaving the flag false would pin every gated choice in its unresolved state.
            _access.value = MemberAccess.None
            _accessResolved.value = auth !is AuthState.Loading
            return
        }
        val cached = loadCached(account.userId)
        _access.value = cached ?: MemberAccess.None
        _accessResolved.value = true
        val remote = fetchWithRetry() ?: return
        val effective = saveRemote(account.userId, remote)
        _access.value = effective
        verifiedUserId = account.userId
        verifiedAtMs = WatchProgressClock.nowEpochMs()
    }

    private suspend fun fetchWithRetry(): MemberAccess? {
        repeat(RetryDelaysMs.size + 1) { attempt ->
            try {
                return MemberAccessRemoteDataSource.getMemberAccess()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (attempt == RetryDelaysMs.size) {
                    log.w(error) { "Unable to verify supporter access; retaining cached access" }
                    return null
                }
                delay(RetryDelaysMs[attempt])
            }
        }
        return null
    }

    private fun loadCached(userId: String): MemberAccess? {
        val payload = MemberAssetStorage.loadAccessPayload() ?: return null
        val stored = runCatching { json.decodeFromString<StoredMemberAccess>(payload) }.getOrNull() ?: return null
        if (stored.userId != userId) return null
        val tier = stored.tier?.let { name -> MemberTier.entries.firstOrNull { it.name == name } }
            ?: return MemberAccess.None
        val entitlements = stored.entitlements
            .mapNotNull { name -> CosmeticEntitlement.entries.firstOrNull { it.name == name } }
            .toSet()
        return MemberAccess(
            tier = tier,
            entitlements = CosmeticEntitlements(entitlements),
        )
    }

    private fun saveRemote(userId: String, remote: MemberAccess): MemberAccess {
        MemberAssetStorage.saveAccessPayload(
            json.encodeToString(
                StoredMemberAccess(
                    userId = userId,
                    tier = remote.tier?.name,
                    entitlements = remote.entitlements.names(),
                ),
            ),
        )
        return remote
    }
}

@Serializable
private data class StoredMemberAccess(
    val userId: String,
    val tier: String? = null,
    val entitlements: Set<String> = emptySet(),
)
