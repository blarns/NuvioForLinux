package com.nuvio.app.core.storage

import java.io.File
import java.util.prefs.Preferences

internal actual object PlatformLocalAccountDataCleaner {
    actual fun wipe() {
        DesktopStorage.wipe()
        wipeLegacyPrefs()
    }

    // Fork: the wipe also removes the legacy java.util.prefs data and DesktopPrefs
    // overflow files. The migration done-flag lives in DesktopStorage and is deleted
    // by wipe() above, so leaving legacy data behind would resurrect the cleared
    // account on the next boot.
    private fun wipeLegacyPrefs() {
        runCatching {
            val root = Preferences.userRoot()
            if (root.nodeExists("com/nuvio/app")) root.node("com/nuvio/app").removeNode()
            if (root.nodeExists("nuvio/player")) root.node("nuvio/player").removeNode()
            root.flush()
        }
        runCatching {
            File(System.getProperty("user.home"), ".local/share/nuvio/prefs")
                .listFiles()?.forEach { it.delete() }
        }
    }
}
