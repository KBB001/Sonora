package com.example.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.YouTubeAccount
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.youtubeAccountDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "sonora_youtube_accounts"
)

@Singleton
class YouTubeAccountStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: SecureAccountCipher
) {
    private val jsonCodec = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cachedActiveAccount: YouTubeAccount? = null

    @Volatile
    private var cachedAutoSyncEnabled: Boolean = true

    val accounts: Flow<List<YouTubeAccount>> = context.youtubeAccountDataStore.data.map { prefs ->
        decodeAccounts(prefs[KEY_ACCOUNTS_JSON])
    }

    val activeAccount: Flow<YouTubeAccount?> = context.youtubeAccountDataStore.data.map { prefs ->
        val all = decodeAccounts(prefs[KEY_ACCOUNTS_JSON])
        val activeId = prefs[KEY_ACTIVE_ACCOUNT_ID]
        val resolved = all.firstOrNull { it.id == activeId } ?: all.firstOrNull()
        cachedActiveAccount = resolved
        resolved
    }

    val autoSyncEnabled: Flow<Boolean> = context.youtubeAccountDataStore.data.map { prefs ->
        val enabled = prefs[KEY_AUTO_SYNC_ENABLED] ?: true
        cachedAutoSyncEnabled = enabled
        enabled
    }

    init {
        storeScope.launch {
            runCatching {
                val prefs = context.youtubeAccountDataStore.data.first()
                val rawStored = prefs[KEY_ACCOUNTS_JSON]
                val all = decodeAccounts(rawStored)
                // Seamlessly migrate legacy unencrypted JSON to AES-GCM ciphertext
                if (!rawStored.isNullOrBlank() && !cipher.isEncryptedPayload(rawStored) && all.isNotEmpty()) {
                    context.youtubeAccountDataStore.edit { mutable ->
                        val latestRaw = mutable[KEY_ACCOUNTS_JSON]
                        if (!latestRaw.isNullOrBlank() && !cipher.isEncryptedPayload(latestRaw)) {
                            mutable[KEY_ACCOUNTS_JSON] = encodeAccounts(decodeAccounts(latestRaw))
                        }
                    }
                }
                val activeId = prefs[KEY_ACTIVE_ACCOUNT_ID]
                cachedActiveAccount = all.firstOrNull { it.id == activeId } ?: all.firstOrNull()
                cachedAutoSyncEnabled = prefs[KEY_AUTO_SYNC_ENABLED] ?: true
            }
        }
    }

    fun getActiveAccountSnapshot(): YouTubeAccount? = cachedActiveAccount

    fun isAutoSyncEnabledSnapshot(): Boolean = cachedAutoSyncEnabled

    suspend fun getAllAccountsSnapshot(): List<YouTubeAccount> = accounts.first()

    suspend fun upsertAndActivateAccount(account: YouTubeAccount) {
        context.youtubeAccountDataStore.edit { prefs ->
            val current = decodeAccounts(prefs[KEY_ACCOUNTS_JSON]).toMutableList()
            val existingIndex = current.indexOfFirst { it.id == account.id }
            if (existingIndex >= 0) {
                current[existingIndex] = account
            } else {
                current.add(0, account)
            }
            prefs[KEY_ACCOUNTS_JSON] = encodeAccounts(current)
            prefs[KEY_ACTIVE_ACCOUNT_ID] = account.id
            cachedActiveAccount = account
        }
    }

    suspend fun updateAccountStats(
        accountId: String,
        likedCount: Int,
        playlistsCount: Int,
        syncTimestamp: Long
    ) {
        context.youtubeAccountDataStore.edit { prefs ->
            val current = decodeAccounts(prefs[KEY_ACCOUNTS_JSON]).map { acc ->
                if (acc.id == accountId) {
                    acc.copy(
                        syncedLikedCount = likedCount,
                        syncedPlaylistsCount = playlistsCount,
                        lastSyncTimestamp = syncTimestamp
                    )
                } else {
                    acc
                }
            }
            prefs[KEY_ACCOUNTS_JSON] = encodeAccounts(current)
            val activeId = prefs[KEY_ACTIVE_ACCOUNT_ID]
            cachedActiveAccount = current.firstOrNull { it.id == activeId } ?: current.firstOrNull()
        }
    }

    suspend fun setActiveAccount(accountId: String) {
        context.youtubeAccountDataStore.edit { prefs ->
            val current = decodeAccounts(prefs[KEY_ACCOUNTS_JSON])
            val target = current.firstOrNull { it.id == accountId } ?: return@edit
            prefs[KEY_ACTIVE_ACCOUNT_ID] = target.id
            cachedActiveAccount = target
        }
    }

    suspend fun removeAccount(accountId: String): Boolean {
        var isEmptyAfterRemoval = false
        context.youtubeAccountDataStore.edit { prefs ->
            val remaining = decodeAccounts(prefs[KEY_ACCOUNTS_JSON]).filterNot { it.id == accountId }
            isEmptyAfterRemoval = remaining.isEmpty()
            if (remaining.isEmpty()) {
                prefs.remove(KEY_ACCOUNTS_JSON)
                prefs.remove(KEY_ACTIVE_ACCOUNT_ID)
                cachedActiveAccount = null
            } else {
                prefs[KEY_ACCOUNTS_JSON] = encodeAccounts(remaining)
                val currentActive = prefs[KEY_ACTIVE_ACCOUNT_ID]
                if (currentActive == accountId || remaining.none { it.id == currentActive }) {
                    val next = remaining.first()
                    prefs[KEY_ACTIVE_ACCOUNT_ID] = next.id
                    cachedActiveAccount = next
                }
            }
        }
        return isEmptyAfterRemoval
    }

    suspend fun clearAllAccounts() {
        context.youtubeAccountDataStore.edit { prefs ->
            prefs.remove(KEY_ACCOUNTS_JSON)
            prefs.remove(KEY_ACTIVE_ACCOUNT_ID)
            cachedActiveAccount = null
        }
    }

    suspend fun setAutoSyncEnabled(enabled: Boolean) {
        context.youtubeAccountDataStore.edit { prefs ->
            prefs[KEY_AUTO_SYNC_ENABLED] = enabled
            cachedAutoSyncEnabled = enabled
        }
    }

    private fun encodeAccounts(accounts: List<YouTubeAccount>): String {
        val rawJson = jsonCodec.encodeToString(accounts)
        return cipher.encrypt(rawJson)
    }

    private fun decodeAccounts(raw: String?): List<YouTubeAccount> {
        if (raw.isNullOrBlank()) return emptyList()
        val plaintextJson = if (cipher.isEncryptedPayload(raw)) {
            cipher.decrypt(raw) ?: return emptyList()
        } else {
            raw
        }
        return runCatching {
            jsonCodec.decodeFromString<List<YouTubeAccount>>(plaintextJson)
        }.getOrDefault(emptyList())
    }

    private companion object {
        val KEY_ACCOUNTS_JSON = stringPreferencesKey("accounts_json")
        val KEY_ACTIVE_ACCOUNT_ID = stringPreferencesKey("active_account_id")
        val KEY_AUTO_SYNC_ENABLED = booleanPreferencesKey("auto_sync_enabled")
    }
}
