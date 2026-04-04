package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlayerSettingsUiState(
    val showLoadingOverlay: Boolean = true,
    val preferredAudioLanguage: String = AudioLanguageOption.DEVICE,
    val secondaryPreferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String = SubtitleLanguageOption.NONE,
    val secondaryPreferredSubtitleLanguage: String? = null,
    val subtitleStyle: SubtitleStyleState = SubtitleStyleState.DEFAULT,
    val streamReuseLastLinkEnabled: Boolean = false,
    val streamReuseLastLinkCacheHours: Int = 24,
    val decoderPriority: Int = 1,
    val mapDV7ToHevc: Boolean = false,
    val tunnelingEnabled: Boolean = false,
    val streamAutoPlayMode: StreamAutoPlayMode = StreamAutoPlayMode.MANUAL,
    val streamAutoPlaySource: StreamAutoPlaySource = StreamAutoPlaySource.ALL_SOURCES,
    val streamAutoPlaySelectedAddons: Set<String> = emptySet(),
    val streamAutoPlaySelectedPlugins: Set<String> = emptySet(),
    val streamAutoPlayRegex: String = "",
    val streamAutoPlayTimeoutSeconds: Int = 3,
)

object PlayerSettingsRepository {
    private val _uiState = MutableStateFlow(PlayerSettingsUiState())
    val uiState: StateFlow<PlayerSettingsUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var showLoadingOverlay = true
    private var preferredAudioLanguage = AudioLanguageOption.DEVICE
    private var secondaryPreferredAudioLanguage: String? = null
    private var preferredSubtitleLanguage = SubtitleLanguageOption.NONE
    private var secondaryPreferredSubtitleLanguage: String? = null
    private var subtitleStyle = SubtitleStyleState.DEFAULT
    private var streamReuseLastLinkEnabled = false
    private var streamReuseLastLinkCacheHours = 24
    private var decoderPriority = 1
    private var mapDV7ToHevc = false
    private var tunnelingEnabled = false
    private var streamAutoPlayMode = StreamAutoPlayMode.MANUAL
    private var streamAutoPlaySource = StreamAutoPlaySource.ALL_SOURCES
    private var streamAutoPlaySelectedAddons: Set<String> = emptySet()
    private var streamAutoPlaySelectedPlugins: Set<String> = emptySet()
    private var streamAutoPlayRegex = ""
    private var streamAutoPlayTimeoutSeconds = 3

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun clearLocalState() {
        hasLoaded = false
        showLoadingOverlay = true
        preferredAudioLanguage = AudioLanguageOption.DEVICE
        secondaryPreferredAudioLanguage = null
        preferredSubtitleLanguage = SubtitleLanguageOption.NONE
        secondaryPreferredSubtitleLanguage = null
        subtitleStyle = SubtitleStyleState.DEFAULT
        streamReuseLastLinkEnabled = false
        streamReuseLastLinkCacheHours = 24
        decoderPriority = 1
        mapDV7ToHevc = false
        tunnelingEnabled = false
        streamAutoPlayMode = StreamAutoPlayMode.MANUAL
        streamAutoPlaySource = StreamAutoPlaySource.ALL_SOURCES
        streamAutoPlaySelectedAddons = emptySet()
        streamAutoPlaySelectedPlugins = emptySet()
        streamAutoPlayRegex = ""
        streamAutoPlayTimeoutSeconds = 3
        publish()
    }

