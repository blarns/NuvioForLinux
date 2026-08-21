package com.nuvio.app.desktop

import com.nuvio.app.features.player.PlayerSettingsStorage

/**
 * Whether this process runs the experimental libmpv engine, decided ONCE at startup.
 *
 * ⚠ One user-facing choice, two internal switches, and they cannot be separated. libmpv's
 * zero-copy `hwdec=vaapi` needs Compose to be rendering through EGL: on skiko's stock GLX
 * context `vaapi` silently degrades to software, and the software tier cannot keep up with 4K
 * (19.8 render fps against a 24 fps source). mpv without EGL is therefore not a lesser version
 * of this feature, it is a worse player than the one it replaces — so the setting arms both or
 * neither.
 *
 * ⚠ Read once and cached, deliberately. [com.nuvio.app.desktop.egl.EglRenderer.install] has to
 * run before `application {}` — Compose builds and realises its SkiaLayer internally and there
 * is no hook afterwards — so the renderer for this process is fixed before any UI exists. If
 * this were re-read live, flipping the toggle mid-session would put an mpv session on a GLX
 * context, which is exactly the silent software fallback above. Changing the setting requires a
 * restart, and the row in Settings says so.
 *
 * The env vars still win, in both directions, so a spike can force either engine and a user
 * whose install is broken can get their player back without finding the store.
 */
internal object DesktopEngineFlags {

    private fun envOverride(name: String): Boolean? =
        System.getenv(name)?.let { it == "1" }

    /** Machine-local, not profile-scoped: there is no active profile this early in startup. */
    private val settingEnabled: Boolean by lazy {
        PlayerSettingsStorage.loadMpvEngineEnabled() ?: false
    }

    val mpvEnabled: Boolean by lazy {
        envOverride("NUVIO_MPV") ?: settingEnabled
    }

    /**
     * The EGL renderer follows the same choice. `NUVIO_EGL` overrides it on its own so the EGL
     * seam can still be exercised without mpv.
     */
    val eglEnabled: Boolean by lazy {
        envOverride("NUVIO_EGL") ?: mpvEnabled
    }

    /** For the startup log — which of the three inputs actually decided it. */
    fun describe(): String = when {
        System.getenv("NUVIO_MPV") != null -> "NUVIO_MPV=${System.getenv("NUVIO_MPV")}"
        settingEnabled -> "enabled in Settings"
        else -> "off (default)"
    }
}
