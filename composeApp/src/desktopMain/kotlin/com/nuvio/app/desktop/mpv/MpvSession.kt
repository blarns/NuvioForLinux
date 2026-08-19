package com.nuvio.app.desktop.mpv

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "NuvioMpvSession"

/** Initial render size, replaced by the real one as soon as the surface is laid out. */
private const val INITIAL_WIDTH = 1280
private const val INITIAL_HEIGHT = 720

/**
 * Frame delivery cap, matching the VLCJ path. Each delivered frame launches a
 * `Dispatchers.Main` coroutine that writes Compose state; at 60 fps those compete with the
 * Compose resource loader for the compose-resources ZipFile and produce "invalid LOC header"
 * crashes on pause. 30 fps has been stable there for months.
 */
private const val MIN_FRAME_INTERVAL_MS = 33L

/**
 * One playback session on libmpv: the handle, the render context, the controller, and the
 * thread that pumps frames out of mpv.
 *
 * Everything with a native lifetime lives here rather than in the Compose surface, because the
 * teardown ORDER is not negotiable — the frame pump must be stopped and joined before the render
 * context is freed (rendering into a freed context is a use-after-free), and the render context
 * must be freed before the handle is destroyed. Composition disposal order is not a strong
 * enough guarantee to hang that on, so [dispose] does the whole sequence itself.
 */
