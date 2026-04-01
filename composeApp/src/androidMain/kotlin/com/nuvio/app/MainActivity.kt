package com.nuvio.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.core.view.WindowCompat
import com.nuvio.app.core.storage.PlatformLocalAccountDataCleaner
import com.nuvio.app.features.addons.AddonStorage
import com.nuvio.app.features.library.LibraryStorage
import com.nuvio.app.features.home.HomeCatalogSettingsStorage
import com.nuvio.app.features.mdblist.MdbListSettingsStorage
import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.profiles.ProfileStorage
import com.nuvio.app.features.details.SeasonViewModeStorage
import com.nuvio.app.features.search.SearchHistoryStorage
import com.nuvio.app.features.settings.ThemeSettingsStorage
import com.nuvio.app.features.trakt.TraktAuthStorage
import com.nuvio.app.features.trakt.handleTraktAuthCallbackUrl
import com.nuvio.app.features.tmdb.TmdbSettingsStorage
import com.nuvio.app.features.watched.WatchedStorage
import com.nuvio.app.features.streams.StreamLinkCacheStorage
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesStorage
import com.nuvio.app.features.watchprogress.WatchProgressStorage

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            navigationBarStyle = SystemBarStyle.dark(
                scrim = 0xFF020404.toInt(),
            ),
        )
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(R.color.nuvio_background)
        window.navigationBarColor = getColor(R.color.nuvio_background)
        window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        AddonStorage.initialize(applicationContext)
        LibraryStorage.initialize(applicationContext)
        WatchedStorage.initialize(applicationContext)
        HomeCatalogSettingsStorage.initialize(applicationContext)
        PlayerSettingsStorage.initialize(applicationContext)
        ProfileStorage.initialize(applicationContext)
        SearchHistoryStorage.initialize(applicationContext)
        SeasonViewModeStorage.initialize(applicationContext)
        ThemeSettingsStorage.initialize(applicationContext)
        TmdbSettingsStorage.initialize(applicationContext)
        MdbListSettingsStorage.initialize(applicationContext)
        TraktAuthStorage.initialize(applicationContext)
        ContinueWatchingPreferencesStorage.initialize(applicationContext)
        WatchProgressStorage.initialize(applicationContext)
        StreamLinkCacheStorage.initialize(applicationContext)
        PlatformLocalAccountDataCleaner.initialize(applicationContext)
        forwardTraktAuthCallback(intent)

        setContent {
            App()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forwardTraktAuthCallback(intent)
    }

    private fun forwardTraktAuthCallback(intent: Intent?) {
        val callbackUrl = intent?.dataString?.trim().orEmpty()
        if (callbackUrl.isBlank()) return
        handleTraktAuthCallbackUrl(callbackUrl)
    }
}
