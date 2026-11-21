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
    private val searchHistoryDao: SearchHistoryDao,
    private val writeThroughSync: YouTubeWriteThroughSync? = null
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
            writeThroughSync?.onFavoriteChanged(track.id, isFavorite = false)
            false
        } else {
            favoriteDao.insertFavorite(FavoriteTrackEntity.fromDomain(track))
            writeThroughSync?.onFavoriteChanged(track.id, isFavorite = true)
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
        initialTracks: List<Track>,
        youtubePlaylistId: String?
    ): Long {
        val coverUrl = initialTracks.firstOrNull()?.thumbnailUrl
        val cleanName = name.trim().ifBlank { "Untitled Playlist" }
        val cleanDesc = description.trim()
        val playlistId = playlistDao.insertPlaylist(
            PlaylistEntity(
                name = cleanName,
                description = cleanDesc,
                coverUrl = coverUrl,
                youtubePlaylistId = youtubePlaylistId
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
        if (youtubePlaylistId.isNullOrBlank()) {
            writeThroughSync?.onPlaylistCreated(playlistId, cleanName, cleanDesc, initialTracks)
        }
        return playlistId
    }

    override suspend fun deletePlaylist(playlistId: Long) {
        playlistDao.deletePlaylist(playlistId)
    }

    override suspend fun updatePlaylistDetails(
        playlistId: Long,
        name: String,
        description: String
    ) {
        val cleanName = name.trim().take(120)
        if (cleanName.isBlank()) return
        val cleanDesc = description.trim().take(500)
        playlistDao.updatePlaylistDetails(playlistId, cleanName, cleanDesc)
    }

    override suspend fun addTrackToPlaylist(playlistId: Long, track: Track) {
        playlistDao.insertPlaylistTrack(PlaylistTrackEntity.fromDomain(playlistId, track))
        writeThroughSync?.onTrackAddedToPlaylist(playlistId, track)
    }

    override suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: String) {
        playlistDao.deleteTrackFromPlaylist(playlistId, trackId)
        writeThroughSync?.onTrackRemovedFromPlaylist(playlistId, trackId)
    }

    override suspend fun mergeFavoriteTracks(tracks: List<Track>): Int = withContext(Dispatchers.IO) {
        if (tracks.isEmpty()) return@withContext 0
        val existingIds = favoriteDao.observeFavoriteIds().first().toSet()
        var addedCount = 0
        tracks.reversed().forEach { track ->
            if (track.id !in existingIds) {
                favoriteDao.insertFavorite(FavoriteTrackEntity.fromDomain(track))
                addedCount++
            }
        }
        addedCount
    }

    override suspend fun mergeHistoryTracks(tracks: List<Track>): Int = withContext(Dispatchers.IO) {
        if (tracks.isEmpty()) return@withContext 0
        val existingIds = historyDao.observeHistory().first().map { it.id }.toSet()
        var addedCount = 0
        tracks.reversed().forEach { track ->
            if (track.id !in existingIds) {
                historyDao.upsertHistory(HistoryTrackEntity.fromDomain(track))
                addedCount++
            }
        }
        addedCount
    }

    override suspend fun mergePlaylist(
        name: String,
        description: String,
        youtubePlaylistId: String?,
        tracks: List<Track>
    ): Pair<Long, Int> = withContext(Dispatchers.IO) {
        val existingByRemote = youtubePlaylistId?.takeIf { it.isNotBlank() }?.let {
            playlistDao.findByYoutubePlaylistId(it)
        }
        val existing = existingByRemote ?: playlistDao.findByNameIgnoreCase(name)

        if (existing == null) {
            val newId = createPlaylist(
                name = name,
                description = description,
                initialTracks = tracks,
                youtubePlaylistId = youtubePlaylistId
            )
            return@withContext newId to tracks.size
        }

        if (!youtubePlaylistId.isNullOrBlank() && existing.youtubePlaylistId.isNullOrBlank()) {
            playlistDao.updateYoutubePlaylistId(existing.id, youtubePlaylistId)
        }

        val existingTrackIds = playlistDao.getPlaylistTrackIds(existing.id).toSet()
        val missingTracks = tracks.filter { it.id !in existingTrackIds }
        if (missingTracks.isNotEmpty()) {
            val baseTime = System.currentTimeMillis()
            val entities = missingTracks.mapIndexed { index, track ->
                PlaylistTrackEntity(
                    playlistId = existing.id,
                    trackId = track.id,
                    title = track.title,
                    artist = track.artist,
                    durationSeconds = track.durationSeconds,
                    thumbnailUrl = track.thumbnailUrl,
                    addedAt = baseTime + index
                )
            }
            playlistDao.insertPlaylistTracks(entities)
        }
        existing.id to missingTracks.size
    }

    override suspend fun updatePlaylistYoutubeId(playlistId: Long, youtubePlaylistId: String) {
        playlistDao.updateYoutubePlaylistId(playlistId, youtubePlaylistId)
    }

    override suspend fun getAllPlaylistsWithTracks(): List<Pair<Playlist, List<Track>>> =
        withContext(Dispatchers.IO) {
            observePlaylists().first().map { playlist ->
                playlist to observePlaylistTracks(playlist.id).first()
            }
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
            require(json.isNotBlank() && json.length <= MAX_BACKUP_JSON_CHARS) {
                "Файл резервной копии пустой или слишком большой"
            }
            val backup = jsonCodec.decodeFromString<LibraryBackup>(json)
            var restoredCount = 0
            backup.favorites
                .filter { it.id.isNotBlank() }
                .take(MAX_IMPORT_TRACKS)
                .reversed()
                .forEach { track ->
                    favoriteDao.insertFavorite(FavoriteTrackEntity.fromDomain(sanitizeTrack(track)))
                    restoredCount++
                }
            backup.history
                .filter { it.id.isNotBlank() }
                .take(MAX_IMPORT_TRACKS)
                .reversed()
                .forEach { track ->
                    historyDao.upsertHistory(HistoryTrackEntity.fromDomain(sanitizeTrack(track)))
                }
            backup.playlists
                .take(MAX_IMPORT_PLAYLISTS)
                .forEach { playlist ->
                    val validTracks = playlist.tracks
                        .filter { it.id.isNotBlank() }
                        .take(MAX_IMPORT_TRACKS)
                        .map(::sanitizeTrack)
                    createPlaylist(
                        name = playlist.name.trim().take(120).ifBlank { "Untitled Playlist" },
                        description = playlist.description.trim().take(500),
                        initialTracks = validTracks
                    )
                    restoredCount += validTracks.size
                }
            restoredCount
        }
    }

    private fun sanitizeTrack(track: Track): Track = track.copy(
        id = track.id.trim().take(64),
        title = track.title.trim().take(240).ifBlank { "Untitled Track" },
        artist = track.artist.trim().take(200).ifBlank { "Unknown Artist" },
        durationSeconds = track.durationSeconds.coerceAtLeast(0L),
        thumbnailUrl = track.thumbnailUrl
            .trim()
            .takeIf { it.startsWith("https://", ignoreCase = true) }
            .orEmpty()
    )

    private companion object {
        const val MAX_BACKUP_JSON_CHARS = 10 * 1024 * 1024
        const val MAX_IMPORT_TRACKS = 5_000
        const val MAX_IMPORT_PLAYLISTS = 200
    }

    private fun PlaylistSummaryTuple.toDomain(): Playlist = Playlist(
        id = id,
        name = name,
        description = description,
        coverUrl = coverUrl,
        trackCount = trackCount,
        createdAt = createdAt,
        youtubePlaylistId = youtubePlaylistId
    )
}
