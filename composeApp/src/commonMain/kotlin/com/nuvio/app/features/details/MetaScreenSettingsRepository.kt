package com.nuvio.app.features.details

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class MetaScreenSectionKey {
    ACTIONS,
    OVERVIEW,
    PRODUCTION,
    CAST,
    COMMENTS,
    TRAILERS,
    EPISODES,
    DETAILS,
    COLLECTION,
    MORE_LIKE_THIS,
}

data class MetaScreenSectionItem(
    val key: MetaScreenSectionKey,
    val title: String,
    val description: String,
    val enabled: Boolean,
    val order: Int,
)

data class MetaScreenSettingsUiState(
    val items: List<MetaScreenSectionItem> = emptyList(),
    val cinematicBackground: Boolean = false,
)

@Serializable
private data class StoredMetaScreenSectionPreference(
    val key: String,
    val enabled: Boolean = true,
    val order: Int = 0,
)

@Serializable
private data class StoredMetaScreenSettingsPayload(
    val items: List<StoredMetaScreenSectionPreference> = emptyList(),
    val cinematicBackground: Boolean = false,
)

private data class MetaScreenSectionDefinition(
    val key: MetaScreenSectionKey,
    val title: String,
    val description: String,
)

object MetaScreenSettingsRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val definitions = listOf(
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.ACTIONS,
            title = "Actions",
            description = "Play and save controls.",
        ),
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.OVERVIEW,
            title = "Overview",
            description = "Synopsis, ratings, genres, and core credits.",
        ),
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.PRODUCTION,
            title = "Production",
            description = "Studios and networks.",
        ),
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.CAST,
            title = "Cast",
            description = "Principal cast list.",
        ),
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.COMMENTS,
            title = "Comments",
            description = "Trakt comments section.",
        ),
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.TRAILERS,
            title = "Trailers",
            description = "Trailer rail and playback shortcuts.",
        ),
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.EPISODES,
            title = "Episodes",
            description = "Seasons and episode list for series.",
        ),
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.DETAILS,
            title = "Details",
            description = "Runtime, status, release, language, and related info.",
        ),
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.COLLECTION,
            title = "Collection",
            description = "Related collection or franchise rail.",
        ),
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.MORE_LIKE_THIS,
            title = "More Like This",
            description = "Recommendation rail.",
        ),
    )

    private val _uiState = MutableStateFlow(MetaScreenSettingsUiState())
    val uiState: StateFlow<MetaScreenSettingsUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var preferences: MutableMap<MetaScreenSectionKey, StoredMetaScreenSectionPreference> = mutableMapOf()
    private var cinematicBackground: Boolean = false

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true

        val payload = MetaScreenSettingsStorage.loadPayload().orEmpty().trim()
        if (payload.isNotEmpty()) {
            val parsed = runCatching {
                json.decodeFromString<StoredMetaScreenSettingsPayload>(payload)
            }.getOrNull()
            if (parsed != null) {
                cinematicBackground = parsed.cinematicBackground
                preferences = parsed.items.mapNotNull { item ->
                    val key = runCatching { MetaScreenSectionKey.valueOf(item.key) }.getOrNull() ?: return@mapNotNull null
                    key to item
                }.toMap().toMutableMap()
            }
        }

        normalizePreferences()
        publish()
        persist()
    }

    fun onProfileChanged() {
        hasLoaded = false
        preferences.clear()
        cinematicBackground = false
        _uiState.value = MetaScreenSettingsUiState()
        ensureLoaded()
    }

    fun setCinematicBackground(enabled: Boolean) {
        ensureLoaded()
        cinematicBackground = enabled
        publish()
        persist()
    }

    fun clearLocalState() {
        hasLoaded = false
        preferences.clear()
        cinematicBackground = false
        _uiState.value = MetaScreenSettingsUiState()
    }

    fun setEnabled(key: MetaScreenSectionKey, enabled: Boolean) {
        updatePreference(key) { preference ->
            preference.copy(enabled = enabled)
        }
    }

    fun resetToDefaults() {
        ensureLoaded()
        preferences.clear()
        cinematicBackground = false
        normalizePreferences()
        publish()
        persist()
    }

    fun moveByIndex(fromIndex: Int, toIndex: Int) {
        ensureLoaded()
        val orderedKeys = definitions
            .sortedBy { definition -> preferences[definition.key]?.order ?: Int.MAX_VALUE }
            .map { it.key }
            .toMutableList()
        if (fromIndex !in orderedKeys.indices || toIndex !in orderedKeys.indices) return
        if (fromIndex == toIndex) return
        orderedKeys.add(toIndex, orderedKeys.removeAt(fromIndex))
        orderedKeys.forEachIndexed { newIndex, sectionKey ->
            val current = preferences[sectionKey] ?: return@forEachIndexed
            preferences[sectionKey] = current.copy(order = newIndex)
        }
        publish()
        persist()
    }

    private fun updatePreference(
        key: MetaScreenSectionKey,
        transform: (StoredMetaScreenSectionPreference) -> StoredMetaScreenSectionPreference,
    ) {
        ensureLoaded()
        val current = preferences[key] ?: return
        preferences[key] = transform(current)
        publish()
        persist()
    }

    private fun normalizePreferences() {
        val normalized = mutableMapOf<MetaScreenSectionKey, StoredMetaScreenSectionPreference>()
        definitions.sortedBy { definition -> preferences[definition.key]?.order ?: Int.MAX_VALUE }
            .forEachIndexed { index, definition ->
                val stored = preferences[definition.key]
                normalized[definition.key] = StoredMetaScreenSectionPreference(
                    key = definition.key.name,
                    enabled = stored?.enabled ?: true,
                    order = index,
                )
            }
        preferences = normalized
    }

    private fun publish() {
        _uiState.value = MetaScreenSettingsUiState(
            items = definitions
                .sortedBy { definition -> preferences[definition.key]?.order ?: Int.MAX_VALUE }
                .map { definition ->
                    val preference = preferences[definition.key]
                    MetaScreenSectionItem(
                        key = definition.key,
                        title = definition.title,
                        description = definition.description,
                        enabled = preference?.enabled ?: true,
                        order = preference?.order ?: 0,
                    )
                },
            cinematicBackground = cinematicBackground,
        )
    }

    private fun persist() {
        MetaScreenSettingsStorage.savePayload(
            json.encodeToString(
                StoredMetaScreenSettingsPayload(
                    items = preferences.values.sortedBy { it.order },
                    cinematicBackground = cinematicBackground,
                ),
            ),
        )
    }
}