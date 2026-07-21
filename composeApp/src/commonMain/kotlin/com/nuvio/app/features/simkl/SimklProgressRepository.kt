package com.nuvio.app.features.simkl

import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal object SimklProgressRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _entries = MutableStateFlow<List<WatchProgressEntry>>(emptyList())
    val entries: StateFlow<List<WatchProgressEntry>> = _entries.asStateFlow()

    fun refresh() {
        if (!SimklAuthRepository.isAuthenticated.value) { _entries.value = emptyList(); return }
        scope.launch {
            _entries.value = SimklSyncRepository.playback().mapNotNull(::toEntry)
        }
    }

    private fun toEntry(playback: SimklPlayback): WatchProgressEntry? {
        val media = playback.movie ?: playback.show ?: return null
        val id = media.id("imdb") ?: media.id("tmdb")?.let { "tmdb:$it" } ?: media.id("simkl") ?: return null
        val episode = playback.episode
        val isMovie = playback.movie != null
        return WatchProgressEntry(
            contentType = if (isMovie) "movie" else "series",
            parentMetaId = id,
            parentMetaType = if (isMovie) "movie" else "series",
            videoId = if (episode == null) id else "$id:${episode.season ?: 1}:${episode.number}",
            title = media.title ?: return null,
            seasonNumber = episode?.season,
            episodeNumber = episode?.number,
            lastPositionMs = 0L,
            durationMs = 0L,
            lastUpdatedEpochMs = WatchProgressClock.nowEpochMs(),
            progressPercent = playback.progress,
            source = "simkl",
            progressKey = "simkl:$id:${episode?.season ?: 0}:${episode?.number ?: 0}",
        )
    }
}
