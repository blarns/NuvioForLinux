package com.nuvio.app.core.ui

import androidx.compose.ui.Modifier

/**
 * Fires [handler] on a secondary (right) mouse button press on desktop.
 * No-op on Android and iOS — those platforms use long-press for context menus.
 */
expect fun Modifier.onRightClick(handler: () -> Unit): Modifier
