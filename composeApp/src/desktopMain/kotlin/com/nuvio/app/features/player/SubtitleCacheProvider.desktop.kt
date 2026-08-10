package com.nuvio.app.features.player

// External players on Linux are launched with the subtitle URLs as-is; they fetch
// over HTTP themselves, so no local caching step is required.
actual object SubtitleCacheProvider {
    actual suspend fun cacheForExternalPlayer(subtitles: List<SubtitleInput>): List<SubtitleInput>? =
        subtitles
}
