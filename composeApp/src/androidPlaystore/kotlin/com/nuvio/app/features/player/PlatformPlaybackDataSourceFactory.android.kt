package com.nuvio.app.features.player

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource

internal object PlatformPlaybackDataSourceFactory {
    fun create(
        defaultRequestHeaders: Map<String, String>,
        defaultResponseHeaders: Map<String, String>,
        useYoutubeChunkedPlayback: Boolean,
    ): DataSource.Factory {
        val baseFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(defaultRequestHeaders)
        return if (defaultResponseHeaders.isEmpty()) {
            baseFactory
        } else {
            ResponseHeaderOverridingDataSourceFactory(
                upstreamFactory = baseFactory,
                defaultResponseHeaders = defaultResponseHeaders,
            )
        }
    }
}