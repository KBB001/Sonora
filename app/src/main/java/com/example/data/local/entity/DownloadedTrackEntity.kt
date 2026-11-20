package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.domain.model.Track

@Entity(tableName = "downloaded_tracks")
data class DownloadedTrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val durationSeconds: Long,
    val thumbnailUrl: String,
    val localFilePath: String,
    val fileSizeBytes: Long,
    val downloadedAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): Track = Track(
        id = id,
        title = title,
        artist = artist,
        durationSeconds = durationSeconds,
        thumbnailUrl = thumbnailUrl,
        localFilePath = localFilePath
    )
}
