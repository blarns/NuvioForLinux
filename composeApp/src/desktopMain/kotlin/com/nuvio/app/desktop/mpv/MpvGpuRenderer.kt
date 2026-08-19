package com.nuvio.app.desktop.mpv

import com.nuvio.app.desktop.egl.Egl
import com.nuvio.app.desktop.egl.Gl
import com.sun.jna.Memory
import com.sun.jna.Pointer
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

    /** The texture mpv renders into, and the FBO that wraps it. Both are ours to delete. */
    var textureId: Int = 0
        private set
    private var fboId: Int = 0

    private val frameReady = AtomicBoolean(false)

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

    /**
     * Renders the current frame into the internal texture, resizing it when the surface changed.
     * Returns false when there was nothing to draw or the render failed.
     *
     * EDT only, with the EGL context current.
     */
    fun render(w: Int, h: Int): Boolean {
        if (disposed || w <= 0 || h <= 0) return false
        if (!ensureTarget(w, h)) return false

        // ADVANCED_CONTROL is set, so mpv expects the client to ask whether a frame is actually
        // pending rather than assuming the update callback means "render now".
        val flags = mpv.mpv_render_context_update(ctx)
        if (flags and MPV_RENDER_UPDATE_FRAME == 0L) return false
        frameReady.set(false)

        val fbo = MpvOpenGLFbo(fboId, width, height, 0).apply { write() }
        // FLIP_Y stays 0 and the image is adopted as TOP_LEFT: mpv writes the frame into the
        // texture the same way up that Skia reads it, so neither side flips.
        val flip = Memory(4).apply { setInt(0, 0) }
        val params = renderParams(
            MpvRenderParam.OPENGL_FBO to fbo.pointer,
            MpvRenderParam.FLIP_Y to flip,
        )
        val rc = mpv.mpv_render_context_render(ctx, params)
        if (rc < 0) {
            println("$TAG: render failed rc=$rc")
            return false
        }
        return true
    }

    /** (Re)creates the texture/FBO pair when the surface size changes. EDT only. */
    private fun ensureTarget(w: Int, h: Int): Boolean {
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
        println("$TAG: render target ${w}x$h (tex=$tex fbo=$fbo)")
        return true
    }

    private fun releaseTarget() {
        Gl.deleteFramebuffer(fboId)
        Gl.deleteTexture(textureId)
        fboId = 0
        textureId = 0
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
