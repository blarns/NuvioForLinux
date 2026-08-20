// Exercises the real JNA binding (composeApp/.../desktop/mpv/LibMpv.kt + MpvHandle.kt) against
// a live libmpv: create, options, initialize, loadfile, the event thread, observed property
// changes, typed property get/set, seeking, and a clean teardown.
//
// Deliberately headless (vo=null, ao=null) and sourced from lavfi, so it needs no media file
// and no display -- this proves the BINDING, not the render seam. The GPU render path is
// proven separately by EglSkiaMpvSpike.kt.
//
// Compile it together with the two production files so Kotlin `internal` is visible (same
// module), then run -- see scripts/spikes/run-mpv-binding-spike.sh.

package com.nuvio.app.desktop.mpv

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val results = LinkedHashMap<String, String>()
private var failures = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    results[name] = (if (ok) "PASS" else "FAIL") + (if (detail.isNotEmpty()) "  ($detail)" else "")
    if (!ok) failures++
}

fun main() {
    println("=== mpv JNA binding spike ===")

    check("libmpv loadable", MpvHandle.isAvailable, "api=${MpvHandle.apiVersion()}")
    if (!MpvHandle.isAvailable) { report(); return }

    // ⚠ The check above passes on ANY dev machine and says nothing about a packaged install:
    // `Native.load("mpv")` finds `libmpv.so`, which ships in `libmpv-dev`. Users get `libmpv2`,
    // where the only file is the soname below — so the fallback leg is loaded EXPLICITLY here,
    // because it is the leg every real install will take and the one nothing else exercises.
    val bySoname = runCatching {
        com.sun.jna.Native.load("libmpv.so.2", MpvLibrary::class.java).mpv_client_api_version()
    }
    check("loadable by soname (no -dev symlink)", bySoname.getOrNull() != null,
        "libmpv.so.2 -> ${bySoname.getOrNull() ?: bySoname.exceptionOrNull()?.message}")

    val mpv = MpvHandle.create()
    check("mpv_create", mpv != null)
    if (mpv == null) { report(); return }

    // Headless: no window, no sound card.
    // ⚠ NOT `untimed`: that runs the source flat out, so a short clip reaches EOF before the
    // assertions below run and every property reads back null -- which looks exactly like a
    // broken binding. Real-time pacing is also what the actual player does.
    mpv.setOption("vo", "null")
    mpv.setOption("ao", "null")
    mpv.setOption("terminal", "no")

    // Observed before initialize, which is the order the real controller will use.
    mpv.observeProperty("time-pos", MpvFormat.DOUBLE)
    mpv.observeProperty("duration", MpvFormat.DOUBLE)
    mpv.observeProperty("pause", MpvFormat.FLAG)
    mpv.observeProperty("eof-reached", MpvFormat.FLAG)

    val fileLoaded = CountDownLatch(1)
    val eventCounts = ConcurrentHashMap<Int, AtomicInteger>()
    val propCounts = ConcurrentHashMap<String, AtomicInteger>()
    // Written on the event thread, read on this one -- atomics rather than plain locals,
    // which a lambda captures in a non-volatile Ref box.
    val lastTimePos = java.util.concurrent.atomic.AtomicReference(-1.0)
    val sawPauseFlag = java.util.concurrent.atomic.AtomicBoolean(false)

    mpv.onEvent = { ev ->
        eventCounts.computeIfAbsent(ev.id) { AtomicInteger() }.incrementAndGet()
        if (ev.id == MpvEventId.PROPERTY_CHANGE) {
            ev.propertyName?.let { propCounts.computeIfAbsent(it) { AtomicInteger() }.incrementAndGet() }
            when (ev.propertyName) {
                "time-pos" -> ev.propertyDouble?.let { lastTimePos.set(it) }
                "pause" -> if (ev.propertyFlag != null) sawPauseFlag.set(true)
            }
        }
        if (ev.id == MpvEventId.FILE_LOADED) fileLoaded.countDown()
    }

    runCatching { mpv.initialize() }
        .onFailure { check("mpv_initialize", false, it.message ?: "threw") }
        .onSuccess { check("mpv_initialize", true) }

    mpv.startEventLoop()

    // 10 minutes of synthetic video, played at real speed: the clip must still be open when
    // the last assertion runs, or the failures are about EOF rather than about the binding.
    mpv.command("loadfile", "av://lavfi:testsrc=size=640x360:rate=25:duration=600")

    val loaded = fileLoaded.await(15, TimeUnit.SECONDS)
    check("FILE_LOADED event", loaded)

    // Give it a moment of real playback before reading anything back.
    Thread.sleep(1_500)

    // Guard the rest of the run: every assertion below is meaningless if the file ended.
    check("file still open", mpv.getPropertyBoolean("eof-reached") == false,
        "eof=${mpv.getPropertyBoolean("eof-reached")}")

    val duration = mpv.getPropertyDouble("duration")
    check("duration property", duration != null && duration > 0, "duration=$duration")

    check("time-pos property-change fired", (propCounts["time-pos"]?.get() ?: 0) > 0,
        "n=${propCounts["time-pos"]?.get()}")
    check("time-pos advanced", lastTimePos.get() > 0.0, "last=${lastTimePos.get()}")

    // --- typed setters, the ones the controller maps onto -----------------------------
    mpv.setPropertyBoolean("pause", true)
    Thread.sleep(300)
    check("pause set+readback", mpv.getPropertyBoolean("pause") == true)
    check("pause observed as FLAG", sawPauseFlag.get())

    mpv.setPropertyDouble("speed", 1.5)
    Thread.sleep(200)
    val speed = mpv.getPropertyDouble("speed")
    check("speed set+readback", speed != null && kotlin.math.abs(speed - 1.5) < 0.001, "speed=$speed")
    mpv.setPropertyDouble("speed", 1.0)

    mpv.setPropertyLong("volume", 80)
    Thread.sleep(200)
    val vol = mpv.getPropertyLong("volume")
    check("volume set+readback", vol == 80L, "volume=$vol")

    // --- seek, still paused: the controller seeks in both states ----------------------
    mpv.command("seek", "5.0", "absolute")
    Thread.sleep(1_000)
    val afterSeek = mpv.getPropertyDouble("time-pos")
    check("absolute seek", afterSeek != null && kotlin.math.abs(afterSeek - 5.0) < 1.5,
        "time-pos=$afterSeek")

    mpv.setPropertyBoolean("pause", false)
    Thread.sleep(500)
    check("unpause", mpv.getPropertyBoolean("pause") == false)

    // --- string properties, used for hwdec assertion and track lists ------------------
    val hwdec = mpv.getPropertyString("hwdec-current")
    check("string property read", hwdec != null, "hwdec-current=$hwdec")
    // Must be a POPULATED list: "[]" is what an already-ended file returns, so a non-blank
    // check alone would pass against no file at all.
    val trackList = mpv.getPropertyString("track-list")
    check("track-list populated", (trackList?.length ?: 0) > 2, "${trackList?.take(60)}")

    // A property that does not exist must return null, not throw or crash -- the controller
    // polls several that are simply absent until a file loads.
    check("missing property returns null", mpv.getPropertyString("no-such-property-xyz") == null)

    // --- teardown ---------------------------------------------------------------------
    val disposeOk = runCatching {
        mpv.dispose()
        // A second dispose must be a no-op: Compose can fire onDispose more than once.
        mpv.dispose()
    }.isSuccess
    check("dispose (twice, clean)", disposeOk)
    check("calls after dispose are inert", mpv.getPropertyDouble("time-pos") == null)

    println("\nevents seen: " + eventCounts.entries
        .sortedBy { it.key }.joinToString(", ") { "${eventName(it.key)}=${it.value.get()}" })
    println("property changes: " + propCounts.entries
        .sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value.get()}" })

    report()
}

private fun eventName(id: Int) = when (id) {
    MpvEventId.SHUTDOWN -> "SHUTDOWN"
    MpvEventId.LOG_MESSAGE -> "LOG"
    MpvEventId.START_FILE -> "START_FILE"
    MpvEventId.END_FILE -> "END_FILE"
    MpvEventId.FILE_LOADED -> "FILE_LOADED"
    MpvEventId.IDLE -> "IDLE"
    MpvEventId.VIDEO_RECONFIG -> "VIDEO_RECONFIG"
    MpvEventId.AUDIO_RECONFIG -> "AUDIO_RECONFIG"
    MpvEventId.SEEK -> "SEEK"
    MpvEventId.PLAYBACK_RESTART -> "PLAYBACK_RESTART"
    MpvEventId.PROPERTY_CHANGE -> "PROPERTY_CHANGE"
    else -> "id$id"
}

private fun report() {
    println("\n--- results ---")
    results.forEach { (k, v) -> println("  ${v.padEnd(28)} $k") }
    println(if (failures == 0) "\nALL GREEN (${results.size} checks)" else "\n$failures FAILURE(S)")
    // Non-zero exit so a wrapper script can gate on it.
    if (failures > 0) System.exit(1)
}
