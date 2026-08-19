// Does MpvSession — the plumbing the Compose surface actually drives — work end to end?
//
// The software renderer was proven separately (MpvSwRenderSpike). What is new here is the
// session: the frame pump thread, the surface-size handshake, the teardown ORDER, and the
// error path. Those are exactly the parts a GUI run would exercise only sporadically, so they
// are asserted headlessly first:
//
//   * frames arrive at the SURFACE size, not the source size (mpv is scaling for us)
//   * a resize is picked up by the pump without a restart and without a torn size
//   * the 30 fps cap actually caps
//   * dispose() stops the pump and frees in order, and is idempotent — a second call, or a
//     frame arriving during teardown, must not crash the process
//   * a dead source reports an ERROR rather than looking like a finished file
//
// usage: run-mpv-session-spike.sh <file-or-url>

package com.nuvio.app.desktop.mpv

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private val results = LinkedHashMap<String, String>()
private var failures = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    results[name] = (if (ok) "PASS" else "FAIL") + (if (detail.isNotEmpty()) "  ($detail)" else "")
    if (!ok) failures++
}

private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (cond()) return true
        Thread.sleep(30)
    }
    return cond()
}

/** Mean of B+G+R over a sparse grid — distinguishes real video from a black buffer. */
private fun meanLuma(b: ByteArray, w: Int, h: Int): Int {
    var sum = 0L; var n = 0
    var y = 0
    while (y < h) {
        var x = 0
        while (x < w) {
            val i = (y * w + x) * 4
            if (i + 2 < b.size) {
                sum += ((b[i].toInt() and 0xFF) + (b[i + 1].toInt() and 0xFF) + (b[i + 2].toInt() and 0xFF)) / 3
                n++
            }
            x += 16
        }
        y += 16
    }
    return if (n > 0) (sum / n).toInt() else -1
}

private class FrameStats {
    val count = AtomicInteger(0)
    val nonBlack = AtomicInteger(0)
    val wrongSize = AtomicInteger(0)
    val lastSize = AtomicReference("none")
    val firstMs = AtomicLong(0L)
    val lastMs = AtomicLong(0L)
    val fingerprints = java.util.Collections.synchronizedSet(LinkedHashSet<Int>())

    fun accept(bytes: ByteArray, w: Int, h: Int) {
        val now = System.currentTimeMillis()
        firstMs.compareAndSet(0L, now)
        lastMs.set(now)
        count.incrementAndGet()
        lastSize.set("${w}x$h")
        // The pump must hand over a buffer that matches the size it reports — a mismatch is
        // how a torn width/height read would show up.
        if (bytes.size != w * h * 4) wrongSize.incrementAndGet()
        if (meanLuma(bytes, w, h) > 8) nonBlack.incrementAndGet()
        var fp = 0
        var i = 0
        while (i < bytes.size) { fp = fp * 31 + bytes[i]; i += 4099 }
        fingerprints += fp
    }

    fun reset() {
        count.set(0); nonBlack.set(0); wrongSize.set(0)
        firstMs.set(0L); lastMs.set(0L)
        fingerprints.clear()
    }
}

