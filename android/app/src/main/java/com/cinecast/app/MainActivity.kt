package com.cinecast.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cinecast.app.music.ui.MusicHubScreen
import com.cinecast.app.music.ui.MusicPlayerScreen
import com.cinecast.app.music.viewmodel.MusicViewModel
import com.cinecast.app.novel.ui.NovelHubScreen
import com.cinecast.app.novel.ui.NovelReaderScreen
import com.cinecast.app.novel.viewmodel.NovelViewModel
import com.cinecast.app.ui.components.CastBottomSheet
import com.cinecast.app.ui.screens.*
import com.cinecast.app.ui.theme.CineCastTheme
import com.cinecast.app.ui.viewmodel.MainViewModel

private enum class MainTab(val label: String, val icon: ImageVector) {
    HOME("影视", Icons.Rounded.Explore),
    LIVE("直播", Icons.Rounded.LiveTv),
    NOVEL("阅读", Icons.AutoMirrored.Rounded.MenuBook),
    MUSIC("音乐", Icons.Rounded.LibraryMusic),
    LIBRARY("我的", Icons.Rounded.Person)
}

private sealed class ActiveOverlay {
    data object None : ActiveOverlay()
    data object Player : ActiveOverlay()
    data object NovelReader : ActiveOverlay()
    data object MusicPlayer : ActiveOverlay()
    data object VideoSources : ActiveOverlay()
    data class WebSniffer(val url: String, val title: String) : ActiveOverlay()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CineCastRoot()
        }
    }
}

@Composable
private fun CineCastRoot(
    viewModel: MainViewModel = viewModel(),
    novelViewModel: NovelViewModel = viewModel(),
    musicViewModel: MusicViewModel = viewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.toastMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
    }
    LaunchedEffect(Unit) {
        novelViewModel.toastMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
    }
    LaunchedEffect(Unit) {
        musicViewModel.toastMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
    }

    CineCastTheme(
        themeMode = settings.themeMode,
        dynamicColor = settings.dynamicColor
    ) {
        var currentTab by remember { mutableStateOf(MainTab.HOME) }
        var overlay by remember { mutableStateOf<ActiveOverlay>(ActiveOverlay.None) }
        var globalCastTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

        BackHandler(enabled = overlay != ActiveOverlay.None) {
            overlay = ActiveOverlay.None
        }

        when (val active = overlay) {
            is ActiveOverlay.Player -> {
                PlayerScreen(
                    viewModel = viewModel,
                    onBack = { overlay = ActiveOverlay.None }
                )
            }
            is ActiveOverlay.NovelReader -> {
                NovelReaderScreen(
                    viewModel = novelViewModel,
                    onBack = { overlay = ActiveOverlay.None }
                )
            }
            is ActiveOverlay.MusicPlayer -> {
                MusicPlayerScreen(
                    viewModel = musicViewModel,
                    onBack = { overlay = ActiveOverlay.None }
                )
            }
            is ActiveOverlay.VideoSources -> {
                SourcesScreen(
                    viewModel = viewModel,
                    onOpenWebSniffer = { url, title ->
                        overlay = ActiveOverlay.WebSniffer(url, title)
                    },
                    onBack = { overlay = ActiveOverlay.None }
                )
            }
            is ActiveOverlay.WebSniffer -> {
                WebSnifferScreen(
                    viewModel = viewModel,
                    initialUrl = active.url,
                    initialTitle = active.title,
                    onBack = { overlay = ActiveOverlay.None },
                    onPlaySniffedUrl = { title, url ->
                        musicViewModel.pauseAudio()
                        viewModel.openDirectStream(title, url, isLive = false)
                        overlay = ActiveOverlay.Player
                    }
                )
            }
            ActiveOverlay.None -> {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        NavigationBar {
                            MainTab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = currentTab == tab,
                                    onClick = { currentTab = tab },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label) }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = innerPadding.calculateBottomPadding())
                    ) {
                        when (currentTab) {
                            MainTab.HOME -> HomeScreen(
                                viewModel = viewModel,
                                onOpenVod = { srcId, vodId ->
                                    musicViewModel.pauseAudio()
                                    viewModel.openVodDetail(srcId, vodId)
                                    overlay = ActiveOverlay.Player
                                },
                                onOpenWebSniffer = { url, title ->
                                    overlay = ActiveOverlay.WebSniffer(url, title)
                                },
                                onOpenCastSheet = {
                                    globalCastTarget = "" to ""
                                },
                                onManageVideoSources = {
                                    overlay = ActiveOverlay.VideoSources
                                }
                            )
                            MainTab.LIVE -> LiveScreen(
                                viewModel = viewModel,
                                onPlayLiveChannel = { title, urls ->
                                    musicViewModel.pauseAudio()
                                    viewModel.openDirectStream(title, urls.first(), isLive = true, backupUrls = urls)
                                    overlay = ActiveOverlay.Player
                                },
                                onCastLiveChannel = { title, url ->
                                    globalCastTarget = title to url
                                }
                            )
                            MainTab.NOVEL -> NovelHubScreen(
                                viewModel = novelViewModel,
                                onOpenReader = { overlay = ActiveOverlay.NovelReader }
                            )
                            MainTab.MUSIC -> MusicHubScreen(
                                viewModel = musicViewModel,
                                onOpenFullPlayer = { overlay = ActiveOverlay.MusicPlayer }
                            )
                            MainTab.LIBRARY -> LibraryScreen(
                                viewModel = viewModel,
                                onOpenVod = { srcId, vodId ->
                                    musicViewModel.pauseAudio()
                                    viewModel.openVodDetail(srcId, vodId)
                                    overlay = ActiveOverlay.Player
                                },
                                onOpenVideoSources = {
                                    overlay = ActiveOverlay.VideoSources
                                }
                            )
                        }
                    }
                }
            }
        }

        globalCastTarget?.let { (title, url) ->
            CastBottomSheet(
                dlnaController = viewModel.dlnaController,
                mediaTitle = title,
                mediaUrl = url,
                onDismiss = { globalCastTarget = null }
            )
        }
    }
}
