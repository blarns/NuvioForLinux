package com.nuvio.app.features.home.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.java.Java
import io.ktor.client.request.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image

@Composable
internal actual fun CollectionCardRemoteImage(
    imageUrl: String,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
    animateIfPossible: Boolean,
    restImageUrl: String?,
    hoverImageUrl: String?,
    hovered: Boolean,
) {
    // Posters load through Coil's singleton ImageLoader (shared memory + disk cache; see
    // configurePlatformImageLoader in PlatformImageLoader.desktop.kt). The static cover sits at
    // rest; the animated "focus" art is revealed while [hovered] (the caller detects hover on an
    // ancestor of the click overlay, which would otherwise swallow it).
    //
    // Coil's desktop decoder is Skia's static one — it only renders the first frame of an animated
    // WebP/GIF and fails outright on some (which is why those collection tiles showed up blank), and
    // coil-gif has no JVM artifact. So the focus animation is decoded frame-by-frame with Skia's
    // Codec, mirroring how the iOS actual hand-rolls animation via ImageIO. Decoding happens only
    // while a card is actually hovered (one at a time), and results are cached so re-hovering is
    // instant.
    val rest = restImageUrl?.takeIf { it.isNotBlank() } ?: imageUrl
    val hover = hoverImageUrl?.takeIf { it.isNotBlank() && it != rest }

    if (hover == null) {
        AsyncImage(
            model = rest,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
        return
    }

    Box(modifier = modifier) {
        // Resting cover is always present, so the tile is never blank and the swap is seamless.
        AsyncImage(
            model = rest,
            contentDescription = contentDescription,
            modifier = Modifier.matchParentSize(),
            contentScale = contentScale,
        )
        if (hovered) {
            AnimatedFocusImage(
                url = hover,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/** Decodes [url] as an animated image with Skia's Codec and plays it; draws nothing until ready (the cover shows through). */
@Composable
private fun AnimatedFocusImage(
    url: String,
    contentDescription: String,
    contentScale: ContentScale,
    modifier: Modifier,
) {
    var animation by remember(url) { mutableStateOf(cachedAnimation(url)) }
    LaunchedEffect(url) {
        if (animation == null) animation = loadAnimation(url)
    }

    val frames = animation ?: return // still decoding or failed → resting cover stays visible
    if (frames.images.isEmpty()) return

    var frameIndex by remember(frames) { mutableStateOf(0) }
    LaunchedEffect(frames) {
        if (frames.images.size <= 1) return@LaunchedEffect
        while (true) {
            delay(frames.delaysMs[frameIndex.coerceIn(0, frames.delaysMs.lastIndex)].toLong())
            frameIndex = (frameIndex + 1) % frames.images.size
        }
    }

    // Fade in so the animation doesn't pop over the resting cover.
    var shown by remember(frames) { mutableStateOf(false) }
    LaunchedEffect(frames) { shown = true }
    val alpha by animateFloatAsState(targetValue = if (shown) 1f else 0f, label = "focusFadeIn")

    Image(
        bitmap = frames.images[frameIndex.coerceIn(0, frames.images.lastIndex)],
        contentDescription = contentDescription,
        modifier = modifier.graphicsLayer { this.alpha = alpha },
        contentScale = contentScale,
    )
}

private class FocusAnimation(
    private val frames: List<Image>,
    val delaysMs: List<Int>,
) {
    /** The drawable views of [frames]; these wrap the Images rather than copying them. */
    val images: List<ImageBitmap> = frames.map { it.toComposeImageBitmap() }

    /**
     * Frees the frames' native Skia memory.
     *
     * Only ever called on eviction, and only for the least-recently-used entry. Exactly one card
     * can be hovered at a time and its entry is touched to most-recently-used when it is read, so
     * the animation being evicted cannot be the one on screen.
     */
    fun close() {
        frames.forEach { runCatching { it.close() } }
    }
}

private const val DefaultFrameDelayMs = 100
private const val MinFrameDelayMs = 20
private const val MaxCachedAnimations = 8

/**
 * Ceiling on how many frames one animation may hold in memory.
 *
 * ⚠ These URLs come from addon metadata, so the frame count is not ours to trust: a long
 * animated WebP at poster resolution is a few megabytes per frame, and eight of them cached is
 * gigabytes of native memory. Playing the first seconds of an over-long animation is a better
 * outcome than exhausting the machine.
 */
private const val MaxAnimationFrames = 120

private val focusHttpClient by lazy { HttpClient(Java) }
private val focusDecodeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

// Small LRU of decoded animations keyed by URL (mirrors the iOS GIF cache). Only hovered cards
// decode, so this stays tiny; capping it bounds the native bitmap memory the frames hold.
private val focusAnimationCache = LinkedHashMap<String, FocusAnimation>()
private val focusAnimationInFlight = mutableMapOf<String, Deferred<FocusAnimation?>>()

@Synchronized
private fun cachedAnimation(url: String): FocusAnimation? {
    val cached = focusAnimationCache.remove(url) ?: return null
    focusAnimationCache[url] = cached // touch → most-recently-used
    return cached
}

@Synchronized
private fun storeAnimation(url: String, animation: FocusAnimation) {
    focusAnimationCache.remove(url)
    focusAnimationCache[url] = animation
    while (focusAnimationCache.size > MaxCachedAnimations) {
        val oldest = focusAnimationCache.keys.firstOrNull() ?: break
        // ⚠ Dropping the map entry is NOT enough. Every frame is a Skia Image behind a tiny
        // Kotlin wrapper, so the JVM sees a few dozen bytes freed while tens or hundreds of
        // megabytes of native memory stay allocated — it has no reason to GC and nothing
        // reclaims them. This is the same mistake that grew RSS by 38 GB on the frame path (see
        // the long note in PlayerEngine.desktop.kt); the cap only bounds memory if the evicted
        // frames are actually closed.
        focusAnimationCache.remove(oldest)?.close()
    }
}

private suspend fun loadAnimation(url: String): FocusAnimation? {
    cachedAnimation(url)?.let { return it }

    val deferred = synchronized(focusAnimationInFlight) {
        focusAnimationInFlight[url] ?: focusDecodeScope.async {
            runCatching {
                decodeAnimation(focusHttpClient.get(url).body<ByteArray>())
            }.getOrNull()
        }.also { focusAnimationInFlight[url] = it }
    }

    val result = try {
        deferred.await()
    } finally {
        synchronized(focusAnimationInFlight) {
            if (focusAnimationInFlight[url] === deferred) focusAnimationInFlight.remove(url)
        }
    }

    if (result != null) storeAnimation(url, result)
    return result
}

private fun decodeAnimation(bytes: ByteArray): FocusAnimation? {
    if (bytes.isEmpty()) return null
    val data = Data.makeFromBytes(bytes)
    val codec = try {
        Codec.makeFromData(data)
    } catch (t: Throwable) {
        data.close()
        return null
    }

    return try {
        val frameCount = codec.frameCount
        if (frameCount <= 0) return null
        val info = codec.framesInfo
        val bitmap = Bitmap()
        if (!bitmap.allocPixels(codec.imageInfo)) {
            bitmap.close()
            return null
        }

        // Held as skia Images, not ImageBitmaps: an ImageBitmap wraps the Image and gives no way
        // to release it, and these are the objects that own the frames' native memory.
        val images = ArrayList<Image>(frameCount)
        val delays = ArrayList<Int>(frameCount)
        try {
            for (i in 0 until minOf(frameCount, MaxAnimationFrames)) {
                // Reuse the bitmap across frames so Skia composes disposal/blend against the prior
                // frame (priorFrame = i - 1, the common sequential case). makeFromBitmap copies the
                // pixels, so each snapshot is independent and the bitmap is safe to overwrite.
                val decoded = runCatching {
                    if (i == 0) codec.readPixels(bitmap, 0) else codec.readPixels(bitmap, i, i - 1)
                }.isSuccess
                if (!decoded) break
                images.add(Image.makeFromBitmap(bitmap))
                val duration = info.getOrNull(i)?.duration ?: 0
                delays.add(if (duration > 0) duration.coerceAtLeast(MinFrameDelayMs) else DefaultFrameDelayMs)
            }
        } finally {
            bitmap.close()
        }

        if (images.isEmpty()) null else FocusAnimation(images, delays)
    } catch (t: Throwable) {
        null
    } finally {
        codec.close()
        data.close()
    }
}
