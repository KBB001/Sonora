package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.TrackAffinityEntity

@Dao
interface TrackSignalDao {
    @Query("SELECT * FROM track_affinities WHERE trackId = :trackId LIMIT 1")
    suspend fun getAffinity(trackId: String): TrackAffinityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAffinity(entity: TrackAffinityEntity)

    @Query("SELECT * FROM track_affinities ORDER BY lastInteractedAt DESC LIMIT 1000")
    suspend fun getAllAffinities(): List<TrackAffinityEntity>

    @Query("DELETE FROM track_affinities")
    suspend fun clearAll()
}
