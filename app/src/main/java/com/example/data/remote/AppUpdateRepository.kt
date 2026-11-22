package com.example.data.remote

import android.content.Context
import com.example.BuildConfig
import com.example.core.common.NetworkMonitor
import com.example.core.common.SemverComparator
import com.example.domain.model.AppUpdateInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UpdateCheckOutcome {
    data class UpdateAvailable(val info: AppUpdateInfo) : UpdateCheckOutcome
    data class UpToDate(val currentVersion: String) : UpdateCheckOutcome
    data class Failure(val message: String) : UpdateCheckOutcome
}

sealed interface ApkDownloadEvent {
    data class Progress(
        val fraction: Float,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : ApkDownloadEvent
    data class Completed(val file: File) : ApkDownloadEvent
}

@Singleton
class AppUpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val networkMonitor: NetworkMonitor
) {
    private val updatesDir: File
        get() = File(context.cacheDir, "updates").apply { mkdirs() }

    suspend fun checkForUpdate(): UpdateCheckOutcome = withContext(Dispatchers.IO) {
        if (!networkMonitor.isOnline.value) {
            return@withContext UpdateCheckOutcome.Failure(
                "Нет подключения к интернету. Проверьте сеть и повторите попытку."
            )
        }

        val repoSlug = BuildConfig.GITHUB_REPOSITORY.trim()
        if (!repoSlug.contains("/") || repoSlug.startsWith("YOUR_GITHUB_OWNER")) {
            return@withContext UpdateCheckOutcome.Failure(
                "Укажите owner/repo в app/build.gradle.kts (GITHUB_REPOSITORY) или соберите релиз через GitHub Actions."
            )
        }

        val apiUrl = "https://api.github.com/repos/$repoSlug/releases/latest"
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "SonoraAndroid/${BuildConfig.VERSION_NAME}")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    404 -> return@withContext UpdateCheckOutcome.Failure(
                        "Релизы ещё не опубликованы в репозитории $repoSlug."
                    )
                    403, 429 -> return@withContext UpdateCheckOutcome.Failure(
                        "Превышен лимит запросов GitHub API. Попробуйте немного позже."
                    )
                }

                if (!response.isSuccessful) {
                    return@withContext UpdateCheckOutcome.Failure(
                        "Ошибка проверки обновлений (HTTP ${response.code})."
                    )
                }

                val rawJson = response.body?.string().orEmpty()
                if (rawJson.isBlank()) {
                    return@withContext UpdateCheckOutcome.Failure(
                        "Получен пустой ответ от GitHub Releases."
                    )
                }

                val json = JSONObject(rawJson)
                val tagName = json.optString("tag_name").trim()
                if (tagName.isBlank()) {
                    return@withContext UpdateCheckOutcome.Failure(
                        "В последнем релизе отсутствует номер версии (tag_name)."
                    )
                }

                if (!SemverComparator.isNewerVersion(tagName, BuildConfig.VERSION_NAME)) {
                    return@withContext UpdateCheckOutcome.UpToDate(
                        SemverComparator.normalizeDisplayVersion(BuildConfig.VERSION_NAME)
                    )
                }

                val releaseNotes = json.optString("body").trim().ifBlank {
                    "Улучшения стабильности и новые функции."
                }
                val assets = json.optJSONArray("assets") ?: JSONArray()
                val apkAsset = findApkAsset(assets)
                    ?: return@withContext UpdateCheckOutcome.Failure(
                        "Доступна версия $tagName, но APK-файл ещё не загружен в релиз."
                    )

                UpdateCheckOutcome.UpdateAvailable(
                    AppUpdateInfo(
                        tagName = tagName,
                        releaseNotes = releaseNotes,
                        downloadUrl = apkAsset.downloadUrl,
                        assetName = apkAsset.name,
                        assetSizeBytes = apkAsset.size
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UpdateCheckOutcome.Failure(
                "Не удалось проверить обновления: ${e.localizedMessage ?: "ошибка сети"}"
            )
        }
    }

    fun downloadApk(info: AppUpdateInfo): Flow<ApkDownloadEvent> = flow {
        require(isTrustedDownloadUrl(info.downloadUrl)) {
            "Небезопасная ссылка загрузки обновления (требуется HTTPS)"
        }
        val dir = updatesDir
        dir.listFiles()?.forEach { old -> old.delete() }

        val safeName = info.assetName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trimStart('.')
            .ifBlank { "sonora-update.apk" }
        val tempFile = File(dir, "$safeName.part")
        val finalFile = File(dir, safeName)
        val baseCanonical = dir.canonicalPath + File.separator
        require(
            tempFile.canonicalPath.startsWith(baseCanonical) &&
                finalFile.canonicalPath.startsWith(baseCanonical)
        ) {
            "Недопустимый путь сохранения файла обновления"
        }

        try {
            val request = Request.Builder()
                .url(info.downloadUrl)
                .header("User-Agent", "SonoraAndroid/${BuildConfig.VERSION_NAME}")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Ошибка скачивания APK (HTTP ${response.code})")
                }
                val body = response.body ?: throw IllegalStateException("Пустой файл APK")
                val totalBytes = body.contentLength().takeIf { it > 0L } ?: info.assetSizeBytes

                body.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastEmittedMs = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read

                            val now = System.currentTimeMillis()
                            if (now - lastEmittedMs >= 80L || downloaded == totalBytes) {
                                lastEmittedMs = now
                                val fraction = if (totalBytes > 0L) {
                                    (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    -1f
                                }
                                emit(ApkDownloadEvent.Progress(fraction, downloaded, totalBytes))
                            }
                        }
                        output.flush()
                    }
                }
            }

            if (!tempFile.exists() || tempFile.length() <= 0L) {
                throw IllegalStateException("Загруженный файл обновления пуст или повреждён")
            }

            if (finalFile.exists()) finalFile.delete()
            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }
            if (!finalFile.exists() || finalFile.length() <= 0L) {
                throw IllegalStateException("Не удалось сохранить файл обновления")
            }

            emit(ApkDownloadEvent.Completed(finalFile))
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }.flowOn(Dispatchers.IO)

    fun clearCachedUpdates() {
        runCatching {
            File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
        }
    }

    private data class ApkAsset(
        val name: String,
        val downloadUrl: String,
        val size: Long
    )

    private fun findApkAsset(assets: JSONArray): ApkAsset? {
        var firstApk: ApkAsset? = null
        var fallbackAsset: ApkAsset? = null
        for (i in 0 until assets.length()) {
            val item = assets.optJSONObject(i) ?: continue
            val name = item.optString("name").trim()
            val url = item.optString("browser_download_url").trim()
            val size = item.optLong("size", -1L)
            if (url.isBlank() || !isTrustedDownloadUrl(url)) continue
            val asset = ApkAsset(name = name, downloadUrl = url, size = size)
            if (name.endsWith(".apk", ignoreCase = true)) {
                if (!name.contains("debug", ignoreCase = true)) {
                    return asset
                }
                if (firstApk == null) {
                    firstApk = asset
                }
            }
            if (fallbackAsset == null) {
                fallbackAsset = asset
            }
        }
        return firstApk ?: fallbackAsset
    }

    private fun isTrustedDownloadUrl(url: String): Boolean {
        val parsed = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
        if (!parsed.scheme.equals("https", ignoreCase = true)) return false
        val host = parsed.host?.lowercase() ?: return false
        return host == "github.com" ||
            host.endsWith(".github.com") ||
            host.endsWith(".githubusercontent.com")
    }
}
