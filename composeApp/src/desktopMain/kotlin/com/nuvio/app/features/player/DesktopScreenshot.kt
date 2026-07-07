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

    fun capture() {
        val (bytes, w, h) = LastFrameStore.snapshot() ?: run {
            NuvioToastController.show("No video frame to capture yet")
            return
        }
        val title = PlayerLaunchStore.currentTitle.value
        scope.launch(Dispatchers.IO) {
            runCatching { saveFrame(bytes, w, h, title) }
                .onSuccess { file -> NuvioToastController.show("Screenshot saved: ${file.absolutePath}") }
                .onFailure { e ->
                    println("$TAG: screenshot failed: ${e.message}")
                    NuvioToastController.show("Screenshot failed: ${e.message}")
                }
        }
    }

    private fun saveFrame(bytes: ByteArray, w: Int, h: Int, title: String?): File {
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

        val dir = screenshotDir().also { it.mkdirs() }
        val safeTitle = (title?.takeIf { it.isNotBlank() } ?: "frame")
            .replace(unsafeChars, "_")
            .take(80)
            .trim('_')
            .ifBlank { "frame" }
        val stamp = LocalDateTime.now().format(timestampFormat)
        val file = File(dir, "Nuvio-$safeTitle-$stamp.png")
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
