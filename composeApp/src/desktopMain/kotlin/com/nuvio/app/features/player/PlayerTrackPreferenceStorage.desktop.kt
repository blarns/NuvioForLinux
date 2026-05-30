package com.nuvio.app.features.player

internal actual object PlayerTrackPreferenceStorage {
    actual fun load(contentId: String): PersistedPlayerTrackPreference? = null
    actual fun save(contentId: String, preference: PersistedPlayerTrackPreference) {}
    actual fun loadSubtitleDelayMs(videoId: String): Int? = null
    actual fun saveSubtitleDelayMs(videoId: String, delayMs: Int) {}
}
