package com.example.ui.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.common.NetworkMonitor
import com.example.data.local.TrackDownloadManager
import com.example.domain.model.PlaybackState
import com.example.domain.model.Playlist
import com.example.domain.model.Track
import com.example.domain.model.YouTubeAccount
import com.example.domain.repository.LibraryRepository
import com.example.domain.repository.YouTubeAccountRepository
import com.example.domain.repository.YouTubeRepository
import com.example.playback.PlaybackManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface DiscoverUiState {
    data object Loading : DiscoverUiState
    data class Success(val tracks: List<Track>) : DiscoverUiState
    data class Error(val message: String) : DiscoverUiState
}

@Immutable
data class HomeUiState(
    val selectedMood: String = MOOD_CHIPS.first(),
    val moods: List<String> = MOOD_CHIPS,
    val discoverState: DiscoverUiState = DiscoverUiState.Loading,
    val history: List<Track> = emptyList(),
    val favorites: List<Track> = emptyList(),
    val downloadedTracks: List<Track> = emptyList(),
    val quickPicks: List<Track> = emptyList(),
    val favoriteIds: Set<String> = emptySet(),
    val downloadedIds: Set<String> = emptySet(),
    val downloadingIds: Set<String> = emptySet(),
    val playlists: List<Playlist> = emptyList(),
    val playbackState: PlaybackState = PlaybackState(),
    val activeAccount: YouTubeAccount? = null,
    val isOnline: Boolean = true
) {
    companion object {
        val MOOD_CHIPS = listOf(
            "Топ-чарты",
            "Lo-Fi биты",
            "Synthwave",
            "Акустика",
            "Глубокий фокус",
            "Электроника",
            "Инди-рок",
            "Джаз"
        )

        fun toSearchQuery(mood: String): String = when (mood) {
            "Топ-чарты" -> "Top Hits Mix music audio"
            "Lo-Fi биты" -> "Lo-Fi Beats chillhop music"
            "Synthwave" -> "Synthwave retrowave music"
            "Акустика" -> "Acoustic Chill indie guitar music"
            "Глубокий фокус" -> "Deep Focus ambient instrumental music"
            "Электроника" -> "Electronic dance chill house music"
            "Инди-рок" -> "Indie Rock alternative music"
            "Джаз" -> "Smooth Jazz cafe piano music"
            else -> "$mood music audio"
        }
    }
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val youTubeRepository: YouTubeRepository,
    private val libraryRepository: LibraryRepository,
    private val playbackManager: PlaybackManager,
    private val trackDownloadManager: TrackDownloadManager,
    private val youTubeAccountRepository: YouTubeAccountRepository,
    private val networkMonitor: NetworkMonitor
) : ViewModel() {

    private val selectedMood = MutableStateFlow(HomeUiState.MOOD_CHIPS.first())
    private val discoverState = MutableStateFlow<DiscoverUiState>(DiscoverUiState.Loading)
    private var discoverJob: Job? = null

    val uiState: StateFlow<HomeUiState> = combine(
        selectedMood,
        discoverState,
        libraryRepository.observeHistory(),
        libraryRepository.observeFavorites(),
        libraryRepository.observeDownloadedTracks(),
        libraryRepository.observeFavoriteIds(),
        libraryRepository.observeDownloadedIds(),
        trackDownloadManager.downloadingIds,
        libraryRepository.observePlaylists(),
        playbackManager.playbackState,
        youTubeAccountRepository.activeAccount,
        networkMonitor.isOnline
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val history = values[2] as List<Track>
        @Suppress("UNCHECKED_CAST")
        val favorites = values[3] as List<Track>
        @Suppress("UNCHECKED_CAST")
        val downloads = values[4] as List<Track>
        val quickPicks = buildList {
            addAll(favorites.take(4))
            addAll(history.take(6))
            addAll(downloads.take(4))
        }.distinctBy { it.id }.take(6)

        @Suppress("UNCHECKED_CAST")
        HomeUiState(
            selectedMood = values[0] as String,
            discoverState = values[1] as DiscoverUiState,
            history = history,
            favorites = favorites,
            downloadedTracks = downloads,
            quickPicks = quickPicks,
            favoriteIds = values[5] as Set<String>,
            downloadedIds = values[6] as Set<String>,
            downloadingIds = values[7] as Set<String>,
            playlists = values[8] as List<Playlist>,
            playbackState = values[9] as PlaybackState,
            activeAccount = values[10] as YouTubeAccount?,
            isOnline = values[11] as Boolean
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState()
    )

    init {
        loadMoodTracks(HomeUiState.MOOD_CHIPS.first())
    }

    fun selectMood(mood: String) {
        if (selectedMood.value == mood && discoverState.value is DiscoverUiState.Success) return
        selectedMood.value = mood
        loadMoodTracks(mood)
    }

    fun retryDiscover() {
        loadMoodTracks(selectedMood.value)
    }

    private fun loadMoodTracks(mood: String) {
        discoverJob?.cancel()
        discoverJob = viewModelScope.launch {
            discoverState.value = DiscoverUiState.Loading
            youTubeRepository.searchTracks(HomeUiState.toSearchQuery(mood)).fold(
                onSuccess = { tracks ->
                    discoverState.value = DiscoverUiState.Success(tracks.take(15))
                },
                onFailure = { error ->
                    discoverState.value = DiscoverUiState.Error(
                        error.localizedMessage ?: "Не удалось подключиться к YouTube."
                    )
                }
            )
        }
    }

    fun playTrack(track: Track, queue: List<Track>) {
        playbackManager.playTrack(track, queue)
    }

    fun playMix(shuffle: Boolean = false) {
        val state = uiState.value
        val source = when {
            state.discoverState is DiscoverUiState.Success && state.discoverState.tracks.isNotEmpty() ->
                state.discoverState.tracks
            state.quickPicks.isNotEmpty() -> state.quickPicks
            state.downloadedTracks.isNotEmpty() -> state.downloadedTracks
            else -> emptyList()
        }
        if (source.isEmpty()) return
        val queue = if (shuffle) source.shuffled() else source
        playbackManager.playTrack(queue.first(), queue)
    }

    fun resumeLastOrMix(onEmpty: () -> Unit) {
        val state = uiState.value
        val active = state.playbackState.currentTrack
        if (active != null) {
            playbackManager.togglePlayPause()
            return
        }
        val firstHistory = state.history.firstOrNull()
        if (firstHistory != null) {
            playbackManager.playTrack(firstHistory, state.history)
            return
        }
        val discoverTracks = (state.discoverState as? DiscoverUiState.Success)?.tracks
        if (!discoverTracks.isNullOrEmpty()) {
            playbackManager.playTrack(discoverTracks.first(), discoverTracks)
        } else if (state.downloadedTracks.isNotEmpty()) {
            playbackManager.playTrack(state.downloadedTracks.first(), state.downloadedTracks)
        } else {
            onEmpty()
        }
    }

    fun playOfflineMix() {
        val offline = uiState.value.downloadedTracks
        if (offline.isEmpty()) return
        val shuffled = offline.shuffled()
        playbackManager.playTrack(shuffled.first(), shuffled)
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
                onNotice(err.localizedMessage ?: "Не удалось запустить радио")
            }
        }
    }

    fun toggleFavorite(track: Track) {
        viewModelScope.launch {
            libraryRepository.toggleFavorite(track)
        }
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
                    onNotice(err.localizedMessage ?: "Ошибка загрузки")
                }
            }
        }
    }

    fun addTrackToPlaylist(playlistId: Long, track: Track) {
        viewModelScope.launch {
            libraryRepository.addTrackToPlaylist(playlistId, track)
        }
    }

    fun createPlaylistAndAddTrack(name: String, track: Track) {
        viewModelScope.launch {
            libraryRepository.createPlaylist(name = name, initialTracks = listOf(track))
        }
    }
}
