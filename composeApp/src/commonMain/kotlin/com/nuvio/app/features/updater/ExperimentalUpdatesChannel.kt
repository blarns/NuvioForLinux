package com.nuvio.app.features.updater

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fork-only. Single source of truth for the experimental (alpha) channel opt-in.
 *
 * The persisted flag has two writers — the Settings toggle and "Return to stable release", which
 * switches the channel off on the user's behalf — so a screen that cached the value in `remember`
 * would keep showing the switch as on after a return-to-stable. Everything reads and writes here
 * instead; [AppUpdaterPlatform] stays the storage, this stays the state.
 */
object ExperimentalUpdatesChannel {
    private val _enabled = MutableStateFlow(
        AppUpdaterPlatform.supportsExperimentalChannel && AppUpdaterPlatform.getExperimentalUpdatesEnabled(),
    )

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    val isEnabled: Boolean get() = _enabled.value

    fun set(value: Boolean) {
        if (!AppUpdaterPlatform.supportsExperimentalChannel) return
        AppUpdaterPlatform.setExperimentalUpdatesEnabled(value)
        _enabled.value = value
    }
}
