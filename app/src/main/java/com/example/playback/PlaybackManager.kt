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
import com.example.core.common.FriendlyErrorMapper
import com.example.core.common.NetworkMonitor
import com.example.data.repository.PlaybackPersistenceStore
import com.example.domain.model.ImplicitSignalType
import com.example.domain.model.PersistedPlaybackState
import com.example.domain.model.PlaybackProgress
import com.example.domain.model.PlaybackState
import com.example.domain.model.RepeatMode
import com.example.domain.model.Track
import com.example.domain.recommendation.SmartRecommendationEngine
import com.example.domain.recommendation.TasteSignalTracker
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
    private val equalizerManager: SonoraEqualizerManager,
    private val playbackPersistenceStore: PlaybackPersistenceStore? = null,
    private val audioCacheProvider: AudioCacheProvider? = null,
    private val tasteSignalTracker: TasteSignalTracker? = null,
    private val smartRecommendationEngine: SmartRecommendationEngine? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _playbackProgress = MutableStateFlow(PlaybackProgress())
    val playbackProgress: StateFlow<PlaybackProgress> = _playbackProgress.asStateFlow()

    private var mediaController: MediaController? = null
    private var isConnectingController = false
    private var pendingMediaItem: Pair<MediaItem, Long>? = null
    private var retriedExpiredStreamTrackId: String? = null
    private var resolveJob: Job? = null
    private var progressJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var persistJob: Job? = null
    private var initialRestoreJob: Job? = null
    private var autoplayJob: Job? = null
    private var prefetchJob: Job? = null
    private var infiniteQueueJob: Job? = null
    private var stalledOnNetwork = false
    private var hasUserPlaybackStarted = false
    private var pendingRestorePositionMs: Long = 0L
    private var lastPersistedPositionAtMs: Long = 0L

    // Implicit feedback & real-time adaptation state
    private var recordedCompletionTrackId: String? = null
    private var replaySignalRecordedForCurrentTrack = false
    private var consecutiveQuickSkips = 0
    private val recentQuickSkippedTracks = ArrayDeque<Track>()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val currentPos = mediaController?.currentPosition?.coerceAtLeast(0L)
            _playbackState.update { state ->
                state.copy(
                    isPlaying = isPlaying,
                    positionMs = currentPos ?: state.positionMs
                )
            }
            if (currentPos != null) {
                val dur = _playbackProgress.value.durationMs.takeIf { it > 0L }
                    ?: _playbackState.value.durationMs
                _playbackProgress.value = PlaybackProgress(positionMs = currentPos, durationMs = dur)
            }
            if (isPlaying) {
                startProgressLoop()
            } else {
                stopProgressLoop()
                schedulePersistPlaybackState()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val isBuffering = playbackState == Player.STATE_BUFFERING
            val controllerDur = mediaController?.duration?.takeIf { it > 0 }
            val currentPos = mediaController?.currentPosition?.coerceAtLeast(0L)
            _playbackState.update { state ->
                state.copy(
                    isBuffering = isBuffering,
                    durationMs = controllerDur ?: state.durationMs,
                    positionMs = currentPos ?: state.positionMs
                )
            }
            if (controllerDur != null) {
                val pos = currentPos ?: _playbackProgress.value.positionMs
                _playbackProgress.value = PlaybackProgress(positionMs = pos, durationMs = controllerDur)
            }
            if (playbackState == Player.STATE_READY) {
                stalledOnNetwork = false
                retriedExpiredStreamTrackId = null
            }
            if (playbackState == Player.STATE_ENDED) {
                onTrackCompleted()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val state = _playbackState.value
            when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> {
                    val repeatedTrack = state.currentTrack ?: return
                    tasteSignalTracker?.recordSignalAsync(repeatedTrack, ImplicitSignalType.REPEAT_PLAY)
                    recordedCompletionTrackId = null
                    replaySignalRecordedForCurrentTrack = false
                    consecutiveQuickSkips = 0
                    recentQuickSkippedTracks.clear()
                    val dur = mediaController?.duration?.takeIf { it > 0L } ?: state.durationMs
                    _playbackProgress.value = PlaybackProgress(positionMs = 0L, durationMs = dur)
                    _playbackState.update { it.copy(positionMs = 0L, durationMs = dur) }
                }

                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> {
                    val previousTrack = state.currentTrack
                    if (previousTrack != null && recordedCompletionTrackId != previousTrack.id) {
                        recordedCompletionTrackId = previousTrack.id
                        tasteSignalTracker?.recordSignalAsync(previousTrack, ImplicitSignalType.COMPLETE_PLAY)
                    }
                    consecutiveQuickSkips = 0
                    recentQuickSkippedTracks.clear()

                    val targetId = mediaItem?.mediaId ?: return
                    val nextIndex = findTransitionedTrackIndex(state, targetId) ?: return
                    val nextTrack = state.queue[nextIndex]
                    recordedCompletionTrackId = null
                    replaySignalRecordedForCurrentTrack = false
                    retriedExpiredStreamTrackId = null
                    smartRecommendationEngine?.markHeardInSession(nextTrack.id)

                    val dur = mediaController?.duration?.takeIf { it > 0L }
                        ?: (nextTrack.durationSeconds * 1000L)
                    _playbackProgress.value = PlaybackProgress(positionMs = 0L, durationMs = dur)
                    _playbackState.update {
                        it.copy(
                            currentTrack = nextTrack,
                            currentIndex = nextIndex,
                            isLoadingStream = false,
                            positionMs = 0L,
                            durationMs = dur,
                            errorMessage = null
                        )
                    }
                    scope.launch { libraryRepository.recordPlayback(nextTrack) }
                    schedulePersistPlaybackState()
                    schedulePrefetchWindow()
                    ensureInfiniteQueueAhead()
                }
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
            val state = _playbackState.value
            val track = state.currentTrack
            if (!stalledOnNetwork &&
                track != null &&
                track.localFilePath.isNullOrBlank() &&
                retriedExpiredStreamTrackId != track.id &&
                state.currentIndex in state.queue.indices
            ) {
                retriedExpiredStreamTrackId = track.id
                val resumePos = (mediaController?.currentPosition ?: state.positionMs).coerceAtLeast(0L)
                val clearedQueue = state.queue.map {
                    if (it.id == track.id) it.copy(streamUrl = null) else it
                }
                _playbackState.update {
                    it.copy(
                        currentTrack = track.copy(streamUrl = null),
                        queue = clearedQueue,
                        isPlaying = false,
                        isBuffering = false,
                        isLoadingStream = true,
                        errorMessage = null
                    )
                }
                loadAndPlayAtIndex(state.currentIndex, resumePos, forceRefreshStream = true)
                return
            }
            _playbackState.update {
                it.copy(
                    isPlaying = false,
                    isBuffering = false,
                    isLoadingStream = false,
                    errorMessage = if (stalledOnNetwork) {
                        "Нет сети. Воспроизведение продолжится при подключении."
                    } else {
                        FriendlyErrorMapper.map(error, "Ошибка воспроизведения")
                    }
                )
            }
        }
    }

    init {
        restorePersistedStateOnInit()
        connectController()
        observeNetworkRecovery()
        observeNetworkMetering()
    }

    private fun restorePersistedStateOnInit() {
        val store = playbackPersistenceStore ?: return
        initialRestoreJob = scope.launch {
            val restored = store.readPlaybackState() ?: return@launch
            if (hasUserPlaybackStarted || _playbackState.value.currentTrack != null) {
                return@launch
            }
            applyRestoredStateInternal(restored)
        }
    }

    private fun applyRestoredStateInternal(restored: PersistedPlaybackState) {
        if (restored.queue.isEmpty() || restored.currentIndex !in restored.queue.indices) {
            _playbackState.update {
                it.copy(
                    shuffleEnabled = restored.shuffleEnabled,
                    repeatMode = restored.repeatMode,
                    playbackSpeed = restored.playbackSpeed
                )
            }
            mediaController?.setPlaybackSpeed(restored.playbackSpeed)
            syncRepeatModeToController(restored.repeatMode)
            return
        }
        val track = restored.queue[restored.currentIndex]
        val durationMs = (track.durationSeconds * 1000L).coerceAtLeast(0L)
        val clampedPos = if (durationMs > 0L) {
            restored.positionMs.coerceIn(0L, durationMs)
        } else {
            restored.positionMs.coerceAtLeast(0L)
        }
        pendingRestorePositionMs = clampedPos
        _playbackProgress.value = PlaybackProgress(positionMs = clampedPos, durationMs = durationMs)
        _playbackState.update {
            it.copy(
                currentTrack = track,
                queue = restored.queue,
                currentIndex = restored.currentIndex,
                isPlaying = false,
                isBuffering = false,
                isLoadingStream = false,
                positionMs = clampedPos,
                durationMs = durationMs,
                shuffleEnabled = restored.shuffleEnabled,
                repeatMode = restored.repeatMode,
                playbackSpeed = restored.playbackSpeed,
                errorMessage = null
            )
        }
        mediaController?.setPlaybackSpeed(restored.playbackSpeed)
        syncRepeatModeToController(restored.repeatMode)
    }

    private fun observeNetworkRecovery() {
        scope.launch {
            networkMonitor.isOnline
                .collect { online ->
                    if (online && stalledOnNetwork) {
                        stalledOnNetwork = false
                        val state = _playbackState.value
                        val idx = state.currentIndex
                        if (idx in state.queue.indices) {
                            val resumePos = (mediaController?.currentPosition ?: state.positionMs).coerceAtLeast(0L)
                            _playbackState.update { it.copy(errorMessage = null) }
                            loadAndPlayAtIndex(idx, resumePos, forceRefreshStream = true)
                        }
                    } else if (online && _playbackState.value.currentTrack != null) {
                        schedulePrefetchWindow()
                        ensureInfiniteQueueAhead()
                    }
                }
        }
    }

    private fun observeNetworkMetering() {
        scope.launch {
            networkMonitor.isMetered
                .collect {
                    if (_playbackState.value.currentTrack != null && networkMonitor.isOnline.value) {
                        schedulePrefetchWindow()
                    }
                }
        }
    }

    private fun connectController() {
        if (mediaController != null || isConnectingController) return
        isConnectingController = true
        val sessionToken = SessionToken(
            context,
            ComponentName(context, SonoraPlaybackService::class.java)
        )
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture.addListener(
            {
                isConnectingController = false
                runCatching {
                    val controller = controllerFuture.get()
                    mediaController = controller
                    controller.addListener(playerListener)
                    controller.setPlaybackSpeed(_playbackState.value.playbackSpeed)
                    syncRepeatModeToController(_playbackState.value.repeatMode)
                    pendingMediaItem?.let { (item, startPos) ->
                        pendingMediaItem = null
                        controller.volume = 1f
                        if (startPos > 0L) {
                            controller.setMediaItem(item, startPos)
                        } else {
                            controller.setMediaItem(item)
                        }
                        controller.prepare()
                        controller.play()
                        schedulePrefetchWindow()
                    }
                }.onFailure {
                    pendingMediaItem = null
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    fun playTrack(track: Track, queue: List<Track> = listOf(track)) {
        autoplayJob?.cancel()
        prefetchJob?.cancel()
        infiniteQueueJob?.cancel()

        val previousTrack = _playbackState.value.currentTrack
        if (previousTrack?.id == track.id || smartRecommendationEngine?.isHeardInSession(track.id) == true) {
            tasteSignalTracker?.recordSignalAsync(track, ImplicitSignalType.REPEAT_PLAY)
        }
        smartRecommendationEngine?.markHeardInSession(track.id)

        hasUserPlaybackStarted = true
        pendingRestorePositionMs = 0L
        pendingMediaItem = null
        retriedExpiredStreamTrackId = null
        consecutiveQuickSkips = 0
        recentQuickSkippedTracks.clear()

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

        schedulePersistPlaybackState()
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
        schedulePersistPlaybackState()
        syncUpcomingMediaItemsToController()
        schedulePrefetchWindow()
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val state = _playbackState.value
        if (fromIndex !in state.queue.indices || toIndex !in state.queue.indices || fromIndex == toIndex) {
            return
        }
        val mutable = state.queue.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        val newCurrentIdx = when {
            state.currentIndex == fromIndex -> toIndex
            fromIndex < state.currentIndex && toIndex >= state.currentIndex -> state.currentIndex - 1
            fromIndex > state.currentIndex && toIndex <= state.currentIndex -> state.currentIndex + 1
            else -> state.currentIndex
        }
        _playbackState.update {
            it.copy(queue = mutable, currentIndex = newCurrentIdx)
        }
        schedulePersistPlaybackState()
        syncUpcomingMediaItemsToController()
        schedulePrefetchWindow()
    }

    fun removeFromQueue(index: Int) {
        val state = _playbackState.value
        if (index !in state.queue.indices || index == state.currentIndex) return
        val mutable = state.queue.toMutableList().apply { removeAt(index) }
        val newCurrentIdx = when {
            state.currentIndex > index -> state.currentIndex - 1
            else -> state.currentIndex
        }
        _playbackState.update {
            it.copy(queue = mutable, currentIndex = newCurrentIdx)
        }
        schedulePersistPlaybackState()
        syncUpcomingMediaItemsToController()
        schedulePrefetchWindow()
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
        schedulePersistPlaybackState()
        syncUpcomingMediaItemsToController()
        schedulePrefetchWindow()
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
        schedulePersistPlaybackState()
        syncUpcomingMediaItemsToController()
        schedulePrefetchWindow()
    }

    fun clearUpcomingQueue() {
        val state = _playbackState.value
        val curIdx = state.currentIndex
        if (curIdx !in state.queue.indices || curIdx >= state.queue.lastIndex) return
        val kept = state.queue.subList(0, curIdx + 1).toList()
        _playbackState.update {
            it.copy(queue = kept)
        }
        schedulePersistPlaybackState()
        syncUpcomingMediaItemsToController()
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
            val relatedResult = if (smartRecommendationEngine != null) {
                val smart = smartRecommendationEngine.generateNextQueueTracks(
                    seedTrack = track,
                    targetCount = 20,
                    excludeIds = setOf(track.id),
                    recentSkippedTracks = recentQuickSkippedTracks.toList()
                )
                if (smart.isNotEmpty()) {
                    Result.success(smart)
                } else {
                    youTubeRepository.getRelatedTracks(track.id)
                }
            } else {
                youTubeRepository.getRelatedTracks(track.id)
            }

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
                        schedulePersistPlaybackState()
                        syncUpcomingMediaItemsToController()
                        schedulePrefetchWindow()
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

    private fun loadAndPlayAtIndex(
        index: Int,
        startPositionMs: Long = 0L,
        forceRefreshStream: Boolean = false
    ) {
        autoplayJob?.cancel()
        prefetchJob?.cancel()
        hasUserPlaybackStarted = true
        val queue = _playbackState.value.queue
        val track = queue.getOrNull(index) ?: return
        if (!forceRefreshStream && retriedExpiredStreamTrackId != null && retriedExpiredStreamTrackId != track.id) {
            retriedExpiredStreamTrackId = null
        }

        recordedCompletionTrackId = null
        replaySignalRecordedForCurrentTrack = false
        smartRecommendationEngine?.markHeardInSession(track.id)

        resolveJob?.cancel()
        resolveJob = scope.launch {
            val initialDur = track.durationSeconds * 1000L
            val clampedStartPos = startPositionMs.coerceAtLeast(0L)
            _playbackProgress.value = PlaybackProgress(positionMs = clampedStartPos, durationMs = initialDur)
            _playbackState.update {
                it.copy(
                    currentTrack = track,
                    currentIndex = index,
                    isLoadingStream = true,
                    positionMs = clampedStartPos,
                    durationMs = initialDur,
                    errorMessage = null
                )
            }
            schedulePersistPlaybackState()

            val offlinePath = track.localFilePath?.takeIf { File(it).exists() }
                ?: libraryRepository.getDownloadedFilePath(track.id)

            val playableUriResult: Result<Uri> = if (!offlinePath.isNullOrBlank()) {
                Result.success(Uri.fromFile(File(offlinePath)))
            } else if (!forceRefreshStream && !track.streamUrl.isNullOrBlank()) {
                Result.success(Uri.parse(track.streamUrl))
            } else {
                val quality = settingsRepository.settings.first().audioQuality
                youTubeRepository.getAudioStreamUrl(
                    videoId = track.id,
                    audioQuality = quality,
                    forceRefresh = forceRefreshStream
                ).map { Uri.parse(it) }
            }

            playableUriResult.fold(
                onSuccess = { mediaUri ->
                    val resolvedTrack = track.copy(
                        localFilePath = offlinePath ?: track.localFilePath,
                        streamUrl = if (offlinePath == null) mediaUri.toString() else track.streamUrl
                    )
                    val updatedQueue = _playbackState.value.queue.toMutableList()
                    if (index in updatedQueue.indices && updatedQueue[index].id == resolvedTrack.id) {
                        updatedQueue[index] = resolvedTrack
                    }
                    _playbackState.update {
                        it.copy(
                            currentTrack = resolvedTrack,
                            queue = updatedQueue,
                            isLoadingStream = false
                        )
                    }

                    val mediaItem = buildMediaItem(resolvedTrack, mediaUri)

                    val controller = mediaController
                    if (controller != null) {
                        pendingMediaItem = null
                        controller.volume = 1f
                        controller.setPlaybackSpeed(_playbackState.value.playbackSpeed)
                        syncRepeatModeToController(_playbackState.value.repeatMode)
                        if (clampedStartPos > 0L) {
                            controller.setMediaItem(mediaItem, clampedStartPos)
                        } else {
                            controller.setMediaItem(mediaItem)
                        }
                        controller.prepare()
                        controller.play()
                        syncUpcomingMediaItemsToController()
                        schedulePrefetchWindow()
                    } else {
                        pendingMediaItem = mediaItem to clampedStartPos
                        pendingRestorePositionMs = clampedStartPos
                        connectController()
                    }

                    ensureInfiniteQueueAhead()
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
                                FriendlyErrorMapper.map(throwable, "Не удалось загрузить аудиопоток")
                            }
                        )
                    }
                }
            )
        }
    }

    fun togglePlayPause() {
        val controller = mediaController
        val state = _playbackState.value
        if (controller == null) {
            val idx = state.currentIndex
            if (idx in state.queue.indices) {
                val resumePos = pendingRestorePositionMs.takeIf { it > 0L } ?: state.positionMs
                pendingRestorePositionMs = 0L
                retriedExpiredStreamTrackId = null
                loadAndPlayAtIndex(idx, resumePos, forceRefreshStream = state.errorMessage != null)
            } else {
                connectController()
            }
            return
        }
        controller.volume = 1f
        if (controller.isPlaying) {
            autoplayJob?.cancel()
            controller.pause()
        } else if (state.errorMessage != null ||
            controller.playerError != null ||
            (controller.playbackState == Player.STATE_IDLE && state.currentIndex in state.queue.indices)
        ) {
            val idx = state.currentIndex
            if (idx in state.queue.indices) {
                val resumePos = (controller.currentPosition.takeIf { it > 0L }
                    ?: pendingRestorePositionMs.takeIf { it > 0L }
                    ?: state.positionMs).coerceAtLeast(0L)
                pendingRestorePositionMs = 0L
                retriedExpiredStreamTrackId = null
                loadAndPlayAtIndex(idx, resumePos, forceRefreshStream = true)
            } else {
                val current = state.currentTrack ?: return
                playTrack(current, state.queue)
            }
        } else if (controller.mediaItemCount > 0) {
            controller.play()
        } else {
            val idx = state.currentIndex
            if (idx in state.queue.indices) {
                val resumePos = pendingRestorePositionMs.takeIf { it > 0L } ?: state.positionMs
                pendingRestorePositionMs = 0L
                loadAndPlayAtIndex(idx, resumePos)
            } else {
                val current = state.currentTrack ?: return
                playTrack(current, state.queue)
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        pendingRestorePositionMs = if (mediaController?.mediaItemCount == 0) clamped else 0L
        mediaController?.seekTo(clamped)
        val dur = _playbackProgress.value.durationMs.takeIf { it > 0L } ?: _playbackState.value.durationMs
        _playbackProgress.value = PlaybackProgress(positionMs = clamped, durationMs = dur)
        _playbackState.update { it.copy(positionMs = clamped) }
        schedulePersistPlaybackState()
    }

    fun skipNext() {
        recordManualSkipFeedbackAndSoftRerankIfNeeded()

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
            val track = state.currentTrack
            if (track != null && !replaySignalRecordedForCurrentTrack) {
                replaySignalRecordedForCurrentTrack = true
                tasteSignalTracker?.recordSignalAsync(track, ImplicitSignalType.REPEAT_PLAY)
            }
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
        schedulePersistPlaybackState()
        syncUpcomingMediaItemsToController()
    }

    fun cycleRepeatMode() {
        var newMode = RepeatMode.OFF
        _playbackState.update { state ->
            newMode = when (state.repeatMode) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
            state.copy(repeatMode = newMode)
        }
        syncRepeatModeToController(newMode)
        syncUpcomingMediaItemsToController()
        schedulePersistPlaybackState()
        if (newMode == RepeatMode.OFF) {
            ensureInfiniteQueueAhead()
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        mediaController?.setPlaybackSpeed(clamped)
        _playbackState.update { it.copy(playbackSpeed = clamped) }
        schedulePersistPlaybackState()
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

    private fun recordManualSkipFeedbackAndSoftRerankIfNeeded() {
        val state = _playbackState.value
        val currentTrack = state.currentTrack ?: return
        if (state.errorMessage != null) return

        val pos = (mediaController?.currentPosition ?: state.positionMs).coerceAtLeast(0L)
        val dur = (mediaController?.duration?.takeIf { it > 0L } ?: state.durationMs).coerceAtLeast(0L)
        val isQuickSkip = pos < QUICK_SKIP_THRESHOLD_MS ||
            (dur > 0L && pos < (dur * QUICK_SKIP_RATIO).toLong())

        when {
            isQuickSkip -> {
                tasteSignalTracker?.recordSignalAsync(currentTrack, ImplicitSignalType.QUICK_SKIP)
                smartRecommendationEngine?.markHeardInSession(currentTrack.id)
                consecutiveQuickSkips++
                recentQuickSkippedTracks.addLast(currentTrack)
                while (recentQuickSkippedTracks.size > MAX_RECENT_SKIP_HISTORY) {
                    recentQuickSkippedTracks.removeFirst()
                }
                if (consecutiveQuickSkips >= SOFT_RERANK_SKIP_THRESHOLD) {
                    applySoftRealtimeRerank(seedTrack = currentTrack)
                }
            }
            dur > 0L && pos < (dur * COMPLETE_PLAY_RATIO).toLong() -> {
                tasteSignalTracker?.recordSignalAsync(currentTrack, ImplicitSignalType.PARTIAL_SKIP)
                smartRecommendationEngine?.markHeardInSession(currentTrack.id)
                consecutiveQuickSkips = 0
                recentQuickSkippedTracks.clear()
            }
            else -> {
                if (dur > 0L && recordedCompletionTrackId != currentTrack.id) {
                    recordedCompletionTrackId = currentTrack.id
                    tasteSignalTracker?.recordSignalAsync(currentTrack, ImplicitSignalType.COMPLETE_PLAY)
                }
                consecutiveQuickSkips = 0
                recentQuickSkippedTracks.clear()
            }
        }
    }

    private fun applySoftRealtimeRerank(seedTrack: Track) {
        val engine = smartRecommendationEngine ?: return
        val state = _playbackState.value
        val curIdx = state.currentIndex
        if (curIdx !in state.queue.indices || curIdx >= state.queue.lastIndex) {
            ensureInfiniteQueueAhead(forceContrast = true)
            return
        }
        val prefix = state.queue.subList(0, curIdx + 1)
        val upcoming = state.queue.subList(curIdx + 1, state.queue.size)
        val shiftedUpcoming = engine.softRerankUpcoming(
            upcomingTracks = upcoming,
            seedTrack = seedTrack,
            recentSkippedTracks = recentQuickSkippedTracks.toList()
        )
        if (shiftedUpcoming != upcoming) {
            _playbackState.update {
                it.copy(queue = prefix + shiftedUpcoming)
            }
            schedulePersistPlaybackState()
            syncUpcomingMediaItemsToController()
            schedulePrefetchWindow()
        }
        if (state.repeatMode == RepeatMode.OFF && networkMonitor.isOnline.value) {
            ensureInfiniteQueueAhead(forceContrast = true)
        }
    }

    private fun onTrackCompleted() {
        val state = _playbackState.value
        val finishedTrack = state.currentTrack
        if (finishedTrack != null && recordedCompletionTrackId != finishedTrack.id) {
            recordedCompletionTrackId = finishedTrack.id
            tasteSignalTracker?.recordSignalAsync(finishedTrack, ImplicitSignalType.COMPLETE_PLAY)
        }
        consecutiveQuickSkips = 0
        recentQuickSkippedTracks.clear()

        if (state.repeatMode == RepeatMode.ONE) {
            seekTo(0L)
            mediaController?.play()
            if (finishedTrack != null) {
                tasteSignalTracker?.recordSignalAsync(finishedTrack, ImplicitSignalType.REPEAT_PLAY)
            }
            return
        }
        val nextIndex = when {
            state.shuffleEnabled && state.queue.size > 1 -> {
                val candidates = state.queue.indices.filter { it != state.currentIndex }
                candidates.random()
            }
            state.currentIndex + 1 < state.queue.size -> state.currentIndex + 1
            state.repeatMode == RepeatMode.ALL && state.queue.isNotEmpty() -> 0
            else -> null
        }
        if (nextIndex != null) {
            loadAndPlayAtIndex(nextIndex)
        } else {
            triggerAutoplayRadio()
        }
    }

    private fun ensureInfiniteQueueAhead(forceContrast: Boolean = false) {
        val engine = smartRecommendationEngine ?: return
        val state = _playbackState.value
        val currentTrack = state.currentTrack ?: return
        if (state.repeatMode != RepeatMode.OFF) return
        if (!networkMonitor.isOnline.value) return

        val remaining = (state.queue.size - 1 - state.currentIndex).coerceAtLeast(0)
        if (remaining > INFINITE_QUEUE_TRIGGER_REMAINING && !forceContrast) return
        if (infiniteQueueJob?.isActive == true && !forceContrast) return

        infiniteQueueJob?.cancel()
        infiniteQueueJob = scope.launch {
            val autoplay = settingsRepository.settings.first().autoplayEnabled
            if (!autoplay || !networkMonitor.isOnline.value) return@launch

            val excludeIds = _playbackState.value.queue.map { it.id }.toSet()
            val generated = engine.generateNextQueueTracks(
                seedTrack = currentTrack,
                targetCount = INFINITE_BATCH_SIZE,
                excludeIds = excludeIds,
                recentSkippedTracks = recentQuickSkippedTracks.toList()
            )
            if (generated.isEmpty()) return@launch

            var addedAny = false
            _playbackState.update { latest ->
                if (latest.currentTrack?.id != currentTrack.id &&
                    latest.queue.none { it.id == currentTrack.id }
                ) {
                    return@update latest
                }
                val latestIds = latest.queue.map { it.id }.toSet()
                val fresh = generated.filterNot { it.id in latestIds }
                if (fresh.isEmpty()) return@update latest
                addedAny = true
                latest.copy(queue = latest.queue + fresh)
            }
            if (addedAny) {
                schedulePersistPlaybackState()
                schedulePrefetchWindow()
            }
        }
    }

    private fun triggerAutoplayRadio() {
        val currentTrack = _playbackState.value.currentTrack
        if (currentTrack == null) {
            _playbackState.update { it.copy(isPlaying = false, positionMs = 0L) }
            return
        }
        autoplayJob?.cancel()
        autoplayJob = scope.launch {
            val autoplay = settingsRepository.settings.first().autoplayEnabled
            if (!autoplay || !networkMonitor.isOnline.value) {
                _playbackState.update { it.copy(isPlaying = false, positionMs = 0L) }
                return@launch
            }
            val latestState = _playbackState.value
            val existingIds = latestState.queue.map { it.id }.toSet()
            val freshTracks = if (smartRecommendationEngine != null) {
                smartRecommendationEngine.generateNextQueueTracks(
                    seedTrack = currentTrack,
                    targetCount = INFINITE_BATCH_SIZE,
                    excludeIds = existingIds,
                    recentSkippedTracks = recentQuickSkippedTracks.toList()
                )
            } else {
                youTubeRepository.getRelatedTracks(currentTrack.id)
                    .getOrDefault(emptyList())
                    .filterNot { it.id in existingIds }
                    .take(INFINITE_BATCH_SIZE)
            }

            val updatedState = _playbackState.value
            if (updatedState.currentTrack?.id != currentTrack.id) return@launch
            val dedupedFresh = freshTracks.filterNot { track -> updatedState.queue.any { it.id == track.id } }
            if (dedupedFresh.isNotEmpty()) {
                val oldSize = updatedState.queue.size
                val expandedQueue = updatedState.queue + dedupedFresh
                _playbackState.update { it.copy(queue = expandedQueue) }
                loadAndPlayAtIndex(oldSize)
            } else {
                _playbackState.update { it.copy(isPlaying = false, positionMs = 0L) }
            }
        }
    }

    private fun currentPrefetchDepth(): Int =
        if (networkMonitor.isMetered.value) PREFETCH_COUNT_METERED else PREFETCH_COUNT_WIFI

    private fun schedulePrefetchWindow() {
        if (!networkMonitor.isOnline.value && audioCacheProvider == null) return
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            val state = _playbackState.value
            val curIdx = state.currentIndex
            if (curIdx !in state.queue.indices) return@launch
            val depth = currentPrefetchDepth()
            if (depth <= 0) return@launch

            val targetTrackIds = buildList {
                for (offset in 1..depth) {
                    val idx = curIdx + offset
                    if (idx in state.queue.indices) {
                        add(state.queue[idx].id)
                    }
                }
            }
            if (targetTrackIds.isEmpty()) return@launch

            val quality = settingsRepository.settings.first().audioQuality
            for (targetId in targetTrackIds) {
                if (!isActive) return@launch
                val currentSnapshot = _playbackState.value
                val track = currentSnapshot.queue.firstOrNull { it.id == targetId } ?: continue

                val offlinePath = track.localFilePath?.takeIf { File(it).exists() }
                    ?: libraryRepository.getDownloadedFilePath(track.id)

                if (!offlinePath.isNullOrBlank()) {
                    if (track.localFilePath != offlinePath) {
                        updateQueuedTrack(track.copy(localFilePath = offlinePath))
                    }
                    syncUpcomingMediaItemsToController()
                    continue
                }

                if (!networkMonitor.isOnline.value) continue

                val streamUrl = track.streamUrl?.takeIf { it.isNotBlank() }
                    ?: youTubeRepository.getAudioStreamUrl(
                        videoId = track.id,
                        audioQuality = quality,
                        forceRefresh = false
                    ).getOrNull()
                    ?: continue

                if (track.streamUrl != streamUrl) {
                    updateQueuedTrack(track.copy(streamUrl = streamUrl))
                }
                audioCacheProvider?.prefetchTrackSegment(track.id, streamUrl)
                syncUpcomingMediaItemsToController()
            }
        }
    }

    private fun updateQueuedTrack(updatedTrack: Track) {
        _playbackState.update { state ->
            var changed = false
            val nextQueue = state.queue.map { existing ->
                if (existing.id == updatedTrack.id) {
                    changed = true
                    updatedTrack
                } else {
                    existing
                }
            }
            if (!changed) return@update state
            val nextCurrent = if (state.currentTrack?.id == updatedTrack.id) updatedTrack else state.currentTrack
            state.copy(currentTrack = nextCurrent, queue = nextQueue)
        }
    }

    private fun syncUpcomingMediaItemsToController() {
        val controller = mediaController ?: return
        if (controller.mediaItemCount == 0) return
        val state = _playbackState.value
        val curPlayerIdx = controller.currentMediaItemIndex
        if (curPlayerIdx < 0 || curPlayerIdx >= controller.mediaItemCount) return
        if (controller.getMediaItemAt(curPlayerIdx).mediaId != state.currentTrack?.id) return

        // Only pre-attach sequential upcoming MediaItems to ExoPlayer when not shuffling or looping one
        if (state.shuffleEnabled || state.repeatMode == RepeatMode.ONE) {
            if (controller.mediaItemCount > curPlayerIdx + 1) {
                controller.removeMediaItems(curPlayerIdx + 1, controller.mediaItemCount)
            }
            return
        }

        val depth = currentPrefetchDepth()
        val desiredItems = buildList {
            for (offset in 1..depth) {
                val upcomingTrack = state.queue.getOrNull(state.currentIndex + offset) ?: break
                val localUri = upcomingTrack.localFilePath
                    ?.takeIf { File(it).exists() }
                    ?.let { Uri.fromFile(File(it)) }
                val remoteUri = upcomingTrack.streamUrl
                    ?.takeIf { it.isNotBlank() }
                    ?.let { Uri.parse(it) }
                val resolvedUri = localUri ?: remoteUri ?: break
                add(buildMediaItem(upcomingTrack, resolvedUri))
            }
        }

        val existingCount = controller.mediaItemCount - (curPlayerIdx + 1)
        val alreadyMatches = existingCount == desiredItems.size &&
            desiredItems.indices.all { i ->
                val existingItem = controller.getMediaItemAt(curPlayerIdx + 1 + i)
                val targetItem = desiredItems[i]
                existingItem.mediaId == targetItem.mediaId &&
                    existingItem.localConfiguration?.uri == targetItem.localConfiguration?.uri
            }
        if (alreadyMatches) return

        if (controller.mediaItemCount > curPlayerIdx + 1) {
            controller.removeMediaItems(curPlayerIdx + 1, controller.mediaItemCount)
        }
        if (desiredItems.isNotEmpty()) {
            controller.addMediaItems(desiredItems)
        }
    }

    private fun syncRepeatModeToController(repeatMode: RepeatMode) {
        val controller = mediaController ?: return
        controller.repeatMode = if (repeatMode == RepeatMode.ONE) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
    }

    private fun findTransitionedTrackIndex(state: PlaybackState, targetMediaId: String): Int? {
        val next = state.currentIndex + 1
        if (next in state.queue.indices && state.queue[next].id == targetMediaId) {
            return next
        }
        for (i in (state.currentIndex + 1).coerceAtLeast(0) until state.queue.size) {
            if (state.queue[i].id == targetMediaId) return i
        }
        val fallback = state.queue.indexOfFirst { it.id == targetMediaId }
        return fallback.takeIf { it >= 0 }
    }

    private fun buildMediaItem(track: Track, mediaUri: Uri): MediaItem {
        val artworkUri = track.thumbnailUrl
            .takeIf { it.isNotBlank() }
            ?.let { Uri.parse(it) }
        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .apply { if (artworkUri != null) setArtworkUri(artworkUri) }
            .build()
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(mediaUri)
            .setMediaMetadata(mediaMetadata)
            .build()
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val controller = mediaController
                if (controller != null) {
                    val pos = controller.currentPosition.coerceAtLeast(0L)
                    val dur = controller.duration.takeIf { it > 0 } ?: _playbackState.value.durationMs
                    val next = PlaybackProgress(positionMs = pos, durationMs = dur)
                    if (_playbackProgress.value != next) {
                        _playbackProgress.value = next
                    }
                    _playbackState.update { it.copy(positionMs = pos, durationMs = dur) }

                    val activeTrack = _playbackState.value.currentTrack
                    if (activeTrack != null &&
                        dur >= MIN_COMPLETION_DURATION_MS &&
                        pos >= (dur * COMPLETE_PLAY_RATIO).toLong() &&
                        recordedCompletionTrackId != activeTrack.id
                    ) {
                        recordedCompletionTrackId = activeTrack.id
                        consecutiveQuickSkips = 0
                        recentQuickSkippedTracks.clear()
                        tasteSignalTracker?.recordSignalAsync(activeTrack, ImplicitSignalType.COMPLETE_PLAY)
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastPersistedPositionAtMs >= 5_000L) {
                        lastPersistedPositionAtMs = now
                        persistNow()
                    }
                }
                delay(500L)
            }
        }
    }

    private fun stopProgressLoop() {
        progressJob?.cancel()
    }

    private fun schedulePersistPlaybackState() {
        if (playbackPersistenceStore == null) return
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(250L)
            persistNow()
        }
    }

    private suspend fun persistNow() {
        val store = playbackPersistenceStore ?: return
        val snapshot = buildPersistedSnapshot()
        store.savePlaybackState(snapshot)
    }

    private fun buildPersistedSnapshot(): PersistedPlaybackState {
        val state = _playbackState.value
        val currentPos = mediaController?.currentPosition?.coerceAtLeast(0L) ?: state.positionMs
        return PersistedPlaybackState(
            queue = state.queue,
            currentIndex = state.currentIndex,
            positionMs = currentPos,
            shuffleEnabled = state.shuffleEnabled,
            repeatMode = state.repeatMode,
            playbackSpeed = state.playbackSpeed
        )
    }

    suspend fun getPersistedStateSnapshot(): PersistedPlaybackState {
        initialRestoreJob?.join()
        return buildPersistedSnapshot()
    }

    suspend fun applyRestoredState(restored: PersistedPlaybackState) {
        hasUserPlaybackStarted = true
        mediaController?.stop()
        mediaController?.clearMediaItems()
        applyRestoredStateInternal(restored)
        playbackPersistenceStore?.savePlaybackState(buildPersistedSnapshot())
    }

    private companion object {
        const val PREFETCH_COUNT_WIFI = 3
        const val PREFETCH_COUNT_METERED = 1
        const val INFINITE_QUEUE_TRIGGER_REMAINING = 2
        const val INFINITE_BATCH_SIZE = 10
        const val QUICK_SKIP_THRESHOLD_MS = 15_000L
        const val QUICK_SKIP_RATIO = 0.15f
        const val COMPLETE_PLAY_RATIO = 0.75f
        const val MIN_COMPLETION_DURATION_MS = 15_000L
        const val SOFT_RERANK_SKIP_THRESHOLD = 2
        const val MAX_RECENT_SKIP_HISTORY = 4
    }
}
