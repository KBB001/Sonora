package com.example.playback

import android.media.audiofx.Equalizer
import com.example.data.repository.PlaybackPersistenceStore
import com.example.domain.model.PersistedEqualizerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class EqualizerState(
    val isAvailable: Boolean = false,
    val isEnabled: Boolean = false,
    val bandLevels: List<Short> = emptyList(),
    val bandFrequenciesHz: List<Int> = emptyList(),
    val minLevelMb: Short = -1500,
    val maxLevelMb: Short = 1500,
    val presetNames: List<String> = emptyList(),
    val selectedPresetIndex: Int = 0
)

@Singleton
class SonoraEqualizerManager @Inject constructor(
    private val persistenceStore: PlaybackPersistenceStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var equalizer: Equalizer? = null
    private var currentSessionId: Int = 0
    private var desiredConfig: PersistedEqualizerState = PersistedEqualizerState()
    private var userModifiedConfig = false
    private val initialLoadJob: Job

    private val _state = MutableStateFlow(EqualizerState())
    val state: StateFlow<EqualizerState> = _state.asStateFlow()

    init {
        initialLoadJob = scope.launch {
            val restored = persistenceStore.readEqualizerState() ?: return@launch
            if (!userModifiedConfig) {
                desiredConfig = restored
                _state.update { current ->
                    current.copy(
                        isEnabled = restored.isEnabled,
                        selectedPresetIndex = restored.selectedPresetIndex
                    )
                }
                equalizer?.let { eq ->
                    applyDesiredConfig(eq, restored)
                }
            }
        }
    }

    fun attachToSession(audioSessionId: Int) {
        if (audioSessionId <= 0 || audioSessionId == currentSessionId) return
        release()
        currentSessionId = audioSessionId

        runCatching {
            val eq = Equalizer(0, audioSessionId)
            equalizer = eq
            applyDesiredConfig(eq, desiredConfig)
        }.onFailure {
            _state.value = EqualizerState(
                isAvailable = false,
                isEnabled = desiredConfig.isEnabled,
                selectedPresetIndex = desiredConfig.selectedPresetIndex
            )
        }
    }

    private fun applyDesiredConfig(eq: Equalizer, config: PersistedEqualizerState) {
        runCatching {
            eq.enabled = config.isEnabled
            val numBands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            val minMb = range.getOrElse(0) { -1500 }
            val maxMb = range.getOrElse(1) { 1500 }
            val presets = (0 until eq.numberOfPresets).map { eq.getPresetName(it.toShort()) }
            val safePreset = config.selectedPresetIndex.coerceIn(0, (presets.size - 1).coerceAtLeast(0))

            if (config.customBandsApplied && config.bandLevels.size == numBands) {
                config.bandLevels.forEachIndexed { idx, level ->
                    eq.setBandLevel(idx.toShort(), level.coerceIn(minMb, maxMb))
                }
            } else if (safePreset in presets.indices) {
                runCatching { eq.usePreset(safePreset.toShort()) }
            }

            val freqs = (0 until numBands).map { eq.getCenterFreq(it.toShort()) / 1000 }
            val levels = (0 until numBands).map { eq.getBandLevel(it.toShort()) }

            _state.value = EqualizerState(
                isAvailable = true,
                isEnabled = eq.enabled,
                bandLevels = levels,
                bandFrequenciesHz = freqs,
                minLevelMb = minMb,
                maxLevelMb = maxMb,
                presetNames = presets,
                selectedPresetIndex = safePreset
            )
        }
    }

    fun setEnabled(enabled: Boolean) {
        userModifiedConfig = true
        desiredConfig = desiredConfig.copy(isEnabled = enabled)
        val eq = equalizer
        if (eq != null) {
            runCatching {
                eq.enabled = enabled
                _state.update { it.copy(isEnabled = eq.enabled) }
            }
        } else {
            _state.update { it.copy(isEnabled = enabled) }
        }
        persistDesiredConfig()
    }

    fun selectPreset(index: Int) {
        userModifiedConfig = true
        val eq = equalizer ?: return
        runCatching {
            eq.usePreset(index.toShort())
            val numBands = eq.numberOfBands.toInt()
            val levels = (0 until numBands).map { eq.getBandLevel(it.toShort()) }
            desiredConfig = desiredConfig.copy(
                selectedPresetIndex = index,
                bandLevels = levels,
                customBandsApplied = false
            )
            _state.update {
                it.copy(
                    selectedPresetIndex = index,
                    bandLevels = levels
                )
            }
            persistDesiredConfig()
        }
    }

    fun setBandLevel(bandIndex: Int, levelMb: Short) {
        userModifiedConfig = true
        val eq = equalizer ?: return
        runCatching {
            eq.setBandLevel(bandIndex.toShort(), levelMb)
            val numBands = eq.numberOfBands.toInt()
            val levels = (0 until numBands).map { eq.getBandLevel(it.toShort()) }
            desiredConfig = desiredConfig.copy(
                bandLevels = levels,
                customBandsApplied = true
            )
            _state.update { it.copy(bandLevels = levels) }
            persistDesiredConfig()
        }
    }

    suspend fun getPersistedConfigSnapshot(): PersistedEqualizerState {
        initialLoadJob.join()
        return desiredConfig
    }

    suspend fun applyRestoredConfig(config: PersistedEqualizerState) {
        userModifiedConfig = true
        desiredConfig = config
        _state.update { current ->
            current.copy(
                isEnabled = config.isEnabled,
                selectedPresetIndex = config.selectedPresetIndex
            )
        }
        equalizer?.let { eq ->
            applyDesiredConfig(eq, config)
        }
        persistenceStore.saveEqualizerState(config)
    }

    private fun persistDesiredConfig() {
        val snapshot = desiredConfig
        scope.launch(Dispatchers.IO) {
            persistenceStore.saveEqualizerState(snapshot)
        }
    }

    fun release() {
        runCatching { equalizer?.release() }
        equalizer = null
        currentSessionId = 0
        _state.update { it.copy(isAvailable = false) }
    }
}
