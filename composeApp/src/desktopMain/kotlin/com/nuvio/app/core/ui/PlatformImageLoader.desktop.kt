package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.disk.DiskCache
import okio.Path.Companion.toPath
import java.io.File
import java.util.Locale

/**
 * Desktop Coil configuration.
 *
 * Coil only provisions a disk cache automatically on Android. On the JVM there is none, so
 * any image evicted from the in-memory cache gets re-fetched over the network the next time
 * it is shown — which is why home-screen collection posters/GIFs visibly reloaded each time
 * they scrolled back into view. Wiring up an on-disk cache under the platform cache directory
 * keeps them cached across scrolling and lets them survive app restarts.
 *
 * The in-memory cache is left at Coil's default (a heap-relative LRU), which already works on
 * desktop — that is why posters that go through [coil3.compose.AsyncImage] (hero, continue
 * watching, catalog rows) never flickered while collection cards did.
 */
internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder =
    diskCache {
        val dir = imageCacheDir().apply { runCatching { mkdirs() } }
        DiskCache.Builder()
            .directory(dir.absolutePath.toPath())
            .maxSizeBytes(512L * 1024 * 1024) // 512 MB of encoded image bytes on disk
            .build()
    }

/** XDG-aware per-OS cache location, mirroring the style of [com.nuvio.app.core.storage.DesktopStorage]. */
private fun imageCacheDir(): File {
    val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    val userHome = System.getProperty("user.home").orEmpty()
    return when {
        osName.contains("mac") ->
            File(userHome, "Library/Caches/Nuvio/images")
        osName.contains("win") -> {
            val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            File(localAppData ?: "$userHome\\AppData\\Local", "Nuvio\\Cache\\images")
        }
        else -> {
            val xdgCache = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
            File(xdgCache ?: "$userHome/.cache", "nuvio/images")
        }
    }
}
