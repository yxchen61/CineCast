package com.cinecast.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cinecast.app.data.engine.VodSourceEngine
import com.cinecast.app.data.model.SourceType
import com.cinecast.app.data.model.VideoSource
import com.cinecast.app.data.model.VodCategory
import com.cinecast.app.data.model.VodItem
import com.cinecast.app.ui.components.HeroSpotlightCarousel
import com.cinecast.app.ui.components.HeroSpotlightItem
import com.cinecast.app.ui.components.PosterGridSkeleton
import com.cinecast.app.ui.components.VideoPosterCard
import com.cinecast.app.ui.components.formatDurationMs
import com.cinecast.app.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

private enum class FeedSortMode(val label: String) {
    DEFAULT("默认最新"),
    SCORE_DESC("高分优先"),
    YEAR_DESC("年份最新")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onOpenVod: (sourceId: String, vodId: String) -> Unit,
    onOpenWebSniffer: (url: String, title: String) -> Unit,
    onOpenCastSheet: () -> Unit,
    onManageVideoSources: () -> Unit = {}
) {
    val homeState by viewModel.homeState.collectAsState()
    val searchState by viewModel.searchState.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val activeSourceId by viewModel.activeSourceId.collectAsState()
    val history by viewModel.history.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val castSession by viewModel.dlnaController.session.collectAsState()

    val apiSources = remember(sources) {
        sources.filter {
            it.enabled && it.type in setOf(SourceType.MACCMS_JSON, SourceType.MACCMS_XML, SourceType.T4_API)
        }
    }
    val webSources = remember(sources) {
        sources.filter { it.enabled && it.type == SourceType.WEB_SNIFFER }
    }
    val activeSource = remember(sources, activeSourceId) {
        sources.firstOrNull { it.id == activeSourceId } ?: apiSources.firstOrNull()
    }

    var isSearchMode by remember { mutableStateOf(false) }
    var showSourcePicker by remember { mutableStateOf(false) }
    var showCategorySheet by remember { mutableStateOf(false) }
    var selectedYearFilter by remember { mutableStateOf("全部") }
    var sortMode by remember { mutableStateOf(FeedSortMode.DEFAULT) }
    var homeSectionTab by remember { mutableIntStateOf(0) } // 0 = 最新热播, 1 = 我的收藏

    val focusManager = LocalFocusManager.current
    val searchFocusRequester = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()

    val showBackToTop by remember {
        derivedStateOf { gridState.firstVisibleItemIndex > 4 }
    }

    val hasExtraFilter = selectedYearFilter != "全部" || sortMode != FeedSortMode.DEFAULT

    // Apply local year/sort refinements smoothly on loaded items
    val displayItems = remember(homeState.items, selectedYearFilter, sortMode) {
        val filtered = when (selectedYearFilter) {
            "全部" -> homeState.items
            "更早" -> homeState.items.filter { (it.year.toIntOrNull() ?: 9999) < 2023 }
            else -> homeState.items.filter { it.year == selectedYearFilter }
        }
        when (sortMode) {
            FeedSortMode.DEFAULT -> filtered
            FeedSortMode.SCORE_DESC -> filtered.sortedByDescending { it.score.toDoubleOrNull() ?: 0.0 }
            FeedSortMode.YEAR_DESC -> filtered.sortedByDescending { it.year.toIntOrNull() ?: 0 }
        }
    }

    LaunchedEffect(isSearchMode) {
        if (isSearchMode) {
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    // Trigger load more near bottom
    LaunchedEffect(gridState) {
        snapshotFlow {
            val layout = gridState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= (layout.totalItemsCount - 6) && layout.totalItemsCount > 0
        }.collect { shouldLoadMore ->
            if (shouldLoadMore && !isSearchMode && !homeState.isLoading && !homeState.isLoadingMore) {
                viewModel.loadHomeFeed(reset = false)
            }
        }
    }

    // Category hierarchy helpers
    val topCategories = remember(homeState.categories) {
        val roots = homeState.categories.filter { it.pid == "0" || it.pid.isBlank() }
        if (roots.size >= 2) roots else homeState.categories
    }
    val activeParentId = remember(homeState.categories, homeState.selectedCategoryId) {
        val sel = homeState.categories.firstOrNull { it.id == homeState.selectedCategoryId }
        when {
            sel == null -> null
            sel.childIds.isNotEmpty() -> sel.id
            sel.pid != "0" && sel.pid.isNotBlank() -> sel.pid
            else -> null
        }
    }
    val subCategories = remember(homeState.categories, activeParentId) {
        if (activeParentId == null) emptyList()
        else homeState.categories.filter { it.pid == activeParentId }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Compact Google TV / Material 3 Expressive Header
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = if (showBackToTop) 2.dp else 0.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(top = 8.dp, bottom = 6.dp)
                ) {
                    if (isSearchMode) {
                        // Dedicated Google Search Bar Pill
                        Surface(
                            shape = RoundedCornerShape(28.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp)
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
                                    placeholder = { Text("搜索电影、剧集、动漫、演员...") },
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
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Text("搜索")
                                }
                                Spacer(Modifier.width(4.dp))
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = searchState.searchAllSources,
                                onClick = { viewModel.toggleSearchAllSources(true) },
                                label = { Text("全源聚合搜索") },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Hub, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            )
                            FilterChip(
                                selected = !searchState.searchAllSources,
                                onClick = { viewModel.toggleSearchAllSources(false) },
                                label = { Text("仅当前源") }
                            )
                        }
                    } else {
                        // Row 1: Unified Brand + Source Switcher Pill + Action Icons (Google TV style)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Inline Source Pill (Brand + Active Source)
                            Surface(
                                onClick = { showSourcePicker = true },
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = activeSource?.name?.substringBefore(" ") ?: "选择片源",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = "切换视频源",
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            Spacer(Modifier.weight(1f))

                            // Compact Search Pill Button
                            FilledTonalIconButton(
                                onClick = { isSearchMode = true },
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                            ) {
                                Icon(Icons.Rounded.Search, contentDescription = "搜索")
                            }
                            IconButton(onClick = { onOpenWebSniffer("https://m.bilibili.com/", "网页嗅探浏览器") }) {
                                Icon(
                                    Icons.Rounded.Language,
                                    contentDescription = "网页嗅探",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = onOpenCastSheet) {
                                Icon(
                                    imageVector = if (castSession.connectedDevice != null) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
                                    contentDescription = "投屏",
                                    tint = if (castSession.connectedDevice != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Row 2: Clean Primary Categories + 「阳阳专区」 (Pure English Cartoons) + Filter Trigger
                        Spacer(Modifier.height(6.dp))
                        val isYangyangSelected = homeState.selectedCategoryId == VodSourceEngine.YANGYANG_CATEGORY_ID
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                item {
                                    PrimaryCategoryPill(
                                        label = "推荐",
                                        selected = homeState.selectedCategoryId == null,
                                        onClick = {
                                            selectedYearFilter = "全部"
                                            viewModel.selectCategory(null)
                                        }
                                    )
                                }
                                item {
                                    Surface(
                                        onClick = {
                                            selectedYearFilter = "全部"
                                            viewModel.selectCategory(VodSourceEngine.YANGYANG_CATEGORY_ID)
                                        },
                                        shape = RoundedCornerShape(20.dp),
                                        color = if (isYangyangSelected) Color(0xFF188038) else Color(0xFFE6F4EA)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.AutoAwesome,
                                                contentDescription = null,
                                                tint = if (isYangyangSelected) Color.White else Color(0xFF137333),
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Text(
                                                text = "阳阳专区",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isYangyangSelected) Color.White else Color(0xFF137333)
                                            )
                                        }
                                    }
                                }
                                items(topCategories, key = { it.id }) { cat ->
                                    val isSelected = homeState.selectedCategoryId == cat.id || activeParentId == cat.id
                                    PrimaryCategoryPill(
                                        label = cat.name,
                                        selected = isSelected,
                                        onClick = {
                                            selectedYearFilter = "全部"
                                            viewModel.selectCategory(cat.id)
                                        }
                                    )
                                }
                            }

                            // Pinned Filter & Full Category Drawer Trigger
                            IconButton(onClick = { showCategorySheet = true }) {
                                BadgedBox(
                                    badge = {
                                        if (hasExtraFilter) {
                                            Badge(containerColor = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Tune,
                                        contentDescription = "全部分类与筛选",
                                        tint = if (hasExtraFilter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Row 3 (Animated): Sub-Categories Row for Yangyang Zone OR active parent category
                        AnimatedVisibility(
                            visible = isYangyangSelected,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            val yangFilters = listOf("全部", "少儿启蒙", "动画电影", "欧美英语动漫")
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(yangFilters) { f ->
                                    SubCategoryChip(
                                        label = if (f == "全部") "全部英文动画" else f,
                                        selected = homeState.yangyangSubFilter == f,
                                        onClick = { viewModel.selectYangyangSubFilter(f) }
                                    )
                                }
                                item {
                                    SubCategoryChip(
                                        label = "B站英文动画 ↗",
                                        selected = false,
                                        onClick = {
                                            onOpenWebSniffer(
                                                "https://m.bilibili.com/search?keyword=%E8%8B%B1%E6%96%87%E5%8A%A8%E7%94%BB%E7%89%87",
                                                "B站英文动画专区"
                                            )
                                        }
                                    )
                                }
                                item {
                                    SubCategoryChip(
                                        label = "PBS Kids ↗",
                                        selected = false,
                                        onClick = {
                                            onOpenWebSniffer("https://pbskids.org/video/", "PBS Kids 英语原版动画")
                                        }
                                    )
                                }
                            }
                        }

                        if (homeState.categories.isNotEmpty() && !isYangyangSelected) {
                            // Row 3 (Animated): Sub-Categories Row ONLY when a parent category is active
                            AnimatedVisibility(
                                visible = subCategories.isNotEmpty() && activeParentId != null,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    item {
                                        SubCategoryChip(
                                            label = "全部",
                                            selected = homeState.selectedCategoryId == activeParentId,
                                            onClick = { activeParentId?.let { viewModel.selectCategory(it) } }
                                        )
                                    }
                                    items(subCategories, key = { it.id }) { sub ->
                                        SubCategoryChip(
                                            label = sub.name,
                                            selected = homeState.selectedCategoryId == sub.id,
                                            onClick = { viewModel.selectCategory(sub.id) }
                                        )
                                    }
                                }
                            }
                        }

                        // Active Filter Summary Banner if user picked Year / Sort
                        if (hasExtraFilter) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (selectedYearFilter != "全部") {
                                    InputChip(
                                        selected = true,
                                        onClick = { selectedYearFilter = "全部" },
                                        label = { Text("年份：$selectedYearFilter", style = MaterialTheme.typography.labelSmall) },
                                        trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                    )
                                }
                                if (sortMode != FeedSortMode.DEFAULT) {
                                    InputChip(
                                        selected = true,
                                        onClick = { sortMode = FeedSortMode.DEFAULT },
                                        label = { Text("排序：${sortMode.label}", style = MaterialTheme.typography.labelSmall) },
                                        trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Main Body: Search Results or Discovery Feed
            if (isSearchMode) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (searchState.isSearching) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Box(modifier = Modifier.fillMaxSize()) {
                        when {
                            searchState.results.isNotEmpty() -> {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Text(
                                        text = if (searchState.isSearching)
                                            "已找到 ${searchState.results.size} 条结果，正在继续检索其余视频源..."
                                        else "共找到 ${searchState.results.size} 条高清结果",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                    )
                                    LazyVerticalGrid(
                                        columns = GridCells.Fixed(3),
                                        contentPadding = PaddingValues(14.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        items(searchState.results, key = { "${it.sourceId}_${it.id}" }) { item ->
                                            VideoPosterCard(
                                                item = item,
                                                showSourceBadge = true,
                                                onClick = { onOpenVod(item.sourceId, item.id) }
                                            )
                                        }
                                    }
                                }
                            }
                            searchState.isSearching -> {
                                PosterGridSkeleton()
                            }
                            searchState.error != null -> {
                                EmptyStateBox(
                                    icon = Icons.Rounded.SearchOff,
                                    title = searchState.error!!,
                                    actionLabel = "重试",
                                    onAction = { viewModel.executeSearch() }
                                )
                            }
                            else -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        "热门搜索",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    val hotKeywords = listOf("遮天", "庆余年", "斗破苍穹", "凡人修仙传", "流浪地球", "周星驰", "柯南", "海贼王")
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        hotKeywords.forEach { kw ->
                                            SuggestionChip(
                                                onClick = {
                                                    focusManager.clearFocus()
                                                    viewModel.executeSearch(kw)
                                                },
                                                label = { Text(kw) }
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(24.dp))
                                    EmptyStateBox(
                                        icon = Icons.Rounded.TravelExplore,
                                        title = "输入片名、剧名或演员开始极速搜片",
                                        subtitle = "9 源并发流式搜索，最快响应源将秒级呈现高清结果"
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        homeState.isLoading && homeState.items.isEmpty() -> {
                            PosterGridSkeleton()
                        }
                        homeState.error != null && homeState.items.isEmpty() -> {
                            EmptyStateBox(
                                icon = Icons.Rounded.CloudOff,
                                title = homeState.error!!,
                                subtitle = "可点击左上角切换其他高速视频源，或在「影视源」中添加源",
                                actionLabel = "重新加载",
                                onAction = { viewModel.loadHomeFeed(reset = true) }
                            )
                        }
                        displayItems.isEmpty() && hasExtraFilter -> {
                            EmptyStateBox(
                                icon = Icons.Rounded.FilterAltOff,
                                title = "当前筛选条件下暂无影片",
                                subtitle = "尝试清空年份筛选或切换其他分类查看更多内容",
                                actionLabel = "重置筛选",
                                onAction = {
                                    selectedYearFilter = "全部"
                                    sortMode = FeedSortMode.DEFAULT
                                }
                            )
                        }
                        else -> {
                            val showHero = homeState.selectedCategoryId == null && !hasExtraFilter && (displayItems.isNotEmpty() || history.isNotEmpty())
                            val heroSpotlightItems = remember(history, displayItems) {
                                val continueItems = history.take(5).map { h ->
                                    val progress = if (h.durationMs > 0L) {
                                        (h.positionMs.toFloat() / h.durationMs.toFloat()).coerceIn(0f, 1f)
                                    } else null
                                    val timeInfo = if (h.durationMs > 0L) {
                                        "${formatDurationMs(h.positionMs)} / ${formatDurationMs(h.durationMs)}"
                                    } else {
                                        formatDurationMs(h.positionMs)
                                    }
                                    HeroSpotlightItem(
                                        vodId = h.vodId,
                                        sourceId = h.sourceId,
                                        name = h.name,
                                        pic = h.pic,
                                        badgeText = "继续观看",
                                        subtitle = "上次看到：${h.episodeName.ifBlank { "正片" }} · $timeInfo",
                                        buttonText = "继续播放",
                                        isContinueWatching = true,
                                        progressFraction = progress
                                    )
                                }
                                val seenKeys = continueItems.map { "${it.sourceId}_${it.vodId}" }.toSet()
                                val needFill = (5 - continueItems.size).coerceAtLeast(0)
                                val featuredFill = displayItems
                                    .filterNot { "${it.sourceId}_${it.id}" in seenKeys }
                                    .take(needFill)
                                    .map { item ->
                                        val meta = listOf(
                                            item.typeName,
                                            item.year.takeIf { it != "0" }.orEmpty(),
                                            item.remarks
                                        ).filter { it.isNotBlank() }.joinToString(" · ")
                                        HeroSpotlightItem(
                                            vodId = item.id,
                                            sourceId = item.sourceId,
                                            name = item.name,
                                            pic = item.pic,
                                            badgeText = "今日精选",
                                            subtitle = meta.ifBlank { "高清直链 · 支持投屏" },
                                            buttonText = "播放",
                                            isContinueWatching = false,
                                            progressFraction = null
                                        )
                                    }
                                continueItems + featuredFill
                            }

                            val heroFeaturedVodIds = remember(heroSpotlightItems) {
                                heroSpotlightItems.filterNot { it.isContinueWatching }.map { it.vodId }.toSet()
                            }
                            val hotGridItems = remember(displayItems, showHero, heroFeaturedVodIds) {
                                if (showHero && displayItems.size > 6) {
                                    displayItems.filterNot { it.id in heroFeaturedVodIds }
                                } else displayItems
                            }
                            val favVodItems = remember(favorites) {
                                favorites.map { f ->
                                    VodItem(
                                        id = f.vodId,
                                        name = f.name,
                                        pic = f.pic,
                                        remarks = f.remarks,
                                        sourceId = f.sourceId,
                                        sourceName = f.sourceName
                                    )
                                }
                            }

                            LazyVerticalGrid(
                                state = gridState,
                                columns = GridCells.Fixed(3),
                                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                if (showHero) {
                                    item(span = { GridItemSpan(3) }) {
                                        Column {
                                            Spacer(Modifier.height(4.dp))
                                            HeroSpotlightCarousel(
                                                items = heroSpotlightItems,
                                                onPlayClick = { onOpenVod(it.sourceId, it.vodId) }
                                            )
                                            Spacer(Modifier.height(14.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // Inline Tab Switcher: 最新热播 | 我的收藏
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    HomeSectionTabPill(
                                                        label = "最新热播",
                                                        selected = homeSectionTab == 0,
                                                        onClick = { homeSectionTab = 0 }
                                                    )
                                                    HomeSectionTabPill(
                                                        label = if (favorites.isNotEmpty()) "我的收藏 (${favorites.size})" else "我的收藏",
                                                        selected = homeSectionTab == 1,
                                                        onClick = { homeSectionTab = 1 }
                                                    )
                                                }
                                                if (homeSectionTab == 0) {
                                                    TextButton(
                                                        onClick = { viewModel.loadHomeFeed(reset = true) },
                                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                                    ) {
                                                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                                        Spacer(Modifier.width(4.dp))
                                                        Text("换一批")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                if (showHero && homeSectionTab == 1) {
                                    if (favVodItems.isEmpty()) {
                                        item(span = { GridItemSpan(3) }) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                                shape = RoundedCornerShape(20.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 12.dp)
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(28.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.BookmarkBorder,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(40.dp),
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                                    )
                                                    Spacer(Modifier.height(10.dp))
                                                    Text("还没有收藏影片", style = MaterialTheme.typography.titleSmall)
                                                    Spacer(Modifier.height(4.dp))
                                                    Text(
                                                        "在任意影片播放页点击「收藏影片」后，将直接在此快捷展示",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        items(favVodItems, key = { "fav_${it.sourceId}_${it.id}" }) { item ->
                                            VideoPosterCard(
                                                item = item,
                                                showSourceBadge = true,
                                                onClick = { onOpenVod(item.sourceId, item.id) }
                                            )
                                        }
                                    }
                                } else {
                                    if (homeState.selectedCategoryId == VodSourceEngine.YANGYANG_CATEGORY_ID) {
                                        item(span = { GridItemSpan(3) }) {
                                            Surface(
                                                shape = RoundedCornerShape(18.dp),
                                                color = Color(0xFFE6F4EA),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        Icons.Rounded.AutoAwesome,
                                                        contentDescription = null,
                                                        tint = Color(0xFF137333),
                                                        modifier = Modifier.size(22.dp)
                                                    )
                                                    Spacer(Modifier.width(10.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            "阳阳专区 · 纯英文动画乐园",
                                                            style = MaterialTheme.typography.titleSmall,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color(0xFF0D652D)
                                                        )
                                                        Text(
                                                            "已严选 ${hotGridItems.size} 部英语原版动画，自动过滤非英语内容",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = Color(0xFF137333)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    items(hotGridItems, key = { "${it.sourceId}_${it.id}" }) { item ->
                                        VideoPosterCard(
                                            item = item,
                                            showSourceBadge = homeState.selectedCategoryId == VodSourceEngine.YANGYANG_CATEGORY_ID,
                                            onClick = { onOpenVod(item.sourceId, item.id) }
                                        )
                                    }

                                    if (homeState.isLoadingMore) {
                                        item(span = { GridItemSpan(3) }) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(16.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Back-to-top Mini FAB
        AnimatedVisibility(
            visible = !isSearchMode && showBackToTop,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            SmallFloatingActionButton(
                onClick = {
                    coroutineScope.launch { gridState.animateScrollToItem(0) }
                },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "回到顶部")
            }
        }
    }

    // Full Categorized Filter ModalBottomSheet (2-Tier Grid + Year + Sort)
    if (showCategorySheet) {
        ModalBottomSheet(
            onDismissRequest = { showCategorySheet = false },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .padding(horizontal = 20.dp),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "全部分类与筛选",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        TextButton(onClick = {
                            selectedYearFilter = "全部"
                            sortMode = FeedSortMode.DEFAULT
                            viewModel.selectCategory(null)
                            showCategorySheet = false
                        }) {
                            Text("重置全部")
                        }
                    }
                }

                // Sort Mode
                item {
                    Text("排序方式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FeedSortMode.entries.forEach { mode ->
                            FilterChip(
                                selected = sortMode == mode,
                                onClick = { sortMode = mode },
                                label = { Text(mode.label) }
                            )
                        }
                    }
                }

                // Year Filter
                item {
                    Text("按年份筛选", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("全部", "2026", "2025", "2024", "2023", "更早").forEach { yr ->
                            FilterChip(
                                selected = selectedYearFilter == yr,
                                onClick = { selectedYearFilter = yr },
                                label = { Text(yr) }
                            )
                        }
                    }
                }

                // Grouped 2-Tier Category Map
                item {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("频道分类总览", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }

                items(topCategories, key = { "sheet_${it.id}" }) { parent ->
                    val children = homeState.categories.filter { it.pid == parent.id }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = parent.name,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            TextButton(
                                onClick = {
                                    viewModel.selectCategory(parent.id)
                                    showCategorySheet = false
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("查看全部 ›", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (children.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                children.forEach { child ->
                                    FilterChip(
                                        selected = homeState.selectedCategoryId == child.id,
                                        onClick = {
                                            viewModel.selectCategory(child.id)
                                            showCategorySheet = false
                                        },
                                        label = { Text(child.name) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Source Switcher BottomSheet
    if (showSourcePicker) {
        ModalBottomSheet(
            onDismissRequest = { showSourcePicker = false },
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
                    Text(
                        text = "切换当前视频源",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    FilledTonalButton(
                        onClick = {
                            showSourcePicker = false
                            onManageVideoSources()
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Rounded.Dns, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("管理影视源", style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier.heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (apiSources.isNotEmpty()) {
                        item {
                            Text(
                                "高清直连接口 (${apiSources.size})",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        items(apiSources, key = { it.id }) { src ->
                            SourcePickerRow(
                                source = src,
                                selected = src.id == activeSource?.id,
                                onClick = {
                                    selectedYearFilter = "全部"
                                    sortMode = FeedSortMode.DEFAULT
                                    viewModel.selectSource(src.id)
                                    showSourcePicker = false
                                }
                            )
                        }
                    }
                    if (webSources.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "网页嗅探视频站（内置浏览器自动抓流播放/投屏）",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        items(webSources, key = { it.id }) { src ->
                            SourcePickerRow(
                                source = src,
                                selected = false,
                                onClick = {
                                    showSourcePicker = false
                                    onOpenWebSniffer(src.apiUrl, src.name)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSectionTabPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun PrimaryCategoryPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun SubCategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun SourcePickerRow(
    source: VideoSource,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (source.type == SourceType.WEB_SNIFFER) Icons.Rounded.Language else Icons.Rounded.PlayCircle,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(source.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${source.type.badge} · ${source.group}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun EmptyStateBox(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String = "",
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(16.dp))
                FilledTonalButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}
