package com.nuvio.app.features.simkl

import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trakt.parseTraktContentIds
import com.nuvio.app.features.trakt.hasAnyId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Mirrors the player's final-stop event to SIMKL. SIMKL stores resume points below 80%. */
internal object SimklScrobbleRepository {
    private val json = Json { encodeDefaults = false; explicitNulls = false }

    suspend fun scrobbleStop(
        profileId: Int,
        contentType: String,
        parentMetaId: String,
        videoId: String?,
        title: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        progressPercent: Float,
    ) {
        if (ProfileRepository.activeProfileId != profileId) return
        val headers = SimklAuthRepository.authorizedHeaders() ?: return
        val ids = parseTraktContentIds(parentMetaId).takeIf { it.hasAnyId() }
            ?: videoId?.let(::parseTraktContentIds)?.takeIf { it.hasAnyId() }
        val mediaIds = buildMap<String, String> {
            ids?.imdb?.let { put("imdb", it) }
            ids?.tmdb?.let { put("tmdb", it.toString()) }
        }
        if (mediaIds.isEmpty() && title.isNullOrBlank()) return
        val isSeries = contentType.lowercase() in setOf("series", "tv", "show", "tvshow") && seasonNumber != null && episodeNumber != null
        val body = if (isSeries) {
            SimklScrobbleBody(show = SimklMedia(title, mediaIds), episode = SimklEpisode(seasonNumber, episodeNumber), progress = progressPercent.coerceIn(0f, 100f))
        } else {
            SimklScrobbleBody(movie = SimklMedia(title, mediaIds), progress = progressPercent.coerceIn(0f, 100f))
        }
        val query = "client_id=${SimklConfig.CLIENT_ID}&app-name=nuvio&app-version=${AppVersionConfig.VERSION_NAME}"
        httpRequestRaw("POST", "https://api.simkl.com/scrobble/stop?$query", headers + ("Content-Type" to "application/json"), json.encodeToString(body))
    }
}

@kotlinx.serialization.Serializable private data class SimklScrobbleBody(
    val movie: SimklMedia? = null,
    val show: SimklMedia? = null,
    val episode: SimklEpisode? = null,
    val progress: Float,
)
@kotlinx.serialization.Serializable private data class SimklMedia(val title: String? = null, val ids: Map<String, String>)
@kotlinx.serialization.Serializable private data class SimklEpisode(val season: Int, val number: Int)
