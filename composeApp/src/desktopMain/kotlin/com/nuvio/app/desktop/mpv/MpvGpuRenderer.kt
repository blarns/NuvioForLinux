package com.nuvio.app.desktop.mpv

import com.nuvio.app.desktop.egl.Egl
import com.nuvio.app.desktop.egl.Gl
import com.sun.jna.Memory
import com.sun.jna.Pointer
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.SurfaceOrigin
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "NuvioMpvGpuRender"

/**
 * mpv's OpenGL render API, rendering straight into a texture that Compose's own Skia context
 * can sample — so a decoded 4K frame is never read back, copied into the JVM, or re-uploaded.
 *
 * This is the point of the whole libmpv effort. The software path still hardware-*decodes*, but
 * it pays a `vaapi-copy` readback per frame, and that readback alone is what stops it keeping up
 * with 4K (measured: 19.8 render fps against a 24 fps source, unchanged by output size). Here
 * `hwdec=vaapi` stays zero-copy end to end.
 *
 * ⚠ **Every method that touches GL must run on the EDT**, because that is where skiko makes the
 * EGL context current (`AWTRedrawer.inDrawScope` hard-asserts the event thread). That includes
 * [render] and [dispose] — not just creation.
 */
internal class MpvGpuRenderer private constructor(
    private val ctx: Pointer,
    private var width: Int,
    private var height: Int,
) {
    private val mpv = MpvLibrary.INSTANCE

    @Volatile private var disposed = false

    /** The texture mpv renders into, and the FBO that wraps it. */
    private var textureId: Int = 0
    private var fboId: Int = 0

    /**
     * Skia's view of that texture, built ONCE per texture rather than per frame.
     *
     * ⚠ `adoptTextureFrom` means what it says: Skia takes **ownership** and deletes the GL
     * texture when the image is closed. Wrapping the texture fresh every frame and closing the
     * old image therefore destroyed the texture mpv was still rendering into, and Skia then
     * handed the recycled id to the UI — which looked like wrong colours and a corrupted
     * overlay, not like a use-after-free. One image per texture, closed only on resize/teardown.
     */
    private var image: Image? = null
    private var backend: BackendTexture? = null
    private var adopted = false

    private val frameReady = AtomicBoolean(false)

    /**
     * Frames actually rendered. ⚠ Not decoration: a path that presents a third of the frames
     * still advances the timeline in real time, because the clock is the audio — so this is the
     * only thing that distinguishes "cheap" from "not keeping up".
     */
    val framesRendered = java.util.concurrent.atomic.AtomicLong(0)

    /** Invoked when mpv signals a frame, so the surface can ask Compose to redraw. */
    @Volatile var onFrameAvailable: (() -> Unit)? = null

    private val updateCallback = MpvRenderUpdateFn {
        // ⚠ mpv's own thread, and mpv forbids blocking or re-entering the API here.
        frameReady.set(true)
        onFrameAvailable?.invoke()
    }

    init {
        mpv.mpv_render_context_set_update_callback(ctx, updateCallback, null)
    }

    fun hasNewFrame(): Boolean = !disposed && frameReady.get()

    /** The frame already in the texture, without asking mpv for a new one. */
    fun currentImage(): Image? = if (disposed) null else image

    /**
     * Renders the current frame into the internal texture, resizing it when the surface changed.
     * Returns false when there was nothing to draw or the render failed.
     *
     * EDT only, with the EGL context current.
     */
    fun render(ctx: DirectContext, colorType: ColorType, w: Int, h: Int): Image? {
        if (disposed || w <= 0 || h <= 0) return null
        if (!ensureTarget(ctx, colorType, w, h)) return null

        // ADVANCED_CONTROL is set, so mpv expects the client to ask whether a frame is actually
        // pending rather than assuming the update callback means "render now".
        // No new frame is not an error: the previous one is still in the texture and gets
        // redrawn, which is what keeps the picture up during Compose's own repaints.
        val flags = mpv.mpv_render_context_update(this.ctx)
        if (flags and MPV_RENDER_UPDATE_FRAME == 0L) return image
        frameReady.set(false)

        val fbo = MpvOpenGLFbo(fboId, width, height, 0).apply { write() }
        // FLIP_Y stays 0 and the image is adopted as TOP_LEFT: mpv writes the frame into the
        // texture the same way up that Skia reads it, so neither side flips.
        val flip = Memory(4).apply { setInt(0, 0) }
        val params = renderParams(
            MpvRenderParam.OPENGL_FBO to fbo.pointer,
            MpvRenderParam.FLIP_Y to flip,
        )
        val rc = mpv.mpv_render_context_render(this.ctx, params)
        // ⚠ Not defensive: mandatory. `fbo` and `flip` are last READ by the renderParams call
        // above, and the JVM may collect a local the moment it stops being read — it does not
        // wait for the end of the scope. JNA's Memory frees its native block when collected, so
        // without these fences a GC during the render call above can free the very buffers mpv
        // is reading, every frame, 30-60 times a second. The symptom would be a torn frame or a
        // crash inside libmpv, neither of which points back here.
        java.lang.ref.Reference.reachabilityFence(fbo)
        java.lang.ref.Reference.reachabilityFence(flip)
        java.lang.ref.Reference.reachabilityFence(params)
        // ⚠ mpv leaves ITS framebuffer bound. Everything Compose draws afterwards — the whole
        // controls overlay — would go into mpv's video texture instead of the window, which
        // looks like a corrupted UI rather than like a GL error. Rebinding the default
        // framebuffer here is not optional.
        Gl.bindFramebuffer(0)
        if (rc < 0) {
            println("$TAG: render failed rc=$rc")
            return null
        }
        framesRendered.incrementAndGet()
        return image
    }

    /** (Re)creates the texture/FBO/Image set when the surface size changes. EDT only. */
    private fun ensureTarget(ctx: DirectContext, colorType: ColorType, w: Int, h: Int): Boolean {
        if (textureId != 0 && w == width && h == height) return true
        releaseTarget()
        width = w
        height = h

        val tex = Gl.genTexture()
        if (tex == 0) {
            println("$TAG: glGenTextures unavailable — no GPU path")
            return false
        }
        Gl.bindTexture(tex)
        Gl.texImage2D(w, h)
        Gl.texFilterLinear()

        val fbo = Gl.genFramebuffer()
        Gl.bindFramebuffer(fbo)
        Gl.framebufferTexture2D(tex)
        val status = Gl.checkFramebufferStatus()
        // Unbind before returning: leaving OUR framebuffer bound would send Compose's next draw
        // into mpv's texture instead of the window.
        Gl.bindFramebuffer(0)
        Gl.bindTexture(0)

        if (status != Gl.FRAMEBUFFER_COMPLETE) {
            println("$TAG: framebuffer incomplete (status=0x%x)".format(status))
            Gl.deleteFramebuffer(fbo)
            Gl.deleteTexture(tex)
            return false
        }
        textureId = tex
        fboId = fbo
        val bt = BackendTexture.makeGL(w, h, false, tex, Gl.TEXTURE_2D, Gl.RGBA8)
        backend = bt
        image = Image.adoptTextureFrom(ctx, bt, SurfaceOrigin.TOP_LEFT, colorType)
        adopted = true
        println("$TAG: render target ${w}x$h (tex=$tex fbo=$fbo, colorType=$colorType)")
        return true
    }

    private fun releaseTarget() {
        // Order matters: the FBO references the texture, and closing the image is what DELETES
        // that texture (Skia adopted it), so the FBO goes first — and the texture must NOT also
        // be deleted here, which would be a double free.
        Gl.deleteFramebuffer(fboId)
        fboId = 0
        if (adopted) {
            runCatching { image?.close() }
        } else if (textureId != 0) {
            Gl.deleteTexture(textureId)
        }
        runCatching { backend?.close() }
        image = null
        backend = null
        textureId = 0
        adopted = false
    }

    /**
     * Tells mpv the frame reached the screen. With ADVANCED_CONTROL mpv uses this for its own
     * timing, and without it playback drifts away from the audio clock.
     */
    fun reportSwap() {
        if (!disposed) runCatching { mpv.mpv_render_context_report_swap(ctx) }
    }

    /** EDT only — it deletes GL objects. Idempotent. */
    fun dispose() {
        if (disposed) return
        disposed = true
        onFrameAvailable = null
        // Drop the callback first so mpv cannot call into a half-torn-down renderer.
        runCatching { mpv.mpv_render_context_set_update_callback(ctx, null, null) }
        // ⚠ Free the render context BEFORE deleting the texture: mpv may still hold GL state
        // referring to it, and mpv documents that the context must be freed on the thread where
        // the GL context is current.
        runCatching { mpv.mpv_render_context_free(ctx) }
        runCatching { releaseTarget() }
    }

    companion object {
        /**
         * Null when mpv cannot build a GL render context — the caller falls back to the software
         * renderer, and ultimately to VLCJ.
         *
         * EDT only: mpv probes the live GL context during creation.
         */
        fun create(handle: MpvHandle, xDisplay: Pointer?, width: Int, height: Int): MpvGpuRenderer? {
            val raw = handle.rawHandle() ?: return null

            val apiType = Memory(16).apply { setString(0, MPV_RENDER_API_TYPE_OPENGL) }
            // ⚠ The get_proc_address signature takes a ctx argument that eglGetProcAddress does
            // NOT have; handing mpv the raw symbol makes it read the ctx as the name. This
            // adapter shape is mandatory. It is held in a field below so the JNA trampoline is
            // never collected while mpv holds the pointer.
            val initParams = MpvOpenGLInitParams(procAddress, null).apply { write() }
            val advanced = Memory(4).apply { setInt(0, 1) }

            val params = if (xDisplay != null) {
                renderParams(
                    MpvRenderParam.API_TYPE to apiType,
                    MpvRenderParam.OPENGL_INIT_PARAMS to initParams.pointer,
                    MpvRenderParam.ADVANCED_CONTROL to advanced,
                    // Without the X11 display mpv cannot set up VA-API interop and silently
                    // settles for a slower tier — the failure this path exists to avoid.
                    MpvRenderParam.X11_DISPLAY to xDisplay,
                )
            } else {
                renderParams(
                    MpvRenderParam.API_TYPE to apiType,
                    MpvRenderParam.OPENGL_INIT_PARAMS to initParams.pointer,
                    MpvRenderParam.ADVANCED_CONTROL to advanced,
                )
            }

            val res = arrayOfNulls<Pointer>(1)
            val rc = MpvLibrary.INSTANCE.mpv_render_context_create(res, raw, params)
            // Same reachability rule as render(): these buffers are unreachable to the JVM the
            // moment renderParams stops reading them, and JNA frees Memory on collection.
            java.lang.ref.Reference.reachabilityFence(apiType)
            java.lang.ref.Reference.reachabilityFence(initParams)
            java.lang.ref.Reference.reachabilityFence(advanced)
            java.lang.ref.Reference.reachabilityFence(params)
            val rctx = res[0]
            if (rc < 0 || rctx == null) {
                println("$TAG: mpv_render_context_create failed rc=$rc")
                return null
            }
            println("$TAG: GL render context created (x11Display=${xDisplay != null})")
            return MpvGpuRenderer(rctx, width, height)
        }

        /** Held forever on purpose: mpv keeps this function pointer for the context's lifetime. */
        private val procAddress = MpvGetProcAddressFn { _, name -> Egl.lib.eglGetProcAddress(name) }
    }
}

/**
 * Builds mpv's `mpv_render_param[]`, terminated by a zeroed entry.
 *
 * ⚠ `arrayOf(MpvRenderParamStruct(...), ...)` does NOT work: JNA gives each instance its own
 * unrelated allocation, so mpv reads past the first entry into unrelated memory. `Structure.toArray`
 * allocates one contiguous block, which is what the C API requires.
 */
private fun renderParams(vararg entries: Pair<Int, Pointer?>): Pointer {
    val array = MpvRenderParamStruct().toArray(entries.size + 1)
    entries.forEachIndexed { i, (type, data) ->
        (array[i] as MpvRenderParamStruct).apply {
            this.type = type
            this.data = data
            write()
        }
    }
    (array[entries.size] as MpvRenderParamStruct).apply {
        type = MpvRenderParam.INVALID
        data = null
        write()
    }
    return array[0].pointer
}
