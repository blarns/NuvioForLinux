// Exercises MpvPlayerController + MpvEngineOptions -- the production controller, compiled in
// the same module so Kotlin `internal` is visible -- against a real encoded H.264 file.
//
// This covers the PlayerEngineController contract (transport, seek, tracks, volume, speed,
// snapshots, teardown), not the render seam. hwdec reads "vaapi-copy" here -- hardware decode
// with a readback, which is what MpvVideoOutput.SOFTWARE asks for. Proving the zero-copy
// "vaapi" tier needs an EGL context and is the render path's job.
//
// Needs a test file. Generate one with (note the pixel format -- lavfi encodes to yuv444p by
// default, which VA-API cannot decode, so hwdec would silently fall back to software):
//   mpv --no-config --o=test1080.mp4 --of=mp4 --ovc=libx264 \
//       --ovcopts=preset=ultrafast,crf=30 --vf=format=yuv420p --no-audio \
//       "av://lavfi:testsrc=size=1920x1080:rate=25:duration=30"
//
// Run: scripts/spikes/run-mpv-controller-spike.sh <path-to-test1080.mp4>

package com.nuvio.app.desktop.mpv

import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private val results = LinkedHashMap<String, String>()
private var failures = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    results[name] = (if (ok) "PASS" else "FAIL") + (if (detail.isNotEmpty()) "  ($detail)" else "")
    if (!ok) failures++
}

/** Polls until [cond] holds or the budget runs out; returns whether it held. */
private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (cond()) return true
        Thread.sleep(50)
    }
    return cond()
}

