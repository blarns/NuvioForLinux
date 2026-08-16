// Runs Compose Desktop's renderer on EGL instead of GLX.
//
// Why: skiko on Linux is GLX-only, and mpv's zero-copy VA-API interop is EGL-only.
// That caps 4K playback at `vaapi-copy` -- 15.3s CPU per 120 frames against 3.9s for
// the zero-copy path on the same stream. Putting Compose's own GL context on EGL is
// what unlocks the fast tier without a separate video window or a forked skiko.
// Full measurements and reasoning in docs/MPV_SWAP_SCOPING.md.
//
// OPT-IN: nothing here runs unless NUVIO_EGL=1. See [EglRenderer.install].
// If anything fails, stock skiko (GLX) is used and the app renders exactly as before.
//
// Three things this has to get right, all of which bit somewhere in the investigation:
//   1. EGLConfig must match the AWT window's ACTUAL visual, or eglCreateWindowSurface
//      returns EGL_BAD_MATCH -- and must grant >= 8 stencil bits, because Skia is
//      asked for 8. eglvisual.c shows both stencil-0 and stencil-8 configs on the
//      same visual, so matching the visual alone is not enough.
//   2. ⚠ All EGL work must happen ON THE EDT -- the opposite of what you would expect.
//      AWTRedrawer.inDrawScope hard-asserts SwingUtilities.isEventDispatchThread(), so
//      skiko renders on the event thread and its frame dispatcher runs on MainUIDispatcher.
//      A dedicated render thread fails with "Method should be called from AWT event
//      dispatch thread". Since an EGL context is current on exactly one thread, that
//      makes the EDT the context's thread -- and mpv's render will have to go there too.
//   4. Redrawer.update(nanoTime) must be called before drawing: SkiaLayer records the
//      frame into a Picture and THAT invokes the render delegate. Without it the
//      context and surface build correctly and the window is simply black, no error.
//   3. Resize must recreate the Skia surface. skiko's own OpenGLContextHandler keeps
//      currentWidth/currentHeight for exactly this; miss it and you either leak a
//      Surface per frame or never resize.

// ⚠ FORWARD-COMPAT: the INVISIBLE_REFERENCE suppression below is how this file reaches
// skiko's `internal` RenderFactory/Redrawer/ContextHandler from Kotlin. The compiler warns
// that this "might compile and work, but the compiler behavior is UNSPECIFIED and WILL NOT
// BE PRESERVED". Treat a Kotlin or Compose Desktop upgrade as able to break this file --
// which is survivable, because every failure path falls back to stock GLX skiko.
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.nuvio.app.desktop.egl

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.GLAssembledInterface
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.SurfaceProps
import org.jetbrains.skia.makeGLWithInterface
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.LayerDrawScope
import org.jetbrains.skiko.RenderFactory
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkiaLayerAnalytics
import org.jetbrains.skiko.SkiaLayerProperties
import org.jetbrains.skiko.context.ContextBasedContextHandler
import org.jetbrains.skiko.redrawer.AWTRedrawer
import org.jetbrains.skiko.redrawer.Redrawer
import javax.swing.SwingUtilities

// ---------------------------------------------------------------------------
// EGL, via JNA. JnaEglSpike proved a JNA callback works as Skia's proc-address
// function, so no native shim is needed anywhere in this design.
// ---------------------------------------------------------------------------

private const val EGL_NO_CONTEXT = 0L
private const val EGL_PLATFORM_X11_KHR = 0x31D5
private const val EGL_OPENGL_API = 0x30A2
private const val EGL_WINDOW_BIT = 0x0004
private const val EGL_OPENGL_BIT = 0x0008
private const val EGL_SURFACE_TYPE = 0x3033
private const val EGL_RENDERABLE_TYPE = 0x3040
private const val EGL_RED_SIZE = 0x3024
private const val EGL_GREEN_SIZE = 0x3023
private const val EGL_BLUE_SIZE = 0x3022
private const val EGL_ALPHA_SIZE = 0x3021
private const val EGL_DEPTH_SIZE = 0x3025
private const val EGL_STENCIL_SIZE = 0x3026
private const val EGL_NATIVE_VISUAL_ID = 0x302E
private const val EGL_NONE = 0x3038
private const val EGL_CONTEXT_MAJOR_VERSION = 0x3098
private const val EGL_CONTEXT_MINOR_VERSION = 0x30FB

