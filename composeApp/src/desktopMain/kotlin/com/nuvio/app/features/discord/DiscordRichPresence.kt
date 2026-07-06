package com.nuvio.app.features.discord

import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.PlayerLaunchStore
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

// ---------------------------------------------------------------------------
// DiscordRichPresence — opt-in "Watching <title> · S<x>E<y>" status in Discord.
//
// Pure-JDK Discord IPC: the local Discord client exposes a Unix domain socket
// ($XDG_RUNTIME_DIR/discord-ipc-N and flatpak/snap variants). We talk the raw
// framed JSON protocol over it (little-endian int32 opcode + int32 length +
// UTF-8 JSON), so no third-party RPC library is needed.
//
// Modelled on ScreensaverInhibitor: a @Volatile `unavailable` latch, all socket
// I/O on a single dedicated daemon thread, idempotent/debounced calls driven at
// ~10Hz from the player poll, and it NEVER throws into the caller. If no client
// id resolves, or unix domain sockets aren't supported, the singleton latches
// inert and does nothing.
// ---------------------------------------------------------------------------

internal object DiscordRichPresence {
    private const val ITAG = "NuvioDiscordRPC"
    private const val MAX_FAILURES = 10          // give up for the session after this many I/O failures
    private const val SEEK_RESEND_THRESHOLD_S = 5L

    private val lock = Any()
    private val pid: Long = runCatching { ProcessHandle.current().pid() }.getOrDefault(0L)

    @Volatile private var unavailable = false    // latched once we know we can't present here
    @Volatile private var started = false        // true once the worker thread has been engaged

    // Resolved lazily, once. null means "no client id configured" → feature inert.
    @Volatile private var clientIdResolved = false
    @Volatile private var cachedClientId: String? = null

    // Debounce state — guarded by `lock`, touched from the caller thread.
    private var lastDesired: DesiredPresence? = null

    // Worker-thread-only state (single-thread executor → no synchronization needed).
    private var channel: SocketChannel? = null
    private var failures = 0

