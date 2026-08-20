// Does a stalled network stream show up as "loading", and does the log say WHY?
//
// The one stall this engine has produced in the app was a picture frozen at 00:02 with NO
// spinner, after a seek, on a link that had been open for an hour — unreproducible after the
// fact and impossible to diagnose from the log, which only reported frame pacing. Both halves
// are addressed here, against a server that stalls on demand rather than against a real link:
//
//   * `paused-for-cache` must be reflected in the snapshot AT ALL TIMES. It is an observed
//     property, so it only notifies on a CHANGE — any code that resets `isLoading` while it is
//     already true (FILE_LOADED, PLAYBACK_RESTART after a seek) leaves the UI contradicting mpv
//     with no further event to correct it. That is the "no spinner" half, and the invariant is
//     sampled continuously below rather than checked at one convenient moment.
//   * `pacingReport()` must name the reason: pause / core-idle / paused-for-cache / cache
//     buffering / demuxer cache time / eof.
//
// The server is a credit-limited HTTP/1.1 file server: each connection may write at most
// `credit` bytes and then waits, so a stall is exact and reversible, and the test never depends
// on the media's bitrate. Range requests are honoured because mpv seeks with them.
//
// usage: run-mpv-stall-spike.sh <file>

package com.nuvio.app.desktop.mpv

import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
        Thread.sleep(50)
    }
    return cond()
}

/**
 * HTTP file server that hands out only as many bytes as it has been given credit for.
 *
 * ⚠ The budget is SHARED across connections, not per connection. mpv opens a new one for every
 * seek, and a per-connection budget silently handed each of them the full allowance — so the
 * stream never re-starved after a seek and the test measured nothing.
 *
 * Credit is re-read as it is raised, so an exhausted budget wedges every transfer mid-body while
 * leaving the sockets open — which is what a dead link looks like to mpv, as opposed to a closed
 * connection (an error) or a truncated one (an end of file).
 */
private class CreditServer(private val file: java.io.File) {
    val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    /** Bytes the server may still send, across all connections. Raised to feed, spent to stall. */
    val credit = AtomicLong(0)
    val connections = AtomicInteger(0)
    private val stopped = AtomicBoolean(false)
    val url: String get() = "http://127.0.0.1:${socket.localPort}/video"

    fun start() {
        Thread(null, {
            while (!stopped.get()) {
                val client = try { socket.accept() } catch (_: Exception) { return@Thread }
                connections.incrementAndGet()
                Thread(null, { runCatching { serve(client) }; runCatching { client.close() } },
                    "credit-conn", 0).apply { isDaemon = true }.start()
            }
        }, "credit-accept", 0).apply { isDaemon = true }.start()
    }

