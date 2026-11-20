package com.example.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class Playlist(
    val id: Long = 0L,
    val name: String,
    val description: String = "",
    val coverUrl: String? = null,
    val trackCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val youtubePlaylistId: String? = null
)
