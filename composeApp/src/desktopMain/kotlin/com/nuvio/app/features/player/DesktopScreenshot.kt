package com.nuvio.app.features.player

import com.nuvio.app.core.ui.NuvioToastController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

/**
 * Holds the most recent decoded video frame (BGRA, as delivered to the VLCJ render
 * callback) so the "S" screenshot hotkey can grab it without re-reading libVLC.
 *
 * The stored ByteArray is the SAME reference the render callback already copied off the
 * native buffer — no extra allocation on the hot path. Reads take a local snapshot of the
 * volatile triple so a concurrent update can't tear width/height away from the bytes.
 */
internal object LastFrameStore {
    private data class Frame(val bytes: ByteArray, val width: Int, val height: Int)

    @Volatile private var frame: Frame? = null

    fun update(bytes: ByteArray, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        frame = Frame(bytes, width, height)
    }

    fun clear() {
        frame = null
    }

    fun snapshot(): Triple<ByteArray, Int, Int>? =
        frame?.let { Triple(it.bytes, it.width, it.height) }
}

/**
 * Saves the current video frame to a PNG on disk and toasts the result.
 *
 * Bound to the "S" key in the desktop window handler. Conversion + I/O run off the UI
 * thread; failures are reported via a toast and never crash the player.
 */
internal object DesktopScreenshot {
    private const val TAG = "NuvioScreenshot"
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val unsafeChars = Regex("[^a-zA-Z0-9._-]+")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Set by an engine that can write a **source-resolution** frame itself; returns true when it
     * wrote [File].
     *
     * Needed because [LastFrameStore] holds whatever the renderer produced, and that is no longer
     * always the source frame: the mpv path scales to the window while decoding, so a 4K film
     * would be screenshotted at window size. libmpv can save the real frame itself, so it does.
     */
    @Volatile
    var engineCapture: ((File) -> Boolean)? = null

    fun capture() {
        val engine = engineCapture
        val fallback = LastFrameStore.snapshot()
        if (engine == null && fallback == null) {
            NuvioToastController.show("No video frame to capture yet")
            return
        }
        val title = PlayerLaunchStore.currentTitle.value
        scope.launch(Dispatchers.IO) {
            runCatching {
                val target = screenshotFile(title)
                // Fall through to the stored frame if the engine declines — a screenshot at the
                // wrong resolution still beats no screenshot.
                if (engine != null && runCatching { engine(target) }.getOrDefault(false) &&
                    target.length() > 0L
                ) {
                    target
                } else {
                    val (bytes, w, h) = fallback ?: error("no video frame to capture")
                    saveFrame(bytes, w, h, target)
                }
            }
                .onSuccess { file -> NuvioToastController.show("Screenshot saved: ${file.absolutePath}") }
                .onFailure { e ->
                    println("$TAG: screenshot failed: ${e.message}")
                    NuvioToastController.show("Screenshot failed: ${e.message}")
                }
        }
    }

    private fun screenshotFile(title: String?): File {
        val dir = screenshotDir().also { it.mkdirs() }
        val safeTitle = (title?.takeIf { it.isNotBlank() } ?: "frame")
            .replace(unsafeChars, "_")
            .take(80)
            .trim('_')
            .ifBlank { "frame" }
        val stamp = LocalDateTime.now().format(timestampFormat)
        return File(dir, "Nuvio-$safeTitle-$stamp.png")
    }

    private fun saveFrame(bytes: ByteArray, w: Int, h: Int, file: File): File {
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        // BGRA little-endian bytes -> packed 0xRRGGBB ints.
        val pixels = IntArray(w * h)
        var si = 0
        for (i in pixels.indices) {
            val b = bytes[si].toInt() and 0xFF
            val g = bytes[si + 1].toInt() and 0xFF
            val r = bytes[si + 2].toInt() and 0xFF
            pixels[i] = (r shl 16) or (g shl 8) or b
            si += 4
        }
        image.setRGB(0, 0, w, h, pixels, 0, w)
        ImageIO.write(image, "png", file)
        return file
    }

    private fun screenshotDir(): File {
        val home = System.getProperty("user.home").orEmpty()
        val pictures = File(home, "Pictures")
        return if (pictures.isDirectory || pictures.mkdirs()) {
            File(pictures, "Nuvio")
        } else {
            File(home, ".local/share/nuvio/screenshots")
        }
    }
}
