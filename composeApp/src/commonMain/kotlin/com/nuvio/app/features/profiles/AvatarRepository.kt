package com.nuvio.app.features.profiles

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseConfig
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.addons.httpPostJsonWithHeaders
import com.nuvio.app.features.membership.CosmeticEntitlement
import com.nuvio.app.features.membership.MemberAccessRepository
import com.nuvio.app.features.membership.MemberAssetStorage
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val MemberAvatarBucket = "membership-profile-avatars"

@Serializable
private data class StoredAvatarCatalogPayload(
    val items: List<AvatarCatalogItem> = emptyList(),
)

@Serializable
private data class MemberAvatarCatalogItem(
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("storage_path") val storagePath: String,
    val category: String,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("bg_color") val bgColor: String? = null,
    @SerialName("asset_version") val assetVersion: Int,
)

private val AvatarCatalogRefreshInterval = 15.minutes

internal fun availableAvatarCatalog(
    standardCatalog: List<AvatarCatalogItem>,
    memberCatalog: List<AvatarCatalogItem>,
    hasMemberAccess: Boolean,
): List<AvatarCatalogItem> = standardCatalog + if (hasMemberAccess) memberCatalog else emptyList()

object AvatarRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("AvatarRepository")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _avatars = MutableStateFlow<List<AvatarCatalogItem>>(emptyList())
    val avatars: StateFlow<List<AvatarCatalogItem>> = _avatars.asStateFlow()

    // The public catalog and the supporter-only one are kept apart so that only the former is
    // ever written to the on-disk cache, and so losing entitlement is a republish rather than
    // a refetch.
    private var standardCatalog = emptyList<AvatarCatalogItem>()
    private var memberCatalog = emptyList<AvatarCatalogItem>()

    private var loaded = false
    private var cacheHydrated = false
    private var fetchInFlight = false
    private var memberFetchInFlight = false
    private var accessObserverStarted = false
    private var hasMemberAccess = false
    private var lastRefresh: TimeMark? = null

    suspend fun fetchAvatars() {
        hydrateFromCacheIfNeeded()
        ensureMemberAccessObserver()
        if (loaded && standardCatalog.isNotEmpty()) {
            publishCatalog()
            return
        }
        doFetch()
    }

    /**
     * Refetches the avatar catalog, at most once per [AvatarCatalogRefreshInterval].
     *
     * Three screens call this on open — profile selection, profile edit and the switcher tab —
     * and moving between them is normal navigation, so each transition used to cost a catalog
     * request. The catalog is a slow-moving public list; a stale one for a few minutes is not a
     * defect. [force] is there for a deliberate user-initiated refresh.
     */
    suspend fun refreshAvatars(force: Boolean = false) {
        hydrateFromCacheIfNeeded()
        ensureMemberAccessObserver()
        if (force || isRefreshDue()) {
            doFetch()
            if (hasMemberAccess) fetchMemberCatalog()
        }
    }

    // Monotonic, so a wall-clock change cannot make the catalog look fresh for hours.
    private fun isRefreshDue(): Boolean =
        lastRefresh?.let { it.elapsedNow() >= AvatarCatalogRefreshInterval } ?: true

    private fun hydrateFromCacheIfNeeded() {
        if (cacheHydrated) return
        cacheHydrated = true

        val payload = AvatarStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) return

        val stored = runCatching {
            json.decodeFromString<StoredAvatarCatalogPayload>(payload)
        }.getOrNull() ?: return

        val items = stored.items.activeSorted()
        if (items.isEmpty()) return

        standardCatalog = items
        loaded = true
        publishCatalog()
    }

    private fun ensureMemberAccessObserver() {
        if (accessObserverStarted) return
        accessObserverStarted = true
        MemberAccessRepository.ensureStarted()
        scope.launch {
            MemberAccessRepository.access.collectLatest { access ->
                val nextAccess = access.entitlements.includes(CosmeticEntitlement.PROFILE_AVATARS)
                if (nextAccess == hasMemberAccess) return@collectLatest
                hasMemberAccess = nextAccess
                if (nextAccess) {
                    fetchMemberCatalog()
                } else {
                    publishCatalog()
                }
            }
        }
    }

    private suspend fun doFetch() {
        if (fetchInFlight) return
        fetchInFlight = true
        try {
            // Primary path rides the user's Supabase session. A stale/expired session makes
            // PostgREST return 401 ("JWT cryptographic operation failed"), which previously got
            // swallowed and left the catalog empty — blanking every profile avatar.
            val viaSession = runCatching { fetchCatalogViaSession() }
                .onFailure { e -> log.w(e) { "Avatar catalog fetch via session failed; falling back to anon" } }
                .getOrNull()

            // The avatar catalog is public, so it must not depend on login state. Fall back to a
            // session-independent anon read whenever the authenticated call fails or returns nothing.
            val activeItems = viaSession?.takeIf { it.isNotEmpty() }
                ?: runCatching { fetchCatalogViaAnon() }
                    .onFailure { e -> log.e(e) { "Avatar catalog anon fetch failed" } }
                    .getOrNull()

            if (!activeItems.isNullOrEmpty()) {
                standardCatalog = activeItems
                loaded = true
                publishCatalog()
                // Only a fetch that actually produced a catalog starts the window; a failed
                // one must stay retryable rather than being suppressed for 15 minutes.
                lastRefresh = TimeSource.Monotonic.markNow()
                AvatarStorage.savePayload(
                    json.encodeToString(StoredAvatarCatalogPayload(items = activeItems)),
                )
            }
        } finally {
            fetchInFlight = false
        }
    }

    private suspend fun fetchMemberCatalog() {
        if (memberFetchInFlight) return
        memberFetchInFlight = true
        try {
            val remote = SupabaseProvider.client.postgrest
                .rpc("get_member_profile_avatar_catalog")
                .decodeList<MemberAvatarCatalogItem>()
            memberCatalog = coroutineScope {
                remote.map { item -> async { loadMemberAvatar(item) } }.awaitAll().filterNotNull()
            }.sortedWith(compareBy({ it.category }, { it.sortOrder }))
            publishCatalog()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Unable to load supporter avatar catalog" }
        } finally {
            memberFetchInFlight = false
        }
    }

    private suspend fun loadMemberAvatar(item: MemberAvatarCatalogItem): AvatarCatalogItem? {
        return try {
            val extension = item.storagePath.substringAfterLast('.', "img")
                .takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
                ?: "img"
            val cacheKey = "${item.id}-v${item.assetVersion}.$extension"
            val localImageUrl = MemberAssetStorage.loadProfileAvatar(cacheKey)
                ?: SupabaseProvider.client.storage[MemberAvatarBucket]
                    .downloadAuthenticated(item.storagePath)
                    .let { bytes -> MemberAssetStorage.saveProfileAvatar(cacheKey, bytes) }
                ?: return null
            AvatarCatalogItem(
                id = item.id,
                displayName = item.displayName,
                storagePath = item.storagePath,
                category = item.category,
                sortOrder = item.sortOrder,
                bgColor = item.bgColor,
                localImageUrl = localImageUrl,
                memberOnly = true,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Unable to load supporter avatar ${item.id}" }
            null
        }
    }

    private fun publishCatalog() {
        _avatars.value = availableAvatarCatalog(
            standardCatalog = standardCatalog,
            memberCatalog = memberCatalog,
            hasMemberAccess = hasMemberAccess,
        )
    }

    private suspend fun fetchCatalogViaSession(): List<AvatarCatalogItem> =
        SupabaseProvider.client.postgrest.rpc("get_avatar_catalog")
            .decodeList<AvatarCatalogItem>()
            .activeSorted()

    private suspend fun fetchCatalogViaAnon(): List<AvatarCatalogItem> {
        val url = "${SupabaseConfig.URL.trimEnd('/')}/rest/v1/rpc/get_avatar_catalog"
        val body = httpPostJsonWithHeaders(
            url = url,
            body = "{}",
            headers = mapOf(
                "apikey" to SupabaseConfig.ANON_KEY,
                "Content-Type" to "application/json",
            ),
        )
        return json.decodeFromString<List<AvatarCatalogItem>>(body).activeSorted()
    }

    private fun List<AvatarCatalogItem>.activeSorted(): List<AvatarCatalogItem> =
        filter { it.isActive }.sortedWith(compareBy({ it.category }, { it.sortOrder }))
}
