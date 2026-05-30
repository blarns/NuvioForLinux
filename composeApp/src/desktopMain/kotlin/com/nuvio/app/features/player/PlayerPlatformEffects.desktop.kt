package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntSize

@Composable
actual fun LockPlayerToLandscape() {}

@Composable
actual fun EnterImmersivePlayerMode(keepScreenAwake: Boolean) {}

@Composable
actual fun ManagePlayerPictureInPicture(isPlaying: Boolean, playerSize: IntSize) {}

@Composable
actual fun rememberPlayerGestureController(): PlayerGestureController? = null
