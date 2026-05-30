package com.nuvio.app.features.watchprogress

import com.nuvio.app.desktop.DesktopPrefs

internal actual object ResumePromptStorage {
    actual fun loadWasInPlayer(): Boolean =
        DesktopPrefs.getBoolean("resumePrompt", "wasInPlayer") ?: false
    actual fun saveWasInPlayer(value: Boolean) =
        DesktopPrefs.putBoolean("resumePrompt", "wasInPlayer", value)
    actual fun loadLastPlayerVideoId(): String? =
        DesktopPrefs.getString("resumePrompt", "lastPlayerVideoId")
    actual fun saveLastPlayerVideoId(videoId: String?) {
        if (videoId != null) DesktopPrefs.putString("resumePrompt", "lastPlayerVideoId", videoId)
        else DesktopPrefs.putString("resumePrompt", "lastPlayerVideoId", "")
    }
}
