package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "track_affinities",
    indices = [
        Index(value = ["normalizedArtist"]),
        Index(value = ["moodName"])
    ]
)
data class TrackAffinityEntity(
    @PrimaryKey val trackId: String,
    val normalizedArtist: String,
    val moodName: String,
    val tempoName: String,
    val completedCount: Int = 0,
    val repeatCount: Int = 0,
    val quickSkipCount: Int = 0,
    val partialSkipCount: Int = 0,
    val affinityScore: Float = 0f,
    val lastInteractedAt: Long = System.currentTimeMillis()
)
