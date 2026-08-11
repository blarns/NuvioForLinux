package com.nuvio.app.features.updater

import com.nuvio.app.DesktopWindowState
import com.nuvio.app.core.storage.DesktopRestore
import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

actual object AppUpdaterPlatform {
    private const val UPDATER_STORE = "nuvio_updater"

    // Fork-only store: the official client has no updater, it just ignores this file.
    private val store = DesktopStorage.store(UPDATER_STORE)

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

    actual val supportsDataBackup: Boolean = true

    // Zip the whole data dir (~/.config/nuvio and friends) into Downloads. Small — it is
    // properties files, not media — so this is fast enough to run on the click.
    actual suspend fun backupUserData(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val rootDir = DesktopStorage.rootDir
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            val downloadsDir = File(System.getProperty("user.home"), "Downloads").also { it.mkdirs() }
            val destFile = File(downloadsDir, "nuvio-backup-$stamp.zip")

            ZipOutputStream(destFile.outputStream().buffered()).use { zip ->
                Files.walk(rootDir).use { stream ->
                    stream.filter { Files.isRegularFile(it) }
                        // Not user data, and restoring it would silently switch the channel back
                        // on: the backup is taken the moment you opt in, so it captures
                        // experimental_updates=true. Someone restoring after a bad alpha would
                        // land straight back on the alpha channel.
                        .filter { it.fileName.toString() != "$UPDATER_STORE.properties" }
                        .forEach { path ->
                            zip.putNextEntry(ZipEntry(rootDir.relativize(path).toString()))
                            Files.copy(path, zip)
                            zip.closeEntry()
                        }
                }
            }

            // The case this whole feature exists for is "the downgrade went wrong", and after a
            // downgrade the running app is an older build with no restore UI in it. So the zip
            // gets a companion script that restores it with Nuvio closed, no app required.
            runCatching { writeRestoreScript(downloadsDir, destFile) }

            destFile.absolutePath
        }
    }

    actual fun pickBackupFile(): String? {
        // Called straight from a click handler, which Compose Desktop runs on the AWT event
        // thread — exactly where a modal file dialog belongs. No invokeAndWait (that throws
        // when you are already on the EDT).
        val chooser = JFileChooser(File(System.getProperty("user.home"), "Downloads")).apply {
            dialogTitle = "Choose a Nuvio backup"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isAcceptAllFileFilterUsed = false
            fileFilter = FileNameExtensionFilter("Nuvio backup (*.zip)", "zip")
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile?.absolutePath
        } else {
            null
        }
    }

    actual suspend fun stageDataRestore(zipPath: String): Result<Int> = withContext(Dispatchers.IO) {
        DesktopRestore.stage(zipPath)
    }

    // Route through the same shutdown the close button uses: it flushes watch progress and tears
    // down MPRIS/tray/Discord. Those writes land in the directory that is about to be moved
    // aside, which is harmless, and skipping them would leave D-Bus handles behind.
    actual fun requestQuit() {
        DesktopWindowState.requestExit?.invoke()
    }

    private fun writeRestoreScript(downloadsDir: File, backup: File) {
        val script = File(downloadsDir, backup.nameWithoutExtension + "-restore.sh")
        script.writeText(
            """
            #!/usr/bin/env bash
            # Restore a Nuvio backup with Nuvio closed.
            #
            # Use this when Nuvio itself cannot help — after a failed downgrade, or on any build
            # that predates the in-app "Restore from backup" option. It does the same thing as:
            #
            #     unzip -o "${backup.name}" -d "${'$'}{XDG_CONFIG_HOME:-${'$'}HOME/.config}/nuvio"
            #
            # ...but moves your current data aside first instead of merging into it.
            set -euo pipefail

            ZIP="${'$'}{1:-${'$'}(dirname "${'$'}0")/${backup.name}}"
            DIR="${'$'}{XDG_CONFIG_HOME:-${'$'}HOME/.config}/nuvio"

            if pgrep -x nuvio >/dev/null 2>&1 || pgrep -x Nuvio >/dev/null 2>&1; then
              echo "Nuvio is still running. Quit it completely, then run this again." >&2
              exit 1
            fi
            if [ ! -f "${'$'}ZIP" ]; then
              echo "Backup not found: ${'$'}ZIP" >&2
              exit 1
            fi
            if ! command -v unzip >/dev/null 2>&1; then
              echo "This script needs 'unzip' installed (sudo apt install unzip)." >&2
              exit 1
            fi

            if [ -d "${'$'}DIR" ]; then
              ASIDE="${'$'}DIR.pre-restore-${'$'}(date +%Y%m%d-%H%M%S)"
              mv "${'$'}DIR" "${'$'}ASIDE"
              echo "Your current data was moved to ${'$'}ASIDE (delete it once you are happy)."
            fi
            mkdir -p "${'$'}DIR"
            unzip -oq "${'$'}ZIP" -d "${'$'}DIR"
            echo "Restored ${'$'}ZIP into ${'$'}DIR. Start Nuvio."

            """.trimIndent(),
        )
        runCatching {
            Files.setPosixFilePermissions(
                script.toPath(),
                PosixFilePermissions.fromString("rwxr-xr-x"),
            )
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

    // Packaged .deb/AppImage builds are always release builds.
    actual val isDebugBuild: Boolean = false
}
