package com.example.ui.home

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.R
import com.example.domain.model.Playlist
import com.example.domain.model.Track
import com.example.domain.model.YouTubeAccount
import com.example.ui.components.AddToPlaylistDialog
import com.example.ui.components.SonoraIcons
import com.example.ui.components.TrackRowItem
import java.util.Calendar

@Composable
fun HomeScreen(
    onNavigateToSearch: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    bottomContentPadding: PaddingValues,
    onShowMessage: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var trackForPlaylistDialog by remember { mutableStateOf<Track?>(null) }

    val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = remember(currentHour) {
        when (currentHour) {
            in 5..11 -> "Доброе утро"
            in 12..17 -> "Добрый день"
            in 18..22 -> "Добрый вечер"
            else -> "Ночной эфир"
        }
    }

    trackForPlaylistDialog?.let { selectedTrack ->
        AddToPlaylistDialog(
            track = selectedTrack,
            playlists = uiState.playlists,
            onDismiss = { trackForPlaylistDialog = null },
            onSelectPlaylist = { playlistId ->
                viewModel.addTrackToPlaylist(playlistId, selectedTrack)
                onShowMessage("Трек добавлен в плейлист")
                trackForPlaylistDialog = null
            },
            onCreateNewPlaylistAndAdd = { name ->
                viewModel.createPlaylistAndAddTrack(name, selectedTrack)
                onShowMessage("Плейлист «$name» создан")
                trackForPlaylistDialog = null
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            top = 10.dp,
            bottom = bottomContentPadding.calculateBottomPadding() + 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            HomeTopHeader(
                greeting = greeting,
                account = uiState.activeAccount,
                onSearchClick = onNavigateToSearch,
                onAccountClick = onOpenSettings
            )
        }

        item {
            HeroBannerCard(
                currentTrack = uiState.playbackState.currentTrack,
                isPlaying = uiState.playbackState.isPlaying,
                lastHistoryTrack = uiState.history.firstOrNull(),
                hasDiscoverTracks = (uiState.discoverState as? DiscoverUiState.Success)?.tracks?.isNotEmpty() == true,
                onSearchClick = onNavigateToSearch,
                onPrimaryActionClick = {
                    viewModel.resumeLastOrMix(onEmpty = onNavigateToSearch)
                },
                onShuffleMixClick = { viewModel.playMix(shuffle = true) }
            )
        }

        if (uiState.quickPicks.isNotEmpty()) {
            item {
                QuickPicksSection(
                    tracks = uiState.quickPicks,
                    currentTrackId = uiState.playbackState.currentTrack?.id,
                    onTrackClick = { track ->
                        viewModel.playTrack(track, uiState.quickPicks)
                    }
                )
            }
        }

        if (uiState.playlists.isNotEmpty() || uiState.downloadedTracks.isNotEmpty()) {
            item {
                HomePlaylistsSection(
                    playlists = uiState.playlists,
                    offlineCount = uiState.downloadedTracks.size,
                    onOpenPlaylist = onOpenPlaylist,
                    onPlayOfflineMix = {
                        viewModel.playOfflineMix()
                        onShowMessage("Запущен офлайн-микс")
                    }
                )
            }
        }

        if (uiState.history.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Недавно прослушано",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    val recentTracks = remember(uiState.history) { uiState.history.take(10) }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(
                            items = recentTracks,
                            key = { "hist_${it.id}" },
                            contentType = { "recent_card" }
                        ) { track ->
                            RecentTrackCard(
                                track = track,
                                isPlaying = uiState.playbackState.currentTrack?.id == track.id,
                                onClick = { viewModel.playTrack(track, uiState.history) }
                            )
                        }
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Подборка по настроению",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val discoverTracks = (uiState.discoverState as? DiscoverUiState.Success)?.tracks
                        if (!discoverTracks.isNullOrEmpty()) {
                            TextButton(
                                onClick = { viewModel.playMix(shuffle = false) },
                                modifier = Modifier.testTag("discover_play_all_button")
                            ) {
                                Text("Всё")
                            }
                            IconButton(
                                onClick = { viewModel.playMix(shuffle = true) },
                                modifier = Modifier.testTag("discover_shuffle_button")
                            ) {
                                Icon(
                                    imageVector = SonoraIcons.Shuffle,
                                    contentDescription = "Перемешать подборку",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        IconButton(
                            onClick = viewModel::retryDiscover,
                            modifier = Modifier.testTag("discover_refresh_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Обновить подборку",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = uiState.moods,
                        key = { it },
                        contentType = { "mood_chip" }
                    ) { mood ->
                        val selected = mood == uiState.selectedMood
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.selectMood(mood) },
                            label = {
                                Text(
                                    text = mood,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                labelColor = MaterialTheme.colorScheme.onSurface
                            ),
                            border = null,
                            modifier = Modifier.testTag("mood_chip_$mood")
                        )
                    }
                }
            }
        }

        when (val discover = uiState.discoverState) {
            is DiscoverUiState.Loading -> {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Text(
                                text = "Настраиваем волну «${uiState.selectedMood}»...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            is DiscoverUiState.Error -> {
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = if (!uiState.isOnline) {
                                    "Нет подключения к интернету"
                                } else {
                                    discover.message
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = viewModel::retryDiscover,
                                    modifier = Modifier.testTag("retry_discover_button")
                                ) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Повторить")
                                }
                                if (uiState.downloadedTracks.isNotEmpty()) {
                                    OutlinedButton(onClick = viewModel::playOfflineMix) {
                                        Icon(SonoraIcons.DownloadDone, contentDescription = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Офлайн (${uiState.downloadedTracks.size})")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            is DiscoverUiState.Success -> {
                items(
                    items = discover.tracks,
                    key = { "disc_${it.id}" },
                    contentType = { "track_row" }
                ) { track ->
                    TrackRowItem(
                        track = track,
                        isCurrentTrack = uiState.playbackState.currentTrack?.id == track.id,
                        isFavorite = track.id in uiState.favoriteIds,
                        isDownloaded = track.id in uiState.downloadedIds,
                        isDownloading = track.id in uiState.downloadingIds,
                        onPlayClick = { viewModel.playTrack(track, discover.tracks) },
                        onFavoriteClick = { viewModel.toggleFavorite(track) },
                        onPlayNextClick = {
                            viewModel.playNext(track)
                            onShowMessage("«${track.title}» включится далее")
                        },
                        onStartRadioClick = { viewModel.startTrackRadio(track, onShowMessage) },
                        onDownloadClick = { viewModel.toggleDownload(track, onShowMessage) },
                        onAddToPlaylistClick = { trackForPlaylistDialog = track },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeTopHeader(
    greeting: String,
    account: YouTubeAccount?,
    onSearchClick: () -> Unit,
    onAccountClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = greeting,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (account != null) {
                Text(
                    text = account.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalIconButton(
                onClick = onSearchClick,
                modifier = Modifier.testTag("home_header_search_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = "Поиск музыки"
                )
            }

            Surface(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onAccountClick)
                    .testTag("home_account_avatar"),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                if (account?.avatarUrl?.isNotBlank() == true) {
                    AsyncImage(
                        model = account.avatarUrl,
                        contentDescription = "Профиль ${account.name}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = SonoraIcons.AccountCircle,
                            contentDescription = "Настройки и аккаунт",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroBannerCard(
    currentTrack: Track?,
    isPlaying: Boolean,
    lastHistoryTrack: Track?,
    hasDiscoverTracks: Boolean,
    onSearchClick: () -> Unit,
    onPrimaryActionClick: () -> Unit,
    onShuffleMixClick: () -> Unit
) {
    val spotlightTrack = currentTrack ?: lastHistoryTrack
    val subtitle = when {
        currentTrack != null -> "Сейчас в эфире: ${currentTrack.title} • ${currentTrack.artist}"
        lastHistoryTrack != null -> "Продолжить: ${lastHistoryTrack.title} • ${lastHistoryTrack.artist}"
        else -> "Музыка YouTube без рекламы и офлайн-плейлисты"
    }
    val primaryLabel = when {
        currentTrack != null && isPlaying -> "Пауза"
        spotlightTrack != null -> "Продолжить"
        else -> "Микс дня"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(192.dp)
            .clip(RoundedCornerShape(24.dp))
    ) {
        Image(
            painter = painterResource(R.drawable.img_hero_discover),
            contentDescription = "Баннер Sonora",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xE8090B0F),
                            Color(0xA6090B0F),
                            Color(0x3D090B0F)
                        )
                    )
                )
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = SonoraIcons.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (currentTrack != null) "СЕЙЧАС ИГРАЕТ" else "SONORA AUDIO",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = spotlightTrack?.title ?: "Sonora",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onPrimaryActionClick,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.testTag("hero_play_button")
                ) {
                    Icon(
                        imageVector = if (currentTrack != null && isPlaying) {
                            SonoraIcons.Pause
                        } else {
                            Icons.Rounded.PlayArrow
                        },
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(primaryLabel, fontWeight = FontWeight.Bold)
                }

                if (hasDiscoverTracks) {
                    Button(
                        onClick = onShuffleMixClick,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f),
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.testTag("hero_shuffle_button")
                    ) {
                        Icon(
                            imageVector = SonoraIcons.Shuffle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Микс")
                    }
                } else {
                    Button(
                        onClick = onSearchClick,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f),
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.testTag("hero_search_button")
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Поиск")
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickPicksSection(
    tracks: List<Track>,
    currentTrackId: String?,
    onTrackClick: (Track) -> Unit
) {
    val rows = remember(tracks) { tracks.chunked(2) }
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Быстрый выбор",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        rows.forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                pair.forEach { track ->
                    val isCurrent = track.id == currentTrackId
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(58.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onTrackClick(track) }
                            .testTag("quick_pick_${track.id}"),
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = track.thumbnailUrl,
                                contentDescription = track.title,
                                placeholder = painterResource(R.drawable.img_default_cover),
                                error = painterResource(R.drawable.img_default_cover),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(58.dp)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = track.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = track.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                if (pair.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HomePlaylistsSection(
    playlists: List<Playlist>,
    offlineCount: Int,
    onOpenPlaylist: (Long) -> Unit,
    onPlayOfflineMix: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Ваша коллекция",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (offlineCount > 0) {
                item(key = "offline_mix_card") {
                    Surface(
                        modifier = Modifier
                            .width(148.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(onClick = onPlayOfflineMix)
                            .testTag("home_offline_mix_card"),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = SonoraIcons.DownloadDone,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = "Офлайн-микс",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "$offlineCount скачано",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            items(
                items = playlists,
                key = { "pl_${it.id}" },
                contentType = { "playlist_card" }
            ) { playlist ->
                Surface(
                    modifier = Modifier
                        .width(148.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onOpenPlaylist(playlist.id) }
                        .testTag("home_playlist_${playlist.id}"),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AsyncImage(
                            model = playlist.coverUrl,
                            contentDescription = playlist.name,
                            placeholder = painterResource(R.drawable.img_default_cover),
                            error = painterResource(R.drawable.img_default_cover),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(88.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        )
                        Text(
                            text = playlist.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${playlist.trackCount} треков",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentTrackCard(
    track: Track,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(136.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(8.dp)
            .testTag("recent_card_${track.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(10.dp))
        ) {
            AsyncImage(
                model = track.thumbnailUrl,
                contentDescription = "${track.title} обложка",
                placeholder = painterResource(R.drawable.img_default_cover),
                error = painterResource(R.drawable.img_default_cover),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (isPlaying) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = SonoraIcons.GraphicEq,
                        contentDescription = "Сейчас играет",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
        Text(
            text = track.title,
            style = MaterialTheme.typography.labelLarge,
            color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = track.artist,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
