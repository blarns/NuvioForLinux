package com.nuvio.app.features.player

internal object PlayerControlBridge {
    @Volatile var controller: PlayerEngineController? = null
    @Volatile var isPlaying: Boolean = false
    @Volatile var flushProgress: (() -> Unit)? = null

    // Live playback position/duration (ms), fed from the desktop player's snapshot loop.
    // Consumed by the MPRIS handler for Position/Metadata length.
    @Volatile var positionMs: Long = 0L
    @Volatile var durationMs: Long = 0L

    // Set to true once a media player exists and has not been torn down. Lets MPRIS report
    // "Stopped" vs "Paused" correctly (isPlaying alone can't distinguish them).
    @Volatile var hasMedia: Boolean = false

    // The MPRIS handler registers here so the snapshot loop can nudge it to re-emit
    // PropertiesChanged when the now-playing title/status/duration actually changes.
    @Volatile var onNowPlayingChanged: (() -> Unit)? = null
}
