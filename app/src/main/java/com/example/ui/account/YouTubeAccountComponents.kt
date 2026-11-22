package com.example.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.domain.model.AccountSyncState
import com.example.domain.model.YouTubeAccount
import com.example.ui.components.SonoraIcons
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun YouTubeAccountSettingsCard(
    uiState: YouTubeAccountUiState,
    onOpenLogin: (addAnother: Boolean) -> Unit,
    onSwitchAccount: (String) -> Unit,
    onRemoveAccount: (String) -> Unit,
    onToggleAutoSync: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onUnifyAllAccounts: () -> Unit,
    onImportCookies: (String, () -> Unit) -> Unit,
    onSignOutAll: () -> Unit
) {
    var showCookieDialog by remember { mutableStateOf(false) }

    if (showCookieDialog) {
        CookieImportDialog(
            isLoading = uiState.isSigningIn,
            onDismiss = { showCookieDialog = false },
            onSubmit = { rawCookies ->
                onImportCookies(rawCookies) {
                    showCookieDialog = false
                }
            }
        )
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = SonoraIcons.AccountCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Аккаунт YouTube и объединение",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            val active = uiState.activeAccount
            val isSyncing = uiState.syncState is AccountSyncState.Syncing

            if (active == null) {
                Text(
                    text = "Войдите в свой аккаунт YouTube, чтобы сохранять понравившиеся треки и плейлисты прямо в облаке YouTube и объединить музыку между аккаунтами.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = { onOpenLogin(false) },
                    enabled = !uiState.isSigningIn,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("youtube_sign_in_button")
                ) {
                    Icon(SonoraIcons.AccountCircle, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Войти в аккаунт YouTube")
                }

                TextButton(
                    onClick = { showCookieDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("youtube_cookie_login_button")
                ) {
                    Icon(
                        imageVector = SonoraIcons.Key,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Войти через Cookie браузера")
                }
            } else {
                // Active account header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AsyncImage(
                        model = active.avatarUrl.ifBlank { null },
                        contentDescription = "Аватар ${active.name}",
                        placeholder = painterResource(R.drawable.img_default_cover),
                        error = painterResource(R.drawable.img_default_cover),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = active.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val subtitle = listOf(active.handle, active.email)
                            .filter { it.isNotBlank() }
                            .joinToString(" • ")
                        if (subtitle.isNotBlank()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        val syncTimeText = remember(active.lastSyncTimestamp) {
                            if (active.lastSyncTimestamp > 0L) {
                                val fmt = DateFormat.getDateTimeInstance(
                                    DateFormat.SHORT,
                                    DateFormat.SHORT,
                                    Locale.getDefault()
                                )
                                "Синхр.: ${fmt.format(Date(active.lastSyncTimestamp))}"
                            } else {
                                "Ещё не синхронизирован"
                            }
                        }
                        Text(
                            text = syncTimeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Multi-account switcher / unification chips
                if (uiState.accounts.size > 1) {
                    Text(
                        text = "Подключённые аккаунты (${uiState.accounts.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        uiState.accounts.forEach { account ->
                            val isSelected = account.id == active.id
                            FilterChip(
                                selected = isSelected,
                                onClick = { if (!isSelected) onSwitchAccount(account.id) },
                                label = {
                                    Text(
                                        text = account.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                trailingIcon = if (!isSelected) {
                                    {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(CircleShape)
                                                .clickable { onRemoveAccount(account.id) }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Close,
                                                contentDescription = "Удалить ${account.name}",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                } else {
                                    null
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                }

                // Live sync progress or summary status
                when (val syncState = uiState.syncState) {
                    is AccountSyncState.Syncing -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LinearProgressIndicator(
                                progress = { syncState.progress.coerceIn(0.05f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = syncState.stage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    is AccountSyncState.Completed -> {
                        val s = syncState.summary
                        Text(
                            text = "Синхронизация завершена • +${s.importedLiked} лайков из YT, ↑${s.uploadedLiked} в YT, +${s.importedPlaylists} / ↑${s.uploadedPlaylists} плейлистов",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    is AccountSyncState.Error -> {
                        Text(
                            text = syncState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    AccountSyncState.Idle -> Unit
                }

                // Auto-sync toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onToggleAutoSync(!uiState.autoSyncEnabled) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Автосохранение на YouTube",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Мгновенно сохранять лайки и новые плейлисты в ваш аккаунт YouTube",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = uiState.autoSyncEnabled,
                        onCheckedChange = onToggleAutoSync,
                        modifier = Modifier.testTag("toggle_youtube_autosync")
                    )
                }

                // Primary sync button
                Button(
                    onClick = onSyncNow,
                    enabled = !isSyncing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("youtube_sync_merge_button")
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Синхронизация...")
                    } else {
                        Icon(SonoraIcons.Sync, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Синхронизировать с YouTube")
                    }
                }

                // Multi-account unification button when 2+ accounts are added
                if (uiState.accounts.size >= 2) {
                    Button(
                        onClick = onUnifyAllAccounts,
                        enabled = !isSyncing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("youtube_unify_all_accounts_button")
                    ) {
                        Icon(SonoraIcons.MergeType, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Объединить все аккаунты в «${active.name}»")
                    }
                }

                // Secondary actions: add account / sign out
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { onOpenLogin(true) },
                        enabled = !isSyncing,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("youtube_add_another_account_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Ещё аккаунт", maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = onSignOutAll,
                        enabled = !isSyncing,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("youtube_sign_out_button")
                    ) {
                        Icon(
                            imageVector = SonoraIcons.Logout,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Выйти", maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
fun LibraryAccountSyncBanner(
    uiState: YouTubeAccountUiState,
    onOpenLogin: () -> Unit,
    onSyncNow: () -> Unit
) {
    val active = uiState.activeAccount
    val isSyncing = uiState.syncState is AccountSyncState.Syncing

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (active != null) {
                    AsyncImage(
                        model = active.avatarUrl.ifBlank { null },
                        contentDescription = active.name,
                        placeholder = painterResource(R.drawable.img_default_cover),
                        error = painterResource(R.drawable.img_default_cover),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = active.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val statusText = when (val s = uiState.syncState) {
                            is AccountSyncState.Syncing -> s.stage
                            else -> if (uiState.accounts.size > 1) {
                                "Аккаунтов: ${uiState.accounts.size} • Нажмите для синхр."
                            } else {
                                "YouTube синхронизация активна"
                            }
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(
                        onClick = onSyncNow,
                        enabled = !isSyncing,
                        modifier = Modifier.testTag("library_sync_youtube_button")
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = SonoraIcons.Sync,
                                contentDescription = "Синхронизировать",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Синхр.")
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = SonoraIcons.AccountCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Аккаунт YouTube",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Войдите и объедините музыку с YouTube",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = onOpenLogin,
                        modifier = Modifier.testTag("library_connect_youtube_button")
                    ) {
                        Text("Войти")
                    }
                }
            }

            if (uiState.syncState is AccountSyncState.Syncing) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { uiState.syncState.progress.coerceIn(0.05f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun CookieImportDialog(
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var cookieInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Вход через Cookie YouTube") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Вставьте заголовок Cookie с music.youtube.com (должен содержать SAPISID и SID) или содержимое Netscape cookies.txt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = cookieInput,
                    onValueChange = { cookieInput = it },
                    label = { Text("Cookie / cookies.txt") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cookie_input_field")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(cookieInput) },
                enabled = cookieInput.isNotBlank() && !isLoading,
                modifier = Modifier.testTag("confirm_cookie_login_button")
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Подключить")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text("Отмена")
            }
        }
    )
}
