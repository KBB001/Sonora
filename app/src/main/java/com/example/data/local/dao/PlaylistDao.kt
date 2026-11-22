package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.PlaylistEntity
import com.example.data.local.entity.PlaylistTrackEntity
import kotlinx.coroutines.flow.Flow

data class PlaylistSummaryTuple(
    val id: Long,
    val name: String,
    val description: String,
    val coverUrl: String?,
    val createdAt: Long,
    val trackCount: Int,
    val youtubePlaylistId: String?
)

@Dao
interface PlaylistDao {
    @Query(
        """
        SELECT p.id, p.name, p.description,
               COALESCE(p.coverUrl, (SELECT pt.thumbnailUrl FROM playlist_tracks pt WHERE pt.playlistId = p.id ORDER BY pt.addedAt ASC LIMIT 1)) AS coverUrl,
               p.createdAt,
               (SELECT COUNT(*) FROM playlist_tracks pt WHERE pt.playlistId = p.id) AS trackCount,
               p.youtubePlaylistId
        FROM playlists p
        ORDER BY p.createdAt DESC
        """
    )
    fun observePlaylistsWithCounts(): Flow<List<PlaylistSummaryTuple>>

    @Query(
        """
        SELECT p.id, p.name, p.description,
               COALESCE(p.coverUrl, (SELECT pt.thumbnailUrl FROM playlist_tracks pt WHERE pt.playlistId = p.id ORDER BY pt.addedAt ASC LIMIT 1)) AS coverUrl,
               p.createdAt,
               (SELECT COUNT(*) FROM playlist_tracks pt WHERE pt.playlistId = p.id) AS trackCount,
               p.youtubePlaylistId
        FROM playlists p
        WHERE p.id = :playlistId
        """
    )
    fun observePlaylistById(playlistId: Long): Flow<PlaylistSummaryTuple?>

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY addedAt ASC")
    fun observePlaylistTracks(playlistId: Long): Flow<List<PlaylistTrackEntity>>

    @Query("SELECT trackId FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun getPlaylistTrackIds(playlistId: Long): List<String>

    @Query("SELECT * FROM playlists WHERE youtubePlaylistId = :youtubePlaylistId LIMIT 1")
    suspend fun findByYoutubePlaylistId(youtubePlaylistId: String): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE LOWER(TRIM(name)) = LOWER(TRIM(:name)) LIMIT 1")
    suspend fun findByNameIgnoreCase(name: String): PlaylistEntity?

    @Query("SELECT * FROM playlists")
    suspend fun getAllPlaylists(): List<PlaylistEntity>

    @Query("SELECT youtubePlaylistId FROM playlists WHERE id = :playlistId")
    suspend fun getYoutubePlaylistId(playlistId: Long): String?

    @Query("UPDATE playlists SET youtubePlaylistId = :youtubePlaylistId WHERE id = :playlistId")
    suspend fun updateYoutubePlaylistId(playlistId: Long, youtubePlaylistId: String)

    @Query("UPDATE playlists SET name = :name, description = :description WHERE id = :playlistId")
    suspend fun updatePlaylistDetails(playlistId: Long, name: String, description: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistTrack(track: PlaylistTrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistTracks(tracks: List<PlaylistTrackEntity>)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun deleteTrackFromPlaylist(playlistId: Long, trackId: String)
}
