package com.nuvio.app.features.player

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncFloat
import com.nuvio.app.core.sync.decodeSyncInt
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.decodeSyncStringSet
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncFloat
import com.nuvio.app.core.sync.encodeSyncInt
import com.nuvio.app.core.sync.encodeSyncString
import com.nuvio.app.core.sync.encodeSyncStringSet
import com.nuvio.app.desktop.DesktopPrefs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Player settings are persisted via DesktopPrefs with profile-scoped keys, mirroring the Android
// implementation (same key names + types so cloud sync stays compatible). Two settings are
// machine-specific and intentionally kept on a separate, non-profile-scoped, non-synced node:
// hwAccelEnabled (GPU/driver) and audioOutput (VLC aout module — pulse/alsa/jack).
private const val NODE = "playerSettings"

private const val showLoadingOverlayKey = "show_loading_overlay"
private const val resizeModeKey = "resize_mode"
private const val holdToSpeedEnabledKey = "hold_to_speed_enabled"
private const val holdToSpeedValueKey = "hold_to_speed_value"
private const val externalPlayerEnabledKey = "external_player_enabled"
private const val externalPlayerForwardSubtitlesKey = "external_player_forward_subtitles"
private const val externalPlayerIdKey = "external_player_id"
private const val preferredAudioLanguageKey = "preferred_audio_language"
private const val secondaryPreferredAudioLanguageKey = "secondary_preferred_audio_language"
private const val preferredSubtitleLanguageKey = "preferred_subtitle_language"
private const val secondaryPreferredSubtitleLanguageKey = "secondary_preferred_subtitle_language"
private const val subtitleTextColorKey = "subtitle_text_color"
private const val subtitleBackgroundColorKey = "subtitle_background_color"
private const val subtitleOutlineColorKey = "subtitle_outline_color"
private const val subtitleOutlineEnabledKey = "subtitle_outline_enabled"
private const val subtitleOutlineWidthKey = "subtitle_outline_width"
private const val subtitleBoldKey = "subtitle_bold"
private const val subtitleFontSizeSpKey = "subtitle_font_size_sp"
private const val subtitleBottomOffsetKey = "subtitle_bottom_offset"
private const val subtitleUseForcedSubtitlesKey = "subtitle_use_forced_subtitles"
private const val subtitleShowOnlyPreferredLanguagesKey = "subtitle_show_only_preferred_languages"
private const val addonSubtitleStartupModeKey = "addon_subtitle_startup_mode"
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
private const val introDbApiKeyKey = "introdb_api_key"
private const val introSubmitEnabledKey = "intro_submit_enabled"
private const val streamAutoPlayNextEpisodeEnabledKey = "stream_auto_play_next_episode_enabled"
private const val streamAutoPlayPreferBingeGroupKey = "stream_auto_play_prefer_binge_group"
private const val streamAutoPlayReuseBingeGroupKey = "stream_auto_play_reuse_binge_group"
private const val nextEpisodeThresholdModeKey = "next_episode_threshold_mode"
private const val nextEpisodeThresholdPercentKey = "next_episode_threshold_percent_v2"
private const val nextEpisodeThresholdMinutesBeforeEndKey = "next_episode_threshold_minutes_before_end_v2"
private const val useLibassKey = "use_libass"
private const val libassRenderTypeKey = "libass_render_type"
private const val iosVideoOutputPresetKey = "ios_video_output_preset"
private const val iosToneMappingModeKey = "ios_tone_mapping_mode"
private const val iosTargetPrimariesKey = "ios_target_primaries"
private const val iosTargetTransferKey = "ios_target_transfer"
private const val iosHardwareDecoderModeKey = "ios_hardware_decoder_mode"
private const val iosExtendedDynamicRangeEnabledKey = "ios_extended_dynamic_range_enabled"
private const val iosTargetColorspaceHintEnabledKey = "ios_target_colorspace_hint_enabled"
private const val iosHdrComputePeakEnabledKey = "ios_hdr_compute_peak_enabled"
private const val iosDebandEnabledKey = "ios_deband_enabled"
private const val iosInterpolationEnabledKey = "ios_interpolation_enabled"
private const val iosBrightnessKey = "ios_brightness"
private const val iosContrastKey = "ios_contrast"
private const val iosSaturationKey = "ios_saturation"
private const val iosGammaKey = "ios_gamma"

