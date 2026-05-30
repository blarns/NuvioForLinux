package com.nuvio.app.features.trakt

import kotlinx.serialization.json.JsonObject

internal actual object TraktCommentsStorage {
    actual fun loadEnabled(): Boolean? = null
    actual fun saveEnabled(enabled: Boolean) {}
    actual fun exportToSyncPayload(): JsonObject = kotlinx.serialization.json.buildJsonObject {}
    actual fun replaceFromSyncPayload(payload: JsonObject) {}
}
