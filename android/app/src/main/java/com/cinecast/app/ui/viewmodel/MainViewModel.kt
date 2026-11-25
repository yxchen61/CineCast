package com.cinecast.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cinecast.app.CineCastApp
import com.cinecast.app.data.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

data class HomeUiState(
    val categories: List<VodCategory> = emptyList(),
    val selectedCategoryId: String? = null,
    val items: List<VodItem> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val page: Int = 1,
    val pageCount: Int = 1,
    val error: String? = null
)

data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val searchAllSources: Boolean = true,
    val results: List<VodItem> = emptyList(),
    val error: String? = null
)

data class LiveUiState(
    val selectedSourceId: String = "",
    val channels: List<LiveChannel> = emptyList(),
    val groups: List<String> = emptyList(),
    val selectedGroup: String = "全部",
    val isLoading: Boolean = false,
    val error: String? = null
)

data class PlayerUiState(
    val detail: VodDetail? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val playSourceIndex: Int = 0,
    val episodeIndex: Int = 0,
    val directTitle: String = "",
    val directUrl: String = "",
    val directHeaders: Map<String, String> = emptyMap(),
    val isLiveStream: Boolean = false,
    val liveBackupUrls: List<String> = emptyList(),
    val liveBackupIndex: Int = 0,
    val resumePositionMs: Long = 0L
)

class MainViewModel : ViewModel() {

    private val app = CineCastApp.instance
    val repository = app.repository
    val dlnaController = app.dlnaController
    private val vodEngine = app.vodEngine
    private val tvBoxParser = app.tvBoxParser
    private val liveParser = app.liveParser

    val sources = repository.sources
    val activeSourceId = repository.activeSourceId
    val subscriptions = repository.subscriptions
    val liveSources = repository.liveSources
    val parses = repository.parses
    val history = repository.history
    val favorites = repository.favorites
    val settings = repository.settings

    private val _homeState = MutableStateFlow(HomeUiState())
    val homeState: StateFlow<HomeUiState> = _homeState.asStateFlow()

    private val _searchState = MutableStateFlow(SearchUiState())
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    private val _liveState = MutableStateFlow(LiveUiState())
    val liveState: StateFlow<LiveUiState> = _liveState.asStateFlow()

    private val _playerState = MutableStateFlow(PlayerUiState())
    val playerState: StateFlow<PlayerUiState> = _playerState.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    init {
        loadHomeFeed(reset = true, autoFallback = true)
    }

    fun getActiveApiSources(): List<VideoSource> =
        sources.value.filter {
            it.enabled && it.type in setOf(SourceType.MACCMS_JSON, SourceType.MACCMS_XML, SourceType.T4_API)
        }

    fun selectSource(sourceId: String) {
        repository.setActiveSource(sourceId)
        _homeState.value = HomeUiState()
        loadHomeFeed(reset = true)
    }

    fun selectCategory(categoryId: String?) {
        _homeState.value = _homeState.value.copy(selectedCategoryId = categoryId)
        loadHomeFeed(reset = true)
    }

