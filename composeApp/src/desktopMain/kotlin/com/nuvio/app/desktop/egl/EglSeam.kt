package com.nuvio.app.desktop.egl

import com.sun.jna.Function
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import org.jetbrains.skia.DirectContext

/**
 * The three things anything else needs in order to share Compose's GL context: the Skia
 * [DirectContext], the X11 display it was created on, and a way to call GL.
 *
 * Compose owns its renderer and exposes none of this, and a second context is not an option —
 * the point of the mpv GPU path is that the decoded frame is never copied, which requires
 * rendering into a texture the *same* context can sample. So [EglRedrawer] publishes what it
 * builds, here, rather than anyone reaching back into skiko internals a second time.
 *
 * Everything published here is only valid while the EGL renderer is live (`NUVIO_EGL=1` and no
 * fallback). [EglRenderer.isActive] is the gate.
 */
internal object EglSeam {

    /** Compose's own Skia context. Null until the first frame has been drawn through EGL. */
    @Volatile
    var directContext: DirectContext? = null
        private set

    /** The X11 `Display*` the EGL context was created on; mpv's VA-API interop needs it. */
    @Volatile
    var xDisplay: Pointer? = null
        private set

    internal fun publishContext(context: DirectContext) {
        directContext = context
    }

    internal fun publishXDisplay(display: Pointer) {
        xDisplay = display
    }

    /** Dropped on teardown so a stale context can never be handed to a later session. */
    internal fun clear() {
        directContext = null
        xDisplay = null
    }
}

/**
 * The handful of GL entry points the mpv GPU path needs, resolved through `eglGetProcAddress`.
 *
 * ⚠ Deliberately NOT `Native.load("GL", …)`. Under EGL the correct symbols are the ones the
 * current context provides, and dlopening libGL by name is how a process ends up with GLX's
 * dispatch table instead — the exact confusion this whole EGL effort exists to avoid. Skia is
 * handed the same `eglGetProcAddress`, so both resolve identically.
 */
internal object Gl {
    const val TEXTURE_2D = 0x0DE1
    const val RGBA8 = 0x8058
    const val RGBA = 0x1908
    const val UNSIGNED_BYTE = 0x1401
    const val TEXTURE_MIN_FILTER = 0x2801
    const val TEXTURE_MAG_FILTER = 0x2800
    const val LINEAR = 0x2601
    const val FRAMEBUFFER = 0x8D40
    const val COLOR_ATTACHMENT0 = 0x8CE0
    const val FRAMEBUFFER_COMPLETE = 0x8CD5

    private val cache = HashMap<String, Function>()

    /** Null when the driver does not provide [name] — callers treat that as "no GPU path". */
    private fun fn(name: String): Function? = synchronized(cache) {
        cache[name] ?: run {
            val p = Egl.lib.eglGetProcAddress(name)
            if (p == null || Pointer.nativeValue(p) == 0L) return null
            Function.getFunction(p).also { cache[name] = it }
        }
    }

    fun genTexture(): Int {
        val out = IntByReference()
        fn("glGenTextures")?.invokeVoid(arrayOf<Any?>(1, out)) ?: return 0
        return out.value
    }

    fun genFramebuffer(): Int {
        val out = IntByReference()
        fn("glGenFramebuffers")?.invokeVoid(arrayOf<Any?>(1, out)) ?: return 0
        return out.value
    }

    fun bindTexture(id: Int) = fn("glBindTexture")?.invokeVoid(arrayOf<Any?>(TEXTURE_2D, id))

    fun bindFramebuffer(id: Int) = fn("glBindFramebuffer")?.invokeVoid(arrayOf<Any?>(FRAMEBUFFER, id))

    /** Allocates an empty RGBA8 texture image of [w]x[h] for the bound texture. */
    fun texImage2D(w: Int, h: Int) = fn("glTexImage2D")?.invokeVoid(
        arrayOf<Any?>(TEXTURE_2D, 0, RGBA8, w, h, 0, RGBA, UNSIGNED_BYTE, null),
    )

    fun texFilterLinear() {
        fn("glTexParameteri")?.invokeVoid(arrayOf<Any?>(TEXTURE_2D, TEXTURE_MIN_FILTER, LINEAR))
        fn("glTexParameteri")?.invokeVoid(arrayOf<Any?>(TEXTURE_2D, TEXTURE_MAG_FILTER, LINEAR))
    }

    fun framebufferTexture2D(texture: Int) = fn("glFramebufferTexture2D")
        ?.invokeVoid(arrayOf<Any?>(FRAMEBUFFER, COLOR_ATTACHMENT0, TEXTURE_2D, texture, 0))

    fun checkFramebufferStatus(): Int =
        fn("glCheckFramebufferStatus")?.invokeInt(arrayOf<Any?>(FRAMEBUFFER)) ?: 0

    fun deleteTexture(id: Int) {
        if (id != 0) fn("glDeleteTextures")?.invokeVoid(arrayOf<Any?>(1, IntByReference(id)))
    }

    fun deleteFramebuffer(id: Int) {
        if (id != 0) fn("glDeleteFramebuffers")?.invokeVoid(arrayOf<Any?>(1, IntByReference(id)))
    }

    fun getError(): Int = fn("glGetError")?.invokeInt(emptyArray()) ?: 0
}