private val syncKeys = listOf(
    showLoadingOverlayKey, resizeModeKey, holdToSpeedEnabledKey, holdToSpeedValueKey,
    externalPlayerEnabledKey, externalPlayerForwardSubtitlesKey, externalPlayerIdKey,
    preferredAudioLanguageKey, secondaryPreferredAudioLanguageKey, preferredSubtitleLanguageKey,
    secondaryPreferredSubtitleLanguageKey, subtitleTextColorKey, subtitleBackgroundColorKey,
    subtitleOutlineColorKey, subtitleOutlineEnabledKey, subtitleOutlineWidthKey, subtitleBoldKey,
    subtitleFontSizeSpKey, subtitleBottomOffsetKey, subtitleUseForcedSubtitlesKey,
    subtitleShowOnlyPreferredLanguagesKey, addonSubtitleStartupModeKey, streamReuseLastLinkEnabledKey,
    streamReuseLastLinkCacheHoursKey, decoderPriorityKey, mapDV7ToHevcKey, tunnelingEnabledKey,
    streamAutoPlayModeKey, streamAutoPlaySourceKey, streamAutoPlaySelectedAddonsKey,
    streamAutoPlaySelectedPluginsKey, streamAutoPlayRegexKey, streamAutoPlayTimeoutSecondsKey,
    skipIntroEnabledKey, animeSkipEnabledKey, animeSkipClientIdKey, streamAutoPlayNextEpisodeEnabledKey,
    streamAutoPlayPreferBingeGroupKey, streamAutoPlayReuseBingeGroupKey, nextEpisodeThresholdModeKey,
    nextEpisodeThresholdPercentKey, nextEpisodeThresholdMinutesBeforeEndKey, useLibassKey,
    libassRenderTypeKey, iosVideoOutputPresetKey, iosToneMappingModeKey, iosTargetPrimariesKey,
    iosTargetTransferKey, iosHardwareDecoderModeKey, iosExtendedDynamicRangeEnabledKey,
    iosTargetColorspaceHintEnabledKey, iosHdrComputePeakEnabledKey, iosDebandEnabledKey,
    iosInterpolationEnabledKey, iosBrightnessKey, iosContrastKey, iosSaturationKey, iosGammaKey,
)

// DesktopPrefs getters already return null when absent, so no contains()/default boilerplate needed.
private fun loadBool(key: String): Boolean? = DesktopPrefs.getBoolean(NODE, ProfileScopedKey.of(key))
private fun saveBool(key: String, value: Boolean) = DesktopPrefs.putBoolean(NODE, ProfileScopedKey.of(key), value)
private fun loadStr(key: String): String? = DesktopPrefs.getString(NODE, ProfileScopedKey.of(key))
private fun saveStr(key: String, value: String) = DesktopPrefs.putString(NODE, ProfileScopedKey.of(key), value)
private fun loadIntVal(key: String): Int? = DesktopPrefs.getInt(NODE, ProfileScopedKey.of(key))
private fun saveIntVal(key: String, value: Int) = DesktopPrefs.putInt(NODE, ProfileScopedKey.of(key), value)
private fun loadFloatVal(key: String): Float? = DesktopPrefs.getFloat(NODE, ProfileScopedKey.of(key))
private fun saveFloatVal(key: String, value: Float) = DesktopPrefs.putFloat(NODE, ProfileScopedKey.of(key), value)
private fun loadSet(key: String): Set<String>? = DesktopPrefs.getStringSet(NODE, ProfileScopedKey.of(key))
private fun saveSet(key: String, value: Set<String>) = DesktopPrefs.putStringSet(NODE, ProfileScopedKey.of(key), value)
private fun removeKey(key: String) = DesktopPrefs.node(NODE).remove(ProfileScopedKey.of(key))

