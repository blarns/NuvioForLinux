// The sustained interleaved soak -- the last unproven thing in the mpv/Compose design.
//
// EglSkiaMpvSpike.kt renders 60 mpv frames and THEN two Skia draws: one alternation. The
// real player interleaves every single frame for hours, with DirectContext.resetGLAll()
// between them. If Skia's cached GL view degrades over thousands of alternations, or if
// adopting a texture per frame leaks, neither shows up in one pass -- and finding it after
// the GPU path is wired into Compose means debugging it through the EDT and the Compose
// lifecycle instead of here.
//
// Each iteration does exactly what the player will do:
//   mpv renders a frame -> Skia adopts that texture -> Skia draws it -> Skia draws a probe
//   rectangle on top -> the probe is read back and verified
//
// Failing the probe means Skia's state was clobbered. A non-zero glGetError means the
// context is degrading even while pixels still look right. RSS growth means a per-frame leak.
//
// usage: EglSkiaMpvSoakKt <file|url> [iterations] [hwdec]

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.GLAssembledInterface
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.makeGLWithInterface
import kotlin.system.exitProcess

interface SoakShim : Library {
    fun egl_shim_setup(): Int
    fun egl_shim_error(): String
    fun egl_shim_get_proc_fn(): Pointer
    fun egl_shim_make_fbo(w: Int, h: Int): Int
    fun egl_shim_read_pixel(fbo: Int, x: Int, y: Int): Int
    fun egl_shim_gl_error(): Int
    fun mpv_shim_start(file: String, hwdec: String, w: Int, h: Int, hdrs: String): Int
    fun mpv_shim_render(want: Int): Int
    fun mpv_shim_hwdec_current(): String
    fun mpv_shim_texture(): Int
    fun mpv_shim_frame_mean(): Int
    fun mpv_shim_stop()
}

private const val GL_TEXTURE_2D = 0x0DE1
private const val GL_RGBA8 = 0x8058

/** Distinctive colours so a stale buffer cannot be mistaken for a correct draw. */
private val PROBES = intArrayOf(
    0xFF3FA9C8.toInt(), 0xFFE0542E.toInt(), 0xFF7BC043.toInt(), 0xFFF6C90E.toInt(),
)

/** Resident set size in KB — a per-frame GL leak shows here long before it exhausts memory. */
private fun rssKb(): Long = runCatching {
    java.io.File("/proc/self/status").readLines()
        .first { it.startsWith("VmRSS:") }.filter { it.isDigit() }.toLong()
}.getOrDefault(-1L)

