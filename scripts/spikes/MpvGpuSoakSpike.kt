// Does the GPU path survive being used the way the app uses it — over and over, for a long
// time, with the source changing underneath it?
//
// The GPU path was proven CORRECT (right colours, real-time pacing, zero dropped frames) but
// only ever over one playback of one file. Everything below is what the app does that a single
// playback never exercises:
//
//   * enter and leave the player repeatedly — a session is created and disposed each time, and
//     that tears down a GL texture, an FBO, a Skia Image, an mpv render context, an mpv handle
//     and an event thread, in an order that must be exactly right
//   * change source mid-session — the surface keeps ONE controller and just issues another
//     loadfile, so any per-file state that is latched rather than reset survives into the next
//     stream (this is how the end-of-file latches were found)
//   * keep going for long enough that a small per-frame leak becomes visible
//
// It runs the PRODUCTION classes (MpvGpuRenderer, MpvPlayerController, MpvEngineOptions)
// against a real X11-platform EGL context from the soak shim, rather than reimplementing them:
// a soak of a copy proves nothing about what ships.
//
// ⚠ What is measured, and why RSS is not the headline: a leaked event thread or mpv handle
// shows up in the thread and fd counts LONG before it shows in memory, and RSS moves for
// reasons that have nothing to do with leaks (see the false alarm in mpv-egl-next-steps).
// A GL texture id that keeps climbing across cycles is the sharpest signal of the three —
// the driver reuses ids that were actually deleted.
//
// usage: run-mpv-gpu-soak.sh <file> [second-file] [cycles]

package com.nuvio.app.desktop.mpv

import com.nuvio.app.desktop.egl.Gl
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.GLAssembledInterface
import org.jetbrains.skia.makeGLWithInterface

private interface SoakShim : Library {
    fun egl_shim_setup(): Int
    fun egl_shim_error(): String
    fun egl_shim_get_proc_fn(): Pointer
    fun egl_shim_x11_display(): Pointer
}

private val results = LinkedHashMap<String, String>()
private var failures = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    results[name] = (if (ok) "PASS" else "FAIL") + (if (detail.isNotEmpty()) "  ($detail)" else "")
    if (!ok) failures++
}

private fun rssKb(): Long = runCatching {
    java.io.File("/proc/self/status").readLines()
        .first { it.startsWith("VmRSS:") }.filter { it.isDigit() }.toLong()
}.getOrDefault(-1L)

private fun openFds(): Int = runCatching {
    java.io.File("/proc/self/fd").list()?.size ?: -1
}.getOrDefault(-1)

private fun threadCount(): Int = runCatching {
    java.io.File("/proc/self/status").readLines()
        .first { it.startsWith("Threads:") }.filter { it.isDigit() }.toInt()
}.getOrDefault(-1)

/**
 * Thread names as the kernel sees them, so a leak can be attributed instead of merely counted.
 * Native mpv threads never appear in the JVM's own thread list, which is why this reads /proc.
 */
private fun threadNames(): List<String> = runCatching {
    java.io.File("/proc/self/task").listFiles().orEmpty()
        .mapNotNull { runCatching { it.resolve("comm").readText().trim() }.getOrNull() }
        .sorted()
}.getOrDefault(emptyList())

/** What [after] has that [before] did not, counted per name. */
private fun threadDelta(before: List<String>, after: List<String>): String {
    val b = before.groupingBy { it }.eachCount()
    val a = after.groupingBy { it }.eachCount()
    val gained = a.filter { (name, n) -> n > (b[name] ?: 0) }
        .map { (name, n) -> "$name +${n - (b[name] ?: 0)}" }
    return if (gained.isEmpty()) "none" else gained.joinToString(", ")
}

/** RSS after a GC, which is the only RSS number that means anything here. */
private fun settledRssKb(): Long {
    System.gc()
    System.runFinalization()
    Thread.sleep(1_500)
    System.gc()
    Thread.sleep(500)
    return rssKb()
}

/**
 * A texture id from the driver, immediately given back. Ids climbing across cycles means the
 * previous cycle's textures were never deleted — a leak that RSS would take a long time to show
 * and that `glGetError` never reports at all.
 */
private fun probeTextureId(): Int {
    val id = Gl.genTexture()
    Gl.deleteTexture(id)
    return id
}

private class CycleResult(
    val hwdec: String?,
    val framesFirst: Long,
    val framesSecond: Long,
    val textureProbe: Int,
    val fds: Int,
    val threads: Int,
    val rssKb: Long,
)

