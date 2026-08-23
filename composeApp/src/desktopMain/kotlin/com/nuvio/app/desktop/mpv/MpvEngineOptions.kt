package com.nuvio.app.desktop.mpv

import com.nuvio.app.features.player.PlayerSettingsStorage
import kotlin.math.roundToInt

private const val TAG = "NuvioMpvOptions"

/**
 * How the decoded frame reaches the screen. This is the one setting the whole libmpv
 * investigation turned on, so it is explicit rather than left to `hwdec=auto`.
 */
internal enum class MpvVideoOutput {
    /**
     * Zero-copy VA-API into the GL texture Compose already renders through. Requires an EGL
     * context — `hwdec=vaapi` silently degrades to `no` on GLX, which is exactly the failure
     * this whole path exists to avoid.
     */
    GPU,

    /**
     * mpv renders into a CPU buffer that reuses the existing frame→ImageBitmap→Canvas path.
     * Still hardware-*decodes* (`vaapi-copy`), it just reads the frame back, so it is the
     * middle tier: faster than software decode, slower than GPU.
     */
    SOFTWARE,
}

/**
 * Builds a configured, initialised [MpvHandle] from the app's player settings — the mpv
 * counterpart of `getVlcjFactory()`.
 *
 * Returns null when libmpv is missing or refuses to start, so callers fall back to VLCJ
 * rather than leaving the user with no player.
 */
internal object MpvEngineOptions {

    fun createHandle(output: MpvVideoOutput): MpvHandle? {
        val mpv = MpvHandle.create() ?: return null

        // --- video ---------------------------------------------------------------------
        // vo=libmpv is mandatory for the render-context API: mpv must NOT open its own
        // window, it hands frames to us instead.
        mpv.setOption("vo", "libmpv")

        val hwAccel = PlayerSettingsStorage.loadHwAccelEnabled() ?: true
        val hwdec = when {
            !hwAccel -> "no"
            // ⚠ Explicitly `vaapi`, never `auto`: auto silently settles on vaapi-copy, which
            // reads every 4K frame back over the bus and costs most of the win. The caller
            // asserts hwdec-current afterwards because this option is a request, not a promise.
            output == MpvVideoOutput.GPU -> "vaapi"
            // The software path cannot avoid a readback, so copy-back is the honest choice.
            else -> "auto-copy"
        }
        mpv.setOption("hwdec", hwdec)

        // ⚠ The SOFTWARE path does not keep up with 4K on this class of hardware: measured 19.8
        // render fps against a 24 fps source, with audio drifting seconds ahead. The cost is the
        // `vaapi-copy` readback of 4K frames, NOT the scale — per-render cost is identical at
        // 1920x1111 and 960x540, and a 1080p source on the same output size runs 28.7 fps. Fast
        // scalers (`sws-fast`, `fast-bilinear`) were measured and changed nothing, so they are
        // deliberately not set. The GPU path, which never reads the frame back, is the fix.

        // --- audio ---------------------------------------------------------------------
        PlayerSettingsStorage.loadAudioOutput()?.takeIf { it.isNotBlank() }?.let {
            mpv.setOption("ao", it)
        }

        // --- subtitles -----------------------------------------------------------------
        applySubtitleStyle(mpv)

        // --- streaming behaviour --------------------------------------------------------
        // No terminal output and no config files: mpv must behave identically regardless of
        // whatever the user has in ~/.config/mpv, which would otherwise silently change
        // decoding behaviour under us.
        mpv.setOption("terminal", "no")
        mpv.setOption("config", "no")
        mpv.setOption("osc", "no")
        mpv.setOption("input-default-bindings", "no")
        mpv.setOption("input-vo-keyboard", "no")
        // mpv renders subtitles into the frame, matching the VLCJ path where libVLC's freetype
        // module did the same. Auto-loading sidecar .srt files off disk is off: sources are
        // network streams and the app adds subtitles explicitly via sub-add.
        mpv.setOption("sub-auto", "no")
        // Keep the player alive at end-of-file so the UI controls a real handle rather than
        // a torn-down one; the controller reports isEnded from eof-reached.
        mpv.setOption("keep-open", "yes")
        mpv.setOption("idle", "yes")
        // Network resilience for debrid/CDN links, which drop connections routinely.
        mpv.setOption("cache", "yes")
        mpv.setOption("demuxer-max-bytes", "128MiB")
        mpv.setOption("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=5")

        return try {
            mpv.initialize()
            // `info`, not `warn`, and the difference was measured rather than guessed.
            //
            // `warn` does catch an audio device that fails outright — mpv says "Could not
            // open/initialize audio device -> no sound" in as many words. But the silent-playback
            // report this was added for was NOT that: mpv had opened `pipewire` and reported
            // healthy audio throughout, so `warn` would have logged nothing at all about the one
            // bug it exists to explain. `info` adds the track-selection and `AO:`/`VO:` format
            // lines, which is what makes a "no sound" report diagnosable without asking the user
            // to reproduce it under an env var.
            //
            // The cost is 2-4 lines per file load — measured against libmpv, not estimated — so
            // this is not chatty on a player that opens one file per episode.
            mpv.requestLogMessages(
                System.getenv("NUVIO_MPV_LOG_LEVEL")?.takeIf { it.isNotBlank() } ?: "info",
            )
            println("$TAG: initialised (output=$output hwdec-request=$hwdec)")
            mpv
        } catch (e: Exception) {
            println("$TAG: mpv_initialize failed: ${e.message}")
            mpv.dispose()
            null
        }
    }

    private fun applySubtitleStyle(mpv: MpvHandle) {
        // These settings are stored as libVLC values, since the VLCJ engine wrote them first.
        // They are translated here rather than migrated, so both engines keep reading the
        // same stored preference and the user sees the same size on either path.
        val vlcRelFontSize = PlayerSettingsStorage.loadSubtitleFontSize() ?: 16
        val color = PlayerSettingsStorage.loadSubtitleColor() ?: 0xFFFFFF
        val bgOpacity = PlayerSettingsStorage.loadSubtitleBackgroundOpacity() ?: 0
        val outline = PlayerSettingsStorage.loadSubtitleOutline() ?: 2

        // libVLC's rel-fontsize is INVERSE (text height = video height / value), while mpv's
        // sub-font-size is a direct size against a 720-high reference. So the translation is a
        // reciprocal, not a scale factor — treating it as one would make large text tiny.
        val mpvFontSize = (720.0 / vlcRelFontSize.coerceAtLeast(1)).roundToInt().coerceIn(10, 200)
        mpv.setOption("sub-font-size", mpvFontSize.toString())
        mpv.setOption("sub-color", hexColor(0xFF, color))
        mpv.setOption("sub-back-color", hexColor(bgOpacity.coerceIn(0, 255), 0x000000))
        mpv.setOption("sub-border-size", outline.coerceAtLeast(0).toString())
    }

    /** mpv wants `#AARRGGBB`; the stored colour is a plain 0xRRGGBB int. */
    private fun hexColor(alpha: Int, rgb: Int): String =
        "#%02X%06X".format(alpha and 0xFF, rgb and 0xFFFFFF)
}
