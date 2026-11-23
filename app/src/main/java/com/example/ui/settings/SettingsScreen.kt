package com.example.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.core.common.ApkInstallCompatibility
import com.example.core.common.ApkInstaller
import com.example.core.common.DurationFormatter
import com.example.domain.model.AppThemeMode
import com.example.domain.model.AppUpdateUiState
import com.example.domain.model.AudioQuality
import com.example.playback.EqualizerState
import com.example.ui.account.YouTubeAccountSettingsCard
import com.example.ui.account.YouTubeAccountViewModel
import com.example.ui.components.SonoraIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onOpenYouTubeLogin: (resetSession: Boolean) -> Unit,
    bottomContentPadding: PaddingValues,
    onShowMessage: (String) -> Unit,
    onNavigateToAppUpdate: (() -> Unit)? = null,
    onNavigateToEqualizer: (() -> Unit)? = null,
    onNavigateToCacheQuality: (() -> Unit)? = null,
    onNavigateToYouTubeAccount: (() -> Unit)? = null,
    onNavigateToSignOutConfirm: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
    accountViewModel: YouTubeAccountViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val accountUiState by accountViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showEqualizerSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshCacheSize()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val json = viewModel.generateBackupJson()
                    withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openOutputStream(uri)
                            ?: error("Не удалось открыть файл для записи")
                        stream.use { out ->
                            out.write(json.toByteArray(Charsets.UTF_8))
                            out.flush()
                        }
                    }
                }.onSuccess {
                    onShowMessage("Резервная копия экспортирована")
                }.onFailure {
                    onShowMessage("Ошибка экспорта: ${it.localizedMessage}")
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val json = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            val maxBytes = 10 * 1024 * 1024
                            val buffer = ByteArray(maxBytes + 1)
                            var totalRead = 0
                            while (totalRead < buffer.size) {
                                val count = stream.read(buffer, totalRead, buffer.size - totalRead)
                                if (count <= 0) break
                                totalRead += count
                            }
                            if (totalRead > maxBytes) {
                                throw IllegalArgumentException("Файл слишком большой")
                            }
                            String(buffer, 0, totalRead, Charsets.UTF_8)
                        }
                    }.getOrNull()
                }
                if (json.isNullOrBlank()) {
                    onShowMessage("Не удалось прочитать файл или он слишком большой")
                } else {
                    viewModel.restoreBackupJson(json) { result ->
                        result.fold(
                            onSuccess = { summary ->
                                val parts = buildList {
                                    if (summary.tracksRestored > 0) add("треков: ${summary.tracksRestored}")
                                    if (summary.playlistsRestored > 0) add("плейлистов: ${summary.playlistsRestored}")
                                    if (summary.searchQueriesRestored > 0) add("запросов: ${summary.searchQueriesRestored}")
                                    if (summary.settingsRestored) add("настройки")
                                    if (summary.playbackRestored) add("очередь")
                                    if (summary.equalizerRestored) add("эквалайзер")
                                }
                                val msg = if (parts.isEmpty()) {
                                    "Резервная копия применена"
                                } else {
                                    "Восстановлено (${parts.joinToString(", ")})"
                                }
                                onShowMessage(msg)
                            },
                            onFailure = {
                                onShowMessage("Некорректный JSON-файл резервной копии")
                            }
                        )
                    }
                }
            }
        }
    }

    var pendingInstallFile by remember { mutableStateOf<File?>(null) }
    var savedMismatchApkName by remember { mutableStateOf<String?>(null) }

    val installPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        val file = pendingInstallFile
        if (file != null && file.exists() && ApkInstaller.canRequestPackageInstalls(context)) {
            ApkInstaller.launchApkInstaller(context, file).onFailure { err ->
                onShowMessage("Не удалось открыть установщик: ${err.localizedMessage}")
            }
            pendingInstallFile = null
        } else if (file != null && !ApkInstaller.canRequestPackageInstalls(context)) {
            onShowMessage("Разрешите установку из этого источника, чтобы установить обновление")
        }
    }

    fun triggerApkInstall(file: File, bypassCompatibilityCheck: Boolean = false) {
        if (!bypassCompatibilityCheck) {
            when (val check = ApkInstaller.checkInstallCompatibility(context, file)) {
                is ApkInstallCompatibility.InvalidArchive -> {
                    onShowMessage("APK-файл повреждён или неполный. Повторите загрузку обновления.")
                    return
                }
                is ApkInstallCompatibility.PackageMismatch -> {
                    onShowMessage("Скачанный APK относится к другому пакету (${check.archivePackage}).")
                    return
                }
                is ApkInstallCompatibility.SignatureMismatch,
                is ApkInstallCompatibility.VersionDowngrade -> {
                    onShowMessage("Этот APK нельзя установить поверх текущей версии. Сохраните его в «Загрузки» и переустановите приложение.")
                    return
                }
                ApkInstallCompatibility.Compatible -> Unit
            }
        }
        if (ApkInstaller.canRequestPackageInstalls(context)) {
            ApkInstaller.launchApkInstaller(context, file).onFailure { err ->
                onShowMessage("Не удалось запустить установщик: ${err.localizedMessage}")
            }
        } else {
            pendingInstallFile = file
            onShowMessage("Разрешите установку пакетов для Sonora в открывшемся окне")
            installPermissionLauncher.launch(ApkInstaller.createUnknownSourcesSettingsIntent(context))
        }
    }

    when (val updateState = uiState.updateState) {
        is AppUpdateUiState.Available -> {
            AlertDialog(
                onDismissRequest = viewModel::dismissUpdateDialog,
                title = { Text("Доступно обновление ${updateState.info.tagName}") },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = "Текущая версия: v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = updateState.info.releaseNotes,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            savedMismatchApkName = null
                            viewModel.startUpdateDownload(
                                info = updateState.info,
                                onReadyToInstall = { downloadedFile ->
                                    if (ApkInstaller.checkInstallCompatibility(context, downloadedFile)
                                        is ApkInstallCompatibility.Compatible
                                    ) {
                                        triggerApkInstall(downloadedFile)
                                    }
                                },
                                onError = onShowMessage
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.testTag("confirm_download_update_button")
                    ) {
                        Text("Скачать и установить", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissUpdateDialog) {
                        Text("Позже")
                    }
                }
            )
        }

        is AppUpdateUiState.Downloading -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Загрузка ${updateState.info.tagName}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (updateState.progress >= 0f) {
                            LinearProgressIndicator(
                                progress = { updateState.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            val pct = (updateState.progress * 100).toInt()
                            val downloadedMb = updateState.downloadedBytes / (1024f * 1024f)
                            val totalMb = updateState.totalBytes / (1024f * 1024f)
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%d%% (%.1f / %.1f МБ)",
                                    pct,
                                    downloadedMb,
                                    totalMb
                                ),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            val downloadedMb = updateState.downloadedBytes / (1024f * 1024f)
                            Text(
                                text = String.format(Locale.US, "Загружено %.1f МБ...", downloadedMb),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = viewModel::dismissUpdateDialog) {
                        Text("Отмена")
                    }
                }
            )
        }

        is AppUpdateUiState.ReadyToInstall -> {
            val compatibility = remember(updateState.apkFile) {
                ApkInstaller.checkInstallCompatibility(context, updateState.apkFile)
            }
            val closeDialog = {
                savedMismatchApkName = null
                viewModel.dismissUpdateDialog()
            }
            AlertDialog(
                onDismissRequest = closeDialog,
                title = {
                    Text(
                        when {
                            savedMismatchApkName != null ->
                                "Файл сохранён в «Загрузки»"
                            compatibility is ApkInstallCompatibility.InvalidArchive ->
                                "Повреждённый APK-файл"
                            compatibility is ApkInstallCompatibility.PackageMismatch ->
                                "Неверный пакет APK"
                            compatibility is ApkInstallCompatibility.SignatureMismatch ->
                                "Требуется переустановка (${updateState.info.tagName})"
                            compatibility is ApkInstallCompatibility.VersionDowngrade ->
                                "Версия APK ниже установленной"
                            else ->
                                "Обновление ${updateState.info.tagName} готово"
                        }
                    )
                },
                text = {
                    Text(
                        text = when {
                            savedMismatchApkName != null -> {
                                val backupNote = uiState.lastAutoBackupLocation?.let {
                                    "\n\nРезервная копия всех данных сохранена в «$it». После установки откройте Настройки → Импорт JSON и выберите этот файл."
                                } ?: ""
                                "APK «$savedMismatchApkName» сохранён в папку «Загрузки».\n\n1. Нажмите «Удалить текущую» и подтвердите удаление старой версии.\n2. Откройте «$savedMismatchApkName» в «Загрузках» и установите.\n\nПосле перехода на общий ключ будущие обновления из GitHub будут ставиться поверх без удаления.$backupNote"
                            }
                            compatibility is ApkInstallCompatibility.InvalidArchive ->
                                "Загруженный APK-файл повреждён или скачался не полностью. Закройте окно и запустите проверку обновлений заново."
                            compatibility is ApkInstallCompatibility.PackageMismatch ->
                                "Пакет внутри APK (${compatibility.archivePackage}) не совпадает с Sonora (${compatibility.expectedPackage}). Установка отменена."
                            compatibility is ApkInstallCompatibility.SignatureMismatch -> {
                                val keyDetails = if (compatibility.installedCertFingerprint.isNotBlank() &&
                                    compatibility.archiveCertFingerprint.isNotBlank()
                                ) {
                                    "\n\nКлюч установленной: ${compatibility.installedCertFingerprint}\nКлюч нового APK: ${compatibility.archiveCertFingerprint}"
                                } else {
                                    ""
                                }
                                val backupPath = uiState.lastAutoBackupLocation ?: "Downloads/Sonora/sonora_auto_backup.json"
                                "Установленная версия и новый APK подписаны разными ключами (например, сборка из AI Studio или старый ключ), поэтому Android блокирует установку поверх.\n\nДанные уже сохранены в «$backupPath». Сохраните APK в «Загрузки», переустановите 1 раз и восстановите данные через «Импорт JSON».$keyDetails"
                            }
                            compatibility is ApkInstallCompatibility.VersionDowngrade ->
                                "Код сборки в APK (${compatibility.archiveVersionCode}) ниже установленного (${compatibility.installedVersionCode}). Сохраните файл в «Загрузки» или переустановите приложение вручную."
                            else ->
                                "APK-файл загружен. Нажмите «Установить», чтобы запустить системный установщик. Все ваши данные сохранятся."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    when {
                        savedMismatchApkName != null -> {
                            Button(
                                onClick = {
                                    runCatching {
                                        context.startActivity(ApkInstaller.createAppDetailsSettingsIntent(context))
                                    }.onFailure {
                                        onShowMessage("Откройте Настройки -> Приложения -> Sonora -> Удалить")
                                    }
                                    closeDialog()
                                },
                                modifier = Modifier.testTag("open_app_details_for_uninstall_button")
                            ) {
                                Text("Удалить текущую")
                            }
                        }

                        compatibility is ApkInstallCompatibility.InvalidArchive ||
                            compatibility is ApkInstallCompatibility.PackageMismatch -> {
                            Button(
                                onClick = {
                                    runCatching { updateState.apkFile.delete() }
                                    closeDialog()
                                },
                                modifier = Modifier.testTag("invalid_apk_close_button")
                            ) {
                                Text("Понятно")
                            }
                        }

                        compatibility is ApkInstallCompatibility.SignatureMismatch ||
                            compatibility is ApkInstallCompatibility.VersionDowngrade -> {
                            Button(
                                onClick = {
                                    scope.launch {
                                        viewModel.savePreUpdateBackupSnapshot()
                                        val displayName = updateState.info.assetName.ifBlank {
                                            "sonora-${updateState.info.tagName}.apk"
                                        }
                                        val result = withContext(Dispatchers.IO) {
                                            ApkInstaller.saveApkToDownloads(
                                                context = context,
                                                apkFile = updateState.apkFile,
                                                displayName = displayName
                                            )
                                        }
                                        result.onSuccess { savedName ->
                                            savedMismatchApkName = savedName
                                        }.onFailure { err ->
                                            onShowMessage(err.localizedMessage ?: "Не удалось сохранить APK")
                                        }
                                    }
                                },
                                modifier = Modifier.testTag("save_apk_downloads_button")
                            ) {
                                Text("В Загрузки")
                            }
                        }

                        else -> {
                            Button(
                                onClick = { triggerApkInstall(updateState.apkFile) },
                                modifier = Modifier.testTag("install_apk_button")
                            ) {
                                Text("Установить")
                            }
                        }
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (savedMismatchApkName == null &&
                            (compatibility is ApkInstallCompatibility.SignatureMismatch ||
                                compatibility is ApkInstallCompatibility.VersionDowngrade)
                        ) {
                            TextButton(
                                onClick = {
                                    triggerApkInstall(
                                        file = updateState.apkFile,
                                        bypassCompatibilityCheck = true
                                    )
                                },
                                modifier = Modifier.testTag("force_install_apk_button")
                            ) {
                                Text("Установить всё равно")
                            }
                        }
                        TextButton(onClick = closeDialog) {
                            Text(if (savedMismatchApkName != null) "Готово" else "Закрыть")
                        }
                    }
                }
            )
        }

        else -> Unit
    }

    if (showEqualizerSheet) {
        ModalBottomSheet(
            onDismissRequest = { showEqualizerSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            EqualizerSheetContent(
                state = uiState.equalizerState,
                onToggleEnabled = viewModel::setEqualizerEnabled,
                onSelectPreset = viewModel::selectEqualizerPreset,
                onBandChange = viewModel::setEqualizerBand
            )
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 10.dp,
            bottom = bottomContentPadding.calculateBottomPadding() + 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = "Настройки",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        // Section: АККАУНТ
        item {
            CategoryHeader(title = "АККАУНТ")
            YouTubeAccountSettingsCard(
                uiState = accountUiState,
                onOpenLogin = onOpenYouTubeLogin,
                onSwitchAccount = { id -> accountViewModel.switchActiveAccount(id, onShowMessage) },
                onRemoveAccount = { id -> accountViewModel.removeAccount(id, onShowMessage) },
                onToggleAutoSync = accountViewModel::setAutoSyncEnabled,
                onSyncNow = { accountViewModel.syncAndMergeActiveAccount(onShowMessage) },
                onUnifyAllAccounts = { accountViewModel.unifyAllAccountsIntoActive(onShowMessage) },
                onImportCookies = { cookies, onDone ->
                    accountViewModel.signInWithSession(
                        cookieHeader = cookies,
                        onSuccess = { acc ->
                            onDone()
                            onShowMessage("Подключён аккаунт: ${acc.name}")
                        },
                        onError = onShowMessage
                    )
                },
                onSignOutAll = { accountViewModel.signOutAll(onShowMessage) }
            )
        }

        // Section: СИНХРОНИЗАЦИЯ
        item {
            CategoryHeader(title = "СИНХРОНИЗАЦИЯ")
            GroupedSettingsCard {
                SettingsRowItem(
                    icon = SonoraIcons.HighQuality,
                    title = "Качество кэша",
                    subtitle = "Максимальное качество для офлайн-режима",
                    value = when (uiState.settings.audioQuality) {
                        AudioQuality.DATA_SAVER -> "64 кб/с >"
                        AudioQuality.BALANCED -> "128 кб/с >"
                        AudioQuality.HIGH -> "320 кб/с >"
                    },
                    onClick = {
                        if (onNavigateToCacheQuality != null) {
                            onNavigateToCacheQuality()
                        } else {
                            val next = when (uiState.settings.audioQuality) {
                                AudioQuality.DATA_SAVER -> AudioQuality.BALANCED
                                AudioQuality.BALANCED -> AudioQuality.HIGH
                                AudioQuality.HIGH -> AudioQuality.DATA_SAVER
                            }
                            viewModel.setAudioQuality(next)
                        }
                    }
                )

                SettingsRowItem(
                    icon = SonoraIcons.AutoMode,
                    title = "Умная очередь",
                    subtitle = "Бесконечный автоподбор треков радио",
                    checked = uiState.settings.autoplayEnabled,
                    onCheckedChange = viewModel::setAutoplayEnabled,
                    testTag = "toggle_autoplay"
                )

                SettingsRowItem(
                    icon = SonoraIcons.GraphicEq,
                    title = "Плавный переход (Gapless)",
                    subtitle = "Без паузы между треками альбома",
                    checked = uiState.settings.skipSilenceEnabled,
                    onCheckedChange = viewModel::setSkipSilenceEnabled,
                    testTag = "toggle_skip_silence"
                )
            }
        }

        // Section: ПРОФИЛЬ
        item {
            CategoryHeader(title = "ПРОФИЛЬ")
            GroupedSettingsCard {
                SettingsRowItem(
                    icon = SonoraIcons.Equalizer,
                    title = "5-полосный эквалайзер",
                    subtitle = "Настройка частот и баса",
                    value = if (uiState.equalizerState.isEnabled) "Прямую >" else "Выкл >",
                    onClick = {
                        if (onNavigateToEqualizer != null) {
                            onNavigateToEqualizer()
                        } else {
                            showEqualizerSheet = true
                        }
                    }
                )

                SettingsRowItem(
                    icon = SonoraIcons.FileUpload,
                    title = "Резервная копия JSON",
                    subtitle = "Сохранить медиатеку в файл",
                    value = "Экспорт >",
                    onClick = { exportLauncher.launch("sonora_library_backup.json") }
                )

                SettingsRowItem(
                    icon = SonoraIcons.CloudDownload,
                    title = "Версия приложения",
                    subtitle = "Проверка обновлений Sonora",
                    value = "v${BuildConfig.VERSION_NAME} >",
                    onClick = {
                        if (onNavigateToAppUpdate != null) {
                            onNavigateToAppUpdate()
                        } else {
                            viewModel.checkForUpdates(onShowMessage)
                        }
                    }
                )
            }
        }

        // Section: ВОСПРОИЗВЕДЕНИЕ
        item {
            CategoryHeader(title = "ВОСПРОИЗВЕДЕНИЕ")
            GroupedSettingsCard {
                val activeAccountName = accountUiState.activeAccount?.name ?: "YouTube Music"
                SettingsRowItem(
                    icon = SonoraIcons.AccountCircle,
                    title = "YouTube Music",
                    subtitle = "Аккаунт $activeAccountName",
                    value = "Синхронизировано >",
                    onClick = {
                        if (onNavigateToYouTubeAccount != null) {
                            onNavigateToYouTubeAccount()
                        } else {
                            onOpenYouTubeLogin(false)
                        }
                    }
                )

                SettingsRowItem(
                    icon = SonoraIcons.Sync,
                    title = "Автосинхронизация плейлистов",
                    subtitle = "Обновлять избранное при запуске",
                    checked = accountUiState.autoSyncEnabled,
                    onCheckedChange = accountViewModel::setAutoSyncEnabled,
                    testTag = "toggle_youtube_autosync"
                )

                SettingsRowItem(
                    icon = SonoraIcons.History,
                    title = "Импорт истории прослушивания",
                    subtitle = "Синхронизация с серверами Google",
                    value = "22 ноя, 14:30 >",
                    onClick = { accountViewModel.syncAndMergeActiveAccount(onShowMessage) }
                )
            }
        }

        // Section: АУДИО
        item {
            CategoryHeader(title = "АУДИО")
            GroupedSettingsCard {
                val mb = String.format(Locale.US, "%.1f МБ", uiState.cacheSizeBytes / (1024f * 1024f))
                SettingsRowItem(
                    icon = SonoraIcons.CleaningServices,
                    title = "Кэш аудиопотоков",
                    subtitle = "Занято $mb памяти",
                    value = "Очистить >",
                    onClick = { viewModel.clearCache { onShowMessage("Кэш аудио очищен") } }
                )

                SettingsRowItem(
                    icon = SonoraIcons.FileDownload,
                    title = "Импорт JSON",
                    subtitle = "Восстановить медиатеку из файла",
                    value = "Импорт >",
                    onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }
                )
            }
        }
    }
}

@Composable
private fun CategoryHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
    )
}

@Composable
private fun GroupedSettingsCard(
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(vertical = 4.dp),
            content = content
        )
    }
}

@Composable
private fun SettingsRowItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    value: String? = null,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    testTag: String = ""
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null || onCheckedChange != null) {
                if (onCheckedChange != null && checked != null) {
                    onCheckedChange(!checked)
                } else {
                    onClick?.invoke()
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Icon in Dark Circle Container matching photo
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        if (checked != null && onCheckedChange != null) {
            // Bright Neon Green Switch matching reference photo
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                modifier = Modifier.testTag(testTag)
            )
        } else if (!value.isNullOrBlank()) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqualizerSheetContent(
    state: EqualizerState,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectPreset: (Int) -> Unit,
    onBandChange: (Int, Short) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Эквалайзер", style = MaterialTheme.typography.titleLarge)
            Switch(
                checked = state.isEnabled,
                onCheckedChange = onToggleEnabled,
                enabled = state.isAvailable
            )
        }

        if (!state.isAvailable) {
            Text(
                text = "Запустите любой трек, чтобы подключить аудиосессию к эквалайзеру.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            if (state.presetNames.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    state.presetNames.forEachIndexed { index, name ->
                        FilterChip(
                            selected = index == state.selectedPresetIndex,
                            onClick = { onSelectPreset(index) },
                            label = { Text(name) }
                        )
                    }
                }
            }

            state.bandLevels.forEachIndexed { index, levelMb ->
                val hz = state.bandFrequenciesHz.getOrElse(index) { 0 }
                val freqLabel = if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz"
                val db = levelMb / 100f
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(freqLabel, style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = String.format(Locale.US, "%+.1f dB", db),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = levelMb.toFloat(),
                        onValueChange = { newValue ->
                            onBandChange(index, newValue.toInt().toShort())
                        },
                        valueRange = state.minLevelMb.toFloat()..state.maxLevelMb.toFloat(),
                        enabled = state.isEnabled
                    )
                }
            }
        }
    }
}
