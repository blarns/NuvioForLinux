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

private var vlcjFactory: MediaPlayerFactory? = null

private fun getVlcjFactory(): MediaPlayerFactory {
    return vlcjFactory ?: run {
        val hwAccel = PlayerSettingsStorage.loadHwAccelEnabled() ?: true
        val audioOutput = PlayerSettingsStorage.loadAudioOutput()
        val args = buildList {
            if (hwAccel) add("--avcodec-hw=any")
            if (!audioOutput.isNullOrBlank()) add("--aout=$audioOutput")
        }.toTypedArray()
        val factory = MediaPlayerFactory(*args)
        vlcjFactory = factory
        factory
    }
}

// ---------------------------------------------------------------------------
// ScreensaverInhibitor — keep the desktop awake while video is playing.
//
// VLCJ renders via the buffer-callback API (no native video window), so libVLC's
// own screensaver suppression never engages and the desktop blanks/locks mid-movie.
// We hold a D-Bus inhibitor over a tiny python3-gi helper for as long as it runs:
//   • org.gnome.SessionManager.Inhibit(flags=8 idle) — on GNOME/Cinnamon this one
//     inhibitor covers the screensaver, display power-off AND auto-suspend (all key
//     off the session idle state).
//   • org.freedesktop.ScreenSaver.Inhibit — cross-DE fallback (KDE/XFCE/etc.).
// The inhibitor auto-releases the moment the helper's bus connection drops, so the
// helper blocks on stdin: closing it releases gracefully, and if the JVM dies/crashes
// the pipe closes too (EOF) — the screensaver can never be left suppressed forever.
// ---------------------------------------------------------------------------

private object ScreensaverInhibitor {
    private const val ITAG = "NuvioScreensaver"
    private val lock = Any()
    private var process: Process? = null
    private var spawnAtMs = 0L
    private var quickFailures = 0
    @Volatile private var unavailable = false   // latched once we know the helper can't run here

    private val HELPER_SCRIPT = """
        import sys, gi
        from gi.repository import Gio, GLib
        bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
        def inhibit(dest, path, iface, sig, args):
            try:
                bus.call_sync(dest, path, iface, "Inhibit", GLib.Variant(sig, args),
                              GLib.VariantType("(u)"), Gio.DBusCallFlags.NONE, -1, None)
            except Exception:
                pass
        inhibit("org.gnome.SessionManager", "/org/gnome/SessionManager", "org.gnome.SessionManager",
                "(susu)", ("Nuvio", 0, "Playing media", 8))
        inhibit("org.freedesktop.ScreenSaver", "/org/freedesktop/ScreenSaver", "org.freedesktop.ScreenSaver",
                "(ss)", ("Nuvio", "Playing media"))
        sys.stdin.read()
    """.trimIndent()

    fun inhibit() {
        if (unavailable) return
        synchronized(lock) {
            if (unavailable) return
            val existing = process
            if (existing != null) {
                if (existing.isAlive) return   // already inhibiting — no-op (hot path, ~10x/sec)
                // The helper exited on its own. If it died almost immediately after spawning it's
                // broken here (no python3-gi, or a bundled-vs-system lib clash) — latch off after a
                // couple of fast failures so onSnapshot's 10x/sec cadence can't turn a broken helper
                // into a spawn-crash loop. A helper that ran a while then died (e.g. bus restart) is
                // retried cleanly.
                if (System.currentTimeMillis() - spawnAtMs < 2_000L) {
                    if (++quickFailures >= 2) {
                        unavailable = true
                        process = null
                        println("$ITAG: helper keeps exiting immediately; giving up (screensaver not suppressed)")
                        return
                    }
                } else {
                    quickFailures = 0
                }
                process = null
            }
            process = try {
                spawnAtMs = System.currentTimeMillis()
                ProcessBuilder("python3", "-c", HELPER_SCRIPT)
                    // The child must load the SYSTEM glib/gobject/python3-gi, not the bundled
                    // Ubuntu-22.04 libs the AppImage's AppRun puts on LD_LIBRARY_PATH — that
                    // mismatch makes `import gi` fail (undefined glib symbol). Harmless under the .deb.
                    .apply { environment().remove("LD_LIBRARY_PATH") }
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .also { println("$ITAG: screensaver inhibited (playing)") }
            } catch (e: Exception) {
                unavailable = true
                println("$ITAG: inhibitor unavailable (${e.message}); screensaver will not be suppressed")
                null
            }
        }
    }