fun main(args: Array<String>) {
    val file = args.getOrNull(0) ?: error("usage: EglSkiaMpvSoakKt <file|url> [iterations] [hwdec]")
    val iterations = args.getOrNull(1)?.toIntOrNull() ?: 2000
    val hwdec = args.getOrNull(2) ?: "vaapi"
    val w = System.getenv("SOAK_W")?.toIntOrNull() ?: 1920
    val h = System.getenv("SOAK_H")?.toIntOrNull() ?: 1080

    val s = Native.load("eglskiashim", SoakShim::class.java)
    if (s.egl_shim_setup() != 0) error("egl setup: ${s.egl_shim_error()}")

    val ctx = DirectContext.makeGLWithInterface(
        GLAssembledInterface.createFromNativePointers(0L, Pointer.nativeValue(s.egl_shim_get_proc_fn())),
    )

    val rc = s.mpv_shim_start(file, hwdec, w, h, "")
    if (rc != 0) error("mpv start rc=$rc ${s.egl_shim_error()}")

    // Prime the pipeline, then assert the decode tier BEFORE soaking: a soak that ran on
    // software decode would look perfectly healthy and prove nothing about the real path.
    s.mpv_shim_render(5)
    val tier = s.mpv_shim_hwdec_current()
    println("hwdec-current=$tier  (requested=$hwdec)  ${w}x$h  iterations=$iterations")
    if (hwdec == "vaapi" && tier != "vaapi") {
        println("RESULT soak ok=false reason=hwdec-degraded-to-$tier")
        s.mpv_shim_stop()
        exitProcess(1)
    }

    val target = s.egl_shim_make_fbo(w, h)
    val rt = BackendRenderTarget.makeGL(w, h, 0, 8, target, GL_RGBA8)
    val surface = Surface.makeFromBackendRenderTarget(
        ctx, rt, SurfaceOrigin.BOTTOM_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB,
    ) ?: error("makeFromBackendRenderTarget returned null")

    val rssStart = rssKb()
    val started = System.nanoTime()
    var probeFailures = 0
    var glErrors = 0
    var blackFrames = 0
    var firstFailureAt = -1
    var mpvFramesTotal = 0

    for (i in 0 until iterations) {
        // 1. mpv renders one frame into its own FBO, exactly as the player will.
        mpvFramesTotal += s.mpv_shim_render(1)

        // 2. Skia must be told to re-read GL state; mpv changed it behind Skia's back.
        ctx.resetGLAll()

        // 3. Adopt mpv's texture and draw it. Done EVERY iteration, because per-frame
        //    adoption is what the player does and is the most likely thing to leak.
        val bt = BackendTexture.makeGL(w, h, false, s.mpv_shim_texture(), GL_TEXTURE_2D, GL_RGBA8)
        val img = Image.adoptTextureFrom(ctx, bt, SurfaceOrigin.TOP_LEFT, ColorType.RGBA_8888)
        surface.canvas.drawImage(img, 0f, 0f)
        // ⚠ Both are native handles behind a Cleaner, not plain Kotlin objects. Letting GC
        // reclaim them "eventually" leaks steadily at one texture per frame -- measured at
        // ~1 MB per 1000 iterations at 4K before these closes were added, which is ~330 MB
        // over a two-hour film. Closing per frame is mandatory in the real render path.
        img.close()
        bt.close()

        // 4. Draw a probe rectangle on top and verify it. The colour rotates so a stale
        //    framebuffer cannot pass by accident.
        val probe = PROBES[i % PROBES.size]
        surface.canvas.drawRect(Rect(0f, 0f, 64f, 64f), Paint().apply { color = probe })
        ctx.flushAndSubmit(surface, false)

        val got = s.egl_shim_read_pixel(target, 20, h - 20)
        if (got != probe) {
            if (firstFailureAt < 0) firstFailureAt = i
            probeFailures++
        }
        val err = s.egl_shim_gl_error()
        if (err != 0) {
            if (firstFailureAt < 0) firstFailureAt = i
            glErrors++
        }

        // Cheap sanity that mpv is still producing real video rather than looping on a
        // black frame after some internal failure.
        if (i % 250 == 0) {
            val mean = s.mpv_shim_frame_mean()
            if (mean <= 8) blackFrames++
            println(
                "  iter=%5d  probeFail=%d  glErr=%d  meanLuma=%3d  rss=%dMB  %.1f it/s"
                    .format(
                        i, probeFailures, glErrors, mean, rssKb() / 1024,
                        i * 1e9 / (System.nanoTime() - started).coerceAtLeast(1),
                    ),
            )
        }
    }

    val elapsedS = (System.nanoTime() - started) / 1e9
    val rssEnd = rssKb()

    // RSS alone cannot tell a native leak from JVM heap that simply has not been collected
    // yet, and the difference decides whether this matters: heap is reclaimed under pressure,
    // native texture memory is not. Collect first, then measure again.
    System.gc()
    Thread.sleep(1_500)
    System.gc()
    Thread.sleep(1_500)
    val rssAfterGc = rssKb()
    val rssGrowthMb = (rssAfterGc - rssStart) / 1024.0

    s.mpv_shim_stop()

    println()
    println("iterations       : $iterations  (mpv frames rendered: $mpvFramesTotal)")
    println("elapsed          : %.1fs  (%.1f alternations/sec)".format(elapsedS, iterations / elapsedS))
    println("probe failures   : $probeFailures" + if (firstFailureAt >= 0) "  first at iter $firstFailureAt" else "")
    println("gl errors        : $glErrors")
    println("black-frame polls: $blackFrames")
    println("rss              : ${rssStart / 1024}MB -> ${rssEnd / 1024}MB, ${rssAfterGc / 1024}MB after GC  (%+.1fMB retained)".format(rssGrowthMb))

    // A slow leak is the realistic failure here, so RETAINED growth (post-GC) is a criterion
    // rather than a note. Skia's own resource cache has a budget it fills toward, and the JIT
    // accounts for some one-off growth, so the bar is set above that rather than at zero.
    val leaked = rssGrowthMb > 64
    val ok = probeFailures == 0 && glErrors == 0 && blackFrames == 0 && !leaked
    println("RESULT soak ok=$ok" + if (ok) "" else " (probe=$probeFailures gl=$glErrors black=$blackFrames leaked=$leaked)")
    exitProcess(if (ok) 0 else 1)
}
