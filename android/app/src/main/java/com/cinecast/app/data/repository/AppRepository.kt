package com.cinecast.app.data.repository

import android.content.Context
import com.cinecast.app.data.model.*
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

class AppRepository(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val mutex = Mutex()

    private val sourcesFile get() = File(context.filesDir, "cinecast_sources.json")
    private val subsFile get() = File(context.filesDir, "cinecast_subscriptions.json")
    private val livesFile get() = File(context.filesDir, "cinecast_lives.json")
    private val parsesFile get() = File(context.filesDir, "cinecast_parses.json")
    private val historyFile get() = File(context.filesDir, "cinecast_history.json")
    private val favoritesFile get() = File(context.filesDir, "cinecast_favorites.json")
    private val settingsFile get() = File(context.filesDir, "cinecast_settings.json")

    private val _sources = MutableStateFlow<List<VideoSource>>(emptyList())
    val sources: StateFlow<List<VideoSource>> = _sources.asStateFlow()

    private val _activeSourceId = MutableStateFlow("")
    val activeSourceId: StateFlow<String> = _activeSourceId.asStateFlow()

    private val _subscriptions = MutableStateFlow<List<SubscriptionConfig>>(emptyList())
    val subscriptions: StateFlow<List<SubscriptionConfig>> = _subscriptions.asStateFlow()

    private val _liveSources = MutableStateFlow<List<LiveSource>>(emptyList())
    val liveSources: StateFlow<List<LiveSource>> = _liveSources.asStateFlow()

    private val _parses = MutableStateFlow<List<ParseInterface>>(emptyList())
    val parses: StateFlow<List<ParseInterface>> = _parses.asStateFlow()

    private val _history = MutableStateFlow<List<WatchHistoryItem>>(emptyList())
    val history: StateFlow<List<WatchHistoryItem>> = _history.asStateFlow()

    private val _favorites = MutableStateFlow<List<FavoriteItem>>(emptyList())
    val favorites: StateFlow<List<FavoriteItem>> = _favorites.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        loadAllSync()
    }

    private fun loadAllSync() {
        val rawLoaded = readJson<List<VideoSource>>(sourcesFile)
        val defaultList = defaultSources()
        val loadedSources = if (rawLoaded == null) {
            defaultList
        } else {
            // Merge newly verified default sources (including v1.4.0 English Cartoon sources) & retire dead v1 URLs
            val deadUrls = setOf(
                "https://cj.ffzyapi.com/api.php/provide/vod/",
                "https://cj.ffzyapi.com/api.php/provide/vod/at/xml/",
                "https://bfzyapi.com/api.php/provide/vod/",
                "https://api.wujinapi.me/api.php/provide/vod/",
                "https://suoniapi.com/api.php/provide/vod/",
                "http://cj.ffzyapi.com/api.php/provide/vod/",
                "http://cj.ffzyapi.com/api.php/provide/vod/at/xml/"
            )
            val keptCustom = rawLoaded.filterNot { it.apiUrl in deadUrls }
            val existingUrls = keptCustom.map { it.apiUrl }.toSet()
            val missingDefaults = defaultList.filterNot { it.apiUrl in existingUrls }
            val merged = missingDefaults + keptCustom
            if (merged != rawLoaded) writeJsonSync(sourcesFile, merged)
            merged
        }
        _sources.value = loadedSources
        if (!sourcesFile.exists()) writeJsonSync(sourcesFile, loadedSources)

        val firstUsable = loadedSources.firstOrNull {
            it.enabled && it.type in setOf(SourceType.MACCMS_JSON, SourceType.MACCMS_XML, SourceType.T4_API)
        } ?: loadedSources.firstOrNull()
        _activeSourceId.value = firstUsable?.id.orEmpty()

        val loadedSubs = readJson<List<SubscriptionConfig>>(subsFile) ?: defaultSubscriptions()
        _subscriptions.value = loadedSubs
        if (!subsFile.exists()) writeJsonSync(subsFile, loadedSubs)

        val rawLives = readJson<List<LiveSource>>(livesFile)
        val loadedLives = if (rawLives == null || rawLives.all { it.url.contains("gh.927223.xyz") }) {
            defaultLiveSources().also { writeJsonSync(livesFile, it) }
        } else {
            val cleaned = rawLives.filterNot { it.url.contains("gh.927223.xyz") }
            if (cleaned.size != rawLives.size) {
                val merged = (defaultLiveSources() + cleaned).distinctBy { it.url }
                writeJsonSync(livesFile, merged)
                merged
            } else rawLives
        }
        _liveSources.value = loadedLives

        val loadedParses = readJson<List<ParseInterface>>(parsesFile) ?: defaultParses()
        _parses.value = loadedParses
        if (!parsesFile.exists()) writeJsonSync(parsesFile, loadedParses)

        _history.value = readJson<List<WatchHistoryItem>>(historyFile) ?: emptyList()
        _favorites.value = readJson<List<FavoriteItem>>(favoritesFile) ?: emptyList()
        _settings.value = readJson<AppSettings>(settingsFile) ?: AppSettings()
    }

    fun setActiveSource(sourceId: String) {
        _activeSourceId.value = sourceId
    }

    fun getSourceById(sourceId: String): VideoSource? =
        _sources.value.firstOrNull { it.id == sourceId }

    suspend fun upsertSource(source: VideoSource) = withContext(Dispatchers.IO) {
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
                _activeSourceId.value = list.firstOrNull {
                    it.enabled && it.type != SourceType.TVBOX_SPIDER && it.type != SourceType.WEB_SNIFFER
                }?.id ?: list.firstOrNull()?.id.orEmpty()
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
        sub: SubscriptionConfig,
        newSources: List<VideoSource>,
        newLives: List<LiveSource>,
        newParses: List<ParseInterface>,
        newAdHosts: List<String>
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Merge subscriptions
            val subs = _subscriptions.value.toMutableList()
            val sIdx = subs.indexOfFirst { it.url == sub.url || it.id == sub.id }
            if (sIdx >= 0) subs[sIdx] = sub else subs.add(0, sub)
            _subscriptions.value = subs
            writeJsonSync(subsFile, subs)

            // Merge video sources (replace existing entries from the same subscriptionUrl)
            val keptSources = _sources.value.filterNot { it.subscriptionUrl == sub.url && sub.url.isNotBlank() }
            val existingUrls = keptSources.map { it.apiUrl }.toSet()
            val dedupedNew = newSources.filterNot { it.apiUrl in existingUrls }
            val mergedSources = keptSources + dedupedNew
            _sources.value = mergedSources
            writeJsonSync(sourcesFile, mergedSources)

            // Merge live sources
            val existingLiveUrls = _liveSources.value.map { it.url }.toSet()
            val mergedLives = _liveSources.value + newLives.filterNot { it.url in existingLiveUrls }
            _liveSources.value = mergedLives
            writeJsonSync(livesFile, mergedLives)

            // Merge parses
            val existingParseUrls = _parses.value.map { it.url }.toSet()
            val mergedParses = _parses.value + newParses.filterNot { it.url in existingParseUrls }
            _parses.value = mergedParses
            writeJsonSync(parsesFile, mergedParses)

            // Merge ad block hosts
            if (newAdHosts.isNotEmpty()) {
                val cur = _settings.value
                val updated = cur.copy(adBlockHosts = (cur.adBlockHosts + newAdHosts).distinct())
                _settings.value = updated
                writeJsonSync(settingsFile, updated)
            }
        }
    }

    suspend fun addLiveSource(live: LiveSource) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _liveSources.value.toMutableList()
            val idx = list.indexOfFirst { it.id == live.id || it.url == live.url }
            if (idx >= 0) list[idx] = live else list.add(0, live)
            _liveSources.value = list
            writeJsonSync(livesFile, list)
        }
    }

    suspend fun deleteLiveSource(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _liveSources.value.filterNot { it.id == id }
            _liveSources.value = list
            writeJsonSync(livesFile, list)
        }
    }

    suspend fun saveHistory(item: WatchHistoryItem) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _history.value.filterNot {
                it.vodId == item.vodId && it.sourceId == item.sourceId
            }.toMutableList()
            list.add(0, item.copy(updatedAt = System.currentTimeMillis()))
            val trimmed = list.take(120)
            _history.value = trimmed
            writeJsonSync(historyFile, trimmed)
        }
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _history.value = emptyList()
            writeJsonSync(historyFile, emptyList<WatchHistoryItem>())
        }
    }

    fun getHistoryItem(sourceId: String, vodId: String): WatchHistoryItem? =
        _history.value.firstOrNull { it.sourceId == sourceId && it.vodId == vodId }

    suspend fun toggleFavorite(item: FavoriteItem): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val exists = _favorites.value.any { it.vodId == item.vodId && it.sourceId == item.sourceId }
            val updated = if (exists) {
                _favorites.value.filterNot { it.vodId == item.vodId && it.sourceId == item.sourceId }
            } else {
                listOf(item.copy(addedAt = System.currentTimeMillis())) + _favorites.value
            }
            _favorites.value = updated
            writeJsonSync(favoritesFile, updated)
            !exists
        }
    }

    fun isFavorite(sourceId: String, vodId: String): Boolean =
        _favorites.value.any { it.vodId == vodId && it.sourceId == sourceId }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = transform(_settings.value)
            _settings.value = updated
            writeJsonSync(settingsFile, updated)
        }
    }

    fun exportBackupJson(): String {
        val bundle = BackupBundle(
            sources = _sources.value,
            subscriptions = _subscriptions.value,
            liveSources = _liveSources.value,
            parses = _parses.value,
            settings = _settings.value
        )
        return json.encodeToString(bundle)
    }

    suspend fun importBackupJson(rawJson: String) = withContext(Dispatchers.IO) {
        val bundle = json.decodeFromString<BackupBundle>(rawJson)
        mutex.withLock {
            if (bundle.sources.isNotEmpty()) {
                _sources.value = bundle.sources
                writeJsonSync(sourcesFile, bundle.sources)
            }
            if (bundle.subscriptions.isNotEmpty()) {
                _subscriptions.value = bundle.subscriptions
                writeJsonSync(subsFile, bundle.subscriptions)
            }
            if (bundle.liveSources.isNotEmpty()) {
                _liveSources.value = bundle.liveSources
                writeJsonSync(livesFile, bundle.liveSources)
            }
            if (bundle.parses.isNotEmpty()) {
                _parses.value = bundle.parses
                writeJsonSync(parsesFile, bundle.parses)
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
        fun defaultSources(): List<VideoSource> = listOf(
            VideoSource(
                id = "jszy_json",
                name = "极速资源 (高清秒播)",
                type = SourceType.MACCMS_JSON,
                apiUrl = "https://jszyapi.com/api.php/provide/vod/",
                group = "内置精选 (真机实测直连)"
            ),
            VideoSource(
                id = "huya_json",
                name = "虎牙资源 (高清直链)",
                type = SourceType.MACCMS_JSON,
                apiUrl = "https://www.huyaapi.com/api.php/provide/vod/",
                group = "内置精选 (真机实测直连)"
            ),
            VideoSource(
                id = "rycj_json",
                name = "如意资源 (高清直链)",
                type = SourceType.MACCMS_JSON,
                apiUrl = "https://cj.rycjapi.com/api.php/provide/vod/",
                group = "内置精选 (真机实测直连)"
            ),
            VideoSource(
                id = "zy360_json",
                name = "360资源 (高清M3U8)",
                type = SourceType.MACCMS_JSON,
                apiUrl = "https://360zy.com/api.php/provide/vod/",
                group = "内置精选 (真机实测直连)"
            ),
            VideoSource(
                id = "hongniu_json",
                name = "红牛资源 (高清直链)",
                type = SourceType.MACCMS_JSON,
                apiUrl = "https://www.hongniuzy2.com/api.php/provide/vod/",
                group = "内置精选 (真机实测直连)"
            ),
            VideoSource(
                id = "ikun_json",
                name = "iKun资源 (高清直链)",
                type = SourceType.MACCMS_JSON,
                apiUrl = "https://ikunzyapi.com/api.php/provide/vod/",
                group = "内置精选 (真机实测直连)"
            ),
            VideoSource(
                id = "xinlang_json",
                name = "新浪资源 (高清直链)",
                type = SourceType.MACCMS_JSON,
                apiUrl = "https://api.xinlangapi.com/xinlangapi.php/provide/vod/",
                group = "内置精选 (真机实测直连)"
            ),
            VideoSource(
                id = "modu_json",
                name = "魔都资源 (动漫影视)",
                type = SourceType.MACCMS_JSON,
                apiUrl = "https://caiji.moduapi.cc/api.php/provide/vod/",
                group = "内置精选 (真机实测直连)"
            ),
            VideoSource(
                id = "maoyan_json",
                name = "猫眼资源 (高清直链)",
                type = SourceType.MACCMS_JSON,
                apiUrl = "https://api.maoyanapi.top/api.php/provide/vod/",
                group = "内置精选 (真机实测直连)"
            ),
            // v1.4.0 新增：实测高速直连且含丰富英文动画/欧美少儿动画资源的视频源
            VideoSource(
                id = "subo_en_cartoon",
                name = "速播动漫 (英文动画/欧美动漫)",
                type = SourceType.MACCMS_JSON,
                apiUrl = "https://subocaiji.com/api.php/provide/vod/",
                group = "英文动画 / 欧美动漫精选"
            ),
            VideoSource(
                id = "guangsu_en_cartoon",
                name = "光速动漫 (英文版/迪士尼动画)",
                type = SourceType.MACCMS_JSON,
                apiUrl = "https://api.guangsuapi.com/api.php/provide/vod/",
                group = "英文动画 / 欧美动漫精选"
            ),
            VideoSource(
                id = "huohu_en_cartoon",
                name = "火狐动漫 (英文动画/海绵宝宝/布鲁伊)",
                type = SourceType.MACCMS_JSON,
                apiUrl = "https://hhzyapi.com/api.php/provide/vod/",
                group = "英文动画 / 欧美动漫精选"
            ),
            VideoSource(
                id = "dytt_en_cartoon",
                name = "电影天堂动漫 (迪士尼/汪汪队/欧美动漫)",
                type = SourceType.MACCMS_JSON,
                apiUrl = "https://caiji.dyttzyapi.com/api.php/provide/vod/",
                group = "英文动画 / 欧美动漫精选"
            ),
            VideoSource(
                id = "zy1080_en_cartoon",
                name = "优质1080动画 (欧美少儿/英语动画)",
                type = SourceType.MACCMS_JSON,
                apiUrl = "https://api.1080zyku.com/inc/apijson.php",
                group = "英文动画 / 欧美动漫精选"
            ),
            VideoSource(
                id = "web_bili_en_cartoon",
                name = "B站英文动画专区 (网页嗅探)",
                type = SourceType.WEB_SNIFFER,
                apiUrl = "https://m.bilibili.com/search?keyword=%E8%8B%B1%E6%96%87%E5%8A%A8%E7%94%BB%E7%89%87",
                group = "英文动画 / 欧美动漫精选"
            ),
            VideoSource(
                id = "web_pbs_kids",
                name = "PBS Kids 英语原版动画 (网页嗅探)",
                type = SourceType.WEB_SNIFFER,
                apiUrl = "https://pbskids.org/video/",
                group = "英文动画 / 欧美动漫精选"
            ),
            VideoSource(
                id = "web_bilibili",
                name = "哔哩哔哩 (网页嗅探)",
                type = SourceType.WEB_SNIFFER,
                apiUrl = "https://m.bilibili.com/",
                group = "网页嗅探站点"
            ),
            VideoSource(
                id = "web_qq",
                name = "腾讯视频 (解析/嗅探)",
                type = SourceType.WEB_SNIFFER,
                apiUrl = "https://m.v.qq.com/",
                group = "网页嗅探站点"
            ),
            VideoSource(
                id = "web_iqiyi",
                name = "爱奇艺 (解析/嗅探)",
                type = SourceType.WEB_SNIFFER,
                apiUrl = "https://m.iqiyi.com/",
                group = "网页嗅探站点"
            )
        )

        fun defaultSubscriptions(): List<SubscriptionConfig> = listOf(
            SubscriptionConfig(
                id = "sub_cyao_m",
                name = "cyao2q/files 主配置 (m.json)",
                url = "https://cyao2q.github.io/files/m.json"
            ),
            SubscriptionConfig(
                id = "sub_cyao_xs",
                name = "cyao2q/files 扩展配置 (xs.json)",
                url = "https://cyao2q.github.io/files/xs.json"
            )
        )

        fun defaultLiveSources(): List<LiveSource> = listOf(
            LiveSource(
                id = "live_zbds_v4",
                name = "精选 IPv4 央视卫视 (实时校验)",
                url = "https://live.zbds.top/tv/iptv4.m3u",
                epg = "http://epg.51zmt.top:8000/e.xml"
            ),
            LiveSource(
                id = "live_guovin_fastly",
                name = "全国高清直播 (Fastly 加速)",
                url = "https://fastly.jsdelivr.net/gh/Guovin/iptv-api@gd/output/result.m3u"
            ),
            LiveSource(
                id = "live_suxuang_v4",
                name = "4K/高清 IPv4 频道 (Fastly)",
                url = "https://fastly.jsdelivr.net/gh/suxuang/myIPTV@main/ipv4.m3u",
                epg = "http://epg.51zmt.top:8000/e.xml"
            ),
            LiveSource(
                id = "live_guovin_gcore",
                name = "全国高清直播 (Gcore 备用)",
                url = "https://gcore.jsdelivr.net/gh/Guovin/iptv-api@gd/output/result.m3u"
            )
        )

        fun defaultParses(): List<ParseInterface> = listOf(
            ParseInterface("xmflv 解析", 0, "https://jx.xmflv.com/?url="),
            ParseInterface("pouyun 解析", 0, "https://www.pouyun.com/?url="),
            ParseInterface("xymp4 解析", 0, "https://jx.xymp4.cc/?url="),
            ParseInterface("yparse 解析", 0, "https://jx.yparse.com/index.php?url="),
            ParseInterface("8090g 解析", 0, "https://www.8090g.cn/?url=")
        )
    }
}