    fun release() {
        synchronized(lock) {
            val p = process ?: return
            process = null
            // Close the helper's stdin → EOF → it exits and the bus connection drops,
            // auto-releasing the inhibitor. destroyForcibly() is a non-blocking backstop.
            try { p.outputStream.close() } catch (_: Exception) {}
            try { p.destroyForcibly() } catch (_: Exception) {}
            println("$ITAG: screensaver released")
        }
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

    // When playback ends/stops, libVLC drains a few trailing/black frames as the decoder
    // flushes; painting them makes the video "blink" at the end of an episode/movie. Once
    // ended, freeze the last good frame and ignore further callbacks until real playback
    // resumes (e.g. the next episode), so the end transition stays clean.
    //
    // frozen alone is racy: the finished event (vlcj event thread) has to beat the flush
    // frames (render thread) AND any frame conversions already queued on Main. Whether it
    // wins varies with the libVLC build and thread scheduling — which is how the blink
    // came back. nearEnd closes the race from the other side: inside the final 2s of the
    // video, uniformly-black frames are dropped on arrival, no event ordering required.
    val frozen = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val nearEnd = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

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
                        currentFrame = Image.makeRaster(imageInfo, bytes, w * 4)
                            .toComposeImageBitmap()
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

    val mediaPlayer = remember {
        println("$TAG: Creating EmbeddedMediaPlayer with buffer callbacks")
        val factory = getVlcjFactory()
        val player = factory.mediaPlayers().newEmbeddedMediaPlayer()
        val videoSurface = factory.videoSurfaces().newVideoSurface(
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
            onSnapshot = { snap ->
                // Freeze frame rendering once ended; re-enable when real playback resumes.
                if (snap.isEnded) frozen.set(true) else if (snap.isPlaying) frozen.set(false)
                // Arm black-frame dropping for the final 2s (see nearEnd above).
                nearEnd.set(snap.durationMs > 0 && snap.positionMs >= snap.durationMs - 2_000)
                // Keep the desktop awake only while actually playing (idempotent — this
                // fires ~10x/sec from the poll loop, so it no-ops unless state changed).
                if (snap.isPlaying) ScreensaverInhibitor.inhibit() else ScreensaverInhibitor.release()
                // Mirror the now-playing status to Discord (opt-in). update() is cheap and
                // debounced internally, so calling it at the poll's ~10Hz is fine.
                if (PlayerSettingsStorage.loadDiscordRichPresenceEnabled() == true) {
                    DiscordRichPresence.update(snap)
                }
                // Feed live position/duration to MPRIS and nudge it to re-emit metadata
                // only when playing-state or duration actually changes (Position is polled
                // by clients, so it is intentionally NOT signalled every tick).
                val statusOrDurationChanged =
                    PlayerControlBridge.isPlaying != snap.isPlaying ||
                        PlayerControlBridge.durationMs != snap.durationMs
                PlayerControlBridge.positionMs = snap.positionMs
                PlayerControlBridge.durationMs = snap.durationMs
                PlayerControlBridge.hasMedia = !snap.isEnded
                if (statusOrDurationChanged) PlayerControlBridge.onNowPlayingChanged?.invoke()
                latestOnSnapshot.value(snap)
            },
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
            // Drop the screensaver inhibitor in case we left mid-playback (navigating
            // away may not deliver a final paused/stopped snapshot before disposal).
            ScreensaverInhibitor.release()
            // Clear the Discord presence for the same reason.
            DiscordRichPresence.clear()
            // Drop the last captured frame so the screenshot hotkey can't grab a stale
            // frame from a video we already left.
            LastFrameStore.clear()
            // stop() can block for 500ms–2s while VLC flushes buffers and closes the
            // network connection. Running it on a daemon thread keeps the Compose render
            // thread free so the next screen's buttons remain responsive immediately.
            // release() must follow stop() or the native player (and its video surface)
            // leaks on every playback session.
            val mp = mediaPlayer
            Thread(null, {
                try { mp.controls().stop() } catch (_: Exception) {}
                try { mp.release() } catch (_: Exception) {}
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
                playerController?.let { latestOnSnapshot.value(it.currentSnapshot()) }
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

    override fun pause() { mediaPlayer.controls().pause() }

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
            mediaPlayer.controls().pause()
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

// End-of-stream flush frames are a single solid color across the whole picture:
// black (zeroed RGB), green (zeroed YUV converted to RGB), or dark grey, depending
// on the libVLC build. Sample a sparse 5x5 grid (25 pixels) instead of scanning the
// buffer — cheap enough for the render thread, and only called during the final
// seconds of playback. BGRA byte order. Returns a short signature string for the
// dropped frame (for debug logs), or null when the frame is real content.
private fun flushFrameSignature(bytes: ByteArray, w: Int, h: Int): String? {
    if (w <= 1 || h <= 1) return null
    val steps = 5
    var minB = 255; var maxB = 0
    var minG = 255; var maxG = 0
    var minR = 255; var maxR = 0
    for (yi in 0 until steps) {
        val y = (h - 1) * yi / (steps - 1)
        for (xi in 0 until steps) {
            val x = (w - 1) * xi / (steps - 1)
            val i = (y * w + x) * 4
            if (i + 2 >= bytes.size) return null
            val b = bytes[i].toInt() and 0xFF
            val g = bytes[i + 1].toInt() and 0xFF
            val r = bytes[i + 2].toInt() and 0xFF
            if (b < minB) minB = b; if (b > maxB) maxB = b
            if (g < minG) minG = g; if (g > maxG) maxG = g
            if (r < minR) minR = r; if (r > maxR) maxR = r
        }
    }
    // Solid color = negligible spread on every channel across the whole picture.
    val uniform = (maxB - minB) <= 8 && (maxG - minG) <= 8 && (maxR - minR) <= 8
    if (!uniform) return null
    val dark = maxR <= 40 && maxG <= 40 && maxB <= 40
    val green = maxG >= 60 && maxR <= 40 && maxB <= 40
    return if (dark || green) "rgb($minR-$maxR,$minG-$maxG,$minB-$maxB)" else null
}