internal interface EglLib : Library {
    fun eglGetProcAddress(name: String): Pointer?
    fun eglGetError(): Int
    fun eglGetDisplay(nativeDisplay: Pointer?): Pointer?
    fun eglInitialize(dpy: Pointer, major: IntArray?, minor: IntArray?): Boolean
    fun eglBindAPI(api: Int): Boolean
    fun eglChooseConfig(dpy: Pointer, attribs: IntArray, configs: Array<Pointer?>?, size: Int, num: IntArray): Boolean
    fun eglGetConfigAttrib(dpy: Pointer, config: Pointer, attribute: Int, value: IntArray): Boolean
    fun eglCreateContext(dpy: Pointer, config: Pointer, share: Pointer?, attribs: IntArray): Pointer?
    fun eglCreateWindowSurface(dpy: Pointer, config: Pointer, win: Long, attribs: IntArray?): Pointer?
    fun eglMakeCurrent(dpy: Pointer, draw: Pointer?, read: Pointer?, ctx: Pointer?): Boolean
    fun eglSwapBuffers(dpy: Pointer, surface: Pointer): Boolean
    fun eglSwapInterval(dpy: Pointer, interval: Int): Boolean
    fun eglDestroySurface(dpy: Pointer, surface: Pointer): Boolean
    fun eglDestroyContext(dpy: Pointer, ctx: Pointer): Boolean
}

/**
 * XVisualIDFromVisual only. XGetWindowAttributes goes through JNA-platform's real
 * XWindowAttributes struct instead of a hand-computed offset -- the first attempt
 * guessed the `visual` field at 32 bytes when it is at 24, and read garbage into
 * XVisualIDFromVisual, which segfaults the JVM rather than failing cleanly.
 */
internal interface X11Lib : Library {
    fun XVisualIDFromVisual(visual: Pointer): Long
}

internal object Egl {
    val lib: EglLib = Native.load("EGL", EglLib::class.java)
    val x11: X11Lib = Native.load("X11", X11Lib::class.java)

    /**
     * Skia's assembled-interface getter: GrGLFuncPtr(*)(void* ctx, const char* name).
     * Held in a field so the JNA trampoline is never collected while Skia holds it.
     */
    private val getProcCallback = object : GetProc {
        override fun invoke(ctx: Pointer?, name: String): Pointer? = lib.eglGetProcAddress(name)
    }
    val getProcFnPtr: Long = Pointer.nativeValue(CallbackReference.getFunctionPointer(getProcCallback))

    interface GetProc : Callback {
        fun invoke(ctx: Pointer?, name: String): Pointer?
    }

    /**
     * eglGetPlatformDisplayEXT is an EGL *extension*: it is not an exported symbol, so
     * JNA cannot bind it directly (UnsatisfiedLinkError) -- it has to be fetched through
     * eglGetProcAddress and called as a raw function pointer. Falls back to the legacy
     * eglGetDisplay, which on Mesa resolves an X11 Display* to the same platform display.
     */
    fun platformDisplay(xDisplay: Pointer): Pointer? {
        val proc = lib.eglGetProcAddress("eglGetPlatformDisplayEXT")
        if (proc != null && Pointer.nativeValue(proc) != 0L) {
            val fn = com.sun.jna.Function.getFunction(proc)
            val dpy = fn.invokePointer(arrayOf<Any?>(EGL_PLATFORM_X11_KHR, xDisplay, null))
            if (dpy != null && Pointer.nativeValue(dpy) != 0L) return dpy
        }
        return lib.eglGetDisplay(xDisplay)
    }

    fun windowVisualId(display: Pointer, window: Long): Long? {
        val attrs = com.sun.jna.platform.unix.X11.XWindowAttributes()
        val dpy = com.sun.jna.platform.unix.X11.Display().apply { pointer = display }
        val win = com.sun.jna.platform.unix.X11.Window(window)
        if (com.sun.jna.platform.unix.X11.INSTANCE.XGetWindowAttributes(dpy, win, attrs) == 0) return null
        val visual = attrs.visual?.pointer ?: return null
        if (Pointer.nativeValue(visual) == 0L) return null
        return x11.XVisualIDFromVisual(visual)
    }
}

