package com.nuvio.app.features.player

import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.nuvio.app.features.discord.DiscordRichPresence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer

private const val TAG = "NuvioPlayerDesktop"

// How close libVLC's reported time must get to an in-flight seek target before the
// poll trusts it again, and how long to hold the optimistic target at most.
private const val SEEK_SETTLE_TOLERANCE_MS = 3_000L
private const val SEEK_SETTLE_TIMEOUT_MS = 8_000L

// How many decoded frames stay alive at once. The newest is on screen; the rest are a margin
// so a frame is never freed while the renderer could still be drawing it. Each one is ~32MB at
// 4K, so this is also the cap on the frame path's native memory.
private const val FRAME_RETAIN = 3

private var vlcjFactory: MediaPlayerFactory? = null

private fun getVlcjFactory(): MediaPlayerFactory {
    return vlcjFactory ?: run {
        val hwAccel = PlayerSettingsStorage.loadHwAccelEnabled() ?: true
        val audioOutput = PlayerSettingsStorage.loadAudioOutput()
        // Subtitle appearance (libVLC freetype module). Values are the raw VLC options;
        // rel-fontsize is inverse (smaller = larger text). Only add non-defaults.
        val subFontSize = PlayerSettingsStorage.loadSubtitleFontSize() ?: 16
        val subColor = PlayerSettingsStorage.loadSubtitleColor() ?: 0xFFFFFF
        val subBgOpacity = PlayerSettingsStorage.loadSubtitleBackgroundOpacity() ?: 0
        val subOutline = PlayerSettingsStorage.loadSubtitleOutline() ?: 2
        val args = buildList {
            if (hwAccel) add("--avcodec-hw=any")
            if (!audioOutput.isNullOrBlank()) add("--aout=$audioOutput")
            add("--freetype-rel-fontsize=$subFontSize")
            add("--freetype-color=$subColor")
            add("--freetype-background-opacity=$subBgOpacity")
            add("--freetype-outline-thickness=$subOutline")
        }.toTypedArray()
        val factory = MediaPlayerFactory(*args)
        vlcjFactory = factory
        factory
    }
}

// Live player count per factory. A retired factory must outlive the player built from it,
// so it can only be released once that player is gone — hence the refcount rather than a
// release() at invalidation time.
private val factoryLock = Any()
private val factoryUsers = HashMap<MediaPlayerFactory, Int>()

private fun retainFactory(factory: MediaPlayerFactory) {
    synchronized(factoryLock) {
        factoryUsers[factory] = (factoryUsers[factory] ?: 0) + 1
    }
}

private fun releaseFactory(factory: MediaPlayerFactory) {
    val shouldRelease = synchronized(factoryLock) {
        val remaining = (factoryUsers[factory] ?: 0) - 1
        if (remaining > 0) {
            factoryUsers[factory] = remaining
            false
        } else {
            factoryUsers.remove(factory)
            // Still the cached factory — keep it, the next video will reuse it.
            factory !== vlcjFactory
        }
    }
    if (shouldRelease) {
        try { factory.release() } catch (_: Exception) {}
    }
}

// Drop the cached factory so the next player build reflects changed engine options
// (subtitle appearance). The active player keeps the factory it was created with; a new
// video builds a fresh one. Called from PlayerSettingsStorage.invalidatePlayerEngineConfig().
internal fun invalidateVlcjFactory() {
    val retired = vlcjFactory ?: return
    // Nothing is using it (no video open) — release now. Otherwise the last player to be
    // torn down releases it. Without this, every subtitle-setting change leaked a native
    // libVLC instance for the lifetime of the process.
    val shouldRelease = synchronized(factoryLock) {
        vlcjFactory = null
        val inUse = (factoryUsers[retired] ?: 0) > 0
        if (!inUse) factoryUsers.remove(retired)
        !inUse
    }
    if (shouldRelease) {
        try { retired.release() } catch (_: Exception) {}
    }
}

// ---------------------------------------------------------------------------
// PlatformPlayerSurface — pure Compose, no AWT/Swing
//
// Uses VLCJ's buffer callback API to render each frame into a ByteBuffer,
// converts it to an ImageBitmap via Skia, then paints it in a Compose Canvas.
// This avoids all AWT Z-ordering and pointer-event interception issues.
// ---------------------------------------------------------------------------

