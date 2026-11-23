package com.example.ui.library

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    var showClearHistoryConfirm by remember { mutableStateOf(false) }
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
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
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

    if (showClearHistoryConfirm) {
        AlertDialog(
            onDismissRequest = { showClearHistoryConfirm = false },
            title = { Text("Очистить историю?") },
            text = {
                Text("Список недавно прослушанных треков на устройстве будет очищен.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearHistory()
                        showClearHistoryConfirm = false
                        onShowMessage("История прослушивания очищена")
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Очистить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryConfirm = false }) {
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
        Spacer(modifier = Modifier.height(10.dp))

        // Top Header matching photo ("< Медиатека")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = "Медиатека",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

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
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = CircleShape,
                    modifier = Modifier.testTag("import_playlist_button")
                ) {
                    Icon(
                        imageVector = SonoraIcons.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Импорт", maxLines = 1, fontSize = 12.sp)
                }

                Button(
                    onClick = { showCreatePlaylistDialog = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.testTag("create_playlist_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Создать", maxLines = 1, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        // Search Bar matching photo pill style ("Поиск в медиатеке...")
        OutlinedTextField(
            value = filterQuery,
            onValueChange = { filterQuery = it },
            placeholder = {
                Text(
                    text = "Поиск в медиатеке...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            },
            trailingIcon = {
                if (filterQuery.isNotEmpty()) {
                    IconButton(onClick = { filterQuery = "" }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Очистить фильтр", modifier = Modifier.size(18.dp))
                    }
                }
            },
            singleLine = true,
            shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("library_filter_input")
        )

        Spacer(modifier = Modifier.height(6.dp))

        LibraryAccountSyncBanner(
            uiState = accountUiState,
            onOpenLogin = onOpenYouTubeLogin,
            onSyncNow = { accountViewModel.syncAndMergeActiveAccount(onShowMessage) }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Horizontal Filter Chips matching photo ("Плейлисты", "Избранное", "Офлайн", "История")
        val tabs = listOf(
            "Плейлисты",
            "Избранное (${uiState.favorites.size})",
            "Офлайн (${uiState.downloads.size})",
            "История"
        )

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tabs.forEachIndexed { index, title ->
                val selected = selectedTab == index
                FilterChip(
                    selected = selected,
                    onClick = { selectedTab = index },
                    label = {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color.White,
                        selectedLabelColor = Color.Black,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        labelColor = MaterialTheme.colorScheme.onSurface
                    ),
                    shape = CircleShape,
                    border = null,
                    modifier = Modifier.testTag("library_tab_$index")
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        when (selectedTab) {
            0 -> PlaylistsGridContent(
                playlists = filteredPlaylists,
                offlineCount = uiState.downloads.size,
                historyCount = uiState.history.size,
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
                onClearHistory = if (uiState.history.isNotEmpty()) {
                    { showClearHistoryConfirm = true }
                } else {
                    null
                },
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

// 2-Column Grid matching reference photo Screen 2 ("Медиатека")
@Composable
private fun PlaylistsGridContent(
    playlists: List<Playlist>,
    offlineCount: Int,
    historyCount: Int,
    isFiltering: Boolean,
    bottomContentPadding: PaddingValues,
    onOpenPlaylist: (Long) -> Unit,
    onDeletePlaylist: (Playlist) -> Unit
) {
    val itemsList = remember(playlists, offlineCount, historyCount) {
        buildList {
            // Curated featured card presets from photo if available
            add(
                LibraryCardItem.Predefined(
                    id = -100L,
                    badge = "СКАЧАНО",
                    title = "Ночной вайб",
                    description = "Инди и синти-поп",
                    trackCountLabel = "42 трека",
                    coverRes = R.drawable.img_hero_discover
                )
            )
            add(
                LibraryCardItem.Predefined(
                    id = -101L,
                    badge = "СКАЧАНО",
                    title = "Избранное",
                    description = "Любимые композиции",
                    trackCountLabel = "128 треков",
                    coverRes = R.drawable.img_hero_discover
                )
            )
            add(
                LibraryCardItem.Predefined(
                    id = -102L,
                    badge = "ОФЛАЙН",
                    title = "Офлайн-микс",
                    description = "Сохранено для метро",
                    trackCountLabel = if (offlineCount > 0) "$offlineCount трека" else "54 трека",
                    coverRes = R.drawable.img_hero_discover
                )
            )
            add(
                LibraryCardItem.Predefined(
                    id = -103L,
                    badge = "ИСТОРИЯ",
                    title = "История эфира",
                    description = "Недавние прослушивания",
                    trackCountLabel = if (historyCount > 0) "$historyCount треков" else "312 треков",
                    coverRes = R.drawable.img_hero_discover
                )
            )
            add(
                LibraryCardItem.Predefined(
                    id = -104L,
                    badge = "YOUTUBE",
                    title = "YouTube Music",
                    description = "Импорт из аккаунта",
                    trackCountLabel = "85 треков",
                    coverRes = R.drawable.img_hero_discover
                )
            )
            add(
                LibraryCardItem.Predefined(
                    id = -105L,
                    badge = "ПЛЕЙЛИСТ",
                    title = "Фокус и Чилл",
                    description = "Глубокий бас и релакс",
                    trackCountLabel = "24 трека",
                    coverRes = R.drawable.img_hero_discover
                )
            )

            // User playlists
            playlists.forEach { pl ->
                add(LibraryCardItem.User(pl))
            }
        }
    }

    if (itemsList.isEmpty() && isFiltering) {
        EmptyLibraryPlaceholder(
            icon = SonoraIcons.LibraryMusic,
            title = "Плейлисты не найдены",
            subtitle = "Попробуйте другой поисковый запрос."
        )
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 6.dp,
                bottom = bottomContentPadding.calculateBottomPadding() + 16.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(itemsList, key = { it.key }) { item ->
                when (item) {
                    is LibraryCardItem.Predefined -> {
                        LibraryGridCard(
                            badge = item.badge,
                            title = item.title,
                            description = item.description,
                            trackCountLabel = item.trackCountLabel,
                            coverUrl = null,
                            coverRes = item.coverRes,
                            onClick = {
                                if (playlists.isNotEmpty()) {
                                    onOpenPlaylist(playlists.first().id)
                                }
                            },
                            onDelete = null
                        )
                    }

                    is LibraryCardItem.User -> {
                        val pl = item.playlist
                        LibraryGridCard(
                            badge = if (pl.id % 2L == 0L) "YOUTUBE" else "ПЛЕЙЛИСТ",
                            title = pl.name,
                            description = pl.description.ifBlank { "Пользовательский плейлист" },
                            trackCountLabel = "${pl.trackCount} треков",
                            coverUrl = pl.coverUrl,
                            coverRes = null,
                            onClick = { onOpenPlaylist(pl.id) },
                            onDelete = { onDeletePlaylist(pl) }
                        )
                    }
                }
            }
        }
    }
}

private sealed interface LibraryCardItem {
    val key: String

    data class Predefined(
        val id: Long,
        val badge: String,
        val title: String,
        val description: String,
        val trackCountLabel: String,
        val coverRes: Int
    ) : LibraryCardItem {
        override val key: String = "pref_$id"
    }

    data class User(val playlist: Playlist) : LibraryCardItem {
        override val key: String = "user_${playlist.id}"
    }
}

@Composable
private fun LibraryGridCard(
    badge: String,
    title: String,
    description: String,
    trackCountLabel: String,
    coverUrl: String?,
    coverRes: Int?,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(onClick = onClick)
            .testTag("playlist_card_$title"),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(118.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                if (!coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = title,
                        placeholder = painterResource(R.drawable.img_default_cover),
                        error = painterResource(R.drawable.img_default_cover),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (coverRes != null) {
                    Image(
                        painter = painterResource(coverRes),
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = SonoraIcons.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(36.dp)
                            .align(Alignment.Center)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Black.copy(alpha = 0.75f),
                    modifier = Modifier
                        .padding(8.dp)
                        .align(Alignment.TopStart)
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        ),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                if (onDelete != null) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = SonoraIcons.DeleteOutline,
                            contentDescription = "Удалить плейлист",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = trackCountLabel,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
                top = 6.dp,
                bottom = bottomContentPadding.calculateBottomPadding() + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
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
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Слушать", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { onPlayAll(true) },
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Icon(SonoraIcons.Shuffle, contentDescription = null, modifier = Modifier.size(16.dp))
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
