package com.example.ui.player

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.common.NetworkMonitor
import com.example.data.local.TrackDownloadManager
import com.example.data.remote.LyricsRepository
import com.example.domain.model.AppSettings
import com.example.domain.model.LyricsResult
import com.example.domain.model.PlaybackProgress
import com.example.domain.model.PlaybackState
import com.example.domain.model.Playlist
import com.example.domain.model.Track
import com.example.domain.repository.LibraryRepository
import com.example.domain.repository.SettingsRepository
import com.example.playback.EqualizerState
import com.example.playback.PlaybackManager
import com.example.playback.SonoraEqualizerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface LyricsUiState {
    data object Idle : LyricsUiState
    data object Loading : LyricsUiState
    data class Success(val result: LyricsResult) : LyricsUiState
    data class Error(val message: String) : LyricsUiState
}

@Immutable
data class PlayerUiState(
    val playbackState: PlaybackState = PlaybackState(),
    val favoriteIds: Set<String> = emptySet(),
    val downloadedIds: Set<String> = emptySet(),
    val downloadingIds: Set<String> = emptySet(),
    val playlists: List<Playlist> = emptyList(),
    val lyricsState: LyricsUiState = LyricsUiState.Idle,
    val equalizerState: EqualizerState = EqualizerState(),
    val isOnline: Boolean = true,
    val settings: AppSettings = AppSettings()
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackManager: PlaybackManager,
    private val libraryRepository: LibraryRepository,
    private val lyricsRepository: LyricsRepository,
    private val equalizerManager: SonoraEqualizerManager,
    private val trackDownloadManager: TrackDownloadManager,
    private val networkMonitor: NetworkMonitor,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val lyricsFlow = MutableStateFlow<LyricsUiState>(LyricsUiState.Idle)
    private var lyricsJob: Job? = null

    val playbackProgress: StateFlow<PlaybackProgress> = playbackManager.playbackProgress

    val uiState: StateFlow<PlayerUiState> = combine(
        playbackManager.playbackState,
        libraryRepository.observeFavoriteIds(),
        libraryRepository.observeDownloadedIds(),
        trackDownloadManager.downloadingIds,
        libraryRepository.observePlaylists(),
        lyricsFlow,
        equalizerManager.state,
        networkMonitor.isOnline,
        settingsRepository.settings
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        PlayerUiState(
            playbackState = values[0] as PlaybackState,
            favoriteIds = values[1] as Set<String>,
            downloadedIds = values[2] as Set<String>,
            downloadingIds = values[3] as Set<String>,
            playlists = values[4] as List<Playlist>,
            lyricsState = values[5] as LyricsUiState,
            equalizerState = values[6] as EqualizerState,
            isOnline = values[7] as Boolean,
            settings = values[8] as AppSettings
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlayerUiState()
    )

    init {
        viewModelScope.launch {
            playbackManager.playbackState
                .distinctUntilChangedBy { it.currentTrack?.id }
                .collect { state ->
                    val track = state.currentTrack
                    if (track == null) {
                        lyricsJob?.cancel()
                        lyricsFlow.value = LyricsUiState.Idle
                    } else {
                        loadLyricsForTrack(track)
                    }
                }
        }
    }

    fun retryLyrics() {
        val track = uiState.value.playbackState.currentTrack ?: return
        loadLyricsForTrack(track)
    }

    private fun loadLyricsForTrack(track: Track) {
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            lyricsFlow.value = LyricsUiState.Loading
            lyricsRepository.fetchLyrics(
                title = track.title,
                artist = track.artist,
                durationSeconds = track.durationSeconds
            ).fold(
                onSuccess = { result ->
                    lyricsFlow.value = LyricsUiState.Success(result)
                },
                onFailure = { err ->
                    lyricsFlow.value = LyricsUiState.Error(
                        err.localizedMessage ?: "Lyrics unavailable for this track."
                    )
                }
            )
        }
    }

    fun togglePlayPause() = playbackManager.togglePlayPause()

    fun seekTo(positionMs: Long) = playbackManager.seekTo(positionMs)

    fun skipNext() = playbackManager.skipNext()

    fun skipPrevious() = playbackManager.skipPrevious()

    fun toggleShuffle() = playbackManager.toggleShuffle()

    fun cycleRepeatMode() = playbackManager.cycleRepeatMode()

    fun playFromQueue(track: Track) {
        playbackManager.playTrack(track, uiState.value.playbackState.queue)
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        playbackManager.moveQueueItem(fromIndex, toIndex)
    }

    fun removeFromQueue(index: Int) {
        playbackManager.removeFromQueue(index)
    }

    fun startSleepTimer(minutes: Int) = playbackManager.startSleepTimer(minutes)

    fun cancelSleepTimer() = playbackManager.cancelSleepTimer()

    fun setEqualizerEnabled(enabled: Boolean) = equalizerManager.setEnabled(enabled)

    fun selectEqualizerPreset(index: Int) = equalizerManager.selectPreset(index)

    fun setEqualizerBand(bandIndex: Int, levelMb: Short) =
        equalizerManager.setBandLevel(bandIndex, levelMb)

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

    fun clearError() = playbackManager.clearError()
}
