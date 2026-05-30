package com.nuvio.app.features.watchprogress

import java.time.LocalDate
import java.time.format.DateTimeFormatter

actual object CurrentDateProvider {
    actual fun todayIsoDate(): String =
        LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
}
