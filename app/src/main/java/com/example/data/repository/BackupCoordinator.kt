package com.example.data.repository

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.domain.model.BackupSettings
import com.example.domain.model.LibraryBackup
import com.example.domain.repository.LibraryRepository
import com.example.domain.repository.SettingsRepository
import com.example.playback.PlaybackManager
import com.example.playback.SonoraEqualizerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class BackupRestoreSummary(
    val tracksRestored: Int,
    val playlistsRestored: Int,
    val searchQueriesRestored: Int,
    val settingsRestored: Boolean,
    val playbackRestored: Boolean,
    val equalizerRestored: Boolean
)

@Singleton
class BackupCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository,
    private val playbackManager: PlaybackManager,
    private val equalizerManager: SonoraEqualizerManager
) {
    private val jsonCodec = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    suspend fun exportFullBackup(): LibraryBackup = withContext(Dispatchers.IO) {
        val libraryBackup = libraryRepository.exportLibraryBackup()
        val currentSettings = settingsRepository.settings.first()
        val playbackSnapshot = playbackManager.getPersistedStateSnapshot()
        val equalizerSnapshot = equalizerManager.getPersistedConfigSnapshot()

        libraryBackup.copy(
            version = 2,
            exportedAt = System.currentTimeMillis(),
            settings = BackupSettings(
                audioQuality = currentSettings.audioQuality,
                themeMode = currentSettings.themeMode,
                dynamicColorEnabled = currentSettings.dynamicColorEnabled,
                skipSilenceEnabled = currentSettings.skipSilenceEnabled,
                autoplayEnabled = currentSettings.autoplayEnabled
            ),
            playback = playbackSnapshot,
            equalizer = equalizerSnapshot
        )
    }

    suspend fun exportFullBackupJson(): String = withContext(Dispatchers.IO) {
        jsonCodec.encodeToString(exportFullBackup())
    }

    suspend fun importFullBackupJson(json: String): Result<BackupRestoreSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(json.isNotBlank() && json.length <= MAX_BACKUP_JSON_CHARS) {
                    "Файл резервной копии пустой или слишком большой"
                }
                val backup = jsonCodec.decodeFromString<LibraryBackup>(json)
                val libraryResult = libraryRepository.importLibraryBackup(backup)

                var settingsRestored = false
                backup.settings?.let { s ->
                    settingsRepository.setAudioQuality(s.audioQuality)
                    settingsRepository.setThemeMode(s.themeMode)
                    settingsRepository.setDynamicColorEnabled(s.dynamicColorEnabled)
                    settingsRepository.setSkipSilenceEnabled(s.skipSilenceEnabled)
                    settingsRepository.setAutoplayEnabled(s.autoplayEnabled)
                    settingsRestored = true
                }

                var playbackRestored = false
                backup.playback?.let { p ->
                    playbackManager.applyRestoredState(p)
                    playbackRestored = p.queue.isNotEmpty() || p.playbackSpeed != 1.0f || p.shuffleEnabled
                }

                var equalizerRestored = false
                backup.equalizer?.let { eq ->
                    equalizerManager.applyRestoredConfig(eq)
                    equalizerRestored = true
                }

                BackupRestoreSummary(
                    tracksRestored = libraryResult.tracksRestored,
                    playlistsRestored = libraryResult.playlistsRestored,
                    searchQueriesRestored = libraryResult.searchQueriesRestored,
                    settingsRestored = settingsRestored,
                    playbackRestored = playbackRestored,
                    equalizerRestored = equalizerRestored
                )
            }
        }

    suspend fun saveUpdateSnapshotToDownloads(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val jsonBytes = exportFullBackupJson().toByteArray(Charsets.UTF_8)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/Sonora"

                // Replace any previous snapshot owned by this app so Downloads stays tidy
                runCatching {
                    resolver.query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.Downloads._ID),
                        "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                        arrayOf(AUTO_BACKUP_FILE_NAME),
                        null
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndex(MediaStore.Downloads._ID)
                        while (cursor.moveToNext()) {
                            if (idCol >= 0) {
                                val existingUri = ContentUris.withAppendedId(
                                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                    cursor.getLong(idCol)
                                )
                                resolver.delete(existingUri, null, null)
                            }
                        }
                    }
                }

                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, AUTO_BACKUP_FILE_NAME)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Не удалось создать файл резервной копии в Загрузках")
                try {
                    resolver.openOutputStream(uri, "w")?.use { out ->
                        out.write(jsonBytes)
                        out.flush()
                    } ?: error("Не удалось записать файл резервной копии")

                    val readyValues = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    resolver.update(uri, readyValues, null, null)
                } catch (e: Throwable) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw e
                }

                val actualName = resolver.query(
                    uri,
                    arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val col = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                        if (col >= 0) cursor.getString(col) else null
                    } else {
                        null
                    }
                }?.takeIf { it.isNotBlank() } ?: AUTO_BACKUP_FILE_NAME

                "Downloads/Sonora/$actualName"
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "Sonora"
                )
                downloadsDir.mkdirs()
                val target = File(downloadsDir, AUTO_BACKUP_FILE_NAME)
                require(target.canonicalPath.startsWith(downloadsDir.canonicalPath + File.separator)) {
                    "Недопустимый путь резервной копии"
                }
                target.writeBytes(jsonBytes)
                "Downloads/Sonora/$AUTO_BACKUP_FILE_NAME"
            }
        }
    }

    private companion object {
        const val MAX_BACKUP_JSON_CHARS = 10 * 1024 * 1024
        const val AUTO_BACKUP_FILE_NAME = "sonora_auto_backup.json"
    }
}
