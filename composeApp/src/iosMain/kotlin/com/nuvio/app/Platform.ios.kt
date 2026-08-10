package com.nuvio.app

import platform.UIKit.UIDevice

class IOSPlatform: Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

actual fun getPlatform(): Platform = IOSPlatform()

internal actual val isIos: Boolean = true

internal actual val isDesktop: Boolean = false

// Desktop-only: the JVM app flushes watch progress on window close. No-op here.
internal actual fun registerPlaybackFlushCallback(fn: () -> Unit) {}
internal actual fun unregisterPlaybackFlushCallback() {}