package com.nuvio.app.desktop.mpv

import com.sun.jna.Pointer
import com.sun.jna.ptr.DoubleByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference

private const val TAG = "NuvioMpv"

/** A libmpv error code turned into something throwable, keeping mpv's own message. */
internal class MpvException(val code: Int, message: String) : RuntimeException(message)

/**
 * Owns one `mpv_handle` and makes it usable from Kotlin: typed property access, commands,
 * and a daemon event thread.
 *
 * Threading: every method here is safe to call from any thread (libmpv's client API is
 * thread-safe), **except** that [dispose] must not race with in-flight calls on the same
 * handle — it stops the event thread first for exactly that reason.
 */
internal class MpvHandle private constructor(private var handle: Pointer) {

    private val mpv = MpvLibrary.INSTANCE

    @Volatile private var disposed = false
    private var eventThread: Thread? = null

    /** Set before [startEventLoop]; invoked on the event thread. */
    var onEvent: ((MpvEventInfo) -> Unit)? = null

    // --- options / properties -------------------------------------------------------------

    /** Options must be set before [initialize]; after that, use the property setters. */
    fun setOption(name: String, value: String) {
        if (disposed) return
        val rc = mpv.mpv_set_option_string(handle, name, value)
        if (rc < 0) println("$TAG: set_option $name=$value failed: ${errorString(rc)}")
    }

    fun setPropertyString(name: String, value: String) {
        if (disposed) return
        val rc = mpv.mpv_set_property_string(handle, name, value)
        if (rc < 0) println("$TAG: set_property $name=$value failed: ${errorString(rc)}")
    }

    fun setPropertyBoolean(name: String, value: Boolean) {
        if (disposed) return
        val ref = IntByReference(if (value) 1 else 0)
        mpv.mpv_set_property(handle, name, MpvFormat.FLAG, ref.pointer)
    }

    fun setPropertyLong(name: String, value: Long) {
        if (disposed) return
        mpv.mpv_set_property(handle, name, MpvFormat.INT64, LongByReference(value).pointer)
    }

    fun setPropertyDouble(name: String, value: Double) {
        if (disposed) return
        mpv.mpv_set_property(handle, name, MpvFormat.DOUBLE, DoubleByReference(value).pointer)
    }

    /**
     * Null when the property is unavailable — which is normal, not exceptional: `duration`
     * and the track list simply do not exist until a file is loaded, and callers poll them.
     */
    fun getPropertyString(name: String): String? {
        if (disposed) return null
        val ref = PointerByReference()
        if (mpv.mpv_get_property(handle, name, MpvFormat.STRING, ref.pointer) < 0) return null
        val ptr = ref.value ?: return null
        return try {
            ptr.getString(0)
        } finally {
            // mpv allocated this copy; it is ours to free and leaks once per poll otherwise.
            mpv.mpv_free(ptr)
        }
    }

    fun getPropertyDouble(name: String): Double? {
        if (disposed) return null
        val ref = DoubleByReference()
        if (mpv.mpv_get_property(handle, name, MpvFormat.DOUBLE, ref.pointer) < 0) return null
        return ref.value
    }

    fun getPropertyLong(name: String): Long? {
        if (disposed) return null
        val ref = LongByReference()
        if (mpv.mpv_get_property(handle, name, MpvFormat.INT64, ref.pointer) < 0) return null
        return ref.value
    }

    fun getPropertyBoolean(name: String): Boolean? {
        if (disposed) return null
        val ref = IntByReference()
        if (mpv.mpv_get_property(handle, name, MpvFormat.FLAG, ref.pointer) < 0) return null
        return ref.value != 0
    }

    fun observeProperty(name: String, format: Int) {
        if (disposed) return
        val rc = mpv.mpv_observe_property(handle, 0L, name, format)
        if (rc < 0) println("$TAG: observe $name failed: ${errorString(rc)}")
    }

    // --- commands -------------------------------------------------------------------------

    /** Returns false when mpv rejected the command; failures are logged, never thrown. */
    fun command(vararg args: String): Boolean {
        if (disposed) return false
        // mpv_command takes a NULL-terminated argv, so the terminator is appended here rather
        // than at every call site.
        val argv = arrayOfNulls<String>(args.size + 1)
        args.forEachIndexed { i, a -> argv[i] = a }
        val rc = mpv.mpv_command(handle, argv)
        if (rc < 0) println("$TAG: command ${args.joinToString(" ")} failed: ${errorString(rc)}")
        return rc >= 0
    }

    // --- events ---------------------------------------------------------------------------

