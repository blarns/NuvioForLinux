package com.nuvio.app.features.p2p

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------------------
// Desktop P2P engine — a port of the Android actual. It launches the bundled
// TorrServer (a Go torrent-streaming daemon) as a subprocess on 127.0.0.1, drives
// it over HTTP, and hands the player a plain /stream URL (VLCJ plays it like any
// other source). HTTP via java.net.http (no OkHttp), JSON via kotlinx.serialization
// (no org.json), paths resolved for Linux/XDG (no Android Context).
//
// Gated by AppFeaturePolicy.desktop.p2pEnabled + the P2P consent toggle, both OFF
// by default — this code only runs once a user explicitly opts in.
// ---------------------------------------------------------------------------

private const val TAG = "P2pStreamingEngine"
private const val IDLE_TORRENT_TTL_MS = 120_000L
private const val FILE_INDEX_METADATA_TIMEOUT_MS = 15_000L
private const val FILE_INDEX_FAST_VALIDATION_TIMEOUT_MS = 10_000L
private const val FILE_INDEX_POLL_INTERVAL_MS = 250L
private const val STREAMING_CACHE_SIZE_BYTES = 128L * 1024L * 1024L
private const val STREAMING_CONNECTION_LIMIT = 160L
private const val STREAMING_HALF_OPEN_CONNECTION_LIMIT = 120L
private const val STREAMING_TOTAL_HALF_OPEN_CONNECTION_LIMIT = 500L
private const val STREAMING_PEERS_HIGH_WATER = 900L
private const val STREAMING_PEERS_LOW_WATER = 120L
private const val STREAMING_NOMINAL_DIAL_TIMEOUT_MS = 8_000L
private const val STREAMING_MIN_DIAL_TIMEOUT_MS = 1_500L
private const val STREAMING_HANDSHAKE_TIMEOUT_MS = 3_000L
private const val STREAMING_DISCONNECT_TIMEOUT_SECONDS = 120L
private const val STREAMING_READ_AHEAD_PERCENT = 95L
private const val STREAMING_PRELOAD_CACHE_PERCENT = 50L
private const val STREAM_SCREEN_WARMUP_COOLDOWN_MS = 10_000L
private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "avi", "webm", "ts", "m4v", "mov", "wmv", "flv")

private fun logw(message: String, error: Throwable? = null) {
    println("$TAG: $message" + (error?.let { " — ${it.message}" } ?: ""))
}

