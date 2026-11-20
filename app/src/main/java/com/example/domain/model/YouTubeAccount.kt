package com.example.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class YouTubeAccount(
    val id: String,
    val name: String,
    val handle: String = "",
    val email: String = "",
    val avatarUrl: String = "",
    val cookieHeader: String,
    val visitorData: String = "",
    val dataSyncId: String = "",
    val authUserIndex: Int = 0,
    val lastSyncTimestamp: Long = 0L,
    val syncedLikedCount: Int = 0,
    val syncedPlaylistsCount: Int = 0
)

@Immutable
data class AccountSyncSummary(
    val importedLiked: Int = 0,
    val uploadedLiked: Int = 0,
    val importedPlaylists: Int = 0,
    val uploadedPlaylists: Int = 0,
    val importedHistory: Int = 0,
    val mergedAccountsCount: Int = 1,
    val syncedAt: Long = System.currentTimeMillis()
)

sealed interface AccountSyncState {
    data object Idle : AccountSyncState
    data class Syncing(val stage: String, val progress: Float) : AccountSyncState
    data class Completed(val summary: AccountSyncSummary) : AccountSyncState
    data class Error(val message: String) : AccountSyncState
}
