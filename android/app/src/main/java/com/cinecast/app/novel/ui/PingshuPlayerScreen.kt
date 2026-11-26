package com.cinecast.app.novel.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cinecast.app.novel.viewmodel.NovelViewModel
import com.cinecast.app.ui.components.formatDurationMs

/**
 * Google Play Books 风格全屏「听评书」有声播放器
 * 支持：快退15s/快进30s、0.75x~2.0x 倍速、定时关闭、正倒序选回目录、锁屏后台自动连播下一回与断点续听
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PingshuPlayerScreen(
    viewModel: NovelViewModel,
    onBack: () -> Unit
) {
    val playerState by viewModel.pingshuPlayerState.collectAsState()
    val playbackState by viewModel.pingshuPlaybackState.collectAsState()

    var showEpisodeSheet by remember { mutableStateOf(false) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    var showTimerSheet by remember { mutableStateOf(false) }
    var reverseOrder by remember { mutableStateOf(false) }

    BackHandler {
        when {
            showEpisodeSheet -> showEpisodeSheet = false
            showSpeedSheet -> showSpeedSheet = false
            showTimerSheet -> showTimerSheet = false
            else -> onBack()
        }
    }

    val album = playerState.album
    val currentEp = playerState.episodes.getOrNull(playerState.currentEpisodeIndex)

    val bgBrush = Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.surfaceContainerLow
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBrush)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "收起", modifier = Modifier.size(30.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("正在听评书", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    album?.sourceName ?: "极光评书馆",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { showEpisodeSheet = true }) {
                Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, contentDescription = "选回目录")
            }
        }

        Spacer(Modifier.weight(0.5f))

        // Hardcover Pingshu Artwork
        NovelCoverArt(
            title = album?.title ?: "评书",
            coverUrl = album?.coverUrl.orEmpty(),
            modifier = Modifier
                .width(196.dp)
                .height(256.dp)
        )

        Spacer(Modifier.height(20.dp))

        // Album & Episode Title
        Text(
            text = album?.title ?: "未选择评书",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = currentEp?.title ?: "加载回次中...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = listOfNotNull(
                album?.author?.takeIf { it.isNotBlank() }?.let { "播讲：$it" },
                if (playerState.episodes.isNotEmpty()) "第 ${playerState.currentEpisodeIndex + 1} / ${playerState.episodes.size} 回" else null
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.weight(0.5f))

        val err = playerState.error ?: playbackState.error
        if (err != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.playPingshuEpisode(playerState.currentEpisodeIndex) }) {
                        Text("重试")
                    }
                }
            }
        }

        // Progress Slider
        val duration = playbackState.durationMs.coerceAtLeast(1L)
        var dragPos by remember { mutableStateOf<Float?>(null) }
        val sliderPos = dragPos ?: playbackState.positionMs.toFloat()

        Slider(
            value = sliderPos.coerceIn(0f, duration.toFloat()),
            onValueChange = { dragPos = it },
            onValueChangeFinished = {
                dragPos?.let { viewModel.seekPingshu(it.toLong()) }
                dragPos = null
            },
            valueRange = 0f..duration.toFloat(),
            enabled = playbackState.durationMs > 0L,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatDurationMs(sliderPos.toLong()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatDurationMs(playbackState.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(10.dp))

        // Main Audiobook Transport Controls: -15s | Prev | Play/Pause | Next | +30s
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.skipPingshuBy(-15_000L) }) {
                Icon(Icons.Rounded.Replay10, contentDescription = "快退15秒", modifier = Modifier.size(28.dp))
            }
            IconButton(onClick = { viewModel.prevPingshuEpisode() }, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "上一回", modifier = Modifier.size(34.dp))
            }
            Surface(
                onClick = { viewModel.togglePingshuPlayPause() },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 6.dp,
                modifier = Modifier.size(68.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (playerState.isLoading || playbackState.isBuffering) {
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
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }
            IconButton(onClick = { viewModel.nextPingshuEpisode() }, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "下一回", modifier = Modifier.size(34.dp))
            }
            IconButton(onClick = { viewModel.skipPingshuBy(30_000L) }) {
                Icon(Icons.Rounded.Forward30, contentDescription = "快进30秒", modifier = Modifier.size(28.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        // Bottom Feature Bar: Speed | Sleep Timer | Chapters
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FilledTonalButton(
                onClick = { showSpeedSheet = true },
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("${playbackState.speed}x", style = MaterialTheme.typography.labelMedium)
            }
            FilledTonalButton(
                onClick = { showTimerSheet = true },
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Rounded.Bedtime, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                val timerText = when {
                    playerState.sleepAfterEpisode -> "播完本回"
                    playerState.sleepTimerRemainingSec > 0 -> "${(playerState.sleepTimerRemainingSec + 59) / 60}分钟"
                    else -> "定时关闭"
                }
                Text(timerText, style = MaterialTheme.typography.labelMedium)
            }
            FilledTonalButton(
                onClick = { showEpisodeSheet = true },
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("选回 (${playerState.episodes.size})", style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    // ==================== 选回目录抽屉 ====================
    if (showEpisodeSheet) {
        ModalBottomSheet(onDismissRequest = { showEpisodeSheet = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "评书回次目录 (${playerState.episodes.size} 回)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = { reverseOrder = !reverseOrder }) {
                        Icon(Icons.Rounded.SwapVert, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (reverseOrder) "倒序" else "正序")
                    }
                }
                Spacer(Modifier.height(6.dp))
                val displayEps = if (reverseOrder) playerState.episodes.reversed() else playerState.episodes
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(displayEps, key = { _, ep -> "${ep.index}_${ep.url}" }) { _, ep ->
                        val isCur = ep.index == playerState.currentEpisodeIndex
                        ListItem(
                            headlineContent = {
                                Text(
                                    ep.title,
                                    color = if (isCur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingContent = {
                                Text(
                                    "${ep.index + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isCur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            trailingContent = if (isCur) {
                                { Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                            } else null,
                            modifier = Modifier.clickable {
                                showEpisodeSheet = false
                                viewModel.playPingshuEpisode(ep.index)
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // ==================== 倍速选择 ====================
    if (showSpeedSheet) {
        ModalBottomSheet(onDismissRequest = { showSpeedSheet = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
                Text("评书播放倍速", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f).forEach { spd ->
                        val sel = playbackState.speed == spd
                        FilterChip(
                            selected = sel,
                            onClick = {
                                viewModel.setPingshuSpeed(spd)
                                showSpeedSheet = false
                            },
                            label = { Text("${spd}x") }
                        )
                    }
                }
                Spacer(Modifier.height(22.dp))
            }
        }
    }

    // ==================== 定时关闭 ====================
    if (showTimerSheet) {
        ModalBottomSheet(onDismissRequest = { showTimerSheet = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
                Text("定时关闭（助眠听评书）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                val options = listOf(
                    "关闭定时" to 0,
                    "15 分钟后停止" to 15,
                    "30 分钟后停止" to 30,
                    "60 分钟后停止" to 60,
                    "播完当前这一回后停止" to -1
                )
                options.forEach { (label, mins) ->
                    ListItem(
                        headlineContent = { Text(label) },
                        modifier = Modifier.clickable {
                            viewModel.setPingshuSleepTimer(mins)
                            showTimerSheet = false
                        }
                    )
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}
