package com.nuvio.app.features.p2p

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Inert desktop engine: the fork does not bundle TorrServer, and AppFeaturePolicy.p2pEnabled
// is false on desktop so no shared code path should ever start a stream. startStream throws
// (rather than hanging) in case a future sync regresses the gating.
actual object P2pStreamingEngine {
    private val mutableState = MutableStateFlow<P2pStreamingState>(P2pStreamingState.Idle)

    actual val state: StateFlow<P2pStreamingState> = mutableState.asStateFlow()

    actual fun warmup() = Unit

    actual fun cooldownWarmup() = Unit

    actual suspend fun startStream(request: P2pStreamRequest): String {
        throw P2pStreamingException("P2P streaming is not available on the Linux desktop build")
    }

    actual fun stopStream() {
        mutableState.value = P2pStreamingState.Idle
    }

    actual fun shutdown() {
        mutableState.value = P2pStreamingState.Idle
    }
}
