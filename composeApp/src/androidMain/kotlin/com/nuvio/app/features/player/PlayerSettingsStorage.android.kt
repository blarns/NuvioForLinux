package com.nuvio.app.features.player

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

actual object PlayerSettingsStorage {
    private const val preferencesName = "nuvio_player_settings"
    private const val showLoadingOverlayKey = "show_loading_overlay"
    private const val preferredAudioLanguageKey = "preferred_audio_language"
    private const val secondaryPreferredAudioLanguageKey = "secondary_preferred_audio_language"
    private const val preferredSubtitleLanguageKey = "preferred_subtitle_language"
    private const val secondaryPreferredSubtitleLanguageKey = "secondary_preferred_subtitle_language"
    private const val streamReuseLastLinkEnabledKey = "stream_reuse_last_link_enabled"
    private const val streamReuseLastLinkCacheHoursKey = "stream_reuse_last_link_cache_hours"
    private const val decoderPriorityKey = "decoder_priority"
    private const val mapDV7ToHevcKey = "map_dv7_to_hevc"
    private const val tunnelingEnabledKey = "tunneling_enabled"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadShowLoadingOverlay(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(showLoadingOverlayKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, true)
            } else {
                null
            }
        }

    actual fun saveShowLoadingOverlay(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(showLoadingOverlayKey), enabled)
            ?.apply()
    }

    actual fun loadPreferredAudioLanguage(): String? =
        preferences?.getString(ProfileScopedKey.of(preferredAudioLanguageKey), null)

    actual fun savePreferredAudioLanguage(language: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(preferredAudioLanguageKey), language)
            ?.apply()
    }

    actual fun loadSecondaryPreferredAudioLanguage(): String? =
        preferences?.getString(ProfileScopedKey.of(secondaryPreferredAudioLanguageKey), null)

    actual fun saveSecondaryPreferredAudioLanguage(language: String?) {
        preferences
            ?.edit()
            ?.apply {
                val key = ProfileScopedKey.of(secondaryPreferredAudioLanguageKey)
                if (language.isNullOrBlank()) {
                    remove(key)
                } else {
                    putString(key, language)
                }
            }
            ?.apply()
    }

    actual fun loadPreferredSubtitleLanguage(): String? =
        preferences?.getString(ProfileScopedKey.of(preferredSubtitleLanguageKey), null)

    actual fun savePreferredSubtitleLanguage(language: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(preferredSubtitleLanguageKey), language)
            ?.apply()
    }

    actual fun loadSecondaryPreferredSubtitleLanguage(): String? =
        preferences?.getString(ProfileScopedKey.of(secondaryPreferredSubtitleLanguageKey), null)

    actual fun saveSecondaryPreferredSubtitleLanguage(language: String?) {
        preferences
            ?.edit()
            ?.apply {
                val key = ProfileScopedKey.of(secondaryPreferredSubtitleLanguageKey)
                if (language.isNullOrBlank()) {
                    remove(key)
                } else {
                    putString(key, language)
                }
            }
            ?.apply()
    }

    actual fun loadStreamReuseLastLinkEnabled(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(streamReuseLastLinkEnabledKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, false)
            } else {
                null
            }
        }

    actual fun saveStreamReuseLastLinkEnabled(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(streamReuseLastLinkEnabledKey), enabled)
            ?.apply()
    }

    actual fun loadStreamReuseLastLinkCacheHours(): Int? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(streamReuseLastLinkCacheHoursKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getInt(key, 24)
            } else {
                null
            }
        }

    actual fun saveStreamReuseLastLinkCacheHours(hours: Int) {
        preferences
            ?.edit()
            ?.putInt(ProfileScopedKey.of(streamReuseLastLinkCacheHoursKey), hours)
            ?.apply()
    }

    actual fun loadDecoderPriority(): Int? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(decoderPriorityKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getInt(key, 1)
            } else {
                null
            }
        }

    actual fun saveDecoderPriority(priority: Int) {
        preferences
            ?.edit()
            ?.putInt(ProfileScopedKey.of(decoderPriorityKey), priority)
            ?.apply()
    }

    actual fun loadMapDV7ToHevc(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(mapDV7ToHevcKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, false)
            } else {
                null
            }
        }

    actual fun saveMapDV7ToHevc(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(mapDV7ToHevcKey), enabled)
            ?.apply()
    }

    actual fun loadTunnelingEnabled(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(tunnelingEnabledKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, false)
            } else {
                null
            }
        }

    actual fun saveTunnelingEnabled(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(tunnelingEnabledKey), enabled)
            ?.apply()
    }
}