internal actual object PlayerSettingsStorage {
    // Machine-specific, non-profile-scoped, excluded from cloud sync (Android stubs these).
    private val machinePrefs = java.util.prefs.Preferences.userRoot().node("nuvio/player")
    actual fun loadHwAccelEnabled(): Boolean? = machinePrefs.get("hwAccelEnabled", null)?.toBooleanStrictOrNull()
    actual fun saveHwAccelEnabled(enabled: Boolean) { machinePrefs.put("hwAccelEnabled", enabled.toString()) }
    actual fun loadAudioOutput(): String? = machinePrefs.get("audioOutput", null)
    actual fun saveAudioOutput(module: String) { machinePrefs.put("audioOutput", module) }

    actual fun loadShowLoadingOverlay(): Boolean? = loadBool(showLoadingOverlayKey)
    actual fun saveShowLoadingOverlay(enabled: Boolean) = saveBool(showLoadingOverlayKey, enabled)
    actual fun loadResizeMode(): String? = loadStr(resizeModeKey)
    actual fun saveResizeMode(mode: String) = saveStr(resizeModeKey, mode)
    actual fun loadHoldToSpeedEnabled(): Boolean? = loadBool(holdToSpeedEnabledKey)
    actual fun saveHoldToSpeedEnabled(enabled: Boolean) = saveBool(holdToSpeedEnabledKey, enabled)
    actual fun loadHoldToSpeedValue(): Float? = loadFloatVal(holdToSpeedValueKey)
    actual fun saveHoldToSpeedValue(speed: Float) = saveFloatVal(holdToSpeedValueKey, speed)
    actual fun loadExternalPlayerEnabled(): Boolean? = loadBool(externalPlayerEnabledKey)
    actual fun saveExternalPlayerEnabled(enabled: Boolean) = saveBool(externalPlayerEnabledKey, enabled)
    actual fun loadExternalPlayerForwardSubtitles(): Boolean? = loadBool(externalPlayerForwardSubtitlesKey)
    actual fun saveExternalPlayerForwardSubtitles(enabled: Boolean) = saveBool(externalPlayerForwardSubtitlesKey, enabled)
    actual fun loadExternalPlayerId(): String? = loadStr(externalPlayerIdKey)
    actual fun saveExternalPlayerId(playerId: String?) {
        if (playerId.isNullOrBlank()) removeKey(externalPlayerIdKey) else saveStr(externalPlayerIdKey, playerId)
    }
    actual fun loadPreferredAudioLanguage(): String? = loadStr(preferredAudioLanguageKey)
    actual fun savePreferredAudioLanguage(language: String) = saveStr(preferredAudioLanguageKey, language)
    actual fun loadSecondaryPreferredAudioLanguage(): String? = loadStr(secondaryPreferredAudioLanguageKey)
    actual fun saveSecondaryPreferredAudioLanguage(language: String?) {
        if (language.isNullOrBlank()) removeKey(secondaryPreferredAudioLanguageKey) else saveStr(secondaryPreferredAudioLanguageKey, language)
    }
    actual fun loadPreferredSubtitleLanguage(): String? = loadStr(preferredSubtitleLanguageKey)
    actual fun savePreferredSubtitleLanguage(language: String) = saveStr(preferredSubtitleLanguageKey, language)
    actual fun loadSecondaryPreferredSubtitleLanguage(): String? = loadStr(secondaryPreferredSubtitleLanguageKey)
    actual fun saveSecondaryPreferredSubtitleLanguage(language: String?) {
        if (language.isNullOrBlank()) removeKey(secondaryPreferredSubtitleLanguageKey) else saveStr(secondaryPreferredSubtitleLanguageKey, language)
    }
    actual fun loadSubtitleTextColor(): String? = loadStr(subtitleTextColorKey)
    actual fun saveSubtitleTextColor(colorHex: String) = saveStr(subtitleTextColorKey, colorHex)
    actual fun loadSubtitleBackgroundColor(): String? = loadStr(subtitleBackgroundColorKey)
    actual fun saveSubtitleBackgroundColor(colorHex: String) = saveStr(subtitleBackgroundColorKey, colorHex)
    actual fun loadSubtitleOutlineColor(): String? = loadStr(subtitleOutlineColorKey)
    actual fun saveSubtitleOutlineColor(colorHex: String) = saveStr(subtitleOutlineColorKey, colorHex)
    actual fun loadSubtitleOutlineEnabled(): Boolean? = loadBool(subtitleOutlineEnabledKey)
    actual fun saveSubtitleOutlineEnabled(enabled: Boolean) = saveBool(subtitleOutlineEnabledKey, enabled)
    actual fun loadSubtitleOutlineWidth(): Int? = loadIntVal(subtitleOutlineWidthKey)
    actual fun saveSubtitleOutlineWidth(width: Int) = saveIntVal(subtitleOutlineWidthKey, width)
    actual fun loadSubtitleBold(): Boolean? = loadBool(subtitleBoldKey)
    actual fun saveSubtitleBold(enabled: Boolean) = saveBool(subtitleBoldKey, enabled)
    actual fun loadSubtitleFontSizeSp(): Int? = loadIntVal(subtitleFontSizeSpKey)
    actual fun saveSubtitleFontSizeSp(fontSizeSp: Int) = saveIntVal(subtitleFontSizeSpKey, fontSizeSp)
    actual fun loadSubtitleBottomOffset(): Int? = loadIntVal(subtitleBottomOffsetKey)
    actual fun saveSubtitleBottomOffset(bottomOffset: Int) = saveIntVal(subtitleBottomOffsetKey, bottomOffset)
    actual fun loadSubtitleUseForcedSubtitles(): Boolean? = loadBool(subtitleUseForcedSubtitlesKey)
    actual fun saveSubtitleUseForcedSubtitles(enabled: Boolean) = saveBool(subtitleUseForcedSubtitlesKey, enabled)
    actual fun loadSubtitleShowOnlyPreferredLanguages(): Boolean? = loadBool(subtitleShowOnlyPreferredLanguagesKey)
    actual fun saveSubtitleShowOnlyPreferredLanguages(enabled: Boolean) = saveBool(subtitleShowOnlyPreferredLanguagesKey, enabled)
    actual fun loadAddonSubtitleStartupMode(): String? = loadStr(addonSubtitleStartupModeKey)
    actual fun saveAddonSubtitleStartupMode(mode: String) = saveStr(addonSubtitleStartupModeKey, mode)
    actual fun loadStreamReuseLastLinkEnabled(): Boolean? = loadBool(streamReuseLastLinkEnabledKey)
    actual fun saveStreamReuseLastLinkEnabled(enabled: Boolean) = saveBool(streamReuseLastLinkEnabledKey, enabled)
    actual fun loadStreamReuseLastLinkCacheHours(): Int? = loadIntVal(streamReuseLastLinkCacheHoursKey)
    actual fun saveStreamReuseLastLinkCacheHours(hours: Int) = saveIntVal(streamReuseLastLinkCacheHoursKey, hours)
    actual fun loadDecoderPriority(): Int? = loadIntVal(decoderPriorityKey)
    actual fun saveDecoderPriority(priority: Int) = saveIntVal(decoderPriorityKey, priority)
    actual fun loadMapDV7ToHevc(): Boolean? = loadBool(mapDV7ToHevcKey)
    actual fun saveMapDV7ToHevc(enabled: Boolean) = saveBool(mapDV7ToHevcKey, enabled)
    actual fun loadTunnelingEnabled(): Boolean? = loadBool(tunnelingEnabledKey)
    actual fun saveTunnelingEnabled(enabled: Boolean) = saveBool(tunnelingEnabledKey, enabled)
    actual fun loadStreamAutoPlayMode(): String? = loadStr(streamAutoPlayModeKey)
    actual fun saveStreamAutoPlayMode(mode: String) = saveStr(streamAutoPlayModeKey, mode)
    actual fun loadStreamAutoPlaySource(): String? = loadStr(streamAutoPlaySourceKey)
    actual fun saveStreamAutoPlaySource(source: String) = saveStr(streamAutoPlaySourceKey, source)
    actual fun loadStreamAutoPlaySelectedAddons(): Set<String>? = loadSet(streamAutoPlaySelectedAddonsKey)
    actual fun saveStreamAutoPlaySelectedAddons(addons: Set<String>) = saveSet(streamAutoPlaySelectedAddonsKey, addons)
    actual fun loadStreamAutoPlaySelectedPlugins(): Set<String>? = loadSet(streamAutoPlaySelectedPluginsKey)
    actual fun saveStreamAutoPlaySelectedPlugins(plugins: Set<String>) = saveSet(streamAutoPlaySelectedPluginsKey, plugins)
    actual fun loadStreamAutoPlayRegex(): String? = loadStr(streamAutoPlayRegexKey)
    actual fun saveStreamAutoPlayRegex(regex: String) = saveStr(streamAutoPlayRegexKey, regex)
    actual fun loadStreamAutoPlayTimeoutSeconds(): Int? = loadIntVal(streamAutoPlayTimeoutSecondsKey)
    actual fun saveStreamAutoPlayTimeoutSeconds(seconds: Int) = saveIntVal(streamAutoPlayTimeoutSecondsKey, seconds)
    actual fun loadSkipIntroEnabled(): Boolean? = loadBool(skipIntroEnabledKey)
    actual fun saveSkipIntroEnabled(enabled: Boolean) = saveBool(skipIntroEnabledKey, enabled)
    actual fun loadAnimeSkipEnabled(): Boolean? = loadBool(animeSkipEnabledKey)
    actual fun saveAnimeSkipEnabled(enabled: Boolean) = saveBool(animeSkipEnabledKey, enabled)
    actual fun loadAnimeSkipClientId(): String? = loadStr(animeSkipClientIdKey)
    actual fun saveAnimeSkipClientId(clientId: String) = saveStr(animeSkipClientIdKey, clientId)
    actual fun loadIntroDbApiKey(): String? = loadStr(introDbApiKeyKey)
    actual fun saveIntroDbApiKey(apiKey: String) = saveStr(introDbApiKeyKey, apiKey)
    actual fun loadIntroSubmitEnabled(): Boolean? = loadBool(introSubmitEnabledKey)
    actual fun saveIntroSubmitEnabled(enabled: Boolean) = saveBool(introSubmitEnabledKey, enabled)
    actual fun loadStreamAutoPlayNextEpisodeEnabled(): Boolean? = loadBool(streamAutoPlayNextEpisodeEnabledKey)
    actual fun saveStreamAutoPlayNextEpisodeEnabled(enabled: Boolean) = saveBool(streamAutoPlayNextEpisodeEnabledKey, enabled)
    actual fun loadStreamAutoPlayPreferBingeGroup(): Boolean? = loadBool(streamAutoPlayPreferBingeGroupKey)
    actual fun saveStreamAutoPlayPreferBingeGroup(enabled: Boolean) = saveBool(streamAutoPlayPreferBingeGroupKey, enabled)
    actual fun loadStreamAutoPlayReuseBingeGroup(): Boolean? = loadBool(streamAutoPlayReuseBingeGroupKey)
    actual fun saveStreamAutoPlayReuseBingeGroup(enabled: Boolean) = saveBool(streamAutoPlayReuseBingeGroupKey, enabled)
    actual fun loadNextEpisodeThresholdMode(): String? = loadStr(nextEpisodeThresholdModeKey)
    actual fun saveNextEpisodeThresholdMode(mode: String) = saveStr(nextEpisodeThresholdModeKey, mode)
    actual fun loadNextEpisodeThresholdPercent(): Float? = loadFloatVal(nextEpisodeThresholdPercentKey)
    actual fun saveNextEpisodeThresholdPercent(percent: Float) = saveFloatVal(nextEpisodeThresholdPercentKey, percent)
    actual fun loadNextEpisodeThresholdMinutesBeforeEnd(): Float? = loadFloatVal(nextEpisodeThresholdMinutesBeforeEndKey)
    actual fun saveNextEpisodeThresholdMinutesBeforeEnd(minutes: Float) = saveFloatVal(nextEpisodeThresholdMinutesBeforeEndKey, minutes)
    actual fun loadUseLibass(): Boolean? = loadBool(useLibassKey)
    actual fun saveUseLibass(enabled: Boolean) = saveBool(useLibassKey, enabled)
    actual fun loadLibassRenderType(): String? = loadStr(libassRenderTypeKey)
    actual fun saveLibassRenderType(renderType: String) = saveStr(libassRenderTypeKey, renderType)
    actual fun loadIosVideoOutputPreset(): String? = loadStr(iosVideoOutputPresetKey)
    actual fun saveIosVideoOutputPreset(preset: String) = saveStr(iosVideoOutputPresetKey, preset)
    actual fun loadIosToneMappingMode(): String? = loadStr(iosToneMappingModeKey)
    actual fun saveIosToneMappingMode(mode: String) = saveStr(iosToneMappingModeKey, mode)
    actual fun loadIosTargetPrimaries(): String? = loadStr(iosTargetPrimariesKey)
    actual fun saveIosTargetPrimaries(primaries: String) = saveStr(iosTargetPrimariesKey, primaries)
    actual fun loadIosTargetTransfer(): String? = loadStr(iosTargetTransferKey)
    actual fun saveIosTargetTransfer(transfer: String) = saveStr(iosTargetTransferKey, transfer)
    actual fun loadIosHardwareDecoderMode(): String? = loadStr(iosHardwareDecoderModeKey)
    actual fun saveIosHardwareDecoderMode(mode: String) = saveStr(iosHardwareDecoderModeKey, mode)
    actual fun loadIosExtendedDynamicRangeEnabled(): Boolean? = loadBool(iosExtendedDynamicRangeEnabledKey)
    actual fun saveIosExtendedDynamicRangeEnabled(enabled: Boolean) = saveBool(iosExtendedDynamicRangeEnabledKey, enabled)
    actual fun loadIosTargetColorspaceHintEnabled(): Boolean? = loadBool(iosTargetColorspaceHintEnabledKey)
    actual fun saveIosTargetColorspaceHintEnabled(enabled: Boolean) = saveBool(iosTargetColorspaceHintEnabledKey, enabled)
    actual fun loadIosHdrComputePeakEnabled(): Boolean? = loadBool(iosHdrComputePeakEnabledKey)
    actual fun saveIosHdrComputePeakEnabled(enabled: Boolean) = saveBool(iosHdrComputePeakEnabledKey, enabled)
    actual fun loadIosDebandEnabled(): Boolean? = loadBool(iosDebandEnabledKey)
    actual fun saveIosDebandEnabled(enabled: Boolean) = saveBool(iosDebandEnabledKey, enabled)
    actual fun loadIosInterpolationEnabled(): Boolean? = loadBool(iosInterpolationEnabledKey)
    actual fun saveIosInterpolationEnabled(enabled: Boolean) = saveBool(iosInterpolationEnabledKey, enabled)
    actual fun loadIosBrightness(): Int? = loadIntVal(iosBrightnessKey)
    actual fun saveIosBrightness(value: Int) = saveIntVal(iosBrightnessKey, value)
    actual fun loadIosContrast(): Int? = loadIntVal(iosContrastKey)
    actual fun saveIosContrast(value: Int) = saveIntVal(iosContrastKey, value)
    actual fun loadIosSaturation(): Int? = loadIntVal(iosSaturationKey)
    actual fun saveIosSaturation(value: Int) = saveIntVal(iosSaturationKey, value)
    actual fun loadIosGamma(): Int? = loadIntVal(iosGammaKey)
    actual fun saveIosGamma(value: Int) = saveIntVal(iosGammaKey, value)

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadShowLoadingOverlay()?.let { put(showLoadingOverlayKey, encodeSyncBoolean(it)) }
        loadResizeMode()?.let { put(resizeModeKey, encodeSyncString(it)) }
        loadHoldToSpeedEnabled()?.let { put(holdToSpeedEnabledKey, encodeSyncBoolean(it)) }
        loadHoldToSpeedValue()?.let { put(holdToSpeedValueKey, encodeSyncFloat(it)) }
        loadExternalPlayerEnabled()?.let { put(externalPlayerEnabledKey, encodeSyncBoolean(it)) }
        loadExternalPlayerForwardSubtitles()?.let { put(externalPlayerForwardSubtitlesKey, encodeSyncBoolean(it)) }
        loadExternalPlayerId()?.let { put(externalPlayerIdKey, encodeSyncString(it)) }
        loadPreferredAudioLanguage()?.let { put(preferredAudioLanguageKey, encodeSyncString(it)) }
        loadSecondaryPreferredAudioLanguage()?.let { put(secondaryPreferredAudioLanguageKey, encodeSyncString(it)) }
        loadPreferredSubtitleLanguage()?.let { put(preferredSubtitleLanguageKey, encodeSyncString(it)) }
        loadSecondaryPreferredSubtitleLanguage()?.let { put(secondaryPreferredSubtitleLanguageKey, encodeSyncString(it)) }
        loadSubtitleTextColor()?.let { put(subtitleTextColorKey, encodeSyncString(it)) }
        loadSubtitleBackgroundColor()?.let { put(subtitleBackgroundColorKey, encodeSyncString(it)) }
        loadSubtitleOutlineColor()?.let { put(subtitleOutlineColorKey, encodeSyncString(it)) }
        loadSubtitleOutlineEnabled()?.let { put(subtitleOutlineEnabledKey, encodeSyncBoolean(it)) }
        loadSubtitleOutlineWidth()?.let { put(subtitleOutlineWidthKey, encodeSyncInt(it)) }
        loadSubtitleBold()?.let { put(subtitleBoldKey, encodeSyncBoolean(it)) }
        loadSubtitleFontSizeSp()?.let { put(subtitleFontSizeSpKey, encodeSyncInt(it)) }
        loadSubtitleBottomOffset()?.let { put(subtitleBottomOffsetKey, encodeSyncInt(it)) }
        loadSubtitleUseForcedSubtitles()?.let { put(subtitleUseForcedSubtitlesKey, encodeSyncBoolean(it)) }
        loadSubtitleShowOnlyPreferredLanguages()?.let { put(subtitleShowOnlyPreferredLanguagesKey, encodeSyncBoolean(it)) }
        loadAddonSubtitleStartupMode()?.let { put(addonSubtitleStartupModeKey, encodeSyncString(it)) }
        loadStreamReuseLastLinkEnabled()?.let { put(streamReuseLastLinkEnabledKey, encodeSyncBoolean(it)) }
        loadStreamReuseLastLinkCacheHours()?.let { put(streamReuseLastLinkCacheHoursKey, encodeSyncInt(it)) }
        loadDecoderPriority()?.let { put(decoderPriorityKey, encodeSyncInt(it)) }
        loadMapDV7ToHevc()?.let { put(mapDV7ToHevcKey, encodeSyncBoolean(it)) }
        loadTunnelingEnabled()?.let { put(tunnelingEnabledKey, encodeSyncBoolean(it)) }
        loadStreamAutoPlayMode()?.let { put(streamAutoPlayModeKey, encodeSyncString(it)) }
        loadStreamAutoPlaySource()?.let { put(streamAutoPlaySourceKey, encodeSyncString(it)) }
        loadStreamAutoPlaySelectedAddons()?.let { put(streamAutoPlaySelectedAddonsKey, encodeSyncStringSet(it)) }
        loadStreamAutoPlaySelectedPlugins()?.let { put(streamAutoPlaySelectedPluginsKey, encodeSyncStringSet(it)) }
        loadStreamAutoPlayRegex()?.let { put(streamAutoPlayRegexKey, encodeSyncString(it)) }
        loadStreamAutoPlayTimeoutSeconds()?.let { put(streamAutoPlayTimeoutSecondsKey, encodeSyncInt(it)) }
        loadSkipIntroEnabled()?.let { put(skipIntroEnabledKey, encodeSyncBoolean(it)) }
        loadAnimeSkipEnabled()?.let { put(animeSkipEnabledKey, encodeSyncBoolean(it)) }
        loadAnimeSkipClientId()?.let { put(animeSkipClientIdKey, encodeSyncString(it)) }
        loadStreamAutoPlayNextEpisodeEnabled()?.let { put(streamAutoPlayNextEpisodeEnabledKey, encodeSyncBoolean(it)) }
        loadStreamAutoPlayPreferBingeGroup()?.let { put(streamAutoPlayPreferBingeGroupKey, encodeSyncBoolean(it)) }
        loadStreamAutoPlayReuseBingeGroup()?.let { put(streamAutoPlayReuseBingeGroupKey, encodeSyncBoolean(it)) }
        loadNextEpisodeThresholdMode()?.let { put(nextEpisodeThresholdModeKey, encodeSyncString(it)) }
        loadNextEpisodeThresholdPercent()?.let { put(nextEpisodeThresholdPercentKey, encodeSyncFloat(it)) }
        loadNextEpisodeThresholdMinutesBeforeEnd()?.let { put(nextEpisodeThresholdMinutesBeforeEndKey, encodeSyncFloat(it)) }
        loadUseLibass()?.let { put(useLibassKey, encodeSyncBoolean(it)) }
        loadLibassRenderType()?.let { put(libassRenderTypeKey, encodeSyncString(it)) }
        loadIosVideoOutputPreset()?.let { put(iosVideoOutputPresetKey, encodeSyncString(it)) }
        loadIosToneMappingMode()?.let { put(iosToneMappingModeKey, encodeSyncString(it)) }
        loadIosTargetPrimaries()?.let { put(iosTargetPrimariesKey, encodeSyncString(it)) }
        loadIosTargetTransfer()?.let { put(iosTargetTransferKey, encodeSyncString(it)) }
        loadIosHardwareDecoderMode()?.let { put(iosHardwareDecoderModeKey, encodeSyncString(it)) }
        loadIosExtendedDynamicRangeEnabled()?.let { put(iosExtendedDynamicRangeEnabledKey, encodeSyncBoolean(it)) }
        loadIosTargetColorspaceHintEnabled()?.let { put(iosTargetColorspaceHintEnabledKey, encodeSyncBoolean(it)) }
        loadIosHdrComputePeakEnabled()?.let { put(iosHdrComputePeakEnabledKey, encodeSyncBoolean(it)) }
        loadIosDebandEnabled()?.let { put(iosDebandEnabledKey, encodeSyncBoolean(it)) }
        loadIosInterpolationEnabled()?.let { put(iosInterpolationEnabledKey, encodeSyncBoolean(it)) }
        loadIosBrightness()?.let { put(iosBrightnessKey, encodeSyncInt(it)) }
        loadIosContrast()?.let { put(iosContrastKey, encodeSyncInt(it)) }
        loadIosSaturation()?.let { put(iosSaturationKey, encodeSyncInt(it)) }
        loadIosGamma()?.let { put(iosGammaKey, encodeSyncInt(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        syncKeys.forEach { removeKey(it) }

        payload.decodeSyncBoolean(showLoadingOverlayKey)?.let(::saveShowLoadingOverlay)
        payload.decodeSyncString(resizeModeKey)?.let(::saveResizeMode)
        payload.decodeSyncBoolean(holdToSpeedEnabledKey)?.let(::saveHoldToSpeedEnabled)
        payload.decodeSyncFloat(holdToSpeedValueKey)?.let(::saveHoldToSpeedValue)
        payload.decodeSyncBoolean(externalPlayerEnabledKey)?.let(::saveExternalPlayerEnabled)
        payload.decodeSyncBoolean(externalPlayerForwardSubtitlesKey)?.let(::saveExternalPlayerForwardSubtitles)
        payload.decodeSyncString(externalPlayerIdKey)?.let(::saveExternalPlayerId)
        payload.decodeSyncString(preferredAudioLanguageKey)?.let(::savePreferredAudioLanguage)
        payload.decodeSyncString(secondaryPreferredAudioLanguageKey)?.let(::saveSecondaryPreferredAudioLanguage)
        payload.decodeSyncString(preferredSubtitleLanguageKey)?.let(::savePreferredSubtitleLanguage)
        payload.decodeSyncString(secondaryPreferredSubtitleLanguageKey)?.let(::saveSecondaryPreferredSubtitleLanguage)
        payload.decodeSyncString(subtitleTextColorKey)?.let(::saveSubtitleTextColor)
        payload.decodeSyncString(subtitleBackgroundColorKey)?.let(::saveSubtitleBackgroundColor)
        payload.decodeSyncString(subtitleOutlineColorKey)?.let(::saveSubtitleOutlineColor)
        payload.decodeSyncBoolean(subtitleOutlineEnabledKey)?.let(::saveSubtitleOutlineEnabled)
        payload.decodeSyncInt(subtitleOutlineWidthKey)?.let(::saveSubtitleOutlineWidth)
        payload.decodeSyncBoolean(subtitleBoldKey)?.let(::saveSubtitleBold)
        payload.decodeSyncInt(subtitleFontSizeSpKey)?.let(::saveSubtitleFontSizeSp)
        payload.decodeSyncInt(subtitleBottomOffsetKey)?.let(::saveSubtitleBottomOffset)
        payload.decodeSyncBoolean(subtitleUseForcedSubtitlesKey)?.let(::saveSubtitleUseForcedSubtitles)
        payload.decodeSyncBoolean(subtitleShowOnlyPreferredLanguagesKey)?.let(::saveSubtitleShowOnlyPreferredLanguages)
        payload.decodeSyncString(addonSubtitleStartupModeKey)?.let(::saveAddonSubtitleStartupMode)
        payload.decodeSyncBoolean(streamReuseLastLinkEnabledKey)?.let(::saveStreamReuseLastLinkEnabled)
        payload.decodeSyncInt(streamReuseLastLinkCacheHoursKey)?.let(::saveStreamReuseLastLinkCacheHours)
        payload.decodeSyncInt(decoderPriorityKey)?.let(::saveDecoderPriority)
        payload.decodeSyncBoolean(mapDV7ToHevcKey)?.let(::saveMapDV7ToHevc)
        payload.decodeSyncBoolean(tunnelingEnabledKey)?.let(::saveTunnelingEnabled)
        payload.decodeSyncString(streamAutoPlayModeKey)?.let(::saveStreamAutoPlayMode)
        payload.decodeSyncString(streamAutoPlaySourceKey)?.let(::saveStreamAutoPlaySource)
        payload.decodeSyncStringSet(streamAutoPlaySelectedAddonsKey)?.let(::saveStreamAutoPlaySelectedAddons)
        payload.decodeSyncStringSet(streamAutoPlaySelectedPluginsKey)?.let(::saveStreamAutoPlaySelectedPlugins)
        payload.decodeSyncString(streamAutoPlayRegexKey)?.let(::saveStreamAutoPlayRegex)
        payload.decodeSyncInt(streamAutoPlayTimeoutSecondsKey)?.let(::saveStreamAutoPlayTimeoutSeconds)
        payload.decodeSyncBoolean(skipIntroEnabledKey)?.let(::saveSkipIntroEnabled)
        payload.decodeSyncBoolean(animeSkipEnabledKey)?.let(::saveAnimeSkipEnabled)
        payload.decodeSyncString(animeSkipClientIdKey)?.let(::saveAnimeSkipClientId)
        payload.decodeSyncString(introDbApiKeyKey)?.let(::saveIntroDbApiKey)
        payload.decodeSyncBoolean(introSubmitEnabledKey)?.let(::saveIntroSubmitEnabled)
        payload.decodeSyncBoolean(streamAutoPlayNextEpisodeEnabledKey)?.let(::saveStreamAutoPlayNextEpisodeEnabled)
        payload.decodeSyncBoolean(streamAutoPlayPreferBingeGroupKey)?.let(::saveStreamAutoPlayPreferBingeGroup)
        payload.decodeSyncBoolean(streamAutoPlayReuseBingeGroupKey)?.let(::saveStreamAutoPlayReuseBingeGroup)
        payload.decodeSyncString(nextEpisodeThresholdModeKey)?.let(::saveNextEpisodeThresholdMode)
        payload.decodeSyncFloat(nextEpisodeThresholdPercentKey)?.let(::saveNextEpisodeThresholdPercent)
        payload.decodeSyncFloat(nextEpisodeThresholdMinutesBeforeEndKey)?.let(::saveNextEpisodeThresholdMinutesBeforeEnd)
        payload.decodeSyncBoolean(useLibassKey)?.let(::saveUseLibass)
        payload.decodeSyncString(libassRenderTypeKey)?.let(::saveLibassRenderType)
        payload.decodeSyncString(iosVideoOutputPresetKey)?.let(::saveIosVideoOutputPreset)
        payload.decodeSyncString(iosToneMappingModeKey)?.let(::saveIosToneMappingMode)
        payload.decodeSyncString(iosTargetPrimariesKey)?.let(::saveIosTargetPrimaries)
        payload.decodeSyncString(iosTargetTransferKey)?.let(::saveIosTargetTransfer)
        payload.decodeSyncString(iosHardwareDecoderModeKey)?.let(::saveIosHardwareDecoderMode)
        payload.decodeSyncBoolean(iosExtendedDynamicRangeEnabledKey)?.let(::saveIosExtendedDynamicRangeEnabled)
        payload.decodeSyncBoolean(iosTargetColorspaceHintEnabledKey)?.let(::saveIosTargetColorspaceHintEnabled)
        payload.decodeSyncBoolean(iosHdrComputePeakEnabledKey)?.let(::saveIosHdrComputePeakEnabled)
        payload.decodeSyncBoolean(iosDebandEnabledKey)?.let(::saveIosDebandEnabled)
        payload.decodeSyncBoolean(iosInterpolationEnabledKey)?.let(::saveIosInterpolationEnabled)
        payload.decodeSyncInt(iosBrightnessKey)?.let(::saveIosBrightness)
        payload.decodeSyncInt(iosContrastKey)?.let(::saveIosContrast)
        payload.decodeSyncInt(iosSaturationKey)?.let(::saveIosSaturation)
        payload.decodeSyncInt(iosGammaKey)?.let(::saveIosGamma)
    }
}
