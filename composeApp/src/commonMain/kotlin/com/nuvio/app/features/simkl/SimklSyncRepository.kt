package com.nuvio.app.features.simkl

import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.addons.httpPostJsonWithHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** SIMKL sync endpoints. Callers must gate incremental reads with [activities]. */
internal object SimklSyncRepository {
    private const val baseUrl = "https://api.simkl.com"
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun activities(): SimklActivities? = get("/sync/activities")

    suspend fun playback(): List<SimklPlayback> = get("/sync/playback") ?: emptyList()

    suspend fun library(type: SimklMediaType, dateFrom: String? = null): List<SimklLibraryItem> {
        val suffix = dateFrom?.let { "?date_from=$it" }.orEmpty()
        return get("/sync/all-items/${type.apiName}$suffix") ?: emptyList()
    }

    suspend fun markWatched(items: List<SimklHistoryItem>): Boolean {
        if (items.isEmpty()) return true
        val payload = json.encodeToString(SimklHistoryRequest(movies = items.filter { it.type == SimklMediaType.MOVIE }, shows = items.filter { it.type == SimklMediaType.SHOW }, anime = items.filter { it.type == SimklMediaType.ANIME }))
        return post<Unit>("/sync/history", payload) != null
    }

    private suspend inline fun <reified T> get(path: String): T? {
        val headers = SimklAuthRepository.authorizedHeaders() ?: return null
        val response = runCatching { httpGetTextWithHeaders(url(path), headers) }.getOrNull() ?: return null
        return runCatching { json.decodeFromString<T>(response) }.getOrNull()
    }
    private suspend inline fun <reified T> post(path: String, body: String): T? {
        val headers = SimklAuthRepository.authorizedHeaders() ?: return null
        val response = runCatching { httpPostJsonWithHeaders(url(path), body, headers) }.getOrNull() ?: return null
        if (T::class == Unit::class) return Unit as T
        return runCatching { json.decodeFromString<T>(response) }.getOrNull()
    }
    private fun url(path: String): String {
        val separator = if (path.contains('?')) '&' else '?'
        return "$baseUrl$path${separator}client_id=${SimklConfig.CLIENT_ID}&app-name=nuvio&app-version=${AppVersionConfig.VERSION_NAME}"
    }
}

internal enum class SimklMediaType(val apiName: String) { MOVIE("movies"), SHOW("shows"), ANIME("anime") }

@Serializable internal data class SimklActivities(
    val all: String? = null,
    @SerialName("tv_shows") val tvShows: SimklActivityBucket? = null,
    val movies: SimklActivityBucket? = null,
    val anime: SimklActivityBucket? = null,
)
@Serializable internal data class SimklActivityBucket(val all: String? = null, val playback: String? = null)
@Serializable internal data class SimklPlayback(
    val progress: Float? = null,
    val paused_at: String? = null,
    val movie: SimklMedia? = null,
    val show: SimklMedia? = null,
    val episode: SimklEpisode? = null,
)
@Serializable internal data class SimklLibraryItem(
    val status: String? = null,
    val movie: SimklMedia? = null,
    val show: SimklMedia? = null,
    val anime: SimklMedia? = null,
)
@Serializable internal data class SimklHistoryItem(val type: SimklMediaType, val ids: Map<String, String>, val watched_at: String? = null)
@Serializable private data class SimklHistoryRequest(val movies: List<SimklHistoryItem>, val shows: List<SimklHistoryItem>, val anime: List<SimklHistoryItem>)
