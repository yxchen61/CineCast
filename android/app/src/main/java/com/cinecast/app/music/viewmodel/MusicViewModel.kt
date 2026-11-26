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

data class ArtistDetailUiState(
    val artistName: String = "",
    val artistTag: String = "",
    val isLoading: Boolean = false,
    val tracks: List<MusicTrack> = emptyList(),
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

    private val _artistDetailState = MutableStateFlow(ArtistDetailUiState())
    val artistDetailState: StateFlow<ArtistDetailUiState> = _artistDetailState.asStateFlow()

    private val _playerState = MutableStateFlow(MusicPlayerUiState())
    val playerState: StateFlow<MusicPlayerUiState> = _playerState.asStateFlow()

    private var artistJob: Job? = null

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    private var searchJob: Job? = null
    private var switchJob: Job? = null
    private var playJob: Job? = null
    private var extrasJob: Job? = null
    private val triedSourceIdsForCurrentTrack = mutableSetOf<String>()
    private var consecutiveAutoSkipCount: Int = 0
    private val chartCache = java.util.concurrent.ConcurrentHashMap<String, List<MusicTrack>>()

    init {
        audioController.setOnTrackEnded {
            handleTrackCompleted()
        }
        audioController.setOnTrackError { reason ->
            handleCurrentTrackPlaybackError(reason)
        }
        audioController.setOnTrackReady {
            consecutiveAutoSkipCount = 0
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

        val cacheKey = "${source.id}::${targetChart.idOrQuery}"
        val cachedTracks = chartCache[cacheKey]
        if (!cachedTracks.isNullOrEmpty()) {
            _discoverState.value = MusicDiscoverUiState(
                charts = charts,
                selectedChartIndex = idx,
                tracks = cachedTracks,
                isLoading = false
            )
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
                if (tracks.isNotEmpty()) chartCache[cacheKey] = tracks
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
                            chartCache["${alt.id}::${firstChart.idOrQuery}"] = altTracks
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
                    MusicSourceType.VKEYS_API -> 0
                    MusicSourceType.KUWO_DIRECT -> 1
                    MusicSourceType.GDSTUDIO_API -> 2
                    MusicSourceType.AUDIUS_API -> 3
                    MusicSourceType.METING_API -> 4
                    MusicSourceType.RADIO_BROWSER -> 5
                    MusicSourceType.M3U_AUDIO -> 6
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

    // ==================== Artist Category & Precision Artist Query ====================

    fun querySongsByArtist(artistName: String, artistTag: String = "") {
        val name = artistName.trim()
        if (name.isBlank()) return
        val targets = getEnabledSources().filter { it.searchable }.sortedBy {
            when (it.sourceType) {
                MusicSourceType.VKEYS_API -> 0
                MusicSourceType.KUWO_DIRECT -> 1
                MusicSourceType.GDSTUDIO_API -> 2
                MusicSourceType.AUDIUS_API -> 3
                MusicSourceType.METING_API -> 4
                MusicSourceType.RADIO_BROWSER -> 5
                MusicSourceType.M3U_AUDIO -> 6
            }
        }.take(6)

        artistJob?.cancel()
        artistJob = viewModelScope.launch {
            _artistDetailState.value = ArtistDetailUiState(
                artistName = name,
                artistTag = artistTag,
                isLoading = true,
                tracks = emptyList(),
                error = null
            )
            val lock = Any()
            val acc = mutableListOf<MusicTrack>()
            val jobs = targets.map { src ->
                launch {
                    val found = withTimeoutOrNull(10000L) {
                        runCatching { musicEngine.search(src, name, 1) }.getOrDefault(emptyList())
                    }.orEmpty()
                    if (found.isNotEmpty()) {
                        val snap = synchronized(lock) {
                            acc.addAll(found)
                            acc.distinctBy { "${it.title.lowercase()}_${it.artist.lowercase()}_${it.sourceId}" }
                                .sortedByDescending { t ->
                                    when {
                                        t.artist.equals(name, ignoreCase = true) -> 4
                                        t.artist.contains(name, ignoreCase = true) -> 3
                                        t.title.contains(name, ignoreCase = true) -> 2
                                        else -> 1
                                    }
                                }
                        }
                        _artistDetailState.value = _artistDetailState.value.copy(
                            tracks = snap,
                            error = null
                        )
                    }
                }
            }
            jobs.joinAll()
            val finalTracks = _artistDetailState.value.tracks
            _artistDetailState.value = _artistDetailState.value.copy(
                isLoading = false,
                error = if (finalTracks.isEmpty()) "暂未查到「$name」的歌曲，可在「音乐源」页启用更多开源曲库" else null
            )
        }
    }

    fun clearArtistDetail() {
        artistJob?.cancel()
        _artistDetailState.value = ArtistDetailUiState()
    }

    companion object {
        val curatedArtists: List<MusicArtistProfile> = listOf(
            // 华语男歌手
            MusicArtistProfile("周杰伦", ArtistCategory.CN_MALE, "华语流行天王", 0xFF1A73E8),
            MusicArtistProfile("陈奕迅", ArtistCategory.CN_MALE, "粤语/国语金曲", 0xFF3949AB),
            MusicArtistProfile("林俊杰", ArtistCategory.CN_MALE, "行走的CD", 0xFF00897B),
            MusicArtistProfile("薛之谦", ArtistCategory.CN_MALE, "深情创作歌手", 0xFF5E35B1),
            MusicArtistProfile("陶喆", ArtistCategory.CN_MALE, "华语 R&B 教父", 0xFFD81B60),
            MusicArtistProfile("李荣浩", ArtistCategory.CN_MALE, "独立制作人", 0xFF455A64),
            MusicArtistProfile("毛不易", ArtistCategory.CN_MALE, "治愈民谣男声", 0xFF2E7D32),
            MusicArtistProfile("周深", ArtistCategory.CN_MALE, "天籁国风声线", 0xFF0288D1),
            MusicArtistProfile("许嵩", ArtistCategory.CN_MALE, "国风原创才子", 0xFF6D4C41),
            MusicArtistProfile("汪苏泷", ArtistCategory.CN_MALE, "人气唱作人", 0xFFF4511E),
            // 华语女歌手
            MusicArtistProfile("邓紫棋", ArtistCategory.CN_FEMALE, "铁肺创作天后", 0xFFD93025),
            MusicArtistProfile("王菲", ArtistCategory.CN_FEMALE, "华语传奇天后", 0xFF7B1FA2),
            MusicArtistProfile("孙燕姿", ArtistCategory.CN_FEMALE, "治愈系天后", 0xFF00796B),
            MusicArtistProfile("蔡依林", ArtistCategory.CN_FEMALE, "亚洲流行舞后", 0xFFC2185B),
            MusicArtistProfile("张韶涵", ArtistCategory.CN_FEMALE, "励志清亮高音", 0xFF1976D2),
            MusicArtistProfile("梁静茹", ArtistCategory.CN_FEMALE, "情歌天后", 0xFFE65100),
            MusicArtistProfile("单依纯", ArtistCategory.CN_FEMALE, "新生代灵魂歌者", 0xFF512DA8),
            MusicArtistProfile("郁可唯", ArtistCategory.CN_FEMALE, "影视 OST 女王", 0xFF0097A7),
            // 华语组合/乐队
            MusicArtistProfile("五月天", ArtistCategory.CN_GROUP, "华语摇滚天团", 0xFF1565C0),
            MusicArtistProfile("Beyond", ArtistCategory.CN_GROUP, "殿堂级粤语乐队", 0xFFC62828),
            MusicArtistProfile("告五人", ArtistCategory.CN_GROUP, "新世代人气乐团", 0xFF2E7D32),
            MusicArtistProfile("凤凰传奇", ArtistCategory.CN_GROUP, "国民律动组合", 0xFFEF6C00),
            MusicArtistProfile("S.H.E", ArtistCategory.CN_GROUP, "华语经典女团", 0xFFAD1457),
            MusicArtistProfile("草东没有派对", ArtistCategory.CN_GROUP, "独立摇滚乐队", 0xFF37474F),
            // 欧美流行
            MusicArtistProfile("Taylor Swift", ArtistCategory.WESTERN, "全球流行天后", 0xFF8E24AA),
            MusicArtistProfile("Ed Sheeran", ArtistCategory.WESTERN, "英伦吉他唱作人", 0xFFE64A19),
            MusicArtistProfile("Coldplay", ArtistCategory.WESTERN, "英伦摇滚天团", 0xFF1E88E5),
            MusicArtistProfile("Adele", ArtistCategory.WESTERN, "灵魂实力唱将", 0xFF4E342E),
            MusicArtistProfile("The Weeknd", ArtistCategory.WESTERN, "复古合成器 R&B", 0xFFB71C1C),
            MusicArtistProfile("Alan Walker", ArtistCategory.WESTERN, "电音制作人", 0xFF00838F),
            // 日韩歌手
            MusicArtistProfile("YOASOBI", ArtistCategory.JP_KR, "日本现象级双人组", 0xFFD81B60),
            MusicArtistProfile("米津玄师", ArtistCategory.JP_KR, "全能创作鬼才", 0xFF283593),
            MusicArtistProfile("IU", ArtistCategory.JP_KR, "韩国国民创作女声", 0xFF7B1FA2),
            MusicArtistProfile("RADWIMPS", ArtistCategory.JP_KR, "动漫电影原声乐队", 0xFF0277BD),
            MusicArtistProfile("BLACKPINK", ArtistCategory.JP_KR, "人气 K-Pop 女团", 0xFFC2185B),
            // 轻音乐/纯音
            MusicArtistProfile("久石让", ArtistCategory.INSTRUMENTAL, "宫崎骏电影配乐大师", 0xFF2E7D32),
            MusicArtistProfile("坂本龙一", ArtistCategory.INSTRUMENTAL, "现代钢琴与电子大师", 0xFF455A64),
            MusicArtistProfile("班得瑞", ArtistCategory.INSTRUMENTAL, "自然治愈轻音乐", 0xFF00695C),
            MusicArtistProfile("理查德·克莱德曼", ArtistCategory.INSTRUMENTAL, "浪漫钢琴王子", 0xFF1565C0),
            MusicArtistProfile("泽野弘之", ArtistCategory.INSTRUMENTAL, "史诗动漫燃系配乐", 0xFFBF360C)
        )
    }

    // ==================== Playback, Queue, Synced Lyrics & Source Switch ====================

    fun playTrackFromList(tracks: List<MusicTrack>, startIndex: Int) {
        if (tracks.isEmpty() || startIndex !in tracks.indices) return
        _playerState.value = _playerState.value.copy(
            queue = tracks,
            currentIndex = startIndex
        )
        playIndex(startIndex, resetFailoverState = true, isAutoAdvance = false)
    }

    fun playIndex(
        index: Int,
        resetFailoverState: Boolean = true,
        isAutoAdvance: Boolean = false
    ) {
        val queue = _playerState.value.queue
        val track = queue.getOrNull(index) ?: return
        val source = musicRepository.getSourceById(track.sourceId) ?: getActiveSource() ?: return

        playJob?.cancel()
        extrasJob?.cancel()
        if (resetFailoverState) {
            triedSourceIdsForCurrentTrack.clear()
        }
        if (!isAutoAdvance) {
            consecutiveAutoSkipCount = 0
        }
        triedSourceIdsForCurrentTrack.add(source.id)

        playJob = viewModelScope.launch {
            _playerState.value = _playerState.value.copy(
                currentIndex = index,
                currentTrack = track,
                lyrics = MusicSourceEngine.parseLrc(track.rawLyric),
                isResolvingStream = track.streamUrl.isBlank(),
                resolveError = null
            )
            try {
                // Step 1: Resolve audio URL ONLY and start ExoPlayer IMMEDIATELY (non-blocking)
                val resolved = withTimeoutOrNull(9000L) {
                    musicEngine.resolveStreamUrlFast(
                        source = source,
                        track = track,
                        preferHighBitrate = settings.value.preferHighBitrate
                    )
                }
                if (resolved == null || resolved.streamUrl.isBlank()) {
                    // Auto failover to next enabled music source for the SAME song!
                    autoSwitchSameSongOrAdvance(index, track, "当前音源暂无可用音频流")
                    return@launch
                }
                startResolvedPlayback(index, source, resolved)
            } catch (e: Exception) {
                autoSwitchSameSongOrAdvance(index, track, e.message ?: "解析音频失败")
            }
        }
    }

    private fun startResolvedPlayback(index: Int, source: MusicSource, resolved: MusicTrack) {
        val updatedQueue = _playerState.value.queue.toMutableList().apply {
            if (index in indices) this[index] = resolved
        }
        _playerState.value = _playerState.value.copy(
            queue = updatedQueue,
            currentIndex = index,
            currentTrack = resolved,
            lyrics = MusicSourceEngine.parseLrc(resolved.rawLyric).ifEmpty { _playerState.value.lyrics },
            isResolvingStream = false,
            resolveError = null
        )
        audioController.playUrl(resolved.streamUrl)
        viewModelScope.launch { musicRepository.recordRecentPlay(resolved) }

        // Step 2: Fetch cover & synced LRC lyrics asynchronously in background (zero audio delay!)
        extrasJob?.cancel()
        extrasJob = viewModelScope.launch {
            val (cover, lrcLines) = runCatching {
                musicEngine.resolveExtrasAsync(source, resolved)
            }.getOrDefault(resolved.coverUrl to emptyList())
            if (_playerState.value.currentTrack?.uniqueKey == resolved.uniqueKey) {
                val enriched = resolved.copy(coverUrl = cover.ifBlank { resolved.coverUrl })
                _playerState.value = _playerState.value.copy(
                    currentTrack = enriched,
                    lyrics = if (lrcLines.isNotEmpty()) lrcLines else _playerState.value.lyrics
                )
            }
        }
    }

    private fun handleCurrentTrackPlaybackError(reason: String) {
        val cur = _playerState.value
        val track = cur.currentTrack ?: return
        val idx = cur.currentIndex
        if (idx !in cur.queue.indices) return
        if (track.isLiveRadio) {
            _playerState.value = cur.copy(resolveError = "电台流暂时中断 ($reason)")
            return
        }
        playJob?.cancel()
        extrasJob?.cancel()
        playJob = viewModelScope.launch {
            autoSwitchSameSongOrAdvance(idx, track, reason)
        }
    }

    /**
     * 同曲跨源自动容灾切换：当前音源解析失败或 ExoPlayer 播放报错时，
     * 自动按响应速度优先检索其余已启用音源中的「同一首歌曲」并无缝续播；
     * 若全部音源均不可播，则自动安全跳过至队列下一首（带防死循环保护）。
     */
    private suspend fun autoSwitchSameSongOrAdvance(
        index: Int,
        failedTrack: MusicTrack,
        reason: String
    ) {
        val remainingSources = getEnabledSources()
            .filter {
                it.searchable &&
                    it.id !in triedSourceIdsForCurrentTrack &&
                    it.sourceType != MusicSourceType.RADIO_BROWSER &&
                    it.sourceType != MusicSourceType.M3U_AUDIO
            }
            .sortedBy {
                when (it.sourceType) {
                    MusicSourceType.VKEYS_API -> 0
                    MusicSourceType.KUWO_DIRECT -> 1
                    MusicSourceType.GDSTUDIO_API -> 2
                    MusicSourceType.METING_API -> 3
                    MusicSourceType.AUDIUS_API -> 4
                    else -> 5
                }
            }

        if (remainingSources.isNotEmpty()) {
            _playerState.value = _playerState.value.copy(
                isResolvingStream = true,
                resolveError = "当前音源异常，正自动切换其他音源播放《${failedTrack.title}》..."
            )
            val cleanArtist = failedTrack.artist.substringBefore("/").substringBefore("·").trim()
            val queryWithArtist = if (cleanArtist.isNotBlank() && cleanArtist != "未知歌手") {
                "${failedTrack.title} $cleanArtist"
            } else failedTrack.title

            for (altSource in remainingSources) {
                triedSourceIdsForCurrentTrack.add(altSource.id)
                val found = withTimeoutOrNull(6500L) {
                    runCatching { musicEngine.search(altSource, queryWithArtist, 1) }.getOrDefault(emptyList())
                }.orEmpty().ifEmpty {
                    if (queryWithArtist != failedTrack.title) {
                        withTimeoutOrNull(5500L) {
                            runCatching { musicEngine.search(altSource, failedTrack.title, 1) }.getOrDefault(emptyList())
                        }.orEmpty()
                    } else emptyList()
                }

                val candidate = MusicSourceEngine.pickBestSameSongCandidate(failedTrack, found) ?: continue
                val resolvedAlt = withTimeoutOrNull(7500L) {
                    runCatching {
                        musicEngine.resolveStreamUrlFast(
                            source = altSource,
                            track = candidate,
                            preferHighBitrate = settings.value.preferHighBitrate
                        )
                    }.getOrNull()
                }
                if (resolvedAlt != null && resolvedAlt.streamUrl.isNotBlank()) {
                    _toastMessage.tryEmit("音源异常，已自动切换「${altSource.name.substringBefore(" ")}」播放《${failedTrack.title}》")
                    startResolvedPlayback(index, altSource, resolvedAlt)
                    return
                }
            }
        }

        // All enabled sources failed for this song -> safely advance to next track if queue has more songs
        val curQueue = _playerState.value.queue
        val maxConsecutiveSkips = minOf((curQueue.size - 1).coerceAtLeast(0), 5)
        if (curQueue.size > 1 && consecutiveAutoSkipCount < maxConsecutiveSkips) {
            consecutiveAutoSkipCount++
            _toastMessage.tryEmit("《${failedTrack.title}》暂无可用音源，已自动播放下一首")
            advanceToNextTrack(isAutoAdvance = true)
        } else {
            _playerState.value = _playerState.value.copy(
                isResolvingStream = false,
                resolveError = "所有可用音源均无法播放《${failedTrack.title}》($reason)，请检查网络或手动换源"
            )
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
        advanceToNextTrack(isAutoAdvance = false)
    }

    private fun advanceToNextTrack(isAutoAdvance: Boolean) {
        val cur = _playerState.value
        if (cur.queue.isEmpty()) return
        val nextIdx = when (settings.value.repeatMode) {
            RepeatMode.SHUFFLE -> {
                if (cur.queue.size > 1) {
                    var picked = Random.nextInt(cur.queue.size)
                    if (picked == cur.currentIndex) picked = (picked + 1) % cur.queue.size
                    picked
                } else 0
            }
            else -> (cur.currentIndex + 1) % cur.queue.size
        }
        playIndex(nextIdx, resetFailoverState = true, isAutoAdvance = isAutoAdvance)
    }

    fun prevTrack() {
        val cur = _playerState.value
        if (cur.queue.isEmpty()) return
        val prevIdx = if (cur.currentIndex <= 0) cur.queue.lastIndex else cur.currentIndex - 1
        playIndex(prevIdx, resetFailoverState = true, isAutoAdvance = false)
    }

    private fun handleTrackCompleted() {
        val cur = _playerState.value
        if (cur.queue.isEmpty()) return
        when (settings.value.repeatMode) {
            RepeatMode.LOOP_ONE -> {
                val url = cur.currentTrack?.streamUrl
                if (!url.isNullOrBlank()) {
                    audioController.playUrl(url)
                } else {
                    playIndex(cur.currentIndex.coerceIn(0, cur.queue.lastIndex), resetFailoverState = true, isAutoAdvance = true)
                }
            }
            else -> {
                if (cur.queue.size == 1) {
                    val url = cur.currentTrack?.streamUrl
                    if (!url.isNullOrBlank()) audioController.playUrl(url)
                } else {
                    advanceToNextTrack(isAutoAdvance = true)
                }
            }
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
                MusicSourceType.VKEYS_API -> listOf(
                    MusicChartCategory("热歌精选", "热门"),
                    MusicChartCategory("华语金曲", "周杰伦")
                )
                MusicSourceType.KUWO_DIRECT -> listOf(
                    MusicChartCategory("酷我热歌榜", "16"),
                    MusicChartCategory("酷我飙升榜", "93")
                )
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
