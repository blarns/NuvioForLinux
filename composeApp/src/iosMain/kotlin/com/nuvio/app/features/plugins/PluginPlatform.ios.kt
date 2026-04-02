package com.nuvio.app.features.plugins

import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970

internal actual object PluginStorage {
    private const val pluginsStateKey = "plugins_state"

    actual fun loadState(profileId: Int): String? =
        NSUserDefaults.standardUserDefaults.stringForKey("${pluginsStateKey}_$profileId")

    actual fun saveState(profileId: Int, payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(
            payload,
            forKey = "${pluginsStateKey}_$profileId",
        )
    }
}

internal actual fun currentPluginPlatform(): String = "ios"

internal actual fun currentEpochMillis(): Long =
    (platform.Foundation.NSDate().timeIntervalSince1970 * 1000.0).toLong()
