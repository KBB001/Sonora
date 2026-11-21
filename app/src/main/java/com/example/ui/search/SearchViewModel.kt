package com.example.ui.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.common.FriendlyErrorMapper
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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

@Immutable
data class SearchUiState(
    val query: String = "",
    val selectedFilter: SearchFilter = SearchFilter.TRACKS,
    val suggestions: List<String> = emptyList(),
    val localMatches: List<Track> = emptyList(),
    val searchHistory: List<String> = emptyList(),
    val resultsState: SearchResultsState = SearchResultsState.Idle,
    val canNavigateBackInResults: Boolean = false,
    val favoriteIds: Set<String> = emptySet(),
    val downloadedIds: Set<String> = emptySet(),
    val downloadingIds: Set<String> = emptySet(),
    val playlists: List<Playlist> = emptyList(),
    val playbackState: PlaybackState = PlaybackState()
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val youTubeRepository: YouTubeRepository,
    private val libraryRepository: LibraryRepository,
    private val playbackManager: PlaybackManager,
    private val trackDownloadManager: TrackDownloadManager
) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    private val filterFlow = MutableStateFlow(SearchFilter.TRACKS)
    private val resultsFlow = MutableStateFlow<SearchResultsState>(SearchResultsState.Idle)
    private val previousResultsStackFlow = MutableStateFlow<List<SearchResultsState>>(emptyList())
    private val suppressSuggestionsFlow = MutableStateFlow(false)
    private var searchJob: Job? = null

    private val suggestionsFlow = combine(
        queryFlow.map { it.trim() },
        suppressSuggestionsFlow
    ) { trimmed, suppressed ->
        if (suppressed || trimmed.length < 2 || isYouTubePlaylistUrl(trimmed)) "" else trimmed
    }
        .debounce(220L)
        .distinctUntilChanged()
        .flatMapLatest { target ->
            if (target.isEmpty()) {
                flowOf(emptyList())
            } else {
                flow {
                    emit(youTubeRepository.getSearchSuggestions(target))
                }
            }
        }

    // Instant local matches across Favorites, Downloads, and History (0ms, works offline)
    private val localMatchesFlow = combine(
        queryFlow.map { it.trim().lowercase() }.distinctUntilChanged(),
        libraryRepository.observeDownloadedTracks(),
        libraryRepository.observeFavorites(),
        libraryRepository.observeHistory()
    ) { normalizedQuery, downloads, favorites, history ->
        if (normalizedQuery.length < 2) {
            emptyList()
        } else {
            val combined = (downloads + favorites + history).distinctBy { it.id }
            combined.filter { track ->
                track.title.contains(normalizedQuery, ignoreCase = true) ||
                    track.artist.contains(normalizedQuery, ignoreCase = true)
            }.take(8)
        }
    }.flowOn(Dispatchers.Default)

    private val filteredHistoryFlow = combine(
        queryFlow.map { it.trim() }.distinctUntilChanged(),
        libraryRepository.observeSearchHistory()
    ) { trimmed, history ->
        if (trimmed.isEmpty()) {
            history
        } else {
            val prefixMatches = history.filter { it.startsWith(trimmed, ignoreCase = true) }
            val otherMatches = history.filter {
                !it.startsWith(trimmed, ignoreCase = true) && it.contains(trimmed, ignoreCase = true)
            }
            (prefixMatches + otherMatches).take(6)
        }
    }.flowOn(Dispatchers.Default)

    private val librarySignalsFlow = combine(
        libraryRepository.observeFavoriteIds(),
        libraryRepository.observeDownloadedIds(),
        trackDownloadManager.downloadingIds,
        libraryRepository.observePlaylists(),
        playbackManager.playbackState
    ) { favoriteIds, downloadedIds, downloadingIds, playlists, playbackState ->
        LibrarySignals(
            favoriteIds = favoriteIds,
            downloadedIds = downloadedIds,
            downloadingIds = downloadingIds,
            playlists = playlists,
            playbackState = playbackState
        )
    }

    private val searchInputsFlow = combine(
        queryFlow,
        filterFlow,
        suggestionsFlow,
        localMatchesFlow,
        filteredHistoryFlow
    ) { query, filter, suggestions, localMatches, searchHistory ->
        SearchInputs(
            query = query,
            filter = filter,
            suggestions = suggestions,
            localMatches = localMatches,
            searchHistory = searchHistory
        )
    }

    val uiState: StateFlow<SearchUiState> = combine(
        searchInputsFlow,
        resultsFlow,
        previousResultsStackFlow,
        librarySignalsFlow
    ) { inputs, resultsState, backStack, signals ->
        SearchUiState(
            query = inputs.query,
            selectedFilter = inputs.filter,
            suggestions = inputs.suggestions,
            localMatches = inputs.localMatches,
            searchHistory = inputs.searchHistory,
            resultsState = resultsState,
            canNavigateBackInResults = backStack.isNotEmpty(),
            favoriteIds = signals.favoriteIds,
            downloadedIds = signals.downloadedIds,
            downloadingIds = signals.downloadingIds,
            playlists = signals.playlists,
            playbackState = signals.playbackState
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SearchUiState()
    )

    fun onQueryChange(newQuery: String) {
        suppressSuggestionsFlow.value = false
        queryFlow.value = newQuery
        if (newQuery.isBlank()) {
            searchJob?.cancel()
            previousResultsStackFlow.value = emptyList()
            resultsFlow.value = SearchResultsState.Idle
        } else if (resultsFlow.value !is SearchResultsState.Idle) {
            previousResultsStackFlow.value = emptyList()
            resultsFlow.value = SearchResultsState.Idle
        }
    }

    fun fillQueryFromSuggestion(suggestion: String) {
        suppressSuggestionsFlow.value = false
        queryFlow.value = "$suggestion "
        previousResultsStackFlow.value = emptyList()
        resultsFlow.value = SearchResultsState.Idle
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
        suppressSuggestionsFlow.value = true
        if (overrideQuery != null) {
            queryFlow.value = target
        }
        if (target.isEmpty()) return

        searchJob?.cancel()
        previousResultsStackFlow.value = emptyList()
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
                            FriendlyErrorMapper.map(error, "Не удалось загрузить плейлист YouTube")
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
                                    FriendlyErrorMapper.map(err, "Ошибка поиска треков.")
                                )
                            }
                        )
                    }
                    SearchFilter.PLAYLISTS -> {
                        youTubeRepository.searchPlaylists(target).fold(
                            onSuccess = { resultsFlow.value = SearchResultsState.Playlists(it) },
                            onFailure = { err ->
                                resultsFlow.value = SearchResultsState.Error(
                                    FriendlyErrorMapper.map(err, "Ошибка поиска плейлистов.")
                                )
                            }
                        )
                    }
                    SearchFilter.ARTISTS -> {
                        youTubeRepository.searchArtists(target).fold(
                            onSuccess = { resultsFlow.value = SearchResultsState.Artists(it) },
                            onFailure = { err ->
                                resultsFlow.value = SearchResultsState.Error(
                                    FriendlyErrorMapper.map(err, "Ошибка поиска исполнителей.")
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    fun openPlaylistSummary(summary: YouTubePlaylistSummary) {
        val current = resultsFlow.value
        if (current is SearchResultsState.Playlists || current is SearchResultsState.Artists) {
            previousResultsStackFlow.value = previousResultsStackFlow.value + current
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            resultsFlow.value = SearchResultsState.Loading
            youTubeRepository.getPlaylistWithTitle(summary.url).fold(
                onSuccess = { (title, tracks) ->
                    resultsFlow.value = SearchResultsState.Tracks(tracks, playlistTitle = title)
                },
                onFailure = { err ->
                    resultsFlow.value = SearchResultsState.Error(
                        FriendlyErrorMapper.map(err, "Не удалось открыть плейлист")
                    )
                }
            )
        }
    }

    fun openArtistSummary(artist: YouTubeArtistSummary) {
        val current = resultsFlow.value
        if (current is SearchResultsState.Artists || current is SearchResultsState.Playlists) {
            previousResultsStackFlow.value = previousResultsStackFlow.value + current
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            resultsFlow.value = SearchResultsState.Loading
            youTubeRepository.getArtistTracks(artist.url).fold(
                onSuccess = { tracks ->
                    resultsFlow.value = SearchResultsState.Tracks(tracks, playlistTitle = artist.name)
                },
                onFailure = { err ->
                    resultsFlow.value = SearchResultsState.Error(
                        FriendlyErrorMapper.map(err, "Не удалось загрузить треки артиста")
                    )
                }
            )
        }
    }

    fun navigateBackInResults(): Boolean {
        val stack = previousResultsStackFlow.value
        if (stack.isEmpty()) return false
        searchJob?.cancel()
        resultsFlow.value = stack.last()
        previousResultsStackFlow.value = stack.dropLast(1)
        return true
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
                description = "Импортировано из YouTube",
                initialTracks = tracks
            )
            onDone("Плейлист «$title» сохранён в медиатеку")
        }
    }

    fun playAll(tracks: List<Track>, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        val queue = if (shuffle) tracks.shuffled() else tracks
        playbackManager.playTrack(queue.first(), queue)
    }

    fun playTrack(track: Track, queue: List<Track>) {
        playbackManager.playTrack(track, queue)
    }

    fun playNext(track: Track) {
        playbackManager.playNext(track)
    }

    fun startTrackRadio(track: Track, onNotice: (String) -> Unit) {
        onNotice("Подбираем радио по треку...")
        playbackManager.startTrackRadio(track) { result ->
            result.onSuccess { added ->
                onNotice("Радио запущено: +$added треков")
            }.onFailure { err ->
                onNotice(FriendlyErrorMapper.map(err, "Не удалось запустить радио"))
            }
        }
    }

    fun toggleFavorite(track: Track) {
        viewModelScope.launch { libraryRepository.toggleFavorite(track) }
    }

    fun toggleDownload(track: Track, onNotice: (String) -> Unit) {
        viewModelScope.launch {
            if (track.id in uiState.value.downloadedIds) {
                trackDownloadManager.removeDownload(track.id)
                onNotice("Офлайн-копия удалена")
            } else {
                onNotice("Загрузка «${track.title}»...")
                trackDownloadManager.downloadTrack(track).onSuccess {
                    onNotice("Сохранено для офлайн-прослушивания")
                }.onFailure { err ->
                    onNotice(FriendlyErrorMapper.map(err, "Ошибка загрузки"))
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

    private data class SearchInputs(
        val query: String,
        val filter: SearchFilter,
        val suggestions: List<String>,
        val localMatches: List<Track>,
        val searchHistory: List<String>
    )

    private data class LibrarySignals(
        val favoriteIds: Set<String>,
        val downloadedIds: Set<String>,
        val downloadingIds: Set<String>,
        val playlists: List<Playlist>,
        val playbackState: PlaybackState
    )
}