    private val executor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "discord-rpc").apply { isDaemon = true }
        }
    }

    /** Called ~10x/sec from the player poll while the setting is enabled. Cheap; debounced. */
    fun update(snap: PlayerPlaybackSnapshot) {
        if (unavailable) return
        if (resolveClientId() == null) {
            unavailable = true   // nothing configured — stay inert for the session
            return
        }
        val launch = PlayerLaunchStore.currentLaunch.value ?: return
        val desired = buildPresence(launch, snap) ?: return
        synchronized(lock) {
            val prev = lastDesired
            if (prev != null && !shouldResend(prev, desired)) return
            lastDesired = desired
        }
        started = true
        submit { sendActivity(desired) }
    }

    /** Clear the presence (playback stopped / surface torn down). Safe to call anytime. */
    fun clear() {
        if (!started) return
        synchronized(lock) { lastDesired = null }
        submit { clearActivity() }
    }

    /** Final teardown on app exit — clear the presence and drop the socket. Non-blocking. */
    fun shutdown() {
        if (!started) return
        submit {
            clearActivity()
            closeChannel()
        }
        runCatching { executor.shutdown() }
    }

    // ---- caller-thread helpers ------------------------------------------------

    private fun submit(task: () -> Unit) {
        try {
            executor.submit { runCatching { task() } }
        } catch (_: Exception) {
            // executor rejected (shutting down) — ignore, presence is best-effort
        }
    }

    private fun resolveClientId(): String? {
        if (clientIdResolved) return cachedClientId
        synchronized(lock) {
            if (clientIdResolved) return cachedClientId
            cachedClientId = computeClientId()
            clientIdResolved = true
        }
        return cachedClientId
    }

    private fun computeClientId(): String? {
        DiscordConfig.CLIENT_ID.trim().takeIf { it.isNotEmpty() }?.let { return it }
        System.getenv("NUVIO_DISCORD_CLIENT_ID")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return runCatching {
            val home = System.getProperty("user.home").orEmpty()
            val file = Paths.get(home, ".config", "nuvio", "discord-client-id")
            if (Files.exists(file)) {
                Files.readAllLines(file).firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            } else null
        }.getOrNull()
    }

    private fun buildPresence(launch: PlayerLaunch, snap: PlayerPlaybackSnapshot): DesiredPresence? {
        if (snap.isEnded) return null
        val title = launch.title.ifBlank { launch.streamTitle }.trim()
        if (title.length < 2) return null   // Discord requires details/state length >= 2
        val season = launch.seasonNumber
        val episode = launch.episodeNumber
        val state = if (season != null && episode != null) "S${season}E${episode}" else "Movie"
        val start = if (snap.isPlaying) {
            (System.currentTimeMillis() - snap.positionMs.coerceAtLeast(0L)) / 1000L
        } else 0L
        return DesiredPresence(
            details = title,
            state = state,
            largeImage = launch.poster?.takeIf { it.startsWith("http") },
            largeText = title,
            startEpochSec = start,
            playing = snap.isPlaying,
        )
    }

    // Only push a new SET_ACTIVITY when something the user would actually see changed,
    // or the elapsed anchor jumped by more than a few seconds (a seek) — never 10x/sec.
    private fun shouldResend(prev: DesiredPresence, next: DesiredPresence): Boolean =
        prev.details != next.details ||
            prev.state != next.state ||
            prev.largeImage != next.largeImage ||
            prev.playing != next.playing ||
            abs(prev.startEpochSec - next.startEpochSec) > SEEK_RESEND_THRESHOLD_S

    // ---- worker-thread helpers ------------------------------------------------

    private fun sendActivity(desired: DesiredPresence) {
        if (unavailable) return
        val ch = ensureConnected() ?: return
        try {
            writeFrame(ch, OP_FRAME, activityPayload(desired))
            if (!readFrame(ch)) { closeChannel(); noteFailure(); return }
            failures = 0
        } catch (_: Exception) {
            closeChannel()
            noteFailure()
        }
    }

    private fun clearActivity() {
        val ch = channel ?: return   // never connected → nothing is showing
        try {
            writeFrame(ch, OP_FRAME, clearPayload())
            readFrame(ch)
        } catch (_: Exception) {
            closeChannel()
        }
    }

    private fun ensureConnected(): SocketChannel? {
        channel?.let { if (it.isOpen) return it }
        channel = null
        val clientId = resolveClientId() ?: return null
        val path = discoverSocket() ?: run { noteFailure(); return null }
        return try {
            val ch = SocketChannel.open(UnixDomainSocketAddress.of(path))
            writeFrame(ch, OP_HANDSHAKE, handshakePayload(clientId))
            if (!readFrame(ch)) {
                runCatching { ch.close() }
                noteFailure()
                return null
            }
            channel = ch
            failures = 0
            println("$ITAG: connected ($path)")
            ch
        } catch (_: UnsupportedOperationException) {
            unavailable = true
            println("$ITAG: unix domain sockets unsupported; Discord presence disabled")
            null
        } catch (_: Exception) {
            noteFailure()
            null
        }
    }

    private fun noteFailure() {
        if (++failures >= MAX_FAILURES) {
            unavailable = true
            println("$ITAG: giving up after $failures failures (is Discord running?)")
        }
    }

    private fun closeChannel() {
        val ch = channel ?: return
        channel = null
        runCatching { ch.close() }
    }

    private fun discoverSocket(): Path? {
        val bases = mutableListOf<Path>()
        System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }?.let { bases.add(Paths.get(it)) }
        if (bases.isEmpty()) {
            // XDG_RUNTIME_DIR unset — derive the per-user runtime dir from our own uid.
            runCatching {
                val uid = Files.getAttribute(Paths.get("/proc/self"), "unix:uid") as Int
                bases.add(Paths.get("/run/user/$uid"))
            }
        }
        val subdirs = listOf("", "app/com.discordapp.Discord", "snap.discord")
        for (base in bases) {
            for (sub in subdirs) {
                val dir = if (sub.isEmpty()) base else base.resolve(sub)
                for (i in 0..9) {
                    val candidate = dir.resolve("discord-ipc-$i")
                    if (Files.exists(candidate)) return candidate
                }
            }
        }
        return null
    }

    // ---- framed JSON protocol -------------------------------------------------

    private const val OP_HANDSHAKE = 0
    private const val OP_FRAME = 1
    private const val OP_CLOSE = 2

    private fun handshakePayload(clientId: String): String = buildJsonObject {
        put("v", 1)
        put("client_id", clientId)
    }.toString()

    private fun activityPayload(d: DesiredPresence): String = buildJsonObject {
        put("cmd", "SET_ACTIVITY")
        putJsonObject("args") {
            put("pid", pid)
            putJsonObject("activity") {
                put("details", d.details)
                put("state", d.state)
                if (d.playing && d.startEpochSec > 0L) {
                    putJsonObject("timestamps") { put("start", d.startEpochSec) }
                }
                if (d.largeImage != null) {
                    putJsonObject("assets") {
                        put("large_image", d.largeImage)
                        put("large_text", d.largeText)
                    }
                }
            }
        }
        put("nonce", UUID.randomUUID().toString())
    }.toString()

    private fun clearPayload(): String = buildJsonObject {
        put("cmd", "SET_ACTIVITY")
        putJsonObject("args") {
            put("pid", pid)
            put("activity", JsonNull)
        }
        put("nonce", UUID.randomUUID().toString())
    }.toString()

    private fun writeFrame(ch: SocketChannel, op: Int, payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(8 + bytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(op)
        buf.putInt(bytes.size)
        buf.put(bytes)
        buf.flip()
        while (buf.hasRemaining()) ch.write(buf)
    }

    /** Reads one frame; returns false on EOF/oversized/CLOSE so the caller reconnects. */
    private fun readFrame(ch: SocketChannel): Boolean {
        val header = readFully(ch, 8) ?: return false
        header.order(ByteOrder.LITTLE_ENDIAN)
        val op = header.getInt(0)
        val len = header.getInt(4)
        if (len < 0 || len > (1 shl 20)) return false
        if (len > 0 && readFully(ch, len) == null) return false
        return op != OP_CLOSE
    }

    private fun readFully(ch: SocketChannel, n: Int): ByteBuffer? {
        val buf = ByteBuffer.allocate(n)
        while (buf.hasRemaining()) {
            if (ch.read(buf) < 0) return null   // peer closed
        }
        buf.flip()
        return buf
    }

    private data class DesiredPresence(
        val details: String,
        val state: String,
        val largeImage: String?,
        val largeText: String,
        val startEpochSec: Long,
        val playing: Boolean,
    )
}
