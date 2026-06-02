package com.nuvio.app

class DesktopPlatform : Platform {
    override val name: String = "Linux Desktop (JVM)"
}

actual fun getPlatform(): Platform = DesktopPlatform()

internal actual val isIos: Boolean = false
internal actual val isDesktop: Boolean = true
