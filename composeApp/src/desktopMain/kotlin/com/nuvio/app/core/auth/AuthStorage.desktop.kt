package com.nuvio.app.core.auth

/**
 * Desktop auth storage.
 *
 * Anonymous user IDs are intentionally NOT persisted on desktop — anonymous
 * sessions are ephemeral. Real sessions are persisted via DesktopSessionManager.
 * Persisting anonymous IDs causes them to shadow real Supabase sessions on
 * restart, blocking addon sync (SyncManager skips pulls for anonymous users).
 */
internal actual object AuthStorage {
    actual fun loadAnonymousUserId(): String? = null
    actual fun saveAnonymousUserId(userId: String) = Unit
    actual fun clearAnonymousUserId() = Unit
}
