package com.nuvio.app.features.trakt

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.Color

@Composable
actual fun traktBrandPainter(asset: TraktBrandAsset): Painter = ColorPainter(Color.Transparent)
