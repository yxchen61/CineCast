package com.cinecast.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cinecast.app.data.model.LiveChannel
import com.cinecast.app.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    viewModel: MainViewModel,
    onPlayLiveChannel: (title: String, urls: List<String>) -> Unit,
    onCastLiveChannel: (title: String, url: String) -> Unit
) {
    val liveSources by viewModel.liveSources.collectAsState()
    val liveState by viewModel.liveState.collectAsState()
    var showAddLiveDialog by remember { mutableStateOf(false) }

    LaunchedEffect(liveSources) {
        if (liveState.selectedSourceId.isBlank() && liveSources.isNotEmpty()) {
            viewModel.loadLiveSource(liveSources.first(), autoFallback = true)
        }
    }

    val filteredChannels = remember(liveState.channels, liveState.selectedGroup) {
        if (liveState.selectedGroup == "全部") liveState.channels
        else liveState.channels.filter { it.group == liveState.selectedGroup }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("高清直播", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = {
                        liveSources.firstOrNull { it.id == liveState.selectedSourceId }?.let {
                            viewModel.loadLiveSource(it)
                        }
                    }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新直播源")
                    }
                    IconButton(onClick = { showAddLiveDialog = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = "添加直播源")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Live source selector row
            if (liveSources.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(liveSources, key = { it.id }) { src ->
                        FilterChip(
                            selected = src.id == liveState.selectedSourceId,
                            onClick = { viewModel.loadLiveSource(src) },
                            label = { Text(src.name) },
                            leadingIcon = {
                                Icon(Icons.Rounded.LiveTv, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                }
            }

            // Group filter chips
            if (liveState.groups.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(liveState.groups) { grp ->
                        AssistChip(
                            onClick = { viewModel.selectLiveGroup(grp) },
                            label = { Text(grp) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (grp == liveState.selectedGroup)
                                    MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                        )
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    liveState.isLoading -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(10.dp))
                            Text("正在解析 M3U / TXT 直播频道列表...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    liveState.error != null -> {
                        EmptyStateBox(
                            icon = Icons.Rounded.TvOff,
                            title = liveState.error!!,
                            subtitle = "可点击右上角 + 添加自定义 M3U/TXT 直播源，或在「影视源」导入 TVBox 配置",
                            actionLabel = "重试",
                            onAction = {
                                liveSources.firstOrNull { it.id == liveState.selectedSourceId }?.let {
                                    viewModel.loadLiveSource(it)
                                }
                            }
                        )
                    }
                    filteredChannels.isEmpty() -> {
                        EmptyStateBox(
                            icon = Icons.Rounded.LiveTv,
                            title = "暂无直播频道",
                            subtitle = "点击右上角 + 添加 M3U 或 TVBox TXT 直播源地址"
                        )
                    }
                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filteredChannels, key = { "${it.group}_${it.name}" }) { channel ->
                                LiveChannelRow(
                                    channel = channel,
                                    onPlay = { onPlayLiveChannel(channel.name, channel.urls) },
                                    onCast = { onCastLiveChannel(channel.name, channel.urls.first()) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddLiveDialog) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddLiveDialog = false },
            title = { Text("添加自定义直播源") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "支持标准 M3U / M3U8 以及 TVBox TXT 格式直播订阅链接",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("名称") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("直播源 URL") },
                        placeholder = { Text("https://.../iptv.m3u") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (url.isNotBlank()) {
                            viewModel.addCustomLiveSource(name, url)
                            showAddLiveDialog = false
                        }
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showAddLiveDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun LiveChannelRow(
    channel: LiveChannel,
    onPlay: () -> Unit,
    onCast: () -> Unit
) {
    Surface(
        onClick = onPlay,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Tv, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${channel.group} · ${channel.urls.size} 条高清源",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onCast) {
                Icon(Icons.Rounded.Cast, contentDescription = "投屏直播")
            }
            FilledTonalIconButton(onClick = onPlay) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "播放")
            }
        }
    }
}
