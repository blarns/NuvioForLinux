package com.nuvio.app.features.simkl

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey
import java.security.MessageDigest
import java.security.SecureRandom

internal actual object SimklAuthStorage {
    private val store = DesktopStorage.store("nuvio_simkl_auth")

    private fun key(name: String) = ProfileScopedKey.of("simkl_$name")

    actual fun loadMetadataPayload(): String? = store.getString(key("metadata"))
    actual fun saveMetadataPayload(payload: String) { store.putString(key("metadata"), payload) }
    actual fun loadAccessToken(): String? = store.getString(key("access_token"))
    actual fun saveAccessToken(value: String?) { store.putString(key("access_token"), value) }
    actual fun loadCodeVerifier(): String? = store.getString(key("code_verifier"))
    actual fun saveCodeVerifier(value: String?) { store.putString(key("code_verifier"), value) }

    actual fun removeProfile(profileId: Int) {
        for (name in listOf("metadata", "access_token", "code_verifier")) {
            store.putString(ProfileScopedKey.of("simkl_$name", profileId), null)
        }
    }
}

private val secureRandom = SecureRandom()

internal actual object SimklPkceCrypto {
    actual fun secureRandomBytes(size: Int): ByteArray =
        ByteArray(size).also { secureRandom.nextBytes(it) }

    actual fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)
}

internal actual object SimklPlatformClock {
    actual fun nowEpochMs(): Long = System.currentTimeMillis()
}
