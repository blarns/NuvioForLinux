package com.nuvio.app.features.trailer

// Desktop port of androidFull/TrailerExtractionPlatform, using the JDK java.net.http client.
// Pairs with the desktop copy of InAppYouTubeExtractor — re-sync both on upstream YouTube fixes.

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal object TrailerExtractionPlatform {
    val defaultHeaders: Map<String, String> = mapOf(
        "accept-language" to "en-US,en;q=0.9",
        "user-agent" to
            "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
    )

    // java.net.http does not advertise gzip by default, so responses come back uncompressed
    // (equivalent to Android filtering out Accept-Encoding). Restricted headers are skipped.
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofMillis(TRAILER_REQUEST_TIMEOUT_MS))
        .build()

    suspend fun performRequest(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMillis: Long,
    ): TrailerRequestResponse = withContext(Dispatchers.IO) {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(timeoutMillis))
        applyHeaders(builder, headers)
        when (method.uppercase()) {
            "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body ?: ""))
            "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofString(body ?: ""))
            "DELETE" -> builder.DELETE()
            else -> builder.GET()
        }
        val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        TrailerRequestResponse(
            ok = response.statusCode() in 200..299,
            status = response.statusCode(),
            statusText = "",
            url = response.uri().toString(),
            body = response.body().orEmpty(),
        )
    }

    suspend fun buildPlaybackSource(
        bestManifest: ManifestCandidate?,
        bestProgressive: StreamCandidate?,
        bestVideo: StreamCandidate?,
        bestAudio: StreamCandidate?,
    ): TrailerPlaybackSource? = withContext(Dispatchers.IO) {
        val bestCombinedIsManifest = bestManifest != null &&
            (bestProgressive == null || bestManifest.height > bestProgressive.height)

        val combinedUrl = if (bestCombinedIsManifest) {
            bestManifest.selectedVariantUrl
        } else {
            bestProgressive?.url
        }

        val separatedVideoUrl = bestVideo?.url?.let { resolveReachableUrlOrNull(it) }
        val combinedCandidateUrl = combinedUrl?.let { resolveReachableUrlOrNull(it) }
        val videoUrl = separatedVideoUrl ?: combinedCandidateUrl ?: return@withContext null
        val audioUrl = if (!separatedVideoUrl.isNullOrBlank()) {
            bestAudio?.url?.let { resolveReachableUrlOrNull(it) }
        } else {
            null
        }

        TrailerPlaybackSource(
            videoUrl = videoUrl,
            audioUrl = audioUrl,
        )
    }

    private suspend fun resolveReachableUrlOrNull(url: String): String? {
        if (!url.contains("googlevideo.com")) return url
        val mnParam = queryParam(url, "mn") ?: return url
        val servers = mnParam.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (servers.size < 2) {
            return if (isUrlReachable(url)) url else null
        }

        val host = runCatching { URI.create(url).host }.getOrNull()
            ?: return if (isUrlReachable(url)) url else null
        val candidates = mutableListOf(url)
        servers.forEachIndexed { index, server ->
            val altHost = host
                .replaceFirst(Regex("^rr\\d+---"), "rr${index + 1}---")
                .replaceFirst(Regex("sn-[a-z0-9]+-[a-z0-9]+"), server)
            if (altHost != host) {
                candidates += url.replace(host, altHost)
            }
        }

        if (candidates.size == 1) {
            return if (isUrlReachable(candidates[0])) candidates[0] else null
        }

        val result = CompletableDeferred<String>()
        val probeScope = CoroutineScope(Dispatchers.IO)
        candidates.forEach { candidate ->
            probeScope.launch {
                if (isUrlReachable(candidate)) {
                    result.complete(candidate)
                }
            }
        }

        return try {
            withTimeoutOrNull(2_000L) { result.await() }
        } finally {
            probeScope.cancel()
        }
    }

    private fun isUrlReachable(url: String): Boolean = runCatching {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(2))
            .header("Range", "bytes=0-0")
            .GET()
        applyHeaders(builder, defaultHeaders)
        val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding())
        response.statusCode() in 200..299
    }.getOrDefault(false)

    private fun applyHeaders(builder: HttpRequest.Builder, source: Map<String, String>) {
        source.forEach { (name, value) ->
            if (!name.equals("Accept-Encoding", ignoreCase = true)) {
                // java.net.http throws on a few restricted headers (host, connection, etc.) — skip those.
                runCatching { builder.header(name, value) }
            }
        }
        if (source.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            runCatching { builder.header("User-Agent", defaultHeaders.getValue("user-agent")) }
        }
    }

    private fun queryParam(url: String, key: String): String? {
        val query = runCatching { URI.create(url).rawQuery }.getOrNull() ?: return null
        return query.split('&').firstNotNullOfOrNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@firstNotNullOfOrNull null
            val k = pair.substring(0, idx)
            if (k == key) {
                java.net.URLDecoder.decode(pair.substring(idx + 1), Charsets.UTF_8)
            } else {
                null
            }
        }
    }
}
