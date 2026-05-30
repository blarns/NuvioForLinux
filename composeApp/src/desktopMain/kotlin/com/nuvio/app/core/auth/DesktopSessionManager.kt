package com.nuvio.app.core.auth

import com.nuvio.app.desktop.DesktopPrefs
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DesktopSessionManager : SessionManager {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun saveSession(session: UserSession) {
        DesktopPrefs.putString("auth", "supabase_session", json.encodeToString(session))
    }

    override suspend fun loadSession(): UserSession? {
        val raw = DesktopPrefs.getString("auth", "supabase_session")
            ?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            json.decodeFromString<UserSession>(raw)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun deleteSession() {
        DesktopPrefs.putString("auth", "supabase_session", "")
    }
}
