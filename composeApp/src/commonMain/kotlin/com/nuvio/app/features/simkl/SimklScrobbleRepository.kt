package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trakt.TraktEpisodeMappingService
import com.nuvio.app.features.trakt.hasAnyId
import com.nuvio.app.features.trakt.parseTraktContentIds
import com.nuvio.app.features.watchprogress.WatchProgressClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

internal object SimklScrobbleRepository {
    private data class ScrobbleStamp(
        val profileId: Int,
        val action: String,
        val itemKey: String,
        val progress: Float,
        val timestampMs: Long,
    )

    private val log = Logger.withTag("SimklScrobble")
    private val json = Json { encodeDefaults = false; explicitNulls = false }
    private var lastScrobbleStamp: ScrobbleStamp? = null
    private const val minSendIntervalMs = 8_000L
    private const val progressWindow = 1.5f
    private const val maxStopRetries = 2
    private const val retryDelayMs = 1_500L
    private const val overloadedRetryDelayMs = 5_000L

    suspend fun buildItem(
        contentType: String,
        parentMetaId: String,
        videoId: String?,
        title: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        episodeTitle: String?,
    ): SimklScrobbleItem? {
        var ids = parseTraktContentIds(parentMetaId)
        if (!ids.hasAnyId() && !videoId.isNullOrBlank() && videoId != parentMetaId) {
            ids = parseTraktContentIds(videoId)
        }
        if (!ids.hasAnyId()) return null
        val mediaIds = buildMap {
            ids.imdb?.let { put("imdb", it) }
            ids.tmdb?.let { put("tmdb", it.toString()) }
        }
        if (mediaIds.isEmpty()) return null

        val isSeries = contentType.trim().lowercase() in setOf("series", "tv", "show", "tvshow") &&
            seasonNumber != null && episodeNumber != null
        if (!isSeries) return SimklScrobbleItem.Movie(title, mediaIds)

        val mapped = TraktEpisodeMappingService.resolveEpisodeMapping(
            contentId = parentMetaId,
            contentType = contentType,
            videoId = videoId,
            season = seasonNumber!!,
            episode = episodeNumber!!,
            episodeTitle = episodeTitle,
        )
        return SimklScrobbleItem.Episode(
            showTitle = title,
            ids = mediaIds,
            season = mapped?.season ?: seasonNumber,
            number = mapped?.episode ?: episodeNumber,
            episodeTitle = episodeTitle,
        )
    }

    suspend fun scrobbleStart(profileId: Int, item: SimklScrobbleItem, progressPercent: Float) =
        sendScrobble(profileId, "start", item, progressPercent)

    suspend fun scrobblePause(profileId: Int, item: SimklScrobbleItem, progressPercent: Float) =
        sendScrobble(profileId, "pause", item, progressPercent)

    suspend fun scrobbleStop(profileId: Int, item: SimklScrobbleItem, progressPercent: Float) =
        sendScrobble(profileId, "stop", item, progressPercent)

    private suspend fun sendScrobble(
        profileId: Int,
        action: String,
        item: SimklScrobbleItem,
        progressPercent: Float,
    ) {
        if (ProfileRepository.activeProfileId != profileId) return
        val headers = SimklAuthRepository.authorizedHeaders() ?: run {
            log.w { "Skipping SIMKL $action: not authenticated." }
            return
        }
        if (ProfileRepository.activeProfileId != profileId) return
        val progress = progressPercent.coerceIn(0f, 100f)
        if (shouldSkip(profileId, action, item.itemKey, progress)) return

        val body = json.encodeToString(item.toBody(progress))
        val url = "https://api.simkl.com/scrobble/$action?client_id=${SimklConfig.CLIENT_ID}&app-name=nuvio&app-version=${AppVersionConfig.VERSION_NAME}"
        val attempts = if (action == "stop") maxStopRetries + 1 else 1
        var accepted = false
        for (attempt in 1..attempts) {
            val response = runCatching {
                httpRequestRaw("POST", url, headers + ("Content-Type" to "application/json"), body)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                log.w(error) { "SIMKL $action transport failure on attempt $attempt/$attempts" }
            }.getOrNull()

            if (response == null) {
                if (attempt < attempts) delay(retryDelayMs * attempt)
                continue
            }
            if (response.status in 200..299 || response.status == 409) {
                accepted = true
                break
            }
            if (response.status in 500..599 && attempt < attempts) {
                delay(if (response.status in 502..504) overloadedRetryDelayMs else retryDelayMs * attempt)
                continue
            }
            log.w { "SIMKL $action rejected: HTTP ${response.status} ${response.statusText}; ${response.body}" }
            return
        }
        if (!accepted) return

        lastScrobbleStamp = ScrobbleStamp(profileId, action, item.itemKey, progress, WatchProgressClock.nowEpochMs())
        log.d { "SIMKL $action accepted at $progress%." }
        if (action == "stop" || action == "pause") SimklProgressRepository.refresh()
    }

    private fun shouldSkip(profileId: Int, action: String, itemKey: String, progress: Float): Boolean {
        val last = lastScrobbleStamp ?: return false
        if (action in setOf("pause", "stop") && last.action == "start" && last.profileId == profileId && last.itemKey == itemKey) return false
        return WatchProgressClock.nowEpochMs() - last.timestampMs < minSendIntervalMs &&
            last.profileId == profileId && last.action == action && last.itemKey == itemKey &&
            abs(last.progress - progress) <= progressWindow
    }
}

internal sealed interface SimklScrobbleItem {
    val itemKey: String
    val title: String?
    val ids: Map<String, String>

    data class Movie(override val title: String?, override val ids: Map<String, String>) : SimklScrobbleItem {
        override val itemKey = "movie:${ids["imdb"] ?: ids["tmdb"]}"
    }
    data class Episode(
        val showTitle: String?,
        override val ids: Map<String, String>,
        val season: Int,
        val number: Int,
        val episodeTitle: String?,
    ) : SimklScrobbleItem {
        override val title: String? get() = showTitle
        override val itemKey = "episode:${ids["imdb"] ?: ids["tmdb"]}:$season:$number"
    }
}

private fun SimklScrobbleItem.toBody(progress: Float): SimklScrobbleBody = when (this) {
    is SimklScrobbleItem.Movie -> SimklScrobbleBody(movie = SimklMedia(title, ids.mapValues { JsonPrimitive(it.value) }), progress = progress)
    is SimklScrobbleItem.Episode -> SimklScrobbleBody(
        show = SimklMedia(showTitle, ids.mapValues { JsonPrimitive(it.value) }),
        episode = SimklEpisode(season, number, episodeTitle),
        progress = progress,
    )
}

@Serializable private data class SimklScrobbleBody(
    val movie: SimklMedia? = null,
    val show: SimklMedia? = null,
    val episode: SimklEpisode? = null,
    val progress: Float,
)

@Serializable internal data class SimklMedia(
    val title: String? = null,
    val ids: Map<String, JsonPrimitive>,
    val poster: String? = null,
)

internal fun SimklMedia.id(key: String): String? = ids[key]?.content
@Serializable internal data class SimklEpisode(val season: Int? = null, val number: Int, val title: String? = null)
