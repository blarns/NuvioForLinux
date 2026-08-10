package com.nuvio.app.core.build

actual object AppFeaturePolicy {
    actual val pluginsEnabled: Boolean = true
    actual val p2pEnabled: Boolean = true
    actual val trailerPlaybackMode: TrailerPlaybackMode = TrailerPlaybackMode.IN_APP
    actual val inAppUpdaterEnabled: Boolean = true
    actual val supportersContributorsPageEnabled: Boolean = true
    actual val accountDeletionEnabled: Boolean = true
    actual val personalMediaAddonCopyEnabled: Boolean = true
    actual val heroTrailerPlaybackSupported: Boolean = true
    actual val imdbRatingLogoEnabled: Boolean = true
    // Android-only concept (foreground service); nothing to run on the desktop.
    actual val mediaPlaybackForegroundServiceEnabled: Boolean = false
}