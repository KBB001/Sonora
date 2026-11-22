package com.example.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.PersistedEqualizerState
import com.example.domain.model.PersistedPlaybackState
import com.example.domain.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.playbackStateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "sonora_playback_state"
)

@Singleton
class PlaybackPersistenceStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val jsonCodec = Json {
        ignoreUnknownKeys = true
    }

    suspend fun readPlaybackState(): PersistedPlaybackState? = runCatching {
        val prefs = context.playbackStateDataStore.data.first()
        val raw = prefs[KEY_PLAYBACK_STATE] ?: return null
        sanitizePlaybackState(jsonCodec.decodeFromString<PersistedPlaybackState>(raw))
    }.getOrNull()

    suspend fun savePlaybackState(state: PersistedPlaybackState) {
        val sanitized = sanitizePlaybackState(state)
        runCatching {
            val encoded = jsonCodec.encodeToString(sanitized)
            context.playbackStateDataStore.edit { prefs ->
                prefs[KEY_PLAYBACK_STATE] = encoded
            }
        }
    }

    suspend fun readEqualizerState(): PersistedEqualizerState? = runCatching {
        val prefs = context.playbackStateDataStore.data.first()
        val raw = prefs[KEY_EQUALIZER_STATE] ?: return null
        sanitizeEqualizerState(jsonCodec.decodeFromString<PersistedEqualizerState>(raw))
    }.getOrNull()

    suspend fun saveEqualizerState(state: PersistedEqualizerState) {
        val sanitized = sanitizeEqualizerState(state)
        runCatching {
            val encoded = jsonCodec.encodeToString(sanitized)
            context.playbackStateDataStore.edit { prefs ->
                prefs[KEY_EQUALIZER_STATE] = encoded
            }
        }
    }

    private fun sanitizePlaybackState(state: PersistedPlaybackState): PersistedPlaybackState {
        val cleanQueue = state.queue
            .filter { it.id.isNotBlank() }
            .take(MAX_PERSISTED_QUEUE_SIZE)
            .map(::sanitizeTrack)
        val clampedIndex = if (cleanQueue.isEmpty()) {
            -1
        } else {
            state.currentIndex.coerceIn(0, cleanQueue.lastIndex)
        }
        return PersistedPlaybackState(
            queue = cleanQueue,
            currentIndex = clampedIndex,
            positionMs = state.positionMs.coerceAtLeast(0L),
            shuffleEnabled = state.shuffleEnabled,
            repeatMode = state.repeatMode,
            playbackSpeed = state.playbackSpeed.coerceIn(0.5f, 2.0f)
        )
    }

    private fun sanitizeEqualizerState(state: PersistedEqualizerState): PersistedEqualizerState {
        return PersistedEqualizerState(
            isEnabled = state.isEnabled,
            selectedPresetIndex = state.selectedPresetIndex.coerceAtLeast(0),
            bandLevels = state.bandLevels.take(16).map { it.coerceIn(-3000, 3000) },
            customBandsApplied = state.customBandsApplied
        )
    }

    private fun sanitizeTrack(track: Track): Track = track.copy(
        id = track.id.trim().take(64),
        title = track.title.trim().take(240).ifBlank { "Без названия" },
        artist = track.artist.trim().take(200).ifBlank { "Неизвестный исполнитель" },
        durationSeconds = track.durationSeconds.coerceAtLeast(0L),
        thumbnailUrl = track.thumbnailUrl.trim(),
        streamUrl = null
    )

    private companion object {
        val KEY_PLAYBACK_STATE = stringPreferencesKey("playback_state_json")
        val KEY_EQUALIZER_STATE = stringPreferencesKey("equalizer_state_json")
        const val MAX_PERSISTED_QUEUE_SIZE = 500
    }
}
