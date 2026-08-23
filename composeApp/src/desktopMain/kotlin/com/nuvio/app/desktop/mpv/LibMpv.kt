package com.nuvio.app.desktop.mpv

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.PointerType
import com.sun.jna.Structure

/**
 * JNA binding for libmpv (`libmpv.so.2`, API 2.x).
 *
 * Only the calls this app actually makes are bound. VLCJ already pulls JNA in, so this adds
 * no new dependency, and — proven by `scripts/spikes/JnaEglSpike.kt` — JNA callbacks work as
 * real native function pointers, so **no hand-written native artifact is needed anywhere**:
 * the GL proc-address function mpv wants can be a Kotlin lambda.
 *
 * See `docs/MPV_SWAP_SCOPING.md` (revisions 2026-08-16b/16c) for why this exists at all: the
 * shipping VLCJ path cannot hardware-decode, because libVLC silently forces `avcodec-hw=none`
 * whenever the video output is the buffer-callback surface.
 */
internal interface MpvLibrary : Library {

    // --- lifecycle -----------------------------------------------------------------------
    fun mpv_client_api_version(): Int
    fun mpv_error_string(error: Int): String?
    fun mpv_create(): Pointer?
    fun mpv_initialize(handle: Pointer): Int
    fun mpv_terminate_destroy(handle: Pointer)
    fun mpv_free(data: Pointer)

    // --- options and properties ---------------------------------------------------------
    fun mpv_set_option_string(handle: Pointer, name: String, data: String): Int
    fun mpv_set_property_string(handle: Pointer, name: String, data: String): Int

    /**
     * `data` is an **out-pointer**: for [MPV_FORMAT_STRING] pass a `PointerByReference` and
     * free the result with [mpv_free]; for INT64/DOUBLE/FLAG pass the matching ByReference.
     */
    fun mpv_get_property(handle: Pointer, name: String, format: Int, data: Pointer): Int

    /** `data` points at the value itself (a `LongByReference` for INT64, etc.), not at a copy of it. */
    fun mpv_set_property(handle: Pointer, name: String, format: Int, data: Pointer): Int

    fun mpv_observe_property(handle: Pointer, replyUserdata: Long, name: String, format: Int): Int

    // --- commands ------------------------------------------------------------------------
    /** `args` must be NULL-terminated — see [MpvHandle.command], which appends the terminator. */
    fun mpv_command(handle: Pointer, args: Array<String?>): Int

    // --- events --------------------------------------------------------------------------
    /**
     * Returns a pointer mpv owns and reuses; it is valid only until the next call on this
     * handle, so anything needed afterwards must be copied out before returning.
     */
    fun mpv_wait_event(handle: Pointer, timeout: Double): MpvEvent
    fun mpv_wakeup(handle: Pointer)
    fun mpv_request_log_messages(handle: Pointer, minLevel: String): Int

    // --- render context (GPU path) -------------------------------------------------------
    fun mpv_render_context_create(res: Array<Pointer?>, mpv: Pointer, params: Pointer): Int
    fun mpv_render_context_render(ctx: Pointer, params: Pointer): Int
    fun mpv_render_context_update(ctx: Pointer): Long
    fun mpv_render_context_set_update_callback(ctx: Pointer, callback: MpvRenderUpdateFn?, ctxArg: Pointer?)
    fun mpv_render_context_report_swap(ctx: Pointer)
    fun mpv_render_context_free(ctx: Pointer)

