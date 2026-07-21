package com.nuvio.app.features.simkl

import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.trakt.parseTraktContentIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class SimklLibraryUiState(
    val items: List<LibraryItem> = emptyList(),
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val errorMessage: String? = null,
)

internal object SimklLibraryRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(SimklLibraryUiState())
    val uiState: StateFlow<SimklLibraryUiState> = _uiState.asStateFlow()

    fun refreshAsync() { scope.launch { refreshNow() } }

    suspend fun refreshNow() {
        if (!SimklAuthRepository.isAuthenticated.value) {
            _uiState.value = SimklLibraryUiState()
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        runCatching {
            val activities = SimklSyncRepository.activities()
            val (cursor, existing) = SimklAuthRepository.librarySnapshot()
            if (cursor != null && cursor == activities?.all) return@runCatching existing
            val delta = buildList {
                addAll(SimklSyncRepository.library(SimklMediaType.MOVIE, dateFrom = cursor).mapNotNull { it.movie?.toLibraryItem("movie") })
                addAll(SimklSyncRepository.library(SimklMediaType.SHOW, dateFrom = cursor).mapNotNull { (it.show ?: it.anime)?.toLibraryItem("series") })
                addAll(SimklSyncRepository.library(SimklMediaType.ANIME, dateFrom = cursor).mapNotNull { (it.anime ?: it.show)?.toLibraryItem("series") })
            }
            val merged = (existing + delta).associateBy { "${it.type}:${it.id}" }.values.toList()
            SimklAuthRepository.saveLibrarySnapshot(activities?.all, merged)
            merged
        }.onSuccess { items ->
            _uiState.value = SimklLibraryUiState(items = items, hasLoaded = true)
        }.onFailure { error ->
            _uiState.value = _uiState.value.copy(isLoading = false, hasLoaded = true, errorMessage = error.message)
        }
    }
    suspend fun addToPlanToWatch(item: LibraryItem): Boolean {
        if (!SimklAuthRepository.isAuthenticated.value) return false
        val ids = item.simklIds() ?: return false
        return SimklSyncRepository.setListStatus(
            items = listOf(
                SimklListItem(
                    type = if (item.type.equals("movie", ignoreCase = true)) SimklMediaType.MOVIE else SimklMediaType.SHOW,
                    ids = ids,
                    title = item.name.takeIf { it.isNotBlank() },
                ),
            ),
            status = "plantowatch",
        )
    }

}

private fun SimklMedia.toLibraryItem(type: String): LibraryItem? {
    val imdb = id("imdb")
    val tmdb = id("tmdb")
    val contentId = imdb ?: tmdb?.let { "tmdb:$it" } ?: return null
    return LibraryItem(
        id = contentId,
        type = type,
        name = title?.takeIf(String::isNotBlank) ?: return null,
        poster = poster?.trim()?.takeIf(String::isNotEmpty)?.let { "https://simkl.in/posters/${it}_m.webp" },
        imdbId = imdb,
        tmdbId = tmdb?.toIntOrNull(),
        savedAtEpochMs = 0L,
    )
}

private fun LibraryItem.simklIds(): Map<String, String>? {
    val parsed = parseTraktContentIds(id)
    return buildMap {
        parsed.imdb?.let { put("imdb", it) }
        parsed.tmdb?.let { put("tmdb", it.toString()) }
        parsed.trakt?.let { put("trakt", it.toString()) }
    }.takeIf { it.isNotEmpty() }
}
