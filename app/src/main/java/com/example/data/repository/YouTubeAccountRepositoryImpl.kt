package com.example.data.repository

import com.example.data.remote.YouTubeMusicAccountApi
import com.example.domain.model.AccountSyncState
import com.example.domain.model.AccountSyncSummary
import com.example.domain.model.Track
import com.example.domain.model.YouTubeAccount
import com.example.domain.repository.LibraryRepository
import com.example.domain.repository.YouTubeAccountRepository
import com.example.domain.repository.YouTubeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeAccountRepositoryImpl @Inject constructor(
    private val accountStore: YouTubeAccountStore,
    private val api: YouTubeMusicAccountApi,
    private val libraryRepository: LibraryRepository,
    private val youTubeRepository: YouTubeRepository
) : YouTubeAccountRepository {

    private val syncMutex = Mutex()
    private val _syncState = MutableStateFlow<AccountSyncState>(AccountSyncState.Idle)

    override val accounts: Flow<List<YouTubeAccount>> = accountStore.accounts
    override val activeAccount: Flow<YouTubeAccount?> = accountStore.activeAccount
    override val autoSyncEnabled: Flow<Boolean> = accountStore.autoSyncEnabled
    override val syncState: StateFlow<AccountSyncState> = _syncState.asStateFlow()

    override suspend fun signInWithSession(
        cookieHeader: String,
        visitorData: String,
        dataSyncId: String,
        authUserIndex: Int
    ): Result<YouTubeAccount> = withContext(Dispatchers.IO) {
        val normalizedCookie = YouTubeMusicAccountApi.normalizeCookieHeader(cookieHeader)
        if (!YouTubeMusicAccountApi.hasAuthCookies(normalizedCookie)) {
            return@withContext Result.failure(
                IllegalArgumentException("Не удалось найти сессию YouTube в cookie. Убедитесь, что вы вошли в аккаунт.")
            )
        }
        api.fetchAccountInfo(
            cookieHeader = normalizedCookie,
            visitorData = visitorData,
            dataSyncId = dataSyncId,
            authUserIndex = authUserIndex
        ).map { profile ->
            val account = YouTubeAccount(
                id = profile.id,
                name = profile.name,
                handle = profile.handle,
                email = profile.email,
                avatarUrl = profile.avatarUrl,
                cookieHeader = normalizedCookie,
                visitorData = visitorData,
                dataSyncId = dataSyncId,
                authUserIndex = authUserIndex
            )
            accountStore.upsertAndActivateAccount(account)
            account
        }
    }

    override suspend fun switchActiveAccount(accountId: String) {
        accountStore.setActiveAccount(accountId)
    }

    override suspend fun removeAccount(accountId: String) {
        accountStore.removeAccount(accountId)
    }

    override suspend fun signOutAll() {
        accountStore.clearAllAccounts()
        _syncState.value = AccountSyncState.Idle
    }

    override suspend fun setAutoSyncEnabled(enabled: Boolean) {
        accountStore.setAutoSyncEnabled(enabled)
    }

    override suspend fun refreshActiveAccountProfile(): Result<YouTubeAccount> =
        withContext(Dispatchers.IO) {
            val active = accountStore.getActiveAccountSnapshot()
                ?: accountStore.activeAccount.first()
                ?: return@withContext Result.failure(IllegalStateException("Нет активного аккаунта YouTube"))

            api.fetchAccountInfo(
                cookieHeader = active.cookieHeader,
                visitorData = active.visitorData,
                dataSyncId = active.dataSyncId,
                authUserIndex = active.authUserIndex
            ).map { profile ->
                val updated = active.copy(
                    name = profile.name,
                    handle = profile.handle,
                    email = profile.email,
                    avatarUrl = profile.avatarUrl
                )
                accountStore.upsertAndActivateAccount(updated)
                updated
            }
        }

    override suspend fun syncAndMergeActiveAccount(): Result<AccountSyncSummary> =
        syncMutex.withLock {
            withContext(Dispatchers.IO) {
                val active = accountStore.getActiveAccountSnapshot()
                    ?: accountStore.activeAccount.first()
                    ?: return@withContext Result.failure(
                        IllegalStateException("Сначала войдите в аккаунт YouTube")
                    )

                try {
                    val pullCounts = pullAccountIntoLocal(
                        account = active,
                        progressPrefix = "",
                        progressStart = 0.05f,
                        progressSpan = 0.50f
                    )
                    val pushCounts = pushLocalIntoAccount(
                        account = active,
                        remoteLikedIds = pullCounts.remoteLikedIds,
                        remotePlaylistsById = pullCounts.remotePlaylistsById
                    )

                    val now = System.currentTimeMillis()
                    val finalFavorites = libraryRepository.observeFavorites().first()
                    val finalPlaylists = libraryRepository.observePlaylists().first()
                    accountStore.updateAccountStats(
                        accountId = active.id,
                        likedCount = finalFavorites.size,
                        playlistsCount = finalPlaylists.size,
                        syncTimestamp = now
                    )

                    val summary = AccountSyncSummary(
                        importedLiked = pullCounts.importedLiked,
                        uploadedLiked = pushCounts.uploadedLiked,
                        importedPlaylists = pullCounts.importedPlaylists,
                        uploadedPlaylists = pushCounts.uploadedPlaylists,
                        importedHistory = pullCounts.importedHistory,
                        mergedAccountsCount = 1,
                        syncedAt = now
                    )
                    _syncState.value = AccountSyncState.Completed(summary)
                    Result.success(summary)
                } catch (e: CancellationException) {
                    _syncState.value = AccountSyncState.Idle
                    throw e
                } catch (e: Exception) {
                    val msg = e.localizedMessage ?: "Ошибка синхронизации с YouTube"
                    _syncState.value = AccountSyncState.Error(msg)
                    Result.failure(e)
                }
            }
        }

    override suspend fun unifyAllAccountsIntoActive(): Result<AccountSyncSummary> =
        syncMutex.withLock {
            withContext(Dispatchers.IO) {
                val allAccounts = accountStore.getAllAccountsSnapshot()
                if (allAccounts.isEmpty()) {
                    return@withContext Result.failure(
                        IllegalStateException("Нет подключённых аккаунтов YouTube")
                    )
                }
                val primary = accountStore.getActiveAccountSnapshot()
                    ?: accountStore.activeAccount.first()
                    ?: allAccounts.first()

                try {
                    var totalImportedLiked = 0
                    var totalImportedPlaylists = 0
                    var totalImportedHistory = 0

                    val secondaryAccounts = allAccounts.filter { it.id != primary.id }
                    val pullStep = 0.55f / allAccounts.size.coerceAtLeast(1)

                    // 1. Pull music data from all secondary accounts into Sonora's unified local DB
                    secondaryAccounts.forEachIndexed { index, secondary ->
                        val prefix = "${secondary.name}: "
                        val counts = pullAccountIntoLocal(
                            account = secondary,
                            progressPrefix = prefix,
                            progressStart = index * pullStep,
                            progressSpan = pullStep
                        )
                        totalImportedLiked += counts.importedLiked
                        totalImportedPlaylists += counts.importedPlaylists
                        totalImportedHistory += counts.importedHistory
                    }

                    // 2. Pull primary account as well so we know what's already on it before pushing
                    val primaryStart = secondaryAccounts.size * pullStep
                    val primaryPull = pullAccountIntoLocal(
                        account = primary,
                        progressPrefix = "${primary.name}: ",
                        progressStart = primaryStart,
                        progressSpan = pullStep
                    )
                    totalImportedLiked += primaryPull.importedLiked
                    totalImportedPlaylists += primaryPull.importedPlaylists
                    totalImportedHistory += primaryPull.importedHistory

                    // 3. Push the unified Sonora library up to the primary YouTube account
                    val pushCounts = pushLocalIntoAccount(
                        account = primary,
                        remoteLikedIds = primaryPull.remoteLikedIds,
                        remotePlaylistsById = primaryPull.remotePlaylistsById
                    )

                    val now = System.currentTimeMillis()
                    val finalFavorites = libraryRepository.observeFavorites().first()
                    val finalPlaylists = libraryRepository.observePlaylists().first()
                    accountStore.updateAccountStats(
                        accountId = primary.id,
                        likedCount = finalFavorites.size,
                        playlistsCount = finalPlaylists.size,
                        syncTimestamp = now
                    )

                    val summary = AccountSyncSummary(
                        importedLiked = totalImportedLiked,
                        uploadedLiked = pushCounts.uploadedLiked,
                        importedPlaylists = totalImportedPlaylists,
                        uploadedPlaylists = pushCounts.uploadedPlaylists,
                        importedHistory = totalImportedHistory,
                        mergedAccountsCount = allAccounts.size,
                        syncedAt = now
                    )
                    _syncState.value = AccountSyncState.Completed(summary)
                    Result.success(summary)
                } catch (e: CancellationException) {
                    _syncState.value = AccountSyncState.Idle
                    throw e
                } catch (e: Exception) {
                    val msg = e.localizedMessage ?: "Не удалось объединить аккаунты"
                    _syncState.value = AccountSyncState.Error(msg)
                    Result.failure(e)
                }
            }
        }

    override fun clearSyncState() {
        _syncState.value = AccountSyncState.Idle
    }

    private suspend fun pullAccountIntoLocal(
        account: YouTubeAccount,
        progressPrefix: String,
        progressStart: Float,
        progressSpan: Float
    ): PullResult {
        _syncState.value = AccountSyncState.Syncing(
            stage = "${progressPrefix}Загрузка понравившихся треков...",
            progress = progressStart + progressSpan * 0.15f
        )
        val likedResult = api.fetchLikedTracks(account)
        val likedTracks = likedResult.getOrDefault(emptyList())
        val importedLiked = if (likedTracks.isNotEmpty()) {
            libraryRepository.mergeFavoriteTracks(likedTracks)
        } else {
            0
        }

        _syncState.value = AccountSyncState.Syncing(
            stage = "${progressPrefix}Загрузка истории прослушиваний...",
            progress = progressStart + progressSpan * 0.35f
        )
        val historyResult = api.fetchHistoryTracks(account)
        val historyTracks = historyResult.getOrDefault(emptyList())
        val importedHistory = if (historyTracks.isNotEmpty()) {
            libraryRepository.mergeHistoryTracks(historyTracks)
        } else {
            0
        }

        _syncState.value = AccountSyncState.Syncing(
            stage = "${progressPrefix}Импорт плейлистов YouTube...",
            progress = progressStart + progressSpan * 0.55f
        )
        val playlistsResult = api.fetchUserPlaylists(account)
        if (likedResult.isFailure && historyResult.isFailure && playlistsResult.isFailure) {
            throw (likedResult.exceptionOrNull()
                ?: playlistsResult.exceptionOrNull()
                ?: historyResult.exceptionOrNull()
                ?: IllegalStateException("Не удалось загрузить данные YouTube"))
        }
        val remotePlaylists = playlistsResult.getOrDefault(emptyList())
        var importedPlaylistsCount = 0
        val remotePlaylistsMap = LinkedHashMap<String, List<String>>()

        remotePlaylists.forEachIndexed { index, ytPlaylist ->
            val pct = progressStart + progressSpan * (0.55f + 0.45f * (index + 1) / remotePlaylists.size.coerceAtLeast(1))
            _syncState.value = AccountSyncState.Syncing(
                stage = "${progressPrefix}Плейлист «${ytPlaylist.title}»",
                progress = pct
            )
            val tracks = api.fetchPlaylistTracks(account, ytPlaylist.playlistId)
                .getOrElse {
                    youTubeRepository.getPlaylistTracks(
                        "https://www.youtube.com/playlist?list=${ytPlaylist.playlistId}"
                    ).getOrDefault(emptyList())
                }
            val (_, addedTracks) = libraryRepository.mergePlaylist(
                name = ytPlaylist.title,
                description = "Синхронизировано с YouTube",
                youtubePlaylistId = ytPlaylist.playlistId,
                tracks = tracks
            )
            if (addedTracks > 0 || tracks.isEmpty()) {
                importedPlaylistsCount++
            }
            remotePlaylistsMap[ytPlaylist.playlistId] = tracks.map { it.id }
        }

        return PullResult(
            importedLiked = importedLiked,
            importedHistory = importedHistory,
            importedPlaylists = importedPlaylistsCount,
            remoteLikedIds = likedTracks.map { it.id }.toSet(),
            remotePlaylistsById = remotePlaylistsMap
        )
    }

    private suspend fun pushLocalIntoAccount(
        account: YouTubeAccount,
        remoteLikedIds: Set<String>,
        remotePlaylistsById: Map<String, List<String>>
    ): PushResult {
        _syncState.value = AccountSyncState.Syncing(
            stage = "Сохранение локального избранного на YouTube...",
            progress = 0.65f
        )
        val localFavorites = libraryRepository.observeFavorites().first()
        val toUploadLikes = localFavorites
            .filter { it.id !in remoteLikedIds }
            .take(MAX_LIKES_UPLOAD_PER_SYNC)

        var uploadedLikesCount = 0
        toUploadLikes.forEachIndexed { idx, track ->
            if (api.setLikeStatus(account, track.id, liked = true).isSuccess) {
                uploadedLikesCount++
            }
            if (idx < toUploadLikes.lastIndex) delay(120L)
        }

        _syncState.value = AccountSyncState.Syncing(
            stage = "Сохранение плейлистов на аккаунт YouTube...",
            progress = 0.82f
        )
        val localPlaylists = libraryRepository.getAllPlaylistsWithTracks()
        var uploadedPlaylistsCount = 0

        localPlaylists.forEachIndexed { idx, (playlist, tracks) ->
            val existingRemoteId = playlist.youtubePlaylistId
            val videoIds = tracks.map { it.id }
            if (existingRemoteId.isNullOrBlank()) {
                if (videoIds.isNotEmpty()) {
                    api.createPlaylist(
                        account = account,
                        title = playlist.name,
                        description = playlist.description,
                        videoIds = videoIds
                    ).onSuccess { newRemoteId ->
                        libraryRepository.updatePlaylistYoutubeId(playlist.id, newRemoteId)
                        uploadedPlaylistsCount++
                    }
                }
            } else {
                val alreadyOnRemote = remotePlaylistsById[existingRemoteId].orEmpty().toSet()
                val missingIds = videoIds.filter { it !in alreadyOnRemote }
                if (missingIds.isNotEmpty()) {
                    api.addTracksToPlaylist(
                        account = account,
                        playlistId = existingRemoteId,
                        videoIds = missingIds
                    ).onSuccess {
                        uploadedPlaylistsCount++
                    }
                }
            }
            val pct = 0.82f + 0.16f * (idx + 1) / localPlaylists.size.coerceAtLeast(1)
            _syncState.value = AccountSyncState.Syncing(
                stage = "Объединение плейлистов...",
                progress = pct
            )
        }

        return PushResult(
            uploadedLiked = uploadedLikesCount,
            uploadedPlaylists = uploadedPlaylistsCount
        )
    }

    private data class PullResult(
        val importedLiked: Int,
        val importedHistory: Int,
        val importedPlaylists: Int,
        val remoteLikedIds: Set<String>,
        val remotePlaylistsById: Map<String, List<String>>
    )

    private data class PushResult(
        val uploadedLiked: Int,
        val uploadedPlaylists: Int
    )

    private companion object {
        const val MAX_LIKES_UPLOAD_PER_SYNC = 40
    }
}
