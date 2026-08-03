package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// Desktop has no external-player intent support (buildIntent always returns NotConfigured),
// so the launcher is a no-op that reports it did not launch.
@Composable
actual fun rememberExternalPlayerLauncher(
    onResult: (ExternalPlaybackResult?) -> Unit,
): (ExternalPlayerIntentResult.Success) -> Boolean {
    return remember { { _: ExternalPlayerIntentResult.Success -> false } }
}
