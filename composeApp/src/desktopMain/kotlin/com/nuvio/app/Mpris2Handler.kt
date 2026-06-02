package com.nuvio.app

import org.freedesktop.dbus.annotations.DBusInterfaceName
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
}

class NuvioMpris2(
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onStop: () -> Unit,
) : MediaPlayer2Root, MediaPlayer2Player, Properties {
    override fun getObjectPath() = "/org/mpris/MediaPlayer2"
    override fun Raise() {}
    override fun Quit() {}
    override fun Play() = onPlay()
    override fun Pause() = onPause()
    override fun PlayPause() {}
    override fun Stop() = onStop()
    override fun Next() {}
    override fun Previous() {}
    override fun Seek(offset: Long) {}

    @Suppress("UNCHECKED_CAST")
    override fun <A> Get(interfaceName: String, propertyName: String): A = when (propertyName) {
        "PlaybackStatus" -> Variant("Playing") as A
        "CanPlay"  -> Variant(true) as A
        "CanPause" -> Variant(true) as A
        "CanSeek"  -> Variant(false) as A
        "CanControl" -> Variant(true) as A
        "Identity" -> Variant("Nuvio") as A
        "CanQuit"  -> Variant(false) as A
        "CanRaise" -> Variant(false) as A
        "HasTrackList" -> Variant(false) as A
        "DesktopEntry" -> Variant("nuvio") as A
        "SupportedUriSchemes" -> Variant(arrayOf("http", "https")) as A
        "SupportedMimeTypes"  -> Variant(emptyArray<String>()) as A
        "Metadata" -> Variant(emptyMap<String, Variant<*>>(), "a{sv}") as A
        "MinimumRate" -> Variant(1.0) as A
        "MaximumRate"  -> Variant(1.0) as A
        "Rate"  -> Variant(1.0) as A
        "Volume" -> Variant(1.0) as A
        "Position" -> Variant(0L) as A
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
        conn.exportObject(handler)
        conn
    }.getOrNull()
