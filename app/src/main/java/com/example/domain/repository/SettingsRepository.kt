package com.example.domain.repository

import com.example.domain.model.AppSettings
import com.example.domain.model.AppThemeMode
import com.example.domain.model.AudioQuality
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setAudioQuality(quality: AudioQuality)
    suspend fun setThemeMode(themeMode: AppThemeMode)
    suspend fun setDynamicColorEnabled(enabled: Boolean)
    suspend fun setSkipSilenceEnabled(enabled: Boolean)
    suspend fun setAutoplayEnabled(enabled: Boolean)
}
