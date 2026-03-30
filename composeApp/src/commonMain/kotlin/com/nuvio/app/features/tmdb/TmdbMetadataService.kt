package com.nuvio.app.features.tmdb

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.details.MetaCompany
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaPerson
import com.nuvio.app.features.details.MetaVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object TmdbMetadataService {
    private val log = Logger.withTag("TmdbMetadata")
    private val json = Json { ignoreUnknownKeys = true }

    private val enrichmentCache = mutableMapOf<String, TmdbEnrichment>()
    private val episodeCache = mutableMapOf<String, Map<Pair<Int, Int>, TmdbEpisodeEnrichment>>()

    suspend fun enrichMeta(
        meta: MetaDetails,
        fallbackItemId: String,
        settings: TmdbSettings,
    ): MetaDetails {
        if (!settings.enabled || TmdbConfig.API_KEY.isBlank()) return meta

        val tmdbType = normalizeMetaType(meta.type)
        val tmdbId = TmdbService.ensureTmdbId(meta.id, tmdbType)
            ?: TmdbService.ensureTmdbId(fallbackItemId, tmdbType)
            ?: return meta

        val needsEpisodes = settings.useEpisodes && tmdbType == "tv"
        val (enrichment, episodeMap) = coroutineScope {
            val enrichmentDeferred = async {
                fetchEnrichment(
                    tmdbId = tmdbId,
                    mediaType = tmdbType,
                    language = settings.language,
                )
            }
            val episodeDeferred = if (needsEpisodes) {
                async {
                    val seasons = meta.videos.mapNotNull { it.season }.distinct()
                    fetchEpisodeEnrichment(
                        tmdbId = tmdbId,
                        seasonNumbers = seasons,
                        language = settings.language,
                    )
                }
            } else {
                null
            }
            enrichmentDeferred.await() to episodeDeferred?.await()
        }

        return applyEnrichment(
            meta = meta,
            enrichment = enrichment,
            episodeMap = episodeMap.orEmpty(),
            settings = settings,
        )
    }

    internal fun applyEnrichment(
        meta: MetaDetails,
        enrichment: TmdbEnrichment?,
        episodeMap: Map<Pair<Int, Int>, TmdbEpisodeEnrichment>,
        settings: TmdbSettings,
    ): MetaDetails {
        if (enrichment == null && episodeMap.isEmpty()) return meta

        var updated = meta

        if (enrichment != null && settings.useArtwork) {
            updated = updated.copy(
                background = enrichment.backdrop ?: updated.background,
                poster = enrichment.poster ?: updated.poster,
                logo = enrichment.logo ?: updated.logo,
            )
        }

        if (enrichment != null && settings.useBasicInfo) {
            updated = updated.copy(
                name = enrichment.localizedTitle ?: updated.name,
                description = enrichment.description ?: updated.description,
                imdbRating = enrichment.rating?.formatRating() ?: updated.imdbRating,
                genres = enrichment.genres.ifEmpty { updated.genres },
            )
        }

        if (enrichment != null && settings.useDetails) {
            updated = updated.copy(
                releaseInfo = enrichment.releaseInfo ?: updated.releaseInfo,
                status = enrichment.status ?: updated.status,
                ageRating = enrichment.ageRating ?: updated.ageRating,
                runtime = enrichment.runtimeMinutes?.formatRuntime() ?: updated.runtime,
                country = enrichment.countries.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: updated.country,
                language = enrichment.language ?: updated.language,
            )
        }

        if (enrichment != null && settings.useCredits) {
            updated = updated.copy(
                director = enrichment.director.ifEmpty { updated.director },
                writer = enrichment.writer.ifEmpty { updated.writer },
                cast = enrichment.people.ifEmpty { updated.cast },
            )
        }

        if (enrichment != null && settings.useProductions && enrichment.productionCompanies.isNotEmpty()) {
            updated = updated.copy(productionCompanies = enrichment.productionCompanies)
        }

        if (enrichment != null && settings.useNetworks && enrichment.networks.isNotEmpty()) {
            updated = updated.copy(networks = enrichment.networks)
        }

        if (episodeMap.isNotEmpty()) {
            updated = updated.copy(
                videos = meta.videos.map { video ->
                    val key = video.season?.let { season ->
                        video.episode?.let { episode -> season to episode }
                    }
                    val enrichmentForEpisode = key?.let(episodeMap::get)
                    if (enrichmentForEpisode == null) {
                        video
                    } else {
                        video.copy(
                            title = enrichmentForEpisode.title ?: video.title,
                            overview = enrichmentForEpisode.overview ?: video.overview,
                            released = enrichmentForEpisode.airDate ?: video.released,
                            thumbnail = enrichmentForEpisode.thumbnail ?: video.thumbnail,
                            runtime = enrichmentForEpisode.runtimeMinutes ?: video.runtime,
                        )
                    }
                },
            )
        }

        return updated
    }

    private suspend fun fetchEnrichment(
        tmdbId: String,
        mediaType: String,
        language: String,
    ): TmdbEnrichment? = withContext(Dispatchers.Default) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val cacheKey = "$tmdbId:$mediaType:$normalizedLanguage"
        enrichmentCache[cacheKey]?.let { return@withContext it }

        val numericId = tmdbId.toIntOrNull() ?: return@withContext null
        val includeImageLanguage = buildString {
            append(normalizedLanguage.substringBefore("-"))
            append(",")
            append(normalizedLanguage)
            append(",en,null")
        }

        val response = coroutineScope {
            val details = async {
                fetch<TmdbDetailsResponse>(
                    endpoint = "$mediaType/$numericId",
                    query = mapOf("language" to normalizedLanguage),
                )
            }
            val credits = async {
                fetch<TmdbCreditsResponse>(
                    endpoint = "$mediaType/$numericId/credits",
                    query = mapOf("language" to normalizedLanguage),
                )
            }
            val images = async {
                fetch<TmdbImagesResponse>(
                    endpoint = "$mediaType/$numericId/images",
                    query = mapOf("include_image_language" to includeImageLanguage),
                )
            }
            val ageRating = async {
                when (mediaType) {
                    "tv" -> fetch<TmdbTvContentRatingsResponse>(
                        endpoint = "tv/$numericId/content_ratings",
                    )?.results.orEmpty().selectTvAgeRating(normalizedLanguage)
                    else -> fetch<TmdbMovieReleaseDatesResponse>(
                        endpoint = "movie/$numericId/release_dates",
                    )?.results.orEmpty().selectMovieAgeRating(normalizedLanguage)
                }
            }
            Quadruple(
                first = details.await(),
                second = credits.await(),
                third = images.await(),
                fourth = ageRating.await(),
            )
        }

        val details = response.first ?: return@withContext null
        val credits = response.second
        val images = response.third

        val genres = details.genres.mapNotNull { it.name?.trim()?.takeIf(String::isNotBlank) }
        val description = details.overview?.trim()?.takeIf(String::isNotBlank)
        val releaseInfo = details.releaseDate ?: details.firstAirDate
        val localizedTitle = listOf(details.title, details.name).firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotBlank) }
        val people = buildPeople(details = details, credits = credits, mediaType = mediaType)
        val directors = buildDirectors(details = details, credits = credits, mediaType = mediaType)
        val writers = buildWriters(credits = credits, mediaType = mediaType, hasDirectors = directors.isNotEmpty())
        val enrichment = TmdbEnrichment(
            localizedTitle = localizedTitle,
            description = description,
            genres = genres,
            backdrop = buildImageUrl(details.backdropPath, "w1280"),
            logo = buildImageUrl(images?.logos.orEmpty().selectBestLocalizedImagePath(normalizedLanguage), "w500"),
            poster = buildImageUrl(details.posterPath, "w500"),
            people = people,
            director = directors,
            writer = writers,
            releaseInfo = releaseInfo,
            rating = details.voteAverage,
            runtimeMinutes = details.runtime ?: details.episodeRunTime.firstOrNull(),
            ageRating = response.fourth,
            status = details.status?.trim()?.takeIf(String::isNotBlank),
            countries = details.productionCountries
                .mapNotNull { it.iso31661?.trim()?.takeIf(String::isNotBlank) }
                .ifEmpty { details.originCountry.filter(String::isNotBlank) },
            language = details.originalLanguage?.trim()?.takeIf(String::isNotBlank),
            productionCompanies = details.productionCompanies.mapNotNull { it.toMetaCompany() },
            networks = details.networks.mapNotNull { it.toMetaCompany() },
        )

        if (!enrichment.hasContent()) return@withContext null
        enrichmentCache[cacheKey] = enrichment
        enrichment
    }

    private suspend fun fetchEpisodeEnrichment(
        tmdbId: String,
        seasonNumbers: List<Int>,
        language: String,
    ): Map<Pair<Int, Int>, TmdbEpisodeEnrichment> = withContext(Dispatchers.Default) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val numericId = tmdbId.toIntOrNull() ?: return@withContext emptyMap()
        val normalizedSeasons = seasonNumbers.distinct().sorted()
        if (normalizedSeasons.isEmpty()) return@withContext emptyMap()

        val cacheKey = "$numericId:${normalizedSeasons.joinToString(",")}:$normalizedLanguage"
        episodeCache[cacheKey]?.let { return@withContext it }

        val pairs = coroutineScope {
            normalizedSeasons.map { season ->
                async {
                    val details = fetch<TmdbSeasonDetailsResponse>(
                        endpoint = "tv/$numericId/season/$season",
                        query = mapOf("language" to normalizedLanguage),
                    ) ?: return@async emptyMap()

                    details.episodes
                        .mapNotNull { episode ->
                            val episodeNumber = episode.episodeNumber ?: return@mapNotNull null
                            (season to episodeNumber) to TmdbEpisodeEnrichment(
                                title = episode.name?.trim()?.takeIf(String::isNotBlank),
                                overview = episode.overview?.trim()?.takeIf(String::isNotBlank),
                                thumbnail = buildImageUrl(episode.stillPath, "w500"),
                                airDate = episode.airDate?.trim()?.takeIf(String::isNotBlank),
                                runtimeMinutes = episode.runtime,
                            )
                        }
                        .toMap()
                }
            }.awaitAll()
        }

        val merged = pairs.fold(emptyMap<Pair<Int, Int>, TmdbEpisodeEnrichment>()) { acc, value -> acc + value }
        if (merged.isNotEmpty()) {
            episodeCache[cacheKey] = merged
        }
        merged
    }

    private suspend inline fun <reified T> fetch(
        endpoint: String,
        query: Map<String, String> = emptyMap(),
    ): T? {
        val url = buildTmdbUrl(endpoint = endpoint, query = query)
        return runCatching {
            json.decodeFromString<T>(httpGetText(url))
        }.onFailure { error ->
            log.w { "TMDB request failed for $endpoint: ${error.message}" }
        }.getOrNull()
    }
}

