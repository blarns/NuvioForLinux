package com.nuvio.app.features.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class AppIconSettingsState(
    val selected: AppIconOption = AppIconOption.ORIGINAL,
    val pending: AppIconOption? = null,
    val changeFailed: Boolean = false,
    val blackBackground: Boolean = true,
)

internal object AppIconRepository {
    private val _state = MutableStateFlow(AppIconSettingsState())
    val state: StateFlow<AppIconSettingsState> = _state.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        _state.value = AppIconSettingsState(
            selected = AppIconOption.fromPlatformName(AppIconPlatform.currentIconName()),
            blackBackground = AppIconPlatform.currentBlackBackground(),
        )
    }

    suspend fun select(icon: AppIconOption) {
        ensureLoaded()
        val current = _state.value
        if (current.pending != null || current.selected == icon) return

        _state.value = current.copy(
            pending = icon,
            changeFailed = false,
        )

        val changed = runCatching {
            AppIconPlatform.activateIcon(icon.platformName)
        }.getOrDefault(false)
        _state.value = if (changed) {
            // copy(), not a fresh AppIconSettingsState: constructing one resets blackBackground
            // to its default, so picking an icon silently turned the user's background choice
            // back on. Upstream has this bug; the fork does not.
            current.copy(selected = icon, pending = null, changeFailed = false)
        } else {
            current.copy(pending = null, changeFailed = true)
        }
    }

    fun setBlackBackground(enabled: Boolean) {
        ensureLoaded()
        if (_state.value.blackBackground == enabled) return
        val changed = runCatching { AppIconPlatform.setBlackBackground(enabled) }.getOrDefault(false)
        if (changed) _state.value = _state.value.copy(blackBackground = enabled)
    }

    fun clearFailure() {
        val current = _state.value
        if (!current.changeFailed) return
        _state.value = current.copy(changeFailed = false)
    }
}
