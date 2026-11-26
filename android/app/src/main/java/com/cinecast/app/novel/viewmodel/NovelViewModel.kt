package com.cinecast.app.novel.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cinecast.app.CineCastApp
import com.cinecast.app.novel.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class NovelExploreUiState(
    val categories: List<NovelExploreCategory> = emptyList(),
    val selectedCategoryIndex: Int = 0,
    val books: List<NovelBook> = emptyList(),
    val page: Int = 1,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null
)

data class NovelSearchUiState(
    val query: String = "",
    val searchAllSources: Boolean = true,
    val isSearching: Boolean = false,
    val results: List<NovelBook> = emptyList(),
    val error: String? = null
)

data class NovelDetailUiState(
    val book: NovelBook? = null,
    val chapters: List<NovelChapter> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

data class NovelReaderUiState(
    val book: NovelBook? = null,
    val source: NovelSource? = null,
    val chapters: List<NovelChapter> = emptyList(),
    val currentChapterIndex: Int = 0,
    val content: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val switchSourceCandidates: List<NovelBook> = emptyList(),
    val isSearchingSwitchSources: Boolean = false
)

/**
 * 独立小说阅读模块 ViewModel —— 与视频播放 MainViewModel 完全分离
 */
class NovelViewModel : ViewModel() {

    private val app = CineCastApp.instance
    val novelRepository = app.novelRepository
    private val novelEngine = app.novelEngine
    private val legadoParser = app.legadoParser

    val sources = novelRepository.sources
    val activeSourceId = novelRepository.activeSourceId
    val subscriptions = novelRepository.subscriptions
    val bookshelf = novelRepository.bookshelf
    val readingSettings = novelRepository.readingSettings

    private val _exploreState = MutableStateFlow(NovelExploreUiState())
    val exploreState: StateFlow<NovelExploreUiState> = _exploreState.asStateFlow()

    private val _searchState = MutableStateFlow(NovelSearchUiState())
    val searchState: StateFlow<NovelSearchUiState> = _searchState.asStateFlow()

    private val _detailState = MutableStateFlow(NovelDetailUiState())
    val detailState: StateFlow<NovelDetailUiState> = _detailState.asStateFlow()

    private val _readerState = MutableStateFlow(NovelReaderUiState())
    val readerState: StateFlow<NovelReaderUiState> = _readerState.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    // Chapter content cache keyed by chapter URL for instant page turns & next-chapter preload
    private val chapterCache = ConcurrentHashMap<String, String>()
    private var searchJob: Job? = null
    private var switchSourceJob: Job? = null

    init {
        loadExplore(reset = true, autoFallback = true)
    }

    fun getEnabledSources(): List<NovelSource> = sources.value.filter { it.enabled }

    fun getActiveSource(): NovelSource? =
        novelRepository.getSourceById(activeSourceId.value) ?: getEnabledSources().firstOrNull()

    fun selectSource(sourceId: String) {
        novelRepository.setActiveSource(sourceId)
        _exploreState.value = NovelExploreUiState()
        loadExplore(reset = true, autoFallback = false)
    }

    fun selectExploreCategory(index: Int) {
        _exploreState.value = _exploreState.value.copy(selectedCategoryIndex = index)
        loadExplore(reset = true, autoFallback = false)
    }

    fun loadExplore(reset: Boolean = false, autoFallback: Boolean = false) {
        val source = getActiveSource()
        if (source == null) {
            _exploreState.value = NovelExploreUiState(error = "请先在「书源」页面启用或导入小说书源")
            return
        }
        val categories = novelEngine.getExploreCategories(source)
        val cur = _exploreState.value
        val catIdx = cur.selectedCategoryIndex.coerceIn(0, (categories.size - 1).coerceAtLeast(0))
        val targetCat = categories.getOrNull(catIdx)
        val nextPage = if (reset) 1 else cur.page + 1

        if (targetCat == null) {
            _exploreState.value = NovelExploreUiState(
                categories = emptyList(),
                error = "当前书源未配置发现分类，请直接通过顶部搜索框搜索书名"
            )
            return
        }
        if (!reset && cur.isLoadingMore) return

        viewModelScope.launch {
            _exploreState.value = if (reset) {
                cur.copy(
                    categories = categories,
                    selectedCategoryIndex = catIdx,
                    isLoading = true,
                    error = null
                )
            } else {
                cur.copy(isLoadingMore = true, error = null)
            }

            try {
                val list = novelEngine.fetchExplore(source, targetCat.url, nextPage)
                val merged = if (reset) list else (cur.books + list).distinctBy { it.bookUrl }
                _exploreState.value = _exploreState.value.copy(
                    categories = categories,
                    selectedCategoryIndex = catIdx,
                    books = merged,
                    page = nextPage,
                    isLoading = false,
                    isLoadingMore = false,
                    error = if (merged.isEmpty()) "该分类暂无小说数据，可切换书源或直接搜索" else null
                )
            } catch (e: Exception) {
                if (autoFallback && reset) {
                    val candidates = getEnabledSources().filter {
                        it.id != source.id && novelEngine.getExploreCategories(it).isNotEmpty()
                    }
                    for (alt in candidates) {
                        val altCats = novelEngine.getExploreCategories(alt)
                        val altFirst = altCats.firstOrNull() ?: continue
                        val altBooks = runCatching { novelEngine.fetchExplore(alt, altFirst.url, 1) }.getOrNull()
                        if (!altBooks.isNullOrEmpty()) {
                            novelRepository.setActiveSource(alt.id)
                            _exploreState.value = NovelExploreUiState(
                                categories = altCats,
                                selectedCategoryIndex = 0,
                                books = altBooks,
                                page = 1,
                                isLoading = false
                            )
                            _toastMessage.tryEmit("已自动切换至可用书源：${alt.name}")
                            return@launch
                        }
                    }
                }
                _exploreState.value = _exploreState.value.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    error = "加载书城失败：${e.message ?: "请检查网络或切换书源"}"
                )
            }
        }
    }

    // ==================== Multi-Source Concurrent Novel Search ====================

    fun updateSearchQuery(query: String) {
        _searchState.value = _searchState.value.copy(query = query)
    }

    fun toggleSearchAllSources(all: Boolean) {
        _searchState.value = _searchState.value.copy(searchAllSources = all)
        if (_searchState.value.query.isNotBlank()) {
            executeSearch(_searchState.value.query)
        }
    }

    fun executeSearch(keyword: String = _searchState.value.query) {
        val q = keyword.trim()
        if (q.isBlank()) return

        val active = getActiveSource()
        val targets = if (_searchState.value.searchAllSources) {
            val all = getEnabledSources().filter { it.searchable }
            (listOfNotNull(active?.takeIf { it.searchable }) + all).distinctBy { it.id }.take(10)
        } else {
            listOfNotNull(active)
        }

        if (targets.isEmpty()) {
            _searchState.value = _searchState.value.copy(error = "没有可搜索的小说书源，请先在「书源」页启用")
            return
        }

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _searchState.value = _searchState.value.copy(
                query = q,
                isSearching = true,
                results = emptyList(),
                error = null
            )
            val lock = Any()
            val accumulated = mutableListOf<NovelBook>()

            val jobs = targets.map { src ->
                launch {
                    val found = withTimeoutOrNull(12000L) {
                        runCatching { novelEngine.search(src, q, 1) }.getOrDefault(emptyList())
                    }.orEmpty()
                    if (found.isNotEmpty()) {
                        val snapshot = synchronized(lock) {
                            accumulated.addAll(found)
                            accumulated
                                .distinctBy { "${it.sourceId}::${it.bookUrl}" }
                                .sortedByDescending { b ->
                                    when {
                                        b.title.equals(q, ignoreCase = true) -> 4
                                        b.title.startsWith(q, ignoreCase = true) -> 3
                                        b.title.contains(q, ignoreCase = true) -> 2
                                        b.author.contains(q, ignoreCase = true) -> 1
                                        else -> 0
                                    }
                                }
                        }
                        _searchState.value = _searchState.value.copy(results = snapshot, error = null)
                    }
                }
            }
            jobs.joinAll()

            val finalResults = _searchState.value.results
            _searchState.value = _searchState.value.copy(
                isSearching = false,
                error = if (finalResults.isEmpty()) "未找到「$q」相关小说，可在「书源」页导入更多 GitHub 书源" else null
            )
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _searchState.value = NovelSearchUiState(searchAllSources = _searchState.value.searchAllSources)
    }

    // ==================== Book Detail & Bookshelf ====================

    fun openBookDetail(book: NovelBook) {
        val source = novelRepository.getSourceById(book.sourceId) ?: getActiveSource()
        if (source == null) {
            _detailState.value = NovelDetailUiState(book = book, error = "找不到该书对应的书源")
            return
        }
        viewModelScope.launch {
            _detailState.value = NovelDetailUiState(book = book, isLoading = true)
            try {
                val (updatedBook, chapters) = novelEngine.fetchBookDetailAndToc(source, book)
                _detailState.value = NovelDetailUiState(
                    book = updatedBook,
                    chapters = chapters,
                    isLoading = false,
                    error = if (chapters.isEmpty()) "暂未解析到目录章节，可尝试换源" else null
                )
            } catch (e: Exception) {
                _detailState.value = NovelDetailUiState(
                    book = book,
                    isLoading = false,
                    error = "加载目录失败：${e.message}"
                )
            }
        }
    }

    fun clearBookDetail() {
        _detailState.value = NovelDetailUiState()
    }

    fun toggleBookshelf(book: NovelBook, totalChapters: Int = 0) {
        viewModelScope.launch {
            val key = "${book.sourceId}::${book.bookUrl}"
            if (novelRepository.isInBookshelf(book.sourceId, book.bookUrl)) {
                novelRepository.removeFromBookshelf(key)
                _toastMessage.tryEmit("已移出书架")
            } else {
                novelRepository.addToBookshelf(book, totalChapters = totalChapters)
                _toastMessage.tryEmit("已加入书架")
            }
        }
    }

    fun removeFromBookshelf(bookKey: String) {
        viewModelScope.launch {
            novelRepository.removeFromBookshelf(bookKey)
            _toastMessage.tryEmit("已从书架移除")
        }
    }

    // ==================== Reader & Chapter Preloading ====================

    fun openReaderFromBook(book: NovelBook, preloadedChapters: List<NovelChapter> = emptyList(), startChapterIndex: Int? = null) {
        val source = novelRepository.getSourceById(book.sourceId) ?: getActiveSource()
        if (source == null) {
            _readerState.value = NovelReaderUiState(book = book, error = "找不到对应书源")
            return
        }
        val saved = novelRepository.getBookshelfItem(book.sourceId, book.bookUrl)
        viewModelScope.launch {
            if (preloadedChapters.isNotEmpty()) {
                val targetIdx = (startChapterIndex ?: saved?.currentChapterIndex ?: 0)
                    .coerceIn(0, preloadedChapters.lastIndex)
                _readerState.value = NovelReaderUiState(
                    book = book,
                    source = source,
                    chapters = preloadedChapters,
                    currentChapterIndex = targetIdx,
                    isLoading = true
                )
                // Auto add/update bookshelf when reading
                novelRepository.addToBookshelf(
                    book = book,
                    chapterIndex = targetIdx,
                    chapterTitle = preloadedChapters[targetIdx].title,
                    totalChapters = preloadedChapters.size
                )
                loadChapter(targetIdx)
            } else {
                _readerState.value = NovelReaderUiState(book = book, source = source, isLoading = true)
                try {
                    val (updatedBook, chapters) = novelEngine.fetchBookDetailAndToc(source, book)
                    if (chapters.isEmpty()) {
                        _readerState.value = NovelReaderUiState(
                            book = updatedBook,
                            source = source,
                            isLoading = false,
                            error = "目录解析为空，请点击右上角「换源」尝试其他书源"
                        )
                        return@launch
                    }
                    val targetIdx = (startChapterIndex ?: saved?.currentChapterIndex ?: 0)
                        .coerceIn(0, chapters.lastIndex)
                    _readerState.value = NovelReaderUiState(
                        book = updatedBook,
                        source = source,
                        chapters = chapters,
                        currentChapterIndex = targetIdx,
                        isLoading = true
                    )
                    novelRepository.addToBookshelf(
                        book = updatedBook,
                        chapterIndex = targetIdx,
                        chapterTitle = chapters[targetIdx].title,
                        totalChapters = chapters.size
                    )
                    loadChapter(targetIdx)
                } catch (e: Exception) {
                    _readerState.value = NovelReaderUiState(
                        book = book,
                        source = source,
                        isLoading = false,
                        error = "加载书籍失败：${e.message}"
                    )
                }
            }
        }
    }

    fun openReaderFromShelf(item: BookshelfItem) {
        val book = NovelBook(
            id = item.bookKey,
            sourceId = item.sourceId,
            sourceName = item.sourceName,
            title = item.title,
            author = item.author,
            coverUrl = item.coverUrl,
            intro = item.intro,
            kind = item.kind,
            latestChapter = item.latestChapter,
            bookUrl = item.bookUrl,
            tocUrl = item.tocUrl
        )
        openReaderFromBook(book, emptyList(), item.currentChapterIndex)
    }

    fun loadChapter(index: Int) {
        val state = _readerState.value
        val source = state.source ?: return
        val book = state.book ?: return
        val chapter = state.chapters.getOrNull(index) ?: return

        viewModelScope.launch {
            val cached = chapterCache[chapter.url]
            if (cached != null) {
                _readerState.value = _readerState.value.copy(
                    currentChapterIndex = index,
                    content = cached,
                    isLoading = false,
                    error = null
                )
                novelRepository.saveReadingProgress(book, index, chapter.title, state.chapters.size)
                preloadNextChapter(source, state.chapters, index + 1)
                return@launch
            }

            _readerState.value = _readerState.value.copy(
                currentChapterIndex = index,
                isLoading = true,
                error = null
            )
            try {
                val text = novelEngine.fetchChapterContent(source, chapter)
                chapterCache[chapter.url] = text
                _readerState.value = _readerState.value.copy(
                    currentChapterIndex = index,
                    content = text,
                    isLoading = false,
                    error = null
                )
                novelRepository.saveReadingProgress(book, index, chapter.title, state.chapters.size)
                preloadNextChapter(source, state.chapters, index + 1)
            } catch (e: Exception) {
                _readerState.value = _readerState.value.copy(
                    currentChapterIndex = index,
                    isLoading = false,
                    error = "正文加载失败：${e.message ?: "请检查网络或尝试换源"}"
                )
            }
        }
    }

    private fun preloadNextChapter(source: NovelSource, chapters: List<NovelChapter>, nextIdx: Int) {
        val next = chapters.getOrNull(nextIdx) ?: return
        if (chapterCache.containsKey(next.url)) return
        viewModelScope.launch {
            runCatching {
                val text = novelEngine.fetchChapterContent(source, next)
                chapterCache[next.url] = text
            }
        }
    }

    fun nextChapter() {
        val cur = _readerState.value
        if (cur.currentChapterIndex + 1 < cur.chapters.size) {
            loadChapter(cur.currentChapterIndex + 1)
        } else {
            _toastMessage.tryEmit("已是最新章节")
        }
    }

    fun prevChapter() {
        val cur = _readerState.value
        if (cur.currentChapterIndex > 0) {
            loadChapter(cur.currentChapterIndex - 1)
        } else {
            _toastMessage.tryEmit("已是第一章")
        }
    }

    fun searchAlternativeSourcesForCurrentBook() {
        val book = _readerState.value.book ?: return
        val targets = getEnabledSources().filter { it.searchable && it.id != book.sourceId }.take(8)
        switchSourceJob?.cancel()
        switchSourceJob = viewModelScope.launch {
            _readerState.value = _readerState.value.copy(
                isSearchingSwitchSources = true,
                switchSourceCandidates = emptyList()
            )
            val lock = Any()
            val foundAll = mutableListOf<NovelBook>()
            val jobs = targets.map { src ->
                launch {
                    val res = withTimeoutOrNull(10000L) {
                        runCatching { novelEngine.search(src, book.title, 1) }.getOrDefault(emptyList())
                    }.orEmpty()
                    val best = res.firstOrNull {
                        it.title.contains(book.title, ignoreCase = true) ||
                            book.title.contains(it.title, ignoreCase = true)
                    } ?: res.firstOrNull()
                    if (best != null) {
                        val snap = synchronized(lock) {
                            foundAll.add(best)
                            foundAll.toList()
                        }
                        _readerState.value = _readerState.value.copy(switchSourceCandidates = snap)
                    }
                }
            }
            jobs.joinAll()
            _readerState.value = _readerState.value.copy(isSearchingSwitchSources = false)
        }
    }

    fun switchToAlternativeBook(altBook: NovelBook) {
        val curIdx = _readerState.value.currentChapterIndex
        _toastMessage.tryEmit("正在切换至书源：${altBook.sourceName}")
        openReaderFromBook(altBook, emptyList(), curIdx)
    }

    // ==================== Reading Typography & Theme Settings ====================

    fun updateReaderTheme(theme: ReaderTheme) {
        viewModelScope.launch { novelRepository.updateReadingSettings { it.copy(readerTheme = theme) } }
    }

    fun updateFontSize(sizeSp: Float) {
        viewModelScope.launch {
            novelRepository.updateReadingSettings { it.copy(fontSizeSp = sizeSp.coerceIn(13f, 30f)) }
        }
    }

    fun updateLineHeight(multiplier: Float) {
        viewModelScope.launch {
            novelRepository.updateReadingSettings { it.copy(lineHeightMultiplier = multiplier.coerceIn(1.3f, 2.3f)) }
        }
    }

    fun updateHorizontalMargin(dp: Float) {
        viewModelScope.launch {
            novelRepository.updateReadingSettings { it.copy(horizontalMarginDp = dp.coerceIn(12f, 36f)) }
        }
    }

    fun updateUseSerif(serif: Boolean) {
        viewModelScope.launch { novelRepository.updateReadingSettings { it.copy(useSerifFont = serif) } }
    }

    // ==================== Book Sources & GitHub Subscription Management ====================

    fun importLegadoUrl(url: String, customName: String = "") {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            _isImporting.value = true
            try {
                val res = legadoParser.importFromUrl(trimmed, customName)
                novelRepository.mergeSubscriptionImport(res.subscription, res.sources)
                _toastMessage.tryEmit(
                    "导入成功：解析 ${res.sources.size} 个可用小说源（总 ${res.totalRawCount} 条，跳过纯JS ${res.skippedJsOnlyCount} 条）"
                )
            } catch (e: Exception) {
                _toastMessage.tryEmit("书源拉取失败：${e.message}")
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun importLegadoJsonText(rawJson: String) {
        if (rawJson.isBlank()) return
        viewModelScope.launch {
            _isImporting.value = true
            try {
                val res = legadoParser.parseJsonText(rawJson, "", "本地粘贴书源")
                novelRepository.mergeSubscriptionImport(res.subscription, res.sources)
                _toastMessage.tryEmit("JSON 导入成功：新增 ${res.sources.size} 个小说源")
            } catch (e: Exception) {
                _toastMessage.tryEmit("Legado JSON 解析失败：${e.message}")
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun toggleSourceEnabled(source: NovelSource) {
        viewModelScope.launch {
            novelRepository.upsertSource(source.copy(enabled = !source.enabled))
        }
    }

    fun deleteSource(sourceId: String) {
        viewModelScope.launch {
            novelRepository.deleteSource(sourceId)
            _toastMessage.tryEmit("已删除小说源")
        }
    }

    fun testSourceLatency(source: NovelSource) {
        viewModelScope.launch {
            val ms = novelEngine.pingSource(source)
            novelRepository.updateSourceLatency(source.id, ms)
            val msg = if (ms < 0) "${source.name} 连接超时或被站点拦截" else "${source.name} 响应正常 (${ms} ms)"
            _toastMessage.tryEmit(msg)
        }
    }

    fun saveCustomSource(
        existingId: String?,
        name: String,
        baseUrl: String,
        group: String,
        searchUrl: String,
        bookListRule: String,
        nameRule: String,
        bookUrlRule: String,
        tocListRule: String,
        contentRule: String
    ) {
        viewModelScope.launch {
            val existing = existingId?.let { novelRepository.getSourceById(it) }
            val isJson = bookListRule.trim().startsWith("$") || contentRule.trim().startsWith("$")
            val source = (existing ?: NovelSource(
                id = "custom_novel_${UUID.randomUUID().toString().take(8)}",
                name = name.trim(),
                baseUrl = baseUrl.trim().trimEnd('/')
            )).copy(
                name = name.trim(),
                baseUrl = baseUrl.trim().trimEnd('/'),
                group = group.trim().ifBlank { "自定义书源" },
                sourceType = if (isJson) NovelSourceType.LEGADO_JSON else NovelSourceType.LEGADO_HTML,
                searchUrl = searchUrl.trim(),
                searchable = searchUrl.isNotBlank(),
                ruleSearch = (existing?.ruleSearch ?: NovelRuleSearch()).copy(
                    bookList = bookListRule.trim(),
                    name = nameRule.trim(),
                    bookUrl = bookUrlRule.trim()
                ),
                ruleToc = (existing?.ruleToc ?: NovelRuleToc()).copy(
                    chapterList = tocListRule.trim(),
                    chapterName = (existing?.ruleToc?.chapterName).orEmpty().ifBlank { "text" },
                    chapterUrl = (existing?.ruleToc?.chapterUrl).orEmpty().ifBlank { "href" }
                ),
                ruleContent = (existing?.ruleContent ?: NovelRuleContent()).copy(
                    content = contentRule.trim()
                )
            )
            novelRepository.upsertSource(source)
            _toastMessage.tryEmit("已保存小说源：${source.name}")
        }
    }

    fun importNovelBackup(rawJson: String) {
        viewModelScope.launch {
            runCatching {
                novelRepository.importNovelBackupJson(rawJson)
                _toastMessage.tryEmit("小说书源与书架备份恢复成功")
                loadExplore(reset = true)
            }.onFailure {
                _toastMessage.tryEmit("小说备份格式错误：${it.message}")
            }
        }
    }
}