/**
 * skiko already wraps JAWT (lock, read X11 display+window, unlock) -- worth reusing
 * rather than hand-rolling JNI. But HardwareLayer and LinuxDrawingSurface are Kotlin
 * `internal` in a way @Suppress does not cover for *receiver* resolution, so this goes
 * through reflection to avoid naming them at all.
 *
 * The JAWT lock must be held briefly and always released, hence the try/finally.
 */
internal object Jawt {
    private val helper = Class.forName("org.jetbrains.skiko.AWTLinuxDrawingSurfaceKt")
    private val lockFn = helper.declaredMethods.first { it.name == "lockLinuxDrawingSurface" && it.parameterCount == 1 }
        .apply { isAccessible = true }
    private val unlockFn = helper.declaredMethods.first { it.name == "unlockLinuxDrawingSurface" }
        .apply { isAccessible = true }
    private val surfaceClass = Class.forName("org.jetbrains.skiko.LinuxDrawingSurface")
    private val getDisplay = surfaceClass.getMethod("getDisplay").apply { isAccessible = true }
    private val getWindow = surfaceClass.getMethod("getWindow").apply { isAccessible = true }

    /** Calls [body] with (x11Display, x11Window) while the JAWT lock is held. */
    fun <T> withDrawingSurface(backedLayer: Any, body: (Long, Long) -> T): T {
        val ds = lockFn.invoke(null, backedLayer)
        try {
            return body(getDisplay.invoke(ds) as Long, getWindow.invoke(ds) as Long)
        } finally {
            unlockFn.invoke(null, ds)
        }
    }
}

/**
 * Per-layer EGL display/config/context/surface. Lives entirely on the render thread.
 */
internal class EglDevice private constructor(
    val display: Pointer,
    val config: Pointer,
    val context: Pointer,
    val surface: Pointer,
    val xDisplay: Pointer,
    val stencilBits: Int,
    val vendorInfo: String,
) {
    fun makeCurrent(): Boolean = Egl.lib.eglMakeCurrent(display, surface, surface, context)
    fun swapBuffers(): Boolean = Egl.lib.eglSwapBuffers(display, surface)

    fun dispose() {
        Egl.lib.eglMakeCurrent(display, null, null, null)
        Egl.lib.eglDestroySurface(display, surface)
        Egl.lib.eglDestroyContext(display, context)
    }

    companion object {
        /** Non-null failure reason, or null on success (device returned via [out]). */
        fun create(xDisplay: Pointer, window: Long, wantAlpha: Boolean): Pair<EglDevice?, String?> {
            val dpy = Egl.platformDisplay(xDisplay)
                ?: return null to "no EGL display for the X11 display"
            if (Pointer.nativeValue(dpy) == 0L) return null to "no EGL display for the X11 display"
            if (!Egl.lib.eglInitialize(dpy, null, null)) return null to "eglInitialize failed (0x%x)".format(Egl.lib.eglGetError())
            // Desktop GL, not GLES -- this is what GLX would have given Skia.
            if (!Egl.lib.eglBindAPI(EGL_OPENGL_API)) return null to "eglBindAPI(OPENGL) failed"

            val wantVisual = Egl.windowVisualId(xDisplay, window)
                ?: return null to "could not read the window's visual id"

            val attribs = intArrayOf(
                EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
                EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
                EGL_STENCIL_SIZE, 8,
                EGL_NONE,
            )
            val configs = arrayOfNulls<Pointer>(128)
            val num = IntArray(1)
            if (!Egl.lib.eglChooseConfig(dpy, attribs, configs, configs.size, num) || num[0] < 1) {
                return null to "no window-capable OpenGL EGLConfig with 8 stencil bits"
            }

            // Matching the window's visual is mandatory (else EGL_BAD_MATCH), and the
            // config must actually GRANT the stencil bits Skia will be told it has.
            var chosen: Pointer? = null
            var chosenStencil = 0
            for (i in 0 until num[0]) {
                val c = configs[i] ?: continue
                val v = IntArray(1)
                if (!Egl.lib.eglGetConfigAttrib(dpy, c, EGL_NATIVE_VISUAL_ID, v)) continue
                if (v[0].toLong() != wantVisual) continue
                val s = IntArray(1)
                Egl.lib.eglGetConfigAttrib(dpy, c, EGL_STENCIL_SIZE, s)
                if (s[0] < 8) continue
                if (wantAlpha) {
                    val a = IntArray(1)
                    Egl.lib.eglGetConfigAttrib(dpy, c, EGL_ALPHA_SIZE, a)
                    if (a[0] < 8) continue
                }
                chosen = c
                chosenStencil = s[0]
                break
            }
            if (chosen == null) {
                return null to "no EGLConfig matches the window visual 0x%x with 8 stencil bits".format(wantVisual)
            }

            val ctxAttribs = intArrayOf(EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 3, EGL_NONE)
            val ctx = Egl.lib.eglCreateContext(dpy, chosen, null, ctxAttribs)
            if (ctx == null || Pointer.nativeValue(ctx) == EGL_NO_CONTEXT) {
                return null to "eglCreateContext failed (0x%x)".format(Egl.lib.eglGetError())
            }
            val surf = Egl.lib.eglCreateWindowSurface(dpy, chosen, window, null)
            if (surf == null || Pointer.nativeValue(surf) == 0L) {
                Egl.lib.eglDestroyContext(dpy, ctx)
                return null to "eglCreateWindowSurface failed (0x%x)".format(Egl.lib.eglGetError())
            }
            val dev = EglDevice(dpy, chosen, ctx, surf, xDisplay, chosenStencil, "EGL/OpenGL visual=0x%x stencil=%d".format(wantVisual, chosenStencil))
            return dev to null
        }
    }
}