@Composable
actual fun PlatformPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    sourceResponseHeaders: Map<String, String>,
    useYoutubeChunkedPlayback: Boolean,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    useNativeController: Boolean,
    startPositionMs: Long,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val latestOnSnapshot = rememberUpdatedState(onSnapshot)
    val latestOnError = rememberUpdatedState(onError)
    val scope = rememberCoroutineScope()

    var currentFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    val videoWidth = remember { mutableStateOf(0) }
    val videoHeight = remember { mutableStateOf(0) }

    // Shared state between the buffer callbacks (VLCJ thread) and the Compose render thread.
    // We keep a reference to the raw bytes so we can copy them on the VLCJ thread and
    // dispatch to Main only for the ImageBitmap construction.
    var frameBytes by remember { mutableStateOf<ByteArray?>(null) }

    // Decoded frames still in flight, newest last. Only ever touched on Dispatchers.Main.
    val inFlightFrames = remember { ArrayDeque<Bitmap>() }

    val bufferFormatCallback = remember {
        object : BufferFormatCallback {
            override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                println("$TAG: Video dimensions -> ${sourceWidth}x${sourceHeight}")
                videoWidth.value = sourceWidth
                videoHeight.value = sourceHeight
                return RV32BufferFormat(sourceWidth, sourceHeight)
            }

            override fun allocatedBuffers(buffers: Array<out ByteBuffer>) {
                // Nothing needed here — we copy from the nativeBuffers in display()
            }
        }
    }

    // Throttle frame delivery to ~30 fps. display() fires on the VLCJ render thread at the
    // native frame rate (up to 60+ fps); each call launches a Dispatchers.Main coroutine that
    // updates Compose state and triggers a recomposition. Without throttling, 60+ coroutines/s
    // compete with the Compose resource loader for the compose-resources ZipFile, producing
    // "invalid LOC header" crashes on pause.
    // Plain AtomicLong — not Compose state. display() fires on the VLCJ render thread;
    // writing mutableStateOf from a non-Compose thread triggers spurious recompositions.
    val lastFrameMs = remember { java.util.concurrent.atomic.AtomicLong(0L) }

    // Screensaver/Discord/MPRIS side effects and the two frame-gating flags, shared with any
    // other desktop engine so a second surface cannot silently lose them.
    val sideEffects = remember { DesktopPlaybackSideEffects() }
    val frozen = sideEffects.frozen
    val nearEnd = sideEffects.nearEnd
    val handleSnapshot = remember<(PlayerPlaybackSnapshot) -> Unit> {
        { snap -> sideEffects.onSnapshot(snap) { latestOnSnapshot.value(it) } }
    }

    val renderCallback = remember {
        object : RenderCallback {
            override fun display(
                mediaPlayer: MediaPlayer,
                nativeBuffers: Array<out ByteBuffer>,
                bufferFormat: BufferFormat,
            ) {
                if (frozen.get()) return   // playback ended — hold the last frame, skip EOS/black frames
                val now = System.currentTimeMillis()
                if (now - lastFrameMs.get() < 33L) return   // cap at ~30 fps
                lastFrameMs.set(now)

                val buffer = nativeBuffers[0]
                val w = videoWidth.value
                val h = videoHeight.value
                val expectedBytes = w * h * 4
                if (w <= 0 || h <= 0 || buffer.remaining() < expectedBytes) return

                // Copy bytes on the VLCJ thread — safe because lockBuffers = true
                val bytes = ByteArray(expectedBytes)
                buffer.get(bytes)
                buffer.rewind()

                // In the final seconds of the video, drop the decoder's flush frames
                // before they reach the screen. Depending on the libVLC build these are
                // pure black OR solid green/grey (uninitialized YUV planes — seen with
                // the system VLC used by the .deb), so detect any solid-color dark or
                // green frame, not just black. Sampling a sparse pixel grid keeps this
                // effectively free, and it only runs near the end, so mid-video fades
                // and dark scenes are never touched.
                if (nearEnd.get()) {
                    val flush = flushFrameSignature(bytes, w, h)
                    if (flush != null) {
                        println("$TAG: end-of-stream flush frame dropped ($flush)")
                        return
                    }
                    // Log painted frames too while near the end: if a "blink" is ever
                    // reported again, the interleaving in this log shows whether stray
                    // frames slipped through here or the flash comes from the UI layer.
                    val c = ((h / 2) * w + w / 2) * 4
                    if (c + 2 < bytes.size) {
                        val b = bytes[c].toInt() and 0xFF
                        val g = bytes[c + 1].toInt() and 0xFF
                        val r = bytes[c + 2].toInt() and 0xFF
                        println("$TAG: near-end frame painted (center rgb($r,$g,$b), frozen=${frozen.get()})")
                    }
                }

                scope.launch(Dispatchers.Main) {
                    try {
                        // A frame queued before the freeze must not land after it —
                        // re-check at paint time, not just at capture time.
                        if (frozen.get()) return@launch
                        // RV32 from VLC is BGRA in memory on little-endian systems
                        val imageInfo = ImageInfo(
                            ColorInfo(ColorType.BGRA_8888, ColorAlphaType.OPAQUE, ColorSpace.sRGB),
                            w,
                            h,
                        )
                        // ⚠ This used to be `Image.makeRaster(...).toComposeImageBitmap()`, which
                        // leaked NATIVE memory badly: each call allocated ~32MB of Skia memory at
                        // 4K (twice over — makeRaster and then toComposeImageBitmap's own copy),
                        // held by a Kotlin wrapper of a few dozen bytes. The JVM therefore felt
                        // almost no heap pressure, had no reason to GC, and nothing reclaimed the
                        // native side. Measured: 600 frames (20s of 4K) grew RSS by 38 GB, with
                        // the Java heap flat at 60 MB — and a live v0.3.4 was found sitting at
                        // ~10 GB resident against 456 MB of heap.
                        //
                        // The fix keeps allocating a fresh Bitmap per frame, so every frame is
                        // still a distinct object and Skia can never cache a stale generation,
                        // but frees the one from a few frames ago — by which point it cannot
                        // still be on screen. Bounded at ~3 frames instead of unbounded.
                        // scripts/spikes/FrameLeakSpike.kt measures all of this.
                        val bitmap = Bitmap()
                        bitmap.allocPixels(imageInfo)
                        bitmap.installPixels(imageInfo, bytes, w * 4)
                        currentFrame = bitmap.asComposeImageBitmap()
                        // Runs only on Dispatchers.Main, so this deque needs no synchronisation.
                        inFlightFrames.addLast(bitmap)
                        while (inFlightFrames.size > FRAME_RETAIN) inFlightFrames.removeFirst().close()
                    } catch (e: Exception) {
                        println("$TAG: Frame conversion error: ${e.message}")
                    }
                }
                // Hand the just-captured frame to the screenshot store (same ByteArray
                // reference, no extra copy). Skip while frozen so the "S" hotkey never
                // grabs an end-of-stream black/green flush frame.
                if (!frozen.get()) LastFrameStore.update(bytes, w, h)
            }
        }
    }

    val playerFactory = remember { getVlcjFactory().also { retainFactory(it) } }
    val mediaPlayer = remember {
        println("$TAG: Creating EmbeddedMediaPlayer with buffer callbacks")
        val player = playerFactory.mediaPlayers().newEmbeddedMediaPlayer()
        val videoSurface = playerFactory.videoSurfaces().newVideoSurface(
            bufferFormatCallback,
            renderCallback,
            /* lockBuffers = */ true,
        )
        player.videoSurface().set(videoSurface)
        player
    }

    var playerController by remember { mutableStateOf<VlcjPlayerController?>(null) }
    var snapshotUpdateJob: Job? = remember { null }

    // Render the current video frame into a Compose Canvas.
    // Because this is plain Compose (no AWT), the controls Popup renders above it normally
    // and all pointerInput handlers on the parent Box fire correctly.
    // Keyboard shortcuts are handled at Window level in Main.kt so the player
    // Box never needs to grab focus, keeping navigation to other screens smooth.
    Box(
        modifier = modifier
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val scroll = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                        if (scroll != 0f) {
                            val ctrl = playerController ?: continue
                            val current = ctrl.currentVolume()?.fraction ?: 1f
                            if (scroll < 0f) {
                                // scroll up → increase volume
                                ctrl.setVolume((current + 0.05f).coerceAtMost(1f))
                            } else {
                                // scroll down → decrease volume
                                ctrl.setVolume((current - 0.05f).coerceAtLeast(0f))
                            }
                        }
                    }
                }
            }
            .drawBehind {
                val frame = currentFrame ?: return@drawBehind
                val vidW = videoWidth.value.toFloat()
                val vidH = videoHeight.value.toFloat()
                if (vidW <= 0f || vidH <= 0f) return@drawBehind

                val dstRect = when (resizeMode) {
                    PlayerResizeMode.Fill -> Rect(0f, 0f, size.width, size.height)
                    else -> {
                        val scale = minOf(size.width / vidW, size.height / vidH)
                        val dw = vidW * scale
                        val dh = vidH * scale
                        val left = (size.width - dw) / 2f
                        val top = (size.height - dh) / 2f
                        Rect(left, top, left + dw, top + dh)
                    }
                }

                drawImage(
                    image = frame,
                    dstOffset = androidx.compose.ui.unit.IntOffset(
                        dstRect.left.toInt(), dstRect.top.toInt()
                    ),
                    dstSize = androidx.compose.ui.unit.IntSize(
                        dstRect.width.toInt(), dstRect.height.toInt()
                    ),
                )
            }
    )


    // Wire up the player controller. On a source change (token rotation, next-episode
    // auto-play) only the controller is swapped — the new loadMedia()'s play() replaces the
    // running media in-place. Do NOT stop() here: a deferred stop on the shared mediaPlayer
    // can land AFTER the next play() and kill the new stream.
    DisposableEffect(sourceUrl, sourceAudioUrl, sourceHeaders) {
        println("$TAG: Creating VlcjPlayerController for $sourceUrl")

        val controller = VlcjPlayerController(
            mediaPlayer = mediaPlayer,
            onSnapshot = handleSnapshot,
            onError = { error ->
                println("$TAG: PlayerController error: ${error.message}")
                latestOnError.value(error.message ?: "Unknown error")
            },
        )
        playerController = controller
        onControllerReady(controller)
        PlayerControlBridge.controller = controller

        onDispose {
            playerController = null
            PlayerControlBridge.controller = null
            PlayerControlBridge.isPlaying = false
            DiscordRichPresence.clear()
        }
    }

    // True teardown (leaving the surface): stop playback and release the native player.
    DisposableEffect(Unit) {
        onDispose {
            println("$TAG: Tearing down media player")
            // Drop the screensaver inhibitor and Discord presence in case we left
            // mid-playback (navigating away may not deliver a final paused/stopped snapshot
            // before disposal).
            sideEffects.release()
            // Drop the last captured frame so the screenshot hotkey can't grab a stale
            // frame from a video we already left.
            LastFrameStore.clear()
            // Free the retained decoded frames — otherwise leaving the player strands up to
            // FRAME_RETAIN native bitmaps (~96MB at 4K) until a GC happens to run.
            inFlightFrames.forEach { runCatching { it.close() } }
            inFlightFrames.clear()
            // stop() can block for 500ms–2s while VLC flushes buffers and closes the
            // network connection. Running it on a daemon thread keeps the Compose render
            // thread free so the next screen's buttons remain responsive immediately.
            // release() must follow stop() or the native player (and its video surface)
            // leaks on every playback session.
            val mp = mediaPlayer
            val factory = playerFactory
            Thread(null, {
                try { mp.controls().stop() } catch (_: Exception) {}
                try { mp.release() } catch (_: Exception) {}
                // Frees the factory too if a settings change retired it while this player
                // was still using it.
                releaseFactory(factory)
            }, "vlc-stop", 0).also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    // Load media — no need to wait for AWT displayable state any more
    LaunchedEffect(playerController, sourceUrl, sourceAudioUrl, sourceHeaders) {
        val controller = playerController ?: return@LaunchedEffect
        try {
            println("$TAG: Calling loadMedia on controller")
            controller.loadMedia(sourceUrl, sourceHeaders, playWhenReady, startPositionMs, sourceAudioUrl)
        } catch (e: Exception) {
            println("$TAG: loadMedia exception: ${e.message}")
            latestOnError.value(e.message ?: "Failed to load media")
        }
    }

    // Periodic snapshot polling
    LaunchedEffect(playerController) {
        snapshotUpdateJob = launch {
            while (true) {
                delay(100)
                playerController?.let { handleSnapshot(it.currentSnapshot()) }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            snapshotUpdateJob?.cancel()
        }
    }
}

// ---------------------------------------------------------------------------
// VlcjPlayerController — unchanged from original
// ---------------------------------------------------------------------------

private class VlcjPlayerController(
    private val mediaPlayer: uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer,
    private val onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    private val onError: (Exception) -> Unit,
) : PlayerEngineController {

    // Mutated from VLCJ event-callback threads and read from the Main-dispatcher poll loop.
    @Volatile
    private var currentState = PlayerPlaybackSnapshot(
        isLoading = false,
        isPlaying = false,
        isEnded = false,
        durationMs = 0L,
        positionMs = 0L,
        bufferedPositionMs = 0L,
        playbackSpeed = 1.0f,
    )

    // Last position/duration confirmed > 0 during active playback, updated by the
    // polling loop via currentSnapshot(). Used in pause/stop/error handlers instead
    // of querying the player at event-fire time, which can return 0 on some streams.
    private val lastGoodPositionMs = java.util.concurrent.atomic.AtomicLong(0L)
    private val lastGoodDurationMs = java.util.concurrent.atomic.AtomicLong(0L)

    // In-flight seek target (-1 when idle). libVLC keeps reporting the PRE-seek time
    // until the demuxer lands, so the 100ms poll would snap the timeline back to the
    // old position — which reads as "the rewind didn't take" and invites repeated
    // seeks that compound into a long stall. While a seek is pending we report the
    // target instead, and seekBy() accumulates from it.
    private val pendingSeekTargetMs = java.util.concurrent.atomic.AtomicLong(-1L)
    @Volatile
    private var pendingSeekStartedAtMs = 0L

    // Guard against duplicate loadMedia calls (LaunchedEffect can fire twice when
    // sourceHeaders updates after onControllerReady triggers a PlayerScreen recomposition).
    private var lastLoadedUrl: String? = null

    // The raw source URL of the current media, kept so an error can be diagnosed (a
    // failed HTTP source is probed to tell an expired/dead link apart from a real bug).
    @Volatile
    private var lastSourceUrl: String? = null

    private var externalSubtitleUri: String? = null
    private var subtitleDelayMs: Int = 0

    // Written by the playing() event callback (VLCJ thread) and the UI thread.
    @Volatile
    private var currentAudioLevel = PlayerAudioLevel(1.0f, false)

    init {
        setupPlayerListeners()
    }

    private fun setupPlayerListeners() {
        mediaPlayer.events().addMediaPlayerEventListener(
            object : MediaPlayerEventAdapter() {
                override fun opening(mediaPlayer: MediaPlayer?) {
                    println("$TAG: Event -> opening")
                    currentState = currentState.copy(isLoading = true)
                    onSnapshot(currentState)
                }

                override fun buffering(mediaPlayer: MediaPlayer?, newCache: Float) {
                    mediaPlayer?.let {
                        val duration = it.status().length()
                        val buffered = (duration * (newCache / 100f)).toLong()
                        currentState = currentState.copy(bufferedPositionMs = buffered)
                    }
                }

                override fun playing(mediaPlayer: MediaPlayer?) {
                    println("$TAG: Event -> playing")
                    currentState = currentState.copy(isLoading = false, isPlaying = true, isEnded = false)
                    onSnapshot(currentState)
                    PlayerControlBridge.isPlaying = true

                    // Re-apply our cached volume when media actually starts
                    try {
                        val volInt = (currentAudioLevel.fraction * 100).toInt().coerceIn(0, 100)
                        mediaPlayer?.audio()?.setVolume(volInt)
                        mediaPlayer?.audio()?.setMute(currentAudioLevel.isMuted)
                    } catch (_: Exception) {}
                }

                override fun paused(mediaPlayer: MediaPlayer?) {
                    currentState = currentState.copy(isPlaying = false, positionMs = bestPositionMs(), durationMs = bestDurationMs())
                    onSnapshot(currentState)
                    PlayerControlBridge.isPlaying = false
                }

                override fun stopped(mediaPlayer: MediaPlayer?) {
                    currentState = currentState.copy(isPlaying = false, positionMs = bestPositionMs(), durationMs = bestDurationMs())
                    onSnapshot(currentState)
                    PlayerControlBridge.isPlaying = false
                }

                override fun finished(mediaPlayer: MediaPlayer?) {
                    currentState = currentState.copy(isEnded = true, isPlaying = false, positionMs = bestPositionMs(), durationMs = bestDurationMs())
                    onSnapshot(currentState)
                    PlayerControlBridge.isPlaying = false
                }

                override fun timeChanged(mediaPlayer: MediaPlayer?, newTime: Long) {
                    currentState = currentState.copy(positionMs = newTime)
                }

                override fun lengthChanged(mediaPlayer: MediaPlayer?, newLength: Long) {
                    currentState = currentState.copy(durationMs = newLength)
                    onSnapshot(currentState)
                }

                override fun error(mediaPlayer: MediaPlayer?) {
                    println("$TAG: Event -> ERROR triggered by VLCJ!")
                    currentState = currentState.copy(isLoading = false, isPlaying = false, positionMs = bestPositionMs(), durationMs = bestDurationMs())
                    onSnapshot(currentState)
                    reportPlaybackError()
                }
            },
        )
    }

    fun currentSnapshot(): PlayerPlaybackSnapshot {
        if (!currentState.isPlaying) return currentState
        return try {
            var pos = mediaPlayer.status().time().coerceAtLeast(0L)
            val dur = mediaPlayer.status().length().coerceAtLeast(0L)
            val pending = pendingSeekTargetMs.get()
            if (pending >= 0L) {
                val settled = kotlin.math.abs(pos - pending) <= SEEK_SETTLE_TOLERANCE_MS
                val expired = System.currentTimeMillis() - pendingSeekStartedAtMs >= SEEK_SETTLE_TIMEOUT_MS
                if (settled || expired) {
                    pendingSeekTargetMs.compareAndSet(pending, -1L)
                } else {
                    pos = pending
                }
            }
            if (pos > 0L) lastGoodPositionMs.set(pos)
            if (dur > 0L) lastGoodDurationMs.set(dur)
            currentState.copy(positionMs = pos, durationMs = dur)
        } catch (_: Exception) {
            currentState
        }
    }

    private fun bestPositionMs() =
        lastGoodPositionMs.get().takeIf { it > 0L } ?: currentState.positionMs

    private fun bestDurationMs() =
        lastGoodDurationMs.get().takeIf { it > 0L } ?: currentState.durationMs

    override fun play() { mediaPlayer.controls().play() }

    // setPause(true), not controls().pause(): the latter is libVLC's *toggle*, so calling it
    // on an already-paused video resumes it. Callers here (sleep timer, MPRIS Pause, tray)
    // all mean "pause", and must be idempotent.
    override fun pause() { mediaPlayer.controls().setPause(true) }

    override fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        pendingSeekTargetMs.set(target)
        pendingSeekStartedAtMs = System.currentTimeMillis()
        println("$TAG: seekTo($target) playing=${mediaPlayer.status().isPlaying}")
        // Single setTime: the old setTime+setPosition pair issued two demux seeks
        // (two flushes + two network reopens) per UI seek for no benefit.
        if (!mediaPlayer.status().isPlaying) {
            // Paused seek: libVLC only applies the new time (and renders a fresh
            // frame) on a playing pipeline, so briefly resume around the seek.
            // Queried fresh from libVLC — currentState can lag mid-rebuffer and a
            // misfired pause() here would freeze playback with no auto-recovery.
            mediaPlayer.controls().play()
            mediaPlayer.controls().setTime(target)
            mediaPlayer.controls().setPause(true)
        } else {
            mediaPlayer.controls().setTime(target)
        }
        currentState = currentState.copy(positionMs = target)
        onSnapshot(currentState)
    }

    override fun seekBy(offsetMs: Long) {
        // Accumulate from the in-flight target — status().time() still reports the
        // pre-seek position while a seek is landing, so rapid ±10s presses would
        // each re-seek from the stale spot instead of stacking.
        val pending = pendingSeekTargetMs.get()
        val base = if (pending >= 0L) pending else bestPositionMs()
        seekTo(base + offsetMs)
    }

    override fun retry() {
        mediaPlayer.controls().stop()
        mediaPlayer.controls().play()
    }

    override fun setPlaybackSpeed(speed: Float) {
        mediaPlayer.controls().setRate(speed)
        currentState = currentState.copy(playbackSpeed = speed)
    }

    override fun getAudioTracks(): List<AudioTrack> = try {
        mediaPlayer.audio().trackDescriptions()?.mapNotNull { track ->
            AudioTrack(
                index = track.id(),
                id = track.id().toString(),
                label = track.description() ?: "Audio ${track.id()}",
                language = null,
                isSelected = false,
            )
        } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    override fun getSubtitleTracks(): List<SubtitleTrack> = try {
        mediaPlayer.subpictures().trackDescriptions()?.mapNotNull { track ->
            SubtitleTrack(
                index = track.id(),
                id = track.id().toString(),
                label = track.description() ?: "Subtitle ${track.id()}",
                language = null,
                isSelected = false,
                isForced = false,
            )
        } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    override fun selectAudioTrack(index: Int) {
        try { mediaPlayer.audio().setTrack(index) } catch (e: Exception) { onError(e) }
    }

    override fun selectSubtitleTrack(index: Int) {
        try { mediaPlayer.subpictures().setTrack(index) } catch (e: Exception) { onError(e) }
    }

    override fun setSubtitleUri(url: String) {
        try {
            externalSubtitleUri = url
            mediaPlayer.subpictures().setSubTitleFile(url)
        } catch (e: Exception) { onError(e) }
    }

    override fun clearExternalSubtitle() {
        try {
            externalSubtitleUri = null
            mediaPlayer.subpictures().setTrack(-1)
        } catch (e: Exception) { onError(e) }
    }

    override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
        clearExternalSubtitle()
        selectSubtitleTrack(trackIndex)
    }

    override fun applySubtitleStyle(style: SubtitleStyleState) {
        // VLCJ subtitle styling — no-op for now
    }

    override fun setSubtitleDelayMs(delayMs: Int) {
        try {
            subtitleDelayMs = delayMs
            mediaPlayer.subpictures().setDelay(delayMs.toLong() * 1000)
        } catch (e: Exception) { onError(e) }
    }

    override fun configureIosVideoOutput(settings: PlayerSettingsUiState) {
        // iOS-specific — no-op on desktop
    }

    override fun currentVolume(): PlayerAudioLevel? {
        return currentAudioLevel
    }

    override fun setVolume(level: Float): PlayerAudioLevel? {
        currentAudioLevel = currentAudioLevel.copy(
            fraction = level.coerceIn(0f, 1f),
            isMuted = level <= 0f
        )
        return try {
            val volInt = (level * 100).toInt().coerceIn(0, 100)
            mediaPlayer.audio().setVolume(volInt)
            mediaPlayer.audio().setMute(level <= 0f)
            currentAudioLevel
        } catch (_: Exception) { currentAudioLevel }
    }

    fun loadMedia(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        playWhenReady: Boolean,
        startPositionMs: Long = 0L,
        sourceAudioUrl: String? = null,
    ) {
        val cacheKey = "$sourceUrl@$startPositionMs@$sourceAudioUrl"
        if (cacheKey == lastLoadedUrl) {
            println("$TAG: loadMedia skipped (duplicate call) url=$sourceUrl startPositionMs=$startPositionMs")
            return
        }
        lastLoadedUrl = cacheKey
        lastSourceUrl = sourceUrl
        pendingSeekTargetMs.set(-1L)
        try {
            println("$TAG: loadMedia url=$sourceUrl playWhenReady=$playWhenReady startPositionMs=$startPositionMs audio=${sourceAudioUrl != null}")
            // libVLC has no generic per-request header option, but the two headers addon
            // proxyHeaders actually rely on map onto media options. Anything else is logged
            // so a failing header-protected stream is diagnosable.
            val userAgent = sourceHeaders.entries
                .firstOrNull { it.key.equals("user-agent", ignoreCase = true) }?.value
            val referer = sourceHeaders.entries
                .firstOrNull {
                    it.key.equals("referer", ignoreCase = true) ||
                        it.key.equals("referrer", ignoreCase = true)
                }?.value
            val unsupportedHeaderKeys = sourceHeaders.keys.filterNot {
                it.equals("user-agent", ignoreCase = true) ||
                    it.equals("referer", ignoreCase = true) ||
                    it.equals("referrer", ignoreCase = true)
            }
            if (unsupportedHeaderKeys.isNotEmpty()) {
                println("$TAG: loadMedia ignoring headers VLC cannot send: $unsupportedHeaderKeys")
            }
            val options = mutableListOf(":http-user-agent=${userAgent?.takeIf { it.isNotBlank() } ?: "NuvioMobile/1.0"}")
            if (!referer.isNullOrBlank()) {
                options += ":http-referrer=$referer"
            }
            if (startPositionMs > 0L) {
                options += ":start-time=${startPositionMs / 1000}"
            }
            // Separate audio track (e.g. YouTube trailers extract video + audio separately).
            if (!sourceAudioUrl.isNullOrBlank()) {
                options += ":input-slave=$sourceAudioUrl"
            }
            val optsArray = options.toTypedArray()

            if (playWhenReady) {
                mediaPlayer.media().play(sourceUrl, *optsArray)
            } else {
                mediaPlayer.media().prepare(sourceUrl, *optsArray)
            }
            currentState = currentState.copy(isLoading = true)
        } catch (e: Exception) {
            println("$TAG: loadMedia exception: ${e.message}")
            onError(e)
        }
    }

    // libVLC only reports a generic "playback error" with no HTTP detail. For an HTTP
    // source we probe it off the event thread so the user sees WHY it failed — plugin /
    // scraper links are usually short-lived signed URLs, so a 403/404 means "the link
    // expired, refresh sources", not "the app is broken". Non-HTTP sources (torrent://,
    // local files) keep the generic message.
    private fun reportPlaybackError() {
        val url = lastSourceUrl
        if (url == null || !(url.startsWith("http://", true) || url.startsWith("https://", true))) {
            onError(Exception("VLCJ playback error"))
            return
        }
        Thread(null, {
            val message = diagnosePlaybackFailure(url)
            println("$TAG: playback failure diagnosis -> $message")
            onError(Exception(message))
        }, "vlc-error-probe", 0).apply {
            isDaemon = true
            start()
        }
    }

    private fun diagnosePlaybackFailure(url: String): String {
        val parsed = try {
            java.net.URI.create(url).toURL()
        } catch (_: Exception) {
            return "VLCJ playback error"
        }
        return try {
            val connection = (parsed.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                // Ask for a single byte: we only want the status line, not the file.
                setRequestProperty("Range", "bytes=0-0")
                setRequestProperty("User-Agent", "NuvioMobile/1.0")
                instanceFollowRedirects = true
                connectTimeout = 4000
                readTimeout = 4000
            }
            val code = try { connection.responseCode } finally { connection.disconnect() }
            when (code) {
                in 200..399 ->
                    "Source opened but could not be played — likely an unsupported format or codec. Try a different source."
                401, 403 ->
                    "Source link was rejected (HTTP $code) — it has likely expired or is region-locked. Refresh sources and try again."
                404, 410 ->
                    "Source is no longer available (HTTP $code) — the link expired or was removed. Try a different source."
                in 500..599 ->
                    "The source server returned an error (HTTP $code). Try a different source."
                else ->
                    "Source returned HTTP $code. Refresh sources and try again."
            }
        } catch (_: java.net.UnknownHostException) {
            "Could not reach the source server (host not found). Try a different source."
        } catch (_: java.net.SocketTimeoutException) {
            "The source server did not respond in time. Try a different source."
        } catch (e: Exception) {
            "Could not reach the source — ${e.message ?: "connection failed"}. Try a different source."
        }
    }
}
