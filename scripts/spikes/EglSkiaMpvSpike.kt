// The whole design, end to end, on one EGL context.
//
// EglSkiaSpike.kt proved the shipped skiko can rasterise on EGL via
// makeGLWithInterface. That is necessary but not sufficient: the reason to be on
// EGL is that mpv can then hardware-decode zero-copy into a texture Skia adopts,
// both sharing a single context. Sharing is the unproven part -- mpv resets a lot
// of GL state and Skia caches its view of that state.
//
// Asserts, in order:
//   1. Skia gets a DirectContext on the EGL context           (stage A, repeated)
//   2. mpv reports hwdec-current == "vaapi" on that context    <- the whole point
//   3. mpv's frames are real video, not black
//   4. Skia adopts mpv's texture and draws it, pixels verified
//   5. Skia still works AFTER mpv has been rendering           <- state clobbering

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

interface MShim : Library {
    fun egl_shim_setup(): Int
    fun egl_shim_error(): String
    fun egl_shim_get_proc_fn(): Pointer
    fun egl_shim_make_fbo(w: Int, h: Int): Int
    fun egl_shim_read_pixel(fbo: Int, x: Int, y: Int): Int
    fun mpv_shim_start(file: String, hwdec: String, w: Int, h: Int, hdrs: String): Int
    fun mpv_shim_render(want: Int): Int
    fun mpv_shim_hwdec_current(): String
    fun mpv_shim_texture(): Int
    fun mpv_shim_frame_mean(): Int
    fun mpv_shim_stop()
}

const val VW = 3840
const val VH = 2160
const val GL_TEXTURE_2D = 0x0DE1
const val GL_RGBA8 = 0x8058
const val PROBE = 0xFF3FA9C8.toInt()

val failures = mutableListOf<String>()
fun check(name: String, ok: Boolean, detail: String) {
    println("  ${if (ok) "PASS" else "FAIL"}  $name -- $detail")
    if (!ok) failures += name
}

fun main(args: Array<String>) {
    val file = args.getOrNull(0) ?: error("usage: EglSkiaMpvSpikeKt <file|url> [hwdec] [headers]")
    val hwdec = args.getOrNull(1) ?: "vaapi"
    val hdrs = args.getOrNull(2) ?: ""

    val s = Native.load("eglskiashim", MShim::class.java)
    if (s.egl_shim_setup() != 0) error("egl setup: ${s.egl_shim_error()}")

    val ctx = DirectContext.makeGLWithInterface(
        GLAssembledInterface.createFromNativePointers(0L, Pointer.nativeValue(s.egl_shim_get_proc_fn())),
    )
    check("skia-directcontext-on-egl", true, "makeGLWithInterface succeeded")

    val rc = s.mpv_shim_start(file, hwdec, VW, VH, hdrs)
    if (rc != 0) error("mpv start rc=$rc ${s.egl_shim_error()}")
    val frames = s.mpv_shim_render(60)

    // The assertion that catches silent degradation. Everything still "works"
    // at vaapi-copy or no -- it just costs 4-8x the CPU.
    val current = s.mpv_shim_hwdec_current()
    check("mpv-zero-copy-vaapi", current == "vaapi", "hwdec-current=$current frames=$frames")

    val mean = s.mpv_shim_frame_mean()
    check("mpv-frames-are-video", mean > 8, "mean luma=$mean (black frame would be ~0)")

    // Skia has to be told to re-read GL state: mpv changed it behind its back.
    ctx.resetGLAll()
    val target = s.egl_shim_make_fbo(VW, VH)
    val rt = BackendRenderTarget.makeGL(VW, VH, 0, 8, target, GL_RGBA8)
    val surface = Surface.makeFromBackendRenderTarget(
        ctx, rt, SurfaceOrigin.BOTTOM_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB,
    ) ?: error("makeFromBackendRenderTarget returned null")

    val adopted = try {
        val bt = BackendTexture.makeGL(VW, VH, false, s.mpv_shim_texture(), GL_TEXTURE_2D, GL_RGBA8)
        val img = Image.adoptTextureFrom(ctx, bt, SurfaceOrigin.TOP_LEFT, ColorType.RGBA_8888)
        surface.canvas.drawImage(img, 0f, 0f)
        ctx.flushAndSubmit(surface, false)
        true
    } catch (e: Throwable) {
        println("  adopt/draw threw: ${e::class.qualifiedName}: ${e.message}")
        false
    }
    // Sample off-centre: frame centres can legitimately be near-black.
    val drawn = s.egl_shim_read_pixel(target, VW / 3, VH / 3)
    val lum = ((drawn shr 16 and 0xFF) + (drawn shr 8 and 0xFF) + (drawn and 0xFF)) / 3
    check("skia-draws-mpv-texture", adopted && lum > 8, "adopted=$adopted sampled=0x%08X luma=%d".format(drawn, lum))

    // If mpv left Skia's cached GL state poisoned, this is where it shows.
    ctx.resetGLAll()
    surface.canvas.drawRect(Rect(0f, 0f, 64f, 64f), Paint().apply { color = PROBE })
    ctx.flushAndSubmit(surface, false)
    val after = s.egl_shim_read_pixel(target, 20, VH - 20)
    check("skia-still-works-after-mpv", after == PROBE, "expected=0x%08X got=0x%08X".format(PROBE, after))

    s.mpv_shim_stop()
    val ok = failures.isEmpty()
    println("RESULT egl-skia-mpv ok=$ok" + if (ok) "" else " failed=${failures.joinToString(",")}")
    exitProcess(if (ok) 0 else 1)
}
