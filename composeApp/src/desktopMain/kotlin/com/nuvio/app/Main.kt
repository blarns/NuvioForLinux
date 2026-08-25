package com.nuvio.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.nuvio.app.features.player.PlayerSettingsRepository
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.nuvio.app.features.player.ExternalOpenRequestStore
import com.nuvio.app.features.player.SleepTimerController
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.desktop.DesktopEngineFlags
import com.nuvio.app.desktop.DesktopLegacyPrefsMigration
import com.nuvio.app.desktop.egl.EglRenderer
import com.nuvio.app.features.player.DesktopScreenshot
import com.nuvio.app.features.player.PlayerControlBridge
import com.nuvio.app.features.player.PlayerLaunchStore
import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.settings.AppIconRepository
import com.nuvio.app.features.settings.AppLanguage
import com.nuvio.app.features.settings.ThemeSettingsStorage

fun main(args: Array<String>) {
    // A magnet: or http(s): URL passed on the command line (also how the browser / file
    // manager hands off via the x-scheme-handler association) is stashed for the UI to
    // open once a profile is active.
    args.firstOrNull()?.let { parseExternalOpenArg(it) }?.let { ExternalOpenRequestStore.set(it) }
    // One-time legacy java.util.prefs → ~/.config/nuvio migration. MUST stay the first
    // statement: nothing may read a DesktopStorage store before this runs.
    DesktopLegacyPrefsMigration.runIfNeeded()
    // Fork-only store: window geometry is machine-local; the official client ignores it.
    val windowStore = DesktopStorage.store("nuvio_window")
    // Apply the saved app language before any Compose UI composes so bundled string
    // resources resolve to the selected locale. Global (not profile-scoped), so safe this early.
    ThemeSettingsStorage.applySelectedAppLanguage(
        ThemeSettingsStorage.loadSelectedAppLanguage() ?: AppLanguage.ENGLISH.code,
    )
    // Opt-in (Settings → Playback → Linux desktop, or NUVIO_EGL=1): run Compose's renderer on
    // EGL rather than skiko's GLX, which is what lets libmpv hardware-decode 4K zero-copy into
    // the same GL context later. MUST be before application {} — Compose builds and realises its
    // SkiaLayer internally and there is no hook afterwards, which is also why the setting only
    // takes effect on the next launch. A no-op when disabled, and every failure path falls back
    // to stock skiko, so the default build renders exactly as before.
    println("[nuvio] libmpv player engine: ${DesktopEngineFlags.describe()}")
    EglRenderer.install()
    // Before application {} so the very first window already carries the chosen icon.
    AppIconRepository.ensureLoaded()

    application {
    System.setProperty("compose.interop.blending", "true")
    val mediaTitle by PlayerLaunchStore.currentTitle.collectAsState()
    val windowTitle = if (mediaTitle != null) "Nuvio — $mediaTitle" else "Nuvio"
    // Window icon follows the Appearance > App Icon choice. Loaded the fork's way — synchronous
    // useResource rather than compose-resources painterResource — so the icon is present on the
    // very first frame instead of arriving a composition later, and so an unreadable file falls
    // back to the shipped default instead of throwing.
    val appIconState by AppIconRepository.state.collectAsState()
    val appIcon = remember(appIconState.selected, appIconState.blackBackground) {
        val suffix = if (appIconState.blackBackground) "" else "-transparent"
        val selectedPath = "icons/app-icon-${appIconState.selected.key}$suffix.png"
        runCatching { BitmapPainter(useResource(selectedPath, ::loadImageBitmap)) }.getOrNull()
            ?: runCatching { BitmapPainter(useResource("nuvio-icon.png", ::loadImageBitmap)) }.getOrNull()
    }
    // Size the window from the screen it is actually starting on. A hardcoded default read as
    // "the window is smaller than my screen" on anything bigger than 1280x720, and a size
    // restored from a larger monitor could leave the title bar off screen — issues/4.
    val initialSize = remember {
        val screen = DesktopWindowGeometry.usableScreen()
        val saved = windowStore.getFloat("width")?.let { width ->
            windowStore.getFloat("height")?.let { height -> DesktopWindowGeometry.Size(width, height) }
        }
        DesktopWindowGeometry.resolve(saved, screen).also {
            DesktopWindowGeometry.logDisplayInfo(it, screen)
        }
    }
    val windowState = rememberWindowState(
        placement = if (windowStore.getBoolean("maximized") == true) {
            WindowPlacement.Maximized
        } else {
            WindowPlacement.Floating
        },
        width = initialSize.width.dp,
        height = initialSize.height.dp,
    )
    DesktopWindowState.toggleFullscreen = {
        windowState.placement = if (windowState.placement == WindowPlacement.Fullscreen)
            WindowPlacement.Floating else WindowPlacement.Fullscreen
    }
    val mpris = runCatching {
        startMpris2(
            onPlay  = { PlayerControlBridge.controller?.play() },
            onPause = { PlayerControlBridge.controller?.pause() },
            onStop  = { PlayerControlBridge.controller?.pause() },
        )
    }.getOrNull()
    // Let the sleep timer pause the active player.
    SleepTimerController.pauseAction = { PlayerControlBridge.controller?.pause() }
    val sleepTimerState by SleepTimerController.state.collectAsState()
    val playerSettingsUiState by remember {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.uiState
    }.collectAsState()
    // Full shutdown: flush watch progress, tear down MPRIS/Discord/tray, persist geometry.
    // Both the window close button and the tray's Quit item go through this — the tray used
    // to call exitApplication() directly, which skipped all of it and lost the position of
    // whatever was playing.
    val shutdownAndExit: () -> Unit = {
        PlayerControlBridge.flushProgress?.invoke()
        // D-Bus teardown can block for seconds; run it on a daemon thread so the
        // AWT EDT (and thus the window close) is never blocked by MPRIS cleanup.
        val m = mpris
        if (m != null) Thread(null, { try { m.close() } catch (_: Exception) {} }, "mpris-close", 0).also {
            it.isDaemon = true
            it.start()
        }
        // Clear and disconnect the Discord presence (no-op when the feature was inert).
        com.nuvio.app.features.discord.DiscordRichPresence.shutdown()
        // Ask the P2P engine to stop its TorrServer subprocess gracefully. This is best-effort
        // and asynchronous, which is why TorrServerBinary also registers a JVM shutdown hook —
        // between the two, quitting can never leave a torrent daemon seeding in the background.
        runCatching { com.nuvio.app.features.p2p.P2pStreamingEngine.shutdown() }
        DesktopTray.remove()
        // WindowState.size tracks the live window, so while maximised or fullscreen it reports
        // the screen size. Persist the placement separately and only write a size back when the
        // window is floating, otherwise quitting from fullscreen locks that size in forever.
        windowStore.putBoolean("maximized", windowState.placement == WindowPlacement.Maximized)
        if (windowState.placement == WindowPlacement.Floating) {
            val width = windowState.size.width.value
            val height = windowState.size.height.value
            if (DesktopWindowGeometry.isPersistable(width, height)) {
                windowStore.putFloat("width", width)
                windowStore.putFloat("height", height)
            }
        }
        exitApplication()
    }
    // System tray (opt-in, applied at startup). Tray actions route through DesktopWindowState.
    DesktopWindowState.requestExit = shutdownAndExit
    LaunchedEffect(Unit) {
        if (PlayerSettingsStorage.loadTrayIconEnabled() == true) DesktopTray.install()
    }
    Window(
        onCloseRequest = shutdownAndExit,
        onKeyEvent = { keyEvent ->
            if (keyEvent.type != KeyEventType.KeyDown) return@Window false
            val ctrl = PlayerControlBridge.controller ?: return@Window false
            when (keyEvent.key) {
                Key.Spacebar -> { if (PlayerControlBridge.isPlaying) ctrl.pause() else ctrl.play(); true }
                Key.DirectionLeft  -> { ctrl.seekBy(-10_000L); true }
                Key.DirectionRight -> { ctrl.seekBy(+10_000L); true }
                Key.DirectionUp    -> { ctrl.currentVolume()?.let { ctrl.setVolume((it.fraction + 0.05f).coerceAtMost(1f)) }; true }
                Key.DirectionDown  -> { ctrl.currentVolume()?.let { ctrl.setVolume((it.fraction - 0.05f).coerceAtLeast(0f)) }; true }
                Key.M -> { ctrl.currentVolume()?.let { ctrl.setVolume(if (it.isMuted) 0.5f else 0f) }; true }
                Key.F -> { DesktopWindowState.toggleFullscreen?.invoke(); true }
                Key.S -> { DesktopScreenshot.capture(); true }
                else -> false
            }
        },
        title = windowTitle,
        icon = appIcon,
        state = windowState,
    ) {
        // Bring-to-front for the tray "Show" action (window is the ComposeWindow/AWT frame).
        DesktopWindowState.bringToFront = {
            window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
            window.toFront()
            window.requestFocus()
        }
        // The menu bar is native window chrome: it renders in the system theme (a white strip
        // against Nuvio's dark UI on most setups) and stayed on screen in fullscreen, over the
        // video, with no way to dismiss it — github.com/blarns/NuvioForLinux/issues/3.
        // It is now hidden whenever the window is fullscreen, and can be turned off entirely.
        val showMenuBar = playerSettingsUiState.menuBarEnabled &&
            windowState.placement != WindowPlacement.Fullscreen
        if (showMenuBar) {
        MenuBar {
            Menu("Playback", mnemonic = 'P') {
                Menu("Sleep timer") {
                    RadioButtonItem(
                        text = "Off",
                        selected = sleepTimerState is SleepTimerController.State.Off,
                        onClick = { SleepTimerController.cancel() },
                    )
                    SleepTimerController.presetMinutes.forEach { minutes ->
                        RadioButtonItem(
                            text = "$minutes minutes",
                            selected = (sleepTimerState as? SleepTimerController.State.Minutes)?.minutes == minutes,
                            onClick = { SleepTimerController.startMinutes(minutes) },
                        )
                    }
                    RadioButtonItem(
                        text = "After this episode",
                        selected = sleepTimerState is SleepTimerController.State.AfterEpisode,
                        onClick = { SleepTimerController.startAfterEpisode() },
                    )
                }
            }
        }
        }
        App()
    }
    }
}

