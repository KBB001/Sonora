package com.example.domain.repository

import com.example.domain.model.AudioQuality
import com.example.domain.model.Track
import com.example.domain.model.YouTubeArtistSummary
import com.example.domain.model.YouTubePlaylistSummary

interface YouTubeRepository {
    suspend fun searchTracks(query: String): Result<List<Track>>
    suspend fun searchPlaylists(query: String): Result<List<YouTubePlaylistSummary>>
    suspend fun searchArtists(query: String): Result<List<YouTubeArtistSummary>>
    suspend fun getArtistTracks(channelUrl: String): Result<List<Track>>
    suspend fun getSearchSuggestions(query: String): List<String>
    suspend fun getAudioStreamUrl(
        videoId: String,
        audioQuality: AudioQuality = AudioQuality.HIGH
    ): Result<String>
    suspend fun getRelatedTracks(videoId: String): Result<List<Track>>
    suspend fun getPlaylistTracks(playlistUrl: String): Result<List<Track>>
    suspend fun getPlaylistWithTitle(playlistUrl: String): Result<Pair<String, List<Track>>>
}
