package com.nuvio.app.core.network

import io.github.jan.supabase.auth.Auth
import com.nuvio.app.core.auth.provideSessionManager
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest

object SupabaseProvider {
    val client by lazy {
        createSupabaseClient(
            supabaseUrl = SupabaseConfig.URL,
            supabaseKey = SupabaseConfig.ANON_KEY,
        ) {
            install(Auth) {
                provideSessionManager()?.let { sessionManager = it }
            }
            install(Postgrest)
            install(Functions)
        }
    }
}
