package com.example.domain.model

import androidx.compose.runtime.Immutable

enum class RepeatMode {
    OFF,
    ALL,
    ONE
}

@Immutable
data class PlaybackProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
)

@Immutable
data class PlaybackState(
    val currentTrack: Track? = null,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isLoadingStream: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val sleepTimerRemainingMs: Long? = null,
    val audioSessionId: Int = 0,
    val errorMessage: String? = null
)
