package com.nuvio.app.features.notifications

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal actual object EpisodeReleaseNotificationsClock {
    actual fun isoDateFromEpochMs(epochMs: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC))
}