internal data class TmdbEnrichment(
    val localizedTitle: String?,
    val description: String?,
    val genres: List<String>,
    val backdrop: String?,
    val logo: String?,
    val poster: String?,
    val people: List<MetaPerson>,
    val director: List<String>,
    val writer: List<String>,
    val releaseInfo: String?,
    val rating: Double?,
    val runtimeMinutes: Int?,
    val ageRating: String?,
    val status: String?,
    val countries: List<String>,
    val language: String?,
    val productionCompanies: List<MetaCompany>,
    val networks: List<MetaCompany>,
) {
    fun hasContent(): Boolean =
        localizedTitle != null ||
            description != null ||
            genres.isNotEmpty() ||
            backdrop != null ||
            logo != null ||
            poster != null ||
            people.isNotEmpty() ||
            director.isNotEmpty() ||
            writer.isNotEmpty() ||
            releaseInfo != null ||
            rating != null ||
            runtimeMinutes != null ||
            ageRating != null ||
            status != null ||
            countries.isNotEmpty() ||
            language != null ||
            productionCompanies.isNotEmpty() ||
            networks.isNotEmpty()
}

internal data class TmdbEpisodeEnrichment(
    val title: String?,
    val overview: String?,
    val thumbnail: String?,
    val airDate: String?,
    val runtimeMinutes: Int?,
)

