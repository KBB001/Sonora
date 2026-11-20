package com.example.domain.repository

import com.example.domain.model.AccountSyncState
import com.example.domain.model.AccountSyncSummary
import com.example.domain.model.YouTubeAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface YouTubeAccountRepository {
    val accounts: Flow<List<YouTubeAccount>>
    val activeAccount: Flow<YouTubeAccount?>
    val autoSyncEnabled: Flow<Boolean>
    val syncState: StateFlow<AccountSyncState>

    suspend fun signInWithSession(
        cookieHeader: String,
        visitorData: String = "",
        dataSyncId: String = "",
        authUserIndex: Int = 0
    ): Result<YouTubeAccount>

    suspend fun switchActiveAccount(accountId: String)
    suspend fun removeAccount(accountId: String)
    suspend fun signOutAll()
    suspend fun setAutoSyncEnabled(enabled: Boolean)
    suspend fun refreshActiveAccountProfile(): Result<YouTubeAccount>
    suspend fun syncAndMergeActiveAccount(): Result<AccountSyncSummary>
    suspend fun unifyAllAccountsIntoActive(): Result<AccountSyncSummary>
    fun clearSyncState()
}
