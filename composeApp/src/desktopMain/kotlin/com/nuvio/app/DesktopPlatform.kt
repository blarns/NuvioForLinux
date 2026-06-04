package com.nuvio.app

class DesktopPlatform : Platform {
    override val name: String = "Linux Desktop (JVM)"
}

actual fun getPlatform(): Platform = DesktopPlatform()

internal actual val isIos: Boolean = false
internal actual val isDesktop: Boolean = true

internal actual fun registerPlaybackFlushCallback(fn: () -> Unit) {
    com.nuvio.app.features.player.PlayerControlBridge.flushProgress = fn
}
internal actual fun unregisterPlaybackFlushCallback() {
    com.nuvio.app.features.player.PlayerControlBridge.flushProgress = null
}
