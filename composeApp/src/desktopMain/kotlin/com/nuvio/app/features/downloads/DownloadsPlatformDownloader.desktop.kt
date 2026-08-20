package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.io.path.createDirectories

/** How often a running download reports its byte count upwards. */
private const val PROGRESS_REPORT_INTERVAL_MS = 500L

private val desktopDownloadHttpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(60))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

internal actual object DownloadsPlatformDownloader {
    private val downloadsDir: File
        get() = File(DesktopStorage.rootDir.resolve("downloads").also { it.createDirectories() }.toUri())

    actual fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
    ): DownloadsTaskHandle {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)

        scope.launch {
            val destination = File(downloadsDir, request.destinationFileName)
            val tempFile = File(downloadsDir, "${request.destinationFileName}.part")

            try {
                var resumeFromBytes = tempFile.takeIf { it.exists() }?.length()?.coerceAtLeast(0L) ?: 0L
                var attemptedRangeRequest = resumeFromBytes > 0L
                var response = sendDownloadRequest(
                    request = request,
                    rangeStart = if (attemptedRangeRequest) resumeFromBytes else null,
                    validator = if (attemptedRangeRequest) readValidator(tempFile) else null,
                )

                if (attemptedRangeRequest && response.statusCode() == 416) {
                    tempFile.delete()
                    resumeFromBytes = 0L
                    attemptedRangeRequest = false
                    response = sendDownloadRequest(request, null)
                }

                if (response.statusCode() !in 200..299) {
                    error("Download failed with HTTP ${response.statusCode()}")
                }

                // A 200 in reply to a Range request means the server declined to resume — the
                // content changed, or it never supported ranges. Either way the bytes already on
                // disk belong to something else and appending to them would corrupt the file.
                val isPartialResume = attemptedRangeRequest && response.statusCode() == 206 && resumeFromBytes > 0L
                if (attemptedRangeRequest && !isPartialResume) {
                    resumeFromBytes = 0L
                }
                val appendToTemp = isPartialResume
                val startingBytes = if (appendToTemp) resumeFromBytes else 0L
                if (!appendToTemp && tempFile.exists()) {
                    tempFile.delete()
                }

                val totalBytes = resolveTotalBytes(
                    startingBytes = startingBytes,
                    isPartialResume = isPartialResume,
                    contentRangeHeader = response.headers().firstValue("Content-Range").orElse(null),
                    contentLength = response.headers().firstValue("Content-Length").orElse(null)?.toLongOrNull(),
                )
                // Remember what this partial file is a partial OF, so a later resume can prove
                // the remote bytes are still the same ones.
                writeValidator(
                    tempFile,
                    response.headers().firstValue("ETag").orElse(null)
                        ?: response.headers().firstValue("Last-Modified").orElse(null),
                )
                var downloadedBytes = startingBytes
                onProgress(downloadedBytes, totalBytes)

                response.body().use { input ->
                    FileOutputStream(tempFile, appendToTemp).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        // ⚠ Progress is reported on a clock, not per buffer. `read` is 8 KB, so
                        // reporting every iteration meant ~500,000 callbacks for a 4 GB film —
                        // each one re-encoding the whole downloads list and rewriting its store
                        // file, plus a UI state emission. Throughput collapsed to disk speed for
                        // a number that only needs to move a few times a second.
                        var lastReportMs = 0L
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloadedBytes += read.toLong()
                            val nowMs = System.currentTimeMillis()
                            if (nowMs - lastReportMs >= PROGRESS_REPORT_INTERVAL_MS) {
                                lastReportMs = nowMs
                                onProgress(downloadedBytes, totalBytes)
                            }
                        }
                        output.flush()
                    }
                }
                // The throttle can swallow the last tick, so the final byte count is always
                // reported exactly — a download must never finish showing 99%.
                onProgress(downloadedBytes, totalBytes)

                if (destination.exists()) {
                    destination.delete()
                }
                if (!tempFile.renameTo(destination)) {
                    tempFile.copyTo(destination, overwrite = true)
                    tempFile.delete()
                }

                val finalSize = destination.length()
                // A short file is a failed download that would otherwise be filed as complete
                // and play as a truncated video. Better to say so now.
                if (totalBytes != null && totalBytes > 0L && finalSize < totalBytes) {
                    destination.delete()
                    error("Download ended early ($finalSize of $totalBytes bytes)")
                }
                writeValidator(tempFile, null)
                onSuccess(destination.toURI().toString(), totalBytes ?: finalSize)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onFailure(error.message ?: "Download failed")
            }
        }

        return DesktopDownloadsTaskHandle(job)
    }

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        val file = localFileUri.toLocalFileOrNull() ?: return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    actual fun removePartialFile(destinationFileName: String): Boolean {
        val tempFile = File(downloadsDir, "$destinationFileName.part")
        // The validator sidecar goes with it — a stale one left next to a deleted part file
        // would be offered as If-Range for a download that starts from zero.
        writeValidator(tempFile, null)
        if (!tempFile.exists()) return true
        return runCatching { tempFile.delete() }.getOrDefault(false)
    }

    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
        localFileUri
            ?.toLocalFileOrNull()
            ?.takeIf { it.exists() }
            ?.let { return it.toURI().toString() }

        val fileName = destinationFileName.trim().takeIf { it.isNotBlank() }
            ?: localFileUri?.toLocalFileOrNull()?.name?.takeIf { it.isNotBlank() }
            ?: return null
        return File(downloadsDir, fileName).takeIf { it.exists() }?.toURI()?.toString()
    }

    private fun sendDownloadRequest(
        request: DownloadPlatformRequest,
        rangeStart: Long?,
        validator: String? = null,
    ): HttpResponse<java.io.InputStream> {
        val builder = HttpRequest.newBuilder()
            .uri(URI(request.sourceUrl))
            .timeout(Duration.ofSeconds(60))
            .GET()
        request.sourceHeaders.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) {
                builder.header(key, value)
            }
        }
        if (rangeStart != null && rangeStart > 0L) {
            builder.header("Range", "bytes=$rangeStart-")
            // ⚠ If-Range is what makes a resume safe. Without it the server happily serves the
            // requested byte range of whatever it holds NOW, and the two halves get spliced
            // together into a file that looks complete and plays as garbage. That is not an edge
            // case here: debrid links rotate and expire constantly and CDN endpoints re-resolve,
            // so "the bytes moved since last time" is the normal condition. With a validator the
            // server answers 200-with-the-whole-file when the content changed, which the caller
            // already handles by restarting from zero.
            validator?.takeIf { it.isNotBlank() }?.let { builder.header("If-Range", it) }
        }
        return desktopDownloadHttpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
    }
}

