package com.nuvio.app.desktop.mpv

import com.nuvio.app.features.player.AudioTrack
import com.nuvio.app.features.player.PlayerAudioLevel
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerSettingsUiState
import com.nuvio.app.features.player.SubtitleStyleState
import com.nuvio.app.features.player.SubtitleTrack
import com.nuvio.app.features.player.redactSourceUrl
import com.nuvio.app.features.player.reportPlaybackFailureAsync
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "NuvioMpvController"

/** How long an in-flight seek target is trusted if mpv never reports the seek completing. */
private const val SEEK_SETTLE_TIMEOUT_MS = 8_000L

/**
 * How long a seek may be outstanding before the UI calls it loading. Comfortably longer than a
 * local seek (milliseconds) and far shorter than a user's patience with a frozen picture.
 */
private const val SEEK_SPINNER_DELAY_MS = 400L

/**
 * [PlayerEngineController] backed by libmpv.
 *
 * The point of this path is hardware decoding: libVLC silently forces `avcodec-hw=none`
 * whenever its video output is the buffer-callback surface this app renders through, so the
 * shipping VLCJ engine decodes 4K in software (~580% CPU). mpv has no such restriction.
 *
 * State comes from **observed properties**: mpv pushes `time-pos`, `duration`, `pause` and
 * friends to the event thread, which only updates fields here. [currentSnapshot] then reads
 * those fields, so the surface's existing 100 ms poll costs a field read rather than an FFI
 * round trip.
 *
 * ⚠ Deliberately no `onSnapshot` callback. Snapshot handling has real side effects — it
 * spawns the screensaver-inhibitor process, updates Discord, and writes Compose state — and
 * driving those from mpv's own event thread would run them off the main thread and at mpv's
 * cadence. The surface polls instead, which keeps the threading identical to the VLCJ path.
 */