fun main(args: Array<String>) {
    println("=== mpv controller spike ===")
    val file = args.firstOrNull()
    if (file == null || !java.io.File(file).exists()) {
        println("usage: run-mpv-controller-spike.sh <test1080.mp4>  (see header for how to make one)")
        return
    }

    val handle = MpvEngineOptions.createHandle(MpvVideoOutput.SOFTWARE)
    check("MpvEngineOptions.createHandle", handle != null)
    if (handle == null) { report(); return }

    // vo=libmpv expects a render context to pull frames. There is none here, so playback would
    // stall waiting for one -- swap to the null VO so the CONTROLLER can be tested without the
    // render seam. Production keeps vo=libmpv; this is the one deviation, and it is stated.
    handle.setPropertyString("vo", "null")

    val snapshots = AtomicInteger()
    val last = AtomicReference(PlayerPlaybackSnapshot())
    val errors = AtomicReference<String?>(null)

    val ctrl = MpvPlayerController(
        mpv = handle,
        onSnapshot = { snapshots.incrementAndGet(); last.set(it) },
        onError = { errors.set(it.message) },
    )
    handle.startEventLoop()

    // --- load, with a start position and a custom header ------------------------------
    ctrl.loadMedia(
        sourceUrl = file,
        sourceHeaders = mapOf(
            "User-Agent" to "NuvioSpike/1.0",
            // A comma in the value is the whole reason header items are percent-escaped;
            // unescaped, this would corrupt every header after it.
            "Cookie" to "a=1, b=2",
        ),
        playWhenReady = true,
        startPositionMs = 5_000L,
    )

    check("playing within 10s", waitFor(10_000) { last.get().isPlaying }, "snapshots=${snapshots.get()}")
    check("no error reported", errors.get() == null, errors.get() ?: "")

    check("duration reported", waitFor(5_000) { last.get().durationMs > 25_000 },
        "durationMs=${last.get().durationMs}")

    // startPositionMs must actually be honoured -- resume depends on it.
    check("start position honoured", waitFor(5_000) { ctrl.currentSnapshot().positionMs >= 4_500 },
        "positionMs=${ctrl.currentSnapshot().positionMs}")

    val posA = ctrl.currentSnapshot().positionMs
    Thread.sleep(1_500)
    check("position advances", ctrl.currentSnapshot().positionMs > posA,
        "$posA -> ${ctrl.currentSnapshot().positionMs}")

    // --- transport ---------------------------------------------------------------------
    ctrl.pause()
    check("pause", waitFor(3_000) { !last.get().isPlaying })
    // Idempotent: a second pause must not resume, which is the libVLC toggle bug this avoids.
    ctrl.pause()
    Thread.sleep(400)
    check("pause is idempotent", !last.get().isPlaying)

    ctrl.play()
    check("play", waitFor(3_000) { last.get().isPlaying })

    // --- seek ---------------------------------------------------------------------------
    ctrl.seekTo(20_000)
    // The optimistic target must be visible immediately, or the UI timeline snaps backwards.
    check("seek target reported immediately", ctrl.currentSnapshot().positionMs == 20_000L,
        "positionMs=${ctrl.currentSnapshot().positionMs}")
    // ⚠ Checking "position is near 20s" alone proves NOTHING: the controller reports the
    // optimistic target until mpv confirms the seek, so that check passes even when the file
    // never loaded. The real evidence is the position moving PAST the target under its own
    // steam, which only happens once mpv is actually decoding from there.
    check("seek lands and playback continues from it", waitFor(10_000) {
        ctrl.currentSnapshot().positionMs > 20_500
    }, "positionMs=${ctrl.currentSnapshot().positionMs}")

    ctrl.seekTo(5_000)
    check("second seek lands", waitFor(10_000) { ctrl.currentSnapshot().positionMs in 5_000..9_000 },
        "positionMs=${ctrl.currentSnapshot().positionMs}")
    val beforeRelative = ctrl.currentSnapshot().positionMs
    ctrl.seekBy(3_000)
    check("seekBy offsets from current position",
        ctrl.currentSnapshot().positionMs == beforeRelative + 3_000,
        "$beforeRelative + 3000 -> ${ctrl.currentSnapshot().positionMs}")
    check("relative seek also lands", waitFor(10_000) {
        ctrl.currentSnapshot().positionMs > beforeRelative + 3_500
    }, "positionMs=${ctrl.currentSnapshot().positionMs}")

    // --- tracks --------------------------------------------------------------------------
    val video = ctrl.getAudioTracks()
    // The generated file is video-only, so an EMPTY audio list is the correct answer; the
    // check is that reading it neither throws nor invents entries.
    check("audio tracks readable", video.isEmpty(), "n=${video.size}")
    check("subtitle tracks readable", ctrl.getSubtitleTracks().isEmpty())

    // --- volume / speed ------------------------------------------------------------------
    ctrl.setVolume(0.5f)
    check("setVolume", ctrl.currentVolume()?.fraction == 0.5f && ctrl.currentVolume()?.isMuted == false,
        "${ctrl.currentVolume()}")
    ctrl.setVolume(0f)
    check("zero volume reports muted", ctrl.currentVolume()?.isMuted == true)
    ctrl.setVolume(1f)

    ctrl.setPlaybackSpeed(1.5f)
    check("setPlaybackSpeed", waitFor(3_000) {
        kotlin.math.abs(ctrl.currentSnapshot().playbackSpeed - 1.5f) < 0.01f
    }, "speed=${ctrl.currentSnapshot().playbackSpeed}")
    ctrl.setPlaybackSpeed(1f)

    // --- duplicate load guard --------------------------------------------------------------
    val before = ctrl.currentSnapshot().positionMs
    ctrl.loadMedia(file, emptyMap(), true, 5_000L, null)
    Thread.sleep(600)
    check("duplicate loadMedia is skipped", ctrl.currentSnapshot().positionMs >= before - 1_000,
        "$before -> ${ctrl.currentSnapshot().positionMs}")

    // --- header escaping (unit-level, no I/O) -----------------------------------------------
    check("list-item escaping is byte-length prefixed",
        escapeListItem("Cookie: a=1, b=2") == "%16%Cookie: a=1, b=2",
        escapeListItem("Cookie: a=1, b=2"))
    check("escaping counts BYTES not chars",
        escapeListItem("é") == "%2%é", escapeListItem("é"))

    // --- end of stream -----------------------------------------------------------------------
    ctrl.seekTo(29_000)
    check("reaches end-of-stream", waitFor(15_000) { last.get().isEnded },
        "isEnded=${last.get().isEnded} pos=${ctrl.currentSnapshot().positionMs}")

    // --- teardown -----------------------------------------------------------------------------
    check("dispose clean", runCatching { ctrl.dispose(); ctrl.dispose() }.isSuccess)

    println("\nsnapshots delivered: ${snapshots.get()}")
    // Worth printing: this is the assertion every silent failure in this investigation hid
    // behind. "vaapi-copy" is correct for the SOFTWARE output; "no" would mean software decode.
    println("hwdec-current: ${ctrl.hwdecCurrent}  (expected \"vaapi-copy\" for SOFTWARE output)")
    report()
}

private fun report() {
    println("\n--- results ---")
    results.forEach { (k, v) -> println("  ${v.padEnd(34)} $k") }
    println(if (failures == 0) "\nALL GREEN (${results.size} checks)" else "\n$failures FAILURE(S)")
    if (failures > 0) System.exit(1)
}