private class DesktopDownloadsTaskHandle(
    private val job: Job,
) : DownloadsTaskHandle {
    override fun cancel() {
        job.cancel()
    }
}

/**
 * The ETag/Last-Modified a `.part` file was downloaded against, kept in a sidecar next to it.
 *
 * A sidecar rather than in-memory state because resume has to survive an app restart, which is
 * the case it exists for.
 */
private fun validatorFile(tempFile: File): File = File(tempFile.absolutePath + ".etag")

private fun readValidator(tempFile: File): String? =
    runCatching { validatorFile(tempFile).takeIf { it.exists() }?.readText()?.trim() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }

private fun writeValidator(tempFile: File, validator: String?) {
    runCatching {
        val file = validatorFile(tempFile)
        if (validator.isNullOrBlank()) file.delete() else file.writeText(validator)
    }
}

private fun String.toLocalFileOrNull(): File? =
    runCatching {
        if (startsWith("file:")) {
            File(URI(this))
        } else {
            File(this)
        }
    }.getOrNull()

private fun resolveTotalBytes(
    startingBytes: Long,
    isPartialResume: Boolean,
    contentRangeHeader: String?,
    contentLength: Long?,
): Long? {
    parseContentRangeTotal(contentRangeHeader)?.let { return it }
    val normalizedLength = contentLength?.takeIf { it > 0L } ?: return null
    return if (isPartialResume && startingBytes > 0L) {
        startingBytes + normalizedLength
    } else {
        normalizedLength
    }
}

private fun parseContentRangeTotal(headerValue: String?): Long? {
    val value = headerValue?.trim().orEmpty()
    if (value.isBlank()) return null
    val slashIndex = value.lastIndexOf('/')
    if (slashIndex == -1 || slashIndex == value.lastIndex) return null
    val totalPart = value.substring(slashIndex + 1).trim()
    if (totalPart == "*") return null
    return totalPart.toLongOrNull()?.takeIf { it > 0L }
}
