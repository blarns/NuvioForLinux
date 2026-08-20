package com.nuvio.app.desktop

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.prefs.Preferences

/**
 * One-time migration of the fork's legacy java.util.prefs data (plus DesktopPrefs'
 * file-overflow spillover) into the official NuvioDesktop storage layout —
 * one .properties file per store under ~/.config/nuvio — so the eventual official
 * Linux client boots straight onto this data.
 *
 * Must run before anything reads a DesktopStorage store (first statement of main()).
 * Legacy data is left in place as a backup; the done-flag prevents re-runs. The only
 * path that re-arms the migration is DesktopStorage.wipe() (account clear), which is
 * why PlatformLocalAccountDataCleaner also clears the legacy prefs — a cleared
 * account must not resurrect from them.
 */
internal object DesktopLegacyPrefsMigration {
    private const val DONE_FLAG = "legacy_prefs_migrated"
    private const val LEGACY_ROOT = "com/nuvio/app"

    // Legacy DesktopPrefs node → store name. Key names are identical on both sides
    // (both mirror Android's snake_case keys, including ProfileScopedKey suffixes)
    // and copy verbatim — except the set-typed keys converted below.
    private val nodeToStore = mapOf(
        "addons" to "nuvio_addons",
        "auth" to "nuvio_session",
        "avatarStorage" to "nuvio_avatars",
        "bingeGroupCache" to "nuvio_binge_group_cache",
        "collectionMobileSettings" to "nuvio_collection_mobile_settings",
        "collectionStorage" to "nuvio_collections",
        "cwEnrichment" to "nuvio_continue_watching_enrichment",
        "cwPrefs" to "nuvio_continue_watching_preferences",
        "debridSettings" to "nuvio_debrid_settings",
        "downloadsStorage" to "nuvio_downloads",
        "episodeNotifications" to "nuvio_episode_release_notifications",
        "homeCatalogSettings" to "nuvio_home_catalog_settings",
        "libraryStorage" to "nuvio_library",
        "mdblistSettings" to "nuvio_mdblist_settings",
        "metaScreenSettings" to "nuvio_meta_screen_settings",
        "p2p" to "torrent_settings",
        "playerSettings" to "nuvio_player_settings",
        "playerTrackPrefs" to "nuvio_player_track_preferences",
        "posterCardStyle" to "nuvio_poster_card_style",
        "profilePinCache" to "nuvio_profile_pin_cache",
        "profileStorage" to "nuvio_profiles",
        "resumePrompt" to "nuvio_resume_prompt",
        "searchHistory" to "nuvio_search_history",
        "streamBadges" to "nuvio_stream_badge_settings",
        "streamLinkCache" to "nuvio_stream_link_cache",
        "themeSettings" to "nuvio_theme_settings",
        "tmdbSettings" to "nuvio_tmdb_settings",
        "traktAuth" to "nuvio_trakt_auth",
        "traktComments" to "nuvio_trakt_comments",
        "traktLibrary" to "nuvio_trakt_library",
        "traktSettings" to "nuvio_trakt_settings",
        "updater" to "nuvio_updater",
        "watchProgress" to "nuvio_watch_progress",
        "watchedStorage" to "nuvio_watched",
        "window" to "nuvio_window",
    )

    // DesktopPrefs encoded string sets as U+001F-joined values; DesktopStorage uses
    // JSON arrays. These (ProfileScopedKey-suffixed) playerSettings keys were the
    // fork's only set-typed entries.
    private val setTypedKeyPrefixes = listOf(
        "stream_auto_play_selected_addons",
        "stream_auto_play_selected_plugins",
    )

    private val json = Json

    fun runIfNeeded() {
        val machineStore = DesktopStorage.store("nuvio_machine")
        if (machineStore.getBoolean(DONE_FLAG) == true) return
        // ⚠ The done-flag is set ONLY on success. It used to be set unconditionally, outside
        // this runCatching, so any transient failure — an I/O error, a locked prefs backing
        // store, a BackingStoreException — permanently stranded the user's addons, profiles,
        // watch progress and Trakt auth in the legacy location. The migration never ran again
        // and the only trace was one line on stderr, which a launcher-started app discards.
        // Leaving the flag unset means the next launch simply tries again; the migration
        // already skips keys that are present, so a partial run resumes rather than clobbers.
        runCatching { migrate() }
            .onSuccess { machineStore.putBoolean(DONE_FLAG, true) }
            .onFailure { error ->
                System.err.println(
                    "LegacyPrefsMigration: failed (${error.message}); will retry on next launch",
                )
            }
    }

    private fun migrate() {
        val userRoot = Preferences.userRoot()
        var migratedKeys = 0

        if (userRoot.nodeExists(LEGACY_ROOT)) {
            for ((node, storeName) in nodeToStore) {
                val store = DesktopStorage.store(storeName)
                for (key in legacyKeys(userRoot, node)) {
                    if (store.contains(key)) continue // never clobber newer data
                    val value = DesktopPrefs.getString(node, key) ?: continue
                    store.putString(key, convertIfSetTyped(node, key, value))
                    migratedKeys++
                }
            }
        }

        // Machine-local player settings lived on a raw node outside the app root.
        if (userRoot.nodeExists("nuvio/player")) {
            val legacy = userRoot.node("nuvio/player")
            val machineStore = DesktopStorage.store("nuvio_machine")
            for (key in legacy.keys()) {
                if (machineStore.contains(key)) continue
                legacy.get(key, null)?.let {
                    machineStore.putString(key, it)
                    migratedKeys++
                }
            }
        }

        println("LegacyPrefsMigration: migrated $migratedKeys keys into ${DesktopStorage.rootDir}")
    }

    /**
     * All keys ever written for a legacy node: the live Preferences entries plus any
     * DesktopPrefs overflow files — values over the prefs size limit were REMOVED
     * from Preferences when they spilled, so the .dat files are the only record of
     * those keys. Overflow names are "<node>_<key>.dat"; node names and keys are
     * alphanumeric/underscore on desktop, so the sanitised name round-trips.
     */
    private fun legacyKeys(userRoot: Preferences, node: String): Set<String> {
        val keys = mutableSetOf<String>()
        runCatching {
            if (userRoot.nodeExists("$LEGACY_ROOT/$node")) {
                keys += userRoot.node("$LEGACY_ROOT/$node").keys()
            }
        }
        val overflowDir = File(System.getProperty("user.home"), ".local/share/nuvio/prefs")
        val prefix = "${node}_"
        overflowDir.listFiles()?.forEach { file ->
            val name = file.name
            if (name.startsWith(prefix) && name.endsWith(".dat")) {
                keys += name.removePrefix(prefix).removeSuffix(".dat")
            }
        }
        return keys
    }

    private fun convertIfSetTyped(node: String, key: String, value: String): String {
        if (node != "playerSettings") return value
        if (setTypedKeyPrefixes.none { key.startsWith(it) }) return value
        val items = if (value.isEmpty()) emptyList() else value.split('\u001F')
        return json.encodeToString(items)
    }
}
