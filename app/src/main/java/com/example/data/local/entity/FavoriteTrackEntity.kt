package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.domain.model.Track

@Entity(tableName = "favorite_tracks")
data class FavoriteTrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val durationSeconds: Long,
    val thumbnailUrl: String,
    val addedAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): Track = Track(
        id = id,
        title = title,
        artist = artist,
        durationSeconds = durationSeconds,
        thumbnailUrl = thumbnailUrl
    )

    companion object {
        fun fromDomain(track: Track): FavoriteTrackEntity = FavoriteTrackEntity(
            id = track.id,
            title = track.title,
            artist = track.artist,
            durationSeconds = track.durationSeconds,
            thumbnailUrl = track.thumbnailUrl
        )
    }
}
