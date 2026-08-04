package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

// No native sheet on the Linux desktop — the shared Compose sheet is used instead.
internal actual val usesNativeNuvioBottomSheet: Boolean = false

internal actual fun dismissNativeNuvioBottomSheet() {}

@Composable
internal actual fun NuvioNativeModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    containerColor: Color,
    contentColor: Color,
    showDragHandle: Boolean,
    fullHeight: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Never invoked: usesNativeNuvioBottomSheet is false, so callers take the Compose path.
}
