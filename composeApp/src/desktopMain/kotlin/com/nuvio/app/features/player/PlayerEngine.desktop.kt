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

    val renderCallback = remember {
        object : RenderCallback {
            override fun display(
                mediaPlayer: MediaPlayer,
                nativeBuffers: Array<out ByteBuffer>,
                bufferFormat: BufferFormat,
            ) {
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

                scope.launch(Dispatchers.Main) {
                    try {
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


    // Wire up the player controller
    DisposableEffect(sourceUrl, sourceAudioUrl, sourceHeaders) {
        println("$TAG: Creating VlcjPlayerController for $sourceUrl")

        val controller = VlcjPlayerController(
            mediaPlayer = mediaPlayer,
            onSnapshot = latestOnSnapshot.value,
            onError = { error ->
                println("$TAG: PlayerController error: ${error.message}")
                latestOnError.value(error.message ?: "Unknown error")
            },
        )
        playerController = controller
        onControllerReady(controller)
        PlayerControlBridge.controller = controller

        onDispose {
            println("$TAG: Disposing player for $sourceUrl")
            playerController = null
            PlayerControlBridge.controller = null
            PlayerControlBridge.isPlaying = false
            // stop() can block for 500ms–2s while VLC flushes buffers and closes the
            // network connection. Running it on a daemon thread keeps the Compose render
            // thread free so the next screen's buttons remain responsive immediately.
            val mp = mediaPlayer
            Thread(null, { try { mp.controls().stop() } catch (_: Exception) {} }, "vlc-stop", 0).also {
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
            controller.loadMedia(sourceUrl, sourceHeaders, playWhenReady, startPositionMs)
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

    // Guard against duplicate loadMedia calls (LaunchedEffect can fire twice when
    // sourceHeaders updates after onControllerReady triggers a PlayerScreen recomposition).
    private var lastLoadedUrl: String? = null

    private var externalSubtitleUri: String? = null
    private var subtitleDelayMs: Int = 0
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
                    onError(Exception("VLCJ playback error"))
                }
            },
        )
    }

    fun currentSnapshot(): PlayerPlaybackSnapshot {
        if (!currentState.isPlaying) return currentState
        return try {
            val pos = mediaPlayer.status().time().coerceAtLeast(0L)
            val dur = mediaPlayer.status().length().coerceAtLeast(0L)
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
        val doSeek = {
            mediaPlayer.controls().setTime(target)
            if (currentState.durationMs > 0) {
                mediaPlayer.controls().setPosition(target.toFloat() / currentState.durationMs.toFloat())
            }
        }
        
        if (!currentState.isPlaying) {
            mediaPlayer.controls().play()
            doSeek()
            mediaPlayer.controls().pause()
        } else {
            doSeek()
        }
        currentState = currentState.copy(positionMs = target)
        onSnapshot(currentState)
    }

    override fun seekBy(offsetMs: Long) {
        seekTo(mediaPlayer.status().time() + offsetMs)
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
    ) {
        val cacheKey = "$sourceUrl@$startPositionMs"
        if (cacheKey == lastLoadedUrl) {
            println("$TAG: loadMedia skipped (duplicate call) url=$sourceUrl startPositionMs=$startPositionMs")
            return
        }
        lastLoadedUrl = cacheKey
        try {
            println("$TAG: loadMedia url=$sourceUrl playWhenReady=$playWhenReady startPositionMs=$startPositionMs")
            val options = mutableListOf(":http-user-agent=NuvioMobile/1.0")
            if (startPositionMs > 0L) {
                options += ":start-time=${startPositionMs / 1000}"
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
}
