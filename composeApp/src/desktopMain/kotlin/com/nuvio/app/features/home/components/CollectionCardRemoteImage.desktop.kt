package com.nuvio.app.features.home.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

@Composable
internal actual fun CollectionCardRemoteImage(
    imageUrl: String,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
    animateIfPossible: Boolean,
) {
    // Route through Coil's singleton ImageLoader so collection posters come from the shared
    // memory + disk cache. The previous desktop implementation hand-rolled HttpURLConnection +
    // ImageIO with no cache, so each poster was re-downloaded and re-decoded every time its row
    // scrolled back into view — appearing to "unload" and reload. AsyncImage keeps them cached
    // (see configurePlatformImageLoader in PlatformImageLoader.desktop.kt for the disk cache).
    AsyncImage(
        model = imageUrl,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}
