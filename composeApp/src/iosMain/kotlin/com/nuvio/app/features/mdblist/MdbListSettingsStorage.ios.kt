package com.nuvio.app.features.mdblist

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

actual object MdbListSettingsStorage {
    private const val enabledKey = "mdblist_enabled"
    private const val apiKey = "mdblist_api_key"
    private const val useImdbKey = "mdblist_use_imdb"
    private const val useTmdbKey = "mdblist_use_tmdb"
    private const val useTomatoesKey = "mdblist_use_tomatoes"
    private const val useMetacriticKey = "mdblist_use_metacritic"
    private const val useTraktKey = "mdblist_use_trakt"
    private const val useLetterboxdKey = "mdblist_use_letterboxd"
    private const val useAudienceKey = "mdblist_use_audience"

    actual fun loadEnabled(): Boolean? = loadBoolean(enabledKey)

    actual fun saveEnabled(enabled: Boolean) {
        saveBoolean(enabledKey, enabled)
    }

    actual fun loadApiKey(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(apiKey))

    actual fun saveApiKey(apiKey: String) {
        NSUserDefaults.standardUserDefaults.setObject(apiKey, forKey = ProfileScopedKey.of(this.apiKey))
    }

    actual fun loadUseImdb(): Boolean? = loadBoolean(useImdbKey)

    actual fun saveUseImdb(enabled: Boolean) {
        saveBoolean(useImdbKey, enabled)
    }

    actual fun loadUseTmdb(): Boolean? = loadBoolean(useTmdbKey)

    actual fun saveUseTmdb(enabled: Boolean) {
        saveBoolean(useTmdbKey, enabled)
    }

    actual fun loadUseTomatoes(): Boolean? = loadBoolean(useTomatoesKey)

    actual fun saveUseTomatoes(enabled: Boolean) {
        saveBoolean(useTomatoesKey, enabled)
    }

    actual fun loadUseMetacritic(): Boolean? = loadBoolean(useMetacriticKey)

    actual fun saveUseMetacritic(enabled: Boolean) {
        saveBoolean(useMetacriticKey, enabled)
    }

    actual fun loadUseTrakt(): Boolean? = loadBoolean(useTraktKey)

    actual fun saveUseTrakt(enabled: Boolean) {
        saveBoolean(useTraktKey, enabled)
    }

    actual fun loadUseLetterboxd(): Boolean? = loadBoolean(useLetterboxdKey)

    actual fun saveUseLetterboxd(enabled: Boolean) {
        saveBoolean(useLetterboxdKey, enabled)
    }

    actual fun loadUseAudience(): Boolean? = loadBoolean(useAudienceKey)

    actual fun saveUseAudience(enabled: Boolean) {
        saveBoolean(useAudienceKey, enabled)
    }

    private fun loadBoolean(key: String): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val scopedKey = ProfileScopedKey.of(key)
        return if (defaults.objectForKey(scopedKey) != null) {
            defaults.boolForKey(scopedKey)
        } else {
            null
        }
    }

    private fun saveBoolean(key: String, enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(key))
    }
}
