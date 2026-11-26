package com.cinecast.app.music.repository

import android.content.Context
import com.cinecast.app.music.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 独立音乐模块本地数据仓库 —— 与影视 AppRepository、小说 NovelRepository 完全物理隔离
 */
class MusicRepository(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val mutex = Mutex()

    private val sourcesFile get() = File(context.filesDir, "cinecast_music_sources.json")
    private val subsFile get() = File(context.filesDir, "cinecast_music_subscriptions.json")
    private val favoritesFile get() = File(context.filesDir, "cinecast_music_favorites.json")
    private val playlistsFile get() = File(context.filesDir, "cinecast_music_playlists.json")
    private val recentFile get() = File(context.filesDir, "cinecast_music_recent.json")
    private val settingsFile get() = File(context.filesDir, "cinecast_music_settings.json")

    private val _sources = MutableStateFlow<List<MusicSource>>(emptyList())
    val sources: StateFlow<List<MusicSource>> = _sources.asStateFlow()

    private val _activeSourceId = MutableStateFlow("")
    val activeSourceId: StateFlow<String> = _activeSourceId.asStateFlow()

    private val _subscriptions = MutableStateFlow<List<MusicSubscription>>(emptyList())
    val subscriptions: StateFlow<List<MusicSubscription>> = _subscriptions.asStateFlow()

    private val _favorites = MutableStateFlow<List<MusicTrack>>(emptyList())
    val favorites: StateFlow<List<MusicTrack>> = _favorites.asStateFlow()

    private val _playlists = MutableStateFlow<List<MusicPlaylist>>(emptyList())
    val playlists: StateFlow<List<MusicPlaylist>> = _playlists.asStateFlow()

    private val _recentPlays = MutableStateFlow<List<MusicTrack>>(emptyList())
    val recentPlays: StateFlow<List<MusicTrack>> = _recentPlays.asStateFlow()

    private val _settings = MutableStateFlow(MusicSettings())
    val settings: StateFlow<MusicSettings> = _settings.asStateFlow()

    init {
        loadAllSync()
    }

    private fun loadAllSync() {
        val rawLoaded = readJson<List<MusicSource>>(sourcesFile)
        val defaults = defaultMusicSources()
        val loadedSources = if (rawLoaded == null) {
            defaults.also { writeJsonSync(sourcesFile, it) }
        } else {
            val needsV171Migration = rawLoaded.any {
                it.id == "fast_bilibili_music" || it.sourceType == MusicSourceType.BILIBILI_MUSIC
            } || rawLoaded.none { it.id == "git_gdstudio_joox" } || rawLoaded.none { it.id == "fast_imeto_meting" }
            val stripped = rawLoaded.filterNot {
                it.id == "fast_bilibili_music" || it.sourceType == MusicSourceType.BILIBILI_MUSIC
            }
            val existingIds = stripped.map { it.id }.toSet()
            val missingDefaults = defaults.filter { it.id !in existingIds }
            if (needsV171Migration || missingDefaults.isNotEmpty()) {
                val updatedExisting = stripped.map { src ->
                    if (src.id == "fast_vkeys_qq") {
                        src.copy(
                            enabled = false,
                            group = "备用聚合曲库 (易超时/含试听)",
                            comment = "易超时或返回30秒试听，已默认关闭，推荐优先使用奇杰雅320K / 酷我直连 / i-Meto / GD-JOOX。"
                        )
                    } else src
                }
                // Keep new verified full-length sources at the top in optimal order
                val preferredOrder = defaults.map { it.id }
                val merged = (missingDefaults + updatedExisting)
                    .distinctBy { it.id }
                    .sortedBy { s -> preferredOrder.indexOf(s.id).let { if (it >= 0) it else 999 } }
                writeJsonSync(sourcesFile, merged)
                merged
            } else stripped
        }
        _sources.value = loadedSources
        _activeSourceId.value = loadedSources.firstOrNull { it.enabled }?.id
            ?: loadedSources.firstOrNull()?.id.orEmpty()

        val loadedSubs = readJson<List<MusicSubscription>>(subsFile) ?: defaultMusicSubscriptions().also {
            writeJsonSync(subsFile, it)
        }
        _subscriptions.value = loadedSubs

        _favorites.value = readJson<List<MusicTrack>>(favoritesFile) ?: emptyList()
        _playlists.value = readJson<List<MusicPlaylist>>(playlistsFile) ?: emptyList()
        _recentPlays.value = readJson<List<MusicTrack>>(recentFile) ?: emptyList()
        _settings.value = readJson<MusicSettings>(settingsFile) ?: MusicSettings()
    }

    fun setActiveSource(sourceId: String) {
        _activeSourceId.value = sourceId
    }

    fun getSourceById(sourceId: String): MusicSource? =
        _sources.value.firstOrNull { it.id == sourceId }

    suspend fun upsertSource(source: MusicSource) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _sources.value.toMutableList()
            val idx = list.indexOfFirst { it.id == source.id }
            if (idx >= 0) list[idx] = source else list.add(0, source)
            _sources.value = list
            writeJsonSync(sourcesFile, list)
            if (_activeSourceId.value.isBlank() && source.enabled) {
                _activeSourceId.value = source.id
            }
        }
    }

    suspend fun deleteSource(sourceId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _sources.value.filterNot { it.id == sourceId }
            _sources.value = list
            writeJsonSync(sourcesFile, list)
            if (_activeSourceId.value == sourceId) {
                _activeSourceId.value = list.firstOrNull { it.enabled }?.id ?: list.firstOrNull()?.id.orEmpty()
            }
        }
    }

    suspend fun updateSourceLatency(sourceId: String, latencyMs: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _sources.value.map {
                if (it.id == sourceId) it.copy(latencyMs = latencyMs) else it
            }
            _sources.value = list
            writeJsonSync(sourcesFile, list)
        }
    }

    suspend fun mergeSubscriptionImport(
        sub: MusicSubscription,
        newSources: List<MusicSource>
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val subs = _subscriptions.value.toMutableList()
            val sIdx = subs.indexOfFirst { it.url == sub.url || it.id == sub.id }
            if (sIdx >= 0) subs[sIdx] = sub else subs.add(0, sub)
            _subscriptions.value = subs
            writeJsonSync(subsFile, subs)

            val kept = _sources.value.filterNot { it.subscriptionUrl == sub.url && sub.url.isNotBlank() }
            val existingKeys = kept.map { "${it.sourceType}|${it.baseUrl}|${it.platform}|${it.name}" }.toSet()
            val deduped = newSources.filterNot { "${it.sourceType}|${it.baseUrl}|${it.platform}|${it.name}" in existingKeys }
            val merged = kept + deduped
            _sources.value = merged
            writeJsonSync(sourcesFile, merged)
        }
    }

    // ==================== Favorites, Playlists & Recent Plays ====================

    fun isFavorite(track: MusicTrack): Boolean =
        _favorites.value.any { it.uniqueKey == track.uniqueKey }

    suspend fun toggleFavorite(track: MusicTrack): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val exists = _favorites.value.any { it.uniqueKey == track.uniqueKey }
            val updated = if (exists) {
                _favorites.value.filterNot { it.uniqueKey == track.uniqueKey }
            } else {
                listOf(track) + _favorites.value
            }
            _favorites.value = updated
            writeJsonSync(favoritesFile, updated)
            !exists
        }
    }

    suspend fun recordRecentPlay(track: MusicTrack) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = (listOf(track) + _recentPlays.value.filterNot { it.uniqueKey == track.uniqueKey }).take(80)
            _recentPlays.value = updated
            writeJsonSync(recentFile, updated)
        }
    }

    suspend fun createPlaylist(name: String): MusicPlaylist = withContext(Dispatchers.IO) {
        mutex.withLock {
            val playlist = MusicPlaylist(
                id = "mpl_${System.currentTimeMillis()}",
                name = name.trim()
            )
            val updated = listOf(playlist) + _playlists.value
            _playlists.value = updated
            writeJsonSync(playlistsFile, updated)
            playlist
        }
    }

    suspend fun deletePlaylist(playlistId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = _playlists.value.filterNot { it.id == playlistId }
            _playlists.value = updated
            writeJsonSync(playlistsFile, updated)
        }
    }

    suspend fun addTrackToPlaylist(playlistId: String, track: MusicTrack) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = _playlists.value.map { pl ->
                if (pl.id == playlistId) {
                    val tracks = (listOf(track) + pl.tracks.filterNot { it.uniqueKey == track.uniqueKey })
                    pl.copy(tracks = tracks)
                } else pl
            }
            _playlists.value = updated
            writeJsonSync(playlistsFile, updated)
        }
    }

    suspend fun updateSettings(transform: (MusicSettings) -> MusicSettings) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = transform(_settings.value)
            _settings.value = updated
            writeJsonSync(settingsFile, updated)
        }
    }

    // ==================== Independent Music Backup ====================

    fun exportMusicBackupJson(): String {
        val bundle = MusicBackupBundle(
            musicSources = _sources.value,
            musicSubscriptions = _subscriptions.value,
            favorites = _favorites.value,
            playlists = _playlists.value,
            settings = _settings.value
        )
        return json.encodeToString(bundle)
    }

    suspend fun importMusicBackupJson(rawJson: String) = withContext(Dispatchers.IO) {
        val bundle = json.decodeFromString<MusicBackupBundle>(rawJson)
        mutex.withLock {
            if (bundle.musicSources.isNotEmpty()) {
                _sources.value = bundle.musicSources
                writeJsonSync(sourcesFile, bundle.musicSources)
            }
            if (bundle.musicSubscriptions.isNotEmpty()) {
                _subscriptions.value = bundle.musicSubscriptions
                writeJsonSync(subsFile, bundle.musicSubscriptions)
            }
            if (bundle.favorites.isNotEmpty()) {
                _favorites.value = bundle.favorites
                writeJsonSync(favoritesFile, bundle.favorites)
            }
            if (bundle.playlists.isNotEmpty()) {
                _playlists.value = bundle.playlists
                writeJsonSync(playlistsFile, bundle.playlists)
            }
            _settings.value = bundle.settings
            writeJsonSync(settingsFile, bundle.settings)
        }
    }

    private inline fun <reified T> readJson(file: File): T? {
        return try {
            if (!file.exists()) return null
            json.decodeFromString<T>(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    private inline fun <reified T> writeJsonSync(file: File, value: T) {
        try {
            file.writeText(json.encodeToString(value))
        } catch (_: Exception) {
        }
    }

    companion object {
        /**
         * 实测全链路可直连播放（含封面与同步 LRC 歌词）的 GitHub 开源生态默认音乐源
         */
        fun defaultMusicSources(): List<MusicSource> = listOf(
            // #1 默认首选：奇杰雅云音乐 320K（实测 200 首网易云热歌全曲 7~12MB 320kbps MP3 直出，含完整封面与同步歌词）
            MusicSource(
                id = "fast_qijieya_meting",
                name = "奇杰雅云音乐 320K (默认推荐·全曲)",
                baseUrl = "https://api.qijieya.cn/meting/",
                sourceType = MusicSourceType.METING_API,
                platform = "netease",
                group = "320K 完整全曲 (国内实测首选)",
                charts = listOf(
                    MusicChartCategory("热歌榜", "3778678"),
                    MusicChartCategory("飙升榜", "19723756"),
                    MusicChartCategory("新歌榜", "3779629"),
                    MusicChartCategory("原创榜", "2884035"),
                    MusicChartCategory("怀旧经典", "71385702")
                ),
                comment = "实测返回 7~12MB 完整版 320kbps 高音质 MP3（含周杰伦/孙燕姿等全曲），支持热歌/飙升/新歌榜与逐行 LRC 歌词。"
            ),
            // #2 国内官方高速全曲源：酷我官方高速直连（实测搜歌与榜单直出 4MB 官方北京 CDN 完整 MP3 + 同步歌词）
            MusicSource(
                id = "fast_kuwo_direct",
                name = "酷我官方高速直连 (搜歌秒播·全曲)",
                baseUrl = "http://kbangserver.kuwo.cn",
                sourceType = MusicSourceType.KUWO_DIRECT,
                platform = "kuwo",
                group = "320K 完整全曲 (国内实测首选)",
                charts = listOf(
                    MusicChartCategory("酷我热歌榜", "16"),
                    MusicChartCategory("酷我飙升榜", "93"),
                    MusicChartCategory("酷我新歌榜", "17"),
                    MusicChartCategory("抖音热歌榜", "158"),
                    MusicChartCategory("华语金曲榜", "145")
                ),
                comment = "直连酷我官方国内服务器，搜歌响应快，实测周杰伦《晴天》等完整 4MB 官方 MP3 直链秒播，自动过滤伴奏。"
            ),
            // #3 官方 Meting 高速节点：i-Meto 320K 完整版榜单（实测 1.1s 极速响应，7~11MB 完整版 MP3）
            MusicSource(
                id = "fast_imeto_meting",
                name = "i-Meto 云音乐 320K (高速完整版)",
                baseUrl = "https://api.i-meto.com/meting/api",
                sourceType = MusicSourceType.METING_API,
                platform = "netease",
                group = "320K 完整全曲 (国内实测首选)",
                charts = listOf(
                    MusicChartCategory("热歌榜", "3778678"),
                    MusicChartCategory("飙升榜", "19723756"),
                    MusicChartCategory("新歌榜", "3779629"),
                    MusicChartCategory("原创榜", "2884035")
                ),
                comment = "Meting 作者官方节点，实测 1.1s 加载 200 首热歌，返回 7~11MB 完整版 MP3，与奇杰雅节点互为高速容灾备份。"
            ),
            // #4 港台华语 VIP 全曲利器：GD音乐台 · JOOX 320K（实测周杰伦《晴天》10.29MB、陈奕迅《十年》7.81MB 全曲）
            MusicSource(
                id = "git_gdstudio_joox",
                name = "GD音乐台 · JOOX 320K (港台/周杰伦全曲)",
                baseUrl = "https://music-api.gdstudio.xyz/api.php",
                sourceType = MusicSourceType.GDSTUDIO_API,
                platform = "joox",
                group = "320K 完整全曲 (国内实测首选)",
                charts = listOf(
                    MusicChartCategory("周杰伦金曲", "周杰伦"),
                    MusicChartCategory("陈奕迅经典", "陈奕迅"),
                    MusicChartCategory("林俊杰精选", "林俊杰"),
                    MusicChartCategory("华语热歌", "热门"),
                    MusicChartCategory("粤语经典", "粤语")
                ),
                comment = "实测 JOOX 线路支持周杰伦《晴天》10.29MB、陈奕迅《十年》7.81MB 等 320kbps 完整全曲与逐行 LRC 歌词。"
            ),
            MusicSource(
                id = "git_meting_injahow",
                name = "Injahow 云音乐备用榜 (Meting)",
                baseUrl = "https://api.injahow.cn/meting/",
                sourceType = MusicSourceType.METING_API,
                platform = "netease",
                group = "开源聚合曲库 (备用)",
                charts = listOf(
                    MusicChartCategory("热歌榜", "3778678"),
                    MusicChartCategory("飙升榜", "19723756"),
                    MusicChartCategory("新歌榜", "3779629"),
                    MusicChartCategory("原创榜", "2884035")
                ),
                comment = "基于开源仓库 injahow/meting-api，网易云热歌榜单备用源。"
            ),
            MusicSource(
                id = "git_gdstudio_netease",
                name = "GD音乐台 · 网易云备用 (GitHub/GDStudio)",
                baseUrl = "https://music-api.gdstudio.xyz/api.php",
                sourceType = MusicSourceType.GDSTUDIO_API,
                platform = "netease",
                group = "开源聚合曲库 (备用)",
                charts = listOf(
                    MusicChartCategory("华语热歌", "热门"),
                    MusicChartCategory("治愈轻音", "轻音乐"),
                    MusicChartCategory("国风古韵", "古风"),
                    MusicChartCategory("影视原声", "OST")
                ),
                comment = "基于 GDStudio 开源音乐聚合接口的网易云备用线路。"
            ),
            MusicSource(
                id = "open_audius_global",
                name = "Audius 全球独立音乐 (开源曲库)",
                baseUrl = "https://discoveryprovider.audius.co/v1",
                sourceType = MusicSourceType.AUDIUS_API,
                platform = "global",
                group = "欧美/电子/独立音乐",
                charts = listOf(
                    MusicChartCategory("全球热榜", "trending"),
                    MusicChartCategory("Electronic 电子", "Electronic"),
                    MusicChartCategory("Lo-Fi 专注", "Lo-Fi"),
                    MusicChartCategory("Pop 流行", "Pop")
                ),
                comment = "Audius 开源去中心化音乐网络，全曲无损直连播放。"
            ),
            MusicSource(
                id = "open_radiobrowser_fm",
                name = "Radio-Browser 在线音乐电台 (开源 FM)",
                baseUrl = "https://de1.api.radio-browser.info/json",
                sourceType = MusicSourceType.RADIO_BROWSER,
                platform = "cn",
                group = "24h 在线电台",
                charts = listOf(
                    MusicChartCategory("华语音乐台", "cn"),
                    MusicChartCategory("全球人气电台", "top"),
                    MusicChartCategory("爵士与蓝调", "jazz"),
                    MusicChartCategory("Lo-Fi 学习助眠", "lofi")
                ),
                comment = "开源社区维护的全球 30,000+ 实时在线音乐广播电台。"
            )
        )

        /**
         * 预设 GitHub / Gitee 开源音乐源订阅（与影视、小说订阅完全隔离）
         */
        fun defaultMusicSubscriptions(): List<MusicSubscription> = listOf(
            MusicSubscription(
                id = "msub_huibq_mf",
                name = "Huibq MusicFree 聚合源 (CDN 加速)",
                url = "https://fastly.jsdelivr.net/gh/Huibq/keep-alive/Music_Free/myPlugins.json",
                repoHint = "github.com/Huibq/keep-alive"
            ),
            MusicSubscription(
                id = "msub_maotoumao_gitee",
                name = "MusicFree 官方插件源 (Gitee 镜像)",
                url = "https://gitee.com/maotoumao/MusicFreePlugins/raw/master/plugins.json",
                repoHint = "gitee.com/maotoumao/MusicFreePlugins"
            ),
            MusicSubscription(
                id = "msub_cinecast_template",
                name = "CineCast 精选开源音乐源模板",
                url = "https://fastly.jsdelivr.net/gh/yxchen61/CineCast@main/docs/%E7%A4%BA%E4%BE%8B%E9%9F%B3%E4%B9%90%E6%BA%90%E9%85%8D%E7%BD%AE_CineCast%E6%A8%A1%E6%9D%BF.json",
                repoHint = "github.com/yxchen61/CineCast"
            )
        )
    }
}
