package com.cinecast.app.novel.repository

import android.content.Context
import com.cinecast.app.novel.model.*
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
 * 独立小说模块数据仓库
 * 所有书源、书源订阅、书架与阅读器偏好均存储在独立 JSON 文件中，与视频模块完全隔离。
 */
class NovelRepository(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val mutex = Mutex()

    private val sourcesFile get() = File(context.filesDir, "cinecast_novel_sources.json")
    private val subsFile get() = File(context.filesDir, "cinecast_novel_subscriptions.json")
    private val bookshelfFile get() = File(context.filesDir, "cinecast_novel_bookshelf.json")
    private val pingshuHistoryFile get() = File(context.filesDir, "cinecast_pingshu_history.json")
    private val settingsFile get() = File(context.filesDir, "cinecast_novel_reading_settings.json")

    private val _sources = MutableStateFlow<List<NovelSource>>(emptyList())
    val sources: StateFlow<List<NovelSource>> = _sources.asStateFlow()

    private val _activeSourceId = MutableStateFlow("")
    val activeSourceId: StateFlow<String> = _activeSourceId.asStateFlow()

    private val _activePingshuSourceId = MutableStateFlow("")
    val activePingshuSourceId: StateFlow<String> = _activePingshuSourceId.asStateFlow()

    private val _subscriptions = MutableStateFlow<List<NovelSubscription>>(emptyList())
    val subscriptions: StateFlow<List<NovelSubscription>> = _subscriptions.asStateFlow()

    private val _bookshelf = MutableStateFlow<List<BookshelfItem>>(emptyList())
    val bookshelf: StateFlow<List<BookshelfItem>> = _bookshelf.asStateFlow()

    private val _pingshuHistory = MutableStateFlow<List<PingshuHistoryItem>>(emptyList())
    val pingshuHistory: StateFlow<List<PingshuHistoryItem>> = _pingshuHistory.asStateFlow()

    private val _readingSettings = MutableStateFlow(NovelReadingSettings())
    val readingSettings: StateFlow<NovelReadingSettings> = _readingSettings.asStateFlow()

    init {
        loadAllSync()
    }

    private fun loadAllSync() {
        val rawLoaded = readJson<List<NovelSource>>(sourcesFile)
        val defaults = defaultNovelSources()
        val loadedSources = if (rawLoaded == null) {
            defaults.also { writeJsonSync(sourcesFile, it) }
        } else {
            // v1.4.0 自动迁移：注入高可用维基文库名著馆 & 酷我历史名著馆，移除失效 502 源，并默认关闭易失效的网页规则源
            val isPreV140 = rawLoaded.none { it.id == "open_wikisource_classics" }
            val deadIds = setOf("git_liufuyou_aishang")
            val flakyLegacyIds = setOf(
                "git_xiu2_sudugu",
                "git_liufuyou_qbtr",
                "git_liufuyou_esjzone",
                "git_xiu2_shoujix",
                "git_liufuyou_tingroom"
            )
            val cleaned = rawLoaded.filterNot { it.id in deadIds }.map { src ->
                if (isPreV140 && src.id in flakyLegacyIds) {
                    src.copy(enabled = false, group = "备用网页规则源 (按需手动开启)")
                } else src
            }
            val existingIds = cleaned.map { it.id }.toSet()
            val newReliable = defaults.filter { it.id !in existingIds && it.enabled }
            val merged = newReliable + cleaned
            if (merged != rawLoaded) writeJsonSync(sourcesFile, merged)
            merged
        }
        _sources.value = loadedSources
        _activeSourceId.value = loadedSources.firstOrNull { it.enabled && !it.sourceType.isAudioPingshu }?.id
            ?: loadedSources.firstOrNull { !it.sourceType.isAudioPingshu }?.id.orEmpty()
        _activePingshuSourceId.value = loadedSources.firstOrNull { it.enabled && it.sourceType.isAudioPingshu }?.id
            ?: loadedSources.firstOrNull { it.sourceType.isAudioPingshu }?.id.orEmpty()

        val loadedSubs = readJson<List<NovelSubscription>>(subsFile) ?: defaultNovelSubscriptions().also {
            writeJsonSync(subsFile, it)
        }
        _subscriptions.value = loadedSubs

        _bookshelf.value = readJson<List<BookshelfItem>>(bookshelfFile) ?: emptyList()
        _pingshuHistory.value = readJson<List<PingshuHistoryItem>>(pingshuHistoryFile) ?: emptyList()
        _readingSettings.value = readJson<NovelReadingSettings>(settingsFile) ?: NovelReadingSettings()
    }

    fun setActiveSource(sourceId: String) {
        _activeSourceId.value = sourceId
    }

    fun setActivePingshuSource(sourceId: String) {
        _activePingshuSourceId.value = sourceId
    }

    fun getPingshuHistory(sourceId: String, bookUrl: String): PingshuHistoryItem? =
        _pingshuHistory.value.firstOrNull { it.bookKey == "$sourceId::$bookUrl" }

    suspend fun savePingshuProgress(item: PingshuHistoryItem) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = (listOf(item.copy(lastListenedAt = System.currentTimeMillis())) +
                _pingshuHistory.value.filterNot { it.bookKey == item.bookKey }).take(40)
            _pingshuHistory.value = updated
            writeJsonSync(pingshuHistoryFile, updated)
        }
    }

    fun getSourceById(sourceId: String): NovelSource? =
        _sources.value.firstOrNull { it.id == sourceId }

    suspend fun upsertSource(source: NovelSource) = withContext(Dispatchers.IO) {
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
        sub: NovelSubscription,
        newSources: List<NovelSource>
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val subs = _subscriptions.value.toMutableList()
            val sIdx = subs.indexOfFirst { it.url == sub.url || it.id == sub.id }
            if (sIdx >= 0) subs[sIdx] = sub else subs.add(0, sub)
            _subscriptions.value = subs
            writeJsonSync(subsFile, subs)

            val kept = _sources.value.filterNot { it.subscriptionUrl == sub.url && sub.url.isNotBlank() }
            val existingKeys = kept.map { "${it.baseUrl}|${it.name}" }.toSet()
            val deduped = newSources.filterNot { "${it.baseUrl}|${it.name}" in existingKeys }
            val merged = kept + deduped
            _sources.value = merged
            writeJsonSync(sourcesFile, merged)
        }
    }

    // ==================== Bookshelf & Progress ====================

    fun isInBookshelf(sourceId: String, bookUrl: String): Boolean {
        val key = "$sourceId::$bookUrl"
        return _bookshelf.value.any { it.bookKey == key }
    }

    fun getBookshelfItem(sourceId: String, bookUrl: String): BookshelfItem? {
        val key = "$sourceId::$bookUrl"
        return _bookshelf.value.firstOrNull { it.bookKey == key }
    }

    suspend fun addToBookshelf(
        book: NovelBook,
        chapterIndex: Int = 0,
        chapterTitle: String = "",
        totalChapters: Int = 0
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val key = "${book.sourceId}::${book.bookUrl}"
            val existing = _bookshelf.value.firstOrNull { it.bookKey == key }
            val item = BookshelfItem(
                bookKey = key,
                sourceId = book.sourceId,
                sourceName = book.sourceName,
                title = book.title,
                author = book.author,
                coverUrl = book.coverUrl,
                intro = book.intro,
                kind = book.kind,
                latestChapter = book.latestChapter,
                bookUrl = book.bookUrl,
                tocUrl = book.tocUrl,
                currentChapterIndex = existing?.currentChapterIndex ?: chapterIndex,
                currentChapterTitle = existing?.currentChapterTitle?.ifBlank { chapterTitle } ?: chapterTitle,
                totalChapters = totalChapters.takeIf { it > 0 } ?: (existing?.totalChapters ?: 0),
                scrollOffset = existing?.scrollOffset ?: 0,
                addedAt = existing?.addedAt ?: System.currentTimeMillis(),
                lastReadAt = System.currentTimeMillis()
            )
            val updated = listOf(item) + _bookshelf.value.filterNot { it.bookKey == key }
            _bookshelf.value = updated
            writeJsonSync(bookshelfFile, updated)
        }
    }

    suspend fun removeFromBookshelf(bookKey: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = _bookshelf.value.filterNot { it.bookKey == bookKey }
            _bookshelf.value = updated
            writeJsonSync(bookshelfFile, updated)
        }
    }

    suspend fun saveReadingProgress(
        book: NovelBook,
        chapterIndex: Int,
        chapterTitle: String,
        totalChapters: Int,
        scrollOffset: Int = 0
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val key = "${book.sourceId}::${book.bookUrl}"
            val existing = _bookshelf.value.firstOrNull { it.bookKey == key }
            if (existing != null) {
                val updatedItem = existing.copy(
                    currentChapterIndex = chapterIndex,
                    currentChapterTitle = chapterTitle,
                    totalChapters = totalChapters.takeIf { it > 0 } ?: existing.totalChapters,
                    scrollOffset = scrollOffset,
                    lastReadAt = System.currentTimeMillis()
                )
                val updated = listOf(updatedItem) + _bookshelf.value.filterNot { it.bookKey == key }
                _bookshelf.value = updated
                writeJsonSync(bookshelfFile, updated)
            }
        }
    }

    suspend fun updateReadingSettings(transform: (NovelReadingSettings) -> NovelReadingSettings) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val updated = transform(_readingSettings.value)
                _readingSettings.value = updated
                writeJsonSync(settingsFile, updated)
            }
        }

    // ==================== Independent Novel Backup ====================

    fun exportNovelBackupJson(): String {
        val bundle = NovelBackupBundle(
            novelSources = _sources.value,
            novelSubscriptions = _subscriptions.value,
            bookshelf = _bookshelf.value,
            readingSettings = _readingSettings.value
        )
        return json.encodeToString(bundle)
    }

    suspend fun importNovelBackupJson(rawJson: String) = withContext(Dispatchers.IO) {
        val bundle = json.decodeFromString<NovelBackupBundle>(rawJson)
        mutex.withLock {
            if (bundle.novelSources.isNotEmpty()) {
                _sources.value = bundle.novelSources
                writeJsonSync(sourcesFile, bundle.novelSources)
            }
            if (bundle.novelSubscriptions.isNotEmpty()) {
                _subscriptions.value = bundle.novelSubscriptions
                writeJsonSync(subsFile, bundle.novelSubscriptions)
            }
            if (bundle.bookshelf.isNotEmpty()) {
                _bookshelf.value = bundle.bookshelf
                writeJsonSync(bookshelfFile, bundle.bookshelf)
            }
            _readingSettings.value = bundle.readingSettings
            writeJsonSync(settingsFile, bundle.readingSettings)
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
         * v1.4.0 高可用名著与开源书源精选：
         * 默认仅启用 100% 实测可读的公版名著 API（中文维基文库 / 古登堡）与纯 JSON API（酷我书城/历史名著馆），
         * 将易受改版或反爬影响的传统 HTML 网页规则源设为默认关闭的备选项，彻底解决「很多小说点开看不了」问题。
         */
        fun defaultNovelSources(): List<NovelSource> = listOf(
            // 1. 中文维基文库 · 古典与现代名著典藏 —— MediaWiki 官方开放 API，全自动转简体，零反爬 100% 可读
            NovelSource(
                id = "open_wikisource_classics",
                name = "维基文库 · 中文名著典藏 (100%可读)",
                baseUrl = "https://zh.wikisource.org",
                group = "名著典藏 (永久直连)",
                sourceType = NovelSourceType.WIKISOURCE_API,
                searchUrl = "https://zh.wikisource.org/w/api.php",
                comment = "内置四大名著、古典演义、鲁迅全集、史记兵法、唐诗宋词等 40+ 部全本名著，并支持实时检索维基文库全库，正文自动转换为简体中文。"
            ),
            // 2. Gutendex 古登堡开源名著库 —— 444 部中文古典 + 7 万部世界文学名著
            NovelSource(
                id = "open_gutendex_classics",
                name = "古登堡世界名著 (中英公版经典)",
                baseUrl = "https://gutendex.com",
                group = "名著典藏 (永久直连)",
                sourceType = NovelSourceType.GUTENDEX_API,
                searchUrl = "https://gutendex.com/books/?search={{key}}&page={{page}}",
                comment = "Project Gutenberg / Gutendex 开源公版图书馆，包含《西游记》《三国演义》《福尔摩斯》《傲慢与偏见》等 7 万部中英文经典名著。"
            ),
            // 3. 酷我小说 JSON API —— 来自 GitHub XIU2/Yuedu，全链路实测直连秒开
            NovelSource(
                id = "git_xiu2_kuwo",
                name = "酷我小说书城 (API 极速直连)",
                baseUrl = "http://appi.kuwo.cn",
                group = "精选直连 (GitHub API)",
                sourceType = NovelSourceType.LEGADO_JSON,
                searchUrl = "/novels/api/book/search?keyword={{key}}&pi={{page}}&ps=30",
                exploreUrl = listOf(
                    "历史演义::/novels/api/book/search?keyword=三国&pi={{page}}&ps=20",
                    "古典红楼::/novels/api/book/search?keyword=红楼&pi={{page}}&ps=20",
                    "明清风云::/novels/api/book/search?keyword=明朝&pi={{page}}&ps=20",
                    "热门玄幻::/novels/api/book/search?keyword=斗罗&pi={{page}}&ps=20",
                    "修仙仙侠::/novels/api/book/search?keyword=修仙&pi={{page}}&ps=20",
                    "都市异能::/novels/api/book/search?keyword=都市&pi={{page}}&ps=20",
                    "科幻末世::/novels/api/book/search?keyword=星际&pi={{page}}&ps=20",
                    "悬疑奇幻::/novels/api/book/search?keyword=诡秘&pi={{page}}&ps=20"
                ).joinToString("\n"),
                headers = mapOf(
                    "User-Agent" to "Dalvik/2.1.0 (Linux; U; Android 12; Pixel 7)"
                ),
                comment = "导入自开源仓库 XIU2/Yuedu，纯 JSON API 直连，章节加载稳定快速，无广告无验证码。",
                subscriptionUrl = "https://fastly.jsdelivr.net/gh/XIU2/Yuedu@master/shuyuan",
                ruleSearch = NovelRuleSearch(
                    bookList = "$.data",
                    name = "$.title",
                    author = "$.author_name",
                    bookUrl = "/novels/api/book/{{$.book_id}}",
                    coverUrl = "$.cover_url",
                    intro = "$.intro",
                    kind = "$.category_name",
                    wordCount = "$.all_words"
                ),
                ruleBookInfo = NovelRuleBookInfo(
                    init = "$.data",
                    name = "$.title",
                    author = "$.author_name",
                    coverUrl = "$.cover_url",
                    intro = "$.intro",
                    kind = "$.category_name",
                    lastChapter = "$.new_chapter_name",
                    tocUrl = "/novels/api/book/{{$.book_id}}/chapters?pi=1&ps=2000"
                ),
                ruleToc = NovelRuleToc(
                    chapterList = "$.data",
                    chapterName = "$.chapter_title",
                    chapterUrl = "/novels/api/book/{{$.book_id}}/chapters/{{$.chapter_id}}"
                ),
                ruleContent = NovelRuleContent(
                    content = "$.data.content"
                )
            ),
            // 4. 酷我历史文学与名著精选专题（同一高可用纯 JSON API，专注文学/历史/武侠/推理）
            NovelSource(
                id = "git_xiu2_kuwo_classics",
                name = "酷我历史与文学馆 (API 直连)",
                baseUrl = "http://appi.kuwo.cn",
                group = "名著典藏 (永久直连)",
                sourceType = NovelSourceType.LEGADO_JSON,
                searchUrl = "/novels/api/book/search?keyword={{key}}&pi={{page}}&ps=30",
                exploreUrl = listOf(
                    "三国两晋::/novels/api/book/search?keyword=三国&pi={{page}}&ps=20",
                    "大明风华::/novels/api/book/search?keyword=明朝&pi={{page}}&ps=20",
                    "盛唐气象::/novels/api/book/search?keyword=大唐&pi={{page}}&ps=20",
                    "大宋风云::/novels/api/book/search?keyword=大宋&pi={{page}}&ps=20",
                    "民国往事::/novels/api/book/search?keyword=民国&pi={{page}}&ps=20",
                    "传统武侠::/novels/api/book/search?keyword=武侠&pi={{page}}&ps=20",
                    "推理探案::/novels/api/book/search?keyword=探案&pi={{page}}&ps=20"
                ).joinToString("\n"),
                headers = mapOf(
                    "User-Agent" to "Dalvik/2.1.0 (Linux; U; Android 12; Pixel 7)"
                ),
                comment = "基于酷我高速纯 JSON API 精选的历史、演义、武侠与探案书单，章节 100% 可读。",
                ruleSearch = NovelRuleSearch(
                    bookList = "$.data",
                    name = "$.title",
                    author = "$.author_name",
                    bookUrl = "/novels/api/book/{{$.book_id}}",
                    coverUrl = "$.cover_url",
                    intro = "$.intro",
                    kind = "$.category_name",
                    wordCount = "$.all_words"
                ),
                ruleBookInfo = NovelRuleBookInfo(
                    init = "$.data",
                    name = "$.title",
                    author = "$.author_name",
                    coverUrl = "$.cover_url",
                    intro = "$.intro",
                    kind = "$.category_name",
                    lastChapter = "$.new_chapter_name",
                    tocUrl = "/novels/api/book/{{$.book_id}}/chapters?pi=1&ps=2000"
                ),
                ruleToc = NovelRuleToc(
                    chapterList = "$.data",
                    chapterName = "$.chapter_title",
                    chapterUrl = "/novels/api/book/{{$.book_id}}/chapters/{{$.chapter_id}}"
                ),
                ruleContent = NovelRuleContent(
                    content = "$.data.content"
                )
            ),
            // 5. 喜马拉雅评书馆（v1.5.0 新增：免费高音质 M4A 评书直连，单田芳/历史/武侠/单口）
            NovelSource(
                id = "open_ximalaya_pingshu",
                name = "喜马拉雅评书馆 (免费高清音频)",
                baseUrl = "https://www.ximalaya.com",
                group = "听评书专区 (音频直连)",
                sourceType = NovelSourceType.XIMALAYA_PINGSHU,
                searchUrl = "https://www.ximalaya.com/revision/category/v2/albums",
                comment = "免费评书音频直连，涵盖单田芳《白眉大侠》《乱世枭雄》、郭德纲单口评书及 3000+ 免费评书专辑。"
            ),
            // 6. 酷我评书名家馆（v1.5.0 新增：MP3 直连全库搜评书，单田芳/连丽如/刘兰芳/袁阔成/郭德纲）
            NovelSource(
                id = "open_kuwo_pingshu",
                name = "酷我评书名家馆 (MP3极速直连)",
                baseUrl = "http://search.kuwo.cn",
                group = "听评书专区 (音频直连)",
                sourceType = NovelSourceType.KUWO_PINGSHU,
                searchUrl = "http://search.kuwo.cn/r.s",
                comment = "支持全库检索任意评书名家与作品（单田芳隋唐演义142回、连丽如雍正剑侠图378回、刘兰芳大唐侠女等），MP3 直连秒播。"
            ),
            // 以下网页规则源默认关闭（避免部分站点反爬或失效导致点开看不了），用户可在「书源」页按需开启
            NovelSource(
                id = "git_xiu2_sudugu",
                name = "速读谷 (备用规则源)",
                baseUrl = "https://www.sudugu.co",
                group = "备用网页规则源 (按需手动开启)",
                sourceType = NovelSourceType.LEGADO_HTML,
                enabled = false,
                searchUrl = "/modules/article/search.php,{\"method\":\"POST\",\"body\":\"searchkey={{key}}&action=login&searchtype=all&submit=\"}",
                exploreUrl = listOf(
                    "最近更新::/top/lastupdate/{{page}}.html",
                    "完本排行::/quanben/{{page}}.html",
                    "玄幻小说::/fenlei/1/{{page}}/",
                    "仙侠小说::/fenlei/2/{{page}}/",
                    "都市小说::/fenlei/3/{{page}}/"
                ).joinToString("\n"),
                comment = "导入自开源仓库 XIU2/Yuedu，传统网页规则源，按需手动开启。",
                subscriptionUrl = "https://fastly.jsdelivr.net/gh/XIU2/Yuedu@master/shuyuan",
                ruleSearch = NovelRuleSearch(
                    bookList = "class.bookbox||class.item",
                    name = "tag.h4.0@tag.a.0@text||tag.h3.0@tag.a.0@text||tag.a.0@text",
                    author = "class.author.0@text##作者：",
                    bookUrl = "tag.a.0@href",
                    coverUrl = "tag.img.0@src",
                    intro = "class.update.0@textNodes||class.des.0@text"
                ),
                ruleBookInfo = NovelRuleBookInfo(
                    name = "tag.img.0@alt||tag.h1.0@text",
                    author = "class.itemtxt.0@tag.p.1@tag.a.0@text##作者：",
                    coverUrl = "tag.img.0@src",
                    intro = "class.des.0@html",
                    kind = "class.itemtxt.0@tag.p.0@tag.span@text",
                    lastChapter = "class.itemtxt.0@tag.li.0@tag.a.0@text"
                ),
                ruleToc = NovelRuleToc(
                    chapterList = "id.list.0@tag.a",
                    chapterName = "text",
                    chapterUrl = "href"
                ),
                ruleContent = NovelRuleContent(
                    content = "class.con.0@html",
                    nextContentUrl = "//div[@class='prenext']/span/a[text()='下一页']/@href",
                    replaceRegex = "##[\\(（]本章完[\\)）]$"
                )
            ),
            NovelSource(
                id = "git_liufuyou_qbtr",
                name = "全本同人 (备用规则源)",
                baseUrl = "https://www.qbtr.me",
                group = "备用网页规则源 (按需手动开启)",
                sourceType = NovelSourceType.LEGADO_HTML,
                enabled = false,
                charset = "GB2312",
                searchUrl = "/e/search/index.php,{\"charset\":\"gb2312\",\"method\":\"POST\",\"body\":\"keyboard={{key}}&show=title&classid=0\"}",
                exploreUrl = listOf(
                    "同人小说::/tongren/<,index_{{page}}.html>",
                    "常规小说::/changgui/<,index_{{page}}.html>",
                    "同人榜单::/hot/<,index_{{page}}.html>"
                ).joinToString("\n"),
                comment = "导入自开源仓库 liufuyou/read，按需手动开启。",
                subscriptionUrl = "https://fastly.jsdelivr.net/gh/liufuyou/read@main/sources/yiove-novel.json",
                ruleSearch = NovelRuleSearch(
                    bookList = ".bk",
                    name = "h3@text##\\(\\S+",
                    author = ".booknews@ownText##.*：",
                    bookUrl = "a.0@href",
                    coverUrl = "img@src",
                    intro = "p@text##简介.",
                    kind = "label@text",
                    lastChapter = "h3@text##\\S+\\(|\\)"
                ),
                ruleBookInfo = NovelRuleBookInfo(
                    name = ".infos h1@text##\\(\\S+",
                    author = ".date span@text##.*：",
                    coverUrl = ".pic img@src",
                    intro = ".infos p@html",
                    kind = ".menNav a.1@text",
                    lastChapter = ".book_list a.-1@text"
                ),
                ruleToc = NovelRuleToc(
                    chapterList = ".book_list a",
                    chapterName = "text",
                    chapterUrl = "href"
                ),
                ruleContent = NovelRuleContent(
                    content = ".read_chapterDetail@html"
                )
            ),
            NovelSource(
                id = "git_liufuyou_tingroom",
                name = "英文原版名著 (Tingroom)",
                baseUrl = "http://novel.tingroom.com",
                group = "备用网页规则源 (按需手动开启)",
                sourceType = NovelSourceType.LEGADO_HTML,
                enabled = false,
                searchUrl = "http://novel.tingroom.com/wap/index.php?moduleid=1025&type=2&kw={{key}}&page={{page}}",
                exploreUrl = listOf(
                    "热门原著::http://novel.tingroom.com/wap/index.php?moduleid=1025&catid=1&page={{page}}",
                    "经典文学::http://novel.tingroom.com/wap/index.php?moduleid=1025&catid=2&page={{page}}"
                ).joinToString("\n"),
                comment = "导入自开源仓库 liufuyou/read，适合英文原著学习与阅读。",
                subscriptionUrl = "https://fastly.jsdelivr.net/gh/liufuyou/read@main/sources/yiove-novel.json",
                ruleSearch = NovelRuleSearch(
                    bookList = "class.list_1",
                    name = "class.name.0@text",
                    bookUrl = "class.name.0@tag.a.0@href##amp;",
                    coverUrl = "tag.img@src"
                ),
                ruleBookInfo = NovelRuleBookInfo(
                    name = "class.sm@text",
                    author = "class.writer.0@text##\\s+",
                    coverUrl = "class.f_l@tag.img@src",
                    intro = "id.infoShort@text"
                ),
                ruleToc = NovelRuleToc(
                    chapterList = "class.listtxt@tag.li",
                    chapterName = "text",
                    chapterUrl = "tag.a.0@href"
                ),
                ruleContent = NovelRuleContent(
                    content = "id.tt_text@html",
                    nextContentUrl = "text.下一页@href"
                )
            )
        )

        /**
         * 预设 GitHub 开源书源订阅仓库（与视频 TVBox 订阅完全隔离）
         */
        fun defaultNovelSubscriptions(): List<NovelSubscription> = listOf(
            NovelSubscription(
                id = "nsub_xiu2_fastly",
                name = "XIU2/Yuedu 精选书源 (CDN 加速)",
                url = "https://fastly.jsdelivr.net/gh/XIU2/Yuedu@master/shuyuan",
                repoHint = "github.com/XIU2/Yuedu"
            ),
            NovelSubscription(
                id = "nsub_liufuyou_yiove",
                name = "liufuyou/read Yiove 综合书源",
                url = "https://fastly.jsdelivr.net/gh/liufuyou/read@main/sources/yiove-novel.json",
                repoHint = "github.com/liufuyou/read"
            ),
            NovelSubscription(
                id = "nsub_liufuyou_yck",
                name = "liufuyou/read YCK 全量书源",
                url = "https://fastly.jsdelivr.net/gh/liufuyou/read@main/sources/yck-novel.json",
                repoHint = "github.com/liufuyou/read"
            ),
            NovelSubscription(
                id = "nsub_openrepos",
                name = "openrepos 通用阅读 3.0 书源",
                url = "https://fastly.jsdelivr.net/gh/openrepos/booksource@main/yuedu.json",
                repoHint = "github.com/openrepos/booksource"
            )
        )
    }
}
