package com.nuvio.app.features.plugins

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import com.nuvio.app.features.plugins.cryptointerop.CC_MD5
import com.nuvio.app.features.plugins.cryptointerop.CC_MD5_DIGEST_LENGTH
import com.nuvio.app.features.plugins.cryptointerop.CC_SHA1
import com.nuvio.app.features.plugins.cryptointerop.CC_SHA1_DIGEST_LENGTH
import com.nuvio.app.features.plugins.cryptointerop.CC_SHA256
import com.nuvio.app.features.plugins.cryptointerop.CC_SHA256_DIGEST_LENGTH
import com.nuvio.app.features.plugins.cryptointerop.CC_SHA512
import com.nuvio.app.features.plugins.cryptointerop.CC_SHA512_DIGEST_LENGTH
import com.nuvio.app.features.plugins.cryptointerop.CCHmac
import com.nuvio.app.features.plugins.cryptointerop.kCCHmacAlgMD5
import com.nuvio.app.features.plugins.cryptointerop.kCCHmacAlgSHA1
import com.nuvio.app.features.plugins.cryptointerop.kCCHmacAlgSHA256
import com.nuvio.app.features.plugins.cryptointerop.kCCHmacAlgSHA512

private fun UByteArray.toHex(): String = joinToString(separator = "") { byte ->
    byte.toString(16).padStart(2, '0')
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun pluginDigestHex(algorithm: String, data: String): String {
    val normalized = algorithm.uppercase()
    val input = data.encodeToByteArray()
    val output = UByteArray(
        when (normalized) {
            "MD5" -> CC_MD5_DIGEST_LENGTH.toInt()
            "SHA1" -> CC_SHA1_DIGEST_LENGTH.toInt()
            "SHA256" -> CC_SHA256_DIGEST_LENGTH.toInt()
            "SHA512" -> CC_SHA512_DIGEST_LENGTH.toInt()
            else -> error("Unsupported digest algorithm: $algorithm")
        },
    )

    when (normalized) {
        "MD5" -> CC_MD5(input.refTo(0), input.size.toUInt(), output.refTo(0))
        "SHA1" -> CC_SHA1(input.refTo(0), input.size.toUInt(), output.refTo(0))
        "SHA256" -> CC_SHA256(input.refTo(0), input.size.toUInt(), output.refTo(0))
        "SHA512" -> CC_SHA512(input.refTo(0), input.size.toUInt(), output.refTo(0))
    }

    return output.toHex()
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun pluginHmacHex(algorithm: String, key: String, data: String): String {
    val normalized = algorithm.uppercase()
    val keyBytes = key.encodeToByteArray()
    val input = data.encodeToByteArray()

    val (alg, outputSize) = when (normalized) {
        "MD5" -> kCCHmacAlgMD5 to CC_MD5_DIGEST_LENGTH.toInt()
        "SHA1" -> kCCHmacAlgSHA1 to CC_SHA1_DIGEST_LENGTH.toInt()
        "SHA256" -> kCCHmacAlgSHA256 to CC_SHA256_DIGEST_LENGTH.toInt()
        "SHA512" -> kCCHmacAlgSHA512 to CC_SHA512_DIGEST_LENGTH.toInt()
        else -> error("Unsupported HMAC algorithm: $algorithm")
    }

    val output = UByteArray(outputSize)
    CCHmac(
        alg,
        keyBytes.refTo(0),
        keyBytes.size.toULong(),
        input.refTo(0),
        input.size.toULong(),
        output.refTo(0),
    )

    return output.toHex()
}
