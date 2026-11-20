package com.example.domain.model

enum class SearchFilter(val label: String) {
    TRACKS("Tracks"),
    PLAYLISTS("Playlists"),
    ARTISTS("Artists")
}

data class YouTubePlaylistSummary(
    val url: String,
    val name: String,
    val uploaderName: String,
    val trackCount: Long,
    val thumbnailUrl: String
)

data class YouTubeArtistSummary(
    val url: String,
    val name: String,
    val subscriberCount: Long,
    val thumbnailUrl: String
)
