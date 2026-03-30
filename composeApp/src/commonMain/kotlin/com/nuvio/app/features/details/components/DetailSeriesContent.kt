package com.nuvio.app.features.details.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import co.touchlab.kermit.Logger
import com.nuvio.app.core.ui.NuvioAnimatedWatchedBadge
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.metaVideoSeasonEpisodeComparator
import com.nuvio.app.features.details.normalizeSeasonNumber
import com.nuvio.app.features.details.seasonSortKey
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId

private val log = Logger.withTag("SeriesContent")

@Composable
fun DetailSeriesContent(
    meta: MetaDetails,
    modifier: Modifier = Modifier,
    progressByVideoId: Map<String, WatchProgressEntry> = emptyMap(),
    watchedKeys: Set<String> = emptySet(),
    onEpisodeClick: ((MetaVideo) -> Unit)? = null,
    onEpisodeLongPress: ((MetaVideo) -> Unit)? = null,
) {
    val hasVideos = meta.videos.isNotEmpty()
    if (meta.type != "series" && !hasVideos) return

    if (meta.videos.isEmpty()) {
        DetailSection(
            title = "Episodes",
            modifier = modifier,
        ) {
            Text(
                text = when {
                    meta.status.equals("Not yet aired", ignoreCase = true) || meta.hasScheduledVideos ->
                        "Episodes have not been published by this addon yet."
                    else ->
                        "This addon did not provide episode metadata for this series."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val groupedEpisodes = remember(meta.videos) {
        log.d { "videos count=${meta.videos.size}, type=${meta.type}" }
        val withSeasonOrEp = meta.videos.filter { it.season != null || it.episode != null }
        log.d { "videos with season/episode=${withSeasonOrEp.size}" }
        if (meta.videos.isNotEmpty() && withSeasonOrEp.isEmpty()) {
            log.w { "All videos lack season/episode fields! First: ${meta.videos.first()}" }
        }
        if (withSeasonOrEp.isNotEmpty()) {
            withSeasonOrEp
                .sortedWith(metaVideoSeasonEpisodeComparator)
                .groupBy { normalizeSeasonNumber(it.season) }
        } else if (meta.type != "series" && meta.videos.isNotEmpty()) {
            // For non-series types (e.g. "other"), show videos without season/episode as a flat list
            mapOf(normalizeSeasonNumber(null) to meta.videos)
        } else {
            emptyMap()
        }
    }

    if (groupedEpisodes.isEmpty()) {
        if (meta.type == "series") {
            DetailSection(
                title = "Episodes",
                modifier = modifier,
            ) {
                Text(
                    text = "This addon returned videos for the series, but none included season or episode numbers.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    val seasons = groupedEpisodes.keys.sortedBy(::seasonSortKey)
    val defaultSeason = seasons.first()
    var selectedSeason by rememberSaveable(meta.id) { mutableStateOf(defaultSeason) }
    val currentSeason = selectedSeason.takeIf { it in groupedEpisodes } ?: defaultSeason
    val episodes = groupedEpisodes.getValue(currentSeason)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val sizing = seriesContentSizing(maxWidth.value)

        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (seasons.size > 1) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Seasons",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = sizing.seasonHeaderSize,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onBackground,
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(sizing.seasonChipGap),
                    ) {
                        seasons.forEach { season ->
                            val isSelected = season == currentSeason
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(sizing.seasonChipRadius))
                                    .background(
                                        if (isSelected) {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                        } else {
                                            Color.Transparent
                                        },
                                    )
                                    .clickable { selectedSeason = season }
                                    .padding(
                                        horizontal = sizing.seasonChipHorizontalPadding,
                                        vertical = sizing.seasonChipVerticalPadding,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = season.label(),
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = sizing.seasonChipTextSize,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                    ),
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onBackground
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                }
            }

            val sectionTitle = if (meta.type != "series" && seasons.size == 1 && currentSeason <= 0) {
                "Videos"
            } else {
                currentSeason.label()
            }
            DetailSectionTitle(
                title = sectionTitle,
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(sizing.cardGap),
            ) {
                episodes.forEach { episode ->
                    val episodeVideoId = buildPlaybackVideoId(
                        parentMetaId = meta.id,
                        seasonNumber = episode.season,
                        episodeNumber = episode.episode,
                        fallbackVideoId = episode.id,
                    )
                    EpisodeCard(
                        video = episode,
                        fallbackImage = meta.background ?: meta.poster,
                        progressEntry = progressByVideoId[episodeVideoId],
                        isWatched = progressByVideoId[episodeVideoId]?.isCompleted == true ||
                            watchedKeys.contains(
                                com.nuvio.app.features.watched.watchedItemKey(
                                    type = meta.type,
                                    id = meta.id,
                                    season = episode.season,
                                    episode = episode.episode,
                                ),
                            ),
                        sizing = sizing,
                        onClick = { onEpisodeClick?.invoke(episode) },
                        onLongPress = { onEpisodeLongPress?.invoke(episode) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeCard(
    video: MetaVideo,
    fallbackImage: String?,
    progressEntry: WatchProgressEntry?,
    isWatched: Boolean,
    sizing: SeriesContentSizing,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
) {
    val cardShape = RoundedCornerShape(sizing.cardRadius)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(sizing.cardHeight)
            .clip(cardShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.1f),
                shape = cardShape,
            )
            .combinedClickable(
                enabled = onClick != null || onLongPress != null,
                onClick = { onClick?.invoke() },
                onLongClick = onLongPress,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Image area - fixed width matching card height per spec
            Box(
                modifier = Modifier
                    .width(sizing.imageWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = sizing.cardRadius, bottomStart = sizing.cardRadius)),
            ) {
                val imageUrl = video.thumbnail ?: fallbackImage
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = video.title,
                        modifier = Modifier.fillMaxSize(),
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
                        .align(Alignment.TopStart)
                        .padding(start = 8.dp, top = 8.dp)
                        .clip(RoundedCornerShape(sizing.badgeRadius))
                        .background(Color.Black.copy(alpha = 0.85f))
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(sizing.badgeRadius),
                        )
                        .padding(
                            horizontal = sizing.badgeHorizontalPadding,
                            vertical = sizing.badgeVerticalPadding,
                        ),
                ) {
                    Text(
                        text = video.episodeBadge(),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = sizing.badgeTextSize,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.3.sp,
                        ),
                        color = Color.White,
                    )
                }

                NuvioAnimatedWatchedBadge(
                    isVisible = isWatched,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .padding(
                        start = sizing.contentHorizontalPadding,
                        end = sizing.contentHorizontalPadding,
                        top = sizing.contentVerticalPadding,
                        bottom = sizing.contentVerticalPadding,
                    ),
                verticalArrangement = Arrangement.spacedBy(sizing.contentSpacing),
            ) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = sizing.titleTextSize,
                        fontWeight = FontWeight.Bold,
                        lineHeight = sizing.titleLineHeight,
                        letterSpacing = 0.3.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = sizing.titleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )

                video.released?.formattedDate()?.let { formattedDate ->
                    Text(
                        text = formattedDate,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = sizing.metaTextSize,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (!video.overview.isNullOrBlank()) {
                    Text(
                        text = video.overview,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = sizing.bodyTextSize,
                            lineHeight = sizing.bodyLineHeight,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = sizing.overviewMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        progressEntry
            ?.takeIf { it.durationMs > 0L && !it.isCompleted }
            ?.let { entry ->
                NuvioProgressBar(
                    progress = entry.progressFraction,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .width(sizing.imageWidth - 24.dp)
                        .padding(start = 12.dp, bottom = 10.dp),
                    height = 5.dp,
                    trackColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.14f),
                    fillColor = MaterialTheme.colorScheme.primary,
                )
            }
    }
}

private data class SeriesContentSizing(
    val seasonHeaderSize: androidx.compose.ui.unit.TextUnit,
    val seasonChipGap: Dp,
    val seasonChipRadius: Dp,
    val seasonChipHorizontalPadding: Dp,
    val seasonChipVerticalPadding: Dp,
    val seasonChipTextSize: androidx.compose.ui.unit.TextUnit,
    val cardHeight: Dp,
    val imageWidth: Dp,
    val cardRadius: Dp,
    val cardGap: Dp,
    val contentHorizontalPadding: Dp,
    val contentVerticalPadding: Dp,
    val contentSpacing: Dp,
    val titleTextSize: androidx.compose.ui.unit.TextUnit,
    val titleLineHeight: androidx.compose.ui.unit.TextUnit,
    val titleMaxLines: Int,
    val bodyTextSize: androidx.compose.ui.unit.TextUnit,
    val bodyLineHeight: androidx.compose.ui.unit.TextUnit,
    val overviewMaxLines: Int,
    val metaTextSize: androidx.compose.ui.unit.TextUnit,
    val badgeTextSize: androidx.compose.ui.unit.TextUnit,
    val badgeRadius: Dp,
    val badgeHorizontalPadding: Dp,
    val badgeVerticalPadding: Dp,
)

private fun seriesContentSizing(maxWidthDp: Float): SeriesContentSizing =
    when {
        maxWidthDp >= 1440f -> SeriesContentSizing(
            seasonHeaderSize = 28.sp,
            seasonChipGap = 20.dp,
            seasonChipRadius = 16.dp,
            seasonChipHorizontalPadding = 20.dp,
            seasonChipVerticalPadding = 16.dp,
            seasonChipTextSize = 16.sp,
            cardHeight = 200.dp,
            imageWidth = 200.dp,
            cardRadius = 20.dp,
            cardGap = 20.dp,
            contentHorizontalPadding = 20.dp,
            contentVerticalPadding = 18.dp,
            contentSpacing = 8.dp,
            titleTextSize = 18.sp,
            titleLineHeight = 24.sp,
            titleMaxLines = 3,
            bodyTextSize = 15.sp,
            bodyLineHeight = 22.sp,
            overviewMaxLines = 4,
            metaTextSize = 13.sp,
            badgeTextSize = 13.sp,
            badgeRadius = 6.dp,
            badgeHorizontalPadding = 8.dp,
            badgeVerticalPadding = 4.dp,
        )
        maxWidthDp >= 1024f -> SeriesContentSizing(
            seasonHeaderSize = 26.sp,
            seasonChipGap = 18.dp,
            seasonChipRadius = 14.dp,
            seasonChipHorizontalPadding = 18.dp,
            seasonChipVerticalPadding = 14.dp,
            seasonChipTextSize = 15.sp,
            cardHeight = 180.dp,
            imageWidth = 180.dp,
            cardRadius = 18.dp,
            cardGap = 18.dp,
            contentHorizontalPadding = 18.dp,
            contentVerticalPadding = 16.dp,
            contentSpacing = 8.dp,
            titleTextSize = 17.sp,
            titleLineHeight = 22.sp,
            titleMaxLines = 3,
            bodyTextSize = 14.sp,
            bodyLineHeight = 20.sp,
            overviewMaxLines = 4,
            metaTextSize = 12.sp,
            badgeTextSize = 12.sp,
            badgeRadius = 5.dp,
            badgeHorizontalPadding = 7.dp,
            badgeVerticalPadding = 3.dp,
        )
        maxWidthDp >= 768f -> SeriesContentSizing(
            seasonHeaderSize = 24.sp,
            seasonChipGap = 16.dp,
            seasonChipRadius = 12.dp,
            seasonChipHorizontalPadding = 16.dp,
            seasonChipVerticalPadding = 12.dp,
            seasonChipTextSize = 17.sp,
            cardHeight = 160.dp,
            imageWidth = 160.dp,
            cardRadius = 16.dp,
            cardGap = 16.dp,
            contentHorizontalPadding = 16.dp,
            contentVerticalPadding = 14.dp,
            contentSpacing = 6.dp,
            titleTextSize = 16.sp,
            titleLineHeight = 20.sp,
            titleMaxLines = 3,
            bodyTextSize = 14.sp,
            bodyLineHeight = 20.sp,
            overviewMaxLines = 3,
            metaTextSize = 12.sp,
            badgeTextSize = 11.sp,
            badgeRadius = 4.dp,
            badgeHorizontalPadding = 6.dp,
            badgeVerticalPadding = 2.dp,
        )
        else -> SeriesContentSizing(
            seasonHeaderSize = 18.sp,
            seasonChipGap = 16.dp,
            seasonChipRadius = 12.dp,
            seasonChipHorizontalPadding = 16.dp,
            seasonChipVerticalPadding = 12.dp,
            seasonChipTextSize = 15.sp,
            cardHeight = 120.dp,
            imageWidth = 120.dp,
            cardRadius = 16.dp,
            cardGap = 16.dp,
            contentHorizontalPadding = 12.dp,
            contentVerticalPadding = 12.dp,
            contentSpacing = 4.dp,
            titleTextSize = 15.sp,
            titleLineHeight = 18.sp,
            titleMaxLines = 2,
            bodyTextSize = 13.sp,
            bodyLineHeight = 18.sp,
            overviewMaxLines = 2,
            metaTextSize = 12.sp,
            badgeTextSize = 11.sp,
            badgeRadius = 4.dp,
            badgeHorizontalPadding = 6.dp,
            badgeVerticalPadding = 2.dp,
        )
    }

private fun Int.label(): String =
    if (this <= 0) {
        "Specials"
    } else {
        "Season $this"
    }

private fun MetaVideo.episodeBadge(): String =
    when {
        episode != null -> "E${episode.toString().padStart(2, '0')}"
        season != null -> "S${season.toString().padStart(2, '0')}"
        else -> "FILE"
    }

private fun String.formattedDate(): String {
    val isoDate = substringBefore('T')
    return if (isoDate.length == 10) isoDate else this
}
