package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.HistoryTrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history_tracks ORDER BY playedAt DESC LIMIT 50")
    fun observeHistory(): Flow<List<HistoryTrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistory(track: HistoryTrackEntity)

    @Query("DELETE FROM history_tracks")
    suspend fun clearHistory()
}
