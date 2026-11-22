package com.example.data.repository

import android.content.Context
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
import com.example.domain.model.ImplicitSignalType
import com.example.domain.model.LibraryBackup
import com.example.domain.model.LibraryImportResult
import com.example.domain.model.Playlist
import com.example.domain.model.Track
import com.example.domain.recommendation.TasteSignalTracker
import com.example.domain.repository.LibraryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
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
    private val writeThroughSync: YouTubeWriteThroughSync? = null,
    @ApplicationContext private val context: Context? = null,
    private val tasteSignalTracker: TasteSignalTracker? = null
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
            list.mapNotNull { entity ->
                val resolved = resolveOfflineFile(entity.id, entity.localFilePath) ?: return@mapNotNull null
                entity.copy(localFilePath = resolved.absolutePath).toDomain()
            }
        }

    override fun observeDownloadedIds(): Flow<Set<String>> =
        downloadedTrackDao.observeDownloadedIds().map { it.toSet() }

    override suspend fun getDownloadedFilePath(trackId: String): String? {
        val storedPath = downloadedTrackDao.getFilePath(trackId) ?: return null
        val resolved = resolveOfflineFile(trackId, storedPath)
        return if (resolved != null) {
            val resolvedPath = resolved.absolutePath
            if (resolvedPath != storedPath) {
                downloadedTrackDao.updateFilePath(trackId, resolvedPath)
            }
            resolvedPath
        } else {
            downloadedTrackDao.deleteDownload(trackId)
            null
        }
    }

    private fun resolveOfflineFile(trackId: String, storedPath: String?): File? {
        if (!storedPath.isNullOrBlank()) {
            val direct = File(storedPath)
            if (direct.exists() && direct.length() > 0L) {
                return direct
            }
        }
        val baseDir = context?.let { File(it.filesDir, "offline_tracks") } ?: return null
        if (!baseDir.exists()) return null
        val baseCanonical = runCatching { baseDir.canonicalPath + File.separator }.getOrNull() ?: return null

        val safeTrackId = trackId.replace(Regex("[^A-Za-z0-9_-]"), "_")
            .ifBlank { "track_${trackId.hashCode()}" }
        val candidates = buildList {
            add(File(baseDir, "$safeTrackId.m4a"))
            if (!storedPath.isNullOrBlank()) {
                val fileName = File(storedPath).name
                if (fileName.isNotBlank()) add(File(baseDir, fileName))
            }
        }
        return candidates.firstOrNull { candidate ->
            runCatching {
                candidate.canonicalPath.startsWith(baseCanonical) &&
                    candidate.exists() &&
                    candidate.length() > 0L
            }.getOrDefault(false)
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
        tasteSignalTracker?.recordSignalAsync(track, ImplicitSignalType.DOWNLOADED)
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
            tasteSignalTracker?.recordSignalAsync(track, ImplicitSignalType.FAVORITE_REMOVED)
            false
        } else {
            favoriteDao.insertFavorite(FavoriteTrackEntity.fromDomain(track))
            writeThroughSync?.onFavoriteChanged(track.id, isFavorite = true)
            tasteSignalTracker?.recordSignalAsync(track, ImplicitSignalType.FAVORITE_ADDED)
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
        val cleanName = name.trim().ifBlank { "Плейлист без названия" }
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
        val cleanName = name.trim().ifBlank { "Плейлист без названия" }
        val existingByRemote = youtubePlaylistId?.takeIf { it.isNotBlank() }?.let {
            playlistDao.findByYoutubePlaylistId(it)
        }
        val existing = existingByRemote
            ?: playlistDao.findByNameIgnoreCase(cleanName)
            ?: playlistDao.getAllPlaylists().firstOrNull {
                it.name.trim().equals(cleanName, ignoreCase = true)
            }

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

    override suspend fun exportLibraryBackup(): LibraryBackup = withContext(Dispatchers.IO) {
        val favorites = observeFavorites().first().map { it.copy(streamUrl = null) }
        val history = observeHistory().first().map { it.copy(streamUrl = null) }
        val playlists = observePlaylists().first().map { playlist ->
            val tracks = observePlaylistTracks(playlist.id).first().map { it.copy(streamUrl = null) }
            BackupPlaylist(
                name = playlist.name,
                description = playlist.description,
                tracks = tracks,
                youtubePlaylistId = playlist.youtubePlaylistId,
                createdAt = playlist.createdAt
            )
        }
        val searchQueries = observeSearchHistory().first()
        LibraryBackup(
            version = 2,
            favorites = favorites,
            history = history,
            playlists = playlists,
            searchHistory = searchQueries
        )
    }

    override suspend fun importLibraryBackup(backup: LibraryBackup): LibraryImportResult =
        withContext(Dispatchers.IO) {
            val validFavorites = backup.favorites
                .filter { it.id.isNotBlank() }
                .take(MAX_IMPORT_TRACKS)
                .map(::sanitizeTrack)
            val addedFavorites = mergeFavoriteTracks(validFavorites)

            val validHistory = backup.history
                .filter { it.id.isNotBlank() }
                .take(MAX_IMPORT_TRACKS)
                .map(::sanitizeTrack)
            val addedHistory = mergeHistoryTracks(validHistory)

            var playlistsRestored = 0
            var addedPlaylistTracks = 0
            backup.playlists
                .take(MAX_IMPORT_PLAYLISTS)
                .forEach { playlist ->
                    val cleanName = playlist.name.trim().take(120).ifBlank { "Плейлист без названия" }
                    val cleanDesc = playlist.description.trim().take(500)
                    val remoteId = playlist.youtubePlaylistId?.trim()?.take(64)?.takeIf { it.isNotBlank() }
                    val validTracks = playlist.tracks
                        .filter { it.id.isNotBlank() }
                        .take(MAX_IMPORT_TRACKS)
                        .map(::sanitizeTrack)
                    val (_, added) = mergePlaylist(
                        name = cleanName,
                        description = cleanDesc,
                        youtubePlaylistId = remoteId,
                        tracks = validTracks
                    )
                    playlistsRestored++
                    addedPlaylistTracks += added
                }

            val existingQueries = observeSearchHistory().first().toSet()
            var addedQueries = 0
            backup.searchHistory
                .map { it.trim().take(120) }
                .filter { it.isNotBlank() }
                .distinct()
                .take(MAX_IMPORT_SEARCH_QUERIES)
                .reversed()
                .forEach { query ->
                    if (query !in existingQueries) {
                        addedQueries++
                    }
                    searchHistoryDao.upsertQuery(SearchHistoryEntity(query = query))
                }

            LibraryImportResult(
                tracksRestored = addedFavorites + addedPlaylistTracks + addedHistory,
                playlistsRestored = playlistsRestored,
                searchQueriesRestored = addedQueries
            )
        }

    override suspend fun exportBackupJson(): String = withContext(Dispatchers.IO) {
        jsonCodec.encodeToString(exportLibraryBackup())
    }

    override suspend fun importBackupJson(json: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            require(json.isNotBlank() && json.length <= MAX_BACKUP_JSON_CHARS) {
                "Файл резервной копии пустой или слишком большой"
            }
            val backup = jsonCodec.decodeFromString<LibraryBackup>(json)
            importLibraryBackup(backup).tracksRestored
        }
    }

    private fun sanitizeTrack(track: Track): Track = track.copy(
        id = track.id.trim().take(64),
        title = track.title.trim().take(240).ifBlank { "Без названия" },
        artist = track.artist.trim().take(200).ifBlank { "Неизвестный исполнитель" },
        durationSeconds = track.durationSeconds.coerceAtLeast(0L),
        thumbnailUrl = track.thumbnailUrl
            .trim()
            .takeIf { it.startsWith("https://", ignoreCase = true) }
            .orEmpty(),
        streamUrl = null
    )

    private companion object {
        const val MAX_BACKUP_JSON_CHARS = 10 * 1024 * 1024
        const val MAX_IMPORT_TRACKS = 5_000
        const val MAX_IMPORT_PLAYLISTS = 200
        const val MAX_IMPORT_SEARCH_QUERIES = 50
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