// ---------------------------------------------------------------------------
// Skia side
// ---------------------------------------------------------------------------

/**
 * skiko's own OpenGLContextHandler is `final`, and its makeContext() calls
 * DirectContext.makeGL() -- which returns null on EGL because Skia's Linux native
 * interface starts with `if (!glXGetCurrentContext()) return nullptr;`. So this
 * reimplements it on top of makeGLWithInterface, including the resize handling
 * that lives in initCanvas.
 */
internal class EglContextHandler(
    private val layer: SkiaLayer,
    private val stencilBits: () -> Int,
) : ContextBasedContextHandler(layer, "OpenGL-EGL") {

    private var currentWidth = 0
    private var currentHeight = 0

    override fun makeContext(): DirectContext {
        val iface = GLAssembledInterface.createFromNativePointers(0L, Egl.getProcFnPtr)
        return DirectContext.makeGLWithInterface(iface)
    }

    private fun isSizeChanged(width: Int, height: Int): Boolean {
        if (width != currentWidth || height != currentHeight) {
            currentWidth = width
            currentHeight = height
            return true
        }
        return false
    }

    override fun LayerDrawScope.initCanvas() {
        val scaledW = scaledLayerWidth.coerceAtLeast(1)
        val scaledH = scaledLayerHeight.coerceAtLeast(1)

        if (isSizeChanged(scaledW, scaledH) || surface == null) {
            disposeCanvas()
            // fbId 0 = the window's default framebuffer, which is what the EGL window
            // surface binds. samples 0, stencil from the config we actually got.
            renderTarget = BackendRenderTarget.makeGL(scaledW, scaledH, 0, stencilBits(), 0, FBO_RGBA8)
            surface = Surface.makeFromBackendRenderTarget(
                context!!,
                renderTarget!!,
                SurfaceOrigin.BOTTOM_LEFT,
                SurfaceColorFormat.RGBA_8888,
                ColorSpace.sRGB,
                SurfaceProps(pixelGeometry = pixelGeometry),
            ) ?: throw IllegalStateException("Surface.makeFromBackendRenderTarget returned null (EGL)")
        }
        canvas = surface!!.canvas
    }

    fun hasContext(): Boolean = context != null

    override fun rendererInfo(): String = "EGL/OpenGL (${super.rendererInfo()})"

    private companion object {
        const val FBO_RGBA8 = 0x8058
    }
}

/**
 * Drives the EGL device through skiko's Redrawer lifecycle.
 *
 * Everything runs on the EDT because skiko requires it (see the header note). The EGL
 * context is therefore current on the EDT, and anything else that wants to use it --
 * notably mpv's render context -- has to run there as well.
 */
