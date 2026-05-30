package com.nuvio.app.features.player

import kotlinx.serialization.json.JsonObject

internal actual object PlayerSettingsStorage {
    actual fun loadShowLoadingOverlay(): Boolean? = null
    actual fun saveShowLoadingOverlay(enabled: Boolean) {}
    actual fun loadResizeMode(): String? = null
    actual fun saveResizeMode(mode: String) {}
    actual fun loadHoldToSpeedEnabled(): Boolean? = null
    actual fun saveHoldToSpeedEnabled(enabled: Boolean) {}
    actual fun loadHoldToSpeedValue(): Float? = null
    actual fun saveHoldToSpeedValue(speed: Float) {}
    actual fun loadExternalPlayerEnabled(): Boolean? = null
    actual fun saveExternalPlayerEnabled(enabled: Boolean) {}
    actual fun loadExternalPlayerId(): String? = null
    actual fun saveExternalPlayerId(playerId: String?) {}
    actual fun loadPreferredAudioLanguage(): String? = null
    actual fun savePreferredAudioLanguage(language: String) {}
    actual fun loadSecondaryPreferredAudioLanguage(): String? = null
    actual fun saveSecondaryPreferredAudioLanguage(language: String?) {}
    actual fun loadPreferredSubtitleLanguage(): String? = null
    actual fun savePreferredSubtitleLanguage(language: String) {}
    actual fun loadSecondaryPreferredSubtitleLanguage(): String? = null
    actual fun saveSecondaryPreferredSubtitleLanguage(language: String?) {}
    actual fun loadSubtitleTextColor(): String? = null
    actual fun saveSubtitleTextColor(colorHex: String) {}
    actual fun loadSubtitleBackgroundColor(): String? = null
    actual fun saveSubtitleBackgroundColor(colorHex: String) {}
    actual fun loadSubtitleOutlineColor(): String? = null
    actual fun saveSubtitleOutlineColor(colorHex: String) {}
    actual fun loadSubtitleOutlineEnabled(): Boolean? = null
    actual fun saveSubtitleOutlineEnabled(enabled: Boolean) {}
    actual fun loadSubtitleOutlineWidth(): Int? = null
    actual fun saveSubtitleOutlineWidth(width: Int) {}
    actual fun loadSubtitleBold(): Boolean? = null
    actual fun saveSubtitleBold(enabled: Boolean) {}
    actual fun loadSubtitleFontSizeSp(): Int? = null
    actual fun saveSubtitleFontSizeSp(fontSizeSp: Int) {}
    actual fun loadSubtitleBottomOffset(): Int? = null
    actual fun saveSubtitleBottomOffset(bottomOffset: Int) {}
    actual fun loadSubtitleUseForcedSubtitles(): Boolean? = null
    actual fun saveSubtitleUseForcedSubtitles(enabled: Boolean) {}
    actual fun loadSubtitleShowOnlyPreferredLanguages(): Boolean? = null
    actual fun saveSubtitleShowOnlyPreferredLanguages(enabled: Boolean) {}
    actual fun loadAddonSubtitleStartupMode(): String? = null
    actual fun saveAddonSubtitleStartupMode(mode: String) {}
    actual fun loadStreamReuseLastLinkEnabled(): Boolean? = null
    actual fun saveStreamReuseLastLinkEnabled(enabled: Boolean) {}
    actual fun loadStreamReuseLastLinkCacheHours(): Int? = null
    actual fun saveStreamReuseLastLinkCacheHours(hours: Int) {}
    actual fun loadDecoderPriority(): Int? = null
    actual fun saveDecoderPriority(priority: Int) {}
    actual fun loadMapDV7ToHevc(): Boolean? = null
    actual fun saveMapDV7ToHevc(enabled: Boolean) {}
    actual fun loadTunnelingEnabled(): Boolean? = null
    actual fun saveTunnelingEnabled(enabled: Boolean) {}
    actual fun loadStreamAutoPlayMode(): String? = null
    actual fun saveStreamAutoPlayMode(mode: String) {}
    actual fun loadStreamAutoPlaySource(): String? = null
    actual fun saveStreamAutoPlaySource(source: String) {}
    actual fun loadStreamAutoPlaySelectedAddons(): Set<String>? = null
    actual fun saveStreamAutoPlaySelectedAddons(addons: Set<String>) {}
    actual fun loadStreamAutoPlaySelectedPlugins(): Set<String>? = null
    actual fun saveStreamAutoPlaySelectedPlugins(plugins: Set<String>) {}
    actual fun loadStreamAutoPlayRegex(): String? = null
    actual fun saveStreamAutoPlayRegex(regex: String) {}
    actual fun loadStreamAutoPlayTimeoutSeconds(): Int? = null
    actual fun saveStreamAutoPlayTimeoutSeconds(seconds: Int) {}
    actual fun loadSkipIntroEnabled(): Boolean? = null
    actual fun saveSkipIntroEnabled(enabled: Boolean) {}
    actual fun loadAnimeSkipEnabled(): Boolean? = null
    actual fun saveAnimeSkipEnabled(enabled: Boolean) {}
    actual fun loadAnimeSkipClientId(): String? = null
    actual fun saveAnimeSkipClientId(clientId: String) {}

    actual fun loadIntroDbApiKey(): String? = null
    actual fun saveIntroDbApiKey(apiKey: String) {}
    actual fun loadIntroSubmitEnabled(): Boolean? = null
    actual fun saveIntroSubmitEnabled(enabled: Boolean) {}
    actual fun loadStreamAutoPlayNextEpisodeEnabled(): Boolean? = null
    actual fun saveStreamAutoPlayNextEpisodeEnabled(enabled: Boolean) {}
    actual fun loadStreamAutoPlayPreferBingeGroup(): Boolean? = null
    actual fun saveStreamAutoPlayPreferBingeGroup(enabled: Boolean) {}
    actual fun loadStreamAutoPlayReuseBingeGroup(): Boolean? = null
    actual fun saveStreamAutoPlayReuseBingeGroup(enabled: Boolean) {}
    actual fun loadNextEpisodeThresholdMode(): String? = null
    actual fun saveNextEpisodeThresholdMode(mode: String) {}
    actual fun loadNextEpisodeThresholdPercent(): Float? = null
    actual fun saveNextEpisodeThresholdPercent(percent: Float) {}
    actual fun loadNextEpisodeThresholdMinutesBeforeEnd(): Float? = null
    actual fun saveNextEpisodeThresholdMinutesBeforeEnd(minutes: Float) {}
    actual fun loadUseLibass(): Boolean? = null
    actual fun saveUseLibass(enabled: Boolean) {}
    actual fun loadLibassRenderType(): String? = null
    actual fun saveLibassRenderType(renderType: String) {}
    actual fun loadIosVideoOutputPreset(): String? = null
    actual fun saveIosVideoOutputPreset(preset: String) {}
    actual fun loadIosToneMappingMode(): String? = null
    actual fun saveIosToneMappingMode(mode: String) {}
    actual fun loadIosTargetPrimaries(): String? = null
    actual fun saveIosTargetPrimaries(primaries: String) {}
    actual fun loadIosTargetTransfer(): String? = null
    actual fun saveIosTargetTransfer(transfer: String) {}
    actual fun loadIosHardwareDecoderMode(): String? = null
    actual fun saveIosHardwareDecoderMode(mode: String) {}
    actual fun loadIosExtendedDynamicRangeEnabled(): Boolean? = null
    actual fun saveIosExtendedDynamicRangeEnabled(enabled: Boolean) {}
    actual fun loadIosTargetColorspaceHintEnabled(): Boolean? = null
    actual fun saveIosTargetColorspaceHintEnabled(enabled: Boolean) {}
    actual fun loadIosHdrComputePeakEnabled(): Boolean? = null
    actual fun saveIosHdrComputePeakEnabled(enabled: Boolean) {}
    actual fun loadIosDebandEnabled(): Boolean? = null
    actual fun saveIosDebandEnabled(enabled: Boolean) {}
    actual fun loadIosInterpolationEnabled(): Boolean? = null
    actual fun saveIosInterpolationEnabled(enabled: Boolean) {}
    actual fun loadIosBrightness(): Int? = null
    actual fun saveIosBrightness(value: Int) {}
    actual fun loadIosContrast(): Int? = null
    actual fun saveIosContrast(value: Int) {}
    actual fun loadIosSaturation(): Int? = null
    actual fun saveIosSaturation(value: Int) {}
    actual fun loadIosGamma(): Int? = null
    actual fun saveIosGamma(value: Int) {}
    actual fun exportToSyncPayload(): JsonObject = kotlinx.serialization.json.JsonObject(emptyMap<String, kotlinx.serialization.json.JsonElement>())
    actual fun replaceFromSyncPayload(payload: JsonObject) {}
}