fun main(args: Array<String>) {
    val fileA = args.getOrNull(0) ?: run {
        println("usage: run-mpv-gpu-soak.sh <file> [second-file] [cycles]"); return
    }
    // Same file twice is still a valid source switch (a second loadfile on a live controller);
    // two different files just exercise a resolution/format change as well.
    val fileB = args.getOrNull(1) ?: fileA
    val cycles = args.getOrNull(2)?.toIntOrNull() ?: 6
    val secondsPerSource = System.getenv("SOAK_SECONDS")?.toIntOrNull() ?: 8
    val w = System.getenv("SOAK_W")?.toIntOrNull() ?: 1920
    val h = System.getenv("SOAK_H")?.toIntOrNull() ?: 1080

    println("=== mpv GPU soak: $cycles cycles, ${secondsPerSource}s per source, ${w}x$h ===")

    val shim = Native.load("eglskiashim", SoakShim::class.java)
    check("EGL context", shim.egl_shim_setup() == 0, shim.egl_shim_error())
    if (failures > 0) { report(); return }

    val ctx = DirectContext.makeGLWithInterface(
        GLAssembledInterface.createFromNativePointers(
            0L, Pointer.nativeValue(shim.egl_shim_get_proc_fn()),
        ),
    )
    val xDisplay = shim.egl_shim_x11_display()
    check("X11 display for VA-API interop", Pointer.nativeValue(xDisplay) != 0L)

    val baselineFds = openFds()
    val baselineThreads = threadCount()
    val baselineThreadNames = threadNames()
    val baselineRss = settledRssKb()
    val baselineTexture = probeTextureId()
    println("baseline: fds=$baselineFds threads=$baselineThreads rss=${baselineRss / 1024}MB tex=$baselineTexture")

    val cycleResults = mutableListOf<CycleResult>()
    var glErrors = 0
    var createFailures = 0
    var errorsReported = 0

    repeat(cycles) { cycle ->
        val handle = MpvEngineOptions.createHandle(MpvVideoOutput.GPU)
        if (handle == null) { createFailures++; return@repeat }
        val controller = MpvPlayerController(mpv = handle, onError = {
            println("  error: ${it.message}")
            errorsReported++
        })
        handle.startEventLoop()
        val renderer = MpvGpuRenderer.create(handle, xDisplay, w, h)
        if (renderer == null) {
            createFailures++
            controller.dispose()
            return@repeat
        }

        // --- source A ---
        controller.loadMedia(fileA, emptyMap(), playWhenReady = true, startPositionMs = 0L)
        val framesA = renderFor(renderer, ctx, w, h, secondsPerSource)
        val hwdec = controller.hwdecCurrent

        // --- source B, on the SAME controller and the SAME renderer ---
        // ⚠ This is what the surface does on a source change: no new session, no new render
        // context, just another loadfile. Anything the previous file latched is still latched.
        controller.loadMedia(fileB, emptyMap(), playWhenReady = true, startPositionMs = 0L)
        val framesB = renderFor(renderer, ctx, w, h, secondsPerSource)

        if (Gl.getError() != 0) glErrors++

        // Teardown in MpvSession.dispose()'s exact order. ⚠ The `stop` is not tidiness: freeing
        // the render context under a playing file makes mpv try to re-initialise its video
        // output against a context that is gone, and it reports that as a playback ERROR — which
        // the app would surface to the user as a failed stream on the way OUT of the player.
        handle.command("stop")
        renderer.dispose()
        controller.dispose()   // disposes the handle and joins the event thread
        // The event thread's exit is not instantaneous; give it the moment the app's own
        // teardown gives it before counting.
        Thread.sleep(500)

        cycleResults += CycleResult(
            hwdec = hwdec,
            framesFirst = framesA,
            framesSecond = framesB,
            textureProbe = probeTextureId(),
            fds = openFds(),
            threads = threadCount(),
            rssKb = rssKb(),
        )
        val r = cycleResults.last()
        println(
            "cycle ${cycle + 1}/$cycles: hwdec=${r.hwdec} frames=${r.framesFirst}+${r.framesSecond} " +
                "tex=${r.textureProbe} fds=${r.fds} threads=${r.threads} rss=${r.rssKb / 1024}MB",
        )
    }

    check("every cycle built a session", createFailures == 0, "failures=$createFailures")
    check("all cycles ran", cycleResults.size == cycles, "ran=${cycleResults.size}/$cycles")
    if (cycleResults.isEmpty()) { report(); return }

    // ⚠ The assertion the whole path exists for. `vaapi` is zero-copy; `vaapi-copy` is a
    // readback that cannot keep up with 4K; `no` is software decode. All three play correct
    // video, so only this tells them apart.
    check("hardware decode stays zero-copy across every cycle",
        cycleResults.all { it.hwdec == "vaapi" },
        "hwdec=${cycleResults.map { it.hwdec }.distinct()}")

    check("frames flow on the first source in every cycle",
        cycleResults.all { it.framesFirst > 10 }, "min=${cycleResults.minOf { it.framesFirst }}")
    check("frames flow AFTER a mid-session source change",
        cycleResults.all { it.framesSecond > 10 }, "min=${cycleResults.minOf { it.framesSecond }}")

    check("no GL errors", glErrors == 0, "cycles with errors=$glErrors")
    check("no playback error reported (leaving the player is not a failure)",
        errorsReported == 0, "errors=$errorsReported")

    // Ids are allowed to differ from the baseline (Skia allocates its own), but they must not
    // climb with the cycle count — that is what an undeleted texture per cycle looks like.
    val firstProbe = cycleResults.first().textureProbe
    val lastProbe = cycleResults.last().textureProbe
    check("GL texture ids are reused, not leaked", lastProbe <= firstProbe + 2,
        "probe ${firstProbe} -> ${lastProbe} over ${cycleResults.size} cycles")

    // ⚠ Per CYCLE, not in total: a fixed handful of lazily-started JVM/driver threads is normal
    // and appears once, but anything the player leaks appears again on every entry to the
    // player. The names say which is which.
    // ⚠ Measured from the FIRST CYCLE, not from the process baseline. The JVM spins up a fixed
    // handful of threads (GC workers, mostly) the moment real work starts; counted against the
    // baseline that one-off shows up as a per-cycle leak and is not one. Growth from cycle 1 to
    // cycle N is what a leak actually looks like. The names are printed either way, because a
    // count alone cannot say whose thread it is.
    val gainedNames = threadDelta(baselineThreadNames, threadNames())
    val threadDetail = "cycle1=${cycleResults.first().threads} last=${cycleResults.last().threads} " +
        "(process baseline $baselineThreads, gained since: $gainedNames)"
    if (cycleResults.size < 2) {
        results["threads do not grow per cycle"] = "SKIP  (needs >= 2 cycles; $threadDetail)"
    } else {
        check("threads do not grow per cycle",
            cycleResults.last().threads <= cycleResults.first().threads, threadDetail)
    }

    check("no file descriptors leaked per cycle",
        cycleResults.last().fds <= cycleResults.first().fds,
        "cycle1=${cycleResults.first().fds} last=${cycleResults.last().fds} " +
            "(process baseline $baselineFds)")

    val settled = settledRssKb()
    val retainedMb = (settled - baselineRss) / 1024
    // Generous on purpose: the JVM's own heap grows under this load and RSS is a poor leak
    // signal (see the frame-path false alarm). Anything proportional to the cycle count would
    // be caught by the texture and thread checks first; this is the backstop.
    check("memory settles after the soak", retainedMb < 400,
        "baseline=${baselineRss / 1024}MB settled=${settled / 1024}MB retained=${retainedMb}MB")

    report()
}

/**
 * Renders for [seconds] the way the Compose draw scope does — one render per available frame,
 * with the swap reported so mpv's own timing stays honest — and returns the frame count.
 */
private fun renderFor(
    renderer: MpvGpuRenderer,
    ctx: DirectContext,
    w: Int,
    h: Int,
    seconds: Int,
): Long {
    val before = renderer.framesRendered.get()
    val end = System.currentTimeMillis() + seconds * 1000L
    while (System.currentTimeMillis() < end) {
        if (!renderer.hasNewFrame()) { Thread.sleep(2); continue }
        renderer.render(ctx, ColorType.RGBA_8888, w, h)
        // Skia's cached view of the context is stale after mpv has touched it — the same reset
        // the real draw scope does.
        ctx.resetGLAll()
        renderer.reportSwap()
    }
    return renderer.framesRendered.get() - before
}

private fun report() {
    println("\n--- results ---")
    results.forEach { (k, v) -> println("  ${v.padEnd(46)} $k") }
    println(if (failures == 0) "\nALL GREEN (${results.size} checks)" else "\n$failures FAILURE(S)")
    if (failures > 0) System.exit(1)
}
