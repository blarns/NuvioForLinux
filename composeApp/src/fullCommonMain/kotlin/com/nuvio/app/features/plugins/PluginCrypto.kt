package com.nuvio.app.features.plugins

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal expect fun pluginDigestHex(algorithm: String, data: String): String

internal expect fun pluginHmacHex(algorithm: String, key: String, data: String): String

@OptIn(ExperimentalEncodingApi::class)
internal fun pluginBase64Encode(data: String): String =
    Base64.encode(data.encodeToByteArray())

@OptIn(ExperimentalEncodingApi::class)
internal fun pluginBase64Decode(data: String): String {
    val normalized = data.trim().replace("\n", "").replace("\r", "").replace(" ", "")
    val decoded = Base64.decode(normalized)
    return decoded.decodeToString()
}

internal fun pluginUtf8ToHex(value: String): String =
    value.encodeToByteArray().joinToString(separator = "") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }

internal fun pluginHexToUtf8(hex: String): String {
    val normalized = hex.trim().lowercase()
        .replace(" ", "")
        .removePrefix("0x")
    if (normalized.isEmpty()) return ""

    val evenHex = if (normalized.length % 2 == 0) normalized else "0$normalized"
    val out = ByteArray(evenHex.length / 2)
    for (index in out.indices) {
        val part = evenHex.substring(index * 2, index * 2 + 2)
        out[index] = part.toInt(16).toByte()
    }
    return out.decodeToString()
}
