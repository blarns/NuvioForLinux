package com.nuvio.app

import java.awt.GraphicsEnvironment
import java.awt.Toolkit

/**
 * Startup window geometry for the desktop app.
 *
 * The window used to open at a hardcoded 1280x720 regardless of the display, which reads as
 * "the window is smaller than my screen" on anything larger — github.com/blarns/NuvioForLinux/issues/4.
 * The size is now derived from the screen the app actually starts on, and any size restored
 * from a previous run is validated before it is handed to Compose.
 */
internal object DesktopWindowGeometry {
    private const val TAG = "DesktopWindowGeometry"

    /** Below this the shelves and the player controls start colliding. */
    private const val MIN_WIDTH = 800f
    private const val MIN_HEIGHT = 600f

    /** Only used when the display cannot be queried at all (headless, or an exotic setup). */
    private const val FALLBACK_WIDTH = 1280f
    private const val FALLBACK_HEIGHT = 720f

    /** A first run takes most of the screen but stays visibly a floating window. */
    private const val FIRST_RUN_FRACTION = 0.9f

    /** Width/height in Compose dp — the unit [androidx.compose.ui.window.WindowState] works in. */
    data class Size(val width: Float, val height: Float)

    /**
     * The usable area of the default screen in dp, or null when AWT cannot tell us.
     *
     * AWT reports window sizes in user space and Compose converts dp to user-space pixels by
     * multiplying by the display scale, so the scale has to be divided back out here.
     */
    fun usableScreen(): Size? = runCatching {
        if (GraphicsEnvironment.isHeadless()) return@runCatching null
        val config = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice
            .defaultConfiguration
        val bounds = config.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
        val scale = config.defaultTransform.scaleX.toFloat().let { if (it > 0f) it else 1f }
        val width = (bounds.width - insets.left - insets.right) / scale
        val height = (bounds.height - insets.top - insets.bottom) / scale
        if (width < 1f || height < 1f) null else Size(width, height)
    }.getOrNull()

    /**
     * Pick the size to open at. [saved] is the geometry persisted by the previous run (null on a
     * first run), [screen] the usable screen area from [usableScreen].
     */
    fun resolve(saved: Size?, screen: Size?): Size {
        // A stored NaN or a zero survives a round trip through the properties file, so a bad
        // value written by an earlier run would otherwise wedge every later launch.
        val restored = saved?.takeIf {
            it.width.isFinite() && it.height.isFinite() && it.width >= 1f && it.height >= 1f
        }
        val base = restored
            ?: screen?.let { Size(it.width * FIRST_RUN_FRACTION, it.height * FIRST_RUN_FRACTION) }
            ?: Size(FALLBACK_WIDTH, FALLBACK_HEIGHT)
        // Never open larger than the screen the window has to live on: a size restored from a
        // bigger monitor otherwise puts the title bar out of reach.
        val maxWidth = screen?.width ?: Float.MAX_VALUE
        val maxHeight = screen?.height ?: Float.MAX_VALUE
        return Size(
            width = base.width.coerceIn(minOf(MIN_WIDTH, maxWidth), maxWidth),
            height = base.height.coerceIn(minOf(MIN_HEIGHT, maxHeight), maxHeight),
        )
    }

    /** True when a live window size is worth writing back to disk at shutdown. */
    fun isPersistable(width: Float, height: Float): Boolean =
        width.isFinite() && height.isFinite() && width >= 1f && height >= 1f

    /**
     * One line describing the display and session at startup. Window bugs are almost always
     * window-manager specific and cannot be reproduced on the reporter's behalf, so the numbers
     * AWT actually saw are the fastest way to triage one from a pasted log.
     */
    fun logDisplayInfo(resolved: Size, screen: Size?) {
        val session = listOf("XDG_SESSION_TYPE", "XDG_CURRENT_DESKTOP", "WAYLAND_DISPLAY")
            .joinToString(" ") { "$it=${System.getenv(it) ?: "-"}" }
        val device = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        }.getOrNull()
        val scale = runCatching { device?.defaultConfiguration?.defaultTransform?.scaleX }.getOrNull()
        // Compose implements WindowPlacement.Fullscreen as GraphicsDevice.setFullScreenWindow.
        // When AWT reports the device cannot do real fullscreen it falls back to resizing the
        // frame itself, which is where "fullscreen does nothing" reports come from.
        val fullscreenSupported = runCatching { device?.isFullScreenSupported }.getOrNull()
        println(
            "$TAG: opening ${resolved.width}x${resolved.height}dp " +
                "screen=${screen?.let { "${it.width}x${it.height}dp" } ?: "unknown"} " +
                "scale=${scale ?: "unknown"} fullscreenSupported=${fullscreenSupported ?: "unknown"} " +
                session,
        )
    }
}
