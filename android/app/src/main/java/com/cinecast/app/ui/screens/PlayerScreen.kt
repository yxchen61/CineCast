package com.cinecast.app.ui.screens

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.util.Rational
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.cinecast.app.data.network.NetworkClient
import com.cinecast.app.ui.components.CastBottomSheet
import com.cinecast.app.ui.components.formatDurationMs
import com.cinecast.app.ui.viewmodel.MainViewModel
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val playerState by viewModel.playerState.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val castSession by viewModel.dlnaController.session.collectAsState()

    val detail = playerState.detail
    val currentPlaySource = detail?.playSources?.getOrNull(playerState.playSourceIndex)
    val currentEpisode = currentPlaySource?.episodes?.getOrNull(playerState.episodeIndex)

    val streamUrl = if (playerState.directUrl.isNotBlank()) playerState.directUrl else currentEpisode?.url.orEmpty()
    val displayTitle = when {
        playerState.directTitle.isNotBlank() -> playerState.directTitle
        detail != null && currentEpisode != null -> "${detail.name} · ${currentEpisode.name}"
        detail != null -> detail.name
        else -> "正在加载..."
    }

    var isFullscreen by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var showCastSheet by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var showRatioMenu by remember { mutableStateOf(false) }

    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(true) }
    var currentPos by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var bufferedPos by remember { mutableLongStateOf(0L) }
    var videoResolution by remember { mutableStateOf("") }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var playbackSpeed by remember { mutableFloatStateOf(settings.defaultSpeed) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var gestureHint by remember { mutableStateOf<String?>(null) }

    // Keep screen on during playback
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.let { setSystemBarsVisible(it, true) }
        }
    }

    // Fullscreen orientation & immersive bars
    LaunchedEffect(isFullscreen) {
        activity?.let { act ->
            act.requestedOrientation = if (isFullscreen) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            setSystemBarsVisible(act, !isFullscreen)
        }
    }

    BackHandler {
        if (isFullscreen) {
            isFullscreen = false
        } else {
            onBack()
        }
    }

    // Create Media3 ExoPlayer with OkHttpDataSource
    val exoPlayer = remember(context) {
        val httpFactory = OkHttpDataSource.Factory(NetworkClient.client)
            .setUserAgent(NetworkClient.DEFAULT_UA)
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpFactory)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                playWhenReady = true
            }
    }

    // Load media whenever streamUrl changes
    LaunchedEffect(streamUrl) {
        playbackError = null
        if (streamUrl.isNotBlank()) {
            isBuffering = true
            val mediaItem = MediaItem.fromUri(streamUrl)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.playbackParameters = PlaybackParameters(playbackSpeed)
            exoPlayer.prepare()
            if (playerState.resumePositionMs > 3000L && !playerState.isLiveStream) {
                exoPlayer.seekTo(playerState.resumePositionMs)
            }
            exoPlayer.playWhenReady = true
        }
    }

    // Player listener
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    playbackError = null
                    duration = exoPlayer.duration.coerceAtLeast(0L)
                } else if (state == Player.STATE_ENDED) {
                    // Auto next episode
                    if (settings.autoNextEpisode && currentPlaySource != null) {
                        val nextIdx = playerState.episodeIndex + 1
                        if (nextIdx < currentPlaySource.episodes.size) {
                            viewModel.selectPlaySourceAndEpisode(playerState.playSourceIndex, nextIdx)
                        }
                    }
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0) {
                    videoResolution = when {
                        videoSize.height >= 2160 -> "4K"
                        videoSize.height >= 1440 -> "2K"
                        videoSize.height >= 1000 -> "1080P"
                        videoSize.height >= 700 -> "720P"
                        else -> "${videoSize.height}P"
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                val curState = viewModel.playerState.value
                if (curState.isLiveStream && curState.liveBackupIndex + 1 < curState.liveBackupUrls.size) {
                    val nextLine = curState.liveBackupIndex + 1
                    playbackError = "线路 ${curState.liveBackupIndex + 1} 不可用，正在自动切换至线路 ${nextLine + 1}..."
                    viewModel.selectLiveBackupLine(nextLine)
                } else {
                    playbackError = "播放出错 (${error.errorCodeName})，请尝试切换播放线路或视频源"
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            val pos = exoPlayer.currentPosition
            val dur = exoPlayer.duration.coerceAtLeast(0L)
            if (pos > 2000L && detail != null) {
                viewModel.savePlaybackProgress(pos, dur)
            }
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Periodic progress update & auto-save watch history
    LaunchedEffect(exoPlayer) {
        var tick = 0
        while (true) {
            currentPos = exoPlayer.currentPosition.coerceAtLeast(0L)
            duration = exoPlayer.duration.coerceAtLeast(0L)
            bufferedPos = exoPlayer.bufferedPosition.coerceAtLeast(0L)
            tick++
            if (tick % 16 == 0 && currentPos > 3000L && detail != null) {
                viewModel.savePlaybackProgress(currentPos, duration)
            }
            delay(500L)
        }
    }

    // Auto-hide controls
    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(4200L)
            showControls = false
        }
    }

    val isFav = remember(favorites, detail) {
        detail != null && favorites.any { it.vodId == detail.id && it.sourceId == detail.sourceId }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isFullscreen) Color.Black else MaterialTheme.colorScheme.background)
    ) {
        // Video Player Surface Box
        var boxSize by remember { mutableStateOf(IntSize.Zero) }
        val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (isFullscreen) Modifier.fillMaxSize()
                    else Modifier
                        .statusBarsPadding()
                        .aspectRatio(16f / 9f)
                )
                .background(Color.Black)
                .onSizeChanged { boxSize = it }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { showControls = !showControls },
                        onDoubleTap = {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        }
                    )
                }
                .pointerInput(boxSize, duration) {
                    var dragMode = 0 // 1=seek, 2=brightness, 3=volume
                    var accumX = 0f
                    var startPos = 0L
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragMode = 0
                            accumX = 0f
                            startPos = exoPlayer.currentPosition
                        },
                        onDragEnd = {
                            if (dragMode == 1 && duration > 0) {
                                val target = (startPos + (accumX / boxSize.width.coerceAtLeast(1) * 90_000f).toLong())
                                    .coerceIn(0L, duration)
                                exoPlayer.seekTo(target)
                            }
                            gestureHint = null
                        },
                        onDragCancel = { gestureHint = null },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (dragMode == 0) {
                                dragMode = if (kotlin.math.abs(dragAmount.x) > kotlin.math.abs(dragAmount.y)) {
                                    1
                                } else if (change.position.x < boxSize.width / 2f) {
                                    2
                                } else {
                                    3
                                }
                            }
                            when (dragMode) {
                                1 -> {
                                    if (duration > 0) {
                                        accumX += dragAmount.x
                                        val deltaMs = (accumX / boxSize.width.coerceAtLeast(1) * 90_000f).toLong()
                                        val preview = (startPos + deltaMs).coerceIn(0L, duration)
                                        gestureHint = "${formatDurationMs(preview)} / ${formatDurationMs(duration)}"
                                    }
                                }
                                2 -> {
                                    activity?.window?.let { win ->
                                        val lp = win.attributes
                                        val cur = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
                                        val next = (cur - dragAmount.y / boxSize.height.coerceAtLeast(1)).coerceIn(0.05f, 1f)
                                        lp.screenBrightness = next
                                        win.attributes = lp
                                        gestureHint = "亮度 ${(next * 100).toInt()}%"
                                    }
                                }
                                3 -> {
                                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                    val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                    if (kotlin.math.abs(dragAmount.y) > 12f) {
                                        val nextVol = (curVol + if (dragAmount.y < 0) 1 else -1).coerceIn(0, maxVol)
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nextVol, 0)
                                        gestureHint = "音量 ${(nextVol * 100 / maxVol)}%"
                                    }
                                }
                            }
                        }
                    )
                }
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        player = exoPlayer
                        this.resizeMode = resizeMode
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                update = { view ->
                    view.player = exoPlayer
                    view.resizeMode = resizeMode
                },
                modifier = Modifier.fillMaxSize()
            )

            // Loading / Buffering indicator
            if (isBuffering || playerState.isLoading) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // Gesture HUD pill
            gestureHint?.let { hint ->
                Surface(
                    color = Color.Black.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Text(
                        text = hint,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                    )
                }
            }

            // Playback error overlay
            playbackError?.let { err ->
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(err, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(onClick = {
                        playbackError = null
                        exoPlayer.prepare()
                        exoPlayer.playWhenReady = true
                    }) {
                        Text("重新缓冲")
                    }
                }
            }

            // Controls Overlay
            androidx.compose.animation.AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Top bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)
                                )
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            if (isFullscreen) isFullscreen = false else onBack()
                        }) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = Color.White)
                        }
                        Text(
                            text = displayTitle,
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (videoResolution.isNotBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = videoResolution,
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
                            IconButton(onClick = {
                                runCatching {
                                    val params = PictureInPictureParams.Builder()
                                        .setAspectRatio(Rational(16, 9))
                                        .build()
                                    activity.enterPictureInPictureMode(params)
                                }
                            }) {
                                Icon(Icons.Rounded.PictureInPictureAlt, contentDescription = "画中画", tint = Color.White)
                            }
                        }
                        IconButton(onClick = { showCastSheet = true }) {
                            Icon(
                                imageVector = if (castSession.connectedDevice != null) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
                                contentDescription = "投屏",
                                tint = if (castSession.connectedDevice != null) Color(0xFF8AB4F8) else Color.White
                            )
                        }
                    }

                    // Center Play/Pause
                    IconButton(
                        onClick = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(56.dp)
                            .background(Color.Black.copy(alpha = 0.48f), CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = "播放/暂停",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    // Bottom bar
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))
                                )
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        if (!playerState.isLiveStream && duration > 0) {
                            Slider(
                                value = currentPos.toFloat().coerceIn(0f, duration.toFloat()),
                                onValueChange = { exoPlayer.seekTo(it.toLong()) },
                                valueRange = 0f..duration.toFloat(),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = Color(0xFF8AB4F8),
                                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                                ),
                                modifier = Modifier.height(26.dp)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() }) {
                                Icon(
                                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White
                                )
                            }
                            if (currentPlaySource != null && playerState.episodeIndex + 1 < currentPlaySource.episodes.size) {
                                IconButton(onClick = {
                                    viewModel.selectPlaySourceAndEpisode(
                                        playerState.playSourceIndex,
                                        playerState.episodeIndex + 1
                                    )
                                }) {
                                    Icon(Icons.Rounded.SkipNext, contentDescription = "下一集", tint = Color.White)
                                }
                            }
                            Text(
                                text = if (playerState.isLiveStream) "LIVE 直播中" else "${formatDurationMs(currentPos)} / ${formatDurationMs(duration)}",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium
                            )
                            Spacer(Modifier.weight(1f))

                            // Speed Menu
                            Box {
                                TextButton(onClick = { showSpeedMenu = true }) {
                                    Text("${playbackSpeed}x", color = Color.White)
                                }
                                DropdownMenu(expanded = showSpeedMenu, onDismissRequest = { showSpeedMenu = false }) {
                                    listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f).forEach { spd ->
                                        DropdownMenuItem(
                                            text = { Text("${spd}x 倍速") },
                                            onClick = {
                                                playbackSpeed = spd
                                                exoPlayer.playbackParameters = PlaybackParameters(spd)
                                                showSpeedMenu = false
                                            }
                                        )
                                    }
                                }
                            }

                            // Aspect Ratio Menu
                            Box {
                                TextButton(onClick = { showRatioMenu = true }) {
                                    Text(
                                        when (resizeMode) {
                                            AspectRatioFrameLayout.RESIZE_MODE_FILL -> "拉伸"
                                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "铺满"
                                            else -> "适应"
                                        },
                                        color = Color.White
                                    )
                                }
                                DropdownMenu(expanded = showRatioMenu, onDismissRequest = { showRatioMenu = false }) {
                                    listOf(
                                        AspectRatioFrameLayout.RESIZE_MODE_FIT to "原始比例适应",
                                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "裁剪铺满全屏",
                                        AspectRatioFrameLayout.RESIZE_MODE_FILL to "拉伸全屏"
                                    ).forEach { (mode, label) ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            onClick = {
                                                resizeMode = mode
                                                showRatioMenu = false
                                            }
                                        )
                                    }
                                }
                            }

                            IconButton(onClick = { isFullscreen = !isFullscreen }) {
                                Icon(
                                    if (isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                                    contentDescription = "全屏",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }

        // Portrait Content Area (Metadata, Actions, Lines, Episodes)
        if (!isFullscreen) {
            when {
                playerState.error != null -> {
                    EmptyStateBox(
                        icon = Icons.Rounded.ErrorOutline,
                        title = playerState.error!!,
                        actionLabel = "返回",
                        onAction = onBack
                    )
                }
                detail != null -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = detail.name,
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    val meta = listOf(detail.year, detail.area, detail.typeName, detail.remarks, detail.sourceName)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" · ")
                                    Text(
                                        text = meta,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Action Buttons Row (Cast, Favorite, Line count)
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                FilledTonalButton(
                                    onClick = { showCastSheet = true },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Icon(Icons.Rounded.Cast, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (castSession.connectedDevice != null) "投屏控制" else "DLNA 投屏")
                                }
                                FilledTonalButton(
                                    onClick = { viewModel.toggleFavorite(detail) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Icon(
                                        if (isFav) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (isFav) "已收藏" else "收藏影片")
                                }
                            }
                        }

                        // Play Source Lines
                        if (detail.playSources.isNotEmpty()) {
                            item {
                                Text(
                                    text = "播放线路 (${detail.playSources.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(8.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    itemsIndexed(detail.playSources) { idx, ps ->
                                        FilterChip(
                                            selected = idx == playerState.playSourceIndex,
                                            onClick = {
                                                val safeEp = playerState.episodeIndex.coerceIn(0, (ps.episodes.size - 1).coerceAtLeast(0))
                                                viewModel.selectPlaySourceAndEpisode(idx, safeEp)
                                            },
                                            label = { Text("${ps.name} (${ps.episodes.size})") }
                                        )
                                    }
                                }
                            }
                        }

                        // Episode Selector Grid (wrapped rows)
                        if (currentPlaySource != null && currentPlaySource.episodes.isNotEmpty()) {
                            item {
                                Text(
                                    text = "选集 (${currentPlaySource.episodes.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(8.dp))
                                val chunked = currentPlaySource.episodes.withIndex().chunked(4)
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    chunked.forEach { rowItems ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            rowItems.forEach { indexedEp ->
                                                val selected = indexedEp.index == playerState.episodeIndex
                                                Surface(
                                                    onClick = {
                                                        viewModel.selectPlaySourceAndEpisode(
                                                            playerState.playSourceIndex,
                                                            indexedEp.index
                                                        )
                                                    },
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text(
                                                        text = indexedEp.value.name,
                                                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                                        style = MaterialTheme.typography.labelMedium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
                                                    )
                                                }
                                            }
                                            repeat(4 - rowItems.size) {
                                                Spacer(Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Synopsis
                        if (detail.content.isNotBlank() || detail.actor.isNotBlank()) {
                            item {
                                Card(
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Text("影片简介", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                        if (detail.director.isNotBlank()) {
                                            Spacer(Modifier.height(6.dp))
                                            Text("导演：${detail.director}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        if (detail.actor.isNotBlank()) {
                                            Spacer(Modifier.height(4.dp))
                                            Text("主演：${detail.actor}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        if (detail.content.isNotBlank()) {
                                            Spacer(Modifier.height(8.dp))
                                            Text(detail.content, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    // Direct stream (Live TV or Sniffed video) info card
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = displayTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (playerState.isLiveStream) "实时高清直播流" else "直链高清视频播放",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (playerState.isLiveStream && playerState.liveBackupUrls.size > 1) {
                            Text(
                                text = "切换直播线路 (${playerState.liveBackupUrls.size} 条可用)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                itemsIndexed(playerState.liveBackupUrls) { idx, _ ->
                                    FilterChip(
                                        selected = idx == playerState.liveBackupIndex,
                                        onClick = { viewModel.selectLiveBackupLine(idx) },
                                        label = { Text("线路 ${idx + 1}") }
                                    )
                                }
                            }
                        }
                        FilledTonalButton(
                            onClick = { showCastSheet = true },
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Rounded.Cast, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("投屏到电视 (DLNA)")
                        }
                    }
                }
            }
        }
    }

    if (showCastSheet) {
        CastBottomSheet(
            dlnaController = viewModel.dlnaController,
            mediaTitle = displayTitle,
            mediaUrl = streamUrl,
            onDismiss = { showCastSheet = false }
        )
    }
}

private fun setSystemBarsVisible(activity: Activity, visible: Boolean) {
    val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    if (visible) {
        controller.show(WindowInsetsCompat.Type.systemBars())
    } else {
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}
