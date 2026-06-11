package com.nuvio.app.features.updater

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

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

    actual suspend fun downloadApk(
        assetUrl: String,
        assetName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val downloadsDir = File(System.getProperty("user.home"), "Downloads").also { it.mkdirs() }
            val destFile = File(downloadsDir, assetName)

            val connection = URI(assetUrl).toURL().openConnection()
            connection.connect()
            val totalBytes = connection.contentLengthLong.takeIf { it > 0 }

            connection.getInputStream().use { input ->
                destFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, totalBytes)
                    }
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