private fun normalizeMetaType(type: String): String =
    when (type.trim().lowercase()) {
        "series", "tv", "show", "tvshow" -> "tv"
        else -> "movie"
    }

internal fun normalizeTmdbLanguage(language: String?): String {
    val raw = language
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.replace('_', '-')
        ?: return "en"
    val parts = raw.split("-")
    val normalized = if (parts.size == 2) {
        "${parts[0].lowercase()}-${parts[1].uppercase()}"
    } else {
        raw.lowercase()
    }
    return when (normalized) {
        "es-419" -> "es-MX"
        else -> normalized
    }
}

private fun buildPeople(
    details: TmdbDetailsResponse,
    credits: TmdbCreditsResponse?,
    mediaType: String,
): List<MetaPerson> {
    val creators = if (mediaType == "tv") {
        details.createdBy.mapNotNull { creator ->
            val name = creator.name?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            MetaPerson(
                name = name,
                role = "Creator",
                photo = buildImageUrl(creator.profilePath, "w500"),
            )
        }
    } else {
        emptyList()
    }

    val directors = credits?.crew.orEmpty()
        .filter { it.job.equals("Director", ignoreCase = true) }
        .mapNotNull { crew ->
            val name = crew.name?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            MetaPerson(
                name = name,
                role = "Director",
                photo = buildImageUrl(crew.profilePath, "w500"),
            )
        }

    val writers = credits?.crew.orEmpty()
        .filter { crew ->
            val job = crew.job?.lowercase().orEmpty()
            job.contains("writer") || job.contains("screenplay")
        }
        .mapNotNull { crew ->
            val name = crew.name?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            MetaPerson(
                name = name,
                role = "Writer",
                photo = buildImageUrl(crew.profilePath, "w500"),
            )
        }

    val cast = credits?.cast.orEmpty()
        .mapNotNull { castMember ->
            val name = castMember.name?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            MetaPerson(
                name = name,
                role = castMember.character?.trim()?.takeIf(String::isNotBlank),
                photo = buildImageUrl(castMember.profilePath, "w500"),
            )
        }

    val primaryCrew = when {
        mediaType == "tv" && creators.isNotEmpty() -> creators
        mediaType != "tv" && directors.isNotEmpty() -> directors
        else -> writers
    }

    return (primaryCrew + cast)
        .dedupePeople()
}

