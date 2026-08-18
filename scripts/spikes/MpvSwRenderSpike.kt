// Does the software render path actually produce real, MOVING video?
//
// Exercises the production MpvSoftwareRenderer end to end: create the render context, load a
// real file, pull frames into a caller-owned buffer, and check the pixels.
//
// The checks are chosen so that the plausible silent failures cannot pass:
//   * a black buffer would mean the render "succeeded" and produced nothing
//   * identical frames would mean we are re-reading one decoded frame forever
//   * a wrong SW_FORMAT or SW_STRIDE shows up as shear/garbage, not as an error code, so the
//     frame is checked for structure (rows differing) rather than just for non-zero bytes
//
// usage: run-mpv-swrender-spike.sh <file-or-url>

package com.nuvio.app.desktop.mpv

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

/** How many sampled rows differ from the row above — a sheared/garbage frame scores very low. */
private fun rowVariety(b: ByteArray, w: Int, h: Int): Int {
    var differing = 0
    var y = 1
    while (y < h) {
        val a = (y * w + w / 2) * 4
        val c = ((y - 1) * w + w / 2) * 4
        if (a + 2 < b.size && c + 2 < b.size && (b[a] != b[c] || b[a + 1] != b[c + 1] || b[a + 2] != b[c + 2])) {
            differing++
        }
        y += 16
    }
    return differing
}

fun main(args: Array<String>) {
    val file = args.firstOrNull() ?: run {
        println("usage: run-mpv-swrender-spike.sh <file-or-url>"); return
    }
    println("=== mpv software render spike ===")

    val handle = MpvEngineOptions.createHandle(MpvVideoOutput.SOFTWARE)
    check("createHandle", handle != null)
    if (handle == null) { report(); return }

    val w = 1280
    val h = 720
    val renderer = MpvSoftwareRenderer.create(handle, w, h)
    check("render context created", renderer != null)
    if (renderer == null) { handle.dispose(); report(); return }

    val ctrl = MpvPlayerController(mpv = handle, onError = { println("error: ${it.message}") })
    handle.startEventLoop()
    ctrl.loadMedia(file, emptyMap(), playWhenReady = true, startPositionMs = 2_000L)

    check("playback starts", waitFor(20_000) { ctrl.currentSnapshot().isPlaying },
        "snapshot=${ctrl.currentSnapshot()}")

    // The decode tier this path is supposed to reach. "no" would mean pure software decode.
    check("hardware decode engaged", waitFor(10_000) { ctrl.hwdecCurrent != null } &&
        ctrl.hwdecCurrent != "no", "hwdec-current=${ctrl.hwdecCurrent}")

    val buf = ByteArray(w * h * 4)
    check("mpv signals a frame", waitFor(20_000) { renderer.hasNewFrame() })

    // Pull a run of frames the way the surface will.
    var rendered = 0
    var nonBlack = 0
    var structured = 0
    val fingerprints = LinkedHashSet<Int>()
    val deadline = System.currentTimeMillis() + 25_000
    while (rendered < 40 && System.currentTimeMillis() < deadline) {
        if (!renderer.hasNewFrame()) { Thread.sleep(10); continue }
        if (!renderer.render(buf)) continue
        rendered++
        if (meanLuma(buf, w, h) > 8) nonBlack++
        if (rowVariety(buf, w, h) > 5) structured++
        // Cheap content hash of a sparse sample, to tell moving video from a frozen frame.
        var fp = 0
        var i = 0
        while (i < buf.size) { fp = fp * 31 + buf[i]; i += 4099 }
        fingerprints += fp
    }

    check("frames rendered", rendered >= 20, "rendered=$rendered")
    check("frames are not black", nonBlack >= rendered - 2, "nonBlack=$nonBlack/$rendered")
    check("frames have row structure", structured >= rendered - 2,
        "structured=$structured/$rendered — low means a bad SW_STRIDE or SW_FORMAT")
    check("video is MOVING", fingerprints.size >= 5,
        "distinct frames=${fingerprints.size}/$rendered")

    // Resize mid-playback: the surface does this whenever the window changes.
    renderer.resize(640, 360)
    val small = ByteArray(640 * 360 * 4)
    val resizedOk = waitFor(10_000) { renderer.hasNewFrame() && renderer.render(small) }
    check("renders after resize", resizedOk && meanLuma(small, 640, 360) > 8,
        "mean=${meanLuma(small, 640, 360)}")

    check("dispose clean", runCatching {
        renderer.dispose(); renderer.dispose(); ctrl.dispose()
    }.isSuccess)

    println("\nhwdec-current: ${ctrl.hwdecCurrent}")
    report()
}

private fun report() {
    println("\n--- results ---")
    results.forEach { (k, v) -> println("  ${v.padEnd(34)} $k") }
    println(if (failures == 0) "\nALL GREEN (${results.size} checks)" else "\n$failures FAILURE(S)")
    if (failures > 0) System.exit(1)
}
