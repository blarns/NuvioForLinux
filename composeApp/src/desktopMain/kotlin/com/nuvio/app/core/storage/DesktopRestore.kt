package com.nuvio.app.core.storage

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.zip.ZipFile

/**
 * Fork-only. Restores a `nuvio-backup-*.zip` produced by `AppUpdaterPlatform.backupUserData()`.
 *
 * The restore is *staged*, never applied live. [DesktopStorage] hands out cached `Store` objects
 * that hold their properties in memory and rewrite the whole file on every put, and the
 * repositories above them keep their own copies again — so files swapped underneath a running app
 * would simply be overwritten by whatever the app already had. [stage] therefore only unpacks the
 * zip to a sibling directory, and [applyPendingIfNeeded] swaps it in at the next launch, before a
 * single store has been read.
 */
internal object DesktopRestore {
    private const val PENDING_DIR = "nuvio-restore-pending"
    private const val PREVIOUS_DIR = "nuvio-restore-previous"

    /**
     * Validate [zipPath] and unpack it to the staging directory. Touches nothing the running app
     * is using, so a bad zip is rejected with the user's data still exactly where it was.
     *
     * @return the number of files staged.
     */
    fun stage(zipPath: String, root: Path = DesktopStorage.rootDir): Result<Int> = runCatching {
        val archiveFile = File(zipPath)
        if (!archiveFile.isFile) throw IOException("No such file: $zipPath")

        // A Nuvio backup is a flat set of .properties stores. Anything without one is some other
        // zip the file picker was pointed at, and replacing the data directory with it would be
        // indistinguishable from wiping the app.
        val looksLikeBackup = ZipFile(archiveFile).use { archive ->
            archive.entries().asSequence().any { !it.isDirectory && it.name.endsWith(".properties") }
        }
        if (!looksLikeBackup) throw IOException("That zip does not contain any Nuvio data")

        val staging = stagingDir(root)
        deleteRecursively(staging)
        // The safety copy belongs to the restore we are about to run; drop the one from any
        // earlier restore now, while nothing depends on it. apply() must never clear it.
        deleteRecursively(previousDir(root))
        Files.createDirectories(staging)

        var staged = 0
        ZipFile(archiveFile).use { archive ->
            for (entry in archive.entries()) {
                if (entry.isDirectory) continue
                val target = staging.resolve(entry.name).normalize()
                // Zip-slip: an entry named ../../.bashrc would otherwise escape the staging dir.
                if (!target.startsWith(staging)) {
                    throw IOException("Refusing entry outside the backup: ${entry.name}")
                }
                Files.createDirectories(target.parent)
                archive.getInputStream(entry).use { input ->
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                }
                staged++
            }
        }
        staged
    }

    /**
     * Swap a staged restore into the data directory.
     *
     * MUST be called before anything reads a [DesktopStorage] store — including
     * `DesktopLegacyPrefsMigration`, whose "already migrated" flag lives in one of these files.
     * "Is a restore pending" is answered from the filesystem alone for the same reason: there is
     * no store to ask yet.
     *
     * Failures are swallowed. This runs before the window exists, so an escaping exception means
     * the app does not start at all — worse than the broken data the user was trying to fix.
     */
    fun applyPendingIfNeeded(root: Path = DesktopStorage.rootDir) {
        runCatching {
            val staging = stagingDir(root)
            if (!Files.isDirectory(staging)) return@runCatching

            Files.createDirectories(root)
            val previous = previousDir(root)
            if (Files.exists(previous)) {
                // A previous attempt died part-way through: `previous` already holds the real
                // pre-restore data and `root` is whatever we had managed to write. Keep the
                // original and discard the half-finished attempt.
                deleteRecursively(root)
            } else {
                Files.move(root, previous)
            }

            Files.createDirectories(root)
            Files.walk(staging).use { stream ->
                stream.forEach { source ->
                    val target = root.resolve(staging.relativize(source).toString())
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target)
                    } else {
                        Files.createDirectories(target.parent)
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }

            // Last: while this exists the restore is still pending, so a crash above simply
            // replays the whole thing on the next launch.
            deleteRecursively(staging)
        }
    }

    // Siblings, not children, of the data directory: a child would be swept up by
    // DesktopStorage.wipe() and would end up inside the next backup zip.
    private fun stagingDir(root: Path): Path = root.resolveSibling(PENDING_DIR)

    private fun previousDir(root: Path): Path = root.resolveSibling(PREVIOUS_DIR)

    private fun deleteRecursively(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { path ->
                runCatching { Files.deleteIfExists(path) }
            }
        }
    }
}
