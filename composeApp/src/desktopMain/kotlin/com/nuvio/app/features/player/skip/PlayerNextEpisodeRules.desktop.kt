package com.nuvio.app.features.player.skip

import java.time.LocalDate

internal actual fun currentDateComponents(): DateComponents {
    val now = LocalDate.now()
    return DateComponents(year = now.year, month = now.monthValue, day = now.dayOfMonth)
}
