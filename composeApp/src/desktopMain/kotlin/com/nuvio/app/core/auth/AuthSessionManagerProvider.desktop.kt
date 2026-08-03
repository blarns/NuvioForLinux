package com.nuvio.app.core.auth

import io.github.jan.supabase.auth.SessionManager

actual fun provideSessionManager(): SessionManager? = DesktopSessionManager()
