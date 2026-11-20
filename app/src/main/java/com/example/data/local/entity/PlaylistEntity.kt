package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val description: String = "",
    val coverUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val youtubePlaylistId: String? = null
)
