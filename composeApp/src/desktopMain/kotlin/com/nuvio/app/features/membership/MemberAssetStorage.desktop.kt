package com.nuvio.app.features.membership

import com.nuvio.app.core.storage.DesktopStorage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Desktop actual for the membership cache. Upstream ships only the Android and iOS halves, so
 * this file has no counterpart to port from.
 *
 * Everything lives under [DesktopStorage.rootDir] on purpose: `PlatformLocalAccountDataCleaner`
 * wipes that whole tree on sign-out, so the access payload and the cached artwork are cleared
 * with it and the desktop cleaner needs no membership-specific key of its own. (The Android and
 * iOS cleaners each had to be taught one — see upstream's diff.)
 */
internal actual object MemberAssetStorage {
    private const val accessPayloadKey = "access_payload"
    private const val backgroundCatalogPayloadKey = "background_catalog_payload"
    private val store = DesktopStorage.store("nuvio_member_access")

    private val backgroundDirectory: Path by lazy {
        DesktopStorage.rootDir.resolve("member-assets/profile-backgrounds")
    }
    private val avatarDirectory: Path by lazy {
        DesktopStorage.rootDir.resolve("member-assets/profile-avatars")
    }

    actual fun loadAccessPayload(): String? = store.getString(accessPayloadKey)

    actual fun saveAccessPayload(payload: String) {
        store.putString(accessPayloadKey, payload)
    }

    actual fun loadProfileBackgroundCatalogPayload(): String? = store.getString(backgroundCatalogPayloadKey)

    actual fun saveProfileBackgroundCatalogPayload(payload: String) {
        store.putString(backgroundCatalogPayloadKey, payload)
    }

    actual fun loadProfileBackground(cacheKey: String): ByteArray? =
        readBytes(backgroundDirectory.resolve("${safeKey(cacheKey)}.png"))

    actual fun saveProfileBackground(cacheKey: String, bytes: ByteArray) {
        writeBytes(backgroundDirectory.resolve("${safeKey(cacheKey)}.png"), bytes)
    }

    actual fun loadProfileAvatar(cacheKey: String): String? =
        avatarDirectory.resolve(safeKey(cacheKey))
            .takeIf { Files.isRegularFile(it) && sizeOf(it) > 0L }
            ?.toUri()
            ?.toString()

    actual fun saveProfileAvatar(cacheKey: String, bytes: ByteArray): String? {
        val file = avatarDirectory.resolve(safeKey(cacheKey))
        if (!writeBytes(file, bytes)) return null
        return file.toUri().toString()
    }

    actual fun clearAccess() {
        store.remove(accessPayloadKey)
    }

    private fun readBytes(file: Path): ByteArray? = runCatching {
        if (!Files.isRegularFile(file) || Files.size(file) <= 0L) return null
        Files.readAllBytes(file)
    }.getOrNull()

    /**
     * Writes via a sibling temp file and renames, the same shape [DesktopStorage.Store] uses: a
     * kill mid-write must not leave a truncated PNG that the next launch reads back as a valid
     * cache hit and renders as a broken image.
     */
    private fun writeBytes(file: Path, bytes: ByteArray): Boolean = runCatching {
        val directory = file.parent ?: return false
        Files.createDirectories(directory)
        val temp = directory.resolve(".${file.fileName}.tmp")
        Files.write(temp, bytes)
        runCatching {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
        true
    }.getOrElse { false }

    private fun sizeOf(file: Path): Long = runCatching { Files.size(file) }.getOrDefault(0L)

    private fun safeKey(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
