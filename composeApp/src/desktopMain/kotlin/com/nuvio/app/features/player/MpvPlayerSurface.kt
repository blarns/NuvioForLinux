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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.nuvio.app.desktop.egl.EglSeam
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


/**
 * How Skia is told to read the texture mpv rendered into.
 *
 * Overridable for one reason: a wrong choice here is a *colour* bug, not an error — the picture
 * plays perfectly with the channels swapped, which is what shipped in the first GPU build. Being
 * able to flip it without a rebuild is what makes it a one-minute check on other hardware.
 */
private val MPV_TEXTURE_COLOR_TYPE: ColorType =
    if (System.getenv("NUVIO_MPV_TEXTURE") == "bgra") ColorType.BGRA_8888 else ColorType.RGBA_8888

/**
 * Draws mpv's frame straight from the GL texture it rendered into — no readback, no copy.
 *
 * Runs inside Compose's own draw, which is the only place all three preconditions hold at once:
 * the EDT, an EGL context that is current, and the Skia [org.jetbrains.skia.DirectContext] that
 * will do the compositing. [tick] is read purely so the draw scope re-runs when mpv signals a
 * frame; [requestRedraw] is what schedules that.
 *
 * ⚠ `resetGLAll()` is mandatory, not defensive: mpv changes GL state behind Skia's back, and
 * without it Skia draws with a stale cached view of the context.
 */
private fun DrawScope.drawMpvTexture(
    session: MpvSession,
    tick: Int,
    frozen: Boolean,
    requestRedraw: () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") tick   // subscribes this draw scope to mpv's frame signal
    val ctx = EglSeam.directContext ?: return
    val w = size.width.toInt()
    val h = size.height.toInt()
    if (w <= 0 || h <= 0) return

    val image = session.renderGpuFrame(ctx, MPV_TEXTURE_COLOR_TYPE, w, h, frozen, requestRedraw)
        ?: return
    // ⚠ mpv changed GL state behind Skia's back; without this Skia draws against a stale
    // cached view of the context.
    ctx.resetGLAll()
    // The Image is owned by the renderer and lives as long as its texture, so nothing is closed
    // here — and nothing may be, since Compose draws into a RECORDING canvas that is replayed
    // after this function returns.
    drawContext.canvas.nativeCanvas.drawImage(image, 0f, 0f)
    session.reportGpuSwap()
}

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

    // Bumped whenever mpv signals a frame on the GPU path; read inside drawBehind so the draw
    // scope re-runs. The software path drives Compose through `currentFrame` instead.
    var gpuFrameTick by remember { mutableStateOf(0) }

    // --- frames ---------------------------------------------------------------------------
    DisposableEffect(Unit) {
        // No-op on the GPU path — frames are rendered from the draw scope, on the EDT, because
        // that is the only thread where Compose's EGL context is current.
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
                if (session.isGpu) {
                    drawMpvTexture(session, gpuFrameTick, frozen.get()) {
                        scope.launch(Dispatchers.Main) { gpuFrameTick++ }
                    }
                    return@drawBehind
                }
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
            if (session.isGpu) session.logPacing()
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
