package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.DownloadedTrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadedTrackDao {
    @Query("SELECT * FROM downloaded_tracks ORDER BY downloadedAt DESC")
    fun observeDownloads(): Flow<List<DownloadedTrackEntity>>

    @Query("SELECT id FROM downloaded_tracks")
    fun observeDownloadedIds(): Flow<List<String>>

    @Query("SELECT localFilePath FROM downloaded_tracks WHERE id = :trackId LIMIT 1")
    suspend fun getFilePath(trackId: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(entity: DownloadedTrackEntity)

    @Query("UPDATE downloaded_tracks SET localFilePath = :newPath WHERE id = :trackId")
    suspend fun updateFilePath(trackId: String, newPath: String)

    @Query("DELETE FROM downloaded_tracks WHERE id = :trackId")
    suspend fun deleteDownload(trackId: String)
}
