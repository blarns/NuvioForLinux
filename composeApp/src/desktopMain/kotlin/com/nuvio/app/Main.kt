package com.nuvio.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.painter.BitmapPainter
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
            mpris?.close()
            DesktopPrefs.putFloat("window", "width", windowState.size.width.value)
            DesktopPrefs.putFloat("window", "height", windowState.size.height.value)
            exitApplication()
        },
        title = windowTitle,
        icon = appIcon,
        state = windowState,
    ) {
        App()
    }
}
