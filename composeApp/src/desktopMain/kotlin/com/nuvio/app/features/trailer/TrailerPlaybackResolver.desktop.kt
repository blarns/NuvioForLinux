package com.nuvio.app.features.trailer

actual object TrailerPlaybackResolver {
    private val extractor by lazy { InAppYouTubeExtractor() }
    actual suspend fun resolveFromYouTubeUrl(youtubeUrl: String): TrailerPlaybackSource? =
        extractor.extractPlaybackSource(youtubeUrl)
}