    companion object {
        /**
         * ⚠ **libmpv refuses to start under a non-C `LC_NUMERIC`, and the JVM sets a locale
         * during startup.** `mpv_create()` then fails with no diagnostic that reaches Java.
         * This is not optional and not discoverable from the failure — it cost real time to
         * find during the spike, so the fix is pinned here, before the library is ever loaded.
         */
        val INSTANCE: MpvLibrary by lazy {
            LibC.INSTANCE.setlocale(LC_NUMERIC, "C")
            load()
        }

        /**
         * ⚠ `Native.load("mpv")` resolves **`libmpv.so`**, and that unversioned symlink ships in
         * `libmpv-dev` — NOT in the `libmpv2` runtime package a user would have. A dev machine
         * therefore cannot see the failure a packaged install would hit: no symlink, no libmpv,
         * silent fallback to VLCJ at 4× the CPU. The soname is tried explicitly for that reason.
         */
        private fun load(): MpvLibrary =
            runCatching { Native.load("mpv", MpvLibrary::class.java) }
                .getOrElse { Native.load(SONAME, MpvLibrary::class.java) }

        /** The runtime package's actual file name. API 2.x is the only version this binds. */
        private const val SONAME = "libmpv.so.2"
    }
}

/** Just enough libc to fix `LC_NUMERIC` before libmpv loads. */
internal interface LibC : Library {
    fun setlocale(category: Int, locale: String): String?

    companion object {
        val INSTANCE: LibC by lazy { Native.load("c", LibC::class.java) }
    }
}

/** glibc's `LC_NUMERIC`. Values are libc-specific; this is the Linux/glibc one. */
private const val LC_NUMERIC = 1

// ---------------------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------------------

internal object MpvFormat {
    const val NONE = 0
    const val STRING = 1
    const val OSD_STRING = 2
    const val FLAG = 3
    const val INT64 = 4
    const val DOUBLE = 5
    const val NODE = 6
}

internal object MpvEventId {
    const val NONE = 0
    const val SHUTDOWN = 1
    const val LOG_MESSAGE = 2
    const val START_FILE = 6
    const val END_FILE = 7
    const val FILE_LOADED = 8
    const val IDLE = 11
    const val CLIENT_MESSAGE = 16
    const val VIDEO_RECONFIG = 17
    const val AUDIO_RECONFIG = 18
    const val SEEK = 20
    const val PLAYBACK_RESTART = 21
    const val PROPERTY_CHANGE = 22
    const val QUEUE_OVERFLOW = 24
}

/** `mpv_end_file_reason`. Only ERROR means the file actually failed. */
internal object MpvEndFileReason {
    const val EOF = 0
    const val STOP = 2
    const val QUIT = 3
    const val ERROR = 4
    const val REDIRECT = 5
}

internal object MpvRenderParam {
    const val INVALID = 0
    const val API_TYPE = 1
    const val OPENGL_INIT_PARAMS = 2
    const val OPENGL_FBO = 3
    const val FLIP_Y = 4
    const val X11_DISPLAY = 8
    const val ADVANCED_CONTROL = 10
    const val BLOCK_FOR_TARGET_TIME = 12
    const val SW_SIZE = 17
    const val SW_FORMAT = 18
    const val SW_STRIDE = 19
    const val SW_POINTER = 20
}

internal const val MPV_RENDER_API_TYPE_OPENGL = "opengl"
internal const val MPV_RENDER_API_TYPE_SW = "sw"

/** Bit returned by `mpv_render_context_update` meaning a new frame is ready to render. */
internal const val MPV_RENDER_UPDATE_FRAME = 1L

// ---------------------------------------------------------------------------------------
// Structures
//
// Field order defines the native layout, so it must match the header exactly — JNA derives
// offsets from the declaration order in getFieldOrder(), not from the names.
// ---------------------------------------------------------------------------------------

/** `mpv_event` from client.h: `{int event_id; int error; uint64_t reply_userdata; void *data;}`. */
@Structure.FieldOrder("event_id", "error", "reply_userdata", "data")
internal open class MpvEvent(
    @JvmField var event_id: Int = 0,
    @JvmField var error: Int = 0,
    @JvmField var reply_userdata: Long = 0,
    @JvmField var data: Pointer? = null,
) : Structure(), Structure.ByReference

