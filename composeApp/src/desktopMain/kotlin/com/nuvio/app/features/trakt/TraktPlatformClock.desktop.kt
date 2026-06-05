package com.nuvio.app.features.trakt

import java.time.Instant
import java.time.format.DateTimeFormatter

internal actual object TraktPlatformClock {
    actual fun nowEpochMs(): Long = System.currentTimeMillis()
    actual fun parseIsoDateTimeToEpochMs(value: String): Long? =
        try { Instant.from(DateTimeFormatter.ISO_DATE_TIME.parse(value)).toEpochMilli() } catch (_: Exception) { null }
    actual fun availableProcessors(): Int = Runtime.getRuntime().availableProcessors()
}