private fun decodeUrlComponent(value: String): String =
    runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

/** Parse a CLI/browser-handoff argument into an open request, or null if unsupported. */
private fun parseExternalOpenArg(arg: String): ExternalOpenRequestStore.Request? {
    val trimmed = arg.trim()
    return when {
        trimmed.startsWith("magnet:", ignoreCase = true) -> {
            val params = trimmed.substringAfter('?', "").split('&')
            val infoHash = params.firstOrNull { it.startsWith("xt=urn:btih:", ignoreCase = true) }
                ?.substringAfter("xt=urn:btih:", "")?.trim()?.lowercase()
            val name = params.firstOrNull { it.startsWith("dn=", ignoreCase = true) }
                ?.substringAfter("dn=", "")?.let { decodeUrlComponent(it) }?.takeIf { it.isNotBlank() }
            if (infoHash.isNullOrBlank()) null
            else ExternalOpenRequestStore.Request(
                kind = ExternalOpenRequestStore.Kind.MAGNET,
                url = trimmed,
                title = name ?: "Torrent",
                infoHash = infoHash,
            )
        }
        trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) -> {
            val name = trimmed.substringAfterLast('/', "").substringBefore('?')
                .let { decodeUrlComponent(it) }.takeIf { it.isNotBlank() } ?: "Stream"
            ExternalOpenRequestStore.Request(
                kind = ExternalOpenRequestStore.Kind.HTTP,
                url = trimmed,
                title = name,
            )
        }
        else -> null
    }
}
