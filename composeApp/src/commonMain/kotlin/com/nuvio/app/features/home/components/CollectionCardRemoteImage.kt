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
    // Desktop-only mouse hover-to-focus: [restImageUrl] is the static cover shown at rest and
    // [hoverImageUrl] the animated "focus" art played while [hovered]. Hover is detected by the
    // caller on an ancestor of the click overlay (which would otherwise swallow it). Mobile/iOS
    // ignore these and keep showing [imageUrl].
    restImageUrl: String? = null,
    hoverImageUrl: String? = null,
    hovered: Boolean = false,
)