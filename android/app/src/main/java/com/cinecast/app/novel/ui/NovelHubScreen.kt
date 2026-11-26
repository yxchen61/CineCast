package com.cinecast.app.novel.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.automirrored.rounded.MenuBook
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.cinecast.app.novel.model.*
import com.cinecast.app.novel.viewmodel.NovelViewModel

private enum class NovelSubTab(val label: String) {
    BOOKSHELF("书架"),
    DISCOVER("书城"),
    SOURCES("书源")
}

/**
 * Google Play Books 风格独立阅读中心
 * 内含 书架 | 书城发现与全源搜索 | 独立书源管理（支持 GitHub Legado 书源订阅导入）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelHubScreen(
    viewModel: NovelViewModel,
    onOpenReader: () -> Unit
) {
    val sources by viewModel.sources.collectAsState()
    val activeSourceId by viewModel.activeSourceId.collectAsState()
    val subscriptions by viewModel.subscriptions.collectAsState()
    val bookshelf by viewModel.bookshelf.collectAsState()
    val exploreState by viewModel.exploreState.collectAsState()
    val searchState by viewModel.searchState.collectAsState()
    val detailState by viewModel.detailState.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()

    var currentSubTab by remember {
        mutableStateOf(if (bookshelf.isNotEmpty()) NovelSubTab.BOOKSHELF else NovelSubTab.DISCOVER)
    }
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

    Column(modifier = Modifier.fillMaxSize()) {
        // Google Play Books Material 3 Header
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (isSearchMode) {
                    // Google Search Bar Pill for multi-source novel search
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
                                placeholder = { Text("搜书名、作者（多书源并发聚合）...") },
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
                            ) {
                                Text("搜书")
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = searchState.searchAllSources,
                            onClick = { viewModel.toggleSearchAllSources(true) },
                            label = { Text("全书源聚合搜索") },
                            leadingIcon = { Icon(Icons.Rounded.Hub, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        FilterChip(
                            selected = !searchState.searchAllSources,
                            onClick = { viewModel.toggleSearchAllSources(false) },
                            label = { Text("仅当前书源") }
                        )
                    }
                } else {
                    // Row 1: Brand Title + Active Book Source Pill + Search Button
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
                                    Icons.AutoMirrored.Rounded.MenuBook,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "极光阅读",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(10.dp))
                        if (currentSubTab == NovelSubTab.DISCOVER) {
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
                                        text = activeSource?.name?.substringBefore(" ") ?: "选择书源",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Icon(
                                        Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = "切换书源",
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
                            Icon(Icons.Rounded.Search, contentDescription = "搜索小说", modifier = Modifier.size(20.dp))
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Row 2: Sleek Borderless Google Play Books Tonal Pill Tabs (书架 | 书城 | 书源)
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
                            NovelSubTab.entries.forEach { tab ->
                                val selected = currentSubTab == tab
                                val badge = when (tab) {
                                    NovelSubTab.BOOKSHELF -> if (bookshelf.isNotEmpty()) " ${bookshelf.size}" else ""
                                    NovelSubTab.SOURCES -> " ${enabledSources.size}"
                                    NovelSubTab.DISCOVER -> ""
                                }
                                Surface(
                                    onClick = { currentSubTab = tab },
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = when (tab) {
                                                NovelSubTab.BOOKSHELF -> Icons.Rounded.Bookmarks
                                                NovelSubTab.DISCOVER -> Icons.Rounded.AutoStories
                                                NovelSubTab.SOURCES -> Icons.AutoMirrored.Rounded.LibraryBooks
                                            },
                                            contentDescription = null,
                                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(5.dp))
                                        Text(
                                            tab.label + badge,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Main Content Body
        if (isSearchMode) {
            NovelSearchResultsPane(
                searchState = searchState,
                onBookClick = { viewModel.openBookDetail(it) },
                onQuickRead = {
                    viewModel.openReaderFromBook(it)
                    onOpenReader()
                }
            )
        } else {
            when (currentSubTab) {
                NovelSubTab.BOOKSHELF -> NovelBookshelfPane(
                    bookshelf = bookshelf,
                    onContinueRead = {
                        viewModel.openReaderFromShelf(it)
                        onOpenReader()
                    },
                    onRemove = { viewModel.removeFromBookshelf(it.bookKey) },
                    onGoDiscover = { currentSubTab = NovelSubTab.DISCOVER },
                    onGoSearch = { isSearchMode = true }
                )

                NovelSubTab.DISCOVER -> NovelDiscoverPane(
                    exploreState = exploreState,
                    onSelectCategory = { viewModel.selectExploreCategory(it) },
                    onRefresh = { viewModel.loadExplore(reset = true) },
                    onLoadMore = { viewModel.loadExplore(reset = false) },
                    onBookClick = { viewModel.openBookDetail(it) }
                )

                NovelSubTab.SOURCES -> NovelSourcesPane(
                    sources = sources,
                    subscriptions = subscriptions,
                    isImporting = isImporting,
                    viewModel = viewModel
                )
            }
        }
    }

    // Active Book Source Picker Sheet
    if (showSourcePicker) {
        ModalBottomSheet(onDismissRequest = { showSourcePicker = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("切换小说书源", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "小说书源与视频源完全独立，可在「书源」页导入更多 GitHub 开源书源。",
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
                            shape = RoundedCornerShape(14.dp),
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
                                        AssistChipBadge(src.sourceType.badge)
                                    }
                                    Text(
                                        src.group,
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
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    // Book Detail & Chapters Bottom Sheet
    detailState.book?.let { book ->
        val inShelf = bookshelf.any { it.bookKey == "${book.sourceId}::${book.bookUrl}" }
        ModalBottomSheet(onDismissRequest = { viewModel.clearBookDetail() }) {
            NovelDetailSheetContent(
                book = book,
                chapters = detailState.chapters,
                isLoading = detailState.isLoading,
                error = detailState.error,
                inBookshelf = inShelf,
                onToggleShelf = { viewModel.toggleBookshelf(book, detailState.chapters.size) },
                onStartReading = { chIdx ->
                    viewModel.openReaderFromBook(book, detailState.chapters, chIdx)
                    viewModel.clearBookDetail()
                    onOpenReader()
                }
            )
        }
    }
}

// ==================== 1. 书架视图 (Google Play Books Style) ====================

@Composable
private fun NovelBookshelfPane(
    bookshelf: List<BookshelfItem>,
    onContinueRead: (BookshelfItem) -> Unit,
    onRemove: (BookshelfItem) -> Unit,
    onGoDiscover: () -> Unit,
    onGoSearch: () -> Unit
) {
    if (bookshelf.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                    modifier = Modifier.size(72.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Rounded.MenuBook,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("书架空空如也", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "去「书城」逛逛精选小说，或点击右上角搜索框跨多书源找书",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onGoDiscover, shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Rounded.AutoStories, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("逛逛书城")
                    }
                    OutlinedButton(onClick = onGoSearch, shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("全网搜书")
                    }
                }
            }
        }
        return
    }

    val latest = bookshelf.first()

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Continue Reading Hero Card (Google Play Books inspired)
        item(span = { GridItemSpan(3) }) {
            Card(
                onClick = { onContinueRead(latest) },
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NovelCoverArt(
                        title = latest.title,
                        coverUrl = latest.coverUrl,
                        modifier = Modifier
                            .width(74.dp)
                            .height(104.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                "继续阅读",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            latest.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            latest.currentChapterTitle.ifBlank { "第 ${latest.currentChapterIndex + 1} 章" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(8.dp))
                        val progress = if (latest.totalChapters > 0) {
                            ((latest.currentChapterIndex + 1).toFloat() / latest.totalChapters.toFloat()).coerceIn(0f, 1f)
                        } else 0.05f
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${latest.sourceName.substringBefore(" ")} · ${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item(span = { GridItemSpan(3) }) {
            Text(
                "我的书架 (${bookshelf.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        items(bookshelf, key = { it.bookKey }) { item ->
            var showDeleteConfirm by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onContinueRead(item) }
            ) {
                Box {
                    NovelCoverArt(
                        title = item.title,
                        coverUrl = item.coverUrl,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.70f)
                    )
                    Surface(
                        onClick = { showDeleteConfirm = true },
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.45f),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(24.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "移出书架",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    if (item.totalChapters > 0) {
                        val pct = (((item.currentChapterIndex + 1) * 100) / item.totalChapters).coerceIn(1, 100)
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(6.dp)
                        ) {
                            Text(
                                "$pct%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    item.author.ifBlank { item.sourceName.substringBefore(" ") },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (showDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = { Text("移出书架") },
                    text = { Text("确定要将《${item.title}》移出书架吗？") },
                    confirmButton = {
                        Button(onClick = {
                            onRemove(item)
                            showDeleteConfirm = false
                        }) { Text("移出") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
                    }
                )
            }
        }
    }
}

// ==================== 2. 书城发现视图 ====================

@Composable
private fun NovelDiscoverPane(
    exploreState: com.cinecast.app.novel.viewmodel.NovelExploreUiState,
    onSelectCategory: (Int) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onBookClick: (NovelBook) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (exploreState.categories.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(exploreState.categories) { idx, cat ->
                    val sel = exploreState.selectedCategoryIndex == idx
                    Surface(
                        onClick = { onSelectCategory(idx) },
                        shape = RoundedCornerShape(20.dp),
                        color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = if (sel) 2.dp else 0.dp
                    ) {
                        Text(
                            cat.title,
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
            exploreState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(10.dp))
                        Text("正在从书源加载精选小说...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            exploreState.error != null && exploreState.books.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            Icons.Rounded.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(exploreState.error, style = MaterialTheme.typography.bodyMedium)
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
                val heroBook = exploreState.books.firstOrNull()
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Google Play Books Editorial Spotlight Card
                    if (heroBook != null) {
                        item {
                            Card(
                                onClick = { onBookClick(heroBook) },
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
                                                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f)
                                                )
                                            )
                                        )
                                        .padding(16.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        NovelCoverArt(
                                            title = heroBook.title,
                                            coverUrl = heroBook.coverUrl,
                                            modifier = Modifier
                                                .width(78.dp)
                                                .height(110.dp)
                                        )
                                        Spacer(Modifier.width(14.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.primary,
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    "编辑精选 · 好书推荐",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                )
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                heroBook.title,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                listOfNotNull(
                                                    heroBook.author.takeIf { it.isNotBlank() },
                                                    heroBook.kind.takeIf { it.isNotBlank() }
                                                ).joinToString(" · "),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                                maxLines = 1
                                            )
                                            if (heroBook.intro.isNotBlank()) {
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    heroBook.intro.replace("\n", " "),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    items(exploreState.books.drop(if (heroBook != null) 1 else 0), key = { "${it.sourceId}::${it.bookUrl}" }) { book ->
                        NovelBookRowCard(book = book, onClick = { onBookClick(book) })
                    }
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (exploreState.isLoadingMore) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                OutlinedButton(onClick = onLoadMore, shape = RoundedCornerShape(14.dp)) {
                                    Text("加载更多小说")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== 3. 多书源聚合搜索视图 ====================

@Composable
private fun NovelSearchResultsPane(
    searchState: com.cinecast.app.novel.viewmodel.NovelSearchUiState,
    onBookClick: (NovelBook) -> Unit,
    onQuickRead: (NovelBook) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (searchState.isSearching) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        when {
            searchState.query.isBlank() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(28.dp)) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ManageSearch,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("跨书源极速聚合搜书", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "输入书名或作者名，将自动并发检索所有已启用的 GitHub 书源并实时流式展示",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            searchState.results.isEmpty() && !searchState.isSearching -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        searchState.error ?: "未搜索到相关小说",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }

            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        Text(
                            "已找到 ${searchState.results.size} 条结果" + if (searchState.isSearching) "（更多书源正在响应...）" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    items(searchState.results, key = { "${it.sourceId}::${it.bookUrl}" }) { book ->
                        NovelBookRowCard(
                            book = book,
                            showSourceBadge = true,
                            onClick = { onBookClick(book) },
                            onQuickRead = { onQuickRead(book) }
                        )
                    }
                }
            }
        }
    }
}

// ==================== 4. 独立小说书源管理视图（GitHub 书源订阅导入） ====================

@Composable
private fun NovelSourcesPane(
    sources: List<NovelSource>,
    subscriptions: List<NovelSubscription>,
    isImporting: Boolean,
    viewModel: NovelViewModel
) {
    val clipboard = LocalClipboardManager.current
    var subUrlInput by remember { mutableStateOf("https://fastly.jsdelivr.net/gh/XIU2/Yuedu@master/shuyuan") }
    var filterTab by remember { mutableIntStateOf(0) } // 0=全部 1=API/名著 2=规则书源 3=已启用
    var showPasteJsonDialog by remember { mutableStateOf(false) }
    var showAddSourceDialog by remember { mutableStateOf(false) }
    var editingSource by remember { mutableStateOf<NovelSource?>(null) }
    var showRestoreBackupDialog by remember { mutableStateOf(false) }

    val filtered = remember(sources, filterTab) {
        when (filterTab) {
            1 -> sources.filter { it.sourceType != NovelSourceType.LEGADO_HTML }
            2 -> sources.filter { it.sourceType == NovelSourceType.LEGADO_HTML }
            3 -> sources.filter { it.enabled }
            else -> sources
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // GitHub Legado Book Source Import Card
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "导入 GitHub 开源书源 (Legado 3.0 JSON)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "与视频源完全独立管理。支持一键导入 XIU2/Yuedu、liufuyou/read 等开源书源仓库或任意 Legado JSON 链接。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = subUrlInput,
                        onValueChange = { subUrlInput = it },
                        label = { Text("书源 JSON 订阅地址 (GitHub Raw / jsDelivr)") },
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
                            onClick = { viewModel.importLegadoUrl(subUrlInput) },
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
                                Text("拉取中...")
                            } else {
                                Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("从 Git 拉取导入")
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

        // Action row: Add Custom Source + Independent Novel Backup/Restore
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(
                    onClick = {
                        editingSource = null
                        showAddSourceDialog = true
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("自定义书源")
                }
                OutlinedButton(
                    onClick = {
                        val exported = viewModel.novelRepository.exportNovelBackupJson()
                        clipboard.setText(AnnotatedString(exported))
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("导出书源")
                }
                OutlinedButton(
                    onClick = { showRestoreBackupDialog = true },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("恢复")
                }
            }
        }

        // Filter Chips
        item {
            val labels = listOf(
                "全部 (${sources.size})",
                "API/名著 (${sources.count { it.sourceType != NovelSourceType.LEGADO_HTML }})",
                "规则源 (${sources.count { it.sourceType == NovelSourceType.LEGADO_HTML }})",
                "已启用 (${sources.count { it.enabled }})"
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEachIndexed { idx, label ->
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

        // Novel Source Cards
        items(filtered, key = { it.id }) { src ->
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    src.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.width(6.dp))
                                AssistChipBadge(src.sourceType.badge)
                                src.latencyMs?.let { ms ->
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        color = if (ms > 0) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = if (ms < 0) "超时" else "${ms}ms",
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "${src.group} · ${src.baseUrl}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Switch(checked = src.enabled, onCheckedChange = { viewModel.toggleSourceEnabled(src) })
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { viewModel.testSourceLatency(src) }) {
                            Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("测速")
                        }
                        TextButton(onClick = {
                            editingSource = src
                            showAddSourceDialog = true
                        }) { Text("编辑") }
                        TextButton(onClick = { viewModel.deleteSource(src.id) }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    if (showPasteJsonDialog) {
        var rawJson by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPasteJsonDialog = false },
            title = { Text("粘贴 Legado 书源 JSON") },
            text = {
                OutlinedTextField(
                    value = rawJson,
                    onValueChange = { rawJson = it },
                    placeholder = { Text("粘贴 GitHub 书源 JSON 数组或单条书源对象...") },
                    minLines = 6,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (rawJson.isNotBlank()) {
                        viewModel.importLegadoJsonText(rawJson)
                        showPasteJsonDialog = false
                    }
                }) { Text("解析导入") }
            },
            dismissButton = {
                TextButton(onClick = { showPasteJsonDialog = false }) { Text("取消") }
            }
        )
    }

    if (showAddSourceDialog) {
        AddOrEditNovelSourceDialog(
            initial = editingSource,
            onDismiss = { showAddSourceDialog = false },
            onSave = { name, baseUrl, group, searchUrl, listRule, nameRule, urlRule, tocRule, contentRule ->
                viewModel.saveCustomSource(
                    existingId = editingSource?.id,
                    name = name,
                    baseUrl = baseUrl,
                    group = group,
                    searchUrl = searchUrl,
                    bookListRule = listRule,
                    nameRule = nameRule,
                    bookUrlRule = urlRule,
                    tocListRule = tocRule,
                    contentRule = contentRule
                )
                showAddSourceDialog = false
            }
        )
    }

    if (showRestoreBackupDialog) {
        var backupText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showRestoreBackupDialog = false },
            title = { Text("恢复小说书源与书架备份") },
            text = {
                OutlinedTextField(
                    value = backupText,
                    onValueChange = { backupText = it },
                    placeholder = { Text("粘贴导出的小说备份 JSON...") },
                    minLines = 5,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (backupText.isNotBlank()) {
                        viewModel.importNovelBackup(backupText)
                        showRestoreBackupDialog = false
                    }
                }) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreBackupDialog = false }) { Text("取消") }
            }
        )
    }
}

// ==================== Reusable Google Material 3 Components ====================

@Composable
private fun NovelBookRowCard(
    book: NovelBook,
    showSourceBadge: Boolean = false,
    onClick: () -> Unit,
    onQuickRead: (() -> Unit)? = null
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            NovelCoverArt(
                title = book.title,
                coverUrl = book.coverUrl,
                modifier = Modifier
                    .width(68.dp)
                    .height(94.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        book.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (showSourceBadge) {
                        Spacer(Modifier.width(6.dp))
                        AssistChipBadge(book.sourceName.substringBefore(" "))
                    }
                }
                Spacer(Modifier.height(3.dp))
                val meta = listOfNotNull(
                    book.author.takeIf { it.isNotBlank() },
                    book.kind.takeIf { it.isNotBlank() },
                    book.wordCount.takeIf { it.isNotBlank() }
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (book.intro.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        book.intro.replace("\n", " "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (book.latestChapter.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "最新：${book.latestChapter}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (onQuickRead != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        FilledTonalButton(
                            onClick = onQuickRead,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("立即阅读", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NovelCoverArt(
    title: String,
    coverUrl: String,
    modifier: Modifier = Modifier
) {
    val palette = remember(title) {
        val hash = kotlin.math.abs(title.hashCode())
        when (hash % 5) {
            0 -> listOf(Color(0xFF1A73E8), Color(0xFF174EA6))
            1 -> listOf(Color(0xFF188038), Color(0xFF0D652D))
            2 -> listOf(Color(0xFFE37400), Color(0xFFB06000))
            3 -> listOf(Color(0xFF7B1FA2), Color(0xFF4A148C))
            else -> listOf(Color(0xFF00796B), Color(0xFF004D40))
        }
    }
    Box(
        modifier = modifier
            .shadow(6.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(palette)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title.take(6),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.94f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
        )
        if (coverUrl.startsWith("http")) {
            AsyncImage(
                model = coverUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        // Subtle Google Play Books hardcover spine highlight on the left edge
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(7.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.24f),
                            Color.White.copy(alpha = 0.16f),
                            Color.Transparent
                        )
                    )
                )
        )
    }
}

@Composable
private fun AssistChipBadge(text: String) {
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
private fun NovelDetailSheetContent(
    book: NovelBook,
    chapters: List<NovelChapter>,
    isLoading: Boolean,
    error: String?,
    inBookshelf: Boolean,
    onToggleShelf: () -> Unit,
    onStartReading: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            NovelCoverArt(
                title = book.title,
                coverUrl = book.coverUrl,
                modifier = Modifier
                    .width(86.dp)
                    .height(120.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Text(
                    listOfNotNull(
                        book.author.takeIf { it.isNotBlank() },
                        book.kind.takeIf { it.isNotBlank() }
                    ).joinToString(" · ").ifBlank { book.sourceName },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "书源：${book.sourceName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (book.latestChapter.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "最新：${book.latestChapter}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (book.intro.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                book.intro,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onToggleShelf,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    if (inBookshelf) Icons.Rounded.BookmarkAdded else Icons.Rounded.BookmarkAdd,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(if (inBookshelf) "已在书架" else "加入书架")
            }
            Button(
                onClick = { onStartReading(0) },
                enabled = !isLoading,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.AutoMirrored.Rounded.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("开始阅读")
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "目录预览 (${chapters.size} 章)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))

        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            error != null && chapters.isEmpty() -> {
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                ) {
                    itemsIndexed(chapters.take(40)) { idx, ch ->
                        ListItem(
                            headlineContent = {
                                Text(ch.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            },
                            modifier = Modifier.clickable { onStartReading(idx) }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AddOrEditNovelSourceDialog(
    initial: NovelSource?,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        baseUrl: String,
        group: String,
        searchUrl: String,
        listRule: String,
        nameRule: String,
        urlRule: String,
        tocRule: String,
        contentRule: String
    ) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl.orEmpty()) }
    var group by remember { mutableStateOf(initial?.group ?: "自定义书源") }
    var searchUrl by remember { mutableStateOf(initial?.searchUrl.orEmpty()) }
    var listRule by remember { mutableStateOf(initial?.ruleSearch?.bookList.orEmpty()) }
    var nameRule by remember { mutableStateOf(initial?.ruleSearch?.name.orEmpty()) }
    var urlRule by remember { mutableStateOf(initial?.ruleSearch?.bookUrl.orEmpty()) }
    var tocRule by remember { mutableStateOf(initial?.ruleToc?.chapterList.orEmpty()) }
    var contentRule by remember { mutableStateOf(initial?.ruleContent?.content.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加自定义小说源" else "编辑小说源") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 420.dp)
            ) {
                item {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("书源名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("站点根地址 (bookSourceUrl)") }, placeholder = { Text("https://example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(value = group, onValueChange = { group = it }, label = { Text("分组标签") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(value = searchUrl, onValueChange = { searchUrl = it }, label = { Text("搜索 URL 模板") }, placeholder = { Text("/search?q={{key}}&page={{page}}") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(value = listRule, onValueChange = { listRule = it }, label = { Text("搜索列表规则 (bookList)") }, placeholder = { Text("class.item 或 $.data") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(value = nameRule, onValueChange = { nameRule = it }, label = { Text("书名规则 (name)") }, placeholder = { Text("tag.h3.0@text 或 $.title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(value = urlRule, onValueChange = { urlRule = it }, label = { Text("详情链接规则 (bookUrl)") }, placeholder = { Text("tag.a.0@href") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(value = tocRule, onValueChange = { tocRule = it }, label = { Text("目录列表规则 (chapterList)") }, placeholder = { Text("id.list.0@tag.a 或 $.data") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(value = contentRule, onValueChange = { contentRule = it }, label = { Text("正文规则 (content)") }, placeholder = { Text("id.content.0@html 或 $.data.content") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (name.isNotBlank() && baseUrl.isNotBlank()) {
                    onSave(name, baseUrl, group, searchUrl, listRule, nameRule, urlRule, tocRule, contentRule)
                }
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
