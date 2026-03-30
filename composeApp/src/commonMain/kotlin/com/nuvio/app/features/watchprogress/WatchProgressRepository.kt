package com.nuvio.app.features.watchprogress

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.episodePlaybackId
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

@Serializable
private data class WatchProgressSyncEntry(
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("video_id") val videoId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val position: Long = 0,
    val duration: Long = 0,
    @SerialName("last_watched") val lastWatched: Long = 0,
    @SerialName("progress_key") val progressKey: String = "",
)

object WatchProgressRepository {
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("WatchProgressRepository")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _uiState = MutableStateFlow(WatchProgressUiState())
    val uiState: StateFlow<WatchProgressUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var currentProfileId: Int = 1
    private var entriesByVideoId: MutableMap<String, WatchProgressEntry> = mutableMapOf()

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk(ProfileRepository.activeProfileId)
    }

    fun onProfileChanged(profileId: Int) {
        if (profileId == currentProfileId && hasLoaded) return
        loadFromDisk(profileId)
    }

    fun clearLocalState() {
        hasLoaded = false
        currentProfileId = 1
        entriesByVideoId.clear()
        _uiState.value = WatchProgressUiState()
    }

    private fun loadFromDisk(profileId: Int) {
        currentProfileId = profileId
        hasLoaded = true
        entriesByVideoId.clear()

        val payload = WatchProgressStorage.loadPayload(profileId).orEmpty().trim()
        if (payload.isNotEmpty()) {
            entriesByVideoId = WatchProgressCodec.decodeEntries(payload)
                .associateBy { it.videoId }
                .toMutableMap()
        }
        publish()
    }

    suspend fun pullFromServer(profileId: Int) {
        currentProfileId = profileId
        runCatching {
            val params = buildJsonObject { put("p_profile_id", profileId) }
            val result = SupabaseProvider.client.postgrest.rpc("sync_pull_watch_progress", params)
            val serverEntries = result.decodeList<WatchProgressSyncEntry>()

            val oldLocal = entriesByVideoId.toMap()
            val newMap = mutableMapOf<String, WatchProgressEntry>()

            serverEntries.forEach { entry ->
                val videoId = entry.videoId
                val cached = oldLocal[videoId]
                newMap[videoId] = WatchProgressEntry(
                    contentType = entry.contentType,
                    parentMetaId = entry.contentId,
                    parentMetaType = cached?.parentMetaType ?: entry.contentType,
                    videoId = videoId,
                    title = cached?.title?.takeIf { it.isNotBlank() } ?: entry.contentId,
                    logo = cached?.logo,
                    poster = cached?.poster,
                    background = cached?.background,
                    seasonNumber = entry.season,
                    episodeNumber = entry.episode,
                    episodeTitle = cached?.episodeTitle,
                    episodeThumbnail = cached?.episodeThumbnail,
                    lastPositionMs = entry.position,
                    durationMs = entry.duration,
                    lastUpdatedEpochMs = entry.lastWatched,
                    providerName = cached?.providerName,
                    providerAddonId = cached?.providerAddonId,
                    lastStreamTitle = cached?.lastStreamTitle,
                    lastStreamSubtitle = cached?.lastStreamSubtitle,
                    pauseDescription = cached?.pauseDescription,
                    lastSourceUrl = cached?.lastSourceUrl,
                    isCompleted = entry.duration > 0 && entry.position >= entry.duration,
                )
            }

            entriesByVideoId = newMap
            hasLoaded = true
            publish()
            persist()

            resolveRemoteMetadata()
        }.onFailure { e ->
            log.e(e) { "Failed to pull watch progress from server" }
        }
    }

    private fun resolveRemoteMetadata() {
        val needsResolution = entriesByVideoId.values
            .filter { it.poster == null && it.background == null }
            .groupBy { it.parentMetaId to it.contentType }

        if (needsResolution.isEmpty()) return

        syncScope.launch {
            withTimeoutOrNull(30_000L) {
                AddonRepository.awaitManifestsLoaded()
            } ?: run {
                log.w { "Timed out waiting for addon manifests" }
                return@launch
            }

            for ((key, entries) in needsResolution) {
                val (metaId, metaType) = key
                val meta = runCatching {
                    MetaDetailsRepository.fetch(metaType, metaId)
                }.getOrNull() ?: continue

                for (entry in entries) {
                    val episodeVideo = if (entry.seasonNumber != null && entry.episodeNumber != null) {
                        meta.videos.find { v ->
                            v.season == entry.seasonNumber && v.episode == entry.episodeNumber
                        }
                    } else null

                    entriesByVideoId[entry.videoId] = entry.copy(
                        title = meta.name,
                        poster = meta.poster,
                        background = meta.background,
                        logo = meta.logo,
                        episodeTitle = episodeVideo?.title ?: entry.episodeTitle,
                        episodeThumbnail = episodeVideo?.thumbnail ?: entry.episodeThumbnail,
                        pauseDescription = episodeVideo?.overview
                            ?: meta.description
                            ?: entry.pauseDescription,
                    )
                }

                publish()
            }
            persist()
        }
    }

    fun upsertPlaybackProgress(
        session: WatchProgressPlaybackSession,
        snapshot: PlayerPlaybackSnapshot,
    ) {
        ensureLoaded()
        upsert(session = session, snapshot = snapshot, persist = true)
    }

    fun flushPlaybackProgress(
        session: WatchProgressPlaybackSession,
        snapshot: PlayerPlaybackSnapshot,
    ) {
        ensureLoaded()
        upsert(session = session, snapshot = snapshot, persist = true)
    }

    fun clearProgress(videoId: String) {
        clearProgress(listOf(videoId))
    }

    fun clearProgress(videoIds: Collection<String>) {
        ensureLoaded()
        if (videoIds.isEmpty()) return
        val removedEntries = videoIds.mapNotNull { videoId ->
            entriesByVideoId.remove(videoId)
        }
        if (removedEntries.isNotEmpty()) {
            publish()
            persist()
            pushDeleteToServer(removedEntries)
        }
    }

    fun progressForVideo(videoId: String): WatchProgressEntry? {
        ensureLoaded()
        return entriesByVideoId[videoId]
    }

    fun resumeEntryForSeries(metaId: String): WatchProgressEntry? {
        ensureLoaded()
        return entriesByVideoId.values.toList().resumeEntryForSeries(metaId)
    }

    fun continueWatching(): List<WatchProgressEntry> {
        ensureLoaded()
        return entriesByVideoId.values.toList().continueWatchingEntries()
    }

    private fun upsert(
        session: WatchProgressPlaybackSession,
        snapshot: PlayerPlaybackSnapshot,
        persist: Boolean,
    ) {
        val positionMs = snapshot.positionMs.coerceAtLeast(0L)
        val durationMs = snapshot.durationMs.coerceAtLeast(0L)
        val isCompleted = isWatchProgressComplete(
            positionMs = positionMs,
            durationMs = durationMs,
            isEnded = snapshot.isEnded,
        )
        if (!isCompleted && !shouldStoreWatchProgress(positionMs = positionMs, durationMs = durationMs)) {
            return
        }

        val entry = WatchProgressEntry(
            contentType = session.contentType,
            parentMetaId = session.parentMetaId,
            parentMetaType = session.parentMetaType,
            videoId = session.videoId,
            title = session.title,
            logo = session.logo,
            poster = session.poster,
            background = session.background,
            seasonNumber = session.seasonNumber,
            episodeNumber = session.episodeNumber,
            episodeTitle = session.episodeTitle,
            episodeThumbnail = session.episodeThumbnail,
            lastPositionMs = if (isCompleted && durationMs > 0L) durationMs else positionMs,
            durationMs = durationMs,
            lastUpdatedEpochMs = WatchProgressClock.nowEpochMs(),
            providerName = session.providerName,
            providerAddonId = session.providerAddonId,
            lastStreamTitle = session.lastStreamTitle,
            lastStreamSubtitle = session.lastStreamSubtitle,
            pauseDescription = session.pauseDescription,
            lastSourceUrl = session.lastSourceUrl,
            isCompleted = isCompleted,
        )

        entriesByVideoId[session.videoId] = entry
        publish()
        if (persist) persist()
        pushScrobbleToServer(entry)
        if (entry.isCompleted && entry.isEpisode) {
            reconcileCompletedSeriesWatchedState(entry)
        }
    }

    private fun pushScrobbleToServer(entry: WatchProgressEntry) {
        syncScope.launch {
            runCatching {
                val profileId = ProfileRepository.activeProfileId
                val syncEntry = WatchProgressSyncEntry(
                    contentId = entry.parentMetaId,
                    contentType = entry.contentType,
                    videoId = entry.videoId,
                    season = entry.seasonNumber,
                    episode = entry.episodeNumber,
                    position = entry.lastPositionMs,
                    duration = entry.durationMs,
                    lastWatched = entry.lastUpdatedEpochMs,
                )
                val params = buildJsonObject {
                    put("p_profile_id", profileId)
                    put("p_entries", json.encodeToJsonElement(listOf(syncEntry)))
                }
                SupabaseProvider.client.postgrest.rpc("sync_push_watch_progress", params)
            }.onFailure { e ->
                log.e(e) { "Failed to push watch progress scrobble" }
            }
        }
    }

    private fun pushDeleteToServer(entries: Collection<WatchProgressEntry>) {
        syncScope.launch {
            runCatching {
                if (entries.isEmpty()) return@runCatching
                val profileId = ProfileRepository.activeProfileId
                val progressKeys = entries.map(::progressKeyForEntry)
                val params = buildJsonObject {
                    put("p_profile_id", profileId)
                    put("p_keys", json.encodeToJsonElement(progressKeys))
                }
                SupabaseProvider.client.postgrest.rpc("sync_delete_watch_progress", params)
            }.onFailure { e ->
                log.e(e) { "Failed to push watch progress delete" }
            }
        }
    }

    private fun publish() {
        _uiState.value = WatchProgressUiState(
            entries = entriesByVideoId.values.toList().sortedByDescending { it.lastUpdatedEpochMs },
        )
    }

    private fun persist() {
        WatchProgressStorage.savePayload(
            currentProfileId,
            WatchProgressCodec.encodeEntries(entriesByVideoId.values),
        )
    }

    private fun reconcileCompletedSeriesWatchedState(entry: WatchProgressEntry) {
        syncScope.launch {
            val meta = runCatching {
                MetaDetailsRepository.fetch(
                    type = entry.parentMetaType,
                    id = entry.parentMetaId,
                )
            }.getOrNull() ?: return@launch

            WatchedRepository.reconcileSeriesWatchedState(
                meta = meta,
                todayIsoDate = CurrentDateProvider.todayIsoDate(),
                isEpisodeCompleted = { episode ->
                    progressForVideo(meta.episodePlaybackId(episode))?.isCompleted == true
                },
            )
        }
    }

    private fun progressKeyForEntry(entry: WatchProgressEntry): String =
        if (entry.seasonNumber != null && entry.episodeNumber != null) {
            "${entry.parentMetaId}_s${entry.seasonNumber}e${entry.episodeNumber}"
        } else {
            entry.parentMetaId
        }
}
