package com.example.ui.player

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import coil.Coil
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.R
import com.example.core.common.DurationFormatter
import com.example.domain.model.RepeatMode
import com.example.ui.components.AddToPlaylistDialog
import com.example.ui.components.SonoraIcons
import com.example.ui.components.TrackRowItem
import com.example.ui.settings.EqualizerSheetContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NowPlayingScreen(
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playback = uiState.playbackState
    val track = playback.currentTrack
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showQueueSheet by remember { mutableStateOf(false) }
    var showLyricsSheet by remember { mutableStateOf(false) }
    var showEqualizerSheet by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }

    val defaultAccent = MaterialTheme.colorScheme.primaryContainer
    var extractedAccent by remember { mutableStateOf(defaultAccent) }

    LaunchedEffect(track?.thumbnailUrl, defaultAccent) {
        val artUrl = track?.thumbnailUrl
        if (artUrl.isNullOrBlank()) {
            extractedAccent = defaultAccent
            return@LaunchedEffect
        }
        val color = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(artUrl)
                    .allowHardware(false)
                    .size(128)
                    .build()
                val result = Coil.imageLoader(context).execute(request) as? SuccessResult
                val rawBitmap = result?.drawable?.toBitmap() ?: return@runCatching null
                val softBitmap = if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    rawBitmap.config == Bitmap.Config.HARDWARE
                ) {
                    rawBitmap.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    rawBitmap
                }
                val palette = Palette.from(softBitmap).generate()
                val rgb = palette.vibrantSwatch?.rgb
                    ?: palette.darkVibrantSwatch?.rgb
                    ?: palette.mutedSwatch?.rgb
                    ?: palette.dominantSwatch?.rgb
                rgb?.let { Color(it) }
            }.getOrNull()
        }
        extractedAccent = color ?: defaultAccent
    }

    val animatedBackdropTop by animateColorAsState(
        targetValue = extractedAccent.copy(alpha = 0.62f),
        animationSpec = tween(durationMillis = 650),
        label = "player_backdrop_color"
    )

    fun showNotice(message: String) {
        scope.launch {
            snackbarHostState.showSnackbar(message)
        }
    }

    if (showAddToPlaylistDialog && track != null) {
        AddToPlaylistDialog(
            track = track,
            playlists = uiState.playlists,
            onDismiss = { showAddToPlaylistDialog = false },
            onSelectPlaylist = { playlistId ->
                viewModel.addTrackToPlaylist(playlistId, track)
                showAddToPlaylistDialog = false
                showNotice("Added to playlist")
            },
            onCreateNewPlaylistAndAdd = { name ->
                viewModel.createPlaylistAndAddTrack(name, track)
                showAddToPlaylistDialog = false
                showNotice("Created \"$name\"")
            }
        )
    }

    if (showQueueSheet) {
        ModalBottomSheet(
            onDismissRequest = { showQueueSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Up Next (${playback.queue.size})",
                    style = MaterialTheme.typography.titleLarge
                )
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(360.dp)
                ) {
                    itemsIndexed(
                        items = playback.queue,
                        key = { index, item -> "q_${index}_${item.id}" }
                    ) { index, queueTrack ->
                        val isCurrent = index == playback.currentIndex
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                TrackRowItem(
                                    track = queueTrack,
                                    isCurrentTrack = isCurrent,
                                    isFavorite = queueTrack.id in uiState.favoriteIds,
                                    isDownloaded = queueTrack.id in uiState.downloadedIds,
                                    isDownloading = queueTrack.id in uiState.downloadingIds,
                                    onPlayClick = { viewModel.playFromQueue(queueTrack) },
                                    onFavoriteClick = { viewModel.toggleFavorite(queueTrack) }
                                )
                            }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                IconButton(
                                    onClick = { viewModel.moveQueueItem(index, index - 1) },
                                    enabled = index > 0,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardArrowUp,
                                        contentDescription = "Move up",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.moveQueueItem(index, index + 1) },
                                    enabled = index < playback.queue.lastIndex,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = "Move down",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            if (!isCurrent) {
                                IconButton(
                                    onClick = { viewModel.removeFromQueue(index) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Remove from queue",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLyricsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showLyricsSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            LyricsSheetContent(
                lyricsState = uiState.lyricsState,
                positionMs = playback.positionMs,
                onSeekTo = viewModel::seekTo,
                onRetry = viewModel::retryLyrics
            )
        }
    }

    if (showEqualizerSheet) {
        ModalBottomSheet(
            onDismissRequest = { showEqualizerSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            EqualizerSheetContent(
                state = uiState.equalizerState,
                onToggleEnabled = viewModel::setEqualizerEnabled,
                onSelectPreset = viewModel::selectEqualizerPreset,
                onBandChange = viewModel::setEqualizerBand
            )
        }
    }

    if (showSleepTimerDialog) {
        AlertDialog(
            onDismissRequest = { showSleepTimerDialog = false },
            title = { Text("Sleep Timer") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    playback.sleepTimerRemainingMs?.let { rem ->
                        Text(
                            text = "Stopping playback in ${DurationFormatter.formatMillis(rem)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } ?: Text(
                        text = "Fade out volume over the final 5 seconds and pause automatically.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val options = listOf(0 to "Off", 15 to "15m", 30 to "30m", 45 to "45m", 60 to "60m")
                        options.forEach { (mins, label) ->
                            FilterChip(
                                selected = mins == 0 && playback.sleepTimerRemainingMs == null,
                                onClick = {
                                    if (mins == 0) {
                                        viewModel.cancelSleepTimer()
                                        showNotice("Sleep timer turned off")
                                    } else {
                                        viewModel.startSleepTimer(mins)
                                        showNotice("Sleep timer set for $mins minutes")
                                    }
                                    showSleepTimerDialog = false
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSleepTimerDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        animatedBackdropTop,
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 560.dp)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("player_collapse_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Minimize player",
                        modifier = Modifier.size(32.dp)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "NOW PLAYING",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    playback.sleepTimerRemainingMs?.let { rem ->
                        Text(
                            text = "Sleep in ${DurationFormatter.formatMillis(rem)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                IconButton(
                    onClick = { showQueueSheet = true },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("player_queue_button")
                ) {
                    Icon(
                        imageVector = SonoraIcons.QueueMusic,
                        contentDescription = "Open queue"
                    )
                }
            }

            if (track == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Pick a song to start listening",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val artSize = min(maxWidth, maxHeight).coerceAtMost(310.dp)
                    AsyncImage(
                        model = track.thumbnailUrl,
                        contentDescription = "${track.title} album art",
                        placeholder = painterResource(R.drawable.img_default_cover),
                        error = painterResource(R.drawable.img_default_cover),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(artSize)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    playback.errorMessage?.let { err ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = track.artist,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        val isFav = track.id in uiState.favoriteIds
                        IconButton(
                            onClick = { viewModel.toggleFavorite(track) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("player_favorite_button")
                        ) {
                            Icon(
                                imageVector = if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = if (isFav) "Unlike track" else "Like track",
                                tint = if (isFav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        IconButton(
                            onClick = { showAddToPlaylistDialog = true },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("player_add_playlist_button")
                        ) {
                            Icon(
                                imageVector = SonoraIcons.PlaylistAdd,
                                contentDescription = "Add to playlist"
                            )
                        }
                    }

                    // Secondary Audio Tools Row: Lyrics, Offline Download, Equalizer, Sleep Timer
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { showLyricsSheet = true },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("player_lyrics_button")
                        ) {
                            Icon(
                                imageVector = SonoraIcons.Lyrics,
                                contentDescription = "Show lyrics",
                                tint = if (uiState.lyricsState is LyricsUiState.Success) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }

                        val isDownloaded = track.id in uiState.downloadedIds
                        val isDownloading = track.id in uiState.downloadingIds
                        IconButton(
                            onClick = { viewModel.toggleDownload(track, ::showNotice) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("player_download_button")
                        ) {
                            if (isDownloading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = if (isDownloaded) {
                                        SonoraIcons.DownloadDone
                                    } else {
                                        SonoraIcons.DownloadForOffline
                                    },
                                    contentDescription = if (isDownloaded) {
                                        "Remove offline download"
                                    } else {
                                        "Download track"
                                    },
                                    tint = if (isDownloaded) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }

                        IconButton(
                            onClick = { showEqualizerSheet = true },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("player_equalizer_button")
                        ) {
                            Icon(
                                imageVector = SonoraIcons.Equalizer,
                                contentDescription = "Audio equalizer",
                                tint = if (uiState.equalizerState.isEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }

                        IconButton(
                            onClick = { showSleepTimerDialog = true },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("player_sleep_timer_button")
                        ) {
                            Icon(
                                imageVector = SonoraIcons.Bedtime,
                                contentDescription = "Sleep timer",
                                tint = if (playback.sleepTimerRemainingMs != null) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }

                    val totalMs = playback.durationMs.coerceAtLeast(1L)
                    var isDragging by remember { mutableStateOf(false) }
                    var dragValue by remember { mutableFloatStateOf(0f) }
                    val playbackProgress = (playback.positionMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
                    val sliderValue = if (isDragging) dragValue else playbackProgress

                    Column {
                        Slider(
                            value = sliderValue,
                            onValueChange = {
                                isDragging = true
                                dragValue = it
                            },
                            onValueChangeFinished = {
                                viewModel.seekTo((dragValue * totalMs).toLong())
                                isDragging = false
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.outline
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("player_seek_slider")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val displayedPosMs = if (isDragging) {
                                (dragValue * totalMs).toLong()
                            } else {
                                playback.positionMs
                            }
                            Text(
                                text = DurationFormatter.formatMillis(displayedPosMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = DurationFormatter.formatMillis(playback.durationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = viewModel::toggleShuffle,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("player_shuffle_button")
                        ) {
                            Icon(
                                imageVector = SonoraIcons.Shuffle,
                                contentDescription = "Toggle shuffle",
                                tint = if (playback.shuffleEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }

                        IconButton(
                            onClick = viewModel::skipPrevious,
                            modifier = Modifier
                                .size(56.dp)
                                .testTag("player_prev_button")
                        ) {
                            Icon(
                                imageVector = SonoraIcons.SkipPrevious,
                                contentDescription = "Previous track",
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        FilledIconButton(
                            onClick = viewModel::togglePlayPause,
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier
                                .size(68.dp)
                                .testTag("player_play_pause_button")
                        ) {
                            if (playback.isLoadingStream || playback.isBuffering) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 3.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(
                                    imageVector = if (playback.isPlaying) {
                                        SonoraIcons.Pause
                                    } else {
                                        Icons.Rounded.PlayArrow
                                    },
                                    contentDescription = if (playback.isPlaying) "Pause" else "Play",
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = viewModel::skipNext,
                            modifier = Modifier
                                .size(56.dp)
                                .testTag("player_next_button")
                        ) {
                            Icon(
                                imageVector = SonoraIcons.SkipNext,
                                contentDescription = "Next track",
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        IconButton(
                            onClick = viewModel::cycleRepeatMode,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("player_repeat_button")
                        ) {
                            val repeatIcon = when (playback.repeatMode) {
                                RepeatMode.ONE -> SonoraIcons.RepeatOne
                                else -> SonoraIcons.Repeat
                            }
                            Icon(
                                imageVector = repeatIcon,
                                contentDescription = "Repeat mode",
                                tint = if (playback.repeatMode != RepeatMode.OFF) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 56.dp, start = 16.dp, end = 16.dp)
        )
    }
}

@Composable
private fun LyricsSheetContent(
    lyricsState: LyricsUiState,
    positionMs: Long,
    onSeekTo: (Long) -> Unit,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Lyrics", style = MaterialTheme.typography.titleLarge)

        when (lyricsState) {
            LyricsUiState.Idle -> {
                Text(
                    text = "Play a track to load synced lyrics.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            LyricsUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is LyricsUiState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = lyricsState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onRetry) {
                        Text("Retry")
                    }
                }
            }

            is LyricsUiState.Success -> {
                val result = lyricsState.result
                val activeIndex = if (result.isSynced) {
                    result.lines.indexOfLast { it.timeMs <= positionMs }
                } else {
                    -1
                }
                val listState = rememberLazyListState()

                LaunchedEffect(activeIndex) {
                    if (activeIndex >= 0) {
                        listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(result.lines) { index, line ->
                        val isActive = index == activeIndex
                        Text(
                            text = line.text,
                            style = if (isActive) {
                                MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            } else {
                                MaterialTheme.typography.bodyLarge
                            },
                            color = if (isActive) {
                                MaterialTheme.colorScheme.primary
                            } else if (result.isSynced && index < activeIndex) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = result.isSynced) {
                                    onSeekTo(line.timeMs)
                                }
                                .padding(vertical = 4.dp, horizontal = 6.dp)
                        )
                    }
                }
            }
        }
    }
}