internal class EglRedrawer(
    private val layer: SkiaLayer,
    analytics: SkiaLayerAnalytics,
    private val properties: SkiaLayerProperties,
) : AWTRedrawer(layer, analytics, GraphicsApi.OPENGL) {

    private var device: EglDevice? = null
    private var initFailure: String? = null
    private val contextHandler = EglContextHandler(layer) { device?.stencilBits ?: 8 }

    private val DEBUG = System.getenv("NUVIO_EGL_DEBUG") == "1"

    @Volatile private var disposed = false
    @Volatile private var framePending = false

    override val renderInfo: String get() = device?.vendorInfo ?: "EGL (uninitialised)"

    // ⚠ The supertype parameter is `throttledToVsync`, NOT "force" -- it asks whether this
    // frame should be paced to vsync, which is already handled by eglSwapInterval below.
    // Treating it as a force flag (the obvious misreading) would bypass frame coalescing
    // on exactly the calls that want *less* frequent rendering.
    override fun needRender(throttledToVsync: Boolean) {
        if (disposed) return
        // Coalesce: many invalidations within one event turn into a single frame.
        if (framePending) return
        framePending = true
        SwingUtilities.invokeLater { drawFrame() }
    }

    override fun renderImmediately() {
        if (disposed) return
        framePending = true
        // Callers use this for synchronous repaint (resize, expose), so it must not
        // return before the frame is up.
        if (SwingUtilities.isEventDispatchThread()) drawFrame()
        else SwingUtilities.invokeAndWait { drawFrame() }
    }

    private fun ensureDevice(): Boolean {
        device?.let { return true }
        initFailure?.let { return false }

        // skiko already does the JAWT dance; reuse it rather than hand-rolling JNI.
        // The lock must be held only briefly and always balanced.
        // Declared plainly rather than with `?: run { return }` -- the elvis form makes
        // Kotlin compute a common supertype for an @Suppress-visible internal class and
        // it silently emits `checkcast error/NonExistentClass` instead of failing to build.
        val backed = backedLayerOf(layer)
        if (backed == null) {
            initFailure = "could not reach SkiaLayer.backedLayer"
            return false
        }
        Jawt.withDrawingSurface(backed) { displayPtr, window ->
            if (displayPtr == 0L || window == 0L) {
                initFailure = "JAWT gave no X11 display/window yet"
            } else {
                val (dev, err) = EglDevice.create(Pointer(displayPtr), window, wantAlpha = layer.transparency)
                if (dev == null) initFailure = err ?: "unknown EGL failure" else device = dev
            }
        }
        if (device == null) return false

        val dev = device!!
        if (!dev.makeCurrent()) {
            initFailure = "eglMakeCurrent failed on the render thread"
            dev.dispose()
            device = null
            return false
        }
        Egl.lib.eglSwapInterval(dev.display, if (properties.isVsyncEnabled) 1 else 0)
        // AWTRedrawer.inDrawScope requires the analytics lifecycle to have been walked:
        // it hard-asserts that a device was chosen before any frame is drawn.
        onDeviceChosen(dev.vendorInfo)
        // Logged unconditionally: "installed" only means the factory was replaced. Every
        // failure here falls back silently and correctly to GLX, which looks identical on
        // screen, so this is the only line that proves EGL is the path actually in use.
        EglRenderer.noteActive(dev.vendorInfo)
        return true
    }

    private fun drawFrame() {
        if (DEBUG) System.err.println("[nuvio-egl] drawFrame pending=$framePending disposed=$disposed device=${device != null} fail=$initFailure")
        if (disposed || !framePending) return
        framePending = false
        if (!ensureDevice()) return
        val dev = device ?: return
        val current = dev.makeCurrent()
        if (DEBUG) System.err.println("[nuvio-egl] makeCurrent=$current err=0x%x".format(Egl.lib.eglGetError()))
        if (!current) return

        try {
            // SkiaLayer records the frame into a Picture, and that is what invokes the
            // SkikoRenderDelegate. Without update() the context and surface are built
            // correctly and nothing is ever drawn -- no error, just a black window.
            update(System.nanoTime())
            // inDrawScope supplies LayerDrawScope as the receiver; draw() picks it up from there.
            inDrawScope { contextHandler.draw() }
            if (DEBUG) System.err.println("[nuvio-egl] inDrawScope returned, ctx=${contextHandler.hasContext()}")
            dev.swapBuffers()
        } catch (t: Throwable) {
            System.err.println("[nuvio-egl] frame failed: ${t::class.qualifiedName}: ${t.message}")
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        // GL teardown has to happen where the context is current, i.e. the EDT.
        val tearDown = Runnable {
            try {
                contextHandler.dispose()
                device?.dispose()
            } catch (t: Throwable) {
                System.err.println("[nuvio-egl] teardown: ${t.message}")
            } finally {
                device = null
            }
        }
        if (SwingUtilities.isEventDispatchThread()) tearDown.run() else SwingUtilities.invokeAndWait(tearDown)
        super.dispose()
    }

    companion object {
        /**
         * SkiaLayer.backedLayer is the HardwareLayer that skiko's JAWT helper needs.
         * Typed as Any? deliberately -- see [Jawt] for why the class is not named.
         */
        fun backedLayerOf(layer: SkiaLayer): Any? = try {
            SkiaLayer::class.java.getDeclaredField("backedLayer").let {
                it.isAccessible = true
                it.get(layer)
            }
        } catch (t: Throwable) {
            null
        }
    }
}

/** Returns an EGL redrawer for OPENGL; anything else falls through to stock skiko. */
internal class EglRenderFactory(private val delegate: RenderFactory) : RenderFactory {
    override fun createRedrawer(
        layer: SkiaLayer,
        renderApi: GraphicsApi,
        analytics: SkiaLayerAnalytics,
        properties: SkiaLayerProperties,
    ): Redrawer {
        if (renderApi != GraphicsApi.OPENGL) return delegate.createRedrawer(layer, renderApi, analytics, properties)
        return try {
            EglRedrawer(layer, analytics, properties)
        } catch (t: Throwable) {
            System.err.println("[nuvio-egl] falling back to stock skiko: ${t.message}")
            delegate.createRedrawer(layer, renderApi, analytics, properties)
        }
    }
}


/**
 * Installs the EGL renderer process-wide.
 *
 * Must be called before anything constructs a `SkiaLayer` -- i.e. before `application {}`
 * in main() -- because Compose builds and realises its layer internally and there is no
 * hook once that has happened.
 *
 * Opt-in via NUVIO_EGL=1 while this is being proven on real hardware. Every failure path
 * leaves `RenderFactory.Companion.Default` untouched, so the app falls back to stock GLX
 * skiko and renders exactly as it does today.
 */
object EglRenderer {

    /** Human-readable outcome, for logging and for Settings→About later. */
    @Volatile
    var status: String = "not attempted"
        private set

    /** Set once the first EGL device is actually created and current. */
    @Volatile
    var activeRenderer: String? = null
        private set

    internal fun noteActive(info: String) {
        if (activeRenderer == null) {
            activeRenderer = info
            println("[nuvio-egl] ACTIVE — rendering through EGL: $info")
        }
    }

    val isEnabled: Boolean get() = System.getenv("NUVIO_EGL") == "1"

    fun install() {
        if (!isEnabled) {
            status = "disabled (set NUVIO_EGL=1 to enable)"
            return
        }
        // Deliberate escape hatch: exercises the GLX fallback on demand, so that path is
        // testable rather than only ever running on machines we cannot reproduce.
        if (System.getenv("NUVIO_EGL_FORCE_FAIL") == "1") {
            status = "forced failure (NUVIO_EGL_FORCE_FAIL=1) -- using stock GLX skiko"
            log()
            return
        }
        status = try {
            val companion = Class.forName("org.jetbrains.skiko.RenderFactory\$Companion")
            val field = companion.getDeclaredField("Default")
            field.isAccessible = true
            val original = field.get(null) as RenderFactory

            // Default is `private static final`, so Field.set throws. The app already ships
            // --add-opens=java.base/sun.misc and the jdk.unsupported module because VLCJ
            // needs Unsafe for its native video buffers, so this needs no packaging change.
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

            if (field.get(null) is EglRenderFactory) "installed" else "write did not stick -- using stock GLX skiko"
        } catch (t: Throwable) {
            "failed (${t::class.simpleName}: ${t.message}) -- using stock GLX skiko"
        }
        log()
    }

    private fun log() = println("[nuvio-egl] $status")
}
