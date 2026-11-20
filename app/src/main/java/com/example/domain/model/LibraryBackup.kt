package com.example.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class BackupPlaylist(
    val name: String,
    val description: String = "",
    val tracks: List<Track> = emptyList()
)

@Serializable
data class LibraryBackup(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val favorites: List<Track> = emptyList(),
    val history: List<Track> = emptyList(),
    val playlists: List<BackupPlaylist> = emptyList()
)