internal class MpvPlayerController(
    private val mpv: MpvHandle,
    private val onError: (Exception) -> Unit,
) : PlayerEngineController {

    @Volatile private var state = PlayerPlaybackSnapshot(
        isLoading = false,
        isPlaying = false,
        isEnded = false,
        durationMs = 0L,
        positionMs = 0L,
        bufferedPositionMs = 0L,
        playbackSpeed = 1.0f,
    )

    // mpv reports the PRE-seek time until the demuxer lands, so a naive read would snap the
    // timeline back and invite repeat seeks. Unlike libVLC, mpv tells us when the seek
    // completed (PLAYBACK_RESTART), so this needs no tolerance heuristic — just a timeout in
    // case the event never arrives.
    private val pendingSeekTargetMs = AtomicLong(-1L)
    @Volatile private var pendingSeekStartedAtMs = 0L

    /**
     * When the outstanding seek was issued, or 0 when none is. Separate from
     * [pendingSeekTargetMs] on purpose: that one is abandoned after
     * [SEEK_SETTLE_TIMEOUT_MS] so the timeline stops lying about the position, but the seek
     * itself is still outstanding at that point and the UI still needs to say so.
     */
    @Volatile private var seekPendingSinceMs = 0L

    /**
     * Every mpv call the UI triggers runs here, never on the caller's thread.
     *
     * ⚠ This is not tidiness, it is the difference between a stalled stream and a frozen
     * application. `mpv_get_property` / `mpv_set_property` are SYNCHRONOUS: they hand the request
     * to mpv's core and wait. When the core is wedged — a demuxer blocked on a dead network read
     * is the ordinary case — they never return. Called from Compose's main thread, as they were,
     * that is a hard UI freeze: measured in a real session, the event thread healthy in
     * `mpv_wait_event` while the EDT sat in `mpv_get_property` for minutes, picture frozen on a
     * half-decoded frame and no spinner, because the thread that would have drawn one was the
     * thread that was stuck.
     *
     * Single-threaded on purpose: commands keep the order the user issued them in. Optimistic UI
     * state is still written synchronously by the caller, so the interface stays responsive.
     */
    private val control = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(null, r, "mpv-control", 0).apply { isDaemon = true }
    }

    @Volatile private var disposed = false

    /** Runs [block] on the control thread. Silently dropped after [dispose]. */
    private fun onControlThread(block: () -> Unit) {
        if (disposed) return
        runCatching {
            control.execute {
                if (!disposed) runCatching(block).onFailure {
                    println("$TAG: control call failed: ${it.message}")
                }
            }
        }
    }

    @Volatile private var fileLoaded = false
    @Volatile private var currentAudioLevel = PlayerAudioLevel(1.0f, false)
    @Volatile private var lastSourceUrl: String? = null
    @Volatile private var externalSubtitleUri: String? = null
    /** Track id created by sub-add, so it can be removed by id rather than by selection. */
    @Volatile private var externalSubtitleId: Int? = null

    /** Guards against the duplicate loadMedia calls Compose's LaunchedEffect can produce. */
    private var lastLoadedKey: String? = null

    /** Set by the render path so a decode that silently fell back to software is visible. */
    @Volatile var hwdecCurrent: String? = null
        private set

    init {
        // Observed before initialize() would be ideal, but the handle is already initialised
        // by the time a controller wraps it; mpv accepts observers at any point.
        mpv.observeProperty("time-pos", MpvFormat.DOUBLE)
        mpv.observeProperty("duration", MpvFormat.DOUBLE)
        mpv.observeProperty("pause", MpvFormat.FLAG)
        mpv.observeProperty("eof-reached", MpvFormat.FLAG)
        mpv.observeProperty("paused-for-cache", MpvFormat.FLAG)
        mpv.observeProperty("demuxer-cache-time", MpvFormat.DOUBLE)
        mpv.observeProperty("speed", MpvFormat.DOUBLE)
        mpv.onEvent = ::handleEvent
    }

    // --- event handling ---------------------------------------------------------------------

    private fun handleEvent(ev: MpvEventInfo) {
        when (ev.id) {
            MpvEventId.FILE_LOADED -> {
                fileLoaded = true
                // ⚠ Read `pause` here rather than waiting for a property-change notification.
                // loadMedia sets pause to the value it usually already has, so mpv has no
                // change to report and would never announce that playback started — leaving
                // isPlaying false forever on some load orderings.
                val paused = mpv.getPropertyBoolean("pause") == true
                state = state.copy(isLoading = isBuffering(), isEnded = false, isPlaying = !paused)
                // hwdec is only meaningful once a video track is actually decoding. This is the
                // runtime assertion the whole investigation turned on: every failure mode was
                // silent, producing correct-looking video at several times the CPU cost.
                hwdecCurrent = mpv.getPropertyString("hwdec-current")
                // Built here, on the event thread, so the menus can be served from a field
                // instead of a few dozen blocking property reads on the UI thread.
                refreshTrackCaches()
                // sid is logged because mpv picks a subtitle track on its own and the UI layer may
                // then override it; without this the two are indistinguishable from a screenshot.
                println(
                    "$TAG: file loaded, hwdec-current=$hwdecCurrent paused=$paused " +
                        "sid=${mpv.getPropertyString("sid")} aid=${mpv.getPropertyString("aid")}",
                )
            }
            MpvEventId.END_FILE -> {
                // No file is decoding any more either way, so isPlaying must not stay true.
                fileLoaded = false
                // Whatever a seek was waiting for, it is not coming — an error must surface as an
                // error, not as a spinner that never stops.
                seekPendingSinceMs = 0L
                when {
                    // The stream failed to open or died mid-play. Reported as an error rather
                    // than as an end-of-file, otherwise a dead source looks exactly like a
                    // finished episode: no error, and auto-play would advance past it.
                    ev.endFileReason == MpvEndFileReason.ERROR -> {
                        state = state.copy(isLoading = false, isPlaying = false)
                        reportPlaybackError(mpv.errorString(ev.endFileError))
                    }
                    // eof-reached distinguishes a real end-of-stream from the END_FILE mpv also
                    // emits when a file is replaced or stopped; only the former is "ended".
                    mpv.getPropertyBoolean("eof-reached") == true ->
                        state = state.copy(isEnded = true, isPlaying = false)
                    else -> state = state.copy(isPlaying = false)
                }
            }
            MpvEventId.PLAYBACK_RESTART -> {
                // The seek (or the initial load) has landed and time-pos is trustworthy again.
                pendingSeekTargetMs.set(-1L)
                seekPendingSinceMs = 0L
                // ⚠ Also the way back from end-of-file. `keep-open=yes` leaves the file loaded at
                // EOF, so a seek backwards resumes it — but END_FILE had cleared `fileLoaded` and
                // set `isEnded`, and nothing else ever cleared them. The player then stayed
                // "ended" forever: the picture never updated and play/seek did nothing.
                fileLoaded = true
                val paused = mpv.getPropertyBoolean("pause") == true
                // ⚠ NOT `isLoading = false`. A seek into an unbuffered region sets
                // `paused-for-cache` BEFORE playback restarts, and the flag then never changes
                // again — so clearing the spinner here left the player stalled with no spinner
                // and no way to get one back. Ask mpv what it is actually doing instead.
                state = state.copy(isLoading = isBuffering(), isEnded = false, isPlaying = !paused)
            }
            MpvEventId.PROPERTY_CHANGE -> handlePropertyChange(ev)
        }
    }

    private fun handlePropertyChange(ev: MpvEventInfo) {
        when (ev.propertyName) {
            "time-pos" -> {
                val posMs = ((ev.propertyDouble ?: return) * 1000).toLong().coerceAtLeast(0L)
                if (pendingSeekTargetMs.get() < 0L) state = state.copy(positionMs = posMs)
            }
            "duration" -> {
                val durMs = ((ev.propertyDouble ?: return) * 1000).toLong().coerceAtLeast(0L)
                if (durMs != state.durationMs) state = state.copy(durationMs = durMs)
            }
            "pause" -> {
                val paused = ev.propertyFlag ?: return
                // Do not report "playing" before a file exists: mpv starts unpaused while idle.
                state = state.copy(isPlaying = !paused && fileLoaded)
            }
            "eof-reached" -> {
                // Both directions: mpv clears this when a seek moves off the end, and the UI has
                // to leave its ended state with it.
                state = if (ev.propertyFlag == true) {
                    state.copy(isEnded = true, isPlaying = false)
                } else {
                    state.copy(isEnded = false)
                }
            }
            "paused-for-cache" -> {
                // Rebuffering, not an error — this is what drives the spinner.
                state = state.copy(isLoading = ev.propertyFlag == true)
            }
            "demuxer-cache-time" -> {
                // Absolute timestamp of the cache end, so it maps straight onto bufferedPosition.
                val cacheMs = ((ev.propertyDouble ?: return) * 1000).toLong().coerceAtLeast(0L)
                state = state.copy(bufferedPositionMs = cacheMs)
            }
            "speed" -> {
                val s = (ev.propertyDouble ?: return).toFloat()
                state = state.copy(playbackSpeed = s)
            }
        }
    }

    /**
     * Cheap by design — reads cached fields written by the event thread, so the existing
     * 10 Hz UI poll costs nothing. Only the in-flight seek needs any logic.
     */
    fun currentSnapshot(): PlayerPlaybackSnapshot {
        // ⚠ A seek that has not landed is the state that produced a frozen picture with NO
        // spinner: the position jumps to the target immediately, `paused-for-cache` reads back
        // null while the demuxer is being re-opened, and nothing else ever says "waiting". A seek
        // still outstanding after [SEEK_SPINNER_DELAY_MS] therefore reports as loading — long
        // enough that a local seek, which lands in milliseconds, never flashes one.
        val seekingSince = seekPendingSinceMs
        val seekWaiting = seekingSince != 0L &&
            System.currentTimeMillis() - seekingSince >= SEEK_SPINNER_DELAY_MS
        val base = if (seekWaiting && !state.isLoading) state.copy(isLoading = true) else state

        val pending = pendingSeekTargetMs.get()
        if (pending < 0L) return base
        // Safety net: if mpv never reported the seek landing, stop overriding the real position
        // rather than freezing the timeline on the target forever. The spinner above is NOT
        // dropped with it — the seek is still outstanding, and that is the whole point.
        if (System.currentTimeMillis() - pendingSeekStartedAtMs >= SEEK_SETTLE_TIMEOUT_MS) {
            pendingSeekTargetMs.compareAndSet(pending, -1L)
            return base
        }
        return base.copy(positionMs = pending)
    }

    // --- transport ---------------------------------------------------------------------------

    override fun play() = onControlThread {
        // ⚠ At end-of-file with `keep-open=yes`, clearing `pause` does nothing at all — mpv sits
        // on the last frame. Pressing play on a finished episode therefore has to seek off the
        // end first, which is also what libVLC does implicitly when it replays finished media.
        if (mpv.getPropertyBoolean("eof-reached") == true) {
            println("$TAG: play() at EOF — restarting from the beginning")
            // ⚠ Through seekTo, not a bare `seek` command. seekTo is what records that a seek is
            // outstanding, which is what puts a spinner up when one does not land — and an EOF
            // replay is precisely where that was first seen (a stream that never resumed, with
            // no spinner). A second code path issuing seeks would skip that bookkeeping.
            seekTo(0L)
        }
        mpv.setPropertyBoolean("pause", false)
    }

    /** Idempotent by construction: this sets the pause flag, it does not toggle it. */
    override fun pause() = onControlThread { mpv.setPropertyBoolean("pause", true) }

    override fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        // The bookkeeping and the optimistic position are set on the CALLER's thread, so the
        // timeline and the spinner react immediately even if mpv itself is slow to answer.
        pendingSeekTargetMs.set(target)
        pendingSeekStartedAtMs = System.currentTimeMillis()
        seekPendingSinceMs = pendingSeekStartedAtMs
        state = state.copy(positionMs = target)
        println("$TAG: seekTo($target)")
        onControlThread {
            // "absolute" + "keyframes" is mpv's fast seek; exact seeking on a 4K remux can stall
            // for seconds while it decodes forward to the precise frame.
            mpv.command("seek", (target / 1000.0).toString(), "absolute+keyframes")
        }
    }

    override fun seekBy(offsetMs: Long) {
        // Accumulate from the in-flight target, so rapid ±10s presses stack instead of each
        // re-seeking from the same stale position.
        val pending = pendingSeekTargetMs.get()
        val base = if (pending >= 0L) pending else state.positionMs
        seekTo(base + offsetMs)
    }

    override fun retry() {
        val url = lastSourceUrl ?: return
        // Re-issue the load rather than stop+start: mpv has no separate "reopen" and a stop
        // would drop the per-file options set alongside the original loadfile.
        lastLoadedKey = null
        fileLoaded = false
        // A retry re-opens the stream, which takes as long as the original open did — so it has
        // to say "loading" the way loadMedia does, or the UI sits on the error's frozen frame
        // with no sign that anything is happening.
        state = state.copy(isLoading = true, isEnded = false)
        onControlThread { mpv.command("loadfile", url, "replace") }
    }

    /**
     * Fit (letterbox) vs Fill (stretch), done inside mpv while it scales to the surface.
     *
     * Lives on the controller rather than on the session so it goes through the same control
     * thread as every other mpv call — it is issued from a Compose effect on the main thread.
     */
    fun setKeepAspect(keep: Boolean) = onControlThread {
        mpv.setPropertyBoolean("keepaspect", keep)
    }

    override fun setPlaybackSpeed(speed: Float) {
        state = state.copy(playbackSpeed = speed)
        onControlThread { mpv.setPropertyDouble("speed", speed.toDouble()) }
    }

    // --- tracks ------------------------------------------------------------------------------

    /**
     * Read via mpv's indexed sub-properties (`track-list/N/...`) rather than by parsing the
     * `track-list` JSON blob — no serialization dependency, and immune to schema changes in
     * fields we do not use.
     */
    private fun tracks(wantType: String): List<MpvTrack> {
        val count = mpv.getPropertyLong("track-list/count")?.toInt() ?: return emptyList()
        val out = ArrayList<MpvTrack>(count)
        for (i in 0 until count) {
            if (mpv.getPropertyString("track-list/$i/type") != wantType) continue
            val id = mpv.getPropertyLong("track-list/$i/id")?.toInt() ?: continue
            val lang = mpv.getPropertyString("track-list/$i/lang")
            val title = mpv.getPropertyString("track-list/$i/title")
            out += MpvTrack(
                id = id,
                lang = lang,
                title = title,
                selected = mpv.getPropertyBoolean("track-list/$i/selected") == true,
                external = mpv.getPropertyBoolean("track-list/$i/external") == true,
                codec = mpv.getPropertyString("track-list/$i/codec"),
            )
        }
        return out
    }

    /** mpv leaves title/lang null on plenty of streams, so build something readable regardless. */
    private fun MpvTrack.label(kind: String): String =
        listOfNotNull(title, lang?.uppercase(), codec?.uppercase())
            .distinct()
            .joinToString(" · ")
            .ifBlank { "$kind $id" }

    /**
     * ⚠ Served from a cache, refreshed when a file loads or the subtitle set changes.
     *
     * Building these lists costs six blocking property reads PER TRACK, and the UI asks for them
     * from the main thread the instant a menu opens — on a stalled stream that is a frozen app,
     * for the same reason the pacing report was (see [control]).
     */
    @Volatile private var cachedAudioTracks: List<AudioTrack> = emptyList()
    @Volatile private var cachedSubtitleTracks: List<SubtitleTrack> = emptyList()

    /** Called from the event thread or the control thread — never from the UI. */
    private fun refreshTrackCaches() {
        cachedAudioTracks = runCatching {
            tracks("audio").map {
                AudioTrack(
                    index = it.id,
                    id = it.id.toString(),
                    label = it.label("Audio"),
                    language = it.lang,
                    isSelected = it.selected,
                )
            }
        }.getOrDefault(emptyList())
        cachedSubtitleTracks = runCatching {
            tracks("sub").map {
                SubtitleTrack(
                    index = it.id,
                    id = it.id.toString(),
                    label = it.label("Subtitle"),
                    language = it.lang,
                    // See the note on getSubtitleTracks: deliberately false, to match VLCJ.
                    isSelected = false,
                    isForced = false,
                )
            }
        }.getOrDefault(emptyList())
    }

    override fun getAudioTracks(): List<AudioTrack> = cachedAudioTracks

    /**
     * ⚠ `isSelected` is reported as **false even for the track mpv has selected**, deliberately,
     * to match the VLCJ path.
     *
     * `PlayerScreenRuntimeTrackActions` turns subtitles off when no preferred subtitle language is
     * set *and* the engine reports a track selected. `VlcjPlayerController` hardcodes false, so
     * that branch has never fired on desktop and the container's default subtitle plays — which is
     * the behaviour desktop users have. Reporting honestly here silently turned embedded subtitles
     * off on every default install, so the stub is mirrored on purpose rather than by accident.
     *
     * Nothing in the UI reads this: `SubtitleModal` marks the active row from the screen's own
     * `selectedIndex`, so the menu still shows the user's choice correctly.
     */
    override fun getSubtitleTracks(): List<SubtitleTrack> = cachedSubtitleTracks

    override fun selectAudioTrack(index: Int) = onControlThread {
        mpv.setPropertyLong("aid", index.toLong())
    }

    override fun selectSubtitleTrack(index: Int) {
        println("$TAG: selectSubtitleTrack($index)")
        onControlThread {
            // A negative index is the UI's "none"; mpv spells that "no", and setting sid to a
            // negative number would be rejected.
            if (index < 0) mpv.setPropertyString("sid", "no")
            else mpv.setPropertyLong("sid", index.toLong())
        }
    }

    override fun setSubtitleUri(url: String) {
        externalSubtitleUri = url
        onControlThread {
            // "select" makes it the active track immediately; "cached" would leave it inactive.
            mpv.command("sub-add", url, "select")
            // Remember WHICH track this created. sub-add selects it, so sid now names it —
            // needed because sub-remove without an id targets whatever is selected later.
            externalSubtitleId = mpv.getPropertyLong("sid")?.toInt()
            refreshTrackCaches()
        }
    }

    override fun clearExternalSubtitle() = onControlThread {
        // ⚠ Remove BY ID. A bare `sub-remove` targets the currently selected track, so if
        // the user added an external subtitle and then switched to an embedded one, this
        // would delete the embedded track instead of the external one.
        externalSubtitleId?.let { mpv.command("sub-remove", it.toString()) }
        externalSubtitleId = null
        externalSubtitleUri = null
        mpv.setPropertyString("sid", "no")
        refreshTrackCaches()
    }

    override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
        // Both hop to the control thread, and it is single-threaded, so the removal is still
        // guaranteed to happen before the selection.
        clearExternalSubtitle()
        selectSubtitleTrack(trackIndex)
    }

    override fun applySubtitleStyle(style: SubtitleStyleState) {
        // Subtitle appearance is applied as engine options at handle creation
        // (see MpvEngineOptions), matching how the VLCJ path configures freetype.
    }

    override fun setSubtitleDelayMs(delayMs: Int) = onControlThread {
        // mpv's sub-delay is in SECONDS as a float; passing milliseconds would put subtitles
        // minutes out of sync.
        mpv.setPropertyDouble("sub-delay", delayMs / 1000.0)
    }

    override fun configureIosVideoOutput(settings: PlayerSettingsUiState) {
        // iOS-specific — no-op on desktop.
    }

    // --- volume ------------------------------------------------------------------------------

    override fun currentVolume(): PlayerAudioLevel? = currentAudioLevel

    override fun setVolume(level: Float): PlayerAudioLevel? {
        val clamped = level.coerceIn(0f, 1f)
        // Reported back immediately: the volume overlay must track the scroll wheel, not mpv.
        currentAudioLevel = currentAudioLevel.copy(fraction = clamped, isMuted = clamped <= 0f)
        onControlThread {
            mpv.setPropertyLong("volume", (clamped * 100).toLong().coerceIn(0L, 100L))
            mpv.setPropertyBoolean("mute", clamped <= 0f)
        }
        return currentAudioLevel
    }

    // --- loading -----------------------------------------------------------------------------

    fun loadMedia(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        playWhenReady: Boolean,
        startPositionMs: Long = 0L,
        sourceAudioUrl: String? = null,
    ) {
        val key = "$sourceUrl@$startPositionMs@$sourceAudioUrl"
        if (key == lastLoadedKey) {
            println("$TAG: loadMedia skipped (duplicate) url=${redactSourceUrl(sourceUrl)}")
            return
        }
        lastLoadedKey = key
        lastSourceUrl = sourceUrl
        pendingSeekTargetMs.set(-1L)
        // A seek against the OUTGOING file is void; leaving it outstanding would leave the new
        // file showing a spinner it has no reason to.
        seekPendingSinceMs = 0L
        fileLoaded = false

        // The spinner goes up on the CALLER's thread so the UI reacts to the click, not to mpv.
        state = state.copy(isLoading = true, isEnded = false)

        onControlThread {
            println("$TAG: loadMedia url=${redactSourceUrl(sourceUrl)} playWhenReady=$playWhenReady start=$startPositionMs")
            applyRequestHeaders(sourceHeaders)

            // Per-file settings are applied as PROPERTIES rather than as loadfile's trailing
            // options argument. ⚠ loadfile's positional signature changed: mpv 0.38 inserted an
            // <index> parameter before <options>, so any positional options string is either
            // rejected ("invalid parameter") or silently misread depending on the mpv version.
            // Properties behave identically on every version.
            //
            // "none" is mpv's explicit "start at the beginning" — leaving a stale `start` set
            // would make the NEXT video resume at the previous one's position.
            mpv.setPropertyString(
                "start",
                if (startPositionMs > 0L) (startPositionMs / 1000.0).toString() else "none",
            )
            mpv.setPropertyBoolean("pause", !playWhenReady)
            // A separate audio stream (YouTube trailers arrive as split video + audio). Always
            // assigned, so a previous video's extra audio track cannot leak into this one.
            mpv.setPropertyString("audio-files", sourceAudioUrl ?: "")

            mpv.command("loadfile", sourceUrl, "replace")
        }
    }

    private fun applyRequestHeaders(sourceHeaders: Map<String, String>) {
        val ua = sourceHeaders.entries.firstOrNull { it.key.equals("user-agent", true) }?.value
        val referer = sourceHeaders.entries.firstOrNull {
            it.key.equals("referer", true) || it.key.equals("referrer", true)
        }?.value

        mpv.setPropertyString("user-agent", ua?.takeIf { it.isNotBlank() } ?: "NuvioMobile/1.0")
        if (!referer.isNullOrBlank()) mpv.setPropertyString("referrer", referer)

        // Everything else goes through http-header-fields. libVLC could only ever send
        // user-agent and referer, so header-protected sources that failed on the VLCJ path
        // can work here.
        val rest = sourceHeaders.filterKeys {
            !it.equals("user-agent", true) && !it.equals("referer", true) &&
                !it.equals("referrer", true) && !it.equals("range", true)
        }
        mpv.setPropertyString(
            "http-header-fields",
            if (rest.isEmpty()) "" else rest.entries.joinToString(",") {
                escapeListItem("${it.key}: ${it.value}")
            },
        )
    }

    /**
     * Writes the current frame at its **source** resolution, which the render path cannot do —
     * mpv scales to the window while decoding, so the delivered frame is window-sized.
     * "subtitles" includes rendered subtitles, matching what the VLCJ path captured.
     */
    /**
      * ⚠ Fire-and-forget, and it reports success optimistically: writing a 4K PNG takes mpv long
      * enough to be felt, and the "S" key that triggers it is pressed on the UI thread. Waiting
      * for the real answer would stutter the player for a screenshot.
      */
    fun saveScreenshot(path: String): Boolean {
        if (!fileLoaded) return false
        onControlThread { mpv.command("screenshot-to-file", path, "subtitles") }
        return true
    }

    /** Diagnostics for the frame pump: audio/video drift and mpv's own dropped-frame counters. */
    fun pacingReport(): String {
        val avsync = mpv.getPropertyDouble("avsync")
        val dropped = mpv.getPropertyLong("frame-drop-count")
        val delayed = mpv.getPropertyLong("vo-delayed-frame-count")
        val fps = mpv.getPropertyDouble("estimated-vf-fps")
        val pacing = "avsync=%.3f dropped=%s delayed=%s vf-fps=%.1f".format(
            avsync ?: 0.0, dropped ?: -1L, delayed ?: -1L, fps ?: 0.0,
        )
        // ⚠ Why the state fields are here and not only in a debug build: a stalled picture looks
        // identical whatever caused it — paused, buffering, ended, or the demuxer wedged on a
        // dead link. Without these a stall can only be guessed at after the fact, which is
        // exactly what happened to the one stall this path has produced so far.
        val state = "pause=%s core-idle=%s paused-for-cache=%s buffering=%s%% cache-time=%.1f eof=%s".format(
            mpv.getPropertyBoolean("pause"),
            mpv.getPropertyBoolean("core-idle"),
            mpv.getPropertyBoolean("paused-for-cache"),
            mpv.getPropertyLong("cache-buffering-state") ?: -1L,
            mpv.getPropertyDouble("demuxer-cache-time") ?: -1.0,
            mpv.getPropertyBoolean("eof-reached"),
        )
        return "$pacing  $state"
    }

    /**
     * mpv's own answer to "am I waiting for the network right now", read live rather than
     * tracked. The observed `paused-for-cache` notification only fires on a CHANGE, so any code
     * that resets the loading state has to consult the current value or it will silently
     * contradict mpv.
     */
    private fun isBuffering(): Boolean = mpv.getPropertyBoolean("paused-for-cache") == true

    /**
     * mpv's own message ("loading failed") says nothing the user can act on, so an HTTP source
     * is probed for the real reason — the same treatment the VLCJ path gets. Runs off mpv's
     * event thread, which must never block.
     */
    private fun reportPlaybackError(mpvMessage: String) {
        println("$TAG: playback failed: $mpvMessage")
        reportPlaybackFailureAsync(lastSourceUrl, mpvMessage, TAG) { onError(Exception(it)) }
    }

    fun dispose() {
        disposed = true
        mpv.onEvent = null
        // ⚠ Do NOT wait for the control thread here. If it is blocked inside a wedged mpv call —
        // the very case this whole indirection exists for — joining it would hang whatever is
        // disposing the player. It is a daemon thread and every task checks `disposed` first,
        // so abandoning it is safe; mpv_terminate_destroy below unblocks it anyway.
        control.shutdownNow()
        mpv.dispose()
    }
}

private data class MpvTrack(
    val id: Int,
    val lang: String?,
    val title: String?,
    val selected: Boolean,
    val external: Boolean,
    val codec: String?,
)

/**
 * mpv's percent escaping for list-option items: `%<byte-length>%<value>`.
 *
 * Header values legitimately contain commas (Cookie, Accept), which is the list separator —
 * without this, one such header silently corrupts every header after it. The length is in
 * BYTES, not characters, so non-ASCII values escape correctly too.
 */
internal fun escapeListItem(value: String): String =
    "%${value.toByteArray(Charsets.UTF_8).size}%$value"
