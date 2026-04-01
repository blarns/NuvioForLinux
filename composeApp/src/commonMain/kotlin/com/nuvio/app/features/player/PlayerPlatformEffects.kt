package com.nuvio.app.features.player

import androidx.compose.runtime.Composable

interface PlayerGestureController {
    fun currentBrightness(): Float?
    fun setBrightness(level: Float): Float?
    fun currentVolume(): PlayerAudioLevel?
    fun setVolume(level: Float): PlayerAudioLevel?
}

data class PlayerAudioLevel(
    val fraction: Float,
    val isMuted: Boolean,
)

@Composable
expect fun LockPlayerToLandscape()

@Composable
expect fun EnterImmersivePlayerMode()

@Composable
expect fun rememberPlayerGestureController(): PlayerGestureController?
