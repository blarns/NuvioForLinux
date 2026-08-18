// Does the desktop frame path leak NATIVE memory?
//
// PlayerEngine.desktop.kt builds one `Image.makeRaster(...)` per displayed frame, at ~30 fps,
// and never closes it. A 4K BGRA frame is ~33 MB of NATIVE Skia memory, while the Kotlin
// wrapper holding it is a few dozen bytes -- so the JVM feels almost no heap pressure and has
// no reason to run a GC, and the native side is only reclaimed when the Cleaner eventually
// fires. That shape matches a live v0.3.4 instance measured at 456 MB of Java heap and ~10 GB
// resident.
//
// ⚠⚠ READ THIS BEFORE QUOTING THE NUMBERS BELOW. They are real but they do NOT transfer to
// the app. This spike reuses ONE input ByteArray, so it puts no pressure on the Java heap, so
// no GC ever runs and nothing ever fires the Cleaners that free the native side. The real
// surface allocates a fresh 33MB ByteArray per frame ON THE HEAP; that churn forces frequent
// GCs and the native memory is reclaimed anyway. Measured A/B in the app over 150s of 4K:
// unfixed peaked 2738MB / settled 1644MB, fixed peaked 2951MB / settled 1652MB -- no
// meaningful difference. Treat this file as a demonstration of the MECHANISM, not of a bug
// worth shipping a fix for on its own.
//
// This reproduces just that loop, with nothing else in it, and compares:
//   leak  -- exactly what ships today
//   close -- the same loop with the intermediate Image closed
//
// It also verifies the ImageBitmap is still VALID after closing, because the fix is only a fix
// if toComposeImageBitmap() copies rather than wraps.
//
// usage: FrameLeakSpikeKt <leak|close|both> [frames] [width] [height]

import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

private fun rssMb(): Double = runCatching {
    java.io.File("/proc/self/status").readLines()
        .first { it.startsWith("VmRSS:") }.filter { it.isDigit() }.toLong() / 1024.0
}.getOrDefault(-1.0)

private fun heapMb(): Double {
    val rt = Runtime.getRuntime()
    return (rt.totalMemory() - rt.freeMemory()) / 1048576.0
}

private fun run(mode: String, frames: Int, w: Int, h: Int): Double {
    val bytes = ByteArray(w * h * 4)
    // Non-uniform content so nothing can be optimised away and the pixel check is meaningful.
    for (i in bytes.indices) bytes[i] = ((i * 31) and 0xFF).toByte()
    val info = ImageInfo(ColorInfo(ColorType.BGRA_8888, ColorAlphaType.OPAQUE, ColorSpace.sRGB), w, h)

    // Exactly what the surface keeps: only the most recent frame is referenced.
    var currentFrame: androidx.compose.ui.graphics.ImageBitmap? = null

    val rssStart = rssMb()
    println("  [$mode] start rss=%.0fMB heap=%.0fMB".format(rssStart, heapMb()))

    // "reuse" allocates ONE native bitmap for the whole run and overwrites its pixels each
    // frame, so there is no per-frame native allocation to leak in the first place.
    val reused = if (mode == "reuse") Bitmap().also { it.allocPixels(info) } else null

    // "recycle" keeps allocating a fresh Bitmap per frame -- so every frame is a distinct
    // object exactly as today, with no risk of Skia caching a stale generation -- but frees
    // the one from two frames ago, which by then cannot still be on screen. Memory is bounded
    // to ~3 frames instead of growing without limit.
    val inFlight = ArrayDeque<Bitmap>()

    for (i in 0 until frames) {
        if (mode == "recycle") {
            val bmp = Bitmap()
            bmp.allocPixels(info)
            bmp.installPixels(info, bytes, w * 4)
            currentFrame = bmp.asComposeImageBitmap()
            inFlight.addLast(bmp)
            while (inFlight.size > 3) inFlight.removeFirst().close()
        } else if (reused != null) {
            reused.installPixels(info, bytes, w * 4)
            currentFrame = reused.asComposeImageBitmap()
        } else {
            val img = Image.makeRaster(info, bytes, w * 4)
            currentFrame = img.toComposeImageBitmap()
            // The one-line difference between shipping behaviour and the first candidate fix.
            // It only halves the growth: toComposeImageBitmap() makes its OWN native copy.
            if (mode == "close") img.close()
        }

        if (i > 0 && i % 100 == 0) {
            println("    frame=%4d  rss=%6.0fMB  heap=%5.0fMB".format(i, rssMb(), heapMb()))
        }
    }

    val rssEnd = rssMb()
    // Prove the retained frame is still usable -- closing the intermediate Image must not
    // invalidate the ImageBitmap, or the "fix" would be a crash instead.
    val px = runCatching {
        val bmp = currentFrame!!
        val buf = IntArray(bmp.width * 1)
        bmp.readPixels(buf, 0, 0, bmp.width, 1)
        buf.count { it != 0 }
    }.getOrElse { -1 }

    println("  [$mode] end   rss=%.0fMB  (grew %+.0fMB)  heap=%.0fMB  nonzero-pixels-in-row0=%d"
        .format(rssEnd, rssEnd - rssStart, heapMb(), px))
    if (px <= 0) println("  [$mode] ⚠ RETAINED FRAME IS INVALID -- closing broke the bitmap")
    return rssEnd - rssStart
}

fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: "both"
    val frames = args.getOrNull(1)?.toIntOrNull() ?: 600
    val w = args.getOrNull(2)?.toIntOrNull() ?: 3840
    val h = args.getOrNull(3)?.toIntOrNull() ?: 2160

    println("=== frame path native-leak spike ===")
    println("${w}x$h  frames=$frames  (one frame = %.1fMB native)".format(w * h * 4 / 1048576.0))
    println("(at 30fps, $frames frames is ${frames / 30}s of playback)\n")

    when (mode) {
        "both" -> {
            // ⚠ Run these in SEPARATE JVMs when comparing for real: a later mode benefits from
            // GCs the earlier one provoked.
            val leaked = run("leak", frames, w, h)
            println()
            val closed = run("close", frames, w, h)
            println()
            val reused = run("reuse", frames, w, h)
            println()
            val recycled = run("recycle", frames, w, h)
            println()
            println("leak    grew: %+.0fMB".format(leaked))
            println("close   grew: %+.0fMB".format(closed))
            println("reuse   grew: %+.0fMB".format(reused))
            println("recycle grew: %+.0fMB".format(recycled))
        }
        else -> run(mode, frames, w, h)
    }
}
