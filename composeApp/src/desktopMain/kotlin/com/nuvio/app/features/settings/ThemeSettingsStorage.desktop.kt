package com.nuvio.app.features.settings

import kotlinx.serialization.json.JsonObject

internal actual object ThemeSettingsStorage {
    actual fun loadSelectedTheme(): String? = null
    actual fun saveSelectedTheme(themeName: String) {}
    actual fun loadAmoledEnabled(): Boolean? = null
    actual fun saveAmoledEnabled(enabled: Boolean) {}
    actual fun loadLiquidGlassNativeTabBarEnabled(): Boolean? = null
    actual fun saveLiquidGlassNativeTabBarEnabled(enabled: Boolean) {}
    actual fun loadSelectedAppLanguage(): String? = null
    actual fun saveSelectedAppLanguage(languageCode: String) {}
    actual fun applySelectedAppLanguage(languageCode: String) {}
    actual fun exportToSyncPayload(): JsonObject = kotlinx.serialization.json.buildJsonObject {}
    actual fun replaceFromSyncPayload(payload: JsonObject) {}
}
