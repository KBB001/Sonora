package com.example.ui.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
                        modifier = Modifier.testTag("confirm_download_update_button")
                    ) {
                        Text("Скачать и установить")
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
            top = 12.dp,
            bottom = bottomContentPadding.calculateBottomPadding() + 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Настройки",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        // YouTube Account & Unification
        item {
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

        // App Updates (GitHub Releases OTA)
        item {
            val isCheckingUpdate = uiState.updateState is AppUpdateUiState.Checking
            val signatureFingerprint = remember(context) {
                ApkInstaller.getInstalledSignatureFingerprint(context)
            }
            SettingsSectionCard(
                title = "Обновление приложения",
                icon = SonoraIcons.CloudDownload
            ) {
                Text(
                    text = "Текущая версия: v${BuildConfig.VERSION_NAME} (сборка ${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyLarge
                )
                val metaParts = buildList {
                    if (!BuildConfig.GITHUB_REPOSITORY.startsWith("YOUR_GITHUB_OWNER")) {
                        add("Репозиторий: ${BuildConfig.GITHUB_REPOSITORY}")
                    }
                    if (!signatureFingerprint.isNullOrBlank()) {
                        add("Ключ: $signatureFingerprint")
                    }
                }
                if (metaParts.isNotEmpty()) {
                    Text(
                        text = metaParts.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "Медиатека, история, настройки, эквалайзер и очередь сохраняются при обновлении",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { viewModel.checkForUpdates(onShowMessage) },
                    enabled = !isCheckingUpdate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("check_update_button")
                ) {
                    if (isCheckingUpdate) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Проверка обновлений...")
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Обновить приложение")
                    }
                }
            }
        }

        // Audio Quality
        item {
            SettingsSectionCard(
                title = "Качество звука",
                icon = SonoraIcons.HighQuality
            ) {
                AudioQuality.entries.forEach { quality ->
                    val qualityLabel = when (quality) {
                        AudioQuality.DATA_SAVER -> "Экономия трафика (~64 кбит/с)"
                        AudioQuality.BALANCED -> "Сбалансированное (~128 кбит/с)"
                        AudioQuality.HIGH -> "Высокое (максимальный поток)"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { viewModel.setAudioQuality(quality) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = uiState.settings.audioQuality == quality,
                            onClick = { viewModel.setAudioQuality(quality) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = qualityLabel,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }

        // Playback Features
        item {
            SettingsSectionCard(
                title = "Воспроизведение и аудио",
                icon = SonoraIcons.GraphicEq
            ) {
                SettingsToggleRow(
                    title = "Авторадио",
                    subtitle = "Автоматически подбирать похожие треки после окончания очереди",
                    icon = SonoraIcons.AutoMode,
                    checked = uiState.settings.autoplayEnabled,
                    onCheckedChange = viewModel::setAutoplayEnabled,
                    testTag = "toggle_autoplay"
                )

                SettingsToggleRow(
                    title = "Пропуск тишины",
                    subtitle = "Сокращать тихие паузы в треках и подкастах",
                    icon = SonoraIcons.GraphicEq,
                    checked = uiState.settings.skipSilenceEnabled,
                    onCheckedChange = viewModel::setSkipSilenceEnabled,
                    testTag = "toggle_skip_silence"
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { showEqualizerSheet = true }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = SonoraIcons.Equalizer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text("Эквалайзер", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = if (uiState.equalizerState.isAvailable) {
                                    if (uiState.equalizerState.isEnabled) "Активен" else "Нажмите для настройки частот"
                                } else {
                                    "Включите любой трек для активации эквалайзера"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    OutlinedButton(onClick = { showEqualizerSheet = true }) {
                        Text("Открыть")
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = SonoraIcons.Bedtime,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text("Таймер сна", style = MaterialTheme.typography.titleMedium)
                    uiState.playbackState.sleepTimerRemainingMs?.let { rem ->
                        Text(
                            text = "(осталось ${DurationFormatter.formatMillis(rem)})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val timerOptions = listOf(0 to "Выкл", 15 to "15 мин", 30 to "30 мин", 45 to "45 мин", 60 to "60 мин")
                    timerOptions.forEach { (mins, label) ->
                        val active = (mins == 0 && uiState.playbackState.sleepTimerRemainingMs == null)
                        FilterChip(
                            selected = active,
                            onClick = {
                                if (mins == 0) {
                                    viewModel.cancelSleepTimer()
                                    onShowMessage("Таймер сна отменён")
                                } else {
                                    viewModel.startSleepTimer(mins)
                                    onShowMessage("Таймер сна установлен на $mins мин")
                                }
                            },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }
            }
        }

        // Appearance
        item {
            SettingsSectionCard(
                title = "Оформление",
                icon = SonoraIcons.Palette
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppThemeMode.entries.forEach { mode ->
                        val themeLabel = when (mode) {
                            AppThemeMode.SYSTEM -> "Системная"
                            AppThemeMode.DARK -> "Тёмная"
                            AppThemeMode.LIGHT -> "Светлая"
                        }
                        FilterChip(
                            selected = uiState.settings.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            label = { Text(themeLabel) }
                        )
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SettingsToggleRow(
                        title = "Динамические цвета Material You",
                        subtitle = "Адаптировать палитру под обои системы Android",
                        icon = SonoraIcons.ColorLens,
                        checked = uiState.settings.dynamicColorEnabled,
                        onCheckedChange = viewModel::setDynamicColorEnabled,
                        testTag = "toggle_dynamic_color"
                    )
                }
            }
        }

        // Cache & Backup
        item {
            SettingsSectionCard(
                title = "Память и резервная копия",
                icon = SonoraIcons.CleaningServices
            ) {
                val mb = String.format(Locale.US, "%.1f МБ", uiState.cacheSizeBytes / (1024f * 1024f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Кэш аудиопотоков", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Занято $mb для быстрого повтора",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.clearCache { onShowMessage("Кэш аудио очищен") }
                        },
                        modifier = Modifier.testTag("clear_cache_button")
                    ) {
                        Text("Очистить")
                    }
                }

                Text(
                    text = "Резервная копия сохраняет избранное, историю, плейлисты, историю поиска, настройки, эквалайзер и очередь воспроизведения.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { exportLauncher.launch("sonora_library_backup.json") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("export_backup_button")
                    ) {
                        Icon(SonoraIcons.FileUpload, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Экспорт JSON")
                    }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("import_backup_button")
                    ) {
                        Icon(SonoraIcons.FileDownload, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Импорт JSON")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            content()
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
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
