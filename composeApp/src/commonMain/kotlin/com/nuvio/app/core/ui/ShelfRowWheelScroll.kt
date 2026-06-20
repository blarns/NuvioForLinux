package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope

/**
 * Lets a horizontal row (a [androidx.compose.foundation.lazy.LazyRow]) be scrolled with the mouse on
 * desktop: a horizontal trackpad swipe, or Shift + the vertical wheel, scrolls [state] horizontally.
 * A plain vertical wheel is deliberately left alone so it still scrolls the surrounding vertical
 * page — otherwise the home screen (a column of rows) couldn't be scrolled vertically.
 *
 * No-op on Android and iOS, which scroll horizontal lists by touch.
 */
expect fun Modifier.rowWheelScroll(state: LazyListState, scope: CoroutineScope): Modifier

/**
 * Floating left/right scroll-arrow buttons overlaid on a horizontal row so desktop users can scroll
 * it without Shift + wheel. Each arrow only appears when [state] can scroll that direction. Call
 * inside the [BoxScope] that wraps the row. No-op on Android and iOS (touch swipes instead).
 */
@Composable
expect fun BoxScope.RowScrollArrows(state: LazyListState, scope: CoroutineScope)
