package com.example.ui.library

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
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var trackForPlaylistDialog by remember { mutableStateOf<Track?>(null) }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            onCreate = { name, desc ->
                viewModel.createPlaylist(name, desc)
                showCreatePlaylistDialog = false
                onShowMessage("Created playlist \"$name\"")
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
                    onShowMessage("YouTube playlist imported")
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
                onShowMessage("Added to playlist")
            },
            onCreateNewPlaylistAndAdd = { name ->
                viewModel.createPlaylistAndAddTrack(name, selectedTrack)
                trackForPlaylistDialog = null
                onShowMessage("Created \"$name\"")
            }
        )
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
                text = "Your Library",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showImportDialog = true },
                    modifier = Modifier.testTag("import_playlist_button")
                ) {
                    Icon(
                        imageVector = SonoraIcons.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Import")
                }

                Button(
                    onClick = { showCreatePlaylistDialog = true },
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
                    Text("New")
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LibraryAccountSyncBanner(
            uiState = accountUiState,
            onOpenLogin = onOpenYouTubeLogin,
            onSyncNow = { accountViewModel.syncAndMergeActiveAccount(onShowMessage) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        val tabs = listOf(
            "Playlists",
            "Liked (${uiState.favorites.size})",
            "Offline (${uiState.downloads.size})",
            "History"
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
                playlists = uiState.playlists,
                bottomContentPadding = bottomContentPadding,
                onOpenPlaylist = onOpenPlaylist,
                onDeletePlaylist = viewModel::deletePlaylist
            )

            1 -> TrackCollectionTabContent(
                tracks = uiState.favorites,
                favoriteIds = uiState.favoriteIds,
                downloadedIds = uiState.downloadedIds,
                downloadingIds = uiState.downloadingIds,
                currentTrackId = uiState.playbackState.currentTrack?.id,
                emptyIcon = Icons.Rounded.Favorite,
                emptyTitle = "No liked tracks yet",
                emptySubtitle = "Tap the heart icon on any song to save it here.",
                bottomContentPadding = bottomContentPadding,
                onPlayTrack = { track -> viewModel.playTrack(track, uiState.favorites) },
                onPlayNext = { track ->
                    viewModel.playNext(track)
                    onShowMessage("Playing next: ${track.title}")
                },
                onToggleFavorite = viewModel::toggleFavorite,
                onToggleDownload = { track -> viewModel.toggleDownload(track, onShowMessage) },
                onAddToPlaylist = { trackForPlaylistDialog = it }
            )

            2 -> TrackCollectionTabContent(
                tracks = uiState.downloads,
                favoriteIds = uiState.favoriteIds,
                downloadedIds = uiState.downloadedIds,
                downloadingIds = uiState.downloadingIds,
                currentTrackId = uiState.playbackState.currentTrack?.id,
                emptyIcon = SonoraIcons.DownloadDone,
                emptyTitle = "No offline downloads",
                emptySubtitle = "Download tracks from the menu to listen without internet.",
                bottomContentPadding = bottomContentPadding,
                onPlayTrack = { track -> viewModel.playTrack(track, uiState.downloads) },
                onPlayNext = { track ->
                    viewModel.playNext(track)
                    onShowMessage("Playing next: ${track.title}")
                },
                onToggleFavorite = viewModel::toggleFavorite,
                onToggleDownload = { track -> viewModel.toggleDownload(track, onShowMessage) },
                onAddToPlaylist = { trackForPlaylistDialog = it }
            )

            else -> Column(modifier = Modifier.fillMaxSize()) {
                if (uiState.history.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = viewModel::clearHistory) {
                            Text("Clear history")
                        }
                    }
                }
                TrackCollectionTabContent(
                    tracks = uiState.history,
                    favoriteIds = uiState.favoriteIds,
                    downloadedIds = uiState.downloadedIds,
                    downloadingIds = uiState.downloadingIds,
                    currentTrackId = uiState.playbackState.currentTrack?.id,
                    emptyIcon = SonoraIcons.History,
                    emptyTitle = "No listening history yet",
                    emptySubtitle = "Tracks you stream will appear here automatically.",
                    bottomContentPadding = bottomContentPadding,
                    onPlayTrack = { track -> viewModel.playTrack(track, uiState.history) },
                    onPlayNext = { track ->
                        viewModel.playNext(track)
                        onShowMessage("Playing next: ${track.title}")
                    },
                    onToggleFavorite = viewModel::toggleFavorite,
                    onToggleDownload = { track -> viewModel.toggleDownload(track, onShowMessage) },
                    onAddToPlaylist = { trackForPlaylistDialog = it }
                )
            }
        }
    }
}

@Composable
private fun PlaylistsTabContent(
    playlists: List<Playlist>,
    bottomContentPadding: PaddingValues,
    onOpenPlaylist: (Long) -> Unit,
    onDeletePlaylist: (Long) -> Unit
) {
    if (playlists.isEmpty()) {
        EmptyLibraryPlaceholder(
            icon = SonoraIcons.LibraryMusic,
            title = stringResource(R.string.empty_library_title),
            subtitle = stringResource(R.string.empty_library_subtitle)
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
                                    contentDescription = "${playlist.name} cover",
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
                                text = "${playlist.trackCount} tracks" +
                                    if (playlist.description.isNotBlank()) " • ${playlist.description}" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(
                            onClick = { onDeletePlaylist(playlist.id) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("delete_playlist_${playlist.id}")
                        ) {
                            Icon(
                                imageVector = SonoraIcons.DeleteOutline,
                                contentDescription = "Delete playlist",
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
    onPlayTrack: (Track) -> Unit,
    onPlayNext: (Track) -> Unit,
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
                top = 12.dp,
                bottom = bottomContentPadding.calculateBottomPadding() + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
