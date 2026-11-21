package com.example.data.repository

import com.example.data.local.dao.PlaylistDao
import com.example.data.remote.YouTubeMusicAccountApi
import com.example.domain.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeWriteThroughSync @Inject constructor(
    private val accountStore: YouTubeAccountStore,
    private val api: YouTubeMusicAccountApi,
    private val playlistDao: PlaylistDao
) {
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun onFavoriteChanged(trackId: String, isFavorite: Boolean) {
        if (!accountStore.isAutoSyncEnabledSnapshot()) return
        val account = accountStore.getActiveAccountSnapshot() ?: return
        syncScope.launch {
            runCatching {
                api.setLikeStatus(account, trackId, isFavorite)
            }
        }
    }

    fun onPlaylistCreated(
        localPlaylistId: Long,
        name: String,
        description: String,
        initialTracks: List<Track>
    ) {
        if (!accountStore.isAutoSyncEnabledSnapshot()) return
        val account = accountStore.getActiveAccountSnapshot() ?: return
        syncScope.launch {
            runCatching {
                api.createPlaylist(
                    account = account,
                    title = name,
                    description = description,
                    videoIds = initialTracks.map { it.id }
                ).onSuccess { remotePlaylistId ->
                    playlistDao.updateYoutubePlaylistId(localPlaylistId, remotePlaylistId)
                }
            }
        }
    }

    fun onTrackAddedToPlaylist(localPlaylistId: Long, track: Track) {
        if (!accountStore.isAutoSyncEnabledSnapshot()) return
        val account = accountStore.getActiveAccountSnapshot() ?: return
        syncScope.launch {
            runCatching {
                val remoteId = playlistDao.getYoutubePlaylistId(localPlaylistId)
                if (!remoteId.isNullOrBlank()) {
                    api.addTracksToPlaylist(account, remoteId, listOf(track.id))
                }
            }
        }
    }

    fun onTrackRemovedFromPlaylist(localPlaylistId: Long, trackId: String) {
        if (!accountStore.isAutoSyncEnabledSnapshot()) return
        val account = accountStore.getActiveAccountSnapshot() ?: return
        syncScope.launch {
            runCatching {
                val remoteId = playlistDao.getYoutubePlaylistId(localPlaylistId)
                if (!remoteId.isNullOrBlank()) {
                    api.removeTrackFromPlaylist(account, remoteId, trackId)
                }
            }
        }
    }
}
