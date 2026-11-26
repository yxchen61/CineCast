package com.cinecast.app.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cinecast.app.music.model.RepeatMode
import com.cinecast.app.music.viewmodel.MusicViewModel
import com.cinecast.app.ui.components.formatDurationMs

/**
 * YouTube Music / Google Material Design 3 风格全屏音乐播放器
 * 支持：封面/同步滚动歌词切换、进度拖拽、喜欢/歌单管理、在线换源试听、播放队列抽屉
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerScreen(
    viewModel: MusicViewModel,
    onBack: () -> Unit
) {
    val playerState by viewModel.playerState.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val settings by viewModel.settings.collectAsState()

    var showLyrics by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showSwitchSheet by remember { mutableStateOf(false) }
    var showAddToPlaylistSheet by remember { mutableStateOf(false) }

    BackHandler {
        when {
            showQueueSheet -> showQueueSheet = false
            showSwitchSheet -> showSwitchSheet = false
            showAddToPlaylistSheet -> showAddToPlaylistSheet = false
            else -> onBack()
        }
    }

    val track = playerState.currentTrack
    val isFav = track != null && favorites.any { it.uniqueKey == track.uniqueKey }
    val lrcListState = rememberLazyListState()

    val activeLyricIndex = remember(playerState.lyrics, playbackState.positionMs) {
        val list = playerState.lyrics
        if (list.isEmpty()) -1
        else list.indexOfLast { it.timeMs <= playbackState.positionMs + 250L }.coerceAtLeast(0)
    }

    LaunchedEffect(activeLyricIndex, showLyrics) {
        if (showLyrics && activeLyricIndex >= 0) {
            runCatching { lrcListState.animateScrollToItem((activeLyricIndex - 3).coerceAtLeast(0)) }
        }
    }

    val gradientBg = Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.surfaceContainerLow
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(gradientBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Header Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "收起播放器", modifier = Modifier.size(30.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("正在播放", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    track?.sourceName ?: "极光音乐",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = {
                viewModel.searchAlternativeSourcesForCurrentTrack()
                showSwitchSheet = true
            }) {
                Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(3.dp))
                Text("换源")
            }
        }

        // Cover / Lyrics Segmented Pill (YouTube Music Style)
        Spacer(Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.widthIn(min = 220.dp)) {
            SegmentedButton(
                selected = !showLyrics,
                onClick = { showLyrics = false },
                shape = SegmentedButtonDefaults.itemShape(0, 2)
            ) { Text("歌曲封面", maxLines = 1) }
            SegmentedButton(
                selected = showLyrics,
                onClick = { showLyrics = true },
                shape = SegmentedButtonDefaults.itemShape(1, 2)
            ) { Text("同步歌词", maxLines = 1) }
        }

        Spacer(Modifier.height(10.dp))

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val isCompactHeight = maxHeight < 420.dp
            val artSize = if (isCompactHeight) (maxHeight - 24.dp).coerceIn(120.dp, 210.dp) else 264.dp

            val centerStage: @Composable (Modifier) -> Unit = { stageMod ->
                Box(
                    modifier = stageMod,
                    contentAlignment = Alignment.Center
                ) {
                    if (!showLyrics) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                shadowElevation = 12.dp,
                                modifier = Modifier
                                    .size(artSize)
                                    .clickable { showLyrics = true }
                            ) {
                                MusicCoverArt(
                                    title = track?.title ?: "极光音乐",
                                    coverUrl = track?.coverUrl.orEmpty(),
                                    cornerRadius = 24,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            if (!isCompactHeight && playerState.lyrics.isNotEmpty() && activeLyricIndex in playerState.lyrics.indices) {
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    text = playerState.lyrics[activeLyricIndex].text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 2,
                                    textAlign = TextAlign.Center,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    } else {
                        if (playerState.lyrics.isEmpty()) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Rounded.Lyrics, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    if (track?.isLiveRadio == true) "在线音乐电台直播中，暂无歌词" else "当前音源暂未提供同步歌词",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                state = lrcListState,
                                contentPadding = PaddingValues(vertical = 28.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(playerState.lyrics) { idx, line ->
                                    val isCurrent = idx == activeLyricIndex
                                    Text(
                                        text = line.text,
                                        fontSize = if (isCurrent) 19.sp else 14.sp,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.seekTo(line.timeMs) }
                                            .padding(horizontal = 12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            val controlsStage: @Composable (Modifier) -> Unit = { ctrlMod ->
                Column(
                    modifier = ctrlMod,
                    verticalArrangement = Arrangement.Center
                ) {
                    val err = playerState.resolveError ?: playbackState.error
                    if (err != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    viewModel.searchAlternativeSourcesForCurrentTrack()
                                    showSwitchSheet = true
                                }) { Text("换源") }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                track?.title ?: "未选择歌曲",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                listOfNotNull(
                                    track?.artist?.takeIf { it.isNotBlank() },
                                    track?.album?.takeIf { it.isNotBlank() }
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (track != null) {
                            IconButton(onClick = { viewModel.toggleFavorite(track) }) {
                                Icon(
                                    if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    contentDescription = "喜欢",
                                    tint = if (isFav) Color(0xFFD93025) else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(onClick = { showAddToPlaylistSheet = true }) {
                                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = "添加到歌单")
                            }
                        }
                    }

                    val duration = playbackState.durationMs.coerceAtLeast(track?.durationMs ?: 0L)
                    var draggingPos by remember { mutableStateOf<Float?>(null) }
                    val sliderVal = draggingPos ?: playbackState.positionMs.toFloat()

                    Slider(
                        value = sliderVal.coerceIn(0f, duration.coerceAtLeast(1L).toFloat()),
                        onValueChange = { draggingPos = it },
                        onValueChangeFinished = {
                            draggingPos?.let { viewModel.seekTo(it.toLong()) }
                            draggingPos = null
                        },
                        valueRange = 0f..(duration.coerceAtLeast(1L).toFloat()),
                        enabled = duration > 0L && track?.isLiveRadio != true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatDurationMs(sliderVal.toLong()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (track?.isLiveRadio == true) "LIVE FM" else formatDurationMs(duration),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    val fabSize = if (isCompactHeight) 56.dp else 66.dp
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { viewModel.cycleRepeatMode() }) {
                            Icon(
                                imageVector = when (settings.repeatMode) {
                                    RepeatMode.LOOP_ALL -> Icons.Rounded.Repeat
                                    RepeatMode.LOOP_ONE -> Icons.Rounded.RepeatOne
                                    RepeatMode.SHUFFLE -> Icons.Rounded.Shuffle
                                },
                                contentDescription = settings.repeatMode.label,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { viewModel.prevTrack() }, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Rounded.SkipPrevious, contentDescription = "上一首", modifier = Modifier.size(32.dp))
                        }
                        Surface(
                            onClick = { viewModel.togglePlayPause() },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            shadowElevation = 6.dp,
                            modifier = Modifier.size(fabSize)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (playerState.isResolvingStream || playbackState.isBuffering) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(26.dp),
                                        strokeWidth = 3.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = "播放/暂停",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(34.dp)
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { viewModel.nextTrack() }, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Rounded.SkipNext, contentDescription = "下一首", modifier = Modifier.size(32.dp))
                        }
                        IconButton(onClick = { showQueueSheet = true }) {
                            Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = "播放队列", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (isCompactHeight) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    centerStage(Modifier.weight(0.42f).fillMaxHeight())
                    controlsStage(Modifier.weight(0.58f).fillMaxHeight())
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    centerStage(Modifier.weight(1f).fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    controlsStage(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    // ==================== 播放队列抽屉 ====================
    if (showQueueSheet) {
        ModalBottomSheet(onDismissRequest = { showQueueSheet = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text("当前播放队列 (${playerState.queue.size} 首)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    itemsIndexed(playerState.queue, key = { idx, t -> "${t.uniqueKey}_$idx" }) { idx, item ->
                        val isCur = idx == playerState.currentIndex
                        ListItem(
                            headlineContent = {
                                Text(
                                    item.title,
                                    color = if (isCur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isCur) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            supportingContent = { Text(item.artist, maxLines = 1) },
                            trailingContent = if (isCur) {
                                { Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                            } else null,
                            modifier = Modifier.clickable {
                                showQueueSheet = false
                                viewModel.playIndex(idx)
                            }
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // ==================== 在线换源抽屉 ====================
    if (showSwitchSheet) {
        ModalBottomSheet(onDismissRequest = { showSwitchSheet = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text("切换音乐源 · ${track?.title.orEmpty()}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                if (playerState.isSearchingSwitch) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
                if (playerState.switchCandidates.isEmpty() && !playerState.isSearchingSwitch) {
                    Text("暂未在其他音源找到同名曲目", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 18.dp))
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 360.dp)) {
                        items(playerState.switchCandidates, key = { it.uniqueKey }) { alt ->
                            Surface(
                                onClick = {
                                    showSwitchSheet = false
                                    viewModel.switchToTrack(alt)
                                },
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(alt.sourceName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                    Text("${alt.title} · ${alt.artist}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    // ==================== 添加到歌单抽屉 ====================
    if (showAddToPlaylistSheet && track != null) {
        ModalBottomSheet(onDismissRequest = { showAddToPlaylistSheet = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text("添加到自定义歌单", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                if (playlists.isEmpty()) {
                    Text("暂无自建歌单，请先在「音乐 → 歌单」页新建一个歌单。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    playlists.forEach { pl ->
                        ListItem(
                            headlineContent = { Text(pl.name) },
                            supportingContent = { Text("${pl.tracks.size} 首") },
                            leadingContent = { Icon(Icons.Rounded.QueueMusic, contentDescription = null) },
                            modifier = Modifier.clickable {
                                viewModel.addTrackToPlaylist(pl.id, track)
                                showAddToPlaylistSheet = false
                            }
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}
