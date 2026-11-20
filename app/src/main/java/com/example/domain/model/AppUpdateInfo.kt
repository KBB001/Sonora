package com.example.domain.model

import java.io.File

data class AppUpdateInfo(
    val tagName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val assetName: String,
    val assetSizeBytes: Long
)

sealed interface AppUpdateUiState {
    data object Idle : AppUpdateUiState
    data object Checking : AppUpdateUiState
    data class Available(val info: AppUpdateInfo) : AppUpdateUiState
    data class Downloading(
        val info: AppUpdateInfo,
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : AppUpdateUiState
    data class ReadyToInstall(
        val info: AppUpdateInfo,
        val apkFile: File
    ) : AppUpdateUiState
}
