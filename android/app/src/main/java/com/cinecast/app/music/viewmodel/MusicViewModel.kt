package com.cinecast.app.music.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cinecast.app.CineCastApp
import com.cinecast.app.music.engine.MusicSourceEngine
import com.cinecast.app.music.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.random.Random

data class MusicDiscoverUiState(
    val charts: List<MusicChartCategory> = emptyList(),
    val selectedChartIndex: Int = 0,
    val tracks: List<MusicTrack> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

data class MusicSearchUiState(
    val query: String = "",
    val searchAllSources: Boolean = true,
    val isSearching: Boolean = false,
    val results: List<MusicTrack> = emptyList(),
    val error: String? = null
)

data class MusicPlayerUiState(
    val queue: List<MusicTrack> = emptyList(),
    val currentIndex: Int = -1,
    val currentTrack: MusicTrack? = null,
    val lyrics: List<LrcLine> = emptyList(),
    val isResolvingStream: Boolean = false,
    val resolveError: String? = null,
    val switchCandidates: List<MusicTrack> = emptyList(),
    val isSearchingSwitch: Boolean = false
)

/**
 * 独立音乐模块 ViewModel —— 与视频 MainViewModel、小说 NovelViewModel 完全分离
 */
class MusicViewModel : ViewModel() {

    private val app = CineCastApp.instance
    val musicRepository = app.musicRepository
    private val musicEngine = app.musicEngine
    private val sourceParser = app.musicSourceParser
    val audioController = app.musicAudioController

    val sources = musicRepository.sources
    val activeSourceId = musicRepository.activeSourceId
    val subscriptions = musicRepository.subscriptions
    val favorites = musicRepository.favorites
    val playlists = musicRepository.playlists
    val recentPlays = musicRepository.recentPlays
    val settings = musicRepository.settings
    val playbackState = audioController.state

    private val _discoverState = MutableStateFlow(MusicDiscoverUiState())
    val discoverState: StateFlow<MusicDiscoverUiState> = _discoverState.asStateFlow()

    private val _searchState = MutableStateFlow(MusicSearchUiState())
    val searchState: StateFlow<MusicSearchUiState> = _searchState.asStateFlow()

    private val _playerState = MutableStateFlow(MusicPlayerUiState())
    val playerState: StateFlow<MusicPlayerUiState> = _playerState.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    private var searchJob: Job? = null
    private var switchJob: Job? = null

    init {
        audioController.setOnTrackEnded {
            handleTrackCompleted()
        }
        viewModelScope.launch {
            while (isActive) {
                audioController.pollProgress()
                delay(400L)
            }
        }
        loadDiscover(autoFallback = true)
    }

    fun getEnabledSources(): List<MusicSource> = sources.value.filter { it.enabled }

    fun getActiveSource(): MusicSource? =
        musicRepository.getSourceById(activeSourceId.value) ?: getEnabledSources().firstOrNull()

    fun selectSource(sourceId: String) {
        musicRepository.setActiveSource(sourceId)
        _discoverState.value = MusicDiscoverUiState()
        loadDiscover(autoFallback = false)
    }

    fun selectChart(index: Int) {
        _discoverState.value = _discoverState.value.copy(selectedChartIndex = index)
        loadDiscover(autoFallback = false)
    }

    fun loadDiscover(autoFallback: Boolean = false) {
        val source = getActiveSource()
        if (source == null) {
            _discoverState.value = MusicDiscoverUiState(error = "请先在「音乐源」页启用或导入开源音乐源")
            return
        }
        val charts = source.charts
        val idx = _discoverState.value.selectedChartIndex.coerceIn(0, (charts.size - 1).coerceAtLeast(0))
        val targetChart = charts.getOrNull(idx)
        if (targetChart == null) {
            _discoverState.value = MusicDiscoverUiState(error = "当前音乐源无榜单配置，请直接通过顶部搜索框搜歌")
            return
        }

        viewModelScope.launch {
            _discoverState.value = _discoverState.value.copy(
                charts = charts,
                selectedChartIndex = idx,
                isLoading = true,
                error = null
            )
            try {
                val tracks = musicEngine.fetchChart(source, targetChart, 1)
                _discoverState.value = _discoverState.value.copy(
                    charts = charts,
                    selectedChartIndex = idx,
                    tracks = tracks,
                    isLoading = false,
                    error = if (tracks.isEmpty()) "当前榜单暂无曲目，可切换音乐源或直接搜索" else null
                )
            } catch (e: Exception) {
                if (autoFallback) {
                    val alts = getEnabledSources().filter { it.id != source.id && it.charts.isNotEmpty() }
                    for (alt in alts) {
                        val firstChart = alt.charts.firstOrNull() ?: continue
                        val altTracks = runCatching { musicEngine.fetchChart(alt, firstChart, 1) }.getOrNull()
                        if (!altTracks.isNullOrEmpty()) {
                            musicRepository.setActiveSource(alt.id)
                            _discoverState.value = MusicDiscoverUiState(
                                charts = alt.charts,
                                selectedChartIndex = 0,
                                tracks = altTracks,
                                isLoading = false
                            )
                            _toastMessage.tryEmit("已自动切换至高速可用音乐源：${alt.name}")
                            return@launch
                        }
                    }
                }
                _discoverState.value = _discoverState.value.copy(
                    isLoading = false,
                    error = "加载榜单失败：${e.message ?: "请检查网络或切换音乐源"}"
                )
            }
        }
    }

    // ==================== Multi-Source Concurrent Song Search ====================

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
            // Prioritize GDStudio & Audius for full catalog search
            val prioritized = all.sortedBy {
                when (it.sourceType) {
                    MusicSourceType.GDSTUDIO_API -> 0
                    MusicSourceType.METING_API -> 1
                    MusicSourceType.AUDIUS_API -> 2
                    MusicSourceType.RADIO_BROWSER -> 3
                    MusicSourceType.M3U_AUDIO -> 4
                }
            }
            (listOfNotNull(active?.takeIf { it.searchable }) + prioritized).distinctBy { it.id }.take(8)
        } else {
            listOfNotNull(active)
        }

        if (targets.isEmpty()) {
            _searchState.value = _searchState.value.copy(error = "没有可搜索的音乐源")
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
            val acc = mutableListOf<MusicTrack>()
            val jobs = targets.map { src ->
                launch {
                    val found = withTimeoutOrNull(10000L) {
                        runCatching { musicEngine.search(src, q, 1) }.getOrDefault(emptyList())
                    }.orEmpty()
                    if (found.isNotEmpty()) {
                        val snap = synchronized(lock) {
                            acc.addAll(found)
                            acc.distinctBy { it.uniqueKey }.sortedByDescending { t ->
                                when {
                                    t.title.equals(q, true) -> 4
                                    t.title.contains(q, true) -> 3
                                    t.artist.contains(q, true) -> 2
                                    else -> 1
                                }
                            }
                        }
                        _searchState.value = _searchState.value.copy(results = snap, error = null)
                    }
                }
            }
            jobs.joinAll()
            val finalList = _searchState.value.results
            _searchState.value = _searchState.value.copy(
                isSearching = false,
                error = if (finalList.isEmpty()) "未找到「$q」相关歌曲，可尝试其他关键词或在「音乐源」页导入更多源" else null
            )
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _searchState.value = MusicSearchUiState(searchAllSources = _searchState.value.searchAllSources)
    }

    // ==================== Playback, Queue, Synced Lyrics & Source Switch ====================

    fun playTrackFromList(tracks: List<MusicTrack>, startIndex: Int) {
        if (tracks.isEmpty() || startIndex !in tracks.indices) return
        _playerState.value = _playerState.value.copy(
            queue = tracks,
            currentIndex = startIndex
        )
        playIndex(startIndex)
    }

    fun playIndex(index: Int) {
        val queue = _playerState.value.queue
        val track = queue.getOrNull(index) ?: return
        val source = musicRepository.getSourceById(track.sourceId) ?: getActiveSource() ?: return

        viewModelScope.launch {
            _playerState.value = _playerState.value.copy(
                currentIndex = index,
                currentTrack = track,
                lyrics = MusicSourceEngine.parseLrc(track.rawLyric),
                isResolvingStream = true,
                resolveError = null
            )
            try {
                val resolved = musicEngine.resolvePlayableTrack(
                    source = source,
                    track = track,
                    preferHighBitrate = settings.value.preferHighBitrate
                )
                if (resolved.streamUrl.isBlank()) {
                    _playerState.value = _playerState.value.copy(
                        currentTrack = resolved,
                        isResolvingStream = false,
                        resolveError = "当前音乐源暂无此曲音频流，请点击「换源」试听其他源"
                    )
                    return@launch
                }
                val updatedQueue = _playerState.value.queue.toMutableList().apply {
                    if (index in indices) this[index] = resolved
                }
                _playerState.value = _playerState.value.copy(
                    queue = updatedQueue,
                    currentTrack = resolved,
                    lyrics = MusicSourceEngine.parseLrc(resolved.rawLyric),
                    isResolvingStream = false,
                    resolveError = null
                )
                audioController.playUrl(resolved.streamUrl)
                musicRepository.recordRecentPlay(resolved)
            } catch (e: Exception) {
                _playerState.value = _playerState.value.copy(
                    isResolvingStream = false,
                    resolveError = "解析音频失败：${e.message ?: "请尝试换源"}"
                )
            }
        }
    }

    fun togglePlayPause() {
        audioController.togglePlayPause()
    }

    fun pauseAudio() {
        audioController.pause()
    }

    fun seekTo(positionMs: Long) {
        audioController.seekTo(positionMs)
    }

    fun nextTrack() {
        val cur = _playerState.value
        if (cur.queue.isEmpty()) return
        val nextIdx = when (settings.value.repeatMode) {
            RepeatMode.SHUFFLE -> if (cur.queue.size > 1) Random.nextInt(cur.queue.size) else 0
            else -> (cur.currentIndex + 1) % cur.queue.size
        }
        playIndex(nextIdx)
    }

    fun prevTrack() {
        val cur = _playerState.value
        if (cur.queue.isEmpty()) return
        val prevIdx = if (cur.currentIndex <= 0) cur.queue.lastIndex else cur.currentIndex - 1
        playIndex(prevIdx)
    }

    private fun handleTrackCompleted() {
        val cur = _playerState.value
        if (cur.queue.isEmpty()) return
        when (settings.value.repeatMode) {
            RepeatMode.LOOP_ONE -> {
                audioController.seekTo(0L)
                cur.currentTrack?.streamUrl?.takeIf { it.isNotBlank() }?.let { audioController.playUrl(it) }
            }
            else -> nextTrack()
        }
    }

    fun cycleRepeatMode() {
        val next = when (settings.value.repeatMode) {
            RepeatMode.LOOP_ALL -> RepeatMode.LOOP_ONE
            RepeatMode.LOOP_ONE -> RepeatMode.SHUFFLE
            RepeatMode.SHUFFLE -> RepeatMode.LOOP_ALL
        }
        viewModelScope.launch {
            musicRepository.updateSettings { it.copy(repeatMode = next) }
            _toastMessage.tryEmit("播放模式：${next.label}")
        }
    }

    fun searchAlternativeSourcesForCurrentTrack() {
        val track = _playerState.value.currentTrack ?: return
        val targets = getEnabledSources().filter { it.searchable && it.id != track.sourceId }.take(6)
        switchJob?.cancel()
        switchJob = viewModelScope.launch {
            _playerState.value = _playerState.value.copy(
                isSearchingSwitch = true,
                switchCandidates = emptyList()
            )
            val lock = Any()
            val acc = mutableListOf<MusicTrack>()
            val jobs = targets.map { src ->
                launch {
                    val res = withTimeoutOrNull(9000L) {
                        runCatching { musicEngine.search(src, "${track.title} ${track.artist.substringBefore("/")}", 1) }
                            .getOrDefault(emptyList())
                    }.orEmpty().ifEmpty {
                        withTimeoutOrNull(8000L) {
                            runCatching { musicEngine.search(src, track.title, 1) }.getOrDefault(emptyList())
                        }.orEmpty()
                    }
                    if (res.isNotEmpty()) {
                        val snap = synchronized(lock) {
                            acc.addAll(res.take(2))
                            acc.distinctBy { it.uniqueKey }
                        }
                        _playerState.value = _playerState.value.copy(switchCandidates = snap)
                    }
                }
            }
            jobs.joinAll()
            _playerState.value = _playerState.value.copy(isSearchingSwitch = false)
        }
    }

    fun switchToTrack(alt: MusicTrack) {
        val cur = _playerState.value
        val updatedQueue = cur.queue.toMutableList()
        val idx = cur.currentIndex
        if (idx in updatedQueue.indices) {
            updatedQueue[idx] = alt
            _playerState.value = cur.copy(queue = updatedQueue)
            playIndex(idx)
        } else {
            playTrackFromList(listOf(alt), 0)
        }
        _toastMessage.tryEmit("已切换音源：${alt.sourceName}")
    }

    // ==================== Favorites & Playlists ====================

    fun toggleFavorite(track: MusicTrack) {
        viewModelScope.launch {
            val added = musicRepository.toggleFavorite(track)
            _toastMessage.tryEmit(if (added) "已加入「我喜欢的音乐」" else "已取消喜欢")
        }
    }

    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            musicRepository.createPlaylist(name)
            _toastMessage.tryEmit("已创建歌单：$name")
        }
    }

    fun deletePlaylist(id: String) {
        viewModelScope.launch {
            musicRepository.deletePlaylist(id)
            _toastMessage.tryEmit("已删除歌单")
        }
    }

    fun addTrackToPlaylist(playlistId: String, track: MusicTrack) {
        viewModelScope.launch {
            musicRepository.addTrackToPlaylist(playlistId, track)
            _toastMessage.tryEmit("已添加到歌单")
        }
    }

    // ==================== Music Sources & GitHub Subscription Import ====================

    fun importMusicUrl(url: String, customName: String = "") {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            _isImporting.value = true
            try {
                val res = sourceParser.importFromUrl(trimmed, customName)
                musicRepository.mergeSubscriptionImport(res.subscription, res.sources)
                _toastMessage.tryEmit("导入成功：解析 ${res.sources.size} 个可用音乐源（共 ${res.totalRawCount} 条）")
            } catch (e: Exception) {
                _toastMessage.tryEmit("拉取音乐源失败：${e.message}")
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun importMusicText(rawText: String) {
        if (rawText.isBlank()) return
        viewModelScope.launch {
            _isImporting.value = true
            try {
                val res = sourceParser.parseImportText(rawText, "", "本地粘贴音乐源")
                musicRepository.mergeSubscriptionImport(res.subscription, res.sources)
                _toastMessage.tryEmit("导入成功：新增 ${res.sources.size} 个音乐源")
            } catch (e: Exception) {
                _toastMessage.tryEmit("音乐源解析失败：${e.message}")
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun saveCustomSource(
        existingId: String?,
        name: String,
        baseUrl: String,
        type: MusicSourceType,
        platform: String,
        group: String
    ) {
        viewModelScope.launch {
            val existing = existingId?.let { musicRepository.getSourceById(it) }
            val defaultCharts = existing?.charts?.takeIf { it.isNotEmpty() } ?: when (type) {
                MusicSourceType.METING_API -> listOf(
                    MusicChartCategory("热歌榜", "3778678"),
                    MusicChartCategory("飙升榜", "19723756"),
                    MusicChartCategory("新歌榜", "3779629")
                )
                MusicSourceType.GDSTUDIO_API -> listOf(
                    MusicChartCategory("华语热歌", "热门"),
                    MusicChartCategory("经典金曲", "周杰伦")
                )
                MusicSourceType.AUDIUS_API -> listOf(MusicChartCategory("全球热榜", "trending"))
                MusicSourceType.RADIO_BROWSER -> listOf(MusicChartCategory("华语音乐台", "cn"))
                MusicSourceType.M3U_AUDIO -> listOf(MusicChartCategory("全部音频", baseUrl.trim()))
            }
            val src = MusicSource(
                id = existingId ?: "custom_music_${UUID.randomUUID().toString().take(8)}",
                name = name.trim(),
                baseUrl = baseUrl.trim(),
                sourceType = type,
                platform = platform.trim().ifBlank { "netease" },
                group = group.trim().ifBlank { "自定义音乐源" },
                enabled = existing?.enabled ?: true,
                charts = defaultCharts
            )
            musicRepository.upsertSource(src)
            _toastMessage.tryEmit("已保存音乐源：${src.name}")
        }
    }

    fun toggleSourceEnabled(source: MusicSource) {
        viewModelScope.launch {
            musicRepository.upsertSource(source.copy(enabled = !source.enabled))
        }
    }

    fun deleteSource(sourceId: String) {
        viewModelScope.launch {
            musicRepository.deleteSource(sourceId)
            _toastMessage.tryEmit("已删除音乐源")
        }
    }

    fun testSourceLatency(source: MusicSource) {
        viewModelScope.launch {
            val ms = musicEngine.pingSource(source)
            musicRepository.updateSourceLatency(source.id, ms)
            _toastMessage.tryEmit(
                if (ms < 0) "${source.name} 连接超时或暂不可用" else "${source.name} 响应正常 (${ms} ms)"
            )
        }
    }

    fun importMusicBackup(rawJson: String) {
        viewModelScope.launch {
            runCatching {
                musicRepository.importMusicBackupJson(rawJson)
                _toastMessage.tryEmit("音乐源与歌单备份恢复成功")
                loadDiscover(autoFallback = false)
            }.onFailure {
                _toastMessage.tryEmit("音乐备份格式错误：${it.message}")
            }
        }
    }
}
