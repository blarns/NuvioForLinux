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
    private const val subtitleTextColorKey = "subtitle_text_color"
    private const val subtitleOutlineEnabledKey = "subtitle_outline_enabled"
    private const val subtitleFontSizeSpKey = "subtitle_font_size_sp"
    private const val subtitleBottomOffsetKey = "subtitle_bottom_offset"
    private const val streamReuseLastLinkEnabledKey = "stream_reuse_last_link_enabled"
    private const val streamReuseLastLinkCacheHoursKey = "stream_reuse_last_link_cache_hours"
    private const val decoderPriorityKey = "decoder_priority"
    private const val mapDV7ToHevcKey = "map_dv7_to_hevc"
    private const val tunnelingEnabledKey = "tunneling_enabled"
    private const val streamAutoPlayModeKey = "stream_auto_play_mode"
    private const val streamAutoPlaySourceKey = "stream_auto_play_source"
    private const val streamAutoPlaySelectedAddonsKey = "stream_auto_play_selected_addons"
    private const val streamAutoPlaySelectedPluginsKey = "stream_auto_play_selected_plugins"
    private const val streamAutoPlayRegexKey = "stream_auto_play_regex"
    private const val streamAutoPlayTimeoutSecondsKey = "stream_auto_play_timeout_seconds"
    private const val skipIntroEnabledKey = "skip_intro_enabled"
    private const val animeSkipEnabledKey = "animeskip_enabled"
    private const val animeSkipClientIdKey = "animeskip_client_id"
    private const val streamAutoPlayNextEpisodeEnabledKey = "stream_auto_play_next_episode_enabled"
    private const val streamAutoPlayPreferBingeGroupKey = "stream_auto_play_prefer_binge_group"
    private const val nextEpisodeThresholdModeKey = "next_episode_threshold_mode"
    private const val nextEpisodeThresholdPercentKey = "next_episode_threshold_percent_v2"
    private const val nextEpisodeThresholdMinutesBeforeEndKey = "next_episode_threshold_minutes_before_end_v2"

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

    actual fun loadSubtitleTextColor(): String? =
        preferences?.getString(ProfileScopedKey.of(subtitleTextColorKey), null)

    actual fun saveSubtitleTextColor(colorHex: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(subtitleTextColorKey), colorHex)
            ?.apply()
    }

    actual fun loadSubtitleOutlineEnabled(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(subtitleOutlineEnabledKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, false)
            } else {
                null
            }
        }

    actual fun saveSubtitleOutlineEnabled(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(subtitleOutlineEnabledKey), enabled)
            ?.apply()
    }

    actual fun loadSubtitleFontSizeSp(): Int? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(subtitleFontSizeSpKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getInt(key, SubtitleStyleState.DEFAULT.fontSizeSp)
            } else {
                null
            }
        }

    actual fun saveSubtitleFontSizeSp(fontSizeSp: Int) {
        preferences
            ?.edit()
            ?.putInt(ProfileScopedKey.of(subtitleFontSizeSpKey), fontSizeSp)
            ?.apply()
    }

    actual fun loadSubtitleBottomOffset(): Int? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(subtitleBottomOffsetKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getInt(key, SubtitleStyleState.DEFAULT.bottomOffset)
            } else {
                null
            }
        }

    actual fun saveSubtitleBottomOffset(bottomOffset: Int) {
        preferences
            ?.edit()
            ?.putInt(ProfileScopedKey.of(subtitleBottomOffsetKey), bottomOffset)
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

    actual fun loadStreamAutoPlayMode(): String? =
        preferences?.getString(ProfileScopedKey.of(streamAutoPlayModeKey), null)

    actual fun saveStreamAutoPlayMode(mode: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(streamAutoPlayModeKey), mode)
            ?.apply()
    }

    actual fun loadStreamAutoPlaySource(): String? =
        preferences?.getString(ProfileScopedKey.of(streamAutoPlaySourceKey), null)

    actual fun saveStreamAutoPlaySource(source: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(streamAutoPlaySourceKey), source)
            ?.apply()
    }

    actual fun loadStreamAutoPlaySelectedAddons(): Set<String>? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(streamAutoPlaySelectedAddonsKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getStringSet(key, emptySet()) ?: emptySet()
            } else {
                null
            }
        }

    actual fun saveStreamAutoPlaySelectedAddons(addons: Set<String>) {
        preferences
            ?.edit()
            ?.putStringSet(ProfileScopedKey.of(streamAutoPlaySelectedAddonsKey), addons)
            ?.apply()
    }

    actual fun loadStreamAutoPlaySelectedPlugins(): Set<String>? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(streamAutoPlaySelectedPluginsKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getStringSet(key, emptySet()) ?: emptySet()
            } else {
                null
            }
        }

    actual fun saveStreamAutoPlaySelectedPlugins(plugins: Set<String>) {
        preferences
            ?.edit()
            ?.putStringSet(ProfileScopedKey.of(streamAutoPlaySelectedPluginsKey), plugins)
            ?.apply()
    }

    actual fun loadStreamAutoPlayRegex(): String? =
        preferences?.getString(ProfileScopedKey.of(streamAutoPlayRegexKey), null)

    actual fun saveStreamAutoPlayRegex(regex: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(streamAutoPlayRegexKey), regex)
            ?.apply()
    }

    actual fun loadStreamAutoPlayTimeoutSeconds(): Int? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(streamAutoPlayTimeoutSecondsKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getInt(key, 3)
            } else {
                null
            }
        }

    actual fun saveStreamAutoPlayTimeoutSeconds(seconds: Int) {
        preferences
            ?.edit()
            ?.putInt(ProfileScopedKey.of(streamAutoPlayTimeoutSecondsKey), seconds)
            ?.apply()
    }

    actual fun loadSkipIntroEnabled(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(skipIntroEnabledKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, true)
            } else {
                null
            }
        }

    actual fun saveSkipIntroEnabled(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(skipIntroEnabledKey), enabled)
            ?.apply()
    }

    actual fun loadAnimeSkipEnabled(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(animeSkipEnabledKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, false)
            } else {
                null
            }
        }

    actual fun saveAnimeSkipEnabled(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(animeSkipEnabledKey), enabled)
            ?.apply()
    }

    actual fun loadAnimeSkipClientId(): String? =
        preferences?.getString(ProfileScopedKey.of(animeSkipClientIdKey), null)

    actual fun saveAnimeSkipClientId(clientId: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(animeSkipClientIdKey), clientId)
            ?.apply()
    }

    actual fun loadStreamAutoPlayNextEpisodeEnabled(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(streamAutoPlayNextEpisodeEnabledKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, false)
            } else {
                null
            }
        }

    actual fun saveStreamAutoPlayNextEpisodeEnabled(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(streamAutoPlayNextEpisodeEnabledKey), enabled)
            ?.apply()
    }

    actual fun loadStreamAutoPlayPreferBingeGroup(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(streamAutoPlayPreferBingeGroupKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, true)
            } else {
                null
            }
        }

    actual fun saveStreamAutoPlayPreferBingeGroup(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(streamAutoPlayPreferBingeGroupKey), enabled)
            ?.apply()
    }

    actual fun loadNextEpisodeThresholdMode(): String? =
        preferences?.getString(ProfileScopedKey.of(nextEpisodeThresholdModeKey), null)

    actual fun saveNextEpisodeThresholdMode(mode: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(nextEpisodeThresholdModeKey), mode)
            ?.apply()
    }

    actual fun loadNextEpisodeThresholdPercent(): Float? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(nextEpisodeThresholdPercentKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getFloat(key, 99f)
            } else {
                null
            }
        }

    actual fun saveNextEpisodeThresholdPercent(percent: Float) {
        preferences
            ?.edit()
            ?.putFloat(ProfileScopedKey.of(nextEpisodeThresholdPercentKey), percent)
            ?.apply()
    }

    actual fun loadNextEpisodeThresholdMinutesBeforeEnd(): Float? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(nextEpisodeThresholdMinutesBeforeEndKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getFloat(key, 2f)
            } else {
                null
            }
        }

    actual fun saveNextEpisodeThresholdMinutesBeforeEnd(minutes: Float) {
        preferences
            ?.edit()
            ?.putFloat(ProfileScopedKey.of(nextEpisodeThresholdMinutesBeforeEndKey), minutes)
            ?.apply()
    }
}
