package com.cinecast.app.ui.screens

import android.os.Build
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cinecast.app.data.model.ThemeMode
import com.cinecast.app.ui.components.formatDurationMs
import com.cinecast.app.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: MainViewModel,
    onOpenVod: (sourceId: String, vodId: String) -> Unit,
    onOpenVideoSources: () -> Unit = {}
) {
    val history by viewModel.history.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val clipboard = LocalClipboardManager.current

    var showRestoreDialog by remember { mutableStateOf(false) }
    var restoreInput by remember { mutableStateOf("") }
    var exportedNotice by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的媒体库", fontWeight = FontWeight.SemiBold) }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Video Sources Quick Entry (separated from Novel & Music sources)
            item {
                Card(
                    onClick = onOpenVideoSources,
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("影视源与 TVBox 订阅管理", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("独立管理视频采集源、VIP 解析与广告过滤（小说与音乐源在各自模块独立管理）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                    }
                }
            }

            // Continue Watching / History
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("继续观看 (${history.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (history.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearWatchHistory() }) { Text("清空历史") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (history.isEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "暂无播放记录，开始观影后将自动记录进度支持断点续播",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(history.take(20), key = { "${it.sourceId}_${it.vodId}" }) { item ->
                            Card(
                                onClick = { onOpenVod(item.sourceId, item.vodId) },
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                modifier = Modifier.width(190.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "${item.episodeName.ifBlank { "正片" }} · ${formatDurationMs(item.positionMs)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1
                                    )
                                    if (item.durationMs > 0) {
                                        Spacer(Modifier.height(6.dp))
                                        LinearProgressIndicator(
                                            progress = { (item.positionMs.toFloat() / item.durationMs.toFloat()).coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Favorites
            item {
                Text("我的收藏 (${favorites.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                if (favorites.isEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "暂无收藏影片，在播放详情页点击「收藏影片」即可加入",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        favorites.forEach { fav ->
                            Surface(
                                onClick = { onOpenVod(fav.sourceId, fav.vodId) },
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(fav.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            "${fav.sourceName} ${fav.remarks}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                                }
                            }
                        }
                    }
                }
            }

            // Google Material 3 Appearance & Player Settings
            item {
                Text("外观与播放设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("主题风格 (Google Material Design 3)", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemeMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = settings.themeMode == mode,
                                    onClick = { viewModel.updateThemeMode(mode) },
                                    label = { Text(mode.label) }
                                )
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Material You 动态壁纸取色", style = MaterialTheme.typography.titleSmall)
                                    Text("根据 Android 12+ 系统壁纸自动适配主题色彩", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = settings.dynamicColor,
                                    onCheckedChange = { viewModel.updateDynamicColor(it) }
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("自动连播下一集", style = MaterialTheme.typography.titleSmall)
                                Text("当前集播放完成后自动切换至下一集", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = settings.autoNextEpisode,
                                onCheckedChange = { viewModel.updateAutoNext(it) }
                            )
                        }
                    }
                }
            }

            // Backup & Restore
            item {
                Text("配置备份与恢复", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            val json = viewModel.repository.exportBackupJson()
                            clipboard.setText(AnnotatedString(json))
                            exportedNotice = true
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("复制导出配置")
                    }
                    OutlinedButton(
                        onClick = { showRestoreDialog = true },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("粘贴恢复配置")
                    }
                }
                if (exportedNotice) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "完整配置 JSON 已复制到剪贴板，可保存至备忘录或分享给其他设备。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            item {
                Text(
                    "极光视界 CineCast v1.6.0 · 影视 · 阳阳专区 · 阅读听评书 · 全曲音乐",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text("恢复备份配置 JSON") },
            text = {
                OutlinedTextField(
                    value = restoreInput,
                    onValueChange = { restoreInput = it },
                    placeholder = { Text("粘贴之前导出的 CineCast 配置 JSON...") },
                    minLines = 5,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (restoreInput.isNotBlank()) {
                        viewModel.importBackup(restoreInput)
                        showRestoreDialog = false
                    }
                }) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) { Text("取消") }
            }
        )
    }
}
