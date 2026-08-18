package com.nuvio.app.desktop.mpv

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer

private const val TAG = "NuvioMpvSwRender"

/**
 * mpv's software render API: mpv rasterises each frame into a caller-owned buffer, which then
 * feeds the existing frame → `ImageBitmap` → Canvas path almost unchanged.
 *
 * This is deliberately the FIRST of the two render paths, even though the GPU one is the
 * reason for the whole libmpv effort:
 *  - it is the mandatory fallback when EGL or the renderer injection is unavailable, so it
 *    has to exist regardless;
 *  - it needs **no GL context**, so none of the EDT constraints that govern the GPU path
 *    apply here, and the controller, snapshot side effects and packaging can all be proven
 *    without touching the Skia seam;
 *  - it still hardware-*decodes* (`vaapi-copy`), which is already well ahead of the VLCJ
 *    path, where libVLC forces software decoding outright.
 */
internal class MpvSoftwareRenderer private constructor(
    private val ctx: Pointer,
    private var width: Int,
    private var height: Int,
) {
    private val mpv = MpvLibrary.INSTANCE

    @Volatile private var disposed = false

    /**
     * Native destination for mpv's pixels. Kept as JNA [Memory] rather than a Kotlin array
     * because mpv writes it directly; it is copied into a ByteArray only once per delivered
     * frame.
     */
    private var buffer: Memory = Memory((width.toLong() * height * 4).coerceAtLeast(4))

    /** Set from mpv's own thread when a new frame is ready. */
    private val frameReady = java.util.concurrent.atomic.AtomicBoolean(false)

    private val updateCallback = MpvRenderUpdateFn {
        // ⚠ mpv calls this on ITS thread and forbids blocking or re-entering the API here,
        // so this only ever sets a flag; the render itself happens on the caller's thread.
        frameReady.set(true)
    }

    init {
        mpv.mpv_render_context_set_update_callback(ctx, updateCallback, null)
    }

    /** True when mpv has signalled a new frame since the last [render]. */
    fun hasNewFrame(): Boolean = !disposed && frameReady.get()

    /** Resize the destination buffer. Cheap no-op when the size is unchanged. */
    fun resize(w: Int, h: Int) {
        if (disposed || w <= 0 || h <= 0) return
        if (w == width && h == height) return
        width = w
        height = h
        buffer = Memory(w.toLong() * h * 4)
        println("$TAG: render size -> ${w}x$h")
    }

    /**
     * Renders the current frame into [out], which must be `width * height * 4` bytes of BGRA.
     * Returns false when nothing was rendered.
     */
    fun render(out: ByteArray): Boolean {
        if (disposed) return false
        val expected = width * height * 4
        if (out.size < expected) return false
        // Clearing the flag BEFORE rendering means a frame arriving mid-render is not lost —
        // it just leaves the flag set for the next call.
        frameReady.set(false)

        // ⚠ Every one of these has to outlive the call, so they are locals held by this
        // frame rather than temporaries inside the params array.
        val size = Memory(8).apply { setInt(0, width); setInt(4, height) }
        // ⚠ SW_STRIDE is a size_t*, i.e. 64-bit on x86_64. Passing a 32-bit int here yields
        // garbage strides, which shows up as a sheared or corrupted picture rather than an error.
        // mpv would prefer stride and pointer to be 64-byte multiples for SIMD, but only
        // pixel-size alignment is REQUIRED; a non-multiple costs speed, not correctness. Keeping
        // stride at exactly width*4 means the buffer matches what the frame pipeline already
        // expects, with no padding to strip back out.
        val stride = Memory(Native.SIZE_T_SIZE.toLong()).apply {
            if (Native.SIZE_T_SIZE == 8) setLong(0, width.toLong() * 4) else setInt(0, width * 4)
        }
        // ⚠ mpv accepts only "rgb0"/"bgr0"/"0bgr"/"0rgb" here -- there is no "bgra0". "bgr0"
        // is b,g,r at ascending addresses with a garbage 4th byte, which is byte-identical to
        // Skia's BGRA_8888 read as OPAQUE, so the existing frame pipeline needs no change.
        val format = Memory(8).apply { setString(0, "bgr0") }

        val params = renderParams(
            MpvRenderParam.SW_SIZE to size,
            MpvRenderParam.SW_FORMAT to format,
            MpvRenderParam.SW_STRIDE to stride,
            // ⚠ SW_POINTER's data IS the pixel buffer, not a pointer to it. Wrapping it in
            // another indirection makes mpv write over whatever that cell points at.
            MpvRenderParam.SW_POINTER to buffer,
        )

        val rc = mpv.mpv_render_context_render(ctx, params)
        if (rc < 0) {
            println("$TAG: render failed rc=$rc")
            return false
        }
        buffer.read(0, out, 0, expected)
        return true
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        // Drop the callback first: mpv must not call back into a half-torn-down renderer.
        runCatching { mpv.mpv_render_context_set_update_callback(ctx, null, null) }
        runCatching { mpv.mpv_render_context_free(ctx) }
    }

    companion object {
        /** Returns null when the render context cannot be created; callers fall back to VLCJ. */
        fun create(handle: MpvHandle, width: Int, height: Int): MpvSoftwareRenderer? {
            val raw = handle.rawHandle() ?: return null
            val apiType = Memory(8).apply { setString(0, MPV_RENDER_API_TYPE_SW) }
            val params = renderParams(MpvRenderParam.API_TYPE to apiType)

            val res = arrayOfNulls<Pointer>(1)
            val rc = MpvLibrary.INSTANCE.mpv_render_context_create(res, raw, params)
            val ctx = res[0]
            if (rc < 0 || ctx == null) {
                println("$TAG: mpv_render_context_create failed rc=$rc")
                return null
            }
            println("$TAG: software render context created (${width}x$height)")
            return MpvSoftwareRenderer(ctx, width, height)
        }
    }
}

/**
 * Builds mpv's `mpv_render_param[]`, terminated by a zeroed entry.
 *
 * ⚠ `arrayOf(MpvRenderParamStruct(...), ...)` does NOT work: JNA gives each instance its own
 * unrelated allocation, so mpv reads past the first entry into unrelated memory — usually
 * seeing an immediate terminator and therefore no parameters at all. `Structure.toArray`
 * allocates one contiguous block, which is what the C API requires.
 */
private fun renderParams(vararg entries: Pair<Int, Pointer?>): Pointer {
    val n = entries.size + 1   // + the terminator
    val array = MpvRenderParamStruct().toArray(n)
    entries.forEachIndexed { i, (type, data) ->
        (array[i] as MpvRenderParamStruct).apply {
            this.type = type
            this.data = data
            write()
        }
    }
    // MPV_RENDER_PARAM_INVALID (0) with a null pointer ends the list.
    (array[entries.size] as MpvRenderParamStruct).apply {
        type = MpvRenderParam.INVALID
        data = null
        write()
    }
    return array[0].pointer
}
