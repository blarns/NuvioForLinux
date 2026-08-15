// Can Compose Desktop's renderer (Skiko) draw a GL texture it does not own?
//
// This is the last open risk on the libmpv GPU-render plan. mpv renders video into
// a GL texture; the Compose player surface has to draw that texture instead of the
// makeRaster copy at PlayerEngine.desktop.kt:385. Everything else is proven.
//
// The texture here is created and filled with raw GL (via JNA) rather than by mpv,
// deliberately: mpv rendering into an FBO is already proven in mpv_gl_hwdec.c. What
// is unproven is the Skia half -- whether Skiko will adopt and draw a foreign texture
// inside its own render pass, and whether its DirectContext is reachable at all.

import com.sun.jna.Library
import com.sun.jna.Native
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate
import java.awt.Dimension
import java.nio.ByteBuffer
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

private const val GL_TEXTURE_2D = 0x0DE1
private const val GL_RGBA8 = 0x8058
private const val GL_RGBA = 0x1908
private const val GL_UNSIGNED_BYTE = 0x1401
private const val GL_TEXTURE_MIN_FILTER = 0x2801
private const val GL_TEXTURE_MAG_FILTER = 0x2800
private const val GL_LINEAR = 0x2601

interface GL : Library {
    fun glGenTextures(n: Int, textures: IntArray)
    fun glBindTexture(target: Int, texture: Int)
    fun glTexImage2D(
        target: Int, level: Int, internalformat: Int, width: Int, height: Int,
        border: Int, format: Int, type: Int, pixels: ByteBuffer?,
    )
    fun glTexParameteri(target: Int, pname: Int, param: Int)
    fun glGetError(): Int
}

val gl: GL = Native.load("GL", GL::class.java)

const val TEX_W = 256
const val TEX_H = 256

/** Walk SkiaLayer -> Redrawer -> ContextHandler.getContext() to reach Skiko's DirectContext. */
fun findDirectContext(layer: SkiaLayer): Pair<DirectContext?, String> {
    return try {
        val redrawer = SkiaLayer::class.java.methods
            .firstOrNull { it.name.startsWith("getRedrawer") }
            ?.invoke(layer) ?: return null to "redrawer is null (not yet initialised?)"

        var cls: Class<*>? = redrawer.javaClass
        while (cls != null) {
            for (f in cls.declaredFields) {
                if (!f.type.name.contains("ContextHandler")) continue
                f.isAccessible = true
                val handler = f.get(redrawer) ?: continue
                var hcls: Class<*>? = handler.javaClass
                while (hcls != null) {
                    val m = hcls.declaredMethods.firstOrNull {
                        it.name == "getContext" && it.parameterCount == 0
                    }
                    if (m != null) {
                        m.isAccessible = true
                        val ctx = m.invoke(handler) as? DirectContext
                        return ctx to "via ${redrawer.javaClass.simpleName}.${f.name}.getContext()"
                    }
                    hcls = hcls.superclass
                }
            }
            cls = cls.superclass
        }
        null to "no ContextHandler field on ${redrawer.javaClass.name}"
    } catch (e: Throwable) {
        null to "reflection failed: ${e::class.simpleName}: ${e.message}"
    }
}

class Probe : SkikoRenderDelegate {
    var texId = 0
    var frames = 0
    var reported = false
    lateinit var layer: SkiaLayer

    override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
        frames++

        if (texId == 0) {
            // Foreign texture: filled with solid magenta so it is unmistakable on screen.
            val px = ByteBuffer.allocateDirect(TEX_W * TEX_H * 4)
            repeat(TEX_W * TEX_H) {
                px.put(0xFF.toByte()); px.put(0x00.toByte())
                px.put(0xFF.toByte()); px.put(0xFF.toByte())
            }
            px.flip()
            val ids = IntArray(1)
            gl.glGenTextures(1, ids)
            texId = ids[0]
            gl.glBindTexture(GL_TEXTURE_2D, texId)
            gl.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, TEX_W, TEX_H, 0, GL_RGBA, GL_UNSIGNED_BYTE, px)
            gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            println("SPIKE: created foreign GL texture id=$texId glError=${gl.glGetError()}")
        }

        val (ctx, how) = findDirectContext(layer)
        if (frames == 1 || (ctx != null && !reported)) {
            println("SPIKE: frame $frames DirectContext=${if (ctx != null) "FOUND" else "null"} ($how)")
        }

        if (ctx == null) {
            // The handler creates its DirectContext lazily; give it several frames
            // before concluding it is unreachable.
            if (frames > 40 && !reported) { reported = true; finish(false, "DirectContext still null after $frames frames") }
            layer.needRedraw()
            return
        }

        try {
            // We just issued raw GL calls behind Skia's back; make it re-read GL state.
            ctx.resetGLAll()
            val backend = BackendTexture.makeGL(TEX_W, TEX_H, false, texId, GL_TEXTURE_2D, GL_RGBA8)
            val img = Image.adoptTextureFrom(ctx, backend, SurfaceOrigin.TOP_LEFT, ColorType.RGBA_8888)
            canvas.drawImage(img, 20f, 20f)
            if (!reported) {
                reported = true
                println("SPIKE: adoptTextureFrom + drawImage OK (image ${img.width}x${img.height})")
                finish(true, "drew foreign texture on Skiko canvas")
            }
        } catch (e: Throwable) {
            if (!reported) {
                reported = true
                println("SPIKE: adopt/draw FAILED: ${e::class.qualifiedName}: ${e.message}")
                finish(false, "adopt/draw threw")
            }
        }
    }

    var frame: JFrame? = null

    fun finish(ok: Boolean, msg: String) {
        Thread {
            Thread.sleep(1200)
            // "drawImage did not throw" is weaker than "the texture is on screen".
            // Grab the window off the screen and look for the magenta we uploaded.
            var pixelsOk = false
            var sample = "n/a"
            try {
                val f = frame
                if (f != null) {
                    val p = f.locationOnScreen
                    val ins = f.insets
                    val shot = java.awt.Robot().createScreenCapture(
                        java.awt.Rectangle(p.x + ins.left + 20, p.y + ins.top + 20, TEX_W, TEX_H)
                    )
                    var magenta = 0
                    for (y in 0 until shot.height step 8) for (x in 0 until shot.width step 8) {
                        val c = shot.getRGB(x, y)
                        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                        if (r > 200 && g < 60 && b > 200) magenta++
                    }
                    val total = (shot.height / 8) * (shot.width / 8)
                    pixelsOk = magenta > total * 0.8
                    sample = "$magenta/$total magenta"
                    javax.imageio.ImageIO.write(shot, "png", java.io.File("skiko-interop.png"))
                }
            } catch (e: Throwable) {
                sample = "capture failed: ${e.message}"
            }
            println("RESULT skiko-interop ok=${ok && pixelsOk} apiOk=$ok pixelsVerified=$pixelsOk ($sample) frames=$frames msg=$msg")
            exitProcess(if (ok && pixelsOk) 0 else 1)
        }.start()
    }
}

fun main() {
    SwingUtilities.invokeLater {
        val probe = Probe()
        val layer = SkiaLayer()
        layer.renderApi = GraphicsApi.OPENGL
        layer.renderDelegate = probe
        probe.layer = layer

        val frame = JFrame("skiko interop spike")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.preferredSize = Dimension(400, 320)
        layer.attachTo(frame.contentPane)
        frame.pack()
        frame.isVisible = true
        probe.frame = frame
        println("SPIKE: renderApi=${layer.renderApi}")
    }
}
