@file:Suppress("NOTHING_TO_INLINE")

package com.nuvio.app.desktop

import java.io.File
import java.util.prefs.Preferences

/**
 * Shared desktop preferences helper.
 *
 * Small values (≤ 7KB) are stored in java.util.prefs.Preferences for speed.
 * Larger values automatically spill to per-node JSON files under
 * ~/.local/share/nuvio/ to avoid the 8KB Preferences limit that causes
 * VALUE_TOO_LARGE crashes.
 */
internal object DesktopPrefs {
    private val root: Preferences = Preferences.userRoot().node("com/nuvio/app")

    /** Threshold below which we use Preferences (8192 is the JVM hard limit). */
    private const val PREFS_MAX_BYTES = 7000

    /** File-based overflow directory. */
    private val overflowDir: File by lazy {
        val dir = File(System.getProperty("user.home"), ".local/share/nuvio/prefs")
        dir.mkdirs()
        dir
    }

    private fun overflowFile(node: String, key: String): File {
        // Sanitise node/key into a safe filename
        val safeName = "${node}_${key}".replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        return File(overflowDir, "$safeName.dat")
    }

    fun node(name: String): Preferences = root.node(name)

    fun getString(node: String, key: String): String? {
        // Check file overflow first
        val file = overflowFile(node, key)
        if (file.exists()) {
            return try { file.readText() } catch (_: Exception) { null }
        }
        return try { node(node).get(key, null) } catch (_: Exception) { null }
    }

    fun putString(node: String, key: String, value: String) {
        val file = overflowFile(node, key)
        if (value.toByteArray().size > PREFS_MAX_BYTES) {
            // Write to file, remove from prefs if it was there
            try {
                file.writeText(value)
                node(node).remove(key)
            } catch (e: Exception) {
                System.err.println("DesktopPrefs: failed to write overflow file: ${e.message}")
            }
        } else {
            // Small enough for Preferences
            try {
                node(node).put(key, value)
                // Clean up overflow file if we previously spilled
                if (file.exists()) file.delete()
            } catch (_: Exception) {
                // Fallback to file if prefs still fails for any reason
                try { file.writeText(value) } catch (_: Exception) {}
            }
        }
    }

    fun getBoolean(node: String, key: String): Boolean? {
        val raw = getString(node, key)
        return raw?.toBooleanStrictOrNull()
    }

    fun putBoolean(node: String, key: String, value: Boolean) =
        putString(node, key, value.toString())

    fun getInt(node: String, key: String): Int? {
        val raw = getString(node, key)
        return raw?.toIntOrNull()
    }

    fun putInt(node: String, key: String, value: Int) =
        putString(node, key, value.toString())

    fun getFloat(node: String, key: String): Float? {
        val raw = getString(node, key)
        return raw?.toFloatOrNull()
    }

    fun putFloat(node: String, key: String, value: Float) =
        putString(node, key, value.toString())

    fun getStringSet(node: String, key: String): Set<String>? {
        val raw = getString(node, key) ?: return null
        return if (raw.isEmpty()) emptySet() else raw.split("\u001F").toSet()
    }

    fun putStringSet(node: String, key: String, value: Set<String>) =
        putString(node, key, value.joinToString("\u001F"))

    fun clear(node: String) {
        try { node(node).clear() } catch (_: Exception) {}
        // Also clear any overflow files for this node
        try {
            overflowDir.listFiles()?.filter { it.name.startsWith("${node}_") }?.forEach { it.delete() }
        } catch (_: Exception) {}
    }
}
