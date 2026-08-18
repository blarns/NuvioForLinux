package com.nuvio.app.desktop.mpv

import com.nuvio.app.features.player.AudioTrack
import com.nuvio.app.features.player.PlayerAudioLevel
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerSettingsUiState
import com.nuvio.app.features.player.SubtitleStyleState
import com.nuvio.app.features.player.SubtitleTrack
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "NuvioMpvController"

/** How long an in-flight seek target is trusted if mpv never reports the seek completing. */
private const val SEEK_SETTLE_TIMEOUT_MS = 8_000L

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
                state = state.copy(isLoading = false, isEnded = false, isPlaying = !paused)
                // hwdec is only meaningful once a video track is actually decoding. This is the
                // runtime assertion the whole investigation turned on: every failure mode was
                // silent, producing correct-looking video at several times the CPU cost.
                hwdecCurrent = mpv.getPropertyString("hwdec-current")
                println("$TAG: file loaded, hwdec-current=$hwdecCurrent paused=$paused")
            }
            MpvEventId.END_FILE -> {
                // No file is decoding any more either way, so isPlaying must not stay true.
                fileLoaded = false
                // eof-reached distinguishes a real end-of-stream from the END_FILE mpv also
                // emits when a file is replaced or stopped; only the former is "ended".
                if (mpv.getPropertyBoolean("eof-reached") == true) {
                    state = state.copy(isEnded = true, isPlaying = false)
                } else {
                    state = state.copy(isPlaying = false)
                }
            }
            MpvEventId.PLAYBACK_RESTART -> {
                // The seek (or the initial load) has landed and time-pos is trustworthy again.
                pendingSeekTargetMs.set(-1L)
                state = state.copy(isLoading = false)
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
                if (ev.propertyFlag == true) state = state.copy(isEnded = true, isPlaying = false)
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
        val pending = pendingSeekTargetMs.get()
        if (pending < 0L) return state
        // Safety net: if mpv never reported the seek landing, stop overriding the real position
        // rather than freezing the timeline on the target forever.
        if (System.currentTimeMillis() - pendingSeekStartedAtMs >= SEEK_SETTLE_TIMEOUT_MS) {
            pendingSeekTargetMs.compareAndSet(pending, -1L)
            return state
        }
        return state.copy(positionMs = pending)
    }

    // --- transport ---------------------------------------------------------------------------

    override fun play() { mpv.setPropertyBoolean("pause", false) }

    /** Idempotent by construction: this sets the pause flag, it does not toggle it. */
    override fun pause() { mpv.setPropertyBoolean("pause", true) }

    override fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        pendingSeekTargetMs.set(target)
        pendingSeekStartedAtMs = System.currentTimeMillis()
        // "absolute" + "keyframes" is mpv's fast seek; exact seeking on a 4K remux can stall
        // for seconds while it decodes forward to the precise frame.
        mpv.command("seek", (target / 1000.0).toString(), "absolute+keyframes")
        state = state.copy(positionMs = target)
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
        mpv.command("loadfile", url, "replace")
    }

    override fun setPlaybackSpeed(speed: Float) {
        mpv.setPropertyDouble("speed", speed.toDouble())
        state = state.copy(playbackSpeed = speed)
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

    override fun getAudioTracks(): List<AudioTrack> = runCatching {
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

    override fun getSubtitleTracks(): List<SubtitleTrack> = runCatching {
        tracks("sub").map {
            SubtitleTrack(
                index = it.id,
                id = it.id.toString(),
                label = it.label("Subtitle"),
                language = it.lang,
                isSelected = it.selected,
                isForced = false,
            )
        }
    }.getOrDefault(emptyList())

    override fun selectAudioTrack(index: Int) {
        runCatching { mpv.setPropertyLong("aid", index.toLong()) }.onFailure { onError(it as Exception) }
    }

    override fun selectSubtitleTrack(index: Int) {
        // A negative index is the UI's "none"; mpv spells that "no", and setting sid to a
        // negative number would be rejected.
        if (index < 0) mpv.setPropertyString("sid", "no")
        else runCatching { mpv.setPropertyLong("sid", index.toLong()) }
            .onFailure { onError(it as Exception) }
    }

    override fun setSubtitleUri(url: String) {
        runCatching {
            externalSubtitleUri = url
            // "select" makes it the active track immediately; "cached" would leave it inactive.
            mpv.command("sub-add", url, "select")
            // Remember WHICH track this created. sub-add selects it, so sid now names it —
            // needed because sub-remove without an id targets whatever is selected later.
            externalSubtitleId = mpv.getPropertyLong("sid")?.toInt()
        }.onFailure { onError(it as Exception) }
    }

    override fun clearExternalSubtitle() {
        runCatching {
            // ⚠ Remove BY ID. A bare `sub-remove` targets the currently selected track, so if
            // the user added an external subtitle and then switched to an embedded one, this
            // would delete the embedded track instead of the external one.
            externalSubtitleId?.let { mpv.command("sub-remove", it.toString()) }
            externalSubtitleId = null
            externalSubtitleUri = null
            mpv.setPropertyString("sid", "no")
        }.onFailure { onError(it as Exception) }
    }

    override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
        clearExternalSubtitle()
        selectSubtitleTrack(trackIndex)
    }

    override fun applySubtitleStyle(style: SubtitleStyleState) {
        // Subtitle appearance is applied as engine options at handle creation
        // (see MpvEngineOptions), matching how the VLCJ path configures freetype.
    }

    override fun setSubtitleDelayMs(delayMs: Int) {
        // mpv's sub-delay is in SECONDS as a float; passing milliseconds would put subtitles
        // minutes out of sync.
        runCatching { mpv.setPropertyDouble("sub-delay", delayMs / 1000.0) }
            .onFailure { onError(it as Exception) }
    }

    override fun configureIosVideoOutput(settings: PlayerSettingsUiState) {
        // iOS-specific — no-op on desktop.
    }

    // --- volume ------------------------------------------------------------------------------

    override fun currentVolume(): PlayerAudioLevel? = currentAudioLevel

    override fun setVolume(level: Float): PlayerAudioLevel? {
        val clamped = level.coerceIn(0f, 1f)
        currentAudioLevel = currentAudioLevel.copy(fraction = clamped, isMuted = clamped <= 0f)
        return runCatching {
            mpv.setPropertyLong("volume", (clamped * 100).toLong().coerceIn(0L, 100L))
            mpv.setPropertyBoolean("mute", clamped <= 0f)
            currentAudioLevel
        }.getOrDefault(currentAudioLevel)
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
            println("$TAG: loadMedia skipped (duplicate) url=$sourceUrl")
            return
        }
        lastLoadedKey = key
        lastSourceUrl = sourceUrl
        pendingSeekTargetMs.set(-1L)
        fileLoaded = false

        try {
            println("$TAG: loadMedia url=$sourceUrl playWhenReady=$playWhenReady start=$startPositionMs")
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
            state = state.copy(isLoading = true, isEnded = false)
        } catch (e: Exception) {
            println("$TAG: loadMedia exception: ${e.message}")
            onError(e)
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

    fun dispose() {
        mpv.onEvent = null
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
