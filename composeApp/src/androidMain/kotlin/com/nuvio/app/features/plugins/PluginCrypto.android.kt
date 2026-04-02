package com.nuvio.app.features.plugins

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal actual fun pluginDigestHex(algorithm: String, data: String): String {
    val normalized = algorithm.uppercase()
    val digest = MessageDigest.getInstance(normalized).digest(data.encodeToByteArray())
    return digest.joinToString(separator = "") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }
}

internal actual fun pluginHmacHex(algorithm: String, key: String, data: String): String {
    val normalized = when (algorithm.uppercase()) {
        "SHA1" -> "HmacSHA1"
        "SHA256" -> "HmacSHA256"
        "SHA512" -> "HmacSHA512"
        "MD5" -> "HmacMD5"
        else -> error("Unsupported HMAC algorithm: $algorithm")
    }
    val mac = Mac.getInstance(normalized)
    mac.init(SecretKeySpec(key.encodeToByteArray(), normalized))
    val digest = mac.doFinal(data.encodeToByteArray())
    return digest.joinToString(separator = "") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }
}
