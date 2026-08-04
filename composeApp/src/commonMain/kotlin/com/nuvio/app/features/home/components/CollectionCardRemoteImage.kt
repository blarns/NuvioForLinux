package com.nuvio.app.features.home.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

@Composable
internal expect fun CollectionCardRemoteImage(
    imageUrl: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    animateIfPossible: Boolean = false,
    // Desktop fork: collection tiles cross-fade to animated art on hover. Ignored on touch.
    restImageUrl: String? = null,
    hoverImageUrl: String? = null,
    hovered: Boolean = false,
)