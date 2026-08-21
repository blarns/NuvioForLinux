package com.nuvio.app.features.player

// redactSourceUrl used to live here. It moved to com.nuvio.app.core.network because the addon
// fan-out that also logs credential-bearing URLs is commonMain and could not reach a desktopMain
// helper — which is how whole addon URLs, API keys and all, kept being written to the log this
// function exists to keep them out of.

/**
 * Turns a playback failure into something a user can act on.
 *
 * Neither engine says anything useful about *why* a stream failed — libVLC reports a bare
 * "playback error", and mpv gives a generic loading-failed code. Nearly every real failure here
 * is an expired signed URL from a scraper/debrid source, so the source is probed directly and
 * the HTTP status is what the user is told: "the link expired, refresh sources" rather than
 * "the app is broken".
 *
 * Shared by both desktop engines so the mpv path cannot regress to a silent spinner.
 */
internal fun diagnosePlaybackFailure(url: String, fallback: String): String {
    if (!(url.startsWith("http://", true) || url.startsWith("https://", true))) return fallback
    val parsed = try {
        java.net.URI.create(url).toURL()
    } catch (_: Exception) {
        return fallback
    }
    return try {
        val connection = (parsed.openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            // Ask for a single byte: we only want the status line, not the file.
            setRequestProperty("Range", "bytes=0-0")
            setRequestProperty("User-Agent", "NuvioMobile/1.0")
            instanceFollowRedirects = true
            connectTimeout = 4000
            readTimeout = 4000
        }
        val code = try { connection.responseCode } finally { connection.disconnect() }
        when (code) {
            in 200..399 ->
                "Source opened but could not be played — likely an unsupported format or codec. Try a different source."
            401, 403 ->
                "Source link was rejected (HTTP $code) — it has likely expired or is region-locked. Refresh sources and try again."
            404, 410 ->
                "Source is no longer available (HTTP $code) — the link expired or was removed. Try a different source."
            in 500..599 ->
                "The source server returned an error (HTTP $code). Try a different source."
            else ->
                "Source returned HTTP $code. Refresh sources and try again."
        }
    } catch (_: java.net.UnknownHostException) {
        "Could not reach the source server (host not found). Try a different source."
    } catch (_: java.net.SocketTimeoutException) {
        "The source server did not respond in time. Try a different source."
    } catch (e: Exception) {
        "Could not reach the source — ${e.message ?: "connection failed"}. Try a different source."
    }
}

/**
 * Runs [diagnosePlaybackFailure] off the caller's thread and reports the result.
 *
 * Always called from an engine event callback, where a 4s HTTP probe would block the engine's
 * own event loop.
 */
internal fun reportPlaybackFailureAsync(
    url: String?,
    fallback: String,
    tag: String,
    onMessage: (String) -> Unit,
) {
    if (url == null) {
        onMessage(fallback)
        return
    }
    Thread(null, {
        val message = diagnosePlaybackFailure(url, fallback)
        println("$tag: playback failure diagnosis -> $message")
        onMessage(message)
    }, "playback-error-probe", 0).apply {
        isDaemon = true
        start()
    }
}
