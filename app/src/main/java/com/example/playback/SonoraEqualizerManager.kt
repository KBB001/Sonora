package com.example.playback

import android.media.audiofx.Equalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
class SonoraEqualizerManager @Inject constructor() {

    private var equalizer: Equalizer? = null
    private var currentSessionId: Int = 0

    private val _state = MutableStateFlow(EqualizerState())
    val state: StateFlow<EqualizerState> = _state.asStateFlow()

    fun attachToSession(audioSessionId: Int) {
        if (audioSessionId <= 0 || audioSessionId == currentSessionId) return
        val wasEnabled = _state.value.isEnabled
        val previousPreset = _state.value.selectedPresetIndex
        release()
        currentSessionId = audioSessionId

        runCatching {
            val eq = Equalizer(0, audioSessionId)
            equalizer = eq
            eq.enabled = wasEnabled
            val numBands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            val minMb = range.getOrElse(0) { -1500 }
            val maxMb = range.getOrElse(1) { 1500 }
            val presets = (0 until eq.numberOfPresets).map { eq.getPresetName(it.toShort()) }
            if (previousPreset in presets.indices) {
                runCatching { eq.usePreset(previousPreset.toShort()) }
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
                selectedPresetIndex = previousPreset.coerceIn(0, (presets.size - 1).coerceAtLeast(0))
            )
        }.onFailure {
            _state.value = EqualizerState(isAvailable = false)
        }
    }

    fun setEnabled(enabled: Boolean) {
        val eq = equalizer ?: return
        runCatching {
            eq.enabled = enabled
            _state.update { it.copy(isEnabled = eq.enabled) }
        }
    }

    fun selectPreset(index: Int) {
        val eq = equalizer ?: return
        runCatching {
            eq.usePreset(index.toShort())
            val numBands = eq.numberOfBands.toInt()
            val levels = (0 until numBands).map { eq.getBandLevel(it.toShort()) }
            _state.update {
                it.copy(
                    selectedPresetIndex = index,
                    bandLevels = levels
                )
            }
        }
    }

    fun setBandLevel(bandIndex: Int, levelMb: Short) {
        val eq = equalizer ?: return
        runCatching {
            eq.setBandLevel(bandIndex.toShort(), levelMb)
            val numBands = eq.numberOfBands.toInt()
            val levels = (0 until numBands).map { eq.getBandLevel(it.toShort()) }
            _state.update { it.copy(bandLevels = levels) }
        }
    }

    fun release() {
        runCatching { equalizer?.release() }
        equalizer = null
        currentSessionId = 0
    }
}
