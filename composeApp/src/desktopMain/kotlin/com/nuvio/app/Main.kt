package com.nuvio.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.nuvio.app.desktop.DesktopPrefs
import com.nuvio.app.features.player.PlayerControlBridge
import com.nuvio.app.features.player.PlayerLaunchStore

fun main() = application {
    System.setProperty("compose.interop.blending", "true")
    val mediaTitle by PlayerLaunchStore.currentTitle.collectAsState()
    val windowTitle = if (mediaTitle != null) "Nuvio — $mediaTitle" else "Nuvio"
    val appIcon = runCatching { BitmapPainter(useResource("nuvio-icon.png", ::loadImageBitmap)) }.getOrNull()
    val windowState = rememberWindowState(
        width = (DesktopPrefs.getFloat("window", "width") ?: 1280f).dp,
        height = (DesktopPrefs.getFloat("window", "height") ?: 720f).dp,
    )
    DesktopWindowState.toggleFullscreen = {
        windowState.placement = if (windowState.placement == WindowPlacement.Fullscreen)
            WindowPlacement.Floating else WindowPlacement.Fullscreen
    }
    val mpris = runCatching {
        startMpris2(
            onPlay  = { PlayerControlBridge.controller?.play() },
            onPause = { PlayerControlBridge.controller?.pause() },
            onStop  = { PlayerControlBridge.controller?.pause() },
        )
    }.getOrNull()
    Window(
        onCloseRequest = {
            PlayerControlBridge.flushProgress?.invoke()
            // D-Bus teardown can block for seconds; run it on a daemon thread so the
            // AWT EDT (and thus the window close) is never blocked by MPRIS cleanup.
            val m = mpris
            if (m != null) Thread(null, { try { m.close() } catch (_: Exception) {} }, "mpris-close", 0).also {
                it.isDaemon = true
                it.start()
            }
            DesktopPrefs.putFloat("window", "width", windowState.size.width.value)
            DesktopPrefs.putFloat("window", "height", windowState.size.height.value)
            exitApplication()
        },
        onKeyEvent = { keyEvent ->
            if (keyEvent.type != KeyEventType.KeyDown) return@Window false
            val ctrl = PlayerControlBridge.controller ?: return@Window false
            when (keyEvent.key) {
                Key.Spacebar -> { if (PlayerControlBridge.isPlaying) ctrl.pause() else ctrl.play(); true }
                Key.DirectionLeft  -> { ctrl.seekBy(-10_000L); true }
                Key.DirectionRight -> { ctrl.seekBy(+10_000L); true }
                Key.DirectionUp    -> { ctrl.currentVolume()?.let { ctrl.setVolume((it.fraction + 0.05f).coerceAtMost(1f)) }; true }
                Key.DirectionDown  -> { ctrl.currentVolume()?.let { ctrl.setVolume((it.fraction - 0.05f).coerceAtLeast(0f)) }; true }
                Key.M -> { ctrl.currentVolume()?.let { ctrl.setVolume(if (it.isMuted) 0.5f else 0f) }; true }
                Key.F -> { DesktopWindowState.toggleFullscreen?.invoke(); true }
                else -> false
            }
        },
        title = windowTitle,
        icon = appIcon,
        state = windowState,
    ) {
        App()
    }
}
