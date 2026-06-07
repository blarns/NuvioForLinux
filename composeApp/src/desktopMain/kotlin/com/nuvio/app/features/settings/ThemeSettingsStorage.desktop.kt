package com.nuvio.app.features.settings

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncString
import com.nuvio.app.desktop.DesktopPrefs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val NODE = "themeSettings"
private const val selectedThemeKey = "selected_theme"
private const val amoledEnabledKey = "amoled_enabled"
private const val liquidGlassNativeTabBarEnabledKey = "liquid_glass_native_tab_bar_enabled"
private const val selectedAppLanguageKey = "selected_app_language"

// theme/amoled/liquidGlass are per-profile; app language is global (matches Android).
private val profileScopedSyncKeys = listOf(selectedThemeKey, amoledEnabledKey, liquidGlassNativeTabBarEnabledKey)
private val globalSyncKeys = listOf(selectedAppLanguageKey)

internal actual object ThemeSettingsStorage {
    actual fun loadSelectedTheme(): String? =
        DesktopPrefs.getString(NODE, ProfileScopedKey.of(selectedThemeKey))

    actual fun saveSelectedTheme(themeName: String) =
        DesktopPrefs.putString(NODE, ProfileScopedKey.of(selectedThemeKey), themeName)

    actual fun loadAmoledEnabled(): Boolean? =
        DesktopPrefs.getBoolean(NODE, ProfileScopedKey.of(amoledEnabledKey))

    actual fun saveAmoledEnabled(enabled: Boolean) =
        DesktopPrefs.putBoolean(NODE, ProfileScopedKey.of(amoledEnabledKey), enabled)

    actual fun loadLiquidGlassNativeTabBarEnabled(): Boolean? =
        DesktopPrefs.getBoolean(NODE, ProfileScopedKey.of(liquidGlassNativeTabBarEnabledKey))

    actual fun saveLiquidGlassNativeTabBarEnabled(enabled: Boolean) =
        DesktopPrefs.putBoolean(NODE, ProfileScopedKey.of(liquidGlassNativeTabBarEnabledKey), enabled)

    actual fun loadSelectedAppLanguage(): String? =
        DesktopPrefs.getString(NODE, selectedAppLanguageKey)

    actual fun saveSelectedAppLanguage(languageCode: String) =
        DesktopPrefs.putString(NODE, selectedAppLanguageKey, languageCode)

    // Compose Multiplatform resolves bundled string resources from the JVM default locale,
    // so this takes effect for newly composed strings (fully on next launch). Applied at
    // startup from Main.kt and after a sync pull.
    actual fun applySelectedAppLanguage(languageCode: String) {
        val normalized = languageCode.trim().takeIf { it.isNotBlank() } ?: AppLanguage.ENGLISH.code
        runCatching {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag(normalized))
        }
    }

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadSelectedTheme()?.let { put(selectedThemeKey, encodeSyncString(it)) }
        loadAmoledEnabled()?.let { put(amoledEnabledKey, encodeSyncBoolean(it)) }
        loadLiquidGlassNativeTabBarEnabled()?.let { put(liquidGlassNativeTabBarEnabledKey, encodeSyncBoolean(it)) }
        loadSelectedAppLanguage()?.let { put(selectedAppLanguageKey, encodeSyncString(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        profileScopedSyncKeys.forEach { DesktopPrefs.node(NODE).remove(ProfileScopedKey.of(it)) }
        globalSyncKeys.forEach { DesktopPrefs.node(NODE).remove(it) }

        payload.decodeSyncString(selectedThemeKey)?.let(::saveSelectedTheme)
        payload.decodeSyncBoolean(amoledEnabledKey)?.let(::saveAmoledEnabled)
        payload.decodeSyncBoolean(liquidGlassNativeTabBarEnabledKey)?.let(::saveLiquidGlassNativeTabBarEnabled)
        payload.decodeSyncString(selectedAppLanguageKey)?.let(::saveSelectedAppLanguage)
        applySelectedAppLanguage(loadSelectedAppLanguage() ?: AppLanguage.ENGLISH.code)
    }
}
