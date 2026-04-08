package com.nuvio.app.features.player

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

actual object PlayerSettingsStorage {
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

    actual fun loadShowLoadingOverlay(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(showLoadingOverlayKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveShowLoadingOverlay(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(showLoadingOverlayKey))
    }

    actual fun loadPreferredAudioLanguage(): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(preferredAudioLanguageKey)
        return defaults.stringForKey(key)
    }

    actual fun savePreferredAudioLanguage(language: String) {
        NSUserDefaults.standardUserDefaults.setObject(language, forKey = ProfileScopedKey.of(preferredAudioLanguageKey))
    }

    actual fun loadSecondaryPreferredAudioLanguage(): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(secondaryPreferredAudioLanguageKey)
        return defaults.stringForKey(key)
    }

    actual fun saveSecondaryPreferredAudioLanguage(language: String?) {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(secondaryPreferredAudioLanguageKey)
        if (language.isNullOrBlank()) {
            defaults.removeObjectForKey(key)
        } else {
            defaults.setObject(language, forKey = key)
        }
    }

    actual fun loadPreferredSubtitleLanguage(): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(preferredSubtitleLanguageKey)
        return defaults.stringForKey(key)
    }

    actual fun savePreferredSubtitleLanguage(language: String) {
        NSUserDefaults.standardUserDefaults.setObject(language, forKey = ProfileScopedKey.of(preferredSubtitleLanguageKey))
    }

    actual fun loadSecondaryPreferredSubtitleLanguage(): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(secondaryPreferredSubtitleLanguageKey)
        return defaults.stringForKey(key)
    }

    actual fun saveSecondaryPreferredSubtitleLanguage(language: String?) {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(secondaryPreferredSubtitleLanguageKey)
        if (language.isNullOrBlank()) {
            defaults.removeObjectForKey(key)
        } else {
            defaults.setObject(language, forKey = key)
        }
    }

    actual fun loadSubtitleTextColor(): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(subtitleTextColorKey)
        return defaults.stringForKey(key)
    }

    actual fun saveSubtitleTextColor(colorHex: String) {
        NSUserDefaults.standardUserDefaults.setObject(colorHex, forKey = ProfileScopedKey.of(subtitleTextColorKey))
    }

    actual fun loadSubtitleOutlineEnabled(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(subtitleOutlineEnabledKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveSubtitleOutlineEnabled(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(subtitleOutlineEnabledKey))
    }

    actual fun loadSubtitleFontSizeSp(): Int? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(subtitleFontSizeSpKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.integerForKey(key).toInt()
        } else {
            null
        }
    }

    actual fun saveSubtitleFontSizeSp(fontSizeSp: Int) {
        NSUserDefaults.standardUserDefaults.setInteger(fontSizeSp.toLong(), forKey = ProfileScopedKey.of(subtitleFontSizeSpKey))
    }

    actual fun loadSubtitleBottomOffset(): Int? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(subtitleBottomOffsetKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.integerForKey(key).toInt()
        } else {
            null
        }
    }

    actual fun saveSubtitleBottomOffset(bottomOffset: Int) {
        NSUserDefaults.standardUserDefaults.setInteger(bottomOffset.toLong(), forKey = ProfileScopedKey.of(subtitleBottomOffsetKey))
    }

    actual fun loadStreamReuseLastLinkEnabled(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(streamReuseLastLinkEnabledKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveStreamReuseLastLinkEnabled(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(streamReuseLastLinkEnabledKey))
    }

    actual fun loadStreamReuseLastLinkCacheHours(): Int? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(streamReuseLastLinkCacheHoursKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.integerForKey(key).toInt()
        } else {
            null
        }
    }

    actual fun saveStreamReuseLastLinkCacheHours(hours: Int) {
        NSUserDefaults.standardUserDefaults.setInteger(hours.toLong(), forKey = ProfileScopedKey.of(streamReuseLastLinkCacheHoursKey))
    }

    actual fun loadDecoderPriority(): Int? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(decoderPriorityKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.integerForKey(key).toInt()
        } else {
            null
        }
    }

    actual fun saveDecoderPriority(priority: Int) {
        NSUserDefaults.standardUserDefaults.setInteger(priority.toLong(), forKey = ProfileScopedKey.of(decoderPriorityKey))
    }

    actual fun loadMapDV7ToHevc(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(mapDV7ToHevcKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveMapDV7ToHevc(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(mapDV7ToHevcKey))
    }

    actual fun loadTunnelingEnabled(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(tunnelingEnabledKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveTunnelingEnabled(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(tunnelingEnabledKey))
    }

    actual fun loadStreamAutoPlayMode(): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(streamAutoPlayModeKey)
        return defaults.stringForKey(key)
    }

    actual fun saveStreamAutoPlayMode(mode: String) {
        NSUserDefaults.standardUserDefaults.setObject(mode, forKey = ProfileScopedKey.of(streamAutoPlayModeKey))
    }

    actual fun loadStreamAutoPlaySource(): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(streamAutoPlaySourceKey)
        return defaults.stringForKey(key)
    }

    actual fun saveStreamAutoPlaySource(source: String) {
        NSUserDefaults.standardUserDefaults.setObject(source, forKey = ProfileScopedKey.of(streamAutoPlaySourceKey))
    }

    @Suppress("UNCHECKED_CAST")
    actual fun loadStreamAutoPlaySelectedAddons(): Set<String>? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(streamAutoPlaySelectedAddonsKey)
        val array = defaults.arrayForKey(key) as? List<String> ?: return null
        return array.toSet()
    }

    actual fun saveStreamAutoPlaySelectedAddons(addons: Set<String>) {
        NSUserDefaults.standardUserDefaults.setObject(addons.toList(), forKey = ProfileScopedKey.of(streamAutoPlaySelectedAddonsKey))
    }

    @Suppress("UNCHECKED_CAST")
    actual fun loadStreamAutoPlaySelectedPlugins(): Set<String>? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(streamAutoPlaySelectedPluginsKey)
        val array = defaults.arrayForKey(key) as? List<String> ?: return null
        return array.toSet()
    }

    actual fun saveStreamAutoPlaySelectedPlugins(plugins: Set<String>) {
        NSUserDefaults.standardUserDefaults.setObject(plugins.toList(), forKey = ProfileScopedKey.of(streamAutoPlaySelectedPluginsKey))
    }

    actual fun loadStreamAutoPlayRegex(): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(streamAutoPlayRegexKey)
        return defaults.stringForKey(key)
    }

    actual fun saveStreamAutoPlayRegex(regex: String) {
        NSUserDefaults.standardUserDefaults.setObject(regex, forKey = ProfileScopedKey.of(streamAutoPlayRegexKey))
    }

    actual fun loadStreamAutoPlayTimeoutSeconds(): Int? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(streamAutoPlayTimeoutSecondsKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.integerForKey(key).toInt()
        } else {
            null
        }
    }

    actual fun saveStreamAutoPlayTimeoutSeconds(seconds: Int) {
        NSUserDefaults.standardUserDefaults.setInteger(seconds.toLong(), forKey = ProfileScopedKey.of(streamAutoPlayTimeoutSecondsKey))
    }

    actual fun loadSkipIntroEnabled(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(skipIntroEnabledKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveSkipIntroEnabled(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(skipIntroEnabledKey))
    }

    actual fun loadAnimeSkipEnabled(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(animeSkipEnabledKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveAnimeSkipEnabled(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(animeSkipEnabledKey))
    }

    actual fun loadAnimeSkipClientId(): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(animeSkipClientIdKey)
        return defaults.stringForKey(key)
    }

    actual fun saveAnimeSkipClientId(clientId: String) {
        NSUserDefaults.standardUserDefaults.setObject(clientId, forKey = ProfileScopedKey.of(animeSkipClientIdKey))
    }

    actual fun loadStreamAutoPlayNextEpisodeEnabled(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(streamAutoPlayNextEpisodeEnabledKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveStreamAutoPlayNextEpisodeEnabled(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(streamAutoPlayNextEpisodeEnabledKey))
    }

    actual fun loadStreamAutoPlayPreferBingeGroup(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(streamAutoPlayPreferBingeGroupKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveStreamAutoPlayPreferBingeGroup(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(streamAutoPlayPreferBingeGroupKey))
    }

    actual fun loadNextEpisodeThresholdMode(): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(nextEpisodeThresholdModeKey)
        return defaults.stringForKey(key)
    }

    actual fun saveNextEpisodeThresholdMode(mode: String) {
        NSUserDefaults.standardUserDefaults.setObject(mode, forKey = ProfileScopedKey.of(nextEpisodeThresholdModeKey))
    }

    actual fun loadNextEpisodeThresholdPercent(): Float? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(nextEpisodeThresholdPercentKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.floatForKey(key)
        } else {
            null
        }
    }

    actual fun saveNextEpisodeThresholdPercent(percent: Float) {
        NSUserDefaults.standardUserDefaults.setFloat(percent, forKey = ProfileScopedKey.of(nextEpisodeThresholdPercentKey))
    }

    actual fun loadNextEpisodeThresholdMinutesBeforeEnd(): Float? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(nextEpisodeThresholdMinutesBeforeEndKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.floatForKey(key)
        } else {
            null
        }
    }

    actual fun saveNextEpisodeThresholdMinutesBeforeEnd(minutes: Float) {
        NSUserDefaults.standardUserDefaults.setFloat(minutes, forKey = ProfileScopedKey.of(nextEpisodeThresholdMinutesBeforeEndKey))
    }

    actual fun loadUseLibass(): Boolean? = null

    actual fun saveUseLibass(enabled: Boolean) {}

    actual fun loadLibassRenderType(): String? = null

    actual fun saveLibassRenderType(renderType: String) {}
}
