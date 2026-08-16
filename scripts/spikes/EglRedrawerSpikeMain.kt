// Harness for EglRedrawerSpike.kt: installs the EGL RenderFactory globally, puts a
// real SkiaLayer in a real window, and checks what actually reached the screen.
//
// Success criteria are deliberately stricter than "it rendered", because the
// failure modes here are visual rather than exceptional:
//   - the EGL redrawer is the one actually used (not a silent fallback)
//   - known colours land at known places, read back with Robot
//   - RESIZE still renders correctly -- the most likely break, since surface
//     recreation lives in initCanvas and is easy to get wrong
//   - NUVIO_EGL_FORCE_FAIL=1 exercises the GLX fallback deliberately, because
//     otherwise that path is untested code that only runs on machines we cannot
//     reproduce

@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skiko.RenderFactory
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.Robot
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

private const val TOP_LEFT_ARGB = 0xFF3FA9C8.toInt()
private const val BOTTOM_RIGHT_ARGB = 0xFFC83F7A.toInt()

internal object EglInstall {
    /** Null on success, else why it failed. */
    fun installGlobally(): String? {
        if (System.getenv("NUVIO_EGL_FORCE_FAIL") == "1") return "forced failure (NUVIO_EGL_FORCE_FAIL=1)"
        return try {
            val companion = Class.forName("org.jetbrains.skiko.RenderFactory\$Companion")
            val field = companion.getDeclaredField("Default")
            field.isAccessible = true
            val original = field.get(null) as RenderFactory

            // Default is `private static final`, so Field.set throws. The app already
            // ships --add-opens=java.base/sun.misc and the jdk.unsupported module,
            // because VLCJ needs Unsafe for its native video buffers.
            val theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            theUnsafe.isAccessible = true
            val unsafe = theUnsafe.get(null)
            val unsafeClass = unsafe.javaClass
            val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java)
                .invoke(unsafe, field)
            val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java)
                .invoke(unsafe, field) as Long
            unsafeClass.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
                .invoke(unsafe, base, offset, EglRenderFactory(original))

            if (field.get(null) !is EglRenderFactory) "write did not stick" else null
        } catch (t: Throwable) {
            "${t::class.qualifiedName}: ${t.message}"
        }
    }
}

private class Content : SkikoRenderDelegate {
    @Volatile var frames = 0
    @Volatile var lastW = 0
    @Volatile var lastH = 0

    override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
        frames++
        lastW = width
        lastH = height
        canvas.clear(Color.makeARGB(255, 20, 20, 24))
        // Anchored to the layer's CURRENT size, so a resize that failed to recreate
        // the Skia surface shows up as the marks landing in the wrong place.
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(Rect(0f, 0f, w * 0.25f, h * 0.25f), Paint().apply { color = TOP_LEFT_ARGB })
        canvas.drawRect(Rect(w * 0.75f, h * 0.75f, w, h), Paint().apply { color = BOTTOM_RIGHT_ARGB })
    }
}

private val failures = mutableListOf<String>()

private fun check(name: String, ok: Boolean, detail: String) {
    println("  ${if (ok) "PASS" else "FAIL"}  $name -- $detail")
    if (!ok) failures += name
}

private fun sample(frame: JFrame, fx: Double, fy: Double): Int {
    val p = frame.locationOnScreen
    val ins = frame.insets
    val w = frame.width - ins.left - ins.right
    val h = frame.height - ins.top - ins.bottom
    val x = p.x + ins.left + (w * fx).toInt().coerceIn(0, w - 1)
    val y = p.y + ins.top + (h * fy).toInt().coerceIn(0, h - 1)
    return Robot().createScreenCapture(Rectangle(x, y, 1, 1)).getRGB(0, 0) or 0xFF000000.toInt()
}

private fun near(a: Int, b: Int, tol: Int = 12): Boolean {
    fun ch(v: Int, s: Int) = (v shr s) and 0xFF
    return listOf(16, 8, 0).all { s -> kotlin.math.abs(ch(a, s) - ch(b, s)) <= tol }
}

fun main() {
    val installErr = EglInstall.installGlobally()
    val forcedFail = System.getenv("NUVIO_EGL_FORCE_FAIL") == "1"
    println("SPIKE: installGlobally -> ${installErr ?: "ok"}")

    val content = Content()
    lateinit var frame: JFrame
    lateinit var layer: SkiaLayer

    SwingUtilities.invokeAndWait {
        layer = SkiaLayer()
        layer.renderDelegate = content
        frame = JFrame("nuvio EGL redrawer spike")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.preferredSize = Dimension(600, 400)
        layer.attachTo(frame.contentPane)
        frame.pack()
        frame.setLocation(80, 80)
        frame.isVisible = true
    }
    // Compose drives its own invalidation; a bare SkiaLayer needs to be asked.
    repeat(3) { SwingUtilities.invokeLater { layer.needRedraw() }; Thread.sleep(200) }
    Thread.sleep(1200)

    val redrawerName = SkiaLayer::class.java.methods
        .firstOrNull { it.name.startsWith("getRedrawer") }
        ?.invoke(layer)?.javaClass?.simpleName ?: "(none)"

    if (forcedFail) {
        // The fallback path: stock skiko must take over and still render.
        check("fallback-not-egl", redrawerName != "EglRedrawer", "redrawer=$redrawerName")
        check("fallback-renders", content.frames > 0, "frames=${content.frames}")
        check("fallback-pixels", near(sample(frame, 0.1, 0.1), TOP_LEFT_ARGB), "top-left sampled")
        report("egl-redrawer-fallback")
    }

    check("egl-redrawer-used", redrawerName == "EglRedrawer", "redrawer=$redrawerName")
    check("renders-frames", content.frames > 0, "frames=${content.frames}")
    check("render-info", (layer.renderInfo ?: "").contains("EGL"), "renderInfo=${layer.renderInfo}")

    val tl = sample(frame, 0.10, 0.10)
    val br = sample(frame, 0.90, 0.90)
    check("top-left-colour", near(tl, TOP_LEFT_ARGB), "0x%08X vs 0x%08X".format(tl, TOP_LEFT_ARGB))
    check("bottom-right-colour", near(br, BOTTOM_RIGHT_ARGB), "0x%08X vs 0x%08X".format(br, BOTTOM_RIGHT_ARGB))

    // Resize: the surface must be recreated at the new size, and the marks must
    // still land in the corners. If initCanvas ignores the size change they drift.
    val before = content.frames
    SwingUtilities.invokeAndWait { frame.setSize(900, 620) }
    repeat(3) { SwingUtilities.invokeLater { layer.needRedraw() }; Thread.sleep(200) }
    Thread.sleep(1200)
    check("renders-after-resize", content.frames > before, "frames ${before} -> ${content.frames}")
    check("resize-size-propagated", content.lastW > 700, "layer reported ${content.lastW}x${content.lastH}")
    val tl2 = sample(frame, 0.10, 0.10)
    val br2 = sample(frame, 0.90, 0.90)
    check("top-left-after-resize", near(tl2, TOP_LEFT_ARGB), "0x%08X".format(tl2))
    check("bottom-right-after-resize", near(br2, BOTTOM_RIGHT_ARGB), "0x%08X".format(br2))

    report("egl-redrawer")
}

private fun report(tag: String): Nothing {
    val ok = failures.isEmpty()
    println("RESULT $tag ok=$ok" + if (ok) "" else " failed=${failures.joinToString(",")}")
    exitProcess(if (ok) 0 else 1)
}
