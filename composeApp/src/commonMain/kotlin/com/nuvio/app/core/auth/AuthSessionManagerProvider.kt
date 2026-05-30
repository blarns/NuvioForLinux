package com.nuvio.app.core.auth

import io.github.jan.supabase.auth.SessionManager

expect fun provideSessionManager(): SessionManager?
