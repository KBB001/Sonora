package com.example.domain.repository

import com.example.domain.model.Playlist
import com.example.domain.model.Track
import kotlinx.coroutines.flow.Flow

interface LibraryRepository {
    fun observeFavorites(): Flow<List<Track>>
    fun observeFavoriteIds(): Flow<Set<String>>
    fun observeHistory(): Flow<List<Track>>
    fun observePlaylists(): Flow<List<Playlist>>
    fun observePlaylistById(playlistId: Long): Flow<Playlist?>
    fun observePlaylistTracks(playlistId: Long): Flow<List<Track>>

    fun observeDownloadedTracks(): Flow<List<Track>>
    fun observeDownloadedIds(): Flow<Set<String>>
    suspend fun getDownloadedFilePath(trackId: String): String?
    suspend fun saveDownloadedTrack(track: Track, filePath: String, fileSizeBytes: Long)
    suspend fun deleteDownloadedTrack(trackId: String)

    fun observeSearchHistory(): Flow<List<String>>
    suspend fun recordSearchQuery(query: String)
    suspend fun deleteSearchQuery(query: String)
    suspend fun clearSearchHistory()

    suspend fun toggleFavorite(track: Track): Boolean
    suspend fun recordPlayback(track: Track)
    suspend fun clearHistory()
    suspend fun createPlaylist(
        name: String,
        description: String = "",
        initialTracks: List<Track> = emptyList(),
        youtubePlaylistId: String? = null
    ): Long
    suspend fun deletePlaylist(playlistId: Long)
    suspend fun updatePlaylistDetails(playlistId: Long, name: String, description: String)
    suspend fun addTrackToPlaylist(playlistId: Long, track: Track)
    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: String)

    suspend fun mergeFavoriteTracks(tracks: List<Track>): Int
    suspend fun mergeHistoryTracks(tracks: List<Track>): Int
    suspend fun mergePlaylist(
        name: String,
        description: String = "",
        youtubePlaylistId: String? = null,
        tracks: List<Track>
    ): Pair<Long, Int>
    suspend fun updatePlaylistYoutubeId(playlistId: Long, youtubePlaylistId: String)
    suspend fun getAllPlaylistsWithTracks(): List<Pair<Playlist, List<Track>>>

    suspend fun exportBackupJson(): String
    suspend fun importBackupJson(json: String): Result<Int>
}
