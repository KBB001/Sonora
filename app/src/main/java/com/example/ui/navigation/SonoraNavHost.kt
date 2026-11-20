package com.example.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.ui.account.YouTubeLoginScreen
import com.example.ui.components.MiniPlayerBar
import com.example.ui.components.SonoraIcons
import com.example.ui.home.HomeScreen
import com.example.ui.library.LibraryScreen
import com.example.ui.library.PlaylistDetailScreen
import com.example.ui.player.NowPlayingScreen
import com.example.ui.player.PlayerViewModel
import com.example.ui.search.SearchScreen
import com.example.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

private data class TopLevelDestination(
    val route: SonoraRoute,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val topLevelDestinations = listOf(
    TopLevelDestination(
        route = SonoraRoute.Home,
        label = "Home",
        selectedIcon = Icons.Rounded.Home,
        unselectedIcon = Icons.Outlined.Home,
        testTag = "nav_home"
    ),
    TopLevelDestination(
        route = SonoraRoute.Search,
        label = "Search",
        selectedIcon = Icons.Rounded.Search,
        unselectedIcon = Icons.Outlined.Search,
        testTag = "nav_search"
    ),
    TopLevelDestination(
        route = SonoraRoute.Library,
        label = "Library",
        selectedIcon = SonoraIcons.LibraryMusic,
        unselectedIcon = SonoraIcons.LibraryMusicOutlined,
        testTag = "nav_library"
    ),
    TopLevelDestination(
        route = SonoraRoute.Settings,
        label = "Settings",
        selectedIcon = Icons.Rounded.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        testTag = "nav_settings"
    )
)

@Composable
fun SonoraNavHost(
    playerViewModel: PlayerViewModel = hiltViewModel()
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val isNowPlayingScreen = currentDestination?.hasRoute<SonoraRoute.NowPlaying>() == true
    val isFullScreenOverlay = isNowPlayingScreen ||
        currentDestination?.hasRoute<SonoraRoute.YouTubeLogin>() == true

    val playerUiState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val playbackState = playerUiState.playbackState
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(playbackState.errorMessage, isNowPlayingScreen) {
        val message = playbackState.errorMessage
        if (!message.isNullOrBlank() && !isNowPlayingScreen) {
            snackbarHostState.showSnackbar(message)
            playerViewModel.clearError()
        }
    }

    fun showMessage(message: String) {
        coroutineScope.launch {
            snackbarHostState.showSnackbar(message)
        }
    }

    fun navigateTopLevel(route: SonoraRoute) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 700.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.navigationBarsPadding()
                )
            },
            bottomBar = {
                if (!isFullScreenOverlay && !useNavigationRail) {
                    Column {
                        MiniPlayerBar(
                            playbackState = playbackState,
                            progressFlow = playerViewModel.playbackProgress,
                            onOpenNowPlaying = { navController.navigate(SonoraRoute.NowPlaying) },
                            onTogglePlayPause = playerViewModel::togglePlayPause,
                            onSkipNext = playerViewModel::skipNext
                        )
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 0.dp
                        ) {
                            topLevelDestinations.forEach { dest ->
                                val selected = currentDestination?.hasRoute(dest.route::class) == true
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateTopLevel(dest.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = dest.label
                                        )
                                    },
                                    label = { Text(dest.label, maxLines = 1) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    modifier = Modifier.testTag(dest.testTag)
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(modifier = Modifier.fillMaxSize()) {
                if (!isFullScreenOverlay && useNavigationRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxHeight()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                    ) {
                        topLevelDestinations.forEach { dest ->
                            val selected = currentDestination?.hasRoute(dest.route::class) == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTopLevel(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = dest.label
                                    )
                                },
                                label = { Text(dest.label) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                ),
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                ) {
                    AnimatedVisibility(visible = !playerUiState.isOnline && !isFullScreenOverlay) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .testTag("offline_banner"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = SonoraIcons.WifiOff,
                                contentDescription = "Offline",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Offline mode • Playing downloaded & cached audio",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                    ) {
                        val contentPadding = if (useNavigationRail) {
                            PaddingValues(
                                bottom = if (playbackState.currentTrack != null) 88.dp else 16.dp
                            )
                        } else {
                            innerPadding
                        }

                        val navModifier = when {
                            isFullScreenOverlay -> Modifier.fillMaxSize()
                            !playerUiState.isOnline -> Modifier.fillMaxSize()
                            else -> Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                        }

                        NavHost(
                            navController = navController,
                            startDestination = SonoraRoute.Home,
                            modifier = navModifier
                        ) {
                            composable<SonoraRoute.Home> {
                                HomeScreen(
                                    onNavigateToSearch = { navigateTopLevel(SonoraRoute.Search) },
                                    bottomContentPadding = contentPadding,
                                    onShowMessage = ::showMessage
                                )
                            }

                            composable<SonoraRoute.Search> {
                                SearchScreen(
                                    bottomContentPadding = contentPadding,
                                    onShowMessage = ::showMessage
                                )
                            }

                            composable<SonoraRoute.Library> {
                                LibraryScreen(
                                    onOpenPlaylist = { playlistId ->
                                        navController.navigate(SonoraRoute.PlaylistDetail(playlistId))
                                    },
                                    onOpenYouTubeLogin = {
                                        navController.navigate(SonoraRoute.YouTubeLogin(resetSession = false))
                                    },
                                    bottomContentPadding = contentPadding,
                                    onShowMessage = ::showMessage
                                )
                            }

                            composable<SonoraRoute.Settings> {
                                SettingsScreen(
                                    onOpenYouTubeLogin = { resetSession ->
                                        navController.navigate(SonoraRoute.YouTubeLogin(resetSession = resetSession))
                                    },
                                    bottomContentPadding = contentPadding,
                                    onShowMessage = ::showMessage
                                )
                            }

                            composable<SonoraRoute.PlaylistDetail> {
                                PlaylistDetailScreen(
                                    onBack = { navController.popBackStack() },
                                    bottomContentPadding = contentPadding,
                                    onShowMessage = ::showMessage
                                )
                            }

                            composable<SonoraRoute.NowPlaying> {
                                NowPlayingScreen(
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable<SonoraRoute.YouTubeLogin> { entry ->
                                val route = entry.toRoute<SonoraRoute.YouTubeLogin>()
                                YouTubeLoginScreen(
                                    resetSession = route.resetSession,
                                    onBack = { navController.popBackStack() },
                                    onLoginSuccess = { message ->
                                        showMessage(message)
                                        navController.popBackStack()
                                    },
                                    onShowMessage = ::showMessage
                                )
                            }
                        }

                        if (!isFullScreenOverlay && useNavigationRail) {
                            MiniPlayerBar(
                                playbackState = playbackState,
                                progressFlow = playerViewModel.playbackProgress,
                                onOpenNowPlaying = { navController.navigate(SonoraRoute.NowPlaying) },
                                onTogglePlayPause = playerViewModel::togglePlayPause,
                                onSkipNext = playerViewModel::skipNext,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                                    .padding(bottom = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
