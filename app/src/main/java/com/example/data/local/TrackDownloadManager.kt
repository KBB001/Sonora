package com.example.data.local

import android.content.Context
import com.example.core.common.FriendlyErrorMapper
import com.example.domain.model.Track
import com.example.domain.repository.LibraryRepository
import com.example.domain.repository.SettingsRepository
import com.example.domain.repository.YouTubeRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val youTubeRepository: YouTubeRepository,
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository
) {
    private val _downloadingIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadingIds: StateFlow<Set<String>> = _downloadingIds.asStateFlow()

    private val offlineDir: File
        get() = File(context.filesDir, "offline_tracks").apply { mkdirs() }

    suspend fun downloadTrack(track: Track): Result<Unit> = withContext(Dispatchers.IO) {
        if (_downloadingIds.value.contains(track.id)) {
            return@withContext Result.success(Unit)
        }
        _downloadingIds.update { it + track.id }
        var tempFile: File? = null
        try {
            val quality = settingsRepository.settings.first().audioQuality
            val cachedUrl = track.streamUrl?.takeIf { it.startsWith("https://", ignoreCase = true) }
            val initialUrl = cachedUrl
                ?: youTubeRepository.getAudioStreamUrl(track.id, quality).getOrThrow()

            val safeTrackId = track.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
                .ifBlank { "track_${track.id.hashCode()}" }
            val dir = offlineDir
            val baseCanonical = dir.canonicalPath + File.separator
            val targetFile = File(dir, "$safeTrackId.m4a")
            tempFile = File(dir, "$safeTrackId.tmp")
            require(
                targetFile.canonicalPath.startsWith(baseCanonical) &&
                    tempFile.canonicalPath.startsWith(baseCanonical)
            ) {
                "Некорректный путь сохранения трека"
            }

            try {
                downloadToTempFile(initialUrl, tempFile)
            } catch (firstError: DownloadHttpException) {
                if ((firstError.code == 403 || firstError.code == 410) && cachedUrl != null) {
                    val freshUrl = youTubeRepository
                        .getAudioStreamUrl(track.id, quality, forceRefresh = true)
                        .getOrThrow()
                    downloadToTempFile(freshUrl, tempFile)
                } else {
                    throw firstError
                }
            }

            if (!tempFile.exists() || tempFile.length() <= 0L) {
                throw IOException("Скачанный аудиофайл пуст или повреждён")
            }

            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            val savedSize = targetFile.length()
            if (!targetFile.exists() || savedSize <= 0L) {
                targetFile.delete()
                throw IOException("Не удалось сохранить аудиофайл на устройство")
            }

            libraryRepository.saveDownloadedTrack(
                track = track,
                filePath = targetFile.absolutePath,
                fileSizeBytes = savedSize
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(IOException(FriendlyErrorMapper.map(e, "Не удалось скачать трек"), e))
        } finally {
            if (tempFile?.exists() == true) {
                tempFile.delete()
            }
            _downloadingIds.update { it - track.id }
        }
    }

    private fun downloadToTempFile(streamUrl: String, tempFile: File) {
        require(streamUrl.startsWith("https://", ignoreCase = true)) {
            "Небезопасная ссылка на аудиопоток"
        }
        val request = Request.Builder().url(streamUrl).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw DownloadHttpException(
                    response.code,
                    "Ошибка загрузки (HTTP ${response.code})"
                )
            }
            val body = response.body ?: throw IOException("Сервер вернул пустой аудиопоток")
            body.byteStream().use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private class DownloadHttpException(val code: Int, message: String) : IOException(message)

    suspend fun removeDownload(trackId: String) {
        libraryRepository.deleteDownloadedTrack(trackId)
    }
}
