package com.nuvio.app.core.storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Comparator
import java.util.Locale
import java.util.Properties
import kotlin.io.path.exists

internal object DesktopStorage {
    private val json = Json { ignoreUnknownKeys = true }
    private val stores = mutableMapOf<String, Store>()

    val rootDir: Path by lazy {
        resolveAppDataDir().also { Files.createDirectories(it) }
    }

    fun store(name: String): Store = synchronized(stores) {
        stores.getOrPut(name) { Store(rootDir.resolve("$name.properties")) }
    }

    fun wipe() {
        synchronized(stores) {
            stores.values.forEach(Store::clearInMemory)
            stores.clear()
        }
        if (!rootDir.exists()) return
        Files.walk(rootDir).use { stream ->
            stream
                .sorted(Comparator.reverseOrder())
                .filter { it != rootDir }
                .forEach { path -> runCatching { Files.deleteIfExists(path) } }
        }
    }

    private fun resolveAppDataDir(): Path {
        val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val userHome = Paths.get(System.getProperty("user.home").orEmpty())
        return when {
            osName.contains("mac") -> userHome.resolve("Library/Application Support/Nuvio")
            osName.contains("win") -> {
                val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                (appData?.let(Paths::get) ?: userHome.resolve("AppData/Roaming")).resolve("Nuvio")
            }
            else -> {
                val xdgConfig = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                (xdgConfig?.let(Paths::get) ?: userHome.resolve(".config")).resolve("nuvio")
            }
        }
    }

    internal class Store(
        private val file: Path,
    ) {
        private val lock = Any()
        private val properties = Properties()
        private var loaded = false

        fun contains(key: String): Boolean = synchronized(lock) {
            ensureLoaded()
            properties.containsKey(key)
        }

        fun getString(key: String): String? = synchronized(lock) {
            ensureLoaded()
            properties.getProperty(key)
        }

        fun putString(key: String, value: String?) = synchronized(lock) {
            ensureLoaded()
            if (value == null) {
                properties.remove(key)
            } else {
                properties.setProperty(key, value)
            }
            persist()
        }

        fun getBoolean(key: String): Boolean? =
            getString(key)?.toBooleanStrictOrNull()

        fun putBoolean(key: String, value: Boolean) {
            putString(key, value.toString())
        }

        fun getInt(key: String): Int? =
            getString(key)?.toIntOrNull()

        fun putInt(key: String, value: Int) {
            putString(key, value.toString())
        }

        fun getFloat(key: String): Float? =
            getString(key)?.toFloatOrNull()

        fun putFloat(key: String, value: Float) {
            putString(key, value.toString())
        }

        fun getStringSet(key: String): Set<String>? =
            getString(key)?.let { payload ->
                runCatching { json.decodeFromString<List<String>>(payload).toSet() }.getOrNull()
            }

        fun putStringSet(key: String, values: Set<String>) {
            putString(key, json.encodeToString(values.toList()))
        }

        fun remove(key: String) = synchronized(lock) {
            ensureLoaded()
            properties.remove(key)
            persist()
        }

        fun removeAll(keys: Iterable<String>) = synchronized(lock) {
            ensureLoaded()
            keys.forEach(properties::remove)
            persist()
        }

        fun clearInMemory() = synchronized(lock) {
            properties.clear()
            loaded = false
        }

        private fun ensureLoaded() {
            if (loaded) return
            loaded = true
            properties.clear()
            // The backup is the other half of the atomic write in persist(): if the main file is
            // missing or unreadable — a store truncated by a pre-fix build, an interrupted
            // rename — the previous good contents are still on disk and are worth far more to
            // the user than an empty store.
            if (readInto(file)) return
            val backup = file.resolveSibling("${file.fileName}.bak")
            if (readInto(backup)) {
                println("DesktopStorage: recovered ${file.fileName} from its backup")
            }
        }

        private fun readInto(source: Path): Boolean {
            if (!source.exists()) return false
            return runCatching {
                Files.newInputStream(source).use { input -> properties.load(input) }
                properties.isNotEmpty()
            }.getOrElse {
                properties.clear()
                false
            }
        }

        /**
         * Writes the store atomically: full contents to a sibling temp file, then rename.
         *
         * ⚠ This used to open [file] itself for a truncating write. Every store goes through
         * here — session, profiles, addons, collections, watch progress, downloads — so a crash,
         * a kill, a full disk or a power cut anywhere between the truncate and the flush left a
         * zero-length file, and the next launch read that as "no data" and silently started the
         * user over. A rename is atomic on POSIX: readers see either the old file or the new
         * one, never a half-written one. The previous contents are kept as `.bak` so even a
         * failure during the rename leaves something to recover from.
         */
        private fun persist() {
            Files.createDirectories(file.parent)
            val temp = file.resolveSibling("${file.fileName}.tmp")
            Files.newOutputStream(
                temp,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { output ->
                properties.store(output, "Nuvio desktop preferences")
                // The rename is only atomic with respect to what actually reached the disk.
                if (output is FileOutputStream) output.fd.sync()
            }
            if (Files.exists(file)) {
                runCatching {
                    Files.copy(
                        file,
                        file.resolveSibling("${file.fileName}.bak"),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                // Some filesystems (and some FUSE mounts on Linux) refuse ATOMIC_MOVE. A plain
                // replace is still better than writing through the live file.
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
