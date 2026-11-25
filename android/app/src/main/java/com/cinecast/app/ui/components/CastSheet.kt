package com.cinecast.app.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cinecast.app.cast.CastSessionState
import com.cinecast.app.cast.DlnaController
import com.cinecast.app.cast.DlnaDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastBottomSheet(
    dlnaController: DlnaController,
    mediaTitle: String,
    mediaUrl: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val devices by dlnaController.devices.collectAsState()
    val isScanning by dlnaController.isScanning.collectAsState()
    val session by dlnaController.session.collectAsState()

    LaunchedEffect(Unit) {
        dlnaController.startDiscovery()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Cast,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "投屏到电视 (DLNA)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "请确保手机与电视连接在同一 Wi-Fi",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                FilledTonalIconButton(
                    onClick = { dlnaController.startDiscovery() },
                    enabled = !isScanning
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = "重新扫描")
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Active casting session remote control
            if (session.connectedDevice != null) {
                ActiveCastRemoteCard(
                    session = session,
                    onPlayPause = {
                        if (session.transportState == "PLAYING") dlnaController.pause() else dlnaController.play()
                    },
                    onSeek = { dlnaController.seekTo(it) },
                    onVolDown = { dlnaController.adjustVolume(-8) },
                    onVolUp = { dlnaController.adjustVolume(8) },
                    onDisconnect = { dlnaController.stopAndDisconnect() }
                )
                Spacer(Modifier.height(14.dp))
            }

            session.errorMessage?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // Discovered device list
            Text(
                text = if (isScanning && devices.isEmpty()) "正在局域网搜索支持 DLNA 的电视/盒子..." else "可用设备 (${devices.size})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            if (devices.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.TvOff,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (isScanning) "搜索中，请稍候..." else "暂未发现局域网 DLNA 设备",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "请在电视上打开投屏接收（如乐播投屏、系统投屏服务）后点击刷新",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(devices, key = { it.udn }) { device ->
                        val isConnected = session.connectedDevice?.udn == device.udn
                        DeviceRow(
                            device = device,
                            isConnected = isConnected,
                            onClick = {
                                if (mediaUrl.isNotBlank()) {
                                    dlnaController.castMedia(device, mediaUrl, mediaTitle.ifBlank { "极光视界视频" })
                                }
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))

            // Fallback options: System Miracast & External Player
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_CAST_SETTINGS))
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.ScreenShare, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("系统无线投屏", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                if (mediaUrl.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(Uri.parse(mediaUrl), "video/*")
                                    putExtra("title", mediaTitle)
                                }
                                context.startActivity(Intent.createChooser(intent, "选择外部播放器"))
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("外部播放器", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: DlnaDevice,
    isConnected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (isConnected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isConnected) Icons.Rounded.CastConnected else Icons.Rounded.Tv,
                contentDescription = null,
                tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.friendlyName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = device.manufacturer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            if (isConnected) {
                AssistChip(
                    onClick = onClick,
                    label = { Text("已连接") }
                )
            } else {
                Text(
                    text = "点击投屏",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ActiveCastRemoteCard(
    session: CastSessionState,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolDown: () -> Unit,
    onVolUp: () -> Unit,
    onDisconnect: () -> Unit
) {
    var sliderPos by remember(session.positionMs) { mutableFloatStateOf(session.positionMs.toFloat()) }
    val duration = session.durationMs.coerceAtLeast(1L)

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "正在投屏 · ${session.connectedDevice?.friendlyName.orEmpty()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = session.mediaTitle.ifBlank { "视频播放中" },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = onDisconnect) {
                    Text("退出投屏")
                }
            }

            if (session.durationMs > 0) {
                Slider(
                    value = sliderPos.coerceIn(0f, duration.toFloat()),
                    onValueChange = { sliderPos = it },
                    onValueChangeFinished = { onSeek(sliderPos.toLong()) },
                    valueRange = 0f..duration.toFloat()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatDurationMs(sliderPos.toLong()), style = MaterialTheme.typography.labelSmall)
                    Text(formatDurationMs(session.durationMs), style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onVolDown) {
                    Icon(Icons.Rounded.VolumeDown, contentDescription = "音量减")
                }
                IconButton(onClick = { onSeek((session.positionMs - 15_000L).coerceAtLeast(0L)) }) {
                    Icon(Icons.Rounded.Replay10, contentDescription = "快退")
                }
                FilledIconButton(onClick = onPlayPause) {
                    Icon(
                        imageVector = if (session.transportState == "PLAYING") Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = "播放/暂停"
                    )
                }
                IconButton(onClick = { onSeek(session.positionMs + 15_000L) }) {
                    Icon(Icons.Rounded.Forward10, contentDescription = "快进")
                }
                IconButton(onClick = onVolUp) {
                    Icon(Icons.Rounded.VolumeUp, contentDescription = "音量加")
                }
            }
        }
    }
}

fun formatDurationMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
