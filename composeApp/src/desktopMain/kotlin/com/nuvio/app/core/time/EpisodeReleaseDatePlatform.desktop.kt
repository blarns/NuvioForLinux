package com.nuvio.app.core.time

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal actual object EpisodeReleaseDatePlatform {
    actual fun nowEpochMs(): Long = System.currentTimeMillis()

    actual fun localIsoDateAtEpochMs(epochMs: Long): String? = runCatching {
        java.time.Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(DateTimeFormatter.ISO_LOCAL_DATE)
    }.getOrNull()

    actual fun localDateTimeToEpochMs(normalizedIsoDateTime: String): Long? = runCatching {
        LocalDateTime.parse(normalizedIsoDateTime)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.recoverCatching {
        LocalDate.parse(normalizedIsoDateTime, DateTimeFormatter.ISO_LOCAL_DATE)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrNull()
}
