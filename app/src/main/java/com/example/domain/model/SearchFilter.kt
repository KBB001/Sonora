package com.example.domain.model

import androidx.compose.runtime.Immutable

enum class SearchFilter(val label: String) {
    TRACKS("Треки"),
    PLAYLISTS("Плейлисты"),
    ARTISTS("Артисты")
}

@Immutable
data class YouTubePlaylistSummary(
    val url: String,
    val name: String,
    val uploaderName: String,
    val trackCount: Long,
    val thumbnailUrl: String
)

@Immutable
data class YouTubeArtistSummary(
    val url: String,
    val name: String,
    val subscriberCount: Long,
    val thumbnailUrl: String
)
