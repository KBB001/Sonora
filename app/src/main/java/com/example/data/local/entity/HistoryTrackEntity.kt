package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.domain.model.Track

@Entity(tableName = "history_tracks")
data class HistoryTrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val durationSeconds: Long,
    val thumbnailUrl: String,
    val playedAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): Track = Track(
        id = id,
        title = title,
        artist = artist,
        durationSeconds = durationSeconds,
        thumbnailUrl = thumbnailUrl
    )

    companion object {
        fun fromDomain(track: Track): HistoryTrackEntity = HistoryTrackEntity(
            id = track.id,
            title = track.title,
            artist = track.artist,
            durationSeconds = track.durationSeconds,
            thumbnailUrl = track.thumbnailUrl
        )
    }
}
