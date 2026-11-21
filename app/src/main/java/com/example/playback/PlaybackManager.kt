package com.example.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.core.common.NetworkMonitor
import com.example.domain.model.PlaybackProgress
import com.example.domain.model.PlaybackState
import com.example.domain.model.RepeatMode
import com.example.domain.model.Track
import com.example.domain.repository.LibraryRepository
import com.example.domain.repository.SettingsRepository
import com.example.domain.repository.YouTubeRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val youTubeRepository: YouTubeRepository,
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository,
    private val networkMonitor: NetworkMonitor,
    private val equalizerManager: SonoraEqualizerManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _playbackProgress = MutableStateFlow(PlaybackProgress())
    val playbackProgress: StateFlow<PlaybackProgress> = _playbackProgress.asStateFlow()

    private var mediaController: MediaController? = null
    private var resolveJob: Job? = null
    private var progressJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var stalledOnNetwork = false

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _playbackState.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) startProgressLoop() else stopProgressLoop()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val isBuffering = playbackState == Player.STATE_BUFFERING
            val controllerDur = mediaController?.duration?.takeIf { it > 0 }
            _playbackState.update { state ->
                state.copy(
                    isBuffering = isBuffering,
                    durationMs = controllerDur ?: state.durationMs
                )
            }
            if (controllerDur != null) {
                val pos = mediaController?.currentPosition?.coerceAtLeast(0L) ?: _playbackProgress.value.positionMs
                _playbackProgress.value = PlaybackProgress(positionMs = pos, durationMs = controllerDur)
            }
            if (playbackState == Player.STATE_READY) {
                stalledOnNetwork = false
            }
            if (playbackState == Player.STATE_ENDED) {
                onTrackCompleted()
            }
        }

        @OptIn(UnstableApi::class)
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            if (audioSessionId > 0) {
                equalizerManager.attachToSession(audioSessionId)
                _playbackState.update { it.copy(audioSessionId = audioSessionId) }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            stalledOnNetwork = !networkMonitor.isOnline.value
            _playbackState.update {
                it.copy(
                    isPlaying = false,
                    isBuffering = false,
                    isLoadingStream = false,
                    errorMessage = if (stalledOnNetwork) {
                        "Нет сети. Воспроизведение продолжится при подключении."
                    } else {
                        error.localizedMessage ?: "Ошибка воспроизведения"
                    }
                )
            }
        }
    }

    init {
        connectController()
        observeNetworkRecovery()
    }

    private fun observeNetworkRecovery() {
        scope.launch {
            networkMonitor.isOnline
                .collect { online ->
                    if (online && stalledOnNetwork) {
                        stalledOnNetwork = false
                        val idx = _playbackState.value.currentIndex
                        if (idx in _playbackState.value.queue.indices) {
                            _playbackState.update { it.copy(errorMessage = null) }
                            loadAndPlayAtIndex(idx)
                        }
                    }
                }
        }
    }

    private fun connectController() {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, SonoraPlaybackService::class.java)
        )
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture.addListener(
            {
                runCatching {
                    val controller = controllerFuture.get()
                    mediaController = controller
                    controller.addListener(playerListener)
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    fun playTrack(track: Track, queue: List<Track> = listOf(track)) {
        val normalizedQueue = if (queue.any { it.id == track.id }) queue else listOf(track) + queue
        val targetIndex = normalizedQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)

        val initialDur = track.durationSeconds * 1000L
        _playbackProgress.value = PlaybackProgress(positionMs = 0L, durationMs = initialDur)
        _playbackState.update {
            it.copy(
                currentTrack = track,
                queue = normalizedQueue,
                currentIndex = targetIndex,
                isLoadingStream = true,
                positionMs = 0L,
                durationMs = initialDur,
                errorMessage = null
            )
        }

        loadAndPlayAtIndex(targetIndex)
    }

    fun playNext(track: Track) {
        val state = _playbackState.value
        if (state.currentTrack == null || state.queue.isEmpty()) {
            playTrack(track)
            return
        }
        val filtered = state.queue.filterNot { it.id == track.id }.toMutableList()
        val currentIdx = filtered.indexOfFirst { it.id == state.currentTrack.id }.coerceAtLeast(0)
        val insertIdx = (currentIdx + 1).coerceAtMost(filtered.size)
        filtered.add(insertIdx, track)
        _playbackState.update {
            it.copy(
                queue = filtered,
                currentIndex = currentIdx
            )
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val state = _playbackState.value
        if (fromIndex !in state.queue.indices || toIndex !in state.queue.indices || fromIndex == toIndex) {
            return
        }
        val currentId = state.currentTrack?.id
        val mutable = state.queue.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        val newCurrentIdx = currentId?.let { id -> mutable.indexOfFirst { it.id == id } } ?: state.currentIndex
        _playbackState.update {
            it.copy(queue = mutable, currentIndex = newCurrentIdx)
        }
    }

    fun removeFromQueue(index: Int) {
        val state = _playbackState.value
        if (index !in state.queue.indices || index == state.currentIndex) return
        val currentId = state.currentTrack?.id
        val mutable = state.queue.toMutableList().apply { removeAt(index) }
        val newCurrentIdx = currentId?.let { id -> mutable.indexOfFirst { it.id == id } } ?: -1
        _playbackState.update {
            it.copy(queue = mutable, currentIndex = newCurrentIdx)
        }
    }

    fun addTracksToQueue(tracks: List<Track>): Int {
        if (tracks.isEmpty()) return 0
        val state = _playbackState.value
        if (state.currentTrack == null || state.queue.isEmpty()) {
            playTrack(tracks.first(), tracks)
            return tracks.size
        }
        val existingIds = state.queue.map { it.id }.toSet()
        val toAdd = tracks.filterNot { it.id in existingIds }
        if (toAdd.isEmpty()) return 0
        _playbackState.update {
            it.copy(queue = it.queue + toAdd)
        }
        return toAdd.size
    }

    fun shuffleUpcomingQueue() {
        val state = _playbackState.value
        val curIdx = state.currentIndex
        if (curIdx !in state.queue.indices || curIdx >= state.queue.lastIndex - 1) return
        val beforeAndCurrent = state.queue.subList(0, curIdx + 1)
        val upcoming = state.queue.subList(curIdx + 1, state.queue.size).shuffled()
        _playbackState.update {
            it.copy(queue = beforeAndCurrent + upcoming)
        }
    }

    fun clearUpcomingQueue() {
        val state = _playbackState.value
        val curIdx = state.currentIndex
        if (curIdx !in state.queue.indices || curIdx >= state.queue.lastIndex) return
        val kept = state.queue.subList(0, curIdx + 1).toList()
        _playbackState.update {
            it.copy(queue = kept)
        }
    }

    fun startTrackRadio(
        track: Track,
        onResult: ((Result<Int>) -> Unit)? = null
    ) {
        scope.launch {
            if (!networkMonitor.isOnline.value) {
                onResult?.invoke(Result.failure(IllegalStateException("Радио требует подключения к интернету")))
                return@launch
            }
            val relatedResult = youTubeRepository.getRelatedTracks(track.id)
            relatedResult.fold(
                onSuccess = { related ->
                    val uniqueRelated = related.filterNot { it.id == track.id }.distinctBy { it.id }.take(20)
                    if (uniqueRelated.isEmpty()) {
                        onResult?.invoke(Result.failure(IllegalStateException("Похожие треки не найдены")))
                        return@fold
                    }
                    val currentId = _playbackState.value.currentTrack?.id
                    if (currentId == track.id) {
                        val curIdx = _playbackState.value.currentIndex.coerceAtLeast(0)
                        val prefix = _playbackState.value.queue.take(curIdx + 1)
                        val prefixIds = prefix.map { it.id }.toSet()
                        val tail = uniqueRelated.filterNot { it.id in prefixIds }
                        _playbackState.update {
                            it.copy(
                                queue = prefix + tail,
                                currentIndex = curIdx
                            )
                        }
                        onResult?.invoke(Result.success(tail.size))
                    } else {
                        playTrack(track, listOf(track) + uniqueRelated)
                        onResult?.invoke(Result.success(uniqueRelated.size))
                    }
                },
                onFailure = { err ->
                    onResult?.invoke(Result.failure(err))
                }
            )
        }
    }

    private fun loadAndPlayAtIndex(index: Int) {
        val queue = _playbackState.value.queue
        val track = queue.getOrNull(index) ?: return

        resolveJob?.cancel()
        resolveJob = scope.launch {
            val initialDur = track.durationSeconds * 1000L
            _playbackProgress.value = PlaybackProgress(positionMs = 0L, durationMs = initialDur)
            _playbackState.update {
                it.copy(
                    currentTrack = track,
                    currentIndex = index,
                    isLoadingStream = true,
                    positionMs = 0L,
                    durationMs = initialDur,
                    errorMessage = null
                )
            }

            // 1. Prefer offline downloaded local file if available
            val offlinePath = track.localFilePath?.takeIf { File(it).exists() }
                ?: libraryRepository.getDownloadedFilePath(track.id)

            val playableUriResult: Result<Uri> = if (!offlinePath.isNullOrBlank()) {
                Result.success(Uri.fromFile(File(offlinePath)))
            } else if (!track.streamUrl.isNullOrBlank()) {
                Result.success(Uri.parse(track.streamUrl))
            } else {
                val quality = settingsRepository.settings.first().audioQuality
                youTubeRepository.getAudioStreamUrl(track.id, quality).map { Uri.parse(it) }
            }

            playableUriResult.fold(
                onSuccess = { mediaUri ->
                    val resolvedTrack = track.copy(
                        localFilePath = offlinePath ?: track.localFilePath,
                        streamUrl = if (offlinePath == null) mediaUri.toString() else track.streamUrl
                    )
                    val updatedQueue = _playbackState.value.queue.toMutableList()
                    if (index in updatedQueue.indices) {
                        updatedQueue[index] = resolvedTrack
                    }
                    _playbackState.update {
                        it.copy(
                            currentTrack = resolvedTrack,
                            queue = updatedQueue,
                            isLoadingStream = false
                        )
                    }

                    val controller = mediaController
                    if (controller != null) {
                        controller.volume = 1f
                        controller.setPlaybackSpeed(_playbackState.value.playbackSpeed)
                        val mediaMetadata = MediaMetadata.Builder()
                            .setTitle(resolvedTrack.title)
                            .setArtist(resolvedTrack.artist)
                            .setArtworkUri(Uri.parse(resolvedTrack.thumbnailUrl))
                            .build()
                        val mediaItem = MediaItem.Builder()
                            .setMediaId(resolvedTrack.id)
                            .setUri(mediaUri)
                            .setMediaMetadata(mediaMetadata)
                            .build()
                        controller.setMediaItem(mediaItem)
                        controller.prepare()
                        controller.play()
                    } else {
                        connectController()
                    }

                    scope.launch {
                        libraryRepository.recordPlayback(resolvedTrack)
                    }
                },
                onFailure = { throwable ->
                    stalledOnNetwork = !networkMonitor.isOnline.value
                    _playbackState.update {
                        it.copy(
                            isLoadingStream = false,
                            isBuffering = false,
                            isPlaying = false,
                            errorMessage = if (stalledOnNetwork) {
                                "Офлайн: скачайте треки для прослушивания без сети."
                            } else {
                                throwable.localizedMessage ?: "Не удалось загрузить аудиопоток"
                            }
                        )
                    }
                }
            )
        }
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        controller.volume = 1f
        if (controller.isPlaying) {
            controller.pause()
        } else if (controller.mediaItemCount > 0) {
            controller.play()
        } else {
            val current = _playbackState.value.currentTrack ?: return
            playTrack(current, _playbackState.value.queue)
        }
    }

    fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        mediaController?.seekTo(clamped)
        val dur = _playbackProgress.value.durationMs.takeIf { it > 0L } ?: _playbackState.value.durationMs
        _playbackProgress.value = PlaybackProgress(positionMs = clamped, durationMs = dur)
        _playbackState.update { it.copy(positionMs = clamped) }
    }

    fun skipNext() {
        val state = _playbackState.value
        if (state.queue.isEmpty()) return

        val nextIndex = when {
            state.shuffleEnabled && state.queue.size > 1 -> {
                val candidates = state.queue.indices.filter { it != state.currentIndex }
                candidates.random()
            }
            state.currentIndex + 1 < state.queue.size -> state.currentIndex + 1
            state.repeatMode == RepeatMode.ALL -> 0
            else -> {
                triggerAutoplayRadio()
                return
            }
        }
        loadAndPlayAtIndex(nextIndex)
    }

    fun skipPrevious() {
        val state = _playbackState.value
        val currentPos = mediaController?.currentPosition ?: state.positionMs
        if (currentPos > 3000L) {
            seekTo(0L)
            return
        }
        if (state.queue.isEmpty()) return
        val prevIndex = when {
            state.currentIndex - 1 >= 0 -> state.currentIndex - 1
            state.repeatMode == RepeatMode.ALL -> state.queue.lastIndex
            else -> 0
        }
        loadAndPlayAtIndex(prevIndex)
    }

    fun toggleShuffle() {
        _playbackState.update { it.copy(shuffleEnabled = !it.shuffleEnabled) }
    }

    fun cycleRepeatMode() {
        _playbackState.update { state ->
            val nextMode = when (state.repeatMode) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
            state.copy(repeatMode = nextMode)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        mediaController?.setPlaybackSpeed(clamped)
        _playbackState.update { it.copy(playbackSpeed = clamped) }
    }

    fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            cancelSleepTimer()
            return
        }
        val totalMs = minutes * 60_000L
        sleepTimerJob = scope.launch {
            var remaining = totalMs
            while (remaining > 0L && isActive) {
                _playbackState.update { it.copy(sleepTimerRemainingMs = remaining) }
                if (remaining <= 5_000L) {
                    val fraction = (remaining / 5_000f).coerceIn(0f, 1f)
                    mediaController?.volume = fraction
                }
                delay(1_000L)
                remaining -= 1_000L
            }
            mediaController?.pause()
            mediaController?.volume = 1f
            _playbackState.update { it.copy(sleepTimerRemainingMs = null) }
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        mediaController?.volume = 1f
        _playbackState.update { it.copy(sleepTimerRemainingMs = null) }
    }

    fun clearError() {
        _playbackState.update { it.copy(errorMessage = null) }
    }

    private fun onTrackCompleted() {
        val state = _playbackState.value
        if (state.repeatMode == RepeatMode.ONE) {
            seekTo(0L)
            mediaController?.play()
            return
        }
        val hasNext = (state.shuffleEnabled && state.queue.size > 1) ||
            state.currentIndex + 1 < state.queue.size ||
            state.repeatMode == RepeatMode.ALL
        if (hasNext) {
            skipNext()
        } else {
            triggerAutoplayRadio()
        }
    }

    private fun triggerAutoplayRadio() {
        val currentTrack = _playbackState.value.currentTrack
        if (currentTrack == null) {
            _playbackState.update { it.copy(isPlaying = false, positionMs = 0L) }
            return
        }
        scope.launch {
            val autoplay = settingsRepository.settings.first().autoplayEnabled
            if (!autoplay || !networkMonitor.isOnline.value) {
                _playbackState.update { it.copy(isPlaying = false, positionMs = 0L) }
                return@launch
            }
            val related = youTubeRepository.getRelatedTracks(currentTrack.id).getOrDefault(emptyList())
            val existingIds = _playbackState.value.queue.map { it.id }.toSet()
            val freshTracks = related.filterNot { it.id in existingIds }.take(10)
            if (freshTracks.isNotEmpty()) {
                val oldSize = _playbackState.value.queue.size
                val expandedQueue = _playbackState.value.queue + freshTracks
                _playbackState.update { it.copy(queue = expandedQueue) }
                loadAndPlayAtIndex(oldSize)
            } else {
                _playbackState.update { it.copy(isPlaying = false, positionMs = 0L) }
            }
        }
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                // Suspend with 0 CPU wakeups while UI is backgrounded or no progress observer is active
                if (_playbackProgress.subscriptionCount.value == 0) {
                    _playbackProgress.subscriptionCount.first { it > 0 }
                }
                val controller = mediaController
                if (controller != null) {
                    val pos = controller.currentPosition.coerceAtLeast(0L)
                    val dur = controller.duration.takeIf { it > 0 } ?: _playbackState.value.durationMs
                    val next = PlaybackProgress(positionMs = pos, durationMs = dur)
                    if (_playbackProgress.value != next) {
                        _playbackProgress.value = next
                    }
                }
                delay(500L)
            }
        }
    }

    private fun stopProgressLoop() {
        progressJob?.cancel()
    }
}
