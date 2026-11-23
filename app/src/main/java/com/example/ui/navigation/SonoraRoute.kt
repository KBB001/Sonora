package com.example.ui.navigation

import kotlinx.serialization.Serializable

sealed interface SonoraRoute {
    @Serializable
    data object Home : SonoraRoute

    @Serializable
    data object Search : SonoraRoute

    @Serializable
    data object Library : SonoraRoute

    @Serializable
    data object Settings : SonoraRoute

    @Serializable
    data class PlaylistDetail(val playlistId: Long) : SonoraRoute

    @Serializable
    data object NowPlaying : SonoraRoute

    @Serializable
    data class YouTubeLogin(val resetSession: Boolean = false) : SonoraRoute

    @Serializable
    data object AppUpdate : SonoraRoute

    @Serializable
    data object Equalizer : SonoraRoute

    @Serializable
    data object CacheQuality : SonoraRoute

    @Serializable
    data object YouTubeAccount : SonoraRoute

    @Serializable
    data object SignOutConfirm : SonoraRoute
}