    /**
     * Starts the daemon thread that drains mpv's event queue. Events are delivered to
     * [onEvent] on that thread, so handlers must not block.
     */
    fun startEventLoop() {
        if (disposed || eventThread != null) return
        val t = Thread(null, {
            while (!disposed) {
                // A finite timeout rather than -1: it lets the loop notice `disposed` even if
                // the wakeup is missed, so teardown can never hang on a stuck handle.
                val ev = try {
                    mpv.mpv_wait_event(handle, 0.5)
                } catch (e: Throwable) {
                    if (!disposed) println("$TAG: event loop error: ${e.message}")
                    break
                }
                if (disposed) break
                val id = ev.event_id
                if (id == MpvEventId.NONE) continue
                // The event struct and everything it points at belong to mpv and are reused
                // on the next wait_event, so anything the listener may keep is copied out here.
                val info = when (id) {
                    MpvEventId.PROPERTY_CHANGE -> {
                        val p = ev.data?.let { MpvEventProperty(it) }
                        MpvEventInfo(
                            id = id,
                            propertyName = p?.name?.getString(0),
                            propertyFormat = p?.format ?: MpvFormat.NONE,
                            propertyString = if (p?.format == MpvFormat.STRING) {
                                p.data?.getPointer(0)?.getString(0)
                            } else null,
                            propertyDouble = if (p?.format == MpvFormat.DOUBLE) {
                                p.data?.getDouble(0)
                            } else null,
                            propertyLong = if (p?.format == MpvFormat.INT64) {
                                p.data?.getLong(0)
                            } else null,
                            propertyFlag = if (p?.format == MpvFormat.FLAG) {
                                p.data?.getInt(0) != 0
                            } else null,
                        )
                    }
                    MpvEventId.END_FILE -> {
                        // The reason is the only way to tell "the stream failed" from "the file
                        // ended" or "we replaced it" — without it a dead link is indistinguishable
                        // from a finished episode, and the UI would sit on a spinner forever.
                        val e = ev.data?.let { MpvEventEndFile(it) }
                        MpvEventInfo(
                            id = id,
                            endFileReason = e?.reason ?: MpvEndFileReason.EOF,
                            endFileError = e?.error ?: 0,
                        )
                    }
                    else -> MpvEventInfo(id = id)
                }
                try {
                    onEvent?.invoke(info)
                } catch (e: Throwable) {
                    println("$TAG: event handler threw: ${e.message}")
                }
                if (id == MpvEventId.SHUTDOWN) break
            }
        }, "mpv-events", 0).apply { isDaemon = true }
        eventThread = t
        t.start()
    }

    // --- lifecycle -------------------------------------------------------------------------

    fun initialize() {
        val rc = mpv.mpv_initialize(handle)
        if (rc < 0) throw MpvException(rc, "mpv_initialize failed: ${errorString(rc)}")
    }

    /** The raw handle, for the render context. Null once disposed. */
    fun rawHandle(): Pointer? = if (disposed) null else handle

    fun dispose() {
        if (disposed) return
        disposed = true
        // Stop the event thread BEFORE destroying the handle: mpv_terminate_destroy while
        // another thread sits in mpv_wait_event on the same handle is a use-after-free.
        mpv.mpv_wakeup(handle)
        eventThread?.let { t ->
            try { t.join(2_000) } catch (_: InterruptedException) {}
        }
        eventThread = null
        try { mpv.mpv_terminate_destroy(handle) } catch (e: Throwable) {
            println("$TAG: terminate_destroy threw: ${e.message}")
        }
    }

    fun errorString(code: Int): String =
        runCatching { mpv.mpv_error_string(code) }.getOrNull() ?: "error $code"

    companion object {
        /**
         * True when libmpv is present, loadable, and NEW ENOUGH — checked before any attempt to
         * use it.
         *
         * ⚠ The version half is not paranoia. Ubuntu 22.04 and Debian 12 ship mpv 0.34/0.35,
         * whose library is `libmpv.so.1` with client API 1.x; this binding's structs and render
         * API are 2.x. On such a machine `Native.load("mpv")` can still succeed through the
         * `libmpv.so` symlink, and the mismatch would surface as corrupt event data rather than
         * as a load error. Falling back to VLCJ is the right answer there.
         */
        val isAvailable: Boolean by lazy {
            runCatching {
                val v = MpvLibrary.INSTANCE.mpv_client_api_version()
                val major = (v shr 16) and 0xFFFF
                if (major < 2) {
                    println("$TAG: libmpv client API $major.x is too old (2.x required) — using VLCJ")
                    false
                } else {
                    true
                }
            }
                .onFailure { println("$TAG: libmpv unavailable: ${it.message}") }
                .getOrDefault(false)
        }

        fun apiVersion(): String? = runCatching {
            val v = MpvLibrary.INSTANCE.mpv_client_api_version()
            "${(v shr 16) and 0xFFFF}.${v and 0xFFFF}"
        }.getOrNull()

        /** Null when libmpv is missing or refuses to start — callers fall back to VLCJ. */
        fun create(): MpvHandle? {
            if (!isAvailable) return null
            val p = runCatching { MpvLibrary.INSTANCE.mpv_create() }.getOrNull()
            if (p == null) {
                // Almost always the LC_NUMERIC trap — MpvLibrary.INSTANCE fixes it at load
                // time, so reaching here means something else is wrong.
                println("$TAG: mpv_create returned NULL")
                return null
            }
            return MpvHandle(p)
        }
    }
}

/** A copied-out snapshot of one mpv event; safe to hand across threads. */
internal data class MpvEventInfo(
    val id: Int,
    val propertyName: String? = null,
    val propertyFormat: Int = MpvFormat.NONE,
    val propertyString: String? = null,
    val propertyDouble: Double? = null,
    val propertyLong: Long? = null,
    val propertyFlag: Boolean? = null,
    /** END_FILE only: an [MpvEndFileReason]. */
    val endFileReason: Int = MpvEndFileReason.EOF,
    /** END_FILE only: the mpv error code, meaningful when the reason is ERROR. */
    val endFileError: Int = 0,
)
