// Can the SHIPPED skiko render on an EGL context?
//
// Why this matters: mpv's zero-copy VA-API interop is EGL-only, and Skiko on
// Linux is GLX (libskiko links libGLX and exports no EGL symbols). That caps the
// whole GPU-render plan at vaapi-copy -- 15.34s vs 3.91s on the real 4K stream.
//
// The known fix is a forked skiko built with skia_use_egl=true, which means
// owning or trusting a third-party build of the native library the entire app
// draws through. This tests a cheaper alternative.
//
// Skia's GrGLMakeNativeInterface() on Linux bails on `!glXGetCurrentContext()`,
// which is what makes EGL impossible via the normal DirectContext.makeGL(). But
// the shipped skiko also exposes:
//
//     GLAssembledInterface.createFromNativePointers(ctx, getProcFnPtr)
//     DirectContext.makeGLWithInterface(iface)
//
// which assembles the GL interface from a caller-supplied proc-address function
// and never asks GLX anything. If Skia can be driven that way on an EGL context,
// no fork is needed -- a custom skiko Redrawer could put Compose itself on EGL,
// and mpv would then share that context with full zero-copy.
//
// Deliberately headless (pbuffer-less EGL, FBO target): no AWT, no JAWT, no
// Compose. Those are engineering, not unknowns. This isolates the one unknown.

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.GLAssembledInterface
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.makeGLWithInterface
import kotlin.system.exitProcess

interface Shim : Library {
    fun egl_shim_setup(): Int
    fun egl_shim_error(): String
    fun egl_shim_get_proc_fn(): Pointer
    fun egl_shim_has_egl_context(): Int
    fun egl_shim_gl_version(): String
    fun egl_shim_make_fbo(w: Int, h: Int): Int
    fun egl_shim_read_pixel(fbo: Int, x: Int, y: Int): Int
}

const val W = 256
const val H = 256

// Deliberately not a primary or a grey: a wrong-format or wrong-swizzle result
// would still be "some colour", but it would not be this one.
const val EXPECT_ARGB = 0xFF3FA9C8.toInt()

fun fail(msg: String): Nothing {
    println("RESULT egl-skia ok=false msg=$msg")
    exitProcess(1)
}

fun main() {
    val shim = Native.load("eglskiashim", Shim::class.java)

    val rc = shim.egl_shim_setup()
    if (rc != 0) fail("egl setup failed rc=$rc ${shim.egl_shim_error()}")
    if (shim.egl_shim_has_egl_context() != 1) fail("no current EGL context after setup")
    println("SPIKE: EGL context current, GL_VERSION=${shim.egl_shim_gl_version()}")

    // The load-bearing step. If skiko could only ever do GLX, this is where it dies.
    val getProc = Pointer.nativeValue(shim.egl_shim_get_proc_fn())
    val iface = try {
        GLAssembledInterface.createFromNativePointers(0L, getProc)
    } catch (e: Throwable) {
        fail("createFromNativePointers threw: ${e::class.qualifiedName}: ${e.message}")
    }

    val ctx = try {
        DirectContext.makeGLWithInterface(iface)
    } catch (e: Throwable) {
        fail("makeGLWithInterface threw: ${e::class.qualifiedName}: ${e.message}")
    }
    println("SPIKE: DirectContext created on EGL via makeGLWithInterface")

    val fbo = shim.egl_shim_make_fbo(W, H)
    if (fbo == 0) fail("FBO creation failed: ${shim.egl_shim_error()}")

    // A DirectContext that exists but cannot actually rasterise would still pass
    // every check above, so draw through it and read the pixels back.
    val rt = BackendRenderTarget.makeGL(W, H, 0, 8, fbo, 0x8058 /* GL_RGBA8 */)
    val surface = Surface.makeFromBackendRenderTarget(
        ctx, rt, SurfaceOrigin.BOTTOM_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB,
    ) ?: fail("makeFromBackendRenderTarget returned null")

    surface.canvas.drawRect(Rect(0f, 0f, W.toFloat(), H.toFloat()), Paint().apply { color = EXPECT_ARGB })
    ctx.flushAndSubmit(surface, false)

    val got = shim.egl_shim_read_pixel(fbo, W / 2, H / 2)
    val ok = got == EXPECT_ARGB
    println(
        "RESULT egl-skia ok=$ok expected=0x%08X got=0x%08X msg=%s".format(
            EXPECT_ARGB, got,
            if (ok) "shipped skiko rasterised on an EGL context, no fork needed"
            else "DirectContext built but the draw did not land",
        ),
    )
    exitProcess(if (ok) 0 else 1)
}
