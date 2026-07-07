package com.nuvio.app

import com.nuvio.app.features.player.PlayerControlBridge
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import javax.imageio.ImageIO

/**
 * Optional system tray icon (opt-in, machine-local setting, applied at startup).
 *
 * Uses java.awt.SystemTray with a PopupMenu — a stable JDK API that works on trays with an
 * XEmbed/StatusNotifier host (Cinnamon, KDE, XFCE, MATE, and GNOME with an app-indicator
 * extension). If the platform has no tray, install() fails silently and the app runs normally.
 */
internal object DesktopTray {
    private const val TAG = "NuvioTray"
    private var trayIcon: TrayIcon? = null

    fun install() {
        if (trayIcon != null) return
        runCatching {
            if (!SystemTray.isSupported()) {
                println("$TAG: system tray not supported on this platform")
                return
            }
            val image = DesktopTray::class.java.getResourceAsStream("/nuvio-icon.png")?.use { ImageIO.read(it) }
                ?: run { println("$TAG: tray icon resource missing"); return }

            val popup = PopupMenu().apply {
                add(MenuItem("Show Nuvio").apply { addActionListener { DesktopWindowState.bringToFront?.invoke() } })
                add(MenuItem("Play / Pause").apply {
                    addActionListener {
                        PlayerControlBridge.controller?.let { c ->
                            if (PlayerControlBridge.isPlaying) c.pause() else c.play()
                        }
                    }
                })
                addSeparator()
                add(MenuItem("Quit").apply { addActionListener { DesktopWindowState.requestExit?.invoke() } })
            }

            val icon = TrayIcon(image, "Nuvio", popup).apply {
                isImageAutoSize = true
                // Double-click / primary activate brings the window forward.
                addActionListener { DesktopWindowState.bringToFront?.invoke() }
            }
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
            println("$TAG: tray icon installed")
        }.onFailure { e ->
            println("$TAG: failed to install tray icon: ${e.message}")
        }
    }

    fun remove() {
        val icon = trayIcon ?: return
        trayIcon = null
        runCatching { SystemTray.getSystemTray().remove(icon) }
    }
}
