package com.example.domain.model

enum class MoodCategory {
    ENERGETIC,
    WORKOUT_DRIVE,
    CHILL_FOCUS,
    NIGHT_SYNTH,
    ACOUSTIC_CALM,
    MELANCHOLIC,
    JAZZ_LOUNGE,
    GENERAL
}

enum class TempoTier {
    SLOW,
    MEDIUM,
    UPBEAT,
    HIGH_ENERGY
}

data class TrackAudioProfile(
    val mood: MoodCategory,
    val tempo: TempoTier,
    val energy: Float,
    val genreTokens: Set<String>,
    val normalizedArtist: String
)

enum class ImplicitSignalType {
    COMPLETE_PLAY,
    REPEAT_PLAY,
    QUICK_SKIP,
    PARTIAL_SKIP,
    FAVORITE_ADDED,
    FAVORITE_REMOVED,
    DOWNLOADED
}
