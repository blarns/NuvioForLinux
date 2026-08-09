package com.nuvio.app.features.profiles

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseConfig
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.addons.httpPostJsonWithHeaders
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class StoredAvatarCatalogPayload(
    val items: List<AvatarCatalogItem> = emptyList(),
)

object AvatarRepository {
    private val log = Logger.withTag("AvatarRepository")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _avatars = MutableStateFlow<List<AvatarCatalogItem>>(emptyList())
    val avatars: StateFlow<List<AvatarCatalogItem>> = _avatars.asStateFlow()

    private var loaded = false
    private var cacheHydrated = false
    private var fetchInFlight = false

    suspend fun fetchAvatars() {
        hydrateFromCacheIfNeeded()
        if (loaded && _avatars.value.isNotEmpty()) return
        doFetch()
    }

    suspend fun refreshAvatars() {
        hydrateFromCacheIfNeeded()
        doFetch()
    }

    private fun hydrateFromCacheIfNeeded() {
        if (cacheHydrated) return
        cacheHydrated = true

        val payload = AvatarStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) return

        val stored = runCatching {
            json.decodeFromString<StoredAvatarCatalogPayload>(payload)
        }.getOrNull() ?: return

        val items = stored.items
            .filter { it.isActive }
            .sortedWith(compareBy({ it.category }, { it.sortOrder }))
        if (items.isEmpty()) return

        _avatars.value = items
        loaded = true
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
                _avatars.value = activeItems
                loaded = true
                AvatarStorage.savePayload(
                    json.encodeToString(StoredAvatarCatalogPayload(items = activeItems)),
                )
            }
        } finally {
            fetchInFlight = false
        }
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
