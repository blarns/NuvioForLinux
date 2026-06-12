package com.nuvio.app.features.addons

import co.touchlab.kermit.Logger
import com.nuvio.app.core.ui.NuvioToastController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import kotlin.time.Duration.Companion.hours

/**
 * Periodically re-fetches enabled addon manifests during long-running sessions and
 * surfaces a prompt when an addon server starts serving a newer version than the
 * one currently loaded. Manifests are already fetched fresh at every boot, so this
 * only matters while the app stays open past the check interval.
 */
object AddonUpdateChecker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("AddonUpdateChecker")

    // ~twice a week
    private val checkInterval = 84.hours

    // manifestUrl -> version currently served by the addon server
    private val _availableVersions = MutableStateFlow<Map<String, String>>(emptyMap())
    val availableVersions: StateFlow<Map<String, String>> = _availableVersions.asStateFlow()

    private var started = false

    fun ensureStarted() {
        if (started) return
        started = true
        scope.launch {
            while (true) {
                delay(checkInterval)
                runCatching { checkForUpdates() }
                    .onFailure { error -> log.e(error) { "checkForUpdates() — FAILED" } }
            }
        }
    }

    private suspend fun checkForUpdates() {
        val addons = AddonRepository.uiState.value.addons
            .filter { it.enabled && it.manifest != null }
            .distinctBy { it.manifestUrl }
        if (addons.isEmpty()) return

        val remoteVersions = mutableMapOf<String, String>()
        val updatedNames = mutableListOf<String>()
        addons.forEach { addon ->
            val loaded = addon.manifest ?: return@forEach
            val remote = runCatching {
                AddonManifestParser.parse(
                    manifestUrl = addon.manifestUrl,
                    payload = httpGetText(addon.manifestUrl),
                )
            }.getOrNull() ?: return@forEach
            if (remote.version != loaded.version) {
                remoteVersions[addon.manifestUrl] = remote.version
                updatedNames += addon.displayTitle
            }
        }

        log.d { "checkForUpdates() — checked ${addons.size} addon(s), ${remoteVersions.size} with new versions" }
        _availableVersions.value = remoteVersions
        if (updatedNames.isNotEmpty()) {
            NuvioToastController.show(
                message = getString(Res.string.addons_updates_toast, updatedNames.joinToString()),
                durationMillis = 6000L,
            )
        }
    }
}
