package com.nuvio.app.features.player

import com.nuvio.app.features.discord.DiscordRichPresence
import java.util.concurrent.atomic.AtomicBoolean

private const val ITAG = "NuvioScreensaver"

// ---------------------------------------------------------------------------
// Shared desktop playback side effects.
//
// Everything here used to live inside the VLCJ surface. It is engine-agnostic — none of it
// is about how frames are decoded — and a second engine (libmpv) needs all of it, so it is
// hoisted rather than duplicated. Duplicating it would be the quiet kind of regression: a
// new surface that plays video perfectly while the screensaver fires mid-film, MPRIS shows
// a frozen position, Discord goes stale and the screenshot hotkey grabs nothing.
// ---------------------------------------------------------------------------

/**
 * Keeps the desktop awake while video is playing.
 *
 * The buffer-callback render path means there is no native video window, so the engine's own
 * screensaver suppression never engages and the desktop blanks or locks mid-movie. We hold a
 * D-Bus inhibitor over a tiny python3-gi helper for as long as it runs:
 *  - `org.gnome.SessionManager.Inhibit(flags=8 idle)` — on GNOME/Cinnamon this single
 *    inhibitor covers screensaver, display power-off AND auto-suspend (all key off session idle).
 *  - `org.freedesktop.ScreenSaver.Inhibit` — cross-DE fallback (KDE/XFCE/etc.).
 *
 * The inhibitor auto-releases the moment the helper's bus connection drops, so the helper
 * blocks on stdin: closing it releases gracefully, and if the JVM dies or crashes the pipe
 * closes too (EOF) — the screensaver can never be left suppressed forever.
 */
internal object ScreensaverInhibitor {
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
                // couple of fast failures so the ~10x/sec cadence can't turn a broken helper into a
                // spawn-crash loop. A helper that ran a while then died (e.g. bus restart) is
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

/**
 * The per-playback side effects that must run on EVERY snapshot, plus the two frame-gating
 * flags they arm.
 *
 * Both engines drive this from their ~10 Hz poll. Running it only on discrete playback events
 * is a bug that has already happened once: MPRIS position sat frozen between events and the
 * [nearEnd] black-frame filter was never armed.
 */
internal class DesktopPlaybackSideEffects {

    /**
     * Playback has ended — hold the last good frame and ignore further callbacks until real
     * playback resumes. At end of stream a decoder drains trailing/black frames, and painting
     * them makes the video "blink" at the end of an episode.
     */
    val frozen = AtomicBoolean(false)

    /**
     * Inside the final 2s of the video. [frozen] alone is racy — the "finished" event has to
     * beat the flush frames on another thread, and whether it does varies with the engine build
     * and thread scheduling, which is how the blink came back once. This closes the race from
     * the other side: near the end, uniformly-dark frames are dropped on arrival with no event
     * ordering required.
     */
    val nearEnd = AtomicBoolean(false)

    /** Call from the poll loop. [forward] is the surface's own `onSnapshot` callback. */
    fun onSnapshot(snap: PlayerPlaybackSnapshot, forward: (PlayerPlaybackSnapshot) -> Unit) {
        // Freeze frame rendering once ended; re-enable when real playback resumes.
        if (snap.isEnded) frozen.set(true) else if (snap.isPlaying) frozen.set(false)
        nearEnd.set(snap.durationMs > 0 && snap.positionMs >= snap.durationMs - 2_000)
        // Keep the desktop awake only while actually playing (idempotent — this fires ~10x/sec
        // from the poll loop, so it no-ops unless state changed).
        if (snap.isPlaying) ScreensaverInhibitor.inhibit() else ScreensaverInhibitor.release()
        // Mirror the now-playing status to Discord (opt-in). update() is cheap and debounced
        // internally, so calling it at the poll's ~10Hz is fine.
        if (PlayerSettingsStorage.loadDiscordRichPresenceEnabled() == true) {
            DiscordRichPresence.update(snap)
        }
        // Feed live position/duration to MPRIS and nudge it to re-emit metadata only when
        // playing-state or duration actually changes (Position is polled by clients, so it is
        // intentionally NOT signalled every tick).
        val statusOrDurationChanged =
            PlayerControlBridge.isPlaying != snap.isPlaying ||
                PlayerControlBridge.durationMs != snap.durationMs
        PlayerControlBridge.positionMs = snap.positionMs
        PlayerControlBridge.durationMs = snap.durationMs
        PlayerControlBridge.hasMedia = !snap.isEnded
        if (statusOrDurationChanged) PlayerControlBridge.onNowPlayingChanged?.invoke()
        forward(snap)
    }

    /** Teardown: drop the inhibitor and the Discord presence when leaving the surface. */
    fun release() {
        ScreensaverInhibitor.release()
        DiscordRichPresence.clear()
    }
}

/**
 * End-of-stream flush frames are a single solid colour across the whole picture: black (zeroed
 * RGB), green (zeroed YUV converted to RGB), or dark grey, depending on the engine build.
 *
 * Samples a sparse 5x5 grid (25 pixels) rather than scanning the buffer — cheap enough for a
 * render thread, and only called during the final seconds of playback, so mid-video fades and
 * dark scenes are never touched. BGRA byte order. Returns a short signature for the dropped
 * frame (for debug logs), or null when the frame is real content.
 */
internal fun flushFrameSignature(bytes: ByteArray, w: Int, h: Int): String? {
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
    // Solid colour = negligible spread on every channel across the whole picture.
    val uniform = (maxB - minB) <= 8 && (maxG - minG) <= 8 && (maxR - minR) <= 8
    if (!uniform) return null
    val dark = maxR <= 40 && maxG <= 40 && maxB <= 40
    val green = maxG >= 60 && maxR <= 40 && maxB <= 40
    return if (dark || green) "rgb($minR-$maxR,$minG-$maxG,$minB-$maxB)" else null
}
