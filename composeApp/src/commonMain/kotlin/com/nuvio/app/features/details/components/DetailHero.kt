package com.nuvio.app.features.details.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import coil3.compose.AsyncImage
import com.nuvio.app.features.details.MetaDetails
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun DetailHero(
    meta: MetaDetails,
    isTablet: Boolean = false,
    scrollOffset: Int = 0,
    contentMaxWidth: Dp = 560.dp,
    onHeightChanged: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
    ) {
        val heroHeight = detailHeroHeight(maxWidth, isTablet)
        var logoLoadError by remember(meta.id, meta.logo) {
            mutableStateOf(false)
        }
        val logoUrl = meta.logo?.takeIf { it.isNotBlank() }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight)
                .onSizeChanged { onHeightChanged(it.height) }
                .graphicsLayer {
                    clip = true
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                val imageUrl = meta.background ?: meta.poster
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = meta.name,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationY = scrollOffset * 0.5f
                                scaleX = 1.08f
                                scaleY = 1.08f
                            },
                        alignment = if (isTablet) Alignment.TopCenter else Alignment.Center,
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface),
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isTablet) 360.dp else 320.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.00f to Color.Transparent,
                                    0.16f to MaterialTheme.colorScheme.background.copy(alpha = 0.04f),
                                    0.32f to MaterialTheme.colorScheme.background.copy(alpha = 0.14f),
                                    0.50f to MaterialTheme.colorScheme.background.copy(alpha = 0.34f),
                                    0.68f to MaterialTheme.colorScheme.background.copy(alpha = 0.62f),
                                    0.84f to MaterialTheme.colorScheme.background.copy(alpha = 0.84f),
                                    1.00f to MaterialTheme.colorScheme.background,
                                ),
                            ),
                        ),
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (isTablet) 32.dp else 18.dp)
                        .padding(bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (logoUrl != null && !logoLoadError) {
                        AsyncImage(
                            model = logoUrl,
                            contentDescription = stringResource(Res.string.detail_logo_content_description, meta.name),
                            modifier = Modifier
                                .fillMaxWidth(if (isTablet) 0.56f else 0.6f)
                                .widthIn(max = contentMaxWidth)
                                .height(if (isTablet) 72.dp else 80.dp),
                            alignment = Alignment.Center,
                            contentScale = ContentScale.Fit,
                            onError = { logoLoadError = true },
                        )
                    } else {
                        Text(
                            text = meta.name,
                            style = if (isTablet) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center,
                        )
                    }

                    if (meta.genres.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = meta.genres.take(3).joinToString(" \u2022 "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

private fun detailHeroHeight(maxWidth: Dp, isTablet: Boolean): Dp =
    if (!isTablet) {
        (maxWidth * 1.33f).coerceIn(420.dp, 760.dp)
    } else {
        (maxWidth * 0.42f).coerceIn(300.dp, 420.dp)
    }
