package com.nuvio.app.core.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.Color

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {}

actual fun platformExitApp() {}

internal actual val nuvioPlatformExtraTopPadding: Dp = 0.dp
internal actual val nuvioPlatformExtraBottomPadding: Dp = 0.dp
internal actual val nuvioBottomNavigationExtraVerticalPadding: Dp = 0.dp
@Composable
internal actual fun nuvioBottomNavigationBarInsets(): WindowInsets = WindowInsets(0, 0, 0, 0)

internal actual fun isLiquidGlassNativeTabBarSupported(): Boolean = false
internal actual fun publishLiquidGlassNativeTabBarEnabled(enabled: Boolean) {}
internal actual fun publishNativeTabBarVisible(visible: Boolean) {}
internal actual fun publishNativeSelectedTab(tabName: String) {}
internal actual fun publishNativeTabAccentColor(hexColor: String) {}
internal actual fun publishNativeProfileTabIcon(name: String?, avatarColorHex: String?, avatarImageUrl: String?, avatarBackgroundColorHex: String?) {}


@Composable
actual fun appIconPainter(icon: AppIconResource): Painter = when (icon) {
    AppIconResource.PlayerPlay -> rememberVectorPainter(Icons.Rounded.PlayArrow)
    AppIconResource.PlayerPause -> rememberVectorPainter(Icons.Rounded.Pause)
    AppIconResource.PlayerAspectRatio -> rememberVectorPainter(Icons.Rounded.CropFree)
    AppIconResource.PlayerSubtitles -> rememberVectorPainter(Icons.Rounded.Subtitles)
    AppIconResource.PlayerAudioFilled -> rememberVectorPainter(Icons.Rounded.Audiotrack)
    AppIconResource.LibraryAddPlus -> rememberVectorPainter(Icons.Rounded.Add)
}