private fun buildDirectors(
    details: TmdbDetailsResponse,
    credits: TmdbCreditsResponse?,
    mediaType: String,
): List<String> {
    if (mediaType == "tv") {
        return details.createdBy
            .mapNotNull { it.name?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
    }

    return credits?.crew.orEmpty()
        .filter { it.job.equals("Director", ignoreCase = true) }
        .mapNotNull { it.name?.trim()?.takeIf(String::isNotBlank) }
        .distinct()
}

private fun buildWriters(
    credits: TmdbCreditsResponse?,
    mediaType: String,
    hasDirectors: Boolean,
): List<String> {
    if (hasDirectors) {
        return emptyList()
    }

    return credits?.crew.orEmpty()
        .filter { crew ->
            val job = crew.job?.lowercase().orEmpty()
            job.contains("writer") || job.contains("screenplay")
        }
        .mapNotNull { it.name?.trim()?.takeIf(String::isNotBlank) }
        .distinct()
}

private fun List<MetaPerson>.dedupePeople(): List<MetaPerson> {
    val merged = linkedMapOf<String, MetaPerson>()
    forEach { person ->
        val key = person.name.lowercase() + "|" + person.role.orEmpty().lowercase()
        val existing = merged[key]
        merged[key] = if (existing == null) {
            person
        } else {
            existing.copy(photo = existing.photo ?: person.photo)
        }
    }
    return merged.values.toList()
}

private fun buildImageUrl(path: String?, size: String): String? {
    val clean = path?.trim()?.takeIf(String::isNotBlank) ?: return null
    return "https://image.tmdb.org/t/p/$size$clean"
}

private fun List<TmdbImage>.selectBestLocalizedImagePath(normalizedLanguage: String): String? {
    if (isEmpty()) return null
    val languageCode = normalizedLanguage.substringBefore("-")
    val regionCode = normalizedLanguage.substringAfter("-", "").uppercase().takeIf { it.length == 2 }
        ?: defaultLanguageRegions[languageCode]
    return sortedWith(
        compareByDescending<TmdbImage> { it.iso6391 == languageCode && it.iso31661 == regionCode }
            .thenByDescending { it.iso6391 == languageCode && it.iso31661 == null }
            .thenByDescending { it.iso6391 == languageCode }
            .thenByDescending { it.iso6391 == "en" }
            .thenByDescending { it.iso6391 == null },
    ).firstOrNull()?.filePath
}

private val defaultLanguageRegions = mapOf(
    "pt" to "PT",
    "es" to "ES",
)

private fun Double.formatRating(): String =
    if (this == 0.0) {
        "0.0"
    } else {
        (kotlin.math.round(this * 10.0) / 10.0).toString()
    }

private fun Int.formatRuntime(): String = "${this}m"

private fun List<TmdbMovieReleaseDateCountry>.selectMovieAgeRating(normalizedLanguage: String): String? {
    val preferredRegions = preferredRegions(normalizedLanguage)
    val byRegion = associateBy { it.iso31661?.uppercase() }
    preferredRegions.forEach { region ->
        val rating = byRegion[region]
            ?.releaseDates
            .orEmpty()
            .mapNotNull { it.certification?.trim() }
            .firstOrNull(String::isNotBlank)
        if (!rating.isNullOrBlank()) return rating
    }
    return asSequence()
        .flatMap { it.releaseDates.asSequence() }
        .mapNotNull { it.certification?.trim() }
        .firstOrNull(String::isNotBlank)
}

private fun List<TmdbTvContentRating>.selectTvAgeRating(normalizedLanguage: String): String? {
    val preferredRegions = preferredRegions(normalizedLanguage)
    val byRegion = associateBy { it.iso31661?.uppercase() }
    preferredRegions.forEach { region ->
        val rating = byRegion[region]?.rating?.trim()
        if (!rating.isNullOrBlank()) return rating
    }
    return mapNotNull { it.rating?.trim() }.firstOrNull(String::isNotBlank)
}

private fun preferredRegions(normalizedLanguage: String): List<String> {
    val directRegion = normalizedLanguage.substringAfter("-", "").uppercase().takeIf { it.length == 2 }
    return buildList {
        if (!directRegion.isNullOrBlank()) add(directRegion)
        add("US")
        add("GB")
    }.distinct()
}

private fun TmdbCompany.toMetaCompany(): MetaCompany? {
    val name = name?.trim()?.takeIf(String::isNotBlank) ?: return null
    return MetaCompany(
        name = name,
        logo = buildImageUrl(logoPath, "w300"),
        tmdbId = id,
    )
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)

@Serializable
private data class TmdbDetailsResponse(
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val status: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val runtime: Int? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    @SerialName("production_countries") val productionCountries: List<TmdbProductionCountry> = emptyList(),
    @SerialName("origin_country") val originCountry: List<String> = emptyList(),
    @SerialName("original_language") val originalLanguage: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("created_by") val createdBy: List<TmdbCreator> = emptyList(),
    val genres: List<TmdbNamedItem> = emptyList(),
    @SerialName("production_companies") val productionCompanies: List<TmdbCompany> = emptyList(),
    val networks: List<TmdbCompany> = emptyList(),
)

@Serializable
private data class TmdbNamedItem(
    val name: String? = null,
)

@Serializable
private data class TmdbProductionCountry(
    @SerialName("iso_3166_1") val iso31661: String? = null,
)

@Serializable
private data class TmdbCreator(
    val name: String? = null,
    val id: Int? = null,
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
private data class TmdbCreditsResponse(
    val cast: List<TmdbCastMember> = emptyList(),
    val crew: List<TmdbCrewMember> = emptyList(),
)

@Serializable
private data class TmdbCastMember(
    val id: Int? = null,
    val name: String? = null,
    val character: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
private data class TmdbCrewMember(
    val id: Int? = null,
    val name: String? = null,
    val job: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
private data class TmdbImagesResponse(
    val logos: List<TmdbImage> = emptyList(),
)

@Serializable
private data class TmdbImage(
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("iso_639_1") val iso6391: String? = null,
    @SerialName("iso_3166_1") val iso31661: String? = null,
)

@Serializable
private data class TmdbMovieReleaseDatesResponse(
    val results: List<TmdbMovieReleaseDateCountry> = emptyList(),
)

@Serializable
private data class TmdbMovieReleaseDateCountry(
    @SerialName("iso_3166_1") val iso31661: String? = null,
    @SerialName("release_dates") val releaseDates: List<TmdbReleaseDate> = emptyList(),
)

@Serializable
private data class TmdbReleaseDate(
    val certification: String? = null,
)

@Serializable
private data class TmdbTvContentRatingsResponse(
    val results: List<TmdbTvContentRating> = emptyList(),
)

@Serializable
private data class TmdbTvContentRating(
    @SerialName("iso_3166_1") val iso31661: String? = null,
    val rating: String? = null,
)

@Serializable
private data class TmdbCompany(
    val id: Int? = null,
    val name: String? = null,
    @SerialName("logo_path") val logoPath: String? = null,
)

@Serializable
private data class TmdbSeasonDetailsResponse(
    val episodes: List<TmdbEpisodeResponse> = emptyList(),
)

@Serializable
private data class TmdbEpisodeResponse(
    val name: String? = null,
    val overview: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    val runtime: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
)
