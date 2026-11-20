package com.example.data.repository

import com.example.data.local.dao.DownloadedTrackDao
import com.example.data.local.dao.FavoriteDao
import com.example.data.local.dao.HistoryDao
import com.example.data.local.dao.PlaylistDao
import com.example.data.local.dao.PlaylistSummaryTuple
import com.example.data.local.dao.SearchHistoryDao
import com.example.data.local.entity.DownloadedTrackEntity
import com.example.data.local.entity.FavoriteTrackEntity
import com.example.data.local.entity.HistoryTrackEntity
import com.example.data.local.entity.PlaylistEntity
import com.example.data.local.entity.PlaylistTrackEntity
import com.example.data.local.entity.SearchHistoryEntity
import com.example.domain.model.BackupPlaylist
import com.example.domain.model.LibraryBackup
import com.example.domain.model.Playlist
import com.example.domain.model.Track
import com.example.domain.repository.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepositoryImpl @Inject constructor(
    private val favoriteDao: FavoriteDao,
    private val historyDao: HistoryDao,
    private val playlistDao: PlaylistDao,
    private val downloadedTrackDao: DownloadedTrackDao,
    private val searchHistoryDao: SearchHistoryDao
) : LibraryRepository {

    private val jsonCodec = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    override fun observeFavorites(): Flow<List<Track>> =
        favoriteDao.observeFavorites().map { entities -> entities.map { it.toDomain() } }

    override fun observeFavoriteIds(): Flow<Set<String>> =
        favoriteDao.observeFavoriteIds().map { it.toSet() }

    override fun observeHistory(): Flow<List<Track>> =
        historyDao.observeHistory().map { entities -> entities.map { it.toDomain() } }

    override fun observePlaylists(): Flow<List<Playlist>> =
        playlistDao.observePlaylistsWithCounts().map { list -> list.map { it.toDomain() } }

    override fun observePlaylistById(playlistId: Long): Flow<Playlist?> =
        playlistDao.observePlaylistById(playlistId).map { it?.toDomain() }

    override fun observePlaylistTracks(playlistId: Long): Flow<List<Track>> =
        playlistDao.observePlaylistTracks(playlistId).map { entities -> entities.map { it.toDomain() } }

    override fun observeDownloadedTracks(): Flow<List<Track>> =
        downloadedTrackDao.observeDownloads().map { list ->
            list.filter { File(it.localFilePath).exists() }.map { it.toDomain() }
        }

    override fun observeDownloadedIds(): Flow<Set<String>> =
        downloadedTrackDao.observeDownloadedIds().map { it.toSet() }

    override suspend fun getDownloadedFilePath(trackId: String): String? {
        val path = downloadedTrackDao.getFilePath(trackId) ?: return null
        return if (File(path).exists()) path else {
            downloadedTrackDao.deleteDownload(trackId)
            null
        }
    }

    override suspend fun saveDownloadedTrack(
        track: Track,
        filePath: String,
        fileSizeBytes: Long
    ) {
        downloadedTrackDao.insertDownload(
            DownloadedTrackEntity(
                id = track.id,
                title = track.title,
                artist = track.artist,
                durationSeconds = track.durationSeconds,
                thumbnailUrl = track.thumbnailUrl,
                localFilePath = filePath,
                fileSizeBytes = fileSizeBytes
            )
        )
    }

    override suspend fun deleteDownloadedTrack(trackId: String) {
        val path = downloadedTrackDao.getFilePath(trackId)
        if (!path.isNullOrBlank()) {
            runCatching { File(path).delete() }
        }
        downloadedTrackDao.deleteDownload(trackId)
    }

    override fun observeSearchHistory(): Flow<List<String>> =
        searchHistoryDao.observeRecentQueries()

    override suspend fun recordSearchQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) {
            searchHistoryDao.upsertQuery(SearchHistoryEntity(query = trimmed))
        }
    }

    override suspend fun deleteSearchQuery(query: String) {
        searchHistoryDao.deleteQuery(query)
    }

    override suspend fun clearSearchHistory() {
        searchHistoryDao.clearAll()
    }

    override suspend fun toggleFavorite(track: Track): Boolean {
        val currentlyFavorite = favoriteDao.isFavorite(track.id)
        return if (currentlyFavorite) {
            favoriteDao.deleteFavorite(track.id)
            false
        } else {
            favoriteDao.insertFavorite(FavoriteTrackEntity.fromDomain(track))
            true
        }
    }

    override suspend fun recordPlayback(track: Track) {
        historyDao.upsertHistory(HistoryTrackEntity.fromDomain(track))
    }

    override suspend fun clearHistory() {
        historyDao.clearHistory()
    }

    override suspend fun createPlaylist(
        name: String,
        description: String,
        initialTracks: List<Track>
    ): Long {
        val coverUrl = initialTracks.firstOrNull()?.thumbnailUrl
        val playlistId = playlistDao.insertPlaylist(
            PlaylistEntity(
                name = name.trim().ifBlank { "Untitled Playlist" },
                description = description.trim(),
                coverUrl = coverUrl
            )
        )
        if (initialTracks.isNotEmpty()) {
            val baseTime = System.currentTimeMillis()
            val trackEntities = initialTracks.mapIndexed { index, track ->
                PlaylistTrackEntity(
                    playlistId = playlistId,
                    trackId = track.id,
                    title = track.title,
                    artist = track.artist,
                    durationSeconds = track.durationSeconds,
                    thumbnailUrl = track.thumbnailUrl,
                    addedAt = baseTime + index
                )
            }
            playlistDao.insertPlaylistTracks(trackEntities)
        }
        return playlistId
    }

    override suspend fun deletePlaylist(playlistId: Long) {
        playlistDao.deletePlaylist(playlistId)
    }

    override suspend fun addTrackToPlaylist(playlistId: Long, track: Track) {
        playlistDao.insertPlaylistTrack(PlaylistTrackEntity.fromDomain(playlistId, track))
    }

    override suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: String) {
        playlistDao.deleteTrackFromPlaylist(playlistId, trackId)
    }

    override suspend fun exportBackupJson(): String = withContext(Dispatchers.IO) {
        val favorites = observeFavorites().first()
        val history = observeHistory().first()
        val playlists = observePlaylists().first().map { playlist ->
            val tracks = observePlaylistTracks(playlist.id).first()
            BackupPlaylist(
                name = playlist.name,
                description = playlist.description,
                tracks = tracks
            )
        }
        val backup = LibraryBackup(
            favorites = favorites,
            history = history,
            playlists = playlists
        )
        jsonCodec.encodeToString(backup)
    }

    override suspend fun importBackupJson(json: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val backup = jsonCodec.decodeFromString<LibraryBackup>(json)
            var restoredCount = 0
            backup.favorites.reversed().forEach { track ->
                favoriteDao.insertFavorite(FavoriteTrackEntity.fromDomain(track))
                restoredCount++
            }
            backup.history.reversed().forEach { track ->
                historyDao.upsertHistory(HistoryTrackEntity.fromDomain(track))
            }
            backup.playlists.forEach { playlist ->
                createPlaylist(
                    name = playlist.name,
                    description = playlist.description,
                    initialTracks = playlist.tracks
                )
                restoredCount += playlist.tracks.size
            }
            restoredCount
        }
    }

    private fun PlaylistSummaryTuple.toDomain(): Playlist = Playlist(
        id = id,
        name = name,
        description = description,
        coverUrl = coverUrl,
        trackCount = trackCount,
        createdAt = createdAt
    )
}
