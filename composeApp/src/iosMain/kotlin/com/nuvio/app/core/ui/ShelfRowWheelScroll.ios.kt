package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope

// Touch scrolls horizontal lists natively, so there is nothing to add on iOS.
actual fun Modifier.rowWheelScroll(state: LazyListState, scope: CoroutineScope): Modifier = this

@Composable
actual fun BoxScope.RowScrollArrows(state: LazyListState, scope: CoroutineScope) {
    // No scroll-arrow affordance on touch — users swipe.
}
