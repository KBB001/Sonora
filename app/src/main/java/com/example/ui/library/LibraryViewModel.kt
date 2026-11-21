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
import kotlinx.coroutines.Job
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
                        importError.value = "В этом плейлисте не найдены доступные треки."
                    } else {
                        libraryRepository.createPlaylist(
                            name = title,
                            description = "Импортировано из YouTube",
                            initialTracks = tracks
                        )
                        onSuccess()
                    }
                    isImporting.value = false
                },
                onFailure = { throwable ->
                    importError.value = throwable.localizedMessage
                        ?: "Не удалось импортировать плейлист. Проверьте, что он открытый."
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
    val isBatchDownloading: Boolean = false,
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
    private val isBatchDownloadingFlow = MutableStateFlow(false)
    private var batchDownloadJob: Job? = null

    val uiState: StateFlow<PlaylistDetailUiState> = combine(
        libraryRepository.observePlaylistById(playlistId),
        libraryRepository.observePlaylistTracks(playlistId),
        libraryRepository.observeFavoriteIds(),
        libraryRepository.observeDownloadedIds(),
        trackDownloadManager.downloadingIds,
        isBatchDownloadingFlow,
        playbackManager.playbackState
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        PlaylistDetailUiState(
            playlist = values[0] as Playlist?,
            tracks = values[1] as List<Track>,
            favoriteIds = values[2] as Set<String>,
            downloadedIds = values[3] as Set<String>,
            downloadingIds = values[4] as Set<String>,
            isBatchDownloading = values[5] as Boolean,
            playbackState = values[6] as PlaybackState
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

    fun addAllToQueue(onNotice: (String) -> Unit) {
        val tracks = uiState.value.tracks
        if (tracks.isEmpty()) return
        val added = playbackManager.addTracksToQueue(tracks)
        onNotice(
            if (added > 0) "В очередь добавлено треков: $added"
            else "Все треки уже в очереди"
        )
    }

    fun playTrack(track: Track) {
        playbackManager.playTrack(track, uiState.value.tracks)
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

    fun updatePlaylistDetails(name: String, description: String) {
        viewModelScope.launch {
            libraryRepository.updatePlaylistDetails(playlistId, name, description)
        }
    }

    fun downloadAllMissing(onNotice: (String) -> Unit) {
        if (batchDownloadJob?.isActive == true) {
            onNotice("Загрузка плейлиста уже выполняется")
            return
        }
        val state = uiState.value
        val pending = state.tracks.filterNot { it.id in state.downloadedIds }
        if (pending.isEmpty()) {
            onNotice("Все треки уже сохранены офлайн")
            return
        }
        batchDownloadJob = viewModelScope.launch {
            isBatchDownloadingFlow.value = true
            onNotice("Загрузка ${pending.size} треков для офлайн...")
            var successCount = 0
            pending.forEach { track ->
                trackDownloadManager.downloadTrack(track).onSuccess {
                    successCount++
                }
            }
            isBatchDownloadingFlow.value = false
            onNotice("Загружено $successCount из ${pending.size} треков")
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

    fun removeTrack(trackId: String) {
        viewModelScope.launch {
            libraryRepository.removeTrackFromPlaylist(playlistId, trackId)
        }
    }
}