    private fun loadFromDisk() {
        hasLoaded = true
        showLoadingOverlay = PlayerSettingsStorage.loadShowLoadingOverlay() ?: true
        preferredAudioLanguage =
            normalizeLanguageCode(PlayerSettingsStorage.loadPreferredAudioLanguage())
                ?: AudioLanguageOption.DEVICE
        secondaryPreferredAudioLanguage =
            normalizeLanguageCode(PlayerSettingsStorage.loadSecondaryPreferredAudioLanguage())
        preferredSubtitleLanguage =
            normalizeLanguageCode(PlayerSettingsStorage.loadPreferredSubtitleLanguage())
                ?: SubtitleLanguageOption.NONE
        secondaryPreferredSubtitleLanguage =
            normalizeLanguageCode(PlayerSettingsStorage.loadSecondaryPreferredSubtitleLanguage())
        subtitleStyle = SubtitleStyleState(
            textColor = subtitleColorFromStorage(PlayerSettingsStorage.loadSubtitleTextColor())
                ?: SubtitleStyleState.DEFAULT.textColor,
            outlineEnabled = PlayerSettingsStorage.loadSubtitleOutlineEnabled()
                ?: SubtitleStyleState.DEFAULT.outlineEnabled,
            fontSizeSp = PlayerSettingsStorage.loadSubtitleFontSizeSp()
                ?: SubtitleStyleState.DEFAULT.fontSizeSp,
            bottomOffset = PlayerSettingsStorage.loadSubtitleBottomOffset()
                ?: SubtitleStyleState.DEFAULT.bottomOffset,
        )
        streamReuseLastLinkEnabled = PlayerSettingsStorage.loadStreamReuseLastLinkEnabled() ?: false
        streamReuseLastLinkCacheHours = PlayerSettingsStorage.loadStreamReuseLastLinkCacheHours() ?: 24
        decoderPriority = PlayerSettingsStorage.loadDecoderPriority() ?: 1
        mapDV7ToHevc = PlayerSettingsStorage.loadMapDV7ToHevc() ?: false
        tunnelingEnabled = PlayerSettingsStorage.loadTunnelingEnabled() ?: false
        streamAutoPlayMode = PlayerSettingsStorage.loadStreamAutoPlayMode()
            ?.let { runCatching { StreamAutoPlayMode.valueOf(it) }.getOrNull() }
            ?: StreamAutoPlayMode.MANUAL
        streamAutoPlaySource = PlayerSettingsStorage.loadStreamAutoPlaySource()
            ?.let { runCatching { StreamAutoPlaySource.valueOf(it) }.getOrNull() }
            ?: StreamAutoPlaySource.ALL_SOURCES
        streamAutoPlaySelectedAddons = PlayerSettingsStorage.loadStreamAutoPlaySelectedAddons() ?: emptySet()
        streamAutoPlaySelectedPlugins = PlayerSettingsStorage.loadStreamAutoPlaySelectedPlugins() ?: emptySet()
        streamAutoPlayRegex = PlayerSettingsStorage.loadStreamAutoPlayRegex() ?: ""
        streamAutoPlayTimeoutSeconds = PlayerSettingsStorage.loadStreamAutoPlayTimeoutSeconds() ?: 3
        publish()
    }

    fun setShowLoadingOverlay(enabled: Boolean) {
        ensureLoaded()
        if (showLoadingOverlay == enabled) return
        showLoadingOverlay = enabled
        publish()
        PlayerSettingsStorage.saveShowLoadingOverlay(enabled)
    }

    fun setPreferredAudioLanguage(language: String) {
        ensureLoaded()
        val normalized = normalizeLanguageCode(language) ?: AudioLanguageOption.DEVICE
        if (preferredAudioLanguage == normalized) return
        preferredAudioLanguage = normalized
        publish()
        PlayerSettingsStorage.savePreferredAudioLanguage(normalized)
    }

    fun setSecondaryPreferredAudioLanguage(language: String?) {
        ensureLoaded()
        val normalized = normalizeLanguageCode(language)
        if (secondaryPreferredAudioLanguage == normalized) return
        secondaryPreferredAudioLanguage = normalized
        publish()
        PlayerSettingsStorage.saveSecondaryPreferredAudioLanguage(normalized)
    }

    fun setPreferredSubtitleLanguage(language: String) {
        ensureLoaded()
        val normalized = normalizeLanguageCode(language) ?: SubtitleLanguageOption.NONE
        if (preferredSubtitleLanguage == normalized) return
        preferredSubtitleLanguage = normalized
        publish()
        PlayerSettingsStorage.savePreferredSubtitleLanguage(normalized)
    }

    fun setSecondaryPreferredSubtitleLanguage(language: String?) {
        ensureLoaded()
        val normalized = normalizeLanguageCode(language)
        if (secondaryPreferredSubtitleLanguage == normalized) return
        secondaryPreferredSubtitleLanguage = normalized
        publish()
        PlayerSettingsStorage.saveSecondaryPreferredSubtitleLanguage(normalized)
    }

    fun setSubtitleStyle(style: SubtitleStyleState) {
        ensureLoaded()
        if (subtitleStyle == style) return
        subtitleStyle = style
        publish()
        PlayerSettingsStorage.saveSubtitleTextColor(style.textColor.toStorageHexString())
        PlayerSettingsStorage.saveSubtitleOutlineEnabled(style.outlineEnabled)
        PlayerSettingsStorage.saveSubtitleFontSizeSp(style.fontSizeSp)
        PlayerSettingsStorage.saveSubtitleBottomOffset(style.bottomOffset)
    }

