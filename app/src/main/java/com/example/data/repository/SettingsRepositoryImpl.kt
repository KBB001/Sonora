package com.example.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.AppSettings
import com.example.domain.model.AppThemeMode
import com.example.domain.model.AudioQuality
import com.example.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "sonora_settings")

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {

    override val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        val quality = prefs[KEY_AUDIO_QUALITY]
            ?.let { name -> AudioQuality.entries.firstOrNull { it.name == name } }
            ?: AudioQuality.HIGH
        val theme = prefs[KEY_THEME_MODE]
            ?.let { name -> AppThemeMode.entries.firstOrNull { it.name == name } }
            ?: AppThemeMode.DARK
        AppSettings(
            audioQuality = quality,
            themeMode = theme,
            dynamicColorEnabled = prefs[KEY_DYNAMIC_COLOR] ?: false,
            skipSilenceEnabled = prefs[KEY_SKIP_SILENCE] ?: false,
            autoplayEnabled = prefs[KEY_AUTOPLAY] ?: true
        )
    }

    override suspend fun setAudioQuality(quality: AudioQuality) {
        context.settingsDataStore.edit { it[KEY_AUDIO_QUALITY] = quality.name }
    }

    override suspend fun setThemeMode(themeMode: AppThemeMode) {
        context.settingsDataStore.edit { it[KEY_THEME_MODE] = themeMode.name }
    }

    override suspend fun setDynamicColorEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    override suspend fun setSkipSilenceEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_SKIP_SILENCE] = enabled }
    }

    override suspend fun setAutoplayEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_AUTOPLAY] = enabled }
    }

    private companion object {
        val KEY_AUDIO_QUALITY = stringPreferencesKey("audio_quality")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_SKIP_SILENCE = booleanPreferencesKey("skip_silence")
        val KEY_AUTOPLAY = booleanPreferencesKey("autoplay")
    }
}
