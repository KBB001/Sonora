package com.example.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.data.local.dao.DownloadedTrackDao
import com.example.data.local.dao.FavoriteDao
import com.example.data.local.dao.HistoryDao
import com.example.data.local.dao.PlaylistDao
import com.example.data.local.dao.SearchHistoryDao
import com.example.data.local.dao.TrackSignalDao
import com.example.data.local.entity.DownloadedTrackEntity
import com.example.data.local.entity.FavoriteTrackEntity
import com.example.data.local.entity.HistoryTrackEntity
import com.example.data.local.entity.PlaylistEntity
import com.example.data.local.entity.PlaylistTrackEntity
import com.example.data.local.entity.SearchHistoryEntity
import com.example.data.local.entity.TrackAffinityEntity

@Database(
    entities = [
        FavoriteTrackEntity::class,
        HistoryTrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        DownloadedTrackEntity::class,
        SearchHistoryEntity::class,
        TrackAffinityEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class SonoraDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun historyDao(): HistoryDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun downloadedTrackDao(): DownloadedTrackDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun trackSignalDao(): TrackSignalDao
}