    fun loadHomeFeed(reset: Boolean = false, autoFallback: Boolean = false) {
        val source = repository.getSourceById(activeSourceId.value)
            ?: getActiveApiSources().firstOrNull()
        if (source == null) {
            _homeState.value = _homeState.value.copy(
                isLoading = false,
                error = "请先在「影视源」页面启用或添加一个视频源"
            )
            return
        }
        val cur = _homeState.value
        val nextPage = if (reset) 1 else cur.page + 1
        if (!reset && (cur.isLoadingMore || cur.page >= cur.pageCount)) return

        val selectedCat = cur.categories.firstOrNull { it.id == cur.selectedCategoryId }
        val childIds = selectedCat?.childIds.orEmpty()

        viewModelScope.launch {
            _homeState.value = if (reset) {
                cur.copy(isLoading = true, error = null)
            } else {
                cur.copy(isLoadingMore = true, error = null)
            }
            try {
                val res = vodEngine.fetchVodList(source, cur.selectedCategoryId, nextPage, childIds)
                val mergedItems = if (reset) res.items else (cur.items + res.items).distinctBy { it.id }
                val cats = if (res.categories.isNotEmpty()) res.categories else cur.categories
                _homeState.value = _homeState.value.copy(
                    categories = cats,
                    items = mergedItems,
                    page = res.page,
                    pageCount = res.pageCount.coerceAtLeast(1),
                    isLoading = false,
                    isLoadingMore = false,
                    error = if (mergedItems.isEmpty()) "该分类暂无视频内容" else null
                )
            } catch (e: Exception) {
                if (autoFallback && reset) {
                    val candidates = getActiveApiSources().filter { it.id != source.id }
                    for (alt in candidates) {
                        val altRes = runCatching { vodEngine.fetchVodList(alt, null, 1) }.getOrNull()
                        if (altRes != null && altRes.items.isNotEmpty()) {
                            repository.setActiveSource(alt.id)
                            _homeState.value = HomeUiState(
                                categories = altRes.categories,
                                items = altRes.items,
                                page = altRes.page,
                                pageCount = altRes.pageCount.coerceAtLeast(1),
                                isLoading = false
                            )
                            _toastMessage.tryEmit("已自动切换至高速可用源：${alt.name}")
                            return@launch
                        }
                    }
                }
                _homeState.value = _homeState.value.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    error = "加载失败：${e.message ?: "请检查网络或切换视频源"}"
                )
            }
        }
    }

    private var searchJob: Job? = null

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
        val activeSrc = repository.getSourceById(activeSourceId.value)
        val targets = if (_searchState.value.searchAllSources) {
            val allApi = getActiveApiSources().filter { it.searchable }
            // Put active source first so fastest verified source responds immediately
            (listOfNotNull(activeSrc) + allApi).distinctBy { it.id }.take(9)
        } else {
            listOfNotNull(activeSrc ?: getActiveApiSources().firstOrNull())
        }
        if (targets.isEmpty()) {
            _searchState.value = _searchState.value.copy(error = "没有可搜索的视频源")
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _searchState.value = _searchState.value.copy(
                query = q,
                isSearching = true,
                error = null,
                results = emptyList()
            )
            val lock = Any()
            val accumulated = mutableListOf<VodItem>()

            val jobs = targets.map { src ->
                launch {
                    val items = withTimeoutOrNull(12000L) {
                        runCatching { vodEngine.search(src, q, 1) }.getOrDefault(emptyList())
                    }.orEmpty()
                    if (items.isNotEmpty()) {
                        val snapshot = synchronized(lock) {
                            accumulated.addAll(items)
                            accumulated
                                .distinctBy { "${it.sourceId}_${it.id}" }
                                .sortedByDescending { item ->
                                    when {
                                        item.name.equals(q, ignoreCase = true) -> 3
                                        item.name.startsWith(q, ignoreCase = true) -> 2
                                        item.name.contains(q, ignoreCase = true) -> 1
                                        else -> 0
                                    }
                                }
                        }
                        _searchState.value = _searchState.value.copy(
                            results = snapshot,
                            error = null
                        )
                    }
                }
            }

            jobs.joinAll()
            val finalResults = _searchState.value.results
            _searchState.value = _searchState.value.copy(
                isSearching = false,
                error = if (finalResults.isEmpty()) "未找到「$q」相关影片，可尝试缩短关键词或在「影视源」中添加更多源" else null
            )
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _searchState.value = SearchUiState(searchAllSources = _searchState.value.searchAllSources)
    }

    fun openVodDetail(sourceId: String, vodId: String) {
        val source = repository.getSourceById(sourceId)
            ?: getActiveApiSources().firstOrNull()
        if (source == null) {
            _playerState.value = PlayerUiState(error = "找不到对应视频源")
            return
        }
        val historyItem = repository.getHistoryItem(source.id, vodId)
        viewModelScope.launch {
            _playerState.value = PlayerUiState(isLoading = true)
            try {
                val detail = vodEngine.fetchDetail(source, vodId)
                val psIdx = (historyItem?.playSourceIndex ?: 0).coerceIn(0, (detail.playSources.size - 1).coerceAtLeast(0))
                val epCount = detail.playSources.getOrNull(psIdx)?.episodes?.size ?: 0
                val epIdx = (historyItem?.episodeIndex ?: 0).coerceIn(0, (epCount - 1).coerceAtLeast(0))
                _playerState.value = PlayerUiState(
                    detail = detail,
                    isLoading = false,
                    playSourceIndex = psIdx,
                    episodeIndex = epIdx,
                    directHeaders = source.headers,
                    resumePositionMs = historyItem?.positionMs ?: 0L
                )
            } catch (e: Exception) {
                _playerState.value = PlayerUiState(
                    isLoading = false,
                    error = "获取影片详情失败：${e.message}"
                )
            }
        }
    }

    fun openDirectStream(
        title: String,
        url: String,
        isLive: Boolean = false,
        headers: Map<String, String> = emptyMap(),
        backupUrls: List<String> = emptyList()
    ) {
        val urls = if (backupUrls.isNotEmpty()) backupUrls else listOf(url)
        _playerState.value = PlayerUiState(
            directTitle = title,
            directUrl = urls.first(),
            directHeaders = headers,
            isLiveStream = isLive,
            liveBackupUrls = urls,
            liveBackupIndex = 0
        )
    }

    fun selectLiveBackupLine(index: Int) {
        val cur = _playerState.value
        val target = cur.liveBackupUrls.getOrNull(index) ?: return
        _playerState.value = cur.copy(
            directUrl = target,
            liveBackupIndex = index
        )
    }

    fun selectPlaySourceAndEpisode(playSourceIndex: Int, episodeIndex: Int) {
        _playerState.value = _playerState.value.copy(
            playSourceIndex = playSourceIndex,
            episodeIndex = episodeIndex,
            resumePositionMs = 0L
        )
    }

    fun savePlaybackProgress(positionMs: Long, durationMs: Long) {
        val state = _playerState.value
        val detail = state.detail ?: return
        val epName = detail.playSources.getOrNull(state.playSourceIndex)
            ?.episodes?.getOrNull(state.episodeIndex)?.name.orEmpty()
        viewModelScope.launch {
            repository.saveHistory(
                WatchHistoryItem(
                    vodId = detail.id,
                    sourceId = detail.sourceId,
                    sourceName = detail.sourceName,
                    name = detail.name,
                    pic = detail.pic,
                    episodeIndex = state.episodeIndex,
                    episodeName = epName,
                    playSourceIndex = state.playSourceIndex,
                    positionMs = positionMs,
                    durationMs = durationMs
                )
            )
        }
    }

    fun toggleFavorite(detail: VodDetail) {
        viewModelScope.launch {
            val added = repository.toggleFavorite(
                FavoriteItem(
                    vodId = detail.id,
                    sourceId = detail.sourceId,
                    sourceName = detail.sourceName,
                    name = detail.name,
                    pic = detail.pic,
                    remarks = detail.remarks
                )
            )
            _toastMessage.tryEmit(if (added) "已加入收藏夹" else "已取消收藏")
        }
    }

    // Live TV
    fun loadLiveSource(liveSource: LiveSource, autoFallback: Boolean = false) {
        viewModelScope.launch {
            _liveState.value = _liveState.value.copy(
                selectedSourceId = liveSource.id,
                isLoading = true,
                error = null
            )
            try {
                val channels = liveParser.fetchChannels(liveSource.url)
                if (channels.isEmpty()) throw IllegalStateException("直播列表为空")
                val groups = listOf("全部") + channels.map { it.group }.distinct()
                _liveState.value = _liveState.value.copy(
                    channels = channels,
                    groups = groups,
                    selectedGroup = "全部",
                    isLoading = false,
                    error = null
                )
            } catch (e: Exception) {
                if (autoFallback) {
                    val alts = liveSources.value.filter { it.id != liveSource.id }
                    for (alt in alts) {
                        val chs = runCatching { liveParser.fetchChannels(alt.url) }.getOrNull()
                        if (!chs.isNullOrEmpty()) {
                            val groups = listOf("全部") + chs.map { it.group }.distinct()
                            _liveState.value = LiveUiState(
                                selectedSourceId = alt.id,
                                channels = chs,
                                groups = groups,
                                selectedGroup = "全部",
                                isLoading = false
                            )
                            _toastMessage.tryEmit("已自动切换至可用直播源：${alt.name}")
                            return@launch
                        }
                    }
                }
                _liveState.value = _liveState.value.copy(
                    isLoading = false,
                    error = "加载直播源失败：${e.message}"
                )
            }
        }
    }

    fun selectLiveGroup(group: String) {
        _liveState.value = _liveState.value.copy(selectedGroup = group)
    }

    // TVBox Subscription Import
    fun importTvBoxUrl(url: String, name: String = "") {
        if (url.isBlank()) return
        viewModelScope.launch {
            _isImporting.value = true
            try {
                val result = tvBoxParser.importFromUrl(url.trim(), name.trim())
                repository.mergeSubscriptionImport(
                    sub = result.subscription,
                    newSources = result.sources,
                    newLives = result.liveSources,
                    newParses = result.parses,
                    newAdHosts = result.adHosts
                )
                _toastMessage.tryEmit(
                    "导入成功：直连源 ${result.directPlayableCount} 个、总站点 ${result.totalRawSites} 个、直播 ${result.liveSources.size} 个、解析 ${result.parses.size} 个"
                )
            } catch (e: Exception) {
                _toastMessage.tryEmit("订阅导入失败：${e.message}")
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun importTvBoxJsonText(rawJson: String, name: String = "本地粘贴配置") {
        if (rawJson.isBlank()) return
        viewModelScope.launch {
            _isImporting.value = true
            try {
                val result = tvBoxParser.parseJsonText(rawJson, "", name)
                repository.mergeSubscriptionImport(
                    sub = result.subscription,
                    newSources = result.sources,
                    newLives = result.liveSources,
                    newParses = result.parses,
                    newAdHosts = result.adHosts
                )
                _toastMessage.tryEmit("JSON 导入成功：新增 ${result.sources.size} 个站点、${result.parses.size} 个解析")
            } catch (e: Exception) {
                _toastMessage.tryEmit("JSON 解析失败：${e.message}")
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun saveCustomSource(
        existingId: String?,
        name: String,
        type: SourceType,
        apiUrl: String,
        group: String
    ) {
        viewModelScope.launch {
            val src = VideoSource(
                id = existingId ?: "custom_${UUID.randomUUID().toString().take(8)}",
                name = name.trim(),
                type = type,
                apiUrl = apiUrl.trim(),
                group = group.trim().ifBlank { "自定义源" },
                enabled = true
            )
            repository.upsertSource(src)
            _toastMessage.tryEmit("已保存视频源：${src.name}")
        }
    }

    fun toggleSourceEnabled(source: VideoSource) {
        viewModelScope.launch {
            repository.upsertSource(source.copy(enabled = !source.enabled))
        }
    }

    fun deleteSource(sourceId: String) {
        viewModelScope.launch {
            repository.deleteSource(sourceId)
            _toastMessage.tryEmit("已删除视频源")
        }
    }

    fun testSourceLatency(source: VideoSource) {
        viewModelScope.launch {
            val ms = vodEngine.pingSource(source)
            repository.updateSourceLatency(source.id, ms)
            val msg = when {
                ms == -2L -> "${source.name} 为 TVBox 爬虫源，请使用网页嗅探或直连源"
                ms < 0L -> "${source.name} 连接超时或不可用"
                else -> "${source.name} 响应正常 (${ms} ms)"
            }
            _toastMessage.tryEmit(msg)
        }
    }

    fun addCustomLiveSource(name: String, url: String) {
        viewModelScope.launch {
            val live = LiveSource(
                id = "live_${UUID.randomUUID().toString().take(8)}",
                name = name.trim().ifBlank { "自定义直播源" },
                url = url.trim()
            )
            repository.addLiveSource(live)
            _toastMessage.tryEmit("已添加直播源：${live.name}")
        }
    }

    fun updateThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repository.updateSettings { it.copy(themeMode = mode) } }
    }

    fun updateDynamicColor(enabled: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(dynamicColor = enabled) } }
    }

    fun updateAutoNext(enabled: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(autoNextEpisode = enabled) } }
    }

    fun clearWatchHistory() {
        viewModelScope.launch {
            repository.clearHistory()
            _toastMessage.tryEmit("已清空观看历史")
        }
    }

    fun importBackup(jsonText: String) {
        viewModelScope.launch {
            runCatching {
                repository.importBackupJson(jsonText)
                _toastMessage.tryEmit("配置恢复成功")
                loadHomeFeed(reset = true)
            }.onFailure {
                _toastMessage.tryEmit("备份格式错误：${it.message}")
            }
        }
    }
}
