package com.nuvio.app

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

internal expect val isIos: Boolean
internal expect val isDesktop: Boolean

internal expect fun registerPlaybackFlushCallback(fn: () -> Unit)
internal expect fun unregisterPlaybackFlushCallback()