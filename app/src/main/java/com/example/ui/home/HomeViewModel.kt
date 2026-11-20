package com.example.ui.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.TrackDownloadManager
import com.example.domain.model.PlaybackState
import com.example.domain.model.Playlist
import com.example.domain.model.Track
import com.example.domain.repository.LibraryRepository
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
    val favoriteIds: Set<String> = emptySet(),
    val downloadedIds: Set<String> = emptySet(),
    val downloadingIds: Set<String> = emptySet(),
    val playlists: List<Playlist> = emptyList(),
    val playbackState: PlaybackState = PlaybackState()
) {
    companion object {
        val MOOD_CHIPS = listOf(
            "Top Hits Mix",
            "Lo-Fi Beats",
            "Synthwave",
            "Acoustic Chill",
            "Deep Focus",
            "Electronic"
        )
    }
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val youTubeRepository: YouTubeRepository,
    private val libraryRepository: LibraryRepository,
    private val playbackManager: PlaybackManager,
    private val trackDownloadManager: TrackDownloadManager
) : ViewModel() {

    private val selectedMood = MutableStateFlow(HomeUiState.MOOD_CHIPS.first())
    private val discoverState = MutableStateFlow<DiscoverUiState>(DiscoverUiState.Loading)
    private var discoverJob: Job? = null

    val uiState: StateFlow<HomeUiState> = combine(
        selectedMood,
        discoverState,
        libraryRepository.observeHistory(),
        libraryRepository.observeFavorites(),
        libraryRepository.observeFavoriteIds(),
        libraryRepository.observeDownloadedIds(),
        trackDownloadManager.downloadingIds,
        libraryRepository.observePlaylists(),
        playbackManager.playbackState
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        HomeUiState(
            selectedMood = values[0] as String,
            discoverState = values[1] as DiscoverUiState,
            history = values[2] as List<Track>,
            favorites = values[3] as List<Track>,
            favoriteIds = values[4] as Set<String>,
            downloadedIds = values[5] as Set<String>,
            downloadingIds = values[6] as Set<String>,
            playlists = values[7] as List<Playlist>,
            playbackState = values[8] as PlaybackState
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
            youTubeRepository.searchTracks("$mood music audio").fold(
                onSuccess = { tracks ->
                    discoverState.value = DiscoverUiState.Success(tracks.take(15))
                },
                onFailure = { error ->
                    discoverState.value = DiscoverUiState.Error(
                        error.localizedMessage ?: "Unable to reach YouTube right now."
                    )
                }
            )
        }
    }

    fun playTrack(track: Track, queue: List<Track>) {
        playbackManager.playTrack(track, queue)
    }

    fun playNext(track: Track) {
        playbackManager.playNext(track)
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
