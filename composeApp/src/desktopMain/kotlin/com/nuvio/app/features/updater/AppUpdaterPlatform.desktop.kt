package com.nuvio.app.features.updater

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

private const val UPDATE_CONNECT_TIMEOUT_MS = 30_000
private const val UPDATE_READ_TIMEOUT_MS = 60_000
private const val UPDATE_PROGRESS_INTERVAL_MS = 500L

actual object AppUpdaterPlatform {
    // Fork-only store: the official client has no updater, it just ignores this file.
    private val store = DesktopStorage.store("nuvio_updater")

    actual val isSupported: Boolean = true

    actual fun getSupportedAbis(): List<String> {
        val arch = System.getProperty("os.arch") ?: ""
        return when {
            arch.contains("aarch64") || arch.contains("arm64") -> listOf("arm64", "aarch64")
            else -> listOf("amd64", "x86_64")
        }
    }

    actual fun getIgnoredTag(): String? =
        store.getString("ignored_tag")

    actual fun setIgnoredTag(tag: String?) {
        if (tag != null) {
            store.putString("ignored_tag", tag)
        } else {
            store.remove("ignored_tag")
        }
    }

    actual val supportsExperimentalChannel: Boolean = true

    actual fun getExperimentalUpdatesEnabled(): Boolean =
        store.getBoolean("experimental_updates") ?: false

    actual fun setExperimentalUpdatesEnabled(enabled: Boolean) {
        store.putBoolean("experimental_updates", enabled)
    }

    actual suspend fun downloadApk(
        assetUrl: String,
        assetName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val downloadsDir = File(System.getProperty("user.home"), "Downloads").also { it.mkdirs() }
            val destFile = File(downloadsDir, assetName)

            // ⚠ Both timeouts, explicitly. openConnection() defaults to "wait forever": a
            // server that accepts the connection and then stops sending left the update stuck at
            // whatever percentage it had reached, with no error and no way to cancel.
            val connection = URI(assetUrl).toURL().openConnection().apply {
                connectTimeout = UPDATE_CONNECT_TIMEOUT_MS
                readTimeout = UPDATE_READ_TIMEOUT_MS
            }
            connection.connect()
            val totalBytes = connection.contentLengthLong.takeIf { it > 0 }

            connection.getInputStream().use { input ->
                destFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    var read: Int
                    // Reported on a clock, not per 8 KB buffer — a 200 MB package is ~25,000
                    // callbacks otherwise, each one driving a UI update for a number that only
                    // needs to move a few times a second.
                    var lastReportMs = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastReportMs >= UPDATE_PROGRESS_INTERVAL_MS) {
                            lastReportMs = nowMs
                            onProgress(downloaded, totalBytes)
                        }
                    }
                    onProgress(downloaded, totalBytes)
                }
            }

            destFile.absolutePath
        }
    }

    // On Linux we can always attempt to open the package — no "unknown sources" gate.
    actual fun canRequestPackageInstalls(): Boolean = true

    actual fun openUnknownSourcesSettings() = Unit

    // Open the downloaded .deb with xdg-open so the system package manager (GDebi,
    // Ubuntu Software Centre, etc.) handles installation.
    actual fun installDownloadedApk(path: String): Result<Unit> = runCatching {
        ProcessBuilder("xdg-open", path)
            .inheritIO()
            .start()
    }
}
