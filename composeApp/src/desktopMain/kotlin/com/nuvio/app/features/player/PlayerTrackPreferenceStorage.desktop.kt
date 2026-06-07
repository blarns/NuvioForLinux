package com.nuvio.app.features.player

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.desktop.DesktopPrefs

private const val NODE = "playerTrackPrefs"
private const val subtitleTypeKey = "subtitle_type"
private const val subtitleLanguageKey = "subtitle_language"
private const val subtitleNameKey = "subtitle_name"
private const val subtitleTrackIdKey = "subtitle_track_id"
private const val addonSubtitleIdKey = "addon_subtitle_id"
private const val addonSubtitleUrlKey = "addon_subtitle_url"
private const val addonSubtitleAddonNameKey = "addon_subtitle_addon_name"
private const val audioLanguageKey = "audio_language"
private const val audioNameKey = "audio_name"
private const val audioTrackIdKey = "audio_track_id"
private const val subtitleDelayMsKey = "subtitle_delay_ms"

private fun scopedKey(field: String, contentId: String): String =
    ProfileScopedKey.of("$field|$contentId")

private fun String.normalizedStorageId(): String? =
    trim().takeIf { it.isNotBlank() }

private fun loadField(field: String, contentId: String): String? =
    DesktopPrefs.getString(NODE, scopedKey(field, contentId))?.takeIf { it.isNotBlank() }

private fun saveOptionalField(field: String, contentId: String, value: String?) {
    val key = scopedKey(field, contentId)
    if (value.isNullOrBlank()) DesktopPrefs.node(NODE).remove(key)
    else DesktopPrefs.putString(NODE, key, value)
}

internal actual object PlayerTrackPreferenceStorage {
    actual fun load(contentId: String): PersistedPlayerTrackPreference? {
        val id = contentId.normalizedStorageId() ?: return null
        val preference = PersistedPlayerTrackPreference(
            subtitleType = loadField(subtitleTypeKey, id),
            subtitleLanguage = loadField(subtitleLanguageKey, id),
            subtitleName = loadField(subtitleNameKey, id),
            subtitleTrackId = loadField(subtitleTrackIdKey, id),
            addonSubtitleId = loadField(addonSubtitleIdKey, id),
            addonSubtitleUrl = loadField(addonSubtitleUrlKey, id),
            addonSubtitleAddonName = loadField(addonSubtitleAddonNameKey, id),
            audioLanguage = loadField(audioLanguageKey, id),
            audioName = loadField(audioNameKey, id),
            audioTrackId = loadField(audioTrackIdKey, id),
        )
        return preference.takeIf {
            listOf(
                it.subtitleType, it.subtitleLanguage, it.subtitleName, it.subtitleTrackId,
                it.addonSubtitleId, it.addonSubtitleUrl, it.addonSubtitleAddonName,
                it.audioLanguage, it.audioName, it.audioTrackId,
            ).any { value -> !value.isNullOrBlank() }
        }
    }

    actual fun save(contentId: String, preference: PersistedPlayerTrackPreference) {
        val id = contentId.normalizedStorageId() ?: return
        saveOptionalField(subtitleTypeKey, id, preference.subtitleType)
        saveOptionalField(subtitleLanguageKey, id, preference.subtitleLanguage)
        saveOptionalField(subtitleNameKey, id, preference.subtitleName)
        saveOptionalField(subtitleTrackIdKey, id, preference.subtitleTrackId)
        saveOptionalField(addonSubtitleIdKey, id, preference.addonSubtitleId)
        saveOptionalField(addonSubtitleUrlKey, id, preference.addonSubtitleUrl)
        saveOptionalField(addonSubtitleAddonNameKey, id, preference.addonSubtitleAddonName)
        saveOptionalField(audioLanguageKey, id, preference.audioLanguage)
        saveOptionalField(audioNameKey, id, preference.audioName)
        saveOptionalField(audioTrackIdKey, id, preference.audioTrackId)
    }

    actual fun loadSubtitleDelayMs(videoId: String): Int? {
        val id = videoId.normalizedStorageId() ?: return null
        return DesktopPrefs.getInt(NODE, scopedKey(subtitleDelayMsKey, id))
    }

    actual fun saveSubtitleDelayMs(videoId: String, delayMs: Int) {
        val id = videoId.normalizedStorageId() ?: return
        DesktopPrefs.putInt(
            NODE,
            scopedKey(subtitleDelayMsKey, id),
            delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS),
        )
    }
}
