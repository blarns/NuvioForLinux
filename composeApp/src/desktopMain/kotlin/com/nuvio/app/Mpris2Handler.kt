package com.nuvio.app

import com.nuvio.app.features.player.PlayerControlBridge
import com.nuvio.app.features.player.PlayerLaunchStore
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant

@DBusInterfaceName("org.mpris.MediaPlayer2")
interface MediaPlayer2Root : DBusInterface {
    fun Raise()
    fun Quit()
}

@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
interface MediaPlayer2Player : DBusInterface {
    fun Play()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Next()
    fun Previous()
    fun Seek(offset: Long)
    fun SetPosition(trackId: DBusPath, position: Long)
}

private const val MPRIS_PATH = "/org/mpris/MediaPlayer2"
private const val PLAYER_IFACE = "org.mpris.MediaPlayer2.Player"
private const val TRACK_PATH = "/org/nuvio/app/track/0"

class NuvioMpris2(
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onStop: () -> Unit,
) : MediaPlayer2Root, MediaPlayer2Player, Properties {

    @Volatile var connection: DBusConnection? = null
    // Signature of the last emitted metadata/status, so we only signal on real change.
    @Volatile private var lastSignature: String = ""

    override fun getObjectPath() = MPRIS_PATH
    override fun Raise() {}
    override fun Quit() {}
    override fun Play() = onPlay()
    override fun Pause() = onPause()
    override fun PlayPause() {
        if (PlayerControlBridge.isPlaying) onPause() else onPlay()
    }
    override fun Stop() = onStop()
    override fun Next() {}
    override fun Previous() {}

    // MPRIS positions are microseconds; the player controller works in milliseconds.
    override fun Seek(offset: Long) {
        PlayerControlBridge.controller?.seekBy(offset / 1000L)
    }
    override fun SetPosition(trackId: DBusPath, position: Long) {
        PlayerControlBridge.controller?.seekTo(position / 1000L)
    }

    private fun playbackStatus(): String = when {
        !PlayerControlBridge.hasMedia && PlayerControlBridge.controller == null -> "Stopped"
        PlayerControlBridge.isPlaying -> "Playing"
        else -> "Paused"
    }

    private fun metadata(): Map<String, Variant<*>> {
        val launch = PlayerLaunchStore.currentLaunch.value
            ?: return mapOf("mpris:trackid" to Variant(DBusPath(TRACK_PATH)))
        val title = buildString {
            append(launch.title.ifBlank { "Nuvio" })
            val s = launch.seasonNumber
            val e = launch.episodeNumber
            if (s != null && e != null) append(" · S${s}E${e}")
        }
        val artUrl = launch.poster ?: launch.episodeThumbnail ?: launch.background
        val lengthUs = PlayerControlBridge.durationMs.coerceAtLeast(0L) * 1000L
        return buildMap {
            put("mpris:trackid", Variant(DBusPath(TRACK_PATH)))
            put("mpris:length", Variant(lengthUs))
            put("xesam:title", Variant(title))
            put("xesam:artist", Variant(arrayOf("Nuvio")))
            if (!artUrl.isNullOrBlank()) put("mpris:artUrl", Variant(artUrl))
        }
    }

    /** Emit PropertiesChanged for Metadata + PlaybackStatus, but only when they changed. */
    fun notifyNowPlaying() {
        runCatching {
            val status = playbackStatus()
            val meta = metadata()
            val signature = status + "|" + (meta["xesam:title"]?.value ?: "") + "|" + PlayerControlBridge.durationMs
            if (signature == lastSignature) return
            lastSignature = signature
            val conn = connection ?: return
            val changed: Map<String, Variant<*>> = mapOf(
                "PlaybackStatus" to Variant(status),
                "Metadata" to Variant(meta, "a{sv}"),
            )
            conn.sendMessage(
                Properties.PropertiesChanged(MPRIS_PATH, PLAYER_IFACE, changed, emptyList()),
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <A> Get(interfaceName: String, propertyName: String): A = when (propertyName) {
        "PlaybackStatus" -> Variant(playbackStatus()) as A
        "CanPlay"  -> Variant(true) as A
        "CanPause" -> Variant(true) as A
        "CanSeek"  -> Variant(true) as A
        "CanControl" -> Variant(true) as A
        "CanGoNext" -> Variant(false) as A
        "CanGoPrevious" -> Variant(false) as A
        "Identity" -> Variant("Nuvio") as A
        "CanQuit"  -> Variant(false) as A
        "CanRaise" -> Variant(false) as A
        "HasTrackList" -> Variant(false) as A
        "DesktopEntry" -> Variant("nuvio") as A
        "SupportedUriSchemes" -> Variant(arrayOf("http", "https")) as A
        "SupportedMimeTypes"  -> Variant(emptyArray<String>()) as A
        "Metadata" -> Variant(metadata(), "a{sv}") as A
        "MinimumRate" -> Variant(1.0) as A
        "MaximumRate"  -> Variant(1.0) as A
        "Rate"  -> Variant(1.0) as A
        "Volume" -> Variant(1.0) as A
        "Position" -> Variant(PlayerControlBridge.positionMs.coerceAtLeast(0L) * 1000L) as A
        "LoopStatus" -> Variant("None") as A
        "Shuffle" -> Variant(false) as A
        else -> throw IllegalArgumentException("Unknown property: $propertyName")
    }
    override fun GetAll(interfaceName: String): Map<String, Variant<*>> = emptyMap()
    override fun <A> Set(interfaceName: String, propertyName: String, value: A) {}
    override fun isRemote() = false
}

fun startMpris2(onPlay: () -> Unit, onPause: () -> Unit, onStop: () -> Unit): AutoCloseable? =
    runCatching {
        val conn = DBusConnectionBuilder.forSessionBus().build()
        conn.requestBusName("org.mpris.MediaPlayer2.nuvio")
        val handler = NuvioMpris2(onPlay, onPause, onStop)
        handler.connection = conn
        conn.exportObject(handler)
        // Re-emit metadata when the desktop player reports a status/duration/title change.
        PlayerControlBridge.onNowPlayingChanged = { handler.notifyNowPlaying() }
        conn
    }.getOrNull()
