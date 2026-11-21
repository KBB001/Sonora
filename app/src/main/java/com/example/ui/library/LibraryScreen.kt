package com.example.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.R
import com.example.domain.model.Playlist
import com.example.domain.model.Track
import com.example.ui.account.LibraryAccountSyncBanner
import com.example.ui.account.YouTubeAccountViewModel
import com.example.ui.components.AddToPlaylistDialog
import com.example.ui.components.CreatePlaylistDialog
import com.example.ui.components.ImportYouTubePlaylistDialog
import com.example.ui.components.SonoraIcons
import com.example.ui.components.TrackRowItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenPlaylist: (Long) -> Unit,
    onOpenYouTubeLogin: () -> Unit,
    bottomContentPadding: PaddingValues,
    onShowMessage: (String) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
    accountViewModel: YouTubeAccountViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val accountUiState by accountViewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var filterQuery by rememberSaveable { mutableStateOf("") }
    var showFilterBar by rememberSaveable { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var trackForPlaylistDialog by remember { mutableStateOf<Track?>(null) }
    var playlistPendingDelete by remember { mutableStateOf<Playlist?>(null) }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            onCreate = { name, desc ->
                viewModel.createPlaylist(name, desc)
                showCreatePlaylistDialog = false
                onShowMessage("Плейлист «$name» создан")
            }
        )
    }

    if (showImportDialog) {
        ImportYouTubePlaylistDialog(
            isLoading = uiState.isImportingPlaylist,
            errorMessage = uiState.importError,
            onDismiss = {
                viewModel.clearImportError()
                showImportDialog = false
            },
            onImport = { url ->
                viewModel.importYouTubePlaylist(url) {
                    showImportDialog = false
                    onShowMessage("Плейлист YouTube импортирован")
                }
            }
        )
    }

    playlistPendingDelete?.let { playlist ->
        AlertDialog(
            onDismissRequest = { playlistPendingDelete = null },
            title = { Text("Удалить плейлист?") },
            text = {
                Text("Плейлист «${playlist.name}» будет удалён из медиатеки устройства.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deletePlaylist(playlist.id)
                        onShowMessage("Плейлист «${playlist.name}» удалён")
                        playlistPendingDelete = null
                    }
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistPendingDelete = null }) {
                    Text("Отмена")
                }
            }
        )
    }

    trackForPlaylistDialog?.let { selectedTrack ->
        AddToPlaylistDialog(
            track = selectedTrack,
            playlists = uiState.playlists,
            onDismiss = { trackForPlaylistDialog = null },
            onSelectPlaylist = { playlistId ->
                viewModel.addTrackToPlaylist(playlistId, selectedTrack)
                trackForPlaylistDialog = null
                onShowMessage("Трек добавлен в плейлист")
            },
            onCreateNewPlaylistAndAdd = { name ->
                viewModel.createPlaylistAndAddTrack(name, selectedTrack)
                trackForPlaylistDialog = null
                onShowMessage("Плейлист «$name» создан")
            }
        )
    }

    val normalizedFilter = filterQuery.trim()
    val filteredPlaylists = remember(uiState.playlists, normalizedFilter) {
        if (normalizedFilter.isEmpty()) uiState.playlists
        else uiState.playlists.filter {
            it.name.contains(normalizedFilter, ignoreCase = true) ||
                it.description.contains(normalizedFilter, ignoreCase = true)
        }
    }
    val filteredFavorites = remember(uiState.favorites, normalizedFilter) {
        filterTracks(uiState.favorites, normalizedFilter)
    }
    val filteredDownloads = remember(uiState.downloads, normalizedFilter) {
        filterTracks(uiState.downloads, normalizedFilter)
    }
    val filteredHistory = remember(uiState.history, normalizedFilter) {
        filterTracks(uiState.history, normalizedFilter)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Медиатека",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        showFilterBar = !showFilterBar
                        if (!showFilterBar) filterQuery = ""
                    },
                    modifier = Modifier.testTag("library_filter_toggle")
                ) {
                    Icon(
                        imageVector = SonoraIcons.FilterList,
                        contentDescription = "Фильтр медиатеки",
                        tint = if (showFilterBar || filterQuery.isNotEmpty()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                OutlinedButton(
                    onClick = { showImportDialog = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("import_playlist_button")
                ) {
                    Icon(
                        imageVector = SonoraIcons.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Импорт")
                }

                Button(
                    onClick = { showCreatePlaylistDialog = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.testTag("create_playlist_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Создать")
                }
            }
        }

        AnimatedVisibility(visible = showFilterBar) {
            OutlinedTextField(
                value = filterQuery,
                onValueChange = { filterQuery = it },
                placeholder = { Text("Поиск в медиатеке...") },
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
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("library_filter_input")
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        LibraryAccountSyncBanner(
            uiState = accountUiState,
            onOpenLogin = onOpenYouTubeLogin,
            onSyncNow = { accountViewModel.syncAndMergeActiveAccount(onShowMessage) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        val tabs = listOf(
            "Плейлисты",
            "Избранное (${uiState.favorites.size})",
            "Офлайн (${uiState.downloads.size})",
            "История"
        )
        PrimaryScrollableTabRow(
            selectedTabIndex = selectedTab,
            edgePadding = 16.dp,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1
                        )
                    },
                    modifier = Modifier.testTag("library_tab_$index")
                )
            }
        }

        when (selectedTab) {
            0 -> PlaylistsTabContent(
                playlists = filteredPlaylists,
                isFiltering = normalizedFilter.isNotEmpty(),
                bottomContentPadding = bottomContentPadding,
                onOpenPlaylist = onOpenPlaylist,
                onDeletePlaylist = { playlistPendingDelete = it }
            )

            1 -> TrackCollectionTabContent(
                tracks = filteredFavorites,
                favoriteIds = uiState.favoriteIds,
                downloadedIds = uiState.downloadedIds,
                downloadingIds = uiState.downloadingIds,
                currentTrackId = uiState.playbackState.currentTrack?.id,
                emptyIcon = Icons.Rounded.Favorite,
                emptyTitle = if (normalizedFilter.isNotEmpty()) "Ничего не найдено" else "Пока нет избранных треков",
                emptySubtitle = if (normalizedFilter.isNotEmpty()) {
                    "Попробуйте изменить поисковый запрос."
                } else {
                    "Нажмите на сердечко у любого трека, чтобы сохранить его здесь."
                },
                bottomContentPadding = bottomContentPadding,
                onPlayAll = { shuffle -> viewModel.playAll(filteredFavorites, shuffle) },
                onPlayTrack = { track -> viewModel.playTrack(track, filteredFavorites) },
                onPlayNext = { track ->
                    viewModel.playNext(track)
                    onShowMessage("«${track.title}» включится далее")
                },
                onStartRadio = { track -> viewModel.startTrackRadio(track, onShowMessage) },
                onToggleFavorite = viewModel::toggleFavorite,
                onToggleDownload = { track -> viewModel.toggleDownload(track, onShowMessage) },
                onAddToPlaylist = { trackForPlaylistDialog = it }
            )

            2 -> TrackCollectionTabContent(
                tracks = filteredDownloads,
                favoriteIds = uiState.favoriteIds,
                downloadedIds = uiState.downloadedIds,
                downloadingIds = uiState.downloadingIds,
                currentTrackId = uiState.playbackState.currentTrack?.id,
                emptyIcon = SonoraIcons.DownloadDone,
                emptyTitle = if (normalizedFilter.isNotEmpty()) "Ничего не найдено" else "Нет скачанных треков",
                emptySubtitle = if (normalizedFilter.isNotEmpty()) {
                    "Попробуйте изменить поисковый запрос."
                } else {
                    "Скачивайте треки через меню, чтобы слушать музыку без интернета."
                },
                bottomContentPadding = bottomContentPadding,
                onPlayAll = { shuffle -> viewModel.playAll(filteredDownloads, shuffle) },
                onPlayTrack = { track -> viewModel.playTrack(track, filteredDownloads) },
                onPlayNext = { track ->
                    viewModel.playNext(track)
                    onShowMessage("«${track.title}» включится далее")
                },
                onStartRadio = { track -> viewModel.startTrackRadio(track, onShowMessage) },
                onToggleFavorite = viewModel::toggleFavorite,
                onToggleDownload = { track -> viewModel.toggleDownload(track, onShowMessage) },
                onAddToPlaylist = { trackForPlaylistDialog = it }
            )

            else -> TrackCollectionTabContent(
                tracks = filteredHistory,
                favoriteIds = uiState.favoriteIds,
                downloadedIds = uiState.downloadedIds,
                downloadingIds = uiState.downloadingIds,
                currentTrackId = uiState.playbackState.currentTrack?.id,
                emptyIcon = SonoraIcons.History,
                emptyTitle = if (normalizedFilter.isNotEmpty()) "Ничего не найдено" else "История прослушивания пуста",
                emptySubtitle = if (normalizedFilter.isNotEmpty()) {
                    "Попробуйте изменить поисковый запрос."
                } else {
                    "Прослушанные треки появятся здесь автоматически."
                },
                bottomContentPadding = bottomContentPadding,
                onPlayAll = { shuffle -> viewModel.playAll(filteredHistory, shuffle) },
                onClearHistory = if (uiState.history.isNotEmpty()) viewModel::clearHistory else null,
                onPlayTrack = { track -> viewModel.playTrack(track, filteredHistory) },
                onPlayNext = { track ->
                    viewModel.playNext(track)
                    onShowMessage("«${track.title}» включится далее")
                },
                onStartRadio = { track -> viewModel.startTrackRadio(track, onShowMessage) },
                onToggleFavorite = viewModel::toggleFavorite,
                onToggleDownload = { track -> viewModel.toggleDownload(track, onShowMessage) },
                onAddToPlaylist = { trackForPlaylistDialog = it }
            )
        }
    }
}

private fun filterTracks(tracks: List<Track>, query: String): List<Track> {
    if (query.isEmpty()) return tracks
    return tracks.filter {
        it.title.contains(query, ignoreCase = true) ||
            it.artist.contains(query, ignoreCase = true)
    }
}

@Composable
private fun PlaylistsTabContent(
    playlists: List<Playlist>,
    isFiltering: Boolean,
    bottomContentPadding: PaddingValues,
    onOpenPlaylist: (Long) -> Unit,
    onDeletePlaylist: (Playlist) -> Unit
) {
    if (playlists.isEmpty()) {
        EmptyLibraryPlaceholder(
            icon = SonoraIcons.LibraryMusic,
            title = if (isFiltering) "Плейлисты не найдены" else stringResource(R.string.empty_library_title),
            subtitle = if (isFiltering) "Попробуйте другой поисковый запрос." else stringResource(R.string.empty_library_subtitle)
        )
    } else {
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = bottomContentPadding.calculateBottomPadding() + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(playlists, key = { it.id }) { playlist ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onOpenPlaylist(playlist.id) }
                        .testTag("playlist_card_${playlist.id}"),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!playlist.coverUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = playlist.coverUrl,
                                    contentDescription = "${playlist.name} обложка",
                                    placeholder = painterResource(R.drawable.img_default_cover),
                                    error = painterResource(R.drawable.img_default_cover),
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    imageVector = SonoraIcons.QueueMusic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${playlist.trackCount} треков" +
                                    if (playlist.description.isNotBlank()) " • ${playlist.description}" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(
                            onClick = { onDeletePlaylist(playlist) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("delete_playlist_${playlist.id}")
                        ) {
                            Icon(
                                imageVector = SonoraIcons.DeleteOutline,
                                contentDescription = "Удалить плейлист",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackCollectionTabContent(
    tracks: List<Track>,
    favoriteIds: Set<String>,
    downloadedIds: Set<String>,
    downloadingIds: Set<String>,
    currentTrackId: String?,
    emptyIcon: androidx.compose.ui.graphics.vector.ImageVector,
    emptyTitle: String,
    emptySubtitle: String,
    bottomContentPadding: PaddingValues,
    onPlayAll: (Boolean) -> Unit,
    onClearHistory: (() -> Unit)? = null,
    onPlayTrack: (Track) -> Unit,
    onPlayNext: (Track) -> Unit,
    onStartRadio: (Track) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onToggleDownload: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit
) {
    if (tracks.isEmpty()) {
        EmptyLibraryPlaceholder(
            icon = emptyIcon,
            title = emptyTitle,
            subtitle = emptySubtitle
        )
    } else {
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 10.dp,
                bottom = bottomContentPadding.calculateBottomPadding() + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "collection_actions_bar") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onPlayAll(false) },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Слушать")
                        }
                        OutlinedButton(
                            onClick = { onPlayAll(true) },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(SonoraIcons.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Микс")
                        }
                    }

                    if (onClearHistory != null) {
                        TextButton(onClick = onClearHistory) {
                            Text("Очистить")
                        }
                    }
                }
            }

            items(tracks, key = { it.id }) { track ->
                TrackRowItem(
                    track = track,
                    isCurrentTrack = currentTrackId == track.id,
                    isFavorite = track.id in favoriteIds,
                    isDownloaded = track.id in downloadedIds,
                    isDownloading = track.id in downloadingIds,
                    onPlayClick = { onPlayTrack(track) },
                    onFavoriteClick = { onToggleFavorite(track) },
                    onPlayNextClick = { onPlayNext(track) },
                    onStartRadioClick = { onStartRadio(track) },
                    onDownloadClick = { onToggleDownload(track) },
                    onAddToPlaylistClick = { onAddToPlaylist(track) }
                )
            }
        }
    }
}

@Composable
private fun EmptyLibraryPlaceholder(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                modifier = Modifier.size(52.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
