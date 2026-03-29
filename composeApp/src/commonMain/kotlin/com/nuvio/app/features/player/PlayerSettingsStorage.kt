package com.nuvio.app.features.player

internal expect object PlayerSettingsStorage {
    fun loadShowLoadingOverlay(): Boolean?
    fun saveShowLoadingOverlay(enabled: Boolean)
    fun loadPreferredAudioLanguage(): String?
    fun savePreferredAudioLanguage(language: String)
    fun loadSecondaryPreferredAudioLanguage(): String?
    fun saveSecondaryPreferredAudioLanguage(language: String?)
    fun loadPreferredSubtitleLanguage(): String?
    fun savePreferredSubtitleLanguage(language: String)
    fun loadSecondaryPreferredSubtitleLanguage(): String?
    fun saveSecondaryPreferredSubtitleLanguage(language: String?)
    fun loadStreamReuseLastLinkEnabled(): Boolean?
    fun saveStreamReuseLastLinkEnabled(enabled: Boolean)
    fun loadStreamReuseLastLinkCacheHours(): Int?
    fun saveStreamReuseLastLinkCacheHours(hours: Int)
    fun loadDecoderPriority(): Int?
    fun saveDecoderPriority(priority: Int)
    fun loadMapDV7ToHevc(): Boolean?
    fun saveMapDV7ToHevc(enabled: Boolean)
    fun loadTunnelingEnabled(): Boolean?
    fun saveTunnelingEnabled(enabled: Boolean)
}
