package com.nuvio.app.features.profiles

import java.security.MessageDigest

internal actual object ProfilePinCrypto {
    actual fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
