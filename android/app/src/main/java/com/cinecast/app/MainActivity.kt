package com.cinecast.app

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import com.cinecast.app.novel.ui.NovelHubScreen
import com.cinecast.app.novel.ui.NovelReaderScreen
import com.cinecast.app.novel.viewmodel.NovelViewModel
import com.cinecast.app.ui.components.CastBottomSheet
import com.cinecast.app.ui.screens.*
import com.cinecast.app.ui.theme.CineCastTheme
import com.cinecast.app.ui.viewmodel.MainViewModel

private enum class MainTab(val label: String, val icon: ImageVector) {
    HOME("发现", Icons.Rounded.Explore),
    LIVE("直播", Icons.Rounded.LiveTv),
    NOVEL("阅读", Icons.AutoMirrored.Rounded.MenuBook),
    SOURCES("影视源", Icons.Rounded.Dns),
    LIBRARY("我的", Icons.Rounded.Person)
}

private sealed class ActiveOverlay {
    data object None : ActiveOverlay()
    data object Player : ActiveOverlay()
    data object NovelReader : ActiveOverlay()
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
    novelViewModel: NovelViewModel = viewModel()
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

    CineCastTheme(
        themeMode = settings.themeMode,
        dynamicColor = settings.dynamicColor
    ) {
        var currentTab by remember { mutableStateOf(MainTab.HOME) }
        var overlay by remember { mutableStateOf<ActiveOverlay>(ActiveOverlay.None) }
        var globalCastTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

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
            is ActiveOverlay.WebSniffer -> {
                WebSnifferScreen(
                    viewModel = viewModel,
                    initialUrl = active.url,
                    initialTitle = active.title,
                    onBack = { overlay = ActiveOverlay.None },
                    onPlaySniffedUrl = { title, url ->
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
                                    viewModel.openVodDetail(srcId, vodId)
                                    overlay = ActiveOverlay.Player
                                },
                                onOpenWebSniffer = { url, title ->
                                    overlay = ActiveOverlay.WebSniffer(url, title)
                                },
                                onOpenCastSheet = {
                                    globalCastTarget = "" to ""
                                }
                            )
                            MainTab.LIVE -> LiveScreen(
                                viewModel = viewModel,
                                onPlayLiveChannel = { title, urls ->
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
                            MainTab.SOURCES -> SourcesScreen(
                                viewModel = viewModel,
                                onOpenWebSniffer = { url, title ->
                                    overlay = ActiveOverlay.WebSniffer(url, title)
                                }
                            )
                            MainTab.LIBRARY -> LibraryScreen(
                                viewModel = viewModel,
                                onOpenVod = { srcId, vodId ->
                                    viewModel.openVodDetail(srcId, vodId)
                                    overlay = ActiveOverlay.Player
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
