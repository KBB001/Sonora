package com.example.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.TrackDownloadManager
import com.example.domain.model.PlaybackState
import com.example.domain.model.Playlist
import com.example.domain.model.SearchFilter
import com.example.domain.model.Track
import com.example.domain.model.YouTubeArtistSummary
import com.example.domain.model.YouTubePlaylistSummary
import com.example.domain.repository.LibraryRepository
import com.example.domain.repository.YouTubeRepository
import com.example.playback.PlaybackManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SearchResultsState {
    data object Idle : SearchResultsState
    data object Loading : SearchResultsState
    data class Tracks(val items: List<Track>, val playlistTitle: String? = null) : SearchResultsState
    data class Playlists(val items: List<YouTubePlaylistSummary>) : SearchResultsState
    data class Artists(val items: List<YouTubeArtistSummary>) : SearchResultsState
    data class Error(val message: String) : SearchResultsState
}

data class SearchUiState(
    val query: String = "",
    val selectedFilter: SearchFilter = SearchFilter.TRACKS,
    val suggestions: List<String> = emptyList(),
    val searchHistory: List<String> = emptyList(),
    val resultsState: SearchResultsState = SearchResultsState.Idle,
    val favoriteIds: Set<String> = emptySet(),
    val downloadedIds: Set<String> = emptySet(),
    val downloadingIds: Set<String> = emptySet(),
    val playlists: List<Playlist> = emptyList(),
    val playbackState: PlaybackState = PlaybackState()
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val youTubeRepository: YouTubeRepository,
    private val libraryRepository: LibraryRepository,
    private val playbackManager: PlaybackManager,
    private val trackDownloadManager: TrackDownloadManager
) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    private val filterFlow = MutableStateFlow(SearchFilter.TRACKS)
    private val suggestionsFlow = MutableStateFlow<List<String>>(emptyList())
    private val resultsFlow = MutableStateFlow<SearchResultsState>(SearchResultsState.Idle)
    private var searchJob: Job? = null
    private var suggestionJob: Job? = null

    val uiState: StateFlow<SearchUiState> = combine(
        queryFlow,
        filterFlow,
        suggestionsFlow,
        libraryRepository.observeSearchHistory(),
        resultsFlow,
        libraryRepository.observeFavoriteIds(),
        libraryRepository.observeDownloadedIds(),
        trackDownloadManager.downloadingIds,
        libraryRepository.observePlaylists(),
        playbackManager.playbackState
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        SearchUiState(
            query = values[0] as String,
            selectedFilter = values[1] as SearchFilter,
            suggestions = values[2] as List<String>,
            searchHistory = values[3] as List<String>,
            resultsState = values[4] as SearchResultsState,
            favoriteIds = values[5] as Set<String>,
            downloadedIds = values[6] as Set<String>,
            downloadingIds = values[7] as Set<String>,
            playlists = values[8] as List<Playlist>,
            playbackState = values[9] as PlaybackState
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SearchUiState()
    )

    fun onQueryChange(newQuery: String) {
        queryFlow.value = newQuery
        suggestionJob?.cancel()
        if (newQuery.isBlank()) {
            searchJob?.cancel()
            suggestionsFlow.value = emptyList()
            resultsFlow.value = SearchResultsState.Idle
            return
        }
        suggestionJob = viewModelScope.launch {
            delay(250L)
            suggestionsFlow.value = youTubeRepository.getSearchSuggestions(newQuery)
        }
    }

    fun selectFilter(filter: SearchFilter) {
        if (filterFlow.value == filter) return
        filterFlow.value = filter
        if (queryFlow.value.isNotBlank()) {
            submitSearch()
        }
    }

    fun submitSearch(overrideQuery: String? = null) {
        val target = (overrideQuery ?: queryFlow.value).trim()
        if (overrideQuery != null) {
            queryFlow.value = target
        }
        if (target.isEmpty()) return

        suggestionsFlow.value = emptyList()
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            resultsFlow.value = SearchResultsState.Loading
            if (!isYouTubePlaylistUrl(target)) {
                libraryRepository.recordSearchQuery(target)
            }

            if (isYouTubePlaylistUrl(target)) {
                youTubeRepository.getPlaylistWithTitle(target).fold(
                    onSuccess = { (title, tracks) ->
                        resultsFlow.value = SearchResultsState.Tracks(tracks, playlistTitle = title)
                    },
                    onFailure = { error ->
                        resultsFlow.value = SearchResultsState.Error(
                            error.localizedMessage ?: "Failed to load YouTube playlist"
                        )
                    }
                )
            } else {
                when (filterFlow.value) {
                    SearchFilter.TRACKS -> {
                        youTubeRepository.searchTracks(target).fold(
                            onSuccess = { resultsFlow.value = SearchResultsState.Tracks(it) },
                            onFailure = { err ->
                                resultsFlow.value = SearchResultsState.Error(
                                    err.localizedMessage ?: "Search failed."
                                )
                            }
                        )
                    }
                    SearchFilter.PLAYLISTS -> {
                        youTubeRepository.searchPlaylists(target).fold(
                            onSuccess = { resultsFlow.value = SearchResultsState.Playlists(it) },
                            onFailure = { err ->
                                resultsFlow.value = SearchResultsState.Error(
                                    err.localizedMessage ?: "Playlist search failed."
                                )
                            }
                        )
                    }
                    SearchFilter.ARTISTS -> {
                        youTubeRepository.searchArtists(target).fold(
                            onSuccess = { resultsFlow.value = SearchResultsState.Artists(it) },
                            onFailure = { err ->
                                resultsFlow.value = SearchResultsState.Error(
                                    err.localizedMessage ?: "Artist search failed."
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    fun openPlaylistSummary(summary: YouTubePlaylistSummary) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            resultsFlow.value = SearchResultsState.Loading
            youTubeRepository.getPlaylistWithTitle(summary.url).fold(
                onSuccess = { (title, tracks) ->
                    resultsFlow.value = SearchResultsState.Tracks(tracks, playlistTitle = title)
                },
                onFailure = { err ->
                    resultsFlow.value = SearchResultsState.Error(
                        err.localizedMessage ?: "Could not open playlist"
                    )
                }
            )
        }
    }

    fun openArtistSummary(artist: YouTubeArtistSummary) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            resultsFlow.value = SearchResultsState.Loading
            youTubeRepository.getArtistTracks(artist.url).fold(
                onSuccess = { tracks ->
                    resultsFlow.value = SearchResultsState.Tracks(tracks, playlistTitle = artist.name)
                },
                onFailure = { err ->
                    resultsFlow.value = SearchResultsState.Error(
                        err.localizedMessage ?: "Could not load artist tracks"
                    )
                }
            )
        }
    }

    fun deleteSearchQuery(query: String) {
        viewModelScope.launch { libraryRepository.deleteSearchQuery(query) }
    }

    fun clearSearchHistory() {
        viewModelScope.launch { libraryRepository.clearSearchHistory() }
    }

    fun saveLoadedPlaylistToLibrary(title: String, tracks: List<Track>, onDone: (String) -> Unit) {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            libraryRepository.createPlaylist(
                name = title,
                description = "Imported from YouTube",
                initialTracks = tracks
            )
            onDone("Saved \"$title\" to Library")
        }
    }

    fun playTrack(track: Track, queue: List<Track>) {
        playbackManager.playTrack(track, queue)
    }

    fun playNext(track: Track) {
        playbackManager.playNext(track)
    }

    fun toggleFavorite(track: Track) {
        viewModelScope.launch { libraryRepository.toggleFavorite(track) }
    }

    fun toggleDownload(track: Track, onNotice: (String) -> Unit) {
        viewModelScope.launch {
            if (track.id in uiState.value.downloadedIds) {
                trackDownloadManager.removeDownload(track.id)
                onNotice("Removed offline copy")
            } else {
                onNotice("Downloading \"${track.title}\"...")
                trackDownloadManager.downloadTrack(track).onSuccess {
                    onNotice("Saved for offline playback")
                }.onFailure { err ->
                    onNotice(err.localizedMessage ?: "Download failed")
                }
            }
        }
    }

    fun addTrackToPlaylist(playlistId: Long, track: Track) {
        viewModelScope.launch { libraryRepository.addTrackToPlaylist(playlistId, track) }
    }

    fun createPlaylistAndAddTrack(name: String, track: Track) {
        viewModelScope.launch {
            libraryRepository.createPlaylist(name = name, initialTracks = listOf(track))
        }
    }

    private fun isYouTubePlaylistUrl(input: String): Boolean {
        return input.contains("youtube.com", ignoreCase = true) &&
            input.contains("list=", ignoreCase = true)
    }
}