actual object P2pStreamingEngine {
    private val _state = MutableStateFlow<P2pStreamingState>(P2pStreamingState.Idle)
    actual val state: StateFlow<P2pStreamingState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()
    private var statsJob: Job? = null
    private var preloadJob: Job? = null
    private var warmupJob: Job? = null
    private var warmupCooldownJob: Job? = null
    private var currentHash: String? = null
    private var streamGeneration = 0L
    private val idleDropJobs = mutableMapOf<String, Job>()
    private val binary = TorrServerBinary()
    private val api = TorrServerApi(binary)

    actual fun warmup() {
        synchronized(lifecycleLock) {
            warmupCooldownJob?.cancel()
            warmupCooldownJob = null
            if (warmupJob?.isActive == true) return
            warmupJob = scope.launch {
                try {
                    binary.start()
                    if (api.ensureStreamingSettings().changed) {
                        binary.stop()
                        binary.start()
                        api.ensureStreamingSettings()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logw("TorrServer warmup failed", e)
                }
            }
        }
    }

    actual fun cooldownWarmup() {
        synchronized(lifecycleLock) {
            if (currentHash != null) {
                return
            }
            warmupCooldownJob?.cancel()
            warmupCooldownJob = scope.launch {
                delay(STREAM_SCREEN_WARMUP_COOLDOWN_MS)
                val warmup = synchronized(lifecycleLock) {
                    if (currentHash != null) {
                        warmupCooldownJob = null
                        return@launch
                    }
                    val job = warmupJob
                    warmupJob = null
                    warmupCooldownJob = null
                    job
                }
                try {
                    warmup?.join()
                } catch (_: CancellationException) {
                }
                val shouldStop = synchronized(lifecycleLock) { currentHash == null }
                if (!shouldStop) {
                    return@launch
                }
                try {
                    binary.stop()
                } catch (e: Exception) {
                    logw("Failed to stop idle TorrServer", e)
                }
            }
        }
    }

    actual suspend fun startStream(request: P2pStreamRequest): String = withContext(Dispatchers.IO) {
        val requestedHash = request.infoHash.trim().takeIf { it.isNotEmpty() }
            ?: throw P2pStreamingException("Missing torrent info hash")
        val detached = beginStreamGeneration()
        val generation = detached.generation
        detached.hash?.let(::scheduleIdleDrop)
        _state.value = P2pStreamingState.Connecting

        var attachedHash: String? = null
        try {
            cancelWarmupCooldown()
            awaitWarmup()
            binary.start()
            ensureCurrentGeneration(generation)
            val settingsResult = api.ensureStreamingSettings()
            if (settingsResult.changed) {
                binary.stop()
                binary.start()
                api.ensureStreamingSettings()
            }
            ensureCurrentGeneration(generation)

            val magnetLink = buildMagnetUri(
                infoHash = requestedHash,
                magnetUri = request.magnetUri,
                extraTrackers = request.trackers,
            )

            val hash = api.addTorrent(magnetLink)
                ?: throw P2pStreamingException("Failed to add torrent")
            attachedHash = hash
            cancelIdleDrop(hash)
            if (!attachTorrentIfCurrent(generation, hash)) {
                scheduleIdleDrop(hash)
                throw CancellationException("P2P stream start was cancelled")
            }

            val requestedName = request.filename?.trim()?.takeIf { it.isNotEmpty() }
            val useEngineFileSelector = requestedName != null || request.fileIdx != null
            val resolvedIdx = if (useEngineFileSelector) {
                null
            } else {
                resolveFileIndex(
                    hash = hash,
                    requestedIdx = request.fileIdx,
                    filename = request.filename,
                )
            }
            ensureCurrentGeneration(generation)

            val streamSelector = TorrServerStreamSelector(
                legacyIndex = resolvedIdx,
                fileIdx = request.fileIdx,
                filename = requestedName,
            )
            val streamUrl = api.getStreamUrl(
                magnetLink = magnetLink,
                selector = streamSelector,
            )

            startPreload(
                hash = hash,
                generation = generation,
                magnetLink = magnetLink,
                selector = streamSelector,
            )
            startStatsPolling(
                hash = hash,
                generation = generation,
            )

            ensureCurrentGeneration(generation)
            _state.value = P2pStreamingState.Streaming(
                localUrl = streamUrl,
                downloadSpeed = 0,
                uploadSpeed = 0,
                peers = 0,
                seeds = 0,
                bufferProgress = 0f,
                totalProgress = 0f,
            )

            streamUrl
        } catch (e: CancellationException) {
            attachedHash?.takeUnless(::isHashCurrent)?.let(::scheduleIdleDrop)
            throw e
        } catch (e: Exception) {
            logw("Failed to start P2P stream", e)
            attachedHash?.takeUnless(::isHashCurrent)?.let(::scheduleIdleDrop)
            if (isCurrentGeneration(generation)) {
                _state.value = P2pStreamingState.Error(e.message ?: "Unknown torrent error")
            }
            throw e
        }
    }

    actual fun stopStream() {
        detachActiveStream()?.let(::scheduleIdleDrop)
    }

    actual fun shutdown() {
        val hash = detachActiveStream()
        val idleHashes = cancelScheduledIdleDrops()
        val warmup = synchronized(lifecycleLock) {
            warmupCooldownJob?.cancel()
            warmupCooldownJob = null
            val job = warmupJob
            warmupJob = null
            job
        }
        warmup?.cancel()
        scope.launch {
            try {
                warmup?.join()
            } catch (_: CancellationException) {
            }

            (listOfNotNull(hash) + idleHashes)
                .distinctBy { hashKey(it) }
                .forEach {
                    try {
                        api.dropTorrent(it)
                    } catch (e: Exception) {
                        logw("Error dropping torrent", e)
                    }
                }

            try {
                binary.stop()
            } catch (e: Exception) {
                logw("Error stopping TorrServer", e)
            }
        }
    }

    private data class DetachedStream(
        val generation: Long,
        val hash: String?,
        val statsJob: Job?,
        val preloadJob: Job?,
    )

    private fun beginStreamGeneration(): DetachedStream {
        val detached = synchronized(lifecycleLock) {
            streamGeneration += 1
            val detached = DetachedStream(
                generation = streamGeneration,
                hash = currentHash,
                statsJob = statsJob,
                preloadJob = preloadJob,
            )
            currentHash = null
            statsJob = null
            preloadJob = null
            detached
        }
        detached.statsJob?.cancel()
        detached.preloadJob?.cancel()
        return detached
    }

    private fun detachActiveStream(): String? {
        val detached = beginStreamGeneration()
        _state.value = P2pStreamingState.Idle
        return detached.hash
    }

    private suspend fun awaitWarmup() {
        val job = synchronized(lifecycleLock) { warmupJob?.takeIf { it.isActive } }
        job?.join()
    }

    private fun cancelWarmupCooldown() {
        synchronized(lifecycleLock) {
            warmupCooldownJob?.cancel()
            warmupCooldownJob = null
        }
    }

    private fun attachTorrentIfCurrent(generation: Long, hash: String): Boolean =
        synchronized(lifecycleLock) {
            if (streamGeneration != generation) return@synchronized false
            currentHash = hash
            true
        }

    private fun isCurrentGeneration(generation: Long): Boolean =
        synchronized(lifecycleLock) { streamGeneration == generation }

    private fun ensureCurrentGeneration(generation: Long) {
        if (!isCurrentGeneration(generation)) {
            throw CancellationException("P2P stream start was cancelled")
        }
    }

    private fun isHashCurrent(hash: String): Boolean =
        synchronized(lifecycleLock) { hashMatches(currentHash, hash) }

    private fun scheduleIdleDrop(hash: String, delayMs: Long = IDLE_TORRENT_TTL_MS) {
        val key = hashKey(hash)
        if (key.isBlank()) return
        val job = scope.launch {
            delay(delayMs)
            val shouldDrop = synchronized(lifecycleLock) {
                idleDropJobs.remove(key)
                !hashMatches(currentHash, hash)
            }
            if (shouldDrop) {
                api.dropTorrent(hash)
            }
        }
        synchronized(lifecycleLock) {
            if (hashMatches(currentHash, hash)) {
                job.cancel()
                return
            }
            idleDropJobs.remove(key)?.cancel()
            idleDropJobs[key] = job
        }
    }

    private fun cancelIdleDrop(hash: String) {
        synchronized(lifecycleLock) {
            idleDropJobs.remove(hashKey(hash))?.cancel()
        }
    }

    private fun cancelScheduledIdleDrops(): List<String> =
        synchronized(lifecycleLock) {
            val hashes = idleDropJobs.keys.toList()
            idleDropJobs.values.forEach { it.cancel() }
            idleDropJobs.clear()
            hashes
        }

    private fun hashMatches(left: String?, right: String?): Boolean {
        if (left.isNullOrBlank() || right.isNullOrBlank()) return false
        return hashKey(left) == hashKey(right)
    }

    private fun hashKey(hash: String): String =
        hash.trim().lowercase(Locale.US)

    private fun buildMagnetUri(
        infoHash: String,
        magnetUri: String?,
        extraTrackers: List<String>,
    ): String {
        val parsedMagnet = parseMagnetUri(magnetUri)
        val trackers = (DEFAULT_TRACKERS + parsedMagnet.trackers + extraTrackers)
            .asSequence()
            .mapNotNull(::normalizeTracker)
            .distinctBy { it.lowercase(Locale.US) }
            .toList()

        return buildString {
            append("magnet:?xt=urn:btih:")
            append(infoHash.trim())
            parsedMagnet.passthroughParams.forEach { param ->
                append('&')
                append(param)
            }
            trackers.forEach { tracker ->
                append("&tr=")
                append(URLEncoder.encode(tracker, "UTF-8"))
            }
        }
    }

    private data class ParsedMagnet(
        val trackers: List<String>,
        val passthroughParams: List<String>,
    )

    private fun parseMagnetUri(magnetUri: String?): ParsedMagnet {
        val raw = magnetUri
            ?.trim()
            ?.takeIf { it.startsWith("magnet:", ignoreCase = true) }
            ?: return ParsedMagnet(trackers = emptyList(), passthroughParams = emptyList())
        val query = raw.substringAfter('?', missingDelimiterValue = "")
        if (query.isBlank()) return ParsedMagnet(trackers = emptyList(), passthroughParams = emptyList())

        val trackers = mutableListOf<String>()
        val passthroughParams = mutableListOf<String>()
        query.split('&')
            .filter { it.isNotBlank() }
            .forEach { param ->
                val key = param.substringBefore('=').lowercase(Locale.US)
                when (key) {
                    "tr" -> trackers += decodeQueryValue(param.substringAfter('=', missingDelimiterValue = ""))
                    "xt" -> Unit
                    else -> passthroughParams += param
                }
            }
        return ParsedMagnet(
            trackers = trackers,
            passthroughParams = passthroughParams.distinct(),
        )
    }

    private fun decodeQueryValue(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }
            .getOrDefault(value)

    private fun normalizeTracker(value: String): String? =
        value
            .trim()
            .removePrefix("tracker:")
            .trim()
            .takeIf { it.isNotEmpty() }

    private suspend fun resolveFileIndex(hash: String, requestedIdx: Int?, filename: String?): Int {
        val requestedName = filename?.trim()?.takeIf { it.isNotEmpty() }
        if (requestedIdx != null) {
            val torrServerIndex = requestedIdx + 1

            if (requestedName != null) {
                val files = waitForTorrentFiles(
                    hash = hash,
                    timeoutMs = FILE_INDEX_FAST_VALIDATION_TIMEOUT_MS,
                )
                if (files.isNotEmpty()) {
                    resolveByFilename(files, requestedName)?.let { match ->
                        return match.id
                    }

                    val requestedIdMatch = files.firstOrNull { it.id == torrServerIndex }
                    if (requestedIdMatch != null) {
                        return torrServerIndex
                    }

                    val positionalFile = files.getOrNull(requestedIdx)
                    if (positionalFile != null) {
                        return positionalFile.id
                    }
                }
            }
            return torrServerIndex
        }

        val files = waitForTorrentFiles(
            hash = hash,
            timeoutMs = FILE_INDEX_METADATA_TIMEOUT_MS,
        )

        if (files.isEmpty()) {
            return 1
        }

        if (requestedName != null) {
            resolveByFilename(files, requestedName)?.let { match ->
                return match.id
            }
        }

        val videoFile = files
            .filter { file ->
                val ext = file.path.substringAfterLast('.', "").lowercase()
                ext in VIDEO_EXTENSIONS
            }
            .maxByOrNull { it.length }

        return videoFile?.id ?: files.maxByOrNull { it.length }?.id ?: 1
    }

    private suspend fun waitForTorrentFiles(
        hash: String,
        timeoutMs: Long,
    ): List<TorrServerFile> {
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            val stats = api.getTorrentStats(hash)
            val files = stats?.files.orEmpty()
            if (files.isNotEmpty()) {
                return files
            }
            delay(FILE_INDEX_POLL_INTERVAL_MS)
        }
        return emptyList()
    }

    private fun resolveByFilename(files: List<TorrServerFile>, filename: String): TorrServerFile? {
        val name = filename.trim()
        files.firstOrNull { it.path.substringAfterLast('/').equals(name, ignoreCase = true) }
            ?.let { return it }
        files.firstOrNull { it.path.equals(name, ignoreCase = true) }
            ?.let { return it }
        files.firstOrNull { it.path.contains(name, ignoreCase = true) }
            ?.let { return it }
        return null
    }

    private fun startPreload(
        hash: String,
        generation: Long,
        magnetLink: String,
        selector: TorrServerStreamSelector,
    ) {
        val job = scope.launch {
            try {
                api.preloadTorrent(
                    magnetLink = magnetLink,
                    selector = selector,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logw("Torrent preload failed", e)
            }
        }
        val previousJob = synchronized(lifecycleLock) {
            if (streamGeneration == generation && hashMatches(currentHash, hash)) {
                val previous = preloadJob
                preloadJob = job
                previous
            } else {
                job.cancel()
                null
            }
        }
        previousJob?.cancel()
    }

    private fun startStatsPolling(
        hash: String,
        generation: Long,
    ) {
        val job = scope.launch {
            while (isActive) {
                if (!isCurrentGeneration(generation)) return@launch
                try {
                    val stats = api.getTorrentStats(hash)
                    val currentState = _state.value
                    if (
                        stats != null &&
                        currentState is P2pStreamingState.Streaming &&
                        isCurrentGeneration(generation)
                    ) {
                        _state.value = currentState.copy(
                            downloadSpeed = stats.downloadSpeed,
                            uploadSpeed = stats.uploadSpeed,
                            peers = stats.peers,
                            seeds = stats.seeds,
                            preloadedBytes = stats.preloadedBytes,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logw("Stats polling error", e)
                }
                delay(1_000L)
            }
        }
        val previousJob = synchronized(lifecycleLock) {
            if (streamGeneration == generation && hashMatches(currentHash, hash)) {
                val previous = statsJob
                statsJob = job
                previous
            } else {
                job.cancel()
                null
            }
        }
        previousJob?.cancel()
    }

    private val DEFAULT_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://open.stealth.si:80/announce",
        "https://torrent.tracker.durukanbal.com:443/announce",
        "udp://wepzone.net:6969/announce",
        "udp://tracker.wepzone.net:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://tracker.theoks.net:6969/announce",
        "udp://tracker.t-1.org:6969/announce",
        "udp://tracker.darkness.services:6969/announce",
        "udp://tracker-udp.gbitt.info:80/announce",
        "udp://t.overflow.biz:6969/announce",
        "udp://open.dstud.io:6969/announce",
        "udp://explodie.org:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://bittorrent-tracker.e-n-c-r-y-p-t.net:1337/announce",
        "https://tracker.zhuqiy.com:443/announce",
        "https://tracker.pmman.tech:443/announce",
        "https://tracker.moeblog.cn:443/announce",
        "https://tracker.bt4g.com:443/announce",
    )

    // -----------------------------------------------------------------------
    // TorrServer subprocess: resolve + launch the bundled Linux binary, drive it
    // via its HTTP API on 127.0.0.1:PORT.
    // -----------------------------------------------------------------------
    private class TorrServerBinary {
        @Volatile private var process: Process? = null
        private val startMutex = Mutex()
        private val healthClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build()

        val baseUrl: String get() = "http://127.0.0.1:$PORT"

        init {
            // ⚠ A JVM exiting does NOT kill processes it started with ProcessBuilder — they are
            // reparented to init and carry on. Nothing on the quit path stopped TorrServer
            // either: shutdown() defers the stop into a coroutine, and the window-close handler
            // never called it at all. So closing Nuvio after using P2P left a torrent daemon
            // running in the background, still connected to the swarm and still advertising the
            // user's IP, until they noticed and killed it by hand. This hook is the backstop
            // that makes that impossible regardless of how the app exits.
            runCatching {
                Runtime.getRuntime().addShutdownHook(
                    Thread(null, { runCatching { destroyNow() } }, "torrserver-shutdown", 0),
                )
            }
        }

        /**
         * Kills the child immediately, without the graceful HTTP shutdown [stop] tries first.
         *
         * Called from the JVM shutdown hook, where there is no time budget for a polite
         * request-and-wait and the only thing that matters is that the process is gone.
         */
        fun destroyNow() {
            val proc = process ?: return
            process = null
            runCatching { proc.destroy() }
            runCatching {
                if (!proc.waitFor(2, TimeUnit.SECONDS)) proc.destroyForcibly()
            }.onFailure { runCatching { proc.destroyForcibly() } }
        }

        suspend fun start() = startMutex.withLock {
            withContext(Dispatchers.IO) {
                if (isRunning()) {
                    return@withContext
                }

                killOrphanedProcess()

                val binaryFile = resolveBinary()
                    ?: throw P2pStreamingException(
                        "TorrServer binary not found (set NUVIO_TORRSERVER_BIN or bundle it in the app image)",
                    )
                if (!binaryFile.canExecute()) {
                    binaryFile.setExecutable(true)
                }

                val configDir = resolveConfigDir()
                val processBuilder = ProcessBuilder(
                    binaryFile.absolutePath,
                    "--port",
                    PORT.toString(),
                    "--path",
                    configDir.absolutePath,
                )
                processBuilder.directory(configDir)
                processBuilder.redirectErrorStream(true)
                processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD)

                val proc = processBuilder.start()
                process = proc

                val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    if (isRunning()) {
                        return@withContext
                    }
                    if (!proc.isAlive) {
                        val exitCode = runCatching { proc.exitValue() }.getOrDefault(-1)
                        process = null
                        throw P2pStreamingException("TorrServer process died on startup (exit code $exitCode)")
                    }
                    delay(HEALTH_CHECK_INTERVAL_MS)
                }

                stop()
                throw P2pStreamingException("TorrServer failed to start within ${STARTUP_TIMEOUT_MS / 1000}s")
            }
        }

        fun isRunning(): Boolean {
            return try {
                val request = HttpRequest.newBuilder(URI.create("$baseUrl/echo"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build()
                val response = healthClient.send(request, HttpResponse.BodyHandlers.discarding())
                response.statusCode() in 200..299
            } catch (e: Exception) {
                false
            }
        }

        fun stop() {
            try {
                val request = HttpRequest.newBuilder(URI.create("$baseUrl/shutdown"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build()
                healthClient.send(request, HttpResponse.BodyHandlers.discarding())
            } catch (_: Exception) {
            }

            process?.let { proc ->
                try {
                    if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                        proc.destroyForcibly()
                    }
                } catch (_: Exception) {
                    proc.destroyForcibly()
                }
            }
            process = null
        }

        private fun killOrphanedProcess() {
            try {
                val request = HttpRequest.newBuilder(URI.create("$baseUrl/shutdown"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build()
                healthClient.send(request, HttpResponse.BodyHandlers.discarding())
                Thread.sleep(1_000L)
            } catch (_: Exception) {
            }
        }

        // Resolve the TorrServer executable: an env override (for dev/testing) first,
        // then the jpackage app image (phase 2 bundles it under the resources dir).
        private fun resolveBinary(): File? {
            System.getenv("NUVIO_TORRSERVER_BIN")?.takeIf { it.isNotBlank() }?.let { override ->
                File(override).takeIf { it.exists() }?.let { return it }
            }
            val candidates = mutableListOf<File>()
            System.getProperty("compose.application.resources.dir")
                ?.takeIf { it.isNotBlank() }
                ?.let { candidates += File(it, BINARY_NAME) }
            System.getProperty("jpackage.app-path")
                ?.takeIf { it.isNotBlank() }
                ?.let { launcher ->
                    File(launcher).parentFile?.parentFile?.let { appDir ->
                        candidates += File(appDir, "lib/app/$BINARY_NAME")
                        candidates += File(appDir, "lib/$BINARY_NAME")
                    }
                }
            return candidates.firstOrNull { it.exists() }
        }

        private fun resolveConfigDir(): File {
            val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
            val home = System.getProperty("user.home").orEmpty()
            val base = when {
                os.contains("mac") -> File(home, "Library/Caches/Nuvio")
                os.contains("win") -> File(
                    System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: "$home\\AppData\\Local",
                    "Nuvio",
                )
                else -> File(
                    System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.cache",
                    "nuvio",
                )
            }
            return File(base, "torrserver").also { it.mkdirs() }
        }

        companion object {
            const val PORT = 8091
            private const val BINARY_NAME = "torrserver"
            private const val STARTUP_TIMEOUT_MS = 15_000L
            private const val HEALTH_CHECK_INTERVAL_MS = 200L
        }
    }

    private data class TorrServerFile(
        val id: Int,
        val index: Int,
        val path: String,
        val length: Long,
    )

    private data class TorrServerStreamSelector(
        val legacyIndex: Int?,
        val fileIdx: Int?,
        val filename: String?,
    )

    private data class TorrServerStats(
        val status: String,
        val downloadSpeed: Long,
        val uploadSpeed: Long,
        val peers: Int,
        val seeds: Int,
        val preloadedBytes: Long,
        val files: List<TorrServerFile>,
    )

    private data class StreamingSettingsResult(
        val success: Boolean,
        val changed: Boolean,
    )

    // -----------------------------------------------------------------------
    // TorrServer HTTP API (java.net.http + kotlinx.serialization).
    // -----------------------------------------------------------------------
    private class TorrServerApi(
        private val binary: TorrServerBinary,
    ) {
        private val settingsMutex = Mutex()
        private val json = Json { ignoreUnknownKeys = true }
        private val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()

        private val baseUrl: String get() = binary.baseUrl

        private fun postForObject(
            path: String,
            body: JsonObject,
            timeout: Duration = Duration.ofSeconds(30),
        ): JsonObject? {
            val request = HttpRequest.newBuilder(URI.create("$baseUrl$path"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                logw("$path failed code=${response.statusCode()}")
                return null
            }
            val text = response.body()?.takeIf { it.isNotBlank() } ?: "{}"
            return runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        }

        suspend fun ensureStreamingSettings(): StreamingSettingsResult = settingsMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val settings = getSettings()?.toMutableMap() ?: return@withContext StreamingSettingsResult(
                        success = false,
                        changed = false,
                    )
                    val changes = mutableListOf<String>()

                    putIntIfDifferent(settings, "CacheSize", STREAMING_CACHE_SIZE_BYTES, changes)
                    putIntIfDifferent(settings, "ConnectionsLimit", STREAMING_CONNECTION_LIMIT, changes)
                    putIntIfDifferent(settings, "HalfOpenConnectionsLimit", STREAMING_HALF_OPEN_CONNECTION_LIMIT, changes)
                    putIntIfDifferent(settings, "TotalHalfOpenConnectionsLimit", STREAMING_TOTAL_HALF_OPEN_CONNECTION_LIMIT, changes)
                    putIntIfDifferent(settings, "TorrentPeersHighWater", STREAMING_PEERS_HIGH_WATER, changes)
                    putIntIfDifferent(settings, "TorrentPeersLowWater", STREAMING_PEERS_LOW_WATER, changes)
                    putIntIfDifferent(settings, "NominalDialTimeoutMs", STREAMING_NOMINAL_DIAL_TIMEOUT_MS, changes)
                    putIntIfDifferent(settings, "MinDialTimeoutMs", STREAMING_MIN_DIAL_TIMEOUT_MS, changes)
                    putIntIfDifferent(settings, "HandshakeTimeoutMs", STREAMING_HANDSHAKE_TIMEOUT_MS, changes)
                    putIntIfDifferent(settings, "TorrentDisconnectTimeout", STREAMING_DISCONNECT_TIMEOUT_SECONDS, changes)
                    putIntIfDifferent(settings, "ReaderReadAHead", STREAMING_READ_AHEAD_PERCENT, changes)
                    putIntIfDifferent(settings, "PreloadCache", STREAMING_PRELOAD_CACHE_PERCENT, changes)
                    putBoolIfDifferent(settings, "ResponsiveMode", true, changes)
                    putBoolIfDifferent(settings, "DisableDHT", false, changes)
                    putBoolIfDifferent(settings, "DisablePEX", false, changes)
                    putBoolIfDifferent(settings, "DisableTCP", false, changes)
                    putBoolIfDifferent(settings, "DisableUTP", false, changes)
                    putBoolIfDifferent(settings, "DisableUpload", false, changes)
                    putBoolIfDifferent(settings, "ForceEncrypt", false, changes)
                    putIntIfDifferent(settings, "DownloadRateLimit", 0L, changes)
                    putIntIfDifferent(settings, "UploadRateLimit", 0L, changes)
                    putIntIfDifferent(settings, "RetrackersMode", 1L, changes)
                    putBoolIfDifferent(settings, "EnableLPD", true, changes)
                    putBoolIfDifferent(settings, "LPDIPv6", false, changes)
                    putBoolIfDifferent(settings, "StoreSettingsInJson", true, changes)

                    if (changes.isEmpty()) {
                        return@withContext StreamingSettingsResult(success = true, changed = false)
                    }

                    val body = buildJsonObject {
                        put("action", "set")
                        put("sets", JsonObject(settings))
                    }
                    val request = HttpRequest.newBuilder(URI.create("$baseUrl/settings"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build()
                    val response = client.send(request, HttpResponse.BodyHandlers.discarding())
                    if (response.statusCode() !in 200..299) {
                        logw("streaming-settings: set failed code=${response.statusCode()}")
                        return@withContext StreamingSettingsResult(success = false, changed = false)
                    }
                    StreamingSettingsResult(success = true, changed = true)
                } catch (e: Exception) {
                    logw("streaming-settings: failed", e)
                    StreamingSettingsResult(success = false, changed = false)
                }
            }
        }

        private fun getSettings(): JsonObject? {
            val body = buildJsonObject { put("action", "get") }
            return postForObject("/settings", body)
        }

        private fun putIntIfDifferent(
            settings: MutableMap<String, JsonElement>,
            key: String,
            desiredValue: Long,
            changes: MutableList<String>,
        ) {
            val current = (settings[key] as? JsonPrimitive)?.longOrNull
            if (current != desiredValue) {
                settings[key] = JsonPrimitive(desiredValue)
                changes += key
            }
        }

        private fun putBoolIfDifferent(
            settings: MutableMap<String, JsonElement>,
            key: String,
            desiredValue: Boolean,
            changes: MutableList<String>,
        ) {
            val current = (settings[key] as? JsonPrimitive)?.booleanOrNull
            if (current != desiredValue) {
                settings[key] = JsonPrimitive(desiredValue)
                changes += key
            }
        }

        suspend fun addTorrent(magnetLink: String, title: String? = null): String? = withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("action", "add")
                put("link", magnetLink)
                put("save_to_db", false)
                if (title != null) put("title", title)
            }
            try {
                val obj = postForObject("/torrents", body) ?: return@withContext null
                (obj["hash"] as? JsonPrimitive)?.contentOrNull?.ifEmpty { null }
            } catch (e: Exception) {
                logw("addTorrent error", e)
                null
            }
        }

        suspend fun preloadTorrent(
            magnetLink: String,
            selector: TorrServerStreamSelector,
        ): Boolean = withContext(Dispatchers.IO) {
            val url = getStreamUrl(magnetLink = magnetLink, selector = selector, mode = "preload")
            // No read timeout: preload streams until TorrServer has cached the lead-in.
            // Cancelled with the coroutine by cancelling the async future.
            val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
            val future = client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
            val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause != null) future.cancel(true)
            }
            try {
                val response = future.get()
                response.statusCode() in 200..299
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logw("preload: request failed", e)
                false
            } finally {
                cancellationHandle?.dispose()
            }
        }

        suspend fun getTorrentStats(hash: String): TorrServerStats? = withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("action", "get")
                put("hash", hash)
            }
            try {
                val obj = postForObject("/torrents", body) ?: return@withContext null
                val files = mutableListOf<TorrServerFile>()
                (obj["file_stats"] as? JsonArray)?.forEachIndexed { i, element ->
                    val file = element.jsonObject
                    files.add(
                        TorrServerFile(
                            id = (file["id"] as? JsonPrimitive)?.intOrNull ?: (i + 1),
                            index = (file["index"] as? JsonPrimitive)?.intOrNull ?: i,
                            path = (file["path"] as? JsonPrimitive)?.contentOrNull ?: "",
                            length = (file["length"] as? JsonPrimitive)?.longOrNull ?: 0L,
                        ),
                    )
                }
                TorrServerStats(
                    status = (obj["stat_string"] as? JsonPrimitive)?.contentOrNull ?: "",
                    downloadSpeed = (obj["download_speed"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    uploadSpeed = (obj["upload_speed"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    peers = (obj["active_peers"] as? JsonPrimitive)?.intOrNull ?: 0,
                    seeds = (obj["connected_seeders"] as? JsonPrimitive)?.intOrNull ?: 0,
                    preloadedBytes = (obj["preloaded_bytes"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    files = files,
                )
            } catch (e: Exception) {
                logw("getTorrentStats error", e)
                null
            }
        }

        suspend fun dropTorrent(hash: String) {
            withContext(Dispatchers.IO) {
                val body = buildJsonObject {
                    put("action", "drop")
                    put("hash", hash)
                }
                try {
                    postForObject("/torrents", body)
                } catch (e: Exception) {
                    logw("dropTorrent error", e)
                }
            }
        }

        fun getStreamUrl(
            magnetLink: String,
            selector: TorrServerStreamSelector,
            mode: String = "play",
        ): String {
            val params = mutableListOf(
                "link=${URLEncoder.encode(magnetLink, "UTF-8")}",
                mode,
            )
            selector.legacyIndex?.let { params += "index=$it" }
            selector.fileIdx?.let { params += "fileIdx=$it" }
            selector.filename
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { params += "filename=${URLEncoder.encode(it, "UTF-8")}" }
            return "$baseUrl/stream?${params.joinToString("&")}"
        }
    }
}
