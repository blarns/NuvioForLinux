package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.nuvio.app.desktop.mpv.MpvSession
import com.nuvio.app.features.discord.DiscordRichPresence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

private const val TAG = "NuvioPlayerMpv"

// Same bound as the VLCJ path: the newest frame is on screen and the rest are a margin so a
// frame is never freed while the renderer could still be drawing it.
private const val FRAME_RETAIN = 3

/**
 * The libmpv video surface (software render path).
 *
 * It exists because libVLC silently forces `avcodec-hw=none` whenever its video output is the
 * buffer-callback surface this app renders through, so the shipping engine decodes 4K in
 * software at ~580% CPU. mpv has no such restriction: this path hardware-*decodes*
 * (`vaapi-copy`) and additionally scales to the window inside mpv, so the per-frame buffer is
 * window-sized rather than source-sized.
 *
 * Deliberately shaped like the VLCJ surface rather than "better": same ~10 Hz snapshot poll,
 * same 30 fps frame cap, same [DesktopPlaybackSideEffects], same frame recycling. The engine is
 * the variable under test here — everything around it stays put so a regression can only come
 * from one place.
 */
@Composable
internal fun MpvPlayerSurface(
    session: MpvSession,
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    startPositionMs: Long,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val latestOnSnapshot = rememberUpdatedState(onSnapshot)
    val latestOnError = rememberUpdatedState(onError)
    val scope = rememberCoroutineScope()

    var currentFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    val inFlightFrames = remember { ArrayDeque<Bitmap>() }

    val sideEffects = remember { DesktopPlaybackSideEffects() }
    val frozen = sideEffects.frozen
    val nearEnd = sideEffects.nearEnd
    val handleSnapshot = remember<(PlayerPlaybackSnapshot) -> Unit> {
        { snap -> sideEffects.onSnapshot(snap) { latestOnSnapshot.value(it) } }
    }

    // --- frames ---------------------------------------------------------------------------
    DisposableEffect(Unit) {
        session.startFramePump { bytes, w, h ->
            // Playback ended — hold the last good frame rather than painting drain frames.
            if (frozen.get()) return@startFramePump
            if (nearEnd.get()) {
                val flush = flushFrameSignature(bytes, w, h)
                if (flush != null) {
                    println("$TAG: end-of-stream flush frame dropped ($flush)")
                    return@startFramePump
                }
            }
            scope.launch(Dispatchers.Main) {
                try {
                    // A frame queued before the freeze must not land after it.
                    if (frozen.get()) return@launch
                    val imageInfo = ImageInfo(
                        ColorInfo(ColorType.BGRA_8888, ColorAlphaType.OPAQUE, ColorSpace.sRGB),
                        w,
                        h,
                    )
                    // A fresh Bitmap per frame (so Skia can never serve a stale generation),
                    // with the one from a few frames ago closed — by which point it cannot
                    // still be on screen. See the long note in PlayerEngine.desktop.kt.
                    val bitmap = Bitmap()
                    bitmap.allocPixels(imageInfo)
                    bitmap.installPixels(imageInfo, bytes, w * 4)
                    currentFrame = bitmap.asComposeImageBitmap()
                    // Only ever touched on Dispatchers.Main, so no synchronisation needed.
                    inFlightFrames.addLast(bitmap)
                    while (inFlightFrames.size > FRAME_RETAIN) inFlightFrames.removeFirst().close()
                } catch (e: Exception) {
                    println("$TAG: frame conversion error: ${e.message}")
                }
            }
            LastFrameStore.update(bytes, w, h)
        }
        // mpv scales to the window while decoding, so the frames above are window-sized and would
        // make the "S" hotkey save a 4K film at 1080p. libmpv can write the real frame itself.
        DesktopScreenshot.engineCapture = { file ->
            session.controller.saveScreenshot(file.absolutePath)
        }
        onDispose {
            DesktopScreenshot.engineCapture = null
            // The pump itself is stopped by MpvSession.dispose(), which owns the ordering.
        }
    }

    // Fit letterboxes, Fill stretches — both done by mpv while it scales to the window, so the
    // frame is blitted 1:1 below. Zoom has no mpv equivalent yet and behaves as Fit.
    LaunchedEffect(resizeMode) {
        session.setKeepAspect(resizeMode != PlayerResizeMode.Fill)
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            // mpv renders at exactly this size, so the frame needs no scaling on the way out.
            .onSizeChanged { size -> session.setSurfaceSize(size.width, size.height) }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val scroll = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                        if (scroll == 0f) continue
                        val ctrl = session.controller
                        val current = ctrl.currentVolume()?.fraction ?: 1f
                        // scroll up → louder, scroll down → quieter
                        val next = if (scroll < 0f) current + 0.05f else current - 0.05f
                        ctrl.setVolume(next.coerceIn(0f, 1f))
                    }
                }
            }
            .drawBehind {
                val frame = currentFrame ?: return@drawBehind
                drawImage(
                    image = frame,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                )
            }
    )

    // --- controller ------------------------------------------------------------------------
    // One controller for the whole surface, unlike the VLCJ path: it wraps the mpv handle
    // rather than a per-media object, and re-creating it would re-register property observers
    // on the same handle. A source change is just another loadfile.
    DisposableEffect(sourceUrl, sourceAudioUrl, sourceHeaders) {
        session.onError = { e ->
            println("$TAG: engine error: ${e.message}")
            latestOnError.value(e.message ?: "Unknown error")
        }
        onControllerReady(session.controller)
        PlayerControlBridge.controller = session.controller

        onDispose {
            session.onError = null
            PlayerControlBridge.controller = null
            PlayerControlBridge.isPlaying = false
            DiscordRichPresence.clear()
        }
    }

    LaunchedEffect(sourceUrl, sourceAudioUrl, sourceHeaders) {
        try {
            session.controller.loadMedia(
                sourceUrl, sourceHeaders, playWhenReady, startPositionMs, sourceAudioUrl,
            )
        } catch (e: Exception) {
            println("$TAG: loadMedia exception: ${e.message}")
            latestOnError.value(e.message ?: "Failed to load media")
        }
    }

    // Snapshot polling — the side effects must run on a clock, not on playback events: driving
    // them from events alone leaves the MPRIS position frozen between them and never arms the
    // near-end frame filter.
    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
            handleSnapshot(session.controller.currentSnapshot())
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            println("$TAG: tearing down mpv surface (hwdec-current=${session.hwdecCurrent})")
            // Navigating away may not deliver a final paused snapshot, so drop the inhibitor
            // and Discord presence explicitly.
            sideEffects.release()
            LastFrameStore.clear()
            inFlightFrames.forEach { runCatching { it.close() } }
            inFlightFrames.clear()
        }
    }
}