fun main(args: Array<String>) {
    val file = args.firstOrNull() ?: run {
        println("usage: run-mpv-session-spike.sh <file-or-url>"); return
    }
    println("=== mpv session spike ===")

    val session = MpvSession.create()
    check("session created", session != null)
    if (session == null) { report(); return }

    val stats = FrameStats()
    session.setSurfaceSize(1280, 720)
    session.startFramePump { bytes, w, h -> stats.accept(bytes, w, h) }
    // Starting twice must not spawn a second pump competing for the render context.
    session.startFramePump { _, _, _ -> check("second pump started", false, "must be a no-op") }

    val errors = java.util.Collections.synchronizedList(ArrayList<String>())
    session.onError = { errors += (it.message ?: "?") }

    session.controller.loadMedia(file, emptyMap(), playWhenReady = true, startPositionMs = 2_000L)

    check("playback starts", waitFor(20_000) { session.controller.currentSnapshot().isPlaying },
        "snapshot=${session.controller.currentSnapshot()}")
    check("hardware decode engaged", waitFor(10_000) { session.hwdecCurrent != null } &&
        session.hwdecCurrent != "no", "hwdec-current=${session.hwdecCurrent}")

    check("frames flow", waitFor(20_000) { stats.count.get() >= 20 }, "frames=${stats.count.get()}")
    check("frames are at the surface size", stats.lastSize.get() == "1280x720" && stats.wrongSize.get() == 0,
        "size=${stats.lastSize.get()} mismatched=${stats.wrongSize.get()}")
    check("frames are not black", stats.nonBlack.get() >= stats.count.get() - 2,
        "nonBlack=${stats.nonBlack.get()}/${stats.count.get()}")
    check("video is MOVING", stats.fingerprints.size >= 5,
        "distinct=${stats.fingerprints.size}/${stats.count.get()}")

    // The 30 fps cap. Measured over a settled window rather than from the first frame, which
    // arrives before the clock starts.
    stats.reset()
    Thread.sleep(4_000)
    val elapsed = (stats.lastMs.get() - stats.firstMs.get()).coerceAtLeast(1L)
    val fps = stats.count.get() * 1000.0 / elapsed
    check("frame rate is capped near 30fps", fps in 20.0..34.0, "%.1f fps".format(fps))

    // A resize the way the window does it: the pump must pick it up with no restart.
    stats.reset()
    session.setSurfaceSize(960, 540)
    check("resize is picked up", waitFor(10_000) { stats.count.get() >= 10 && stats.lastSize.get() == "960x540" },
        "size=${stats.lastSize.get()} frames=${stats.count.get()} mismatched=${stats.wrongSize.get()}")
    check("frames still good after resize", stats.wrongSize.get() == 0 &&
        stats.nonBlack.get() >= stats.count.get() - 2,
        "nonBlack=${stats.nonBlack.get()}/${stats.count.get()}")

    check("keepaspect toggles cleanly", runCatching {
        session.setKeepAspect(false); Thread.sleep(300); session.setKeepAspect(true)
    }.isSuccess)

    // Teardown, from a thread that is NOT the pump — i.e. exactly what Compose does.
    stats.reset()
    check("dispose is clean and idempotent", runCatching {
        session.dispose()
        session.dispose()
    }.isSuccess)
    val afterDispose = stats.count.get()
    Thread.sleep(600)
    check("no frames after dispose", stats.count.get() == afterDispose,
        "frames kept arriving: $afterDispose -> ${stats.count.get()}")
    check("no spurious errors during playback", errors.isEmpty(), errors.joinToString("; "))

    // --- error path -------------------------------------------------------------------------
    // A dead source must surface as an ERROR. Before END_FILE carried its reason this was
    // indistinguishable from a finished file: no error, and the UI sat on a spinner.
    val bad = MpvSession.create()
    check("second session created", bad != null)
    if (bad != null) {
        val badErrors = java.util.Collections.synchronizedList(ArrayList<String>())
        bad.onError = { badErrors += (it.message ?: "?") }
        bad.setSurfaceSize(640, 360)
        bad.startFramePump { _, _, _ -> }
        bad.controller.loadMedia(
            "http://127.0.0.1:1/does-not-exist.mp4", emptyMap(),
            playWhenReady = true, startPositionMs = 0L,
        )
        check("dead source reports an error", waitFor(20_000) { badErrors.isNotEmpty() },
            "errors=$badErrors")
        check("dead source is not reported as ended", !bad.controller.currentSnapshot().isEnded,
            "snapshot=${bad.controller.currentSnapshot()}")
        bad.dispose()
    }

    report()
}

private fun report() {
    println("\n--- results ---")
    results.forEach { (k, v) -> println("  ${v.padEnd(40)} $k") }
    println(if (failures == 0) "\nALL GREEN (${results.size} checks)" else "\n$failures FAILURE(S)")
    if (failures > 0) System.exit(1) else System.exit(0)
}
