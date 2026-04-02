package com.nuvio.app.features.trakt

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object TraktCommentsStorage {
    private const val preferencesName = "nuvio_trakt_comments"
    private const val enabledKey = "comments_enabled"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadEnabled(): Boolean? {
        val prefs = preferences ?: return null
        val key = ProfileScopedKey.of(enabledKey)
        return if (prefs.contains(key)) prefs.getBoolean(key, true) else null
    }

    actual fun saveEnabled(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(enabledKey), enabled)
            ?.apply()
    }
}
