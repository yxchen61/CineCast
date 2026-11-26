package com.cinecast.app.novel.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cinecast.app.novel.model.ReaderTheme
import com.cinecast.app.novel.viewmodel.NovelViewModel

/**
 * Google Play Books 风格沉浸式小说阅读器
 * 支持：点击屏幕唤出/隐藏工具栏、4 套护眼纸感主题、字号/行距/边距/衬线字体调节、目录跳转、在线一键换源
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelReaderScreen(
    viewModel: NovelViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.readerState.collectAsState()
    val settings by viewModel.readingSettings.collectAsState()

    var showControls by remember { mutableStateOf(false) }
    var showTocSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showSwitchSourceSheet by remember { mutableStateOf(false) }
    var reverseToc by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    val theme = settings.readerTheme
    val bgColor = Color(theme.backgroundHex)
    val textColor = Color(theme.textHex)
    val secondaryColor = Color(theme.secondaryTextHex)
    val panelColor = Color(theme.surfaceHex)

    // Scroll back to top whenever chapter changes
    LaunchedEffect(state.currentChapterIndex, state.content) {
        scrollState.scrollTo(0)
    }

    val currentChapter = state.chapters.getOrNull(state.currentChapterIndex)
    val fontFamily = if (settings.useSerifFont) FontFamily.Serif else FontFamily.SansSerif

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        // Main Distraction-Free Reading Surface
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    showControls = !showControls
                }
        ) {
            // Subtle Top Running Header (Google Play Books style)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = settings.horizontalMarginDp.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = currentChapter?.title ?: state.book?.title.orEmpty(),
                    fontSize = 12.sp,
                    color = secondaryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (state.chapters.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${state.currentChapterIndex + 1} / ${state.chapters.size}",
                        fontSize = 12.sp,
                        color = secondaryColor
                    )
                }
            }

            // Chapter Text Area
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isLoading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(10.dp))
                                Text("正在加载章节正文...", color = secondaryColor, fontSize = 13.sp)
                            }
                        }
                    }

                    state.error != null -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(28.dp)
                            ) {
                                Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = secondaryColor, modifier = Modifier.size(40.dp))
                                Spacer(Modifier.height(10.dp))
                                Text(state.error ?: "加载失败", color = textColor, fontSize = 14.sp)
                                Spacer(Modifier.height(14.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    FilledTonalButton(onClick = { viewModel.loadChapter(state.currentChapterIndex) }) {
                                        Text("重试本章")
                                    }
                                    Button(onClick = {
                                        viewModel.searchAlternativeSourcesForCurrentBook()
                                        showSwitchSourceSheet = true
                                    }) {
                                        Text("换源阅读")
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        val paragraphs = remember(state.content) {
                            state.content.split("\n\n").map { it.trim() }.filter { it.isNotBlank() }
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .padding(
                                    horizontal = settings.horizontalMarginDp.dp,
                                    vertical = 12.dp
                                )
                        ) {
                            // Chapter Title Header
                            Text(
                                text = currentChapter?.title.orEmpty(),
                                fontSize = (settings.fontSizeSp + 5f).sp,
                                lineHeight = ((settings.fontSizeSp + 5f) * 1.35f).sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = fontFamily,
                                color = textColor
                            )
                            Spacer(Modifier.height(20.dp))

                            paragraphs.forEach { para ->
                                Text(
                                    text = "　　$para",
                                    fontSize = settings.fontSizeSp.sp,
                                    lineHeight = (settings.fontSizeSp * settings.lineHeightMultiplier).sp,
                                    fontFamily = fontFamily,
                                    color = textColor
                                )
                                Spacer(Modifier.height(settings.paragraphSpacingDp.dp))
                            }

                            Spacer(Modifier.height(28.dp))

                            // End-of-chapter Navigation Bar
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.prevChapter() },
                                    enabled = state.currentChapterIndex > 0,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("上一章")
                                }
                                Button(
                                    onClick = { viewModel.nextChapter() },
                                    enabled = state.currentChapterIndex + 1 < state.chapters.size,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("下一章")
                                }
                            }
                            Spacer(Modifier.height(36.dp))
                        }
                    }
                }
            }
        }

        // Top Floating Material 3 AppBar Overlay
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                color = panelColor.copy(alpha = 0.96f),
                tonalElevation = 4.dp,
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = textColor)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            state.book?.title.orEmpty(),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            state.source?.name.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = secondaryColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = {
                        viewModel.searchAlternativeSourcesForCurrentBook()
                        showSwitchSourceSheet = true
                    }) {
                        Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("换源")
                    }
                }
            }
        }

        // Bottom Floating Google Play Books Control Dock
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                color = panelColor.copy(alpha = 0.97f),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                tonalElevation = 6.dp,
                shadowElevation = 10.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // Chapter progress slider (interactive jump)
                    if (state.chapters.size > 1) {
                        var sliderPos by remember(state.currentChapterIndex) {
                            mutableFloatStateOf(state.currentChapterIndex.toFloat())
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { viewModel.prevChapter() },
                                enabled = state.currentChapterIndex > 0
                            ) { Text("上一章") }
                            Slider(
                                value = sliderPos,
                                onValueChange = { sliderPos = it },
                                onValueChangeFinished = {
                                    val target = sliderPos.toInt().coerceIn(0, state.chapters.lastIndex)
                                    if (target != state.currentChapterIndex) {
                                        viewModel.loadChapter(target)
                                    }
                                },
                                valueRange = 0f..(state.chapters.lastIndex.coerceAtLeast(1)).toFloat(),
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { viewModel.nextChapter() },
                                enabled = state.currentChapterIndex + 1 < state.chapters.size
                            ) { Text("下一章") }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ReaderDockAction(
                            icon = Icons.AutoMirrored.Rounded.List,
                            label = "目录",
                            tint = textColor,
                            onClick = { showTocSheet = true }
                        )
                        ReaderDockAction(
                            icon = Icons.Rounded.TextFields,
                            label = "排版主题",
                            tint = textColor,
                            onClick = { showSettingsSheet = true }
                        )
                        ReaderDockAction(
                            icon = if (theme == ReaderTheme.NIGHT) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                            label = if (theme == ReaderTheme.NIGHT) "日间" else "夜间",
                            tint = textColor,
                            onClick = {
                                viewModel.updateReaderTheme(
                                    if (theme == ReaderTheme.NIGHT) ReaderTheme.PAPER else ReaderTheme.NIGHT
                                )
                            }
                        )
                        ReaderDockAction(
                            icon = Icons.Rounded.Refresh,
                            label = "刷新",
                            tint = textColor,
                            onClick = { viewModel.loadChapter(state.currentChapterIndex) }
                        )
                    }
                }
            }
        }
    }

    // ==================== 目录抽屉 BottomSheet ====================
    if (showTocSheet) {
        ModalBottomSheet(onDismissRequest = { showTocSheet = false }) {
            val displayChapters = remember(state.chapters, reverseToc) {
                if (reverseToc) state.chapters.reversed() else state.chapters
            }
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = if (reverseToc) 0 else state.currentChapterIndex.coerceAtLeast(0)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "目录 (${state.chapters.size} 章)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = { reverseToc = !reverseToc }) {
                        Icon(Icons.Rounded.SwapVert, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (reverseToc) "正序" else "倒序")
                    }
                }
                HorizontalDivider()
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp)
                ) {
                    items(displayChapters, key = { it.index }) { ch ->
                        val isCurrent = ch.index == state.currentChapterIndex
                        ListItem(
                            headlineContent = {
                                Text(
                                    ch.title,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            trailingContent = if (isCurrent) {
                                { Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                            } else null,
                            modifier = Modifier.clickable {
                                showTocSheet = false
                                showControls = false
                                viewModel.loadChapter(ch.index)
                            }
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // ==================== 排版与护眼主题设置 BottomSheet ====================
    if (showSettingsSheet) {
        ModalBottomSheet(onDismissRequest = { showSettingsSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("阅读排版与主题 (Google Material 3)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                // 4 Paper / Eye-care themes
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ReaderTheme.entries.forEach { t ->
                        val selected = settings.readerTheme == t
                        Surface(
                            onClick = { viewModel.updateReaderTheme(t) },
                            shape = RoundedCornerShape(14.dp),
                            color = Color(t.backgroundHex),
                            border = BorderStroke(
                                width = if (selected) 2.5.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp)
                            ) {
                                Text("Aa", color = Color(t.textHex), fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(2.dp))
                                Text(t.label, fontSize = 11.sp, color = Color(t.secondaryTextHex), maxLines = 1)
                            }
                        }
                    }
                }

                // Font Size Slider
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("字号大小", style = MaterialTheme.typography.titleSmall)
                        Text("${settings.fontSizeSp.toInt()} sp", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    }
                    Slider(
                        value = settings.fontSizeSp,
                        onValueChange = { viewModel.updateFontSize(it) },
                        valueRange = 14f..28f
                    )
                }

                // Line Height Slider
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("行间距", style = MaterialTheme.typography.titleSmall)
                        Text("${String.format("%.2f", settings.lineHeightMultiplier)}x", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    }
                    Slider(
                        value = settings.lineHeightMultiplier,
                        onValueChange = { viewModel.updateLineHeight(it) },
                        valueRange = 1.4f..2.2f
                    )
                }

                // Serif / Sans-serif toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("衬线书籍字体 (Serif)", style = MaterialTheme.typography.titleSmall)
                        Text("切换传统印刷衬线体或现代谷歌无衬线体", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settings.useSerifFont,
                        onCheckedChange = { viewModel.updateUseSerif(it) }
                    )
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // ==================== 在线换源 BottomSheet ====================
    if (showSwitchSourceSheet) {
        ModalBottomSheet(onDismissRequest = { showSwitchSourceSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("切换书源 · ${state.book?.title.orEmpty()}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("正在从其他已启用的 GitHub 小说源并发检索同名书籍...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.isSearchingSwitchSources) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(10.dp))
                if (state.switchSourceCandidates.isEmpty() && !state.isSearchingSwitchSources) {
                    Text(
                        "暂未在其他书源中找到同名书籍，可在「书源」页导入更多 GitHub 书源仓库。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 18.dp)
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.heightIn(max = 360.dp)
                    ) {
                        items(state.switchSourceCandidates, key = { "${it.sourceId}::${it.bookUrl}" }) { alt ->
                            Surface(
                                onClick = {
                                    showSwitchSourceSheet = false
                                    showControls = false
                                    viewModel.switchToAlternativeBook(alt)
                                },
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(alt.sourceName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                    Text("${alt.title} · ${alt.author.ifBlank { "未知作者" }}", style = MaterialTheme.typography.bodySmall)
                                    if (alt.latestChapter.isNotBlank()) {
                                        Text("最新：${alt.latestChapter}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun ReaderDockAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 11.sp, color = tint)
    }
}
