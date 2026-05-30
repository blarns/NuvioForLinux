package com.nuvio.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    System.setProperty("compose.interop.blending", "true")
    Window(
        onCloseRequest = ::exitApplication,
        title = "Nuvio",
    ) {
        App()
    }
}
