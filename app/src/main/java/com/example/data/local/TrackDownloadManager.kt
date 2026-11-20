package com.example.data.local

import android.content.Context
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
        try {
            val quality = settingsRepository.settings.first().audioQuality
            val streamUrl = track.streamUrl?.takeIf { it.isNotBlank() }
                ?: youTubeRepository.getAudioStreamUrl(track.id, quality).getOrThrow()

            val targetFile = File(offlineDir, "${track.id}.m4a")
            val tempFile = File(offlineDir, "${track.id}.tmp")

            val request = Request.Builder().url(streamUrl).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Download failed with HTTP ${response.code}")
                }
                val body = response.body ?: throw IllegalStateException("Empty audio response")
                body.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            libraryRepository.saveDownloadedTrack(
                track = track,
                filePath = targetFile.absolutePath,
                fileSizeBytes = targetFile.length()
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _downloadingIds.update { it - track.id }
        }
    }

    suspend fun removeDownload(trackId: String) {
        libraryRepository.deleteDownloadedTrack(trackId)
    }
}
