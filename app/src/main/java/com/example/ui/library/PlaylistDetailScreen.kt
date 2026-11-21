package com.example.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.R
import com.example.ui.components.EditPlaylistDialog
import com.example.ui.components.SonoraIcons
import com.example.ui.components.TrackRowItem

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlaylistDetailScreen(
    onBack: () -> Unit,
    bottomContentPadding: PaddingValues,
    onShowMessage: (String) -> Unit,
    viewModel: PlaylistDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playlist = uiState.playlist
    var showEditDialog by remember { mutableStateOf(false) }
    var showFilterInput by rememberSaveable { mutableStateOf(false) }
    var filterQuery by rememberSaveable { mutableStateOf("") }

    if (showEditDialog && playlist != null) {
        EditPlaylistDialog(
            initialName = playlist.name,
            initialDescription = playlist.description,
            onDismiss = { showEditDialog = false },
            onSave = { newName, newDescription ->
                viewModel.updatePlaylistDetails(newName, newDescription)
                showEditDialog = false
                onShowMessage("Плейлист обновлён")
            }
        )
    }

    val filteredTracks = remember(uiState.tracks, filterQuery) {
        val q = filterQuery.trim()
        if (q.isEmpty()) uiState.tracks
        else uiState.tracks.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.artist.contains(q, ignoreCase = true)
        }
    }

    val totalDurationSeconds = remember(uiState.tracks) {
        uiState.tracks.sumOf { it.durationSeconds.coerceAtLeast(0L) }
    }
    val hasMissingOffline = remember(uiState.tracks, uiState.downloadedIds) {
        uiState.tracks.any { it.id !in uiState.downloadedIds }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = { Text(playlist?.name ?: "Плейлист") },
            navigationIcon = {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("playlist_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Назад"
                    )
                }
            },
            actions = {
                if (uiState.tracks.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            showFilterInput = !showFilterInput
                            if (!showFilterInput) filterQuery = ""
                        },
                        modifier = Modifier.testTag("playlist_filter_button")
                    ) {
                        Icon(
                            imageVector = SonoraIcons.FilterList,
                            contentDescription = "Фильтр треков",
                            tint = if (showFilterInput || filterQuery.isNotEmpty()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
                if (playlist != null) {
                    IconButton(
                        onClick = { showEditDialog = true },
                        modifier = Modifier.testTag("playlist_edit_button")
                    ) {
                        Icon(
                            imageVector = SonoraIcons.Edit,
                            contentDescription = "Редактировать плейлист"
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        AnimatedVisibility(visible = showFilterInput) {
            OutlinedTextField(
                value = filterQuery,
                onValueChange = { filterQuery = it },
                placeholder = { Text("Поиск в плейлисте...") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                trailingIcon = {
                    if (filterQuery.isNotEmpty()) {
                        IconButton(onClick = { filterQuery = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Очистить фильтр")
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("playlist_filter_input")
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                bottom = bottomContentPadding.calculateBottomPadding() + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AsyncImage(
                        model = playlist?.coverUrl,
                        contentDescription = "Обложка плейлиста",
                        placeholder = painterResource(R.drawable.img_default_cover),
                        error = painterResource(R.drawable.img_default_cover),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(172.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    )

                    Text(
                        text = playlist?.name ?: "Плейлист",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    if (!playlist?.description.isNullOrBlank()) {
                        Text(
                            text = playlist.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = formatPlaylistStats(uiState.tracks.size, totalDurationSeconds),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        Button(
                            onClick = { viewModel.playAll(shuffle = false) },
                            enabled = uiState.tracks.isNotEmpty(),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier.testTag("playlist_play_all_button")
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Воспроизвести")
                        }

                        OutlinedButton(
                            onClick = { viewModel.playAll(shuffle = true) },
                            enabled = uiState.tracks.isNotEmpty(),
                            shape = CircleShape,
                            modifier = Modifier.testTag("playlist_shuffle_button")
                        ) {
                            Icon(SonoraIcons.Shuffle, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Перемешать")
                        }

                        OutlinedButton(
                            onClick = { viewModel.addAllToQueue(onShowMessage) },
                            enabled = uiState.tracks.isNotEmpty(),
                            shape = CircleShape,
                            modifier = Modifier.testTag("playlist_add_queue_button")
                        ) {
                            Icon(SonoraIcons.PlaylistPlay, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("В очередь")
                        }

                        if (hasMissingOffline && uiState.tracks.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { viewModel.downloadAllMissing(onShowMessage) },
                                enabled = !uiState.isBatchDownloading,
                                shape = CircleShape,
                                modifier = Modifier.testTag("playlist_download_all_button")
                            ) {
                                if (uiState.isBatchDownloading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        SonoraIcons.DownloadForOffline,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (uiState.isBatchDownloading) "Загрузка..." else "Скачать всё")
                            }
                        }
                    }
                }
            }

            if (filteredTracks.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (filterQuery.isNotBlank()) {
                                "По запросу «$filterQuery» ничего не найдено."
                            } else {
                                "В плейлисте пока нет треков. Добавляйте музыку с главной страницы или из поиска."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(filteredTracks, key = { it.id }) { track ->
                    TrackRowItem(
                        track = track,
                        isCurrentTrack = uiState.playbackState.currentTrack?.id == track.id,
                        isFavorite = track.id in uiState.favoriteIds,
                        isDownloaded = track.id in uiState.downloadedIds,
                        isDownloading = track.id in uiState.downloadingIds,
                        onPlayClick = { viewModel.playTrack(track) },
                        onFavoriteClick = { viewModel.toggleFavorite(track) },
                        onPlayNextClick = {
                            viewModel.playNext(track)
                            onShowMessage("«${track.title}» включится далее")
                        },
                        onStartRadioClick = { viewModel.startTrackRadio(track, onShowMessage) },
                        onDownloadClick = { viewModel.toggleDownload(track, onShowMessage) },
                        onRemoveFromPlaylistClick = {
                            viewModel.removeTrack(track.id)
                            onShowMessage("Трек удалён из плейлиста")
                        }
                    )
                }
            }
        }
    }
}

private fun formatPlaylistStats(trackCount: Int, totalSeconds: Long): String {
    if (totalSeconds <= 0L) return "$trackCount треков"
    val totalMinutes = totalSeconds / 60L
    val hours = totalMinutes / 60L
    val mins = totalMinutes % 60L
    val durationLabel = if (hours > 0L) "$hours ч $mins мин" else "$mins мин"
    return "$trackCount треков • $durationLabel"
}
