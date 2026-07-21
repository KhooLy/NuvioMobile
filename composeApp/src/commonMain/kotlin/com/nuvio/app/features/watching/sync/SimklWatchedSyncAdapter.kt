package com.nuvio.app.features.watching.sync

import com.nuvio.app.features.simkl.SimklAuthRepository
import com.nuvio.app.features.simkl.SimklHistoryItem
import com.nuvio.app.features.simkl.SimklLibraryItem
import com.nuvio.app.features.simkl.SimklMediaType
import com.nuvio.app.features.simkl.SimklSyncRepository
import com.nuvio.app.features.simkl.id
import com.nuvio.app.features.trakt.TraktPlatformClock
import com.nuvio.app.features.trakt.parseTraktContentIds
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.normalizeWatchedMarkedAtEpochMs

/** Writes Nuvio watched changes to SIMKL history using SIMKL's native payload shape. */
object SimklWatchedSyncAdapter : WatchedSyncAdapter {
    override suspend fun pull(profileId: Int, pageSize: Int): List<WatchedItem> {
        if (!SimklAuthRepository.isAuthenticated.value) return emptyList()
        return buildList {
            addAll(SimklSyncRepository.library(SimklMediaType.MOVIE).mapNotNull { it.toMovieWatchedItem() })
            addAll(SimklSyncRepository.library(SimklMediaType.SHOW, extended = true, includeEpisodeWatchedAt = true).flatMap { it.toEpisodeWatchedItems() })
            addAll(SimklSyncRepository.library(SimklMediaType.ANIME, extended = true, includeEpisodeWatchedAt = true).flatMap { it.toEpisodeWatchedItems() })
        }.distinctBy { "${it.type}:${it.id}:${it.season}:${it.episode}" }
    }

    override suspend fun push(profileId: Int, items: Collection<WatchedItem>) {
        if (!SimklAuthRepository.isAuthenticated.value) return
        SimklSyncRepository.markWatched(items.mapNotNull(::toHistoryItem))
    }

    override suspend fun delete(profileId: Int, items: Collection<WatchedItem>) {
        if (!SimklAuthRepository.isAuthenticated.value) return
        SimklSyncRepository.removeWatched(items.mapNotNull(::toHistoryItem))
    }

    private fun toHistoryItem(item: WatchedItem): SimklHistoryItem? {
        val parsed = parseTraktContentIds(item.id)
        val ids = buildMap {
            parsed.imdb?.let { put("imdb", it) }
            parsed.tmdb?.let { put("tmdb", it.toString()) }
            parsed.trakt?.let { put("trakt", it.toString()) }
        }
        if (ids.isEmpty()) return null
        val series = item.type.trim().lowercase() in setOf("series", "show", "tv", "tvshow")
        return SimklHistoryItem(
            type = if (series) SimklMediaType.SHOW else SimklMediaType.MOVIE,
            ids = ids,
            title = item.name.takeIf { it.isNotBlank() },
            season = item.season,
            episode = item.episode,
            watchedAt = item.markedAtEpochMs.takeIf { it > 0 }?.let(::epochMsToIso),
        )
    }
}

private fun SimklLibraryItem.toMovieWatchedItem(): WatchedItem? {
    if (!status.isCompleted()) return null
    val media = movie ?: return null
    val id = media.nuvioId() ?: return null
    return WatchedItem(id = id, type = "movie", name = media.title ?: id, markedAtEpochMs = lastWatchedAt.toEpochMs())
}

private fun SimklLibraryItem.toEpisodeWatchedItems(): List<WatchedItem> {
    val media = show ?: anime ?: return emptyList()
    val id = media.nuvioId() ?: return emptyList()
    val fallback = lastWatchedAt.toEpochMs()
    return seasons.orEmpty().flatMap { season ->
        val number = season.number ?: return@flatMap emptyList()
        season.episodes.orEmpty().mapNotNull { episode ->
            val episodeNumber = episode.number ?: return@mapNotNull null
            val watchedAt = episode.watchedAt.toEpochMs()
            if (watchedAt <= 0 && !status.isCompleted()) return@mapNotNull null
            WatchedItem(id, "series", media.title ?: id, season = number, episode = episodeNumber, markedAtEpochMs = watchedAt.takeIf { it > 0 } ?: fallback)
        }
    }
}

private fun com.nuvio.app.features.simkl.SimklMedia.nuvioId(): String? =
    id("imdb")?.takeIf(String::isNotBlank) ?: id("tmdb")?.takeIf(String::isNotBlank)?.let { "tmdb:$it" } ?: id("trakt")?.takeIf(String::isNotBlank)?.let { "trakt:$it" } ?: id("simkl")?.takeIf(String::isNotBlank)
private fun String?.isCompleted(): Boolean = equals("completed", ignoreCase = true)
private fun String?.toEpochMs(): Long = this?.let(TraktPlatformClock::parseIsoDateTimeToEpochMs) ?: 0L

private fun epochMsToIso(epochMs: Long): String? {
    val normalized = normalizeWatchedMarkedAtEpochMs(epochMs)
    if (normalized < 10_000_000_000L) return null
    val totalSeconds = normalized / 1_000L
    val second = (totalSeconds % 60).toInt()
    val minute = ((totalSeconds / 60) % 60).toInt()
    val hour = ((totalSeconds / 3_600) % 24).toInt()
    var remainingDays = (totalSeconds / 86_400).toInt()
    var year = 1970
    while (remainingDays >= daysInYear(year)) {
        remainingDays -= daysInYear(year)
        year++
    }
    val monthDays = intArrayOf(31, if (isLeapYear(year)) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    var month = 0
    while (remainingDays >= monthDays[month]) {
        remainingDays -= monthDays[month]
        month++
    }
    return "${year.toString().padStart(4, '0')}-${(month + 1).toString().padStart(2, '0')}-${(remainingDays + 1).toString().padStart(2, '0')}T${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}:${second.toString().padStart(2, '0')}.000Z"
}

private fun isLeapYear(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
private fun daysInYear(year: Int): Int = if (isLeapYear(year)) 366 else 365
