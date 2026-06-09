package com.nuvio.app.features.details.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.player.PlatformPlayerSurface
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import com.nuvio.app.isDesktop
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrailerPlayerPopup(
    visible: Boolean,
    trailerTitle: String,
    trailerType: String,
    contentTitle: String,
    playbackSource: TrailerPlaybackSource?,
    isLoading: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null,
) {
    if (!visible) return

    val headerType = trailerType.trim().ifBlank { stringResource(Res.string.detail_tab_trailer) }
    val headerSubtitle = buildList {
        if (trailerTitle.isNotBlank() && !trailerTitle.equals(headerType, ignoreCase = true)) {
            add(trailerTitle)
        }
        if (contentTitle.isNotBlank()) {
            add(contentTitle)
        }
    }.joinToString(separator = " • ")

    var playerError by remember(playbackSource?.videoUrl, playbackSource?.audioUrl) {
        mutableStateOf<String?>(null)
    }
    val activeError = errorMessage ?: playerError

    if (isDesktop) {
        // Desktop: a top-anchored overlay that slides down from the top, instead of a bottom
        // sheet (which renders as a small panel at the bottom of a wide desktop window).
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        Popup(
            // Position at the window origin and let the content fill the whole window —
            // a modal AlertDialog caps content width on desktop, which kept the card tiny.
            popupPositionProvider = remember {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset = IntOffset.Zero
                }
            },
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true, dismissOnClickOutside = true, dismissOnBackPress = true),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
                contentAlignment = Alignment.TopCenter,
            ) {
                AnimatedVisibility(
                    visible = shown,
                    // Fill the overlay width so the card can use it; AnimatedVisibility otherwise
                    // wraps to its content, which collapsed the card back to a small size.
                    modifier = Modifier.fillMaxWidth(),
                    enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                ) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        Surface(
                            modifier = Modifier
                                .widthIn(max = 960.dp)
                                .fillMaxWidth()
                                .padding(top = 28.dp, start = 24.dp, end = 24.dp)
                                // Consume clicks on the card so they don't fall through to the dismiss scrim.
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {},
                                ),
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            tonalElevation = 8.dp,
                            shadowElevation = 16.dp,
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                TrailerSheetBody(
                                    headerType = headerType,
                                    headerSubtitle = headerSubtitle,
                                    isLoading = isLoading,
                                    activeError = activeError,
                                    playbackSource = playbackSource,
                                    onClose = onDismiss,
                                    onRetry = onRetry,
                                    onPlayerError = { playerError = it },
                                )
                            }
                        }
                    }
                }
            }
        }
        return
    }

    // Mobile: bottom sheet (slides up from the bottom).
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val dismissSheet: () -> Unit = {
        coroutineScope.launch {
            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
        }
    }
    NuvioModalBottomSheet(
        onDismissRequest = dismissSheet,
        sheetState = sheetState,
        sheetMaxWidth = 960.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = nuvioSafeBottomPadding(14.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TrailerSheetBody(
                headerType = headerType,
                headerSubtitle = headerSubtitle,
                isLoading = isLoading,
                activeError = activeError,
                playbackSource = playbackSource,
                onClose = dismissSheet,
                onRetry = onRetry,
                onPlayerError = { playerError = it },
            )
        }
    }
}

@Composable
private fun TrailerSheetBody(
    headerType: String,
    headerSubtitle: String,
    isLoading: Boolean,
    activeError: String?,
    playbackSource: TrailerPlaybackSource?,
    onClose: () -> Unit,
    onRetry: (() -> Unit)?,
    onPlayerError: (String?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = headerType,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (headerSubtitle.isNotBlank()) {
                Text(
                    text = headerSubtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(Res.string.trailer_close),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    NuvioBottomSheetDivider()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.scrim)
            .aspectRatio(16f / 9f),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isLoading -> {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

            activeError != null -> {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.trailer_unable_to_play),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = activeError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (onRetry != null) {
                        TextButton(onClick = onRetry) {
                            Text(stringResource(Res.string.action_retry))
                        }
                    }
                }
            }

            playbackSource != null -> {
                PlatformPlayerSurface(
                    sourceUrl = playbackSource.videoUrl,
                    sourceAudioUrl = playbackSource.audioUrl,
                    useYoutubeChunkedPlayback = true,
                    // fillMaxSize (not just width): the desktop surface is pure Compose with no
                    // intrinsic height, so width-only collapses it to zero height (blank video)
                    // inside this 16:9 box. Mobile's AndroidView/UIKitView filled it anyway.
                    modifier = Modifier.fillMaxSize(),
                    playWhenReady = true,
                    resizeMode = PlayerResizeMode.Fit,
                    useNativeController = true,
                    onControllerReady = {},
                    onSnapshot = {},
                    onError = onPlayerError,
                )
            }
        }
    }
}
