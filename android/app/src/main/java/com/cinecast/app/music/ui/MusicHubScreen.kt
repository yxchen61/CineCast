package com.cinecast.app.music.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.cinecast.app.music.model.*
import com.cinecast.app.music.viewmodel.ArtistDetailUiState
import com.cinecast.app.music.viewmodel.MusicViewModel

private enum class MusicSubTab(val label: String, val icon: ImageVector) {
    DISCOVER("音乐馆", Icons.Rounded.MusicNote),
    ARTISTS("歌手", Icons.Rounded.RecordVoiceOver),
    LIBRARY("歌单", Icons.AutoMirrored.Rounded.QueueMusic),
    SOURCES("音乐源", Icons.Rounded.Podcasts)
}

/**
 * YouTube Music / Google Material Design 3 Expressive 独立音乐中心
 * 内含：音乐馆（今日焦点卡 + 热门歌手横向头像条 + 榜单）| 歌手分类查询 | 歌单收藏 | 独立音乐源管理
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicHubScreen(
    viewModel: MusicViewModel,
    onOpenFullPlayer: () -> Unit
) {
    val sources by viewModel.sources.collectAsState()
    val activeSourceId by viewModel.activeSourceId.collectAsState()
    val subscriptions by viewModel.subscriptions.collectAsState()
    val discoverState by viewModel.discoverState.collectAsState()
    val searchState by viewModel.searchState.collectAsState()
    val artistDetailState by viewModel.artistDetailState.collectAsState()
    val playerState by viewModel.playerState.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val recentPlays by viewModel.recentPlays.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()

    var currentSubTab by remember { mutableStateOf(MusicSubTab.DISCOVER) }
    var isSearchMode by remember { mutableStateOf(false) }
    var showSourcePicker by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    val searchFocusRequester = remember { FocusRequester() }

    val enabledSources = remember(sources) { sources.filter { it.enabled } }
    val activeSource = remember(sources, activeSourceId) {
        sources.firstOrNull { it.id == activeSourceId } ?: enabledSources.firstOrNull()
    }

    LaunchedEffect(isSearchMode) {
        if (isSearchMode) runCatching { searchFocusRequester.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Google Material 3 Expressive Compact Header
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                if (isSearchMode) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {
                                isSearchMode = false
                                viewModel.clearSearch()
                                focusManager.clearFocus()
                            }) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                            }
                            TextField(
                                value = searchState.query,
                                onValueChange = { viewModel.updateSearchQuery(it) },
                                placeholder = { Text("搜歌曲、歌手、专辑、电台...") },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = {
                                    focusManager.clearFocus()
                                    viewModel.executeSearch()
                                }),
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(searchFocusRequester)
                            )
                            if (searchState.query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "清空")
                                }
                            }
                            FilledTonalButton(
                                onClick = {
                                    focusManager.clearFocus()
                                    viewModel.executeSearch()
                                },
                                contentPadding = PaddingValues(horizontal = 14.dp),
                                modifier = Modifier.height(36.dp)
                            ) { Text("搜歌") }
                            Spacer(Modifier.width(4.dp))
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = searchState.searchAllSources,
                            onClick = { viewModel.toggleSearchAllSources(true) },
                            label = { Text("全音乐源聚合搜索") },
                            leadingIcon = { Icon(Icons.Rounded.Hub, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        FilterChip(
                            selected = !searchState.searchAllSources,
                            onClick = { viewModel.toggleSearchAllSources(false) },
                            label = { Text("仅当前源") }
                        )
                    }
                } else {
                    // Row 1: Brand + Active Source Pill + Search Icon
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Rounded.GraphicEq,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "极光音乐",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(10.dp))
                        if (currentSubTab == MusicSubTab.DISCOVER) {
                            Surface(
                                onClick = { showSourcePicker = true },
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
                                modifier = Modifier.weight(1f, fill = false)
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = activeSource?.name?.substringBefore(" ") ?: "选择音源",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Icon(
                                        Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = "切换音乐源",
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        FilledTonalIconButton(
                            onClick = { isSearchMode = true },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            ),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(Icons.Rounded.Search, contentDescription = "搜索音乐", modifier = Modifier.size(20.dp))
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Row 2: Sleek Borderless Google Material 3 Tonal Pill Tabs (音乐馆 | 歌手 | 歌单 | 音乐源)
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            MusicSubTab.entries.forEach { tab ->
                                val selected = currentSubTab == tab
                                val badge = when (tab) {
                                    MusicSubTab.SOURCES -> " ${enabledSources.size}"
                                    MusicSubTab.LIBRARY -> if (favorites.isNotEmpty()) " ${favorites.size}" else ""
                                    else -> ""
                                }
                                Surface(
                                    onClick = { currentSubTab = tab },
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            tab.icon,
                                            contentDescription = null,
                                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            tab.label + badge,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Main Content Pane
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (isSearchMode) {
                MusicSearchPane(
                    searchState = searchState,
                    activeTrackKey = playerState.currentTrack?.uniqueKey,
                    favorites = favorites,
                    onPlayTrack = { list, idx ->
                        viewModel.playTrackFromList(list, idx)
                        onOpenFullPlayer()
                    },
                    onToggleFav = { viewModel.toggleFavorite(it) }
                )
            } else {
                when (currentSubTab) {
                    MusicSubTab.DISCOVER -> MusicDiscoverPane(
                        discoverState = discoverState,
                        activeTrackKey = playerState.currentTrack?.uniqueKey,
                        favorites = favorites,
                        onSelectChart = { viewModel.selectChart(it) },
                        onRefresh = { viewModel.loadDiscover(autoFallback = false) },
                        onPlayTrack = { list, idx ->
                            viewModel.playTrackFromList(list, idx)
                            onOpenFullPlayer()
                        },
                        onToggleFav = { viewModel.toggleFavorite(it) },
                        onSelectArtist = { artist ->
                            viewModel.querySongsByArtist(artist.name, artist.tag)
                        },
                        onSeeAllArtists = { currentSubTab = MusicSubTab.ARTISTS }
                    )

                    MusicSubTab.ARTISTS -> MusicArtistsPane(
                        onSelectArtist = { name, tag ->
                            viewModel.querySongsByArtist(name, tag)
                        }
                    )

                    MusicSubTab.LIBRARY -> MusicLibraryPane(
                        favorites = favorites,
                        playlists = playlists,
                        recentPlays = recentPlays,
                        activeTrackKey = playerState.currentTrack?.uniqueKey,
                        onPlayList = { list, idx ->
                            viewModel.playTrackFromList(list, idx)
                            onOpenFullPlayer()
                        },
                        onToggleFav = { viewModel.toggleFavorite(it) },
                        onCreatePlaylist = { viewModel.createPlaylist(it) },
                        onDeletePlaylist = { viewModel.deletePlaylist(it) }
                    )

                    MusicSubTab.SOURCES -> MusicSourcesPane(
                        sources = sources,
                        subscriptions = subscriptions,
                        isImporting = isImporting,
                        viewModel = viewModel
                    )
                }
            }
        }

        // Floating Google Material 3 Mini-Player Pill Bar
        playerState.currentTrack?.let { current ->
            MusicMiniPlayerBar(
                track = current,
                isPlaying = playbackState.isPlaying,
                isBuffering = playbackState.isBuffering || playerState.isResolvingStream,
                positionMs = playbackState.positionMs,
                durationMs = playbackState.durationMs,
                onClick = onOpenFullPlayer,
                onTogglePlay = { viewModel.togglePlayPause() },
                onNext = { viewModel.nextTrack() }
            )
        }
    }

    // Artist Songs BottomSheet (triggered from 歌手分类页 or 热门歌手横条)
    if (artistDetailState.artistName.isNotBlank()) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.clearArtistDetail() },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            ArtistSongsSheetContent(
                state = artistDetailState,
                activeTrackKey = playerState.currentTrack?.uniqueKey,
                favorites = favorites,
                onPlayTrack = { list, idx ->
                    viewModel.playTrackFromList(list, idx)
                    viewModel.clearArtistDetail()
                    onOpenFullPlayer()
                },
                onToggleFav = { viewModel.toggleFavorite(it) }
            )
        }
    }

    // Active Music Source Picker Sheet
    if (showSourcePicker) {
        ModalBottomSheet(
            onDismissRequest = { showSourcePicker = false },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Text("切换当前音乐源", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "音乐源与视频源、小说书源完全隔离，可在「音乐源」页导入 GitHub 开源曲库或电台。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 380.dp)
                ) {
                    items(enabledSources, key = { it.id }) { src ->
                        val selected = src.id == activeSource?.id
                        Surface(
                            onClick = {
                                viewModel.selectSource(src.id)
                                showSourcePicker = false
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(src.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                        Spacer(Modifier.width(6.dp))
                                        MusicBadge(src.sourceType.badge)
                                    }
                                    Text(src.group, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (selected) {
                                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))
            }
        }
    }
}

// ==================== 1. 音乐馆（焦点卡 + 热门歌手横滑 + 精选榜单） ====================

@Composable
private fun MusicDiscoverPane(
    discoverState: com.cinecast.app.music.viewmodel.MusicDiscoverUiState,
    activeTrackKey: String?,
    favorites: List<MusicTrack>,
    onSelectChart: (Int) -> Unit,
    onRefresh: () -> Unit,
    onPlayTrack: (List<MusicTrack>, Int) -> Unit,
    onToggleFav: (MusicTrack) -> Unit,
    onSelectArtist: (MusicArtistProfile) -> Unit,
    onSeeAllArtists: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (discoverState.charts.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(discoverState.charts) { idx, chart ->
                    val sel = discoverState.selectedChartIndex == idx
                    Surface(
                        onClick = { onSelectChart(idx) },
                        shape = RoundedCornerShape(20.dp),
                        color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = if (sel) 2.dp else 0.dp
                    ) {
                        Text(
                            chart.title,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 15.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }

        when {
            discoverState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(10.dp))
                        Text("正在加载精选音乐...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            discoverState.error != null && discoverState.tracks.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Icon(Icons.Rounded.MusicOff, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Text(discoverState.error, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(onClick = onRefresh) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("重新加载")
                        }
                    }
                }
            }

            else -> {
                val heroTrack = discoverState.tracks.firstOrNull()
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Featured Hero Spotlight Card (YouTube Music Style)
                    if (heroTrack != null) {
                        item {
                            Card(
                                onClick = { onPlayTrack(discoverState.tracks, 0) },
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            Brush.linearGradient(
                                                listOf(
                                                    MaterialTheme.colorScheme.primaryContainer,
                                                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f)
                                                )
                                            )
                                        )
                                        .padding(16.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = RoundedCornerShape(18.dp),
                                            shadowElevation = 8.dp,
                                            modifier = Modifier.size(82.dp)
                                        ) {
                                            MusicCoverArt(
                                                title = heroTrack.title,
                                                coverUrl = heroTrack.coverUrl,
                                                cornerRadius = 18,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                        Spacer(Modifier.width(14.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.primary,
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    "今日焦点 · TOP 1",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                )
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                heroTrack.title,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                heroTrack.artist,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Button(
                                                    onClick = { onPlayTrack(discoverState.tracks, 0) },
                                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                                    shape = RoundedCornerShape(12.dp),
                                                    modifier = Modifier.height(32.dp)
                                                ) {
                                                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Spacer(Modifier.width(4.dp))
                                                    Text("播放全部", style = MaterialTheme.typography.labelMedium)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Hot Artists Quick Horizontal Carousel
                    item {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("热门歌手直达", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                TextButton(
                                    onClick = onSeeAllArtists,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) {
                                    Text("全部分类", style = MaterialTheme.typography.labelLarge)
                                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(MusicViewModel.curatedArtists.take(12), key = { it.name }) { artist ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .width(66.dp)
                                            .clickable { onSelectArtist(artist) }
                                    ) {
                                        ArtistAvatarCircle(
                                            name = artist.name,
                                            accentHex = artist.accentColorHex,
                                            sizeDp = 56
                                        )
                                        Spacer(Modifier.height(5.dp))
                                        Text(
                                            artist.name,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Section Header
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${discoverState.charts.getOrNull(discoverState.selectedChartIndex)?.title ?: "精选榜单"} (${discoverState.tracks.size} 首)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    itemsIndexed(discoverState.tracks, key = { idx, t -> "${t.uniqueKey}_$idx" }) { idx, track ->
                        MusicTrackRow(
                            index = idx + 1,
                            track = track,
                            isCurrent = track.uniqueKey == activeTrackKey,
                            isFavorite = favorites.any { it.uniqueKey == track.uniqueKey },
                            onClick = { onPlayTrack(discoverState.tracks, idx) },
                            onToggleFav = { onToggleFav(track) }
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

// ==================== 2. 歌手分类查询视图 ====================

@Composable
private fun MusicArtistsPane(
    onSelectArtist: (name: String, tag: String) -> Unit
) {
    var selectedCategory by remember { mutableStateOf(ArtistCategory.ALL) }
    var artistQuery by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    val filteredArtists = remember(selectedCategory, artistQuery) {
        val byCat = if (selectedCategory == ArtistCategory.ALL) {
            MusicViewModel.curatedArtists
        } else {
            MusicViewModel.curatedArtists.filter { it.category == selectedCategory }
        }
        val q = artistQuery.trim()
        if (q.isBlank()) byCat else byCat.filter {
            it.name.contains(q, ignoreCase = true) || it.tag.contains(q, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Precision Artist Search Input Bar
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.PersonSearch, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                TextField(
                    value = artistQuery,
                    onValueChange = { artistQuery = it },
                    placeholder = { Text("输入任意歌手名直查作品（如：周杰伦 / 陈奕迅）", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        focusManager.clearFocus()
                        if (artistQuery.isNotBlank()) onSelectArtist(artistQuery.trim(), "全曲库歌手查询")
                    }),
                    modifier = Modifier.weight(1f)
                )
                if (artistQuery.isNotBlank()) {
                    FilledTonalButton(
                        onClick = {
                            focusManager.clearFocus()
                            onSelectArtist(artistQuery.trim(), "全曲库歌手查询")
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp)
                    ) { Text("查歌曲", style = MaterialTheme.typography.labelMedium) }
                }
            }
        }

        // Artist Category Filter Pills
        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(ArtistCategory.entries) { cat ->
                val sel = selectedCategory == cat
                Surface(
                    onClick = { selectedCategory = cat },
                    shape = RoundedCornerShape(20.dp),
                    color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Text(
                        cat.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Responsive Artist Cards Grid
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 152.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(filteredArtists, key = { "${it.category.name}_${it.name}" }) { artist ->
                Card(
                    onClick = { onSelectArtist(artist.name, artist.tag) },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ArtistAvatarCircle(
                            name = artist.name,
                            accentHex = artist.accentColorHex,
                            sizeDp = 46
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                artist.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                artist.tag,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistAvatarCircle(
    name: String,
    accentHex: Long,
    sizeDp: Int
) {
    val baseColor = Color(accentHex)
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .shadow(4.dp, CircleShape)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(baseColor, baseColor.copy(alpha = 0.72f))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.take(2),
            color = Color.White,
            fontSize = (sizeDp * 0.30f).sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun ArtistSongsSheetContent(
    state: ArtistDetailUiState,
    activeTrackKey: String?,
    favorites: List<MusicTrack>,
    onPlayTrack: (List<MusicTrack>, Int) -> Unit,
    onToggleFav: (MusicTrack) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(state.artistName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (state.artistTag.isNotBlank()) {
                    Text(
                        "${state.artistTag} · 跨音源精准匹配 (${state.tracks.size} 首)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (state.tracks.isNotEmpty()) {
                Button(
                    onClick = { onPlayTrack(state.tracks, 0) },
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("播放全部")
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (state.isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        if (state.tracks.isEmpty() && !state.isLoading) {
            Text(
                state.error ?: "暂未找到该歌手曲目",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp)
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 420.dp)
            ) {
                itemsIndexed(state.tracks, key = { idx, t -> "${t.uniqueKey}_$idx" }) { idx, track ->
                    MusicTrackRow(
                        index = idx + 1,
                        track = track,
                        showSourceBadge = true,
                        isCurrent = track.uniqueKey == activeTrackKey,
                        isFavorite = favorites.any { it.uniqueKey == track.uniqueKey },
                        onClick = { onPlayTrack(state.tracks, idx) },
                        onToggleFav = { onToggleFav(track) }
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

// ==================== 3. 多源并发搜歌 ====================

@Composable
private fun MusicSearchPane(
    searchState: com.cinecast.app.music.viewmodel.MusicSearchUiState,
    activeTrackKey: String?,
    favorites: List<MusicTrack>,
    onPlayTrack: (List<MusicTrack>, Int) -> Unit,
    onToggleFav: (MusicTrack) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (searchState.isSearching) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        when {
            searchState.query.isBlank() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(28.dp)) {
                        Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("跨音乐源聚合搜歌", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "输入歌名、歌手或电台名，自动并发检索 GDStudio / Meting / Audius / Radio-Browser 开源曲库",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            searchState.results.isEmpty() && !searchState.isSearching -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        searchState.error ?: "未搜索到相关曲目",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }

            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        Text(
                            "找到 ${searchState.results.size} 首曲目" + if (searchState.isSearching) "（更多音源正在返回...）" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    itemsIndexed(searchState.results, key = { idx, t -> "${t.uniqueKey}_$idx" }) { idx, track ->
                        MusicTrackRow(
                            track = track,
                            showSourceBadge = true,
                            isCurrent = track.uniqueKey == activeTrackKey,
                            isFavorite = favorites.any { it.uniqueKey == track.uniqueKey },
                            onClick = { onPlayTrack(searchState.results, idx) },
                            onToggleFav = { onToggleFav(track) }
                        )
                    }
                }
            }
        }
    }
}

// ==================== 4. 我的歌单与喜欢 ====================

@Composable
private fun MusicLibraryPane(
    favorites: List<MusicTrack>,
    playlists: List<MusicPlaylist>,
    recentPlays: List<MusicTrack>,
    activeTrackKey: String?,
    onPlayList: (List<MusicTrack>, Int) -> Unit,
    onToggleFav: (MusicTrack) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onDeletePlaylist: (String) -> Unit
) {
    var selectedSection by remember { mutableIntStateOf(0) }
    var showCreateDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("我喜欢 (${favorites.size})", "最近 (${recentPlays.size})", "歌单 (${playlists.size})").forEachIndexed { idx, label ->
                val sel = selectedSection == idx
                Surface(
                    onClick = { selectedSection = idx },
                    shape = RoundedCornerShape(18.dp),
                    color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
        }

        when (selectedSection) {
            0 -> {
                if (favorites.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无喜欢的歌曲，点击歌曲右侧红心即可收藏", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(favorites, key = { idx, t -> "${t.uniqueKey}_$idx" }) { idx, track ->
                            MusicTrackRow(
                                track = track,
                                showSourceBadge = true,
                                isCurrent = track.uniqueKey == activeTrackKey,
                                isFavorite = true,
                                onClick = { onPlayList(favorites, idx) },
                                onToggleFav = { onToggleFav(track) }
                            )
                        }
                    }
                }
            }

            1 -> {
                if (recentPlays.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无最近播放记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(recentPlays, key = { idx, t -> "${t.uniqueKey}_$idx" }) { idx, track ->
                            MusicTrackRow(
                                track = track,
                                showSourceBadge = true,
                                isCurrent = track.uniqueKey == activeTrackKey,
                                isFavorite = favorites.any { it.uniqueKey == track.uniqueKey },
                                onClick = { onPlayList(recentPlays, idx) },
                                onToggleFav = { onToggleFav(track) }
                            )
                        }
                    }
                }
            }

            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        FilledTonalButton(
                            onClick = { showCreateDialog = true },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("新建自定义歌单")
                        }
                    }
                    items(playlists, key = { it.id }) { pl ->
                        Card(
                            onClick = { if (pl.tracks.isNotEmpty()) onPlayList(pl.tracks, 0) },
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(50.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(pl.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text("${pl.tracks.size} 首歌曲", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { onDeletePlaylist(pl.id) }) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "删除歌单", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        var plName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("新建歌单") },
            text = {
                OutlinedTextField(
                    value = plName,
                    onValueChange = { plName = it },
                    label = { Text("歌单名称") },
                    placeholder = { Text("例如：开车必听 / 深夜专注") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (plName.isNotBlank()) {
                        onCreatePlaylist(plName)
                        showCreateDialog = false
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("取消") } }
        )
    }
}

// ==================== 5. 独立音乐源管理 ====================

@Composable
private fun MusicSourcesPane(
    sources: List<MusicSource>,
    subscriptions: List<MusicSubscription>,
    isImporting: Boolean,
    viewModel: MusicViewModel
) {
    val clipboard = LocalClipboardManager.current
    var subUrlInput by remember { mutableStateOf("https://fastly.jsdelivr.net/gh/Huibq/keep-alive/Music_Free/myPlugins.json") }
    var showPasteDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingSource by remember { mutableStateOf<MusicSource?>(null) }
    var showRestoreDialog by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("导入 GitHub 开源音乐源 (MusicFree / JSON / M3U)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "与视频源、小说书源完全独立。支持导入 MusicFree plugins.json、自建 Meting / GDStudio JSON 或开源 M3U 电台。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = subUrlInput,
                        onValueChange = { subUrlInput = it },
                        label = { Text("GitHub 音乐源 JSON / M3U 订阅地址") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(subscriptions, key = { it.id }) { sub ->
                            SuggestionChip(
                                onClick = { subUrlInput = sub.url },
                                label = { Text(sub.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.importMusicUrl(subUrlInput) },
                            enabled = !isImporting && subUrlInput.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isImporting) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                Spacer(Modifier.width(8.dp))
                                Text("拉取中...")
                            } else {
                                Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("从 Git 拉取导入")
                            }
                        }
                        OutlinedButton(onClick = { showPasteDialog = true }, shape = RoundedCornerShape(12.dp)) { Text("粘贴 JSON") }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(
                    onClick = {
                        editingSource = null
                        showAddDialog = true
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("自定义音源")
                }
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(viewModel.musicRepository.exportMusicBackupJson())) },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("导出音源")
                }
                OutlinedButton(onClick = { showRestoreDialog = true }, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("恢复")
                }
            }
        }

        items(sources, key = { it.id }) { src ->
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(src.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.width(6.dp))
                                MusicBadge(src.sourceType.badge)
                                src.latencyMs?.let { ms ->
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        color = if (ms > 0) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(if (ms < 0) "超时" else "${ms}ms", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                    }
                                }
                            }
                            Spacer(Modifier.height(3.dp))
                            Text("${src.group} · ${src.baseUrl}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Switch(checked = src.enabled, onCheckedChange = { viewModel.toggleSourceEnabled(src) })
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { viewModel.testSourceLatency(src) }) {
                            Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("测速")
                        }
                        TextButton(onClick = { editingSource = src; showAddDialog = true }) { Text("编辑") }
                        TextButton(onClick = { viewModel.deleteSource(src.id) }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }

    if (showPasteDialog) {
        var raw by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text("粘贴开源音乐源 JSON / M3U") },
            text = {
                OutlinedTextField(
                    value = raw,
                    onValueChange = { raw = it },
                    placeholder = { Text("粘贴 MusicFree plugins.json、CineCast 音乐源 JSON 或 M3U 内容...") },
                    minLines = 6,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (raw.isNotBlank()) {
                        viewModel.importMusicText(raw)
                        showPasteDialog = false
                    }
                }) { Text("解析导入") }
            },
            dismissButton = { TextButton(onClick = { showPasteDialog = false }) { Text("取消") } }
        )
    }

    if (showAddDialog) {
        AddOrEditMusicSourceDialog(
            initial = editingSource,
            onDismiss = { showAddDialog = false },
            onSave = { name, url, type, platform, group ->
                viewModel.saveCustomSource(editingSource?.id, name, url, type, platform, group)
                showAddDialog = false
            }
        )
    }

    if (showRestoreDialog) {
        var backup by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text("恢复音乐源与歌单备份") },
            text = {
                OutlinedTextField(
                    value = backup,
                    onValueChange = { backup = it },
                    placeholder = { Text("粘贴导出的音乐备份 JSON...") },
                    minLines = 5,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (backup.isNotBlank()) {
                        viewModel.importMusicBackup(backup)
                        showRestoreDialog = false
                    }
                }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { showRestoreDialog = false }) { Text("取消") } }
        )
    }
}

// ==================== Reusable Expressive Google Components ====================

@Composable
fun MusicCoverArt(
    title: String,
    coverUrl: String,
    modifier: Modifier = Modifier,
    cornerRadius: Int = 14
) {
    val colors = remember(title) {
        when (kotlin.math.abs(title.hashCode()) % 5) {
            0 -> listOf(Color(0xFF1A73E8), Color(0xFF0D47A1))
            1 -> listOf(Color(0xFFD93025), Color(0xFF8C1D18))
            2 -> listOf(Color(0xFF188038), Color(0xFF0D652D))
            3 -> listOf(Color(0xFF7B1FA2), Color(0xFF4A148C))
            else -> listOf(Color(0xFFE37400), Color(0xFFA84C00))
        }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(28.dp)
        )
        if (coverUrl.startsWith("http")) {
            AsyncImage(
                model = coverUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun MusicTrackRow(
    index: Int? = null,
    track: MusicTrack,
    showSourceBadge: Boolean = false,
    isCurrent: Boolean = false,
    isFavorite: Boolean = false,
    onClick: () -> Unit,
    onToggleFav: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = if (isCurrent) 1.dp else 0.5.dp,
            color = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (index != null) {
                val rankColor = when (index) {
                    1 -> Color(0xFFD93025)
                    2 -> Color(0xFFE37400)
                    3 -> Color(0xFF1A73E8)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = index.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (index <= 3) FontWeight.ExtraBold else FontWeight.Medium,
                    color = rankColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(28.dp)
                )
            }
            MusicCoverArt(
                title = track.title,
                coverUrl = track.coverUrl,
                cornerRadius = 12,
                modifier = Modifier
                    .size(52.dp)
                    .shadow(3.dp, RoundedCornerShape(12.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isCurrent) {
                        Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        track.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (showSourceBadge) {
                        Spacer(Modifier.width(6.dp))
                        MusicBadge(track.sourceName.substringBefore(" "))
                    }
                }
                Spacer(Modifier.height(3.dp))
                val sub = listOfNotNull(
                    track.artist.takeIf { it.isNotBlank() },
                    track.album.takeIf { it.isNotBlank() },
                    if (track.isLiveRadio) "LIVE FM" else null
                ).joinToString(" · ")
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onToggleFav) {
                Icon(
                    imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = "喜欢",
                    tint = if (isFavorite) Color(0xFFD93025) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun MusicMiniPlayerBar(
    track: MusicTrack,
    isPlaying: Boolean,
    isBuffering: Boolean,
    positionMs: Long,
    durationMs: Long,
    onClick: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MusicCoverArt(title = track.title, coverUrl = track.coverUrl, modifier = Modifier.size(44.dp), cornerRadius = 12)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (isBuffering) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp)
                } else {
                    FilledTonalIconButton(onClick = onTogglePlay, modifier = Modifier.size(36.dp)) {
                        Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = "播放/暂停", modifier = Modifier.size(20.dp))
                    }
                }
                IconButton(onClick = onNext, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "下一首")
                }
            }
            val progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
            )
        }
    }
}

@Composable
private fun MusicBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun AddOrEditMusicSourceDialog(
    initial: MusicSource?,
    onDismiss: () -> Unit,
    onSave: (name: String, baseUrl: String, type: MusicSourceType, platform: String, group: String) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var url by remember { mutableStateOf(initial?.baseUrl.orEmpty()) }
    var platform by remember { mutableStateOf(initial?.platform ?: "netease") }
    var group by remember { mutableStateOf(initial?.group ?: "自定义音乐源") }
    var type by remember { mutableStateOf(initial?.sourceType ?: MusicSourceType.METING_API) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加自定义音乐源" else "编辑音乐源") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 420.dp)) {
                item { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("音乐源名称") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("接口地址 / M3U 链接") }, placeholder = { Text("https://api.injahow.cn/meting/") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(value = platform, onValueChange = { platform = it }, label = { Text("平台标识 (netease / cn / global)") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(value = group, onValueChange = { group = it }, label = { Text("分组标签") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item {
                    Text("协议类型", style = MaterialTheme.typography.labelLarge)
                    MusicSourceType.entries.forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { type = t }) {
                            RadioButton(selected = type == t, onClick = { type = t })
                            Text(t.displayName, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (name.isNotBlank() && url.isNotBlank()) onSave(name, url, type, platform, group)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
