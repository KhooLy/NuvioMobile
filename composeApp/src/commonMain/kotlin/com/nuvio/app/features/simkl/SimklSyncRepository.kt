package com.nuvio.app.features.simkl

import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.addons.httpPostJsonWithHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object SimklSyncRepository {
    private const val baseUrl = "https://api.simkl.com"
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun activities(): SimklActivities? = get("/sync/activities")

    suspend fun playback(): List<SimklPlayback> = get("/sync/playback") ?: emptyList()

    suspend fun library(type: SimklMediaType, dateFrom: String? = null, extended: Boolean = false, includeEpisodeWatchedAt: Boolean = false): List<SimklLibraryItem> {
        val query = buildList {
            dateFrom?.let { add("date_from=$it") }
            if (extended) add("extended=full")
            if (includeEpisodeWatchedAt) add("episode_watched_at=yes")
        }.joinToString("&")
        val suffix = query.takeIf(String::isNotEmpty)?.let { "?$it" }.orEmpty()
        val response: SimklAllItemsResponse = get("/sync/all-items/${type.apiName}$suffix") ?: return emptyList()
        return when (type) {
            SimklMediaType.MOVIE -> response.movies
            SimklMediaType.SHOW -> response.shows
            SimklMediaType.ANIME -> response.anime
        }
    }

    suspend fun markWatched(items: List<SimklHistoryItem>): Boolean {
        if (items.isEmpty()) return true
        val payload = json.encodeToString(SimklHistoryRequest.from(items))
        return post<Unit>("/sync/history", payload) != null
    }

    suspend fun removeWatched(items: List<SimklHistoryItem>): Boolean {
        if (items.isEmpty()) return true
        val payload = json.encodeToString(SimklHistoryRequest.from(items))
        return post<Unit>("/sync/history/remove", payload) != null
    }

    suspend fun setListStatus(items: List<SimklListItem>, status: String): Boolean {
        if (items.isEmpty()) return true
        val payload = json.encodeToString(
            SimklListRequest(
                movies = items.filter { it.type == SimklMediaType.MOVIE }.map { SimklListMovie(it.title, it.ids, status) },
                shows = items.filter { it.type == SimklMediaType.SHOW }.map { SimklListShow(it.title, it.ids, status) },
                anime = items.filter { it.type == SimklMediaType.ANIME }.map { SimklListShow(it.title, it.ids, status) },
            ),
        )
        return post<Unit>("/sync/add-to-list", payload) != null
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
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
    val movie: SimklMedia? = null,
    val show: SimklMedia? = null,
    val anime: SimklMedia? = null,
    val seasons: List<SimklWatchedSeason>? = null,
)
@Serializable private data class SimklAllItemsResponse(
    val movies: List<SimklLibraryItem> = emptyList(),
    val shows: List<SimklLibraryItem> = emptyList(),
    val anime: List<SimklLibraryItem> = emptyList(),
)
@Serializable internal data class SimklWatchedSeason(val number: Int? = null, val episodes: List<SimklWatchedEpisode>? = null)
@Serializable internal data class SimklWatchedEpisode(val number: Int? = null, @SerialName("watched_at") val watchedAt: String? = null)
internal data class SimklHistoryItem(
    val type: SimklMediaType,
    val ids: Map<String, String>,
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val watchedAt: String? = null,
)

internal data class SimklListItem(
    val type: SimklMediaType,
    val ids: Map<String, String>,
    val title: String? = null,
)

@Serializable private data class SimklListRequest(
    val movies: List<SimklListMovie> = emptyList(),
    val shows: List<SimklListShow> = emptyList(),
    val anime: List<SimklListShow> = emptyList(),
)
@Serializable private data class SimklListMovie(val title: String? = null, val ids: Map<String, String>, val to: String)
@Serializable private data class SimklListShow(val title: String? = null, val ids: Map<String, String>, val to: String)

@Serializable
private data class SimklHistoryRequest(
    val movies: List<SimklHistoryMovie> = emptyList(),
    val shows: List<SimklHistoryShow> = emptyList(),
    val anime: List<SimklHistoryShow> = emptyList(),
) {
    companion object {
        fun from(items: List<SimklHistoryItem>): SimklHistoryRequest {
            fun media(item: SimklHistoryItem) = SimklHistoryMedia(
                title = item.title,
                ids = item.ids,
            )
            fun shows(type: SimklMediaType) = items.filter { it.type == type }
                .groupBy { it.ids }
                .map { (_, grouped) ->
                    val first = grouped.first()
                    SimklHistoryShow(
                        show = media(first),
                        seasons = grouped.mapNotNull { item ->
                            val season = item.season ?: return@mapNotNull null
                            val episode = item.episode ?: return@mapNotNull null
                            SimklHistorySeason(
                                number = season,
                                episodes = listOf(SimklHistoryEpisode(number = episode, watchedAt = item.watchedAt)),
                            )
                        }.groupBy { it.number }.map { (number, seasons) ->
                            SimklHistorySeason(number = number, episodes = seasons.flatMap { it.episodes })
                        }.takeIf { it.isNotEmpty() },
                        watchedAt = first.watchedAt,
                    )
                }
            return SimklHistoryRequest(
                movies = items.filter { it.type == SimklMediaType.MOVIE }.map { item ->
                    SimklHistoryMovie(movie = media(item), watchedAt = item.watchedAt)
                },
                shows = shows(SimklMediaType.SHOW),
                anime = shows(SimklMediaType.ANIME),
            )
        }
    }
}

@Serializable private data class SimklHistoryMedia(val title: String? = null, val ids: Map<String, String>)
@Serializable private data class SimklHistoryMovie(val movie: SimklHistoryMedia, @SerialName("watched_at") val watchedAt: String? = null)
@Serializable private data class SimklHistoryShow(val show: SimklHistoryMedia, val seasons: List<SimklHistorySeason>? = null, @SerialName("watched_at") val watchedAt: String? = null)
@Serializable private data class SimklHistorySeason(val number: Int, val episodes: List<SimklHistoryEpisode>)
@Serializable private data class SimklHistoryEpisode(val number: Int, @SerialName("watched_at") val watchedAt: String? = null)