/** `mpv_event_property`: `{const char *name; mpv_format format; void *data;}`. */
@Structure.FieldOrder("name", "format", "data")
internal open class MpvEventProperty(
    @JvmField var name: Pointer? = null,
    @JvmField var format: Int = 0,
    @JvmField var data: Pointer? = null,
) : Structure(), Structure.ByReference {
    constructor(p: Pointer) : this() {
        useMemory(p)
        read()
    }
}

/**
 * `mpv_event_end_file`, truncated to the two fields this app reads.
 *
 * The real struct continues with `int64_t playlist_entry_id` and two more ints. Declaring only
 * the leading fields is safe because this is only ever mapped onto memory mpv owns and is never
 * written back or allocated by JNA — the omitted tail is simply not read.
 */
@Structure.FieldOrder("reason", "error")
internal open class MpvEventEndFile(
    @JvmField var reason: Int = 0,
    @JvmField var error: Int = 0,
) : Structure(), Structure.ByReference {
    constructor(p: Pointer) : this() {
        useMemory(p)
        read()
    }
}

/**
 * `mpv_event_log_message`, truncated to the three strings this app reads.
 *
 * The real struct ends with an `mpv_log_level` enum. Omitting it is safe for the same reason as
 * [MpvEventEndFile] — the struct is only ever mapped onto memory mpv owns, never allocated or
 * written back — and `level` already carries the severity as text.
 */
@Structure.FieldOrder("prefix", "level", "text")
internal open class MpvEventLogMessage(
    @JvmField var prefix: Pointer? = null,
    @JvmField var level: Pointer? = null,
    @JvmField var text: Pointer? = null,
) : Structure(), Structure.ByReference {
    constructor(p: Pointer) : this() {
        useMemory(p)
        read()
    }
}

/** `mpv_render_param`: `{int type; void *data;}` — the array is terminated by a zeroed entry. */
@Structure.FieldOrder("type", "data")
internal open class MpvRenderParamStruct(
    @JvmField var type: Int = 0,
    @JvmField var data: Pointer? = null,
) : Structure(), Structure.ByReference

/** `mpv_opengl_init_params`: `{void *(*get_proc_address)(void *ctx, const char *name); void *ctx;}`. */
@Structure.FieldOrder("get_proc_address", "get_proc_address_ctx")
internal open class MpvOpenGLInitParams(
    @JvmField var get_proc_address: MpvGetProcAddressFn? = null,
    @JvmField var get_proc_address_ctx: Pointer? = null,
) : Structure(), Structure.ByReference

/** `mpv_opengl_fbo`: `{int fbo; int w; int h; int internal_format;}`. */
@Structure.FieldOrder("fbo", "w", "h", "internal_format")
internal open class MpvOpenGLFbo(
    @JvmField var fbo: Int = 0,
    @JvmField var w: Int = 0,
    @JvmField var h: Int = 0,
    @JvmField var internal_format: Int = 0,
) : Structure(), Structure.ByReference

// ---------------------------------------------------------------------------------------
// Callbacks
// ---------------------------------------------------------------------------------------

/**
 * mpv's GL loader. ⚠ The signature takes a `ctx` first argument that `eglGetProcAddress`
 * does **not** have — handing mpv the raw `eglGetProcAddress` makes it read `ctx` as the
 * symbol name. This adapter shape is mandatory, not stylistic.
 */
internal fun interface MpvGetProcAddressFn : Callback {
    fun invoke(ctx: Pointer?, name: String): Pointer?
}

/**
 * Fired by mpv when a new frame is available. ⚠ Called from **mpv's own thread**, and mpv
 * documents that it must not block or re-enter the API — so implementations only post work
 * to the render thread.
 */
internal fun interface MpvRenderUpdateFn : Callback {
    fun invoke(ctx: Pointer?)
}

/** Typed wrapper so a raw `mpv_handle*` can't be confused with a render context pointer. */
internal class MpvHandlePointer : PointerType()
