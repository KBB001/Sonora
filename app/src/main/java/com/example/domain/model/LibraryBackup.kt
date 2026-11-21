package com.example.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class BackupPlaylist(
    val name: String,
    val description: String = "",
    val tracks: List<Track> = emptyList(),
    val youtubePlaylistId: String? = null,
    val createdAt: Long = 0L
)

@Serializable
data class BackupSettings(
    val audioQuality: AudioQuality = AudioQuality.HIGH,
    val themeMode: AppThemeMode = AppThemeMode.DARK,
    val dynamicColorEnabled: Boolean = false,
    val skipSilenceEnabled: Boolean = false,
    val autoplayEnabled: Boolean = true
)

@Serializable
data class PersistedPlaybackState(
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val positionMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val playbackSpeed: Float = 1.0f
)

@Serializable
data class PersistedEqualizerState(
    val isEnabled: Boolean = false,
    val selectedPresetIndex: Int = 0,
    val bandLevels: List<Short> = emptyList(),
    val customBandsApplied: Boolean = false
)

@Serializable
data class LibraryBackup(
    val version: Int = 2,
    val exportedAt: Long = System.currentTimeMillis(),
    val favorites: List<Track> = emptyList(),
    val history: List<Track> = emptyList(),
    val playlists: List<BackupPlaylist> = emptyList(),
    val searchHistory: List<String> = emptyList(),
    val settings: BackupSettings? = null,
    val playback: PersistedPlaybackState? = null,
    val equalizer: PersistedEqualizerState? = null
)

data class LibraryImportResult(
    val tracksRestored: Int = 0,
    val playlistsRestored: Int = 0,
    val searchQueriesRestored: Int = 0
)
