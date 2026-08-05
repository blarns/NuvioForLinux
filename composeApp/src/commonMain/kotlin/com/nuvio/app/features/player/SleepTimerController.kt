package com.nuvio.app.features.player

import com.nuvio.app.core.ui.NuvioToastController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * Sleep timer for the desktop player. Either pauses playback after a fixed number of
 * minutes, or stops after the current episode finishes (by suppressing auto-play-next).
 *
 * State is exposed as a StateFlow so the desktop menu bar can show the active choice.
 * Pausing is done through [pauseAction], which the desktop layer registers to hit the
 * player controller — keeping this controller platform-agnostic (it is inert on mobile,
 * where nothing registers a pause action or surfaces the menu).
 */
object SleepTimerController {

    sealed interface State {
        data object Off : State
        /** Timed pause armed for [minutes]. */
        data class Minutes(val minutes: Int) : State
        /** Stop once the current episode ends (auto-play-next suppressed). */
        data object AfterEpisode : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Registered by the desktop player: pauses the active player controller. */
    @Volatile var pauseAction: (() -> Unit)? = null

    private var countdownJob: Job? = null

    val presetMinutes = listOf(15, 30, 60, 90)

    fun startMinutes(minutes: Int) {
        countdownJob?.cancel()
        _state.value = State.Minutes(minutes)
        countdownJob = scope.launch {
            delay(minutes * 60_000L)
            pauseAction?.invoke()
            NuvioToastController.show("Sleep timer: playback paused")
            _state.value = State.Off
        }
    }

    fun startAfterEpisode() {
        countdownJob?.cancel()
        countdownJob = null
        _state.value = State.AfterEpisode
    }

    fun cancel() {
        countdownJob?.cancel()
        countdownJob = null
        if (_state.value != State.Off) {
            _state.value = State.Off
        }
    }

    /** True while "stop after this episode" is armed — used to suppress pre-emptive autoplay. */
    fun isAfterEpisodeArmed(): Boolean = _state.value is State.AfterEpisode

    /**
     * Called at the true end of an episode. If "after episode" was armed, consumes it
     * (toast + reset) and returns true so the caller skips auto-play-next.
     */
    fun consumeAfterEpisodeIfArmed(): Boolean {
        if (_state.value !is State.AfterEpisode) return false
        _state.value = State.Off
        NuvioToastController.show("Sleep timer: stopped after this episode")
        return true
    }
}
