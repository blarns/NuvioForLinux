package com.nuvio.app.core.network

/**
 * A URL reduced to what is safe to print.
 *
 * ⚠ Addon, debrid and scraper links carry CREDENTIALS IN THE PATH — a base64 blob holding the
 * user's Real-Debrid / TorBox API keys, or a signed token — and the query string routinely repeats
 * them. Logging the URL whole therefore writes working account keys into stdout, which is exactly
 * what `scripts/nuvio_debug_logs.sh` collects and users paste into bug reports. Seen in real
 * session logs twice: once on the playback path (fixed when this was written) and once on the
 * addon fan-out, which kept logging whole URLs because this lived in `desktopMain` and the fan-out
 * is `commonMain`. It is common code now so there is one implementation and no second miss.
 *
 * Keeps the host (which names the provider — the useful part when diagnosing) and the file name if
 * the last path segment still looks like one; everything in between becomes an ellipsis. Any query
 * or fragment is dropped whole: nothing in a query string is worth a leaked key.
 *
 * Deliberately hand-parsed rather than using a URI type — this is common code, and the parse must
 * never throw on the malformed URLs that are exactly when logging matters most.
 */
fun redactSourceUrl(url: String?): String {
    if (url.isNullOrBlank()) return "<none>"
    val trimmed = url.trim()
    if (!(trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true))) {
        // Local paths carry no account secrets. A magnet does not either, but it is long and
        // pointless in a log, so it is cut down to around its info-hash.
        return if (trimmed.startsWith("magnet:", true)) trimmed.take(60) + "…" else trimmed
    }

    val schemeEnd = trimmed.indexOf("://") + 3
    val afterScheme = trimmed.substring(schemeEnd)
    // Stop the authority at whichever structural delimiter comes first, so a '?' before any '/'
    // cannot smuggle the rest of the URL into the host.
    val authorityEnd = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
        .let { if (it < 0) afterScheme.length else it }
    val authority = afterScheme.substring(0, authorityEnd)
    // userinfo (user:password@host) is a credential in its own right.
    val host = authority.substringAfterLast('@').ifBlank { return "<unparseable url>" }
    val scheme = trimmed.substring(0, schemeEnd - 3)

    val path = afterScheme.substring(authorityEnd).substringBefore('?').substringBefore('#')
    val lastSegment = path.substringAfterLast('/')
    // Only a segment that still looks like a file name is kept; a long opaque segment is
    // precisely the credential blob. ⚠ "contains a dot" is NOT enough — base64 config blobs are
    // routinely appended to a path segment that already has one, and the test for that case is
    // the reason this checks the EXTENSION rather than the presence of a separator.
    val extension = lastSegment.substringAfterLast('.', missingDelimiterValue = "")
    val fileName = lastSegment.takeIf {
        it.length in 1..120 &&
            extension.length in 1..5 &&
            extension.all { c -> c.isLetterOrDigit() }
    }

    return buildString {
        append(scheme).append("://").append(host)
        append("/…")
        if (fileName != null) append('/').append(fileName)
    }
}
