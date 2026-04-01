package com.nuvio.app.features.trakt

internal expect object TraktPlatformClock {
    fun nowEpochMs(): Long
}
