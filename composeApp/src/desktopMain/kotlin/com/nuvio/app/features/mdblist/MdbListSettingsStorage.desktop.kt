package com.nuvio.app.features.mdblist

import kotlinx.serialization.json.JsonObject

internal actual object MdbListSettingsStorage {
    actual fun loadEnabled(): Boolean? = null
    actual fun saveEnabled(enabled: Boolean) {}
    actual fun loadApiKey(): String? = null
    actual fun saveApiKey(apiKey: String) {}
    actual fun loadUseImdb(): Boolean? = null
    actual fun saveUseImdb(enabled: Boolean) {}
    actual fun loadUseTmdb(): Boolean? = null
    actual fun saveUseTmdb(enabled: Boolean) {}
    actual fun loadUseTomatoes(): Boolean? = null
    actual fun saveUseTomatoes(enabled: Boolean) {}
    actual fun loadUseMetacritic(): Boolean? = null
    actual fun saveUseMetacritic(enabled: Boolean) {}
    actual fun loadUseTrakt(): Boolean? = null
    actual fun saveUseTrakt(enabled: Boolean) {}
    actual fun loadUseLetterboxd(): Boolean? = null
    actual fun saveUseLetterboxd(enabled: Boolean) {}
    actual fun loadUseAudience(): Boolean? = null
    actual fun saveUseAudience(enabled: Boolean) {}
    actual fun exportToSyncPayload(): JsonObject = kotlinx.serialization.json.buildJsonObject {}
    actual fun replaceFromSyncPayload(payload: JsonObject) {}
}
