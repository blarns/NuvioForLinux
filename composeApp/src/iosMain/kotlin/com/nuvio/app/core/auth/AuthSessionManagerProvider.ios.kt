package com.nuvio.app.core.auth

import io.github.jan.supabase.auth.SessionManager

// iOS keeps supabase-kt's default (Keychain-backed) session manager.
actual fun provideSessionManager(): SessionManager? = null