    fun setStreamReuseLastLinkEnabled(enabled: Boolean) {
        ensureLoaded()
        if (streamReuseLastLinkEnabled == enabled) return
        streamReuseLastLinkEnabled = enabled
        publish()
        PlayerSettingsStorage.saveStreamReuseLastLinkEnabled(enabled)
    }

    fun setStreamReuseLastLinkCacheHours(hours: Int) {
        ensureLoaded()
        if (streamReuseLastLinkCacheHours == hours) return
        streamReuseLastLinkCacheHours = hours
        publish()
        PlayerSettingsStorage.saveStreamReuseLastLinkCacheHours(hours)
    }

    fun setDecoderPriority(priority: Int) {
        ensureLoaded()
        if (decoderPriority == priority) return
        decoderPriority = priority
        publish()
        PlayerSettingsStorage.saveDecoderPriority(priority)
    }

    fun setMapDV7ToHevc(enabled: Boolean) {
        ensureLoaded()
        if (mapDV7ToHevc == enabled) return
        mapDV7ToHevc = enabled
        publish()
        PlayerSettingsStorage.saveMapDV7ToHevc(enabled)
    }

    fun setTunnelingEnabled(enabled: Boolean) {
        ensureLoaded()
        if (tunnelingEnabled == enabled) return
        tunnelingEnabled = enabled
        publish()
        PlayerSettingsStorage.saveTunnelingEnabled(enabled)
    }

    fun setStreamAutoPlayMode(mode: StreamAutoPlayMode) {
        ensureLoaded()
        if (streamAutoPlayMode == mode) return
        streamAutoPlayMode = mode
        publish()
        PlayerSettingsStorage.saveStreamAutoPlayMode(mode.name)
    }

    fun setStreamAutoPlaySource(source: StreamAutoPlaySource) {
        ensureLoaded()
        if (streamAutoPlaySource == source) return
        streamAutoPlaySource = source
        publish()
        PlayerSettingsStorage.saveStreamAutoPlaySource(source.name)
    }

    fun setStreamAutoPlaySelectedAddons(addons: Set<String>) {
        ensureLoaded()
        if (streamAutoPlaySelectedAddons == addons) return
        streamAutoPlaySelectedAddons = addons
        publish()
        PlayerSettingsStorage.saveStreamAutoPlaySelectedAddons(addons)
    }

    fun setStreamAutoPlaySelectedPlugins(plugins: Set<String>) {
        ensureLoaded()
        if (streamAutoPlaySelectedPlugins == plugins) return
        streamAutoPlaySelectedPlugins = plugins
        publish()
        PlayerSettingsStorage.saveStreamAutoPlaySelectedPlugins(plugins)
    }

    fun setStreamAutoPlayRegex(regex: String) {
        ensureLoaded()
        if (streamAutoPlayRegex == regex) return
        streamAutoPlayRegex = regex
        publish()
        PlayerSettingsStorage.saveStreamAutoPlayRegex(regex)
    }

    fun setStreamAutoPlayTimeoutSeconds(seconds: Int) {
        ensureLoaded()
        if (streamAutoPlayTimeoutSeconds == seconds) return
        streamAutoPlayTimeoutSeconds = seconds
        publish()
        PlayerSettingsStorage.saveStreamAutoPlayTimeoutSeconds(seconds)
    }

    private fun publish() {
        _uiState.value = PlayerSettingsUiState(
            showLoadingOverlay = showLoadingOverlay,
            preferredAudioLanguage = preferredAudioLanguage,
            secondaryPreferredAudioLanguage = secondaryPreferredAudioLanguage,
            preferredSubtitleLanguage = preferredSubtitleLanguage,
            secondaryPreferredSubtitleLanguage = secondaryPreferredSubtitleLanguage,
            subtitleStyle = subtitleStyle,
            streamReuseLastLinkEnabled = streamReuseLastLinkEnabled,
            streamReuseLastLinkCacheHours = streamReuseLastLinkCacheHours,
            decoderPriority = decoderPriority,
            mapDV7ToHevc = mapDV7ToHevc,
            tunnelingEnabled = tunnelingEnabled,
            streamAutoPlayMode = streamAutoPlayMode,
            streamAutoPlaySource = streamAutoPlaySource,
            streamAutoPlaySelectedAddons = streamAutoPlaySelectedAddons,
            streamAutoPlaySelectedPlugins = streamAutoPlaySelectedPlugins,
            streamAutoPlayRegex = streamAutoPlayRegex,
            streamAutoPlayTimeoutSeconds = streamAutoPlayTimeoutSeconds,
        )
    }
}
