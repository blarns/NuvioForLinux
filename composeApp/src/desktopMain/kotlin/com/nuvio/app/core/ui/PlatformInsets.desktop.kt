package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Desktop windows have no physical display cutout.
@Composable
internal actual fun platformPhysicalTopInset(): Dp = 0.dp
