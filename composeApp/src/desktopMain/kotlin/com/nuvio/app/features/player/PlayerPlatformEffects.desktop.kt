package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntSize

@Composable
actual fun LockPlayerToLandscape() {}

@Composable
actual fun EnterImmersivePlayerMode(keepScreenAwake: Boolean) {}

@Composable
actual fun ManagePlayerPictureInPicture(isPlaying: Boolean, videoSize: IntSize) {}

@Composable
actual fun rememberPlayerGestureController(): PlayerGestureController? = null

// No picture-in-picture on the Linux desktop build.
@Composable
actual fun rememberIsInPictureInPicture(): Boolean = false
