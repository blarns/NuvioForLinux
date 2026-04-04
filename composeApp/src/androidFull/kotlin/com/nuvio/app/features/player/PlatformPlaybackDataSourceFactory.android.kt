package com.nuvio.app.features.player

import androidx.media3.datasource.DataSource
import com.nuvio.app.features.trailer.YoutubeChunkedDataSourceFactory

internal object PlatformPlaybackDataSourceFactory {
    fun create(defaultRequestHeaders: Map<String, String>): DataSource.Factory =
        YoutubeChunkedDataSourceFactory(defaultRequestHeaders = defaultRequestHeaders)
}