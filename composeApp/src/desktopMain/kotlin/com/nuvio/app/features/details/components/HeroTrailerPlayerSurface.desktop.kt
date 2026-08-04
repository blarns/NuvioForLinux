package com.nuvio.app.features.details.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.nuvio.app.features.player.PlatformPlayerSurface
import com.nuvio.app.features.player.PlayerResizeMode

// Reuses the VLCJ surface. The hero trailer is decorative: it never takes the media
// session, and any failure falls back to the static backdrop via onError.
@Composable
actual fun HeroTrailerPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    playWhenReady: Boolean,
    muted: Boolean,
    modifier: Modifier,
    onReady: () -> Unit,
    onEnded: () -> Unit,
    onError: () -> Unit,
) {
    PlatformPlayerSurface(
        sourceUrl = sourceUrl,
        sourceAudioUrl = sourceAudioUrl,
        modifier = modifier,
        playWhenReady = playWhenReady,
        resizeMode = PlayerResizeMode.Fill,
        onControllerReady = { controller ->
            if (muted) controller.setVolume(0f) else controller.setVolume(1f)
        },
        onSnapshot = { snapshot ->
            if (snapshot.isEnded) onEnded()
        },
        onError = { onError() },
    )
    LaunchedEffect(sourceUrl) { onReady() }
}
