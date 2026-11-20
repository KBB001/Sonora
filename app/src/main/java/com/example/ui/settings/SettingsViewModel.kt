package com.example.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.remote.ApkDownloadEvent
import com.example.data.remote.AppUpdateRepository
import com.example.data.remote.UpdateCheckOutcome
import com.example.domain.model.AppSettings
import com.example.domain.model.AppThemeMode
import com.example.domain.model.AppUpdateInfo
import com.example.domain.model.AppUpdateUiState
import com.example.domain.model.AudioQuality
import com.example.domain.model.PlaybackState
import com.example.domain.repository.LibraryRepository
import com.example.domain.repository.SettingsRepository
import com.example.playback.AudioCacheProvider
import com.example.playback.EqualizerState
import com.example.playback.PlaybackManager
import com.example.playback.SonoraEqualizerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val playbackState: PlaybackState = PlaybackState(),
    val equalizerState: EqualizerState = EqualizerState(),
    val cacheSizeBytes: Long = 0L,
    val updateState: AppUpdateUiState = AppUpdateUiState.Idle
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val libraryRepository: LibraryRepository,
    private val audioCacheProvider: AudioCacheProvider,
    private val playbackManager: PlaybackManager,
    private val equalizerManager: SonoraEqualizerManager,
    private val appUpdateRepository: AppUpdateRepository
) : ViewModel() {

    private val cacheSizeFlow = MutableStateFlow(0L)
    private val updateStateFlow = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Idle)
    private var updateDownloadJob: Job? = null

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        playbackManager.playbackState,
        equalizerManager.state,
        cacheSizeFlow,
        updateStateFlow
    ) { settings, playbackState, eqState, cacheBytes, updateState ->
        SettingsUiState(
            settings = settings,
            playbackState = playbackState,
            equalizerState = eqState,
            cacheSizeBytes = cacheBytes,
            updateState = updateState
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState()
    )

    init {
        refreshCacheSize()
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            cacheSizeFlow.value = audioCacheProvider.getCacheSizeBytes()
        }
    }

    fun setAudioQuality(quality: AudioQuality) {
        viewModelScope.launch { settingsRepository.setAudioQuality(quality) }
    }

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDynamicColorEnabled(enabled) }
    }

    fun setSkipSilenceEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSkipSilenceEnabled(enabled) }
    }

    fun setAutoplayEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoplayEnabled(enabled) }
    }

    fun startSleepTimer(minutes: Int) = playbackManager.startSleepTimer(minutes)

    fun cancelSleepTimer() = playbackManager.cancelSleepTimer()

    fun setEqualizerEnabled(enabled: Boolean) = equalizerManager.setEnabled(enabled)

    fun selectEqualizerPreset(index: Int) = equalizerManager.selectPreset(index)

    fun setEqualizerBand(bandIndex: Int, levelMb: Short) =
        equalizerManager.setBandLevel(bandIndex, levelMb)

    fun clearCache(onCleared: () -> Unit) {
        viewModelScope.launch {
            audioCacheProvider.clearCache()
            cacheSizeFlow.value = audioCacheProvider.getCacheSizeBytes()
            onCleared()
        }
    }

    suspend fun generateBackupJson(): String = libraryRepository.exportBackupJson()

    fun restoreBackupJson(json: String, onResult: (Result<Int>) -> Unit) {
        viewModelScope.launch {
            val result = libraryRepository.importBackupJson(json)
            onResult(result)
        }
    }

    fun checkForUpdates(onMessage: (String) -> Unit) {
        if (updateStateFlow.value is AppUpdateUiState.Checking ||
            updateStateFlow.value is AppUpdateUiState.Downloading
        ) {
            return
        }
        viewModelScope.launch {
            updateStateFlow.value = AppUpdateUiState.Checking
            when (val outcome = appUpdateRepository.checkForUpdate()) {
                is UpdateCheckOutcome.UpdateAvailable -> {
                    updateStateFlow.value = AppUpdateUiState.Available(outcome.info)
                }

                is UpdateCheckOutcome.UpToDate -> {
                    updateStateFlow.value = AppUpdateUiState.Idle
                    onMessage("У вас актуальная версия (${outcome.currentVersion})")
                }

                is UpdateCheckOutcome.Failure -> {
                    updateStateFlow.value = AppUpdateUiState.Idle
                    onMessage(outcome.message)
                }
            }
        }
    }

    fun startUpdateDownload(
        info: AppUpdateInfo,
        onReadyToInstall: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        updateDownloadJob?.cancel()
        updateDownloadJob = viewModelScope.launch {
            updateStateFlow.value = AppUpdateUiState.Downloading(
                info = info,
                progress = 0f,
                downloadedBytes = 0L,
                totalBytes = info.assetSizeBytes
            )
            try {
                appUpdateRepository.downloadApk(info).collect { event ->
                    when (event) {
                        is ApkDownloadEvent.Progress -> {
                            updateStateFlow.value = AppUpdateUiState.Downloading(
                                info = info,
                                progress = event.fraction,
                                downloadedBytes = event.downloadedBytes,
                                totalBytes = event.totalBytes
                            )
                        }

                        is ApkDownloadEvent.Completed -> {
                            updateStateFlow.value = AppUpdateUiState.ReadyToInstall(info, event.file)
                            onReadyToInstall(event.file)
                        }
                    }
                }
            } catch (e: CancellationException) {
                updateStateFlow.value = AppUpdateUiState.Idle
                throw e
            } catch (e: Exception) {
                updateStateFlow.value = AppUpdateUiState.Available(info)
                onError("Ошибка загрузки обновления: ${e.localizedMessage ?: "сбой сети"}")
            }
        }
    }

    fun dismissUpdateDialog() {
        updateDownloadJob?.cancel()
        updateDownloadJob = null
        updateStateFlow.value = AppUpdateUiState.Idle
    }
}
