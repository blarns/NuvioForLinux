package com.nuvio.app.features.player

internal object PlayerControlBridge {
    @Volatile var controller: PlayerEngineController? = null
    @Volatile var isPlaying: Boolean = false
    @Volatile var flushProgress: (() -> Unit)? = null
}
