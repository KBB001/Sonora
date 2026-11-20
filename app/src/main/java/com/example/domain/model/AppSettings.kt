package com.example.domain.model

import androidx.compose.runtime.Immutable

enum class AudioQuality(val label: String, val maxBitrateKbps: Int) {
    DATA_SAVER("Data Saver (~64 kbps)", 72),
    BALANCED("Balanced (~128 kbps)", 140),
    HIGH("High Quality (Best)", Int.MAX_VALUE)
}

enum class AppThemeMode(val label: String) {
    DARK("Dark (Spotify / YT Music)"),
    SYSTEM("Follow System"),
    LIGHT("Light")
}

@Immutable
data class AppSettings(
    val audioQuality: AudioQuality = AudioQuality.HIGH,
    val themeMode: AppThemeMode = AppThemeMode.DARK,
    val dynamicColorEnabled: Boolean = false,
    val skipSilenceEnabled: Boolean = false,
    val autoplayEnabled: Boolean = true
)
