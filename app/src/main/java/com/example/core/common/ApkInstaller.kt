package com.example.core.common

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

sealed interface ApkInstallCompatibility {
    data object Compatible : ApkInstallCompatibility
    data class SignatureMismatch(
        val installedCertFingerprint: String = "",
        val archiveCertFingerprint: String = ""
    ) : ApkInstallCompatibility
    data class PackageMismatch(
        val archivePackage: String,
        val expectedPackage: String
    ) : ApkInstallCompatibility
    data object InvalidArchive : ApkInstallCompatibility
    data class VersionDowngrade(val archiveVersionCode: Long, val installedVersionCode: Long) :
        ApkInstallCompatibility
}

object ApkInstaller {

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun createUnknownSourcesSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
    }

    fun createAppDetailsSettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun getInstalledSignatureFingerprint(context: Context): String? {
        return runCatching {
            val pm = context.packageManager
            val flags = signingQueryFlags()
            val installedInfo = queryInstalledPackageInfo(pm, context.packageName, flags)
            var digests = installedInfo?.let(::extractCertDigests).orEmpty()
            if (digests.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                val legacyInfo = queryInstalledPackageInfo(pm, context.packageName, PackageManager.GET_SIGNATURES)
                digests = legacyInfo?.let(::extractCertDigests).orEmpty()
            }
            digests.firstOrNull()?.let(::formatShortFingerprint)
        }.getOrNull()
    }

    fun checkInstallCompatibility(context: Context, apkFile: File): ApkInstallCompatibility {
        if (!apkFile.exists() || apkFile.length() <= 0L) {
            return ApkInstallCompatibility.InvalidArchive
        }
        return runCatching {
            val pm = context.packageManager
            val flags = signingQueryFlags()

            val installedInfo = queryInstalledPackageInfo(pm, context.packageName, flags)
            val archiveInfo = queryArchivePackageInfo(pm, apkFile.absolutePath, flags)
                ?: return ApkInstallCompatibility.InvalidArchive

            val archivePkg = archiveInfo.packageName.orEmpty()
            if (archivePkg.isNotBlank() && archivePkg != context.packageName) {
                return ApkInstallCompatibility.PackageMismatch(archivePkg, context.packageName)
            }

            var installedDigests = installedInfo?.let(::extractCertDigests).orEmpty()
            var archiveDigests = extractCertDigests(archiveInfo)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (installedDigests.isEmpty()) {
                    @Suppress("DEPRECATION")
                    installedDigests = queryInstalledPackageInfo(
                        pm,
                        context.packageName,
                        PackageManager.GET_SIGNATURES
                    )?.let(::extractCertDigests).orEmpty()
                }
                if (archiveDigests.isEmpty()) {
                    @Suppress("DEPRECATION")
                    archiveDigests = queryArchivePackageInfo(
                        pm,
                        apkFile.absolutePath,
                        PackageManager.GET_SIGNATURES
                    )?.let(::extractCertDigests).orEmpty()
                }
            }

            if (installedDigests.isNotEmpty() && archiveDigests.isNotEmpty() &&
                installedDigests.intersect(archiveDigests).isEmpty()
            ) {
                return ApkInstallCompatibility.SignatureMismatch(
                    installedCertFingerprint = formatShortFingerprint(installedDigests.first()),
                    archiveCertFingerprint = formatShortFingerprint(archiveDigests.first())
                )
            }

            val installedVersion = if (installedInfo != null) readVersionCode(installedInfo) else 0L
            val archiveVersion = readVersionCode(archiveInfo)
            if (archiveVersion > 0L && installedVersion > 0L && archiveVersion < installedVersion) {
                return ApkInstallCompatibility.VersionDowngrade(archiveVersion, installedVersion)
            }

            ApkInstallCompatibility.Compatible
        }.getOrDefault(ApkInstallCompatibility.InvalidArchive)
    }

    fun saveApkToDownloads(context: Context, apkFile: File, displayName: String = apkFile.name): Result<String> {
        return runCatching {
            val safeFileName = displayName
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .trimStart('.')
                .ifBlank { "sonora-update.apk" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                // Remove any prior copy owned by this app so Downloads stays clean
                runCatching {
                    resolver.query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.Downloads._ID),
                        "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                        arrayOf(safeFileName),
                        null
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndex(MediaStore.Downloads._ID)
                        while (cursor.moveToNext()) {
                            if (idCol >= 0) {
                                val existing = ContentUris.withAppendedId(
                                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                    cursor.getLong(idCol)
                                )
                                resolver.delete(existing, null, null)
                            }
                        }
                    }
                }

                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeFileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Не удалось создать файл в папке Загрузки")
                try {
                    resolver.openOutputStream(uri, "w")?.use { out ->
                        apkFile.inputStream().use { input -> input.copyTo(out) }
                        out.flush()
                    } ?: error("Не удалось записать файл в папку Загрузки")

                    val doneValues = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    resolver.update(uri, doneValues, null, null)
                } catch (e: Throwable) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw e
                }

                resolver.query(
                    uri,
                    arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val col = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                        if (col >= 0) {
                            return@runCatching cursor.getString(col).orEmpty().ifBlank { safeFileName }
                        }
                    }
                }
                safeFileName
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                downloadsDir.mkdirs()
                val target = File(downloadsDir, safeFileName)
                require(target.canonicalPath.startsWith(downloadsDir.canonicalPath + File.separator)) {
                    "Недопустимое имя файла"
                }
                apkFile.copyTo(target, overwrite = true)
                safeFileName
            }
        }
    }

    fun launchApkInstaller(context: Context, apkFile: File): Result<Unit> {
        return runCatching {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private fun signingQueryFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
    }

    private fun queryInstalledPackageInfo(
        pm: PackageManager,
        packageName: String,
        flags: Int
    ): PackageInfo? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, flags)
            }
        }.getOrNull()
    }

    private fun queryArchivePackageInfo(
        pm: PackageManager,
        archivePath: String,
        flags: Int
    ): PackageInfo? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(archivePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(archivePath, flags)
            }
        }.getOrNull()
    }

    private fun readVersionCode(info: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun extractCertDigests(info: PackageInfo): Set<String> {
        val collected = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = info.signingInfo
                if (signingInfo != null) {
                    signingInfo.apkContentsSigners?.let { addAll(it) }
                    signingInfo.signingCertificateHistory?.let { addAll(it) }
                }
            }
            @Suppress("DEPRECATION")
            info.signatures?.let { addAll(it) }
        }
        if (collected.isEmpty()) return emptySet()

        val md = MessageDigest.getInstance("SHA-256")
        return collected.map { sig ->
            md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun formatShortFingerprint(hex: String): String {
        return hex.uppercase().take(12).chunked(2).joinToString(":")
    }
}
