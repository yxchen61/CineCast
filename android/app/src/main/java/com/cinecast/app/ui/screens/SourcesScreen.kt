package com.cinecast.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cinecast.app.data.model.SourceType
import com.cinecast.app.data.model.VideoSource
import com.cinecast.app.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    viewModel: MainViewModel,
    onOpenWebSniffer: (url: String, title: String) -> Unit,
    onBack: (() -> Unit)? = null
) {
    val sources by viewModel.sources.collectAsState()
    val subscriptions by viewModel.subscriptions.collectAsState()
    val parses by viewModel.parses.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()

    var subUrlInput by remember { mutableStateOf("https://cyao2q.github.io/files/m.json") }
    var filterTab by remember { mutableIntStateOf(0) } // 0=全部 1=直连 2=网页嗅探 3=TVBox爬虫
    var showAddSourceDialog by remember { mutableStateOf(false) }
    var editingSource by remember { mutableStateOf<VideoSource?>(null) }
    var showPasteJsonDialog by remember { mutableStateOf(false) }

    val filteredSources = remember(sources, filterTab) {
        when (filterTab) {
            1 -> sources.filter { it.type in setOf(SourceType.MACCMS_JSON, SourceType.MACCMS_XML, SourceType.T4_API) }
            2 -> sources.filter { it.type == SourceType.WEB_SNIFFER }
            3 -> sources.filter { it.type == SourceType.TVBOX_SPIDER }
            else -> sources
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                title = { Text("影视源配置", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { showPasteJsonDialog = true }) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = "粘贴JSON配置")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    editingSource = null
                    showAddSourceDialog = true
                },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("添加视频源") }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // TVBox Subscription Import Card (inspired by cyao2q/files)
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "导入 TVBox 配置订阅 (兼容 cyao2q/files)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "自动解析配置中的直连影视源 (Type 0/1/4)、直播源 (lives)、VIP 解析接口 (parses) 及广告过滤规则 (ads)。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))

                        OutlinedTextField(
                            value = subUrlInput,
                            onValueChange = { subUrlInput = it },
                            label = { Text("配置订阅地址 (JSON URL)") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))

                        // Preset quick chips
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(subscriptions, key = { it.id }) { sub ->
                                SuggestionChip(
                                    onClick = { subUrlInput = sub.url },
                                    label = { Text(sub.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { viewModel.importTvBoxUrl(subUrlInput) },
                                enabled = !isImporting && subUrlInput.isNotBlank(),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isImporting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("正在同步...")
                                } else {
                                    Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("拉取并导入配置")
                                }
                            }
                            OutlinedButton(
                                onClick = { showPasteJsonDialog = true },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("粘贴 JSON")
                            }
                        }
                    }
                }
            }

            // Quick stats & Filter chips
            item {
                val filterLabels = listOf(
                    "全部 (${sources.size})",
                    "高清直连 (${sources.count { it.type in setOf(SourceType.MACCMS_JSON, SourceType.MACCMS_XML, SourceType.T4_API) }})",
                    "网页嗅探 (${sources.count { it.type == SourceType.WEB_SNIFFER }})",
                    "爬虫源 (${sources.count { it.type == SourceType.TVBOX_SPIDER }})"
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    filterLabels.forEachIndexed { idx, label ->
                        item {
                            FilterChip(
                                selected = filterTab == idx,
                                onClick = { filterTab = idx },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }

            // Source items
            items(filteredSources, key = { it.id }) { source ->
                SourceManageCard(
                    source = source,
                    onToggle = { viewModel.toggleSourceEnabled(source) },
                    onTest = { viewModel.testSourceLatency(source) },
                    onEdit = {
                        editingSource = source
                        showAddSourceDialog = true
                    },
                    onDelete = { viewModel.deleteSource(source.id) },
                    onOpenWeb = if (source.type == SourceType.WEB_SNIFFER) {
                        { onOpenWebSniffer(source.apiUrl, source.name) }
                    } else null
                )
            }

            // Parses summary card
            if (parses.isNotEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                "已加载网页解析接口 (${parses.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "在「网页嗅探浏览器」中点击顶部魔法棒图标即可调用以下解析线路：",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(parses) { p ->
                                    AssistChip(onClick = {}, label = { Text(p.name) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddSourceDialog) {
        AddOrEditSourceDialog(
            initial = editingSource,
            onDismiss = { showAddSourceDialog = false },
            onSave = { name, type, url, group ->
                viewModel.saveCustomSource(editingSource?.id, name, type, url, group)
                showAddSourceDialog = false
            }
        )
    }

    if (showPasteJsonDialog) {
        var rawJson by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPasteJsonDialog = false },
            title = { Text("粘贴 TVBox JSON 配置") },
            text = {
                OutlinedTextField(
                    value = rawJson,
                    onValueChange = { rawJson = it },
                    placeholder = { Text("粘贴完整的 m.json / xs.json 内容...") },
                    minLines = 6,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (rawJson.isNotBlank()) {
                        viewModel.importTvBoxJsonText(rawJson)
                        showPasteJsonDialog = false
                    }
                }) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { showPasteJsonDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SourceManageCard(
    source: VideoSource,
    onToggle: () -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenWeb: (() -> Unit)?
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = source.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = source.type.badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        source.latencyMs?.let { ms ->
                            Spacer(Modifier.width(6.dp))
                            val text = when {
                                ms == -2L -> "仅TVBox"
                                ms < 0L -> "超时"
                                else -> "${ms}ms"
                            }
                            Surface(
                                color = if (ms > 0) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = text,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = source.apiUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(checked = source.enabled, onCheckedChange = { onToggle() })
            }

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (onOpenWeb != null) {
                    TextButton(onClick = onOpenWeb) {
                        Icon(Icons.Rounded.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("打开嗅探")
                    }
                }
                TextButton(onClick = onTest) {
                    Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("测速")
                }
                TextButton(onClick = onEdit) {
                    Text("编辑")
                }
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun AddOrEditSourceDialog(
    initial: VideoSource?,
    onDismiss: () -> Unit,
    onSave: (name: String, type: SourceType, url: String, group: String) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var url by remember { mutableStateOf(initial?.apiUrl.orEmpty()) }
    var group by remember { mutableStateOf(initial?.group ?: "自定义源") }
    var type by remember { mutableStateOf(initial?.type ?: SourceType.MACCMS_JSON) }

    val selectableTypes = listOf(
        SourceType.MACCMS_JSON,
        SourceType.MACCMS_XML,
        SourceType.T4_API,
        SourceType.WEB_SNIFFER
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加自定义视频源" else "编辑视频源") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("视频源名称") },
                    placeholder = { Text("例如：非凡影视 / B站网页") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("接口地址 / 网站 URL") },
                    placeholder = { Text("https://.../api.php/provide/vod/") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = group,
                    onValueChange = { group = it },
                    label = { Text("分组标签") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("协议类型", style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    selectableTypes.forEach { t ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(selected = type == t, onClick = { type = t })
                            Text(t.displayName, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && url.isNotBlank()) {
                        onSave(name, type, url, group)
                    }
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
