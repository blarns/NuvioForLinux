package com.nuvio.app

internal object DesktopWindowState {
    var toggleFullscreen: (() -> Unit)? = null
    // Bring the main window to the foreground (system tray "Show" action).
    var bringToFront: (() -> Unit)? = null
    // Request application exit (system tray "Quit" action).
    var requestExit: (() -> Unit)? = null
}
