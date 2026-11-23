package com.example.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.core.common.DurationFormatter
import com.example.domain.model.Track
import com.example.ui.components.EditPlaylistDialog
import com.example.ui.components.SonoraIcons

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
            windowInsets = WindowInsets(0, 0, 0, 0),
            title = {
                Text(
                    text = playlist?.name ?: "Nova",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
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
                shape = CircleShape,
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
            // Hero Artwork Header Card matching Screen 7 ("Nova")
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(210.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(24.dp)
                            )
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0xFF381B45),
                                        Color(0xFF1B1425),
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!playlist?.coverUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = playlist.coverUrl,
                                contentDescription = "Обложка ${playlist.name}",
                                placeholder = painterResource(R.drawable.img_default_cover),
                                error = painterResource(R.drawable.img_default_cover),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Image(
                                painter = painterResource(R.drawable.img_hero_discover),
                                contentDescription = "Обложка Nova",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.8f)
                                        )
                                    )
                                )
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = playlist?.name ?: "Nova",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 28.sp
                            ),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = if (!playlist?.description.isNullOrBlank()) {
                                playlist.description
                            } else {
                                "Альбом • 2026 • Sonora Smart Radio"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = formatPlaylistStats(uiState.tracks.size, totalDurationSeconds),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Action buttons bar
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
                            Text("Воспроизвести", fontWeight = FontWeight.Bold)
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                itemsIndexed(filteredTracks, key = { _, track -> track.id }) { index, track ->
                    PlaylistTrackRowItem(
                        index = index + 1,
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

@Composable
private fun PlaylistTrackRowItem(
    index: Int,
    track: Track,
    isCurrentTrack: Boolean,
    isFavorite: Boolean,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    onPlayClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onPlayNextClick: () -> Unit,
    onStartRadioClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onRemoveFromPlaylistClick: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isCurrentTrack) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.55f)
                }
            )
            .border(
                width = 1.dp,
                color = if (isCurrentTrack) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                },
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onPlayClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("track_row_${track.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Track Index Number matching Screen 7 ("Nova")
        Text(
            text = "$index",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            ),
            color = if (index <= 3 || isCurrentTrack) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            },
            modifier = Modifier
                .width(28.dp)
                .padding(start = 4.dp)
        )

        val context = LocalContext.current
        val artworkRequest = remember(track.thumbnailUrl) {
            ImageRequest.Builder(context)
                .data(track.thumbnailUrl)
                .size(162)
                .crossfade(true)
                .build()
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = artworkRequest,
                contentDescription = "Обложка ${track.title}",
                placeholder = painterResource(R.drawable.img_default_cover),
                error = painterResource(R.drawable.img_default_cover),
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp)
            )
            if (isCurrentTrack) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = SonoraIcons.GraphicEq,
                        contentDescription = "Сейчас играет",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                ),
                color = if (isCurrentTrack) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Subtitle with Lossless quality tag matching Screen 7 ("Nova")
            val durationLabel = if (track.durationSeconds > 0L) {
                DurationFormatter.formatSeconds(track.durationSeconds)
            } else {
                "03:15"
            }
            Text(
                text = "${track.artist} • $durationLabel • Lossless • 24-bit",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (isDownloading) {
            Box(
                modifier = Modifier.size(36.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        IconButton(
            onClick = onFavoriteClick,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("favorite_button_${track.id}")
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = if (isFavorite) "Убрать из избранного" else "В избранное",
                tint = if (isFavorite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        Box {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f))
                    .clickable { menuExpanded = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "Опции трека",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .testTag("track_more_${track.id}")
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                DropdownMenuItem(
                    text = { Text("Включить далее") },
                    leadingIcon = {
                        Icon(SonoraIcons.QueueMusic, contentDescription = null)
                    },
                    onClick = {
                        menuExpanded = false
                        onPlayNextClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Запустить радио") },
                    leadingIcon = {
                        Icon(SonoraIcons.Radio, contentDescription = null)
                    },
                    onClick = {
                        menuExpanded = false
                        onStartRadioClick()
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(if (isDownloaded) "Удалить офлайн-копию" else "Скачать офлайн")
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (isDownloaded) {
                                SonoraIcons.DownloadDone
                            } else {
                                SonoraIcons.DownloadForOffline
                            },
                            contentDescription = null
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onDownloadClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Удалить из плейлиста") },
                    leadingIcon = {
                        Icon(SonoraIcons.DeleteOutline, contentDescription = null)
                    },
                    onClick = {
                        menuExpanded = false
                        onRemoveFromPlaylistClick()
                    }
                )
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