    private fun serve(client: Socket) {
        val input = client.getInputStream().bufferedReader()
        var start = 0L
        var line = input.readLine()
        while (!line.isNullOrEmpty()) {
            if (line.startsWith("Range:", ignoreCase = true)) {
                start = Regex("bytes=(\\d+)-").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            }
            line = input.readLine()
        }
        val len = file.length()
        val remaining = (len - start).coerceAtLeast(0)
        val out = client.getOutputStream()
        val header = buildString {
            append(if (start > 0) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: video/mp4\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $remaining\r\n")
            if (start > 0) append("Content-Range: bytes $start-${len - 1}/$len\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray())
        out.flush()

        val raf = RandomAccessFile(file, "r")
        raf.seek(start)
        val buf = ByteArray(64 * 1024)
        var written = 0L
        while (written < remaining && !stopped.get()) {
            // Wait, rather than close: a closed socket is an ERROR to mpv and would take the
            // player down a completely different path from the one under test.
            val available = credit.get()
            if (available <= 0) { Thread.sleep(50); continue }
            val chunk = minOf(buf.size.toLong(), available, remaining - written).toInt()
            // Claim from the shared budget BEFORE writing, so two connections cannot both spend
            // the same bytes.
            if (credit.addAndGet(-chunk.toLong()) < 0) { credit.addAndGet(chunk.toLong()); continue }
            val read = raf.read(buf, 0, chunk)
            if (read <= 0) break
            out.write(buf, 0, read)
            out.flush()
            written += read
        }
        raf.close()
    }

    fun stop() {
        stopped.set(true)
        runCatching { socket.close() }
    }
}

fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: run { println("usage: run-mpv-stall-spike.sh <file>"); return }
    val file = java.io.File(path)
    if (!file.isFile) { println("no such file: $path"); return }
    println("=== mpv stall diagnosis spike ===")

    // ⚠ A file small enough to be served whole NEVER STALLS, and every check below then fails
    // for a reason that has nothing to do with the code under test. Refuse rather than report
    // that as a failure.
    if (file.length() < 64L * 1024 * 1024) {
        println("file too small for a stall test: ${file.length() / 1024 / 1024} MB, need >= 64 MB")
        return
    }

    val server = CreditServer(file)
    server.start()
    // Startup is deliberately unconstrained: an mp4 with its index at the END makes mpv read the
    // tail before it can play at all, and a budget tight enough to stall quickly is not enough to
    // get started. The stall is triggered explicitly once playback is running instead.
    server.credit.set(Long.MAX_VALUE / 2)

    val handle = MpvEngineOptions.createHandle(MpvVideoOutput.SOFTWARE)
    check("createHandle", handle != null)
    if (handle == null) { server.stop(); report(); return }
    val renderer = MpvSoftwareRenderer.create(handle, 640, 360)
    check("render context created", renderer != null)
    if (renderer == null) { handle.dispose(); server.stop(); report(); return }

    // ⚠ Without this the demuxer reads ahead until its 150 MiB default, so cutting the source off
    // costs minutes of real time before the cache runs dry and mpv notices. A small cache makes
    // the stall arrive in seconds; it changes WHEN mpv starves, not what it does about it.
    handle.setPropertyString("demuxer-max-bytes", "2MiB")
    handle.setPropertyString("demuxer-max-back-bytes", "1MiB")

    val ctrl = MpvPlayerController(mpv = handle, onError = { println("error: ${it.message}") })
    handle.startEventLoop()

    // The video has to actually be consumed or the cache never drains and nothing ever stalls.
    val pumping = AtomicBoolean(true)
    val frames = AtomicLong(0)
    Thread(null, {
        val buf = ByteArray(640 * 360 * 4)
        while (pumping.get()) {
            if (renderer.hasNewFrame() && renderer.render(buf)) frames.incrementAndGet()
            else Thread.sleep(5)
        }
    }, "spike-frames", 0).apply { isDaemon = true }.start()

    // ⚠ The invariant is sampled from here to teardown, not asserted at chosen moments: a
    // clobbered `isLoading` corrects itself on the next real change, so a snapshot taken at the
    // wrong instant would call the bug fixed. A violation must PERSIST to count — the event
    // thread is allowed to lag the property read by a poll or two.
    val violations = AtomicInteger(0)
    val worstViolationMs = AtomicLong(0)
    val watching = AtomicBoolean(true)
    Thread(null, {
        var firstBadMs = 0L
        while (watching.get()) {
            val buffering = handle.getPropertyBoolean("paused-for-cache") == true
            val loading = ctrl.currentSnapshot().isLoading
            if (buffering && !loading) {
                if (firstBadMs == 0L) firstBadMs = System.currentTimeMillis()
                val held = System.currentTimeMillis() - firstBadMs
                if (held > 700) {
                    violations.incrementAndGet()
                    worstViolationMs.set(maxOf(worstViolationMs.get(), held))
                }
            } else {
                firstBadMs = 0L
            }
            Thread.sleep(100)
        }
    }, "spike-invariant", 0).apply { isDaemon = true }.start()

    ctrl.loadMedia(server.url, emptyMap(), playWhenReady = true, startPositionMs = 0L)
    check("playback starts over HTTP", waitFor(30_000) { ctrl.currentSnapshot().isPlaying },
        "snapshot=${ctrl.currentSnapshot()}")
    check("server was actually used", server.connections.get() > 0,
        "connections=${server.connections.get()}")

    // --- 1. the stall ------------------------------------------------------------------------
    // The source goes silent mid-stream without closing anything — a dead link, not an error.
    server.credit.set(0)
    val stalled = waitFor(60_000) { handle.getPropertyBoolean("paused-for-cache") == true }
    check("mpv reports paused-for-cache when the source stops feeding", stalled,
        "cache-time=${handle.getPropertyDouble("demuxer-cache-time")}")

    check("a stall is reported as loading, not as playing normally",
        waitFor(3_000) { ctrl.currentSnapshot().isLoading }, "snapshot=${ctrl.currentSnapshot()}")

    val stallReport = ctrl.pacingReport()
    check("the log names the reason", stallReport.contains("paused-for-cache=true"), stallReport)
    listOf("pause=", "core-idle=", "buffering=", "cache-time=", "eof=").forEach { key ->
        check("pacing report has $key", stallReport.contains(key), stallReport)
    }

    // --- 2. a seek that cannot land ----------------------------------------------------------
    // ⚠ This is the sequence behind the frozen-picture-with-no-spinner report. The source is
    // still cut off, so the seek never completes: the timeline jumps to the target, the picture
    // holds the old frame, and mpv reports `paused-for-cache` as NULL while it re-opens the
    // demuxer — so at that moment NOTHING in the engine says "waiting" except the fact that a
    // seek is outstanding.
    val durationMs = ctrl.currentSnapshot().durationMs
    if (durationMs > 20_000) {
        ctrl.seekTo(durationMs / 2)
        Thread.sleep(3_000)
        val afterSeek = ctrl.currentSnapshot()
        check("an unlanded seek is not reported as normal playback", afterSeek.isLoading,
            "isLoading=${afterSeek.isLoading} " +
                "paused-for-cache=${handle.getPropertyBoolean("paused-for-cache")}")
        // Past the 8s settle timeout the position override is dropped so the timeline stops
        // lying — but the seek is still outstanding, and dropping the spinner with it is what
        // left the player looking like it was playing while nothing moved.
        Thread.sleep(7_000)
        check("a seek outstanding past the settle timeout still says loading",
            ctrl.currentSnapshot().isLoading, "snapshot=${ctrl.currentSnapshot()}")
    } else {
        results["seek during stall"] = "SKIP  (clip too short: ${durationMs}ms)"
    }

    // --- 3. recovery -------------------------------------------------------------------------
    server.credit.set(file.length())
    check("playback resumes when the source feeds again",
        waitFor(30_000) { handle.getPropertyBoolean("paused-for-cache") == false })
    check("the spinner clears with it", waitFor(5_000) { !ctrl.currentSnapshot().isLoading },
        "snapshot=${ctrl.currentSnapshot()}")
    val framesBefore = frames.get()
    Thread.sleep(2_000)
    check("frames flow again after the stall", frames.get() > framesBefore,
        "frames ${framesBefore} -> ${frames.get()}")

    // --- 4. the EOF replay, which reaches its seek through play() -----------------------------
    // ⚠ The reported stall happened after an end-of-file REPLAY, and that path never calls
    // seekTo: `play()` seeks internally when `eof-reached` is set. If it issues that seek without
    // recording one is outstanding, the spinner signal is never armed and the original symptom
    // returns on the exact path that produced it. So: play to the end, cut the source off, and
    // press play.
    if (durationMs > 20_000) {
        ctrl.seekTo(durationMs - 3_000)
        val reachedEof = waitFor(60_000) { handle.getPropertyBoolean("eof-reached") == true }
        check("reaches end of file", reachedEof, "snapshot=${ctrl.currentSnapshot()}")
        if (reachedEof) {
            // The back-cache is 1 MiB, so position 0 was evicted long ago: replaying has to go
            // back to the network, and the network is about to go quiet.
            server.credit.set(0)
            ctrl.play()
            Thread.sleep(3_000)
            check("a replay from EOF into a dead source shows a spinner",
                ctrl.currentSnapshot().isLoading, "snapshot=${ctrl.currentSnapshot()}")
        }
    }

    watching.set(false)
    check("isLoading never contradicted mpv for longer than a poll", violations.get() == 0,
        "violations=${violations.get()} worst=${worstViolationMs.get()}ms")

    pumping.set(false)
    Thread.sleep(200)
    check("dispose clean", runCatching { renderer.dispose(); ctrl.dispose() }.isSuccess)
    server.stop()

    println("\nlast pacing report: ${stallReport}")
    report()
}

private fun report() {
    println("\n--- results ---")
    results.forEach { (k, v) -> println("  ${v.padEnd(40)} $k") }
    println(if (failures == 0) "\nALL GREEN (${results.size} checks)" else "\n$failures FAILURE(S)")
    if (failures > 0) System.exit(1)
}