internal class MpvSession private constructor(
    private val handle: MpvHandle,
    private val renderer: MpvSoftwareRenderer?,
    val controller: MpvPlayerController,
    /** True when frames go straight into a GL texture Compose samples — no readback, no copy. */
    val isGpu: Boolean,
) {
    /** Set by the surface so engine errors reach the UI's own error handling. */
    @Volatile var onError: ((Exception) -> Unit)? = null

    /**
     * The GL renderer, built on the FIRST DRAW rather than here.
     *
     * ⚠ Creating it probes the live GL context, so it has to happen on the EDT with Compose's
     * EGL context current. Composition runs on the EDT too, but the context is only guaranteed
     * current inside a frame — so this is created from the draw scope, where both hold.
     */
    @Volatile private var gpu: MpvGpuRenderer? = null
    @Volatile private var gpuFailed = false

    /** Requested render size, packed as `width shl 32 or height` so it can never be read torn. */
    private val requestedSize = AtomicLong(pack(INITIAL_WIDTH, INITIAL_HEIGHT))

    private val stopped = AtomicBoolean(false)
    private var pumpThread: Thread? = null

    /** The decode tier mpv actually reached — null until a file has loaded. */
    val hwdecCurrent: String? get() = controller.hwdecCurrent

    /** Called with the surface's pixel size; the pump picks it up on its next iteration. */
    fun setSurfaceSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        requestedSize.set(pack(width, height))
    }

    /**
     * Fit (letterbox, the default) vs Fill (stretch). Done inside mpv rather than by scaling the
     * delivered frame: mpv is already scaling to the surface size, so letterboxing there costs
     * nothing and the frame can be blitted 1:1.
     */
    fun setKeepAspect(keep: Boolean) {
        handle.setPropertyBoolean("keepaspect", keep)
    }

    // --- GPU path ---------------------------------------------------------------------------

    /**
     * Renders the pending frame into the session's texture and returns its GL id, or null when
     * there was nothing new (in which case the caller redraws the previous texture).
     *
     * ⚠ EDT only, from inside a Compose draw — that is the only place the EGL context is current.
     * [onFrameAvailable] is how a new frame gets a redraw scheduled at all.
     */
    fun renderGpuFrame(width: Int, height: Int, onFrameAvailable: () -> Unit): Int? {
        if (!isGpu || stopped.get() || gpuFailed) return null
        val renderer = gpu ?: run {
            val created = MpvGpuRenderer.create(handle, com.nuvio.app.desktop.egl.EglSeam.xDisplay, width, height)
            if (created == null) {
                // Nothing to fall back to at this point — the handle was configured for the GPU
                // tier — so this is latched and logged rather than retried every frame.
                gpuFailed = true
                println("$TAG: GL render context unavailable; no video will be drawn")
                return null
            }
            created.onFrameAvailable = onFrameAvailable
            gpu = created
            created
        }
        // A false return means "no new frame", not "nothing to draw" — the previous frame is
        // still in the texture and must be redrawn, or the video flickers between real frames.
        renderer.render(width, height)
        return renderer.textureId.takeIf { it != 0 }
    }

    /** Tells mpv the frame reached the screen; its timing depends on it. EDT only. */
    fun reportGpuSwap() {
        gpu?.reportSwap()
    }

    /**
     * Starts the frame pump. [onFrame] is called on the pump thread with a freshly allocated
     * BGRA buffer and its dimensions; it must not block.
     *
     * The buffer is allocated per frame rather than reused because the consumer keeps it — it
     * goes to both the Skia bitmap and the screenshot store, and a recycled buffer would let a
     * later frame overwrite a screenshot mid-save. mpv renders at the SURFACE size, not the
     * source size, so this is ~8 MB per frame at 1080p instead of the VLCJ path's ~33 MB at 4K.
     */
    fun startFramePump(onFrame: (ByteArray, Int, Int) -> Unit) {
        val renderer = this.renderer ?: return   // GPU path: frames are pulled from the draw scope
        if (stopped.get() || pumpThread != null) return
        val t = Thread(null, {
            var lastDeliveredMs = 0L
            var windowStartMs = System.currentTimeMillis()
            var windowFrames = 0
            // Destination for frames that are rendered but not delivered. They still have to be
            // rendered — see below — but nobody keeps them, so one buffer is reused.
            var scratch = ByteArray(0)
            while (!stopped.get()) {
                // ⚠ Pacing diagnostics, not decoration. The client IS the display here: mpv hands
                // over one frame per render call, so a pump that cannot keep up with the source
                // frame rate makes video fall behind audio without bound rather than dropping
                // frames. `avsync` growing negative is exactly that failure.
                val nowMs = System.currentTimeMillis()
                if (nowMs - windowStartMs >= 5_000) {
                    val fps = windowFrames * 1000.0 / (nowMs - windowStartMs)
                    println("$TAG: pump %.1f fps  %s".format(fps, controller.pacingReport()))
                    windowStartMs = nowMs
                    windowFrames = 0
                }
                val packed = requestedSize.get()
                val w = unpackWidth(packed)
                val h = unpackHeight(packed)
                // resize() is a cheap no-op unless the size actually changed, and this is the
                // only thread that ever touches the renderer, so size and buffer stay in step.
                renderer.resize(w, h)

                if (!renderer.hasNewFrame()) {
                    Thread.sleep(2)
                    continue
                }

                // ⚠ Every signalled frame is RENDERED, even when it will not be delivered.
                // mpv advances by one frame per render call, so deferring a render does not
                // "skip" a frame — it delays the whole video stream, and the delay accumulates
                // until audio is seconds ahead. The throttle therefore limits how often frames
                // reach Compose (which is what the ~30fps cap was ever for), never how often mpv
                // is asked to render.
                val now = System.currentTimeMillis()
                val deliver = now - lastDeliveredMs >= MIN_FRAME_INTERVAL_MS
                val bytes = if (deliver) {
                    ByteArray(w * h * 4)
                } else {
                    if (scratch.size < w * h * 4) scratch = ByteArray(w * h * 4)
                    scratch
                }
                if (!renderer.render(bytes)) continue
                windowFrames++
                if (!deliver) continue
                lastDeliveredMs = now
                try {
                    onFrame(bytes, w, h)
                } catch (e: Throwable) {
                    println("$TAG: frame consumer threw: ${e.message}")
                }
            }
        }, "mpv-frames", 0).apply { isDaemon = true }
        pumpThread = t
        t.start()
    }

    /** Idempotent, and safe to call from any thread. */
    fun dispose() {
        if (!stopped.compareAndSet(false, true)) return
        pumpThread?.let { t ->
            // 2s is far longer than one render; a pump still running after that is wedged inside
            // mpv, and freeing the context under it would crash the process, so leave it be.
            try { t.join(2_000) } catch (_: InterruptedException) {}
            if (t.isAlive) {
                println("$TAG: frame pump did not stop; leaving the render context alive")
                pumpThread = null
                return
            }
        }
        pumpThread = null
        renderer?.dispose()
        // ⚠ The GL renderer deletes GL objects, so it can only be torn down where the EGL
        // context is current — the EDT. Compose disposes on the EDT already; the branch is for
        // any other caller (an error path, a test) so teardown can never happen off-thread.
        gpu?.let { g ->
            if (javax.swing.SwingUtilities.isEventDispatchThread()) {
                g.dispose()
            } else {
                runCatching { javax.swing.SwingUtilities.invokeAndWait { g.dispose() } }
            }
        }
        gpu = null
        controller.dispose()   // disposes the handle too
        println("$TAG: disposed")
    }

    companion object {
        /**
         * Opt-in while the engine is being proven on real hardware. VLCJ stays the default and
         * the fallback: nothing below runs unless this is set.
         */
        val isEnabled: Boolean get() = System.getenv("NUVIO_MPV") == "1"

        /**
         * Null whenever libmpv is missing or refuses to start — the caller falls back to VLCJ
         * rather than leaving the user with no player at all.
         */
        fun create(): MpvSession? {
            if (!MpvHandle.isAvailable) {
                println("$TAG: libmpv not available — falling back to VLCJ")
                return null
            }
            // ⚠ The GPU tier needs Compose to be rendering through EGL, and needs it to be
            // rendering ALREADY — `isActive` is only true once a frame has been drawn and the
            // Skia context published. On GLX, `hwdec=vaapi` degrades to `no` (software decode)
            // with no error, so guessing wrong here is worse than staying on the copy path.
            val useGpu = com.nuvio.app.desktop.egl.EglRenderer.isActive
            val output = if (useGpu) MpvVideoOutput.GPU else MpvVideoOutput.SOFTWARE
            val handle = MpvEngineOptions.createHandle(output) ?: return null
            // The GL render context is built on the first draw (see [renderGpuFrame]); only the
            // software renderer can be created here, off a live GL context.
            val renderer = if (useGpu) null else {
                MpvSoftwareRenderer.create(handle, INITIAL_WIDTH, INITIAL_HEIGHT) ?: run {
                    handle.dispose()
                    return null
                }
            }
            // The controller has to exist before the session and the session has to exist before
            // the error sink can be reached, so the lambda closes over the (mutable) session
            // reference rather than over a callback that would have to be re-pointed later.
            var session: MpvSession? = null
            val controller = MpvPlayerController(
                mpv = handle,
                onError = { e -> session?.onError?.invoke(e) },
            )
            session = MpvSession(handle, renderer, controller, isGpu = useGpu)
            handle.startEventLoop()
            println("$TAG: created (libmpv ${MpvHandle.apiVersion()}, ${if (useGpu) "GPU" else "SOFTWARE"} render)")
            return session
        }

        private fun pack(w: Int, h: Int): Long = (w.toLong() shl 32) or (h.toLong() and 0xFFFFFFFFL)
        private fun unpackWidth(v: Long): Int = (v ushr 32).toInt()
        private fun unpackHeight(v: Long): Int = (v and 0xFFFFFFFFL).toInt()
    }
}
