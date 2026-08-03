package com.nuvio.app.core.auth

import com.nuvio.app.core.storage.DesktopStorage
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DesktopSessionManager : SessionManager {
    private val json = Json { ignoreUnknownKeys = true }

    // Fork-only store: the official client persists sessions via supabase-kt's
    // default manager, so it ignores this file. Their Auth picks the session up
    // again through its own login flow.
    private val store = DesktopStorage.store("nuvio_session")

    override suspend fun saveSession(session: UserSession) {
        store.putString("supabase_session", json.encodeToString(session))
    }

    override suspend fun loadSession(): UserSession? {
        val raw = store.getString("supabase_session")
            ?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            json.decodeFromString<UserSession>(raw)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun deleteSession() {
        store.putString("supabase_session", "")
    }
}
