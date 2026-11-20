package com.example.ui.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.example.data.local.TrackDownloadManager
import com.example.domain.model.PlaybackState
import com.example.domain.model.Playlist
import com.example.domain.model.Track
import com.example.domain.repository.LibraryRepository
import com.example.domain.repository.YouTubeRepository
import com.example.playback.PlaybackManager
import com.example.ui.navigation.SonoraRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryUiState(
    val favorites: List<Track> = emptyList(),
    val favoriteIds: Set<String> = emptySet(),
    val downloads: List<Track> = emptyList(),
    val downloadedIds: Set<String> = emptySet(),
    val downloadingIds: Set<String> = emptySet(),
    val history: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val playbackState: PlaybackState = PlaybackState(),
    val isImportingPlaylist: Boolean = false,
    val importError: String? = null
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val youTubeRepository: YouTubeRepository,
    private val playbackManager: PlaybackManager,
    private val trackDownloadManager: TrackDownloadManager
) : ViewModel() {

    private val isImporting = MutableStateFlow(false)
    private val importError = MutableStateFlow<String?>(null)

    val uiState: StateFlow<LibraryUiState> = combine(
        libraryRepository.observeFavorites(),
        libraryRepository.observeFavoriteIds(),
        libraryRepository.observeDownloadedTracks(),
        libraryRepository.observeDownloadedIds(),
        trackDownloadManager.downloadingIds,
        libraryRepository.observeHistory(),
        libraryRepository.observePlaylists(),
        playbackManager.playbackState,
        isImporting,
        importError
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        LibraryUiState(
            favorites = values[0] as List<Track>,
            favoriteIds = values[1] as Set<String>,
            downloads = values[2] as List<Track>,
            downloadedIds = values[3] as Set<String>,
            downloadingIds = values[4] as Set<String>,
            history = values[5] as List<Track>,
            playlists = values[6] as List<Playlist>,
            playbackState = values[7] as PlaybackState,
            isImportingPlaylist = values[8] as Boolean,
            importError = values[9] as String?
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState()
    )

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

    fun createPlaylist(name: String, description: String) {
        viewModelScope.launch {
            libraryRepository.createPlaylist(name = name, description = description)
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            libraryRepository.deletePlaylist(playlistId)
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

    fun clearHistory() {
        viewModelScope.launch {
            libraryRepository.clearHistory()
        }
    }

    fun clearImportError() {
        importError.value = null
    }

    fun importYouTubePlaylist(playlistUrl: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            isImporting.value = true
            importError.value = null
            youTubeRepository.getPlaylistWithTitle(playlistUrl).fold(
                onSuccess = { (title, tracks) ->
                    if (tracks.isEmpty()) {
                        importError.value = "No playable tracks found in this playlist."
                    } else {
                        libraryRepository.createPlaylist(
                            name = title,
                            description = "Imported from YouTube",
                            initialTracks = tracks
                        )
                        onSuccess()
                    }
                    isImporting.value = false
                },
                onFailure = { throwable ->
                    importError.value = throwable.localizedMessage
                        ?: "Could not import playlist. Verify the URL is public."
                    isImporting.value = false
                }
            )
        }
    }
}

data class PlaylistDetailUiState(
    val playlist: Playlist? = null,
    val tracks: List<Track> = emptyList(),
    val favoriteIds: Set<String> = emptySet(),
    val downloadedIds: Set<String> = emptySet(),
    val downloadingIds: Set<String> = emptySet(),
    val playbackState: PlaybackState = PlaybackState()
)

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val playbackManager: PlaybackManager,
    private val trackDownloadManager: TrackDownloadManager
) : ViewModel() {

    private val route = savedStateHandle.toRoute<SonoraRoute.PlaylistDetail>()
    private val playlistId = route.playlistId

    val uiState: StateFlow<PlaylistDetailUiState> = combine(
        libraryRepository.observePlaylistById(playlistId),
        libraryRepository.observePlaylistTracks(playlistId),
        libraryRepository.observeFavoriteIds(),
        libraryRepository.observeDownloadedIds(),
        trackDownloadManager.downloadingIds,
        playbackManager.playbackState
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        PlaylistDetailUiState(
            playlist = values[0] as Playlist?,
            tracks = values[1] as List<Track>,
            favoriteIds = values[2] as Set<String>,
            downloadedIds = values[3] as Set<String>,
            downloadingIds = values[4] as Set<String>,
            playbackState = values[5] as PlaybackState
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlaylistDetailUiState()
    )

    fun playAll(shuffle: Boolean = false) {
        val tracks = uiState.value.tracks
        if (tracks.isEmpty()) return
        val queue = if (shuffle) tracks.shuffled() else tracks
        playbackManager.playTrack(queue.first(), queue)
    }

    fun playTrack(track: Track) {
        playbackManager.playTrack(track, uiState.value.tracks)
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

    fun removeTrack(trackId: String) {
        viewModelScope.launch {
            libraryRepository.removeTrackFromPlaylist(playlistId, trackId)
        }
    }
}
