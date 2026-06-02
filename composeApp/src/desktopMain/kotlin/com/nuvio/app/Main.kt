package com.nuvio.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.nuvio.app.features.player.PlayerLaunchStore

fun main() = application {
    System.setProperty("compose.interop.blending", "true")
    val mediaTitle by PlayerLaunchStore.currentTitle.collectAsState()
    val windowTitle = if (mediaTitle != null) "Nuvio — $mediaTitle" else "Nuvio"
    val mpris = runCatching {
        startMpris2(onPlay = {}, onPause = {}, onStop = {})
    }.getOrNull()
    Window(
        onCloseRequest = {
            mpris?.close()
            exitApplication()
        },
        title = windowTitle,
    ) {
        App()
    }
}
