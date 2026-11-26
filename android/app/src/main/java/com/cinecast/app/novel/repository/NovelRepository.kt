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
    private val settingsFile get() = File(context.filesDir, "cinecast_novel_reading_settings.json")

    private val _sources = MutableStateFlow<List<NovelSource>>(emptyList())
    val sources: StateFlow<List<NovelSource>> = _sources.asStateFlow()

    private val _activeSourceId = MutableStateFlow("")
    val activeSourceId: StateFlow<String> = _activeSourceId.asStateFlow()

    private val _subscriptions = MutableStateFlow<List<NovelSubscription>>(emptyList())
    val subscriptions: StateFlow<List<NovelSubscription>> = _subscriptions.asStateFlow()

    private val _bookshelf = MutableStateFlow<List<BookshelfItem>>(emptyList())
    val bookshelf: StateFlow<List<BookshelfItem>> = _bookshelf.asStateFlow()

    private val _readingSettings = MutableStateFlow(NovelReadingSettings())
    val readingSettings: StateFlow<NovelReadingSettings> = _readingSettings.asStateFlow()

    init {
        loadAllSync()
    }

    private fun loadAllSync() {
        val loadedSources = readJson<List<NovelSource>>(sourcesFile) ?: defaultNovelSources().also {
            writeJsonSync(sourcesFile, it)
        }
        _sources.value = loadedSources
        _activeSourceId.value = loadedSources.firstOrNull { it.enabled }?.id
            ?: loadedSources.firstOrNull()?.id.orEmpty()

        val loadedSubs = readJson<List<NovelSubscription>>(subsFile) ?: defaultNovelSubscriptions().also {
            writeJsonSync(subsFile, it)
        }
        _subscriptions.value = loadedSubs

        _bookshelf.value = readJson<List<BookshelfItem>>(bookshelfFile) ?: emptyList()
        _readingSettings.value = readJson<NovelReadingSettings>(settingsFile) ?: NovelReadingSettings()
    }

    fun setActiveSource(sourceId: String) {
        _activeSourceId.value = sourceId
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
         * 从 GitHub 开源书源仓库（XIU2/Yuedu、liufuyou/read）与公版名著 API 精选并实测验证的默认小说源
         */
        fun defaultNovelSources(): List<NovelSource> = listOf(
            // 1. 酷我小说 JSON API —— 来自 GitHub XIU2/Yuedu，全链路实测直连秒开
            NovelSource(
                id = "git_xiu2_kuwo",
                name = "酷我小说 (GitHub/XIU2 API)",
                baseUrl = "http://appi.kuwo.cn",
                group = "精选直连 (GitHub 实测)",
                sourceType = NovelSourceType.LEGADO_JSON,
                searchUrl = "/novels/api/book/search?keyword={{key}}&pi={{page}}&ps=30",
                exploreUrl = listOf(
                    "热门玄幻::/novels/api/book/search?keyword=斗罗&pi={{page}}&ps=20",
                    "修仙仙侠::/novels/api/book/search?keyword=修仙&pi={{page}}&ps=20",
                    "都市异能::/novels/api/book/search?keyword=都市&pi={{page}}&ps=20",
                    "历史穿越::/novels/api/book/search?keyword=大明&pi={{page}}&ps=20",
                    "科幻末世::/novels/api/book/search?keyword=星际&pi={{page}}&ps=20",
                    "悬疑奇幻::/novels/api/book/search?keyword=诡秘&pi={{page}}&ps=20",
                    "甜宠言情::/novels/api/book/search?keyword=总裁&pi={{page}}&ps=20"
                ).joinToString("\n"),
                headers = mapOf(
                    "User-Agent" to "Dalvik/2.1.0 (Linux; U; Android 12; Pixel 7)"
                ),
                comment = "导入自开源仓库 XIU2/Yuedu，纯 JSON API 直连，无广告无验证码。",
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
            // 2. Gutendex 公版开源名著库 —— 永久直连，支持 444 部中文古典名著与 7 万部世界文学
            NovelSource(
                id = "open_gutendex_classics",
                name = "古登堡开源名著 (中文/世界文学)",
                baseUrl = "https://gutendex.com",
                group = "精选直连 (开源公版)",
                sourceType = NovelSourceType.GUTENDEX_API,
                searchUrl = "https://gutendex.com/books/?search={{key}}&page={{page}}",
                comment = "Project Gutenberg / Gutendex 开源公版图书馆，包含《西游记》《三国演义》《红楼梦》《水浒传》等中英文经典。"
            ),
            // 3. 速读谷 —— 来自 GitHub XIU2/Yuedu
            NovelSource(
                id = "git_xiu2_sudugu",
                name = "速读谷 (GitHub/XIU2)",
                baseUrl = "https://www.sudugu.co",
                group = "精选直连 (GitHub 实测)",
                sourceType = NovelSourceType.LEGADO_HTML,
                searchUrl = "/modules/article/search.php,{\"method\":\"POST\",\"body\":\"searchkey={{key}}&action=login&searchtype=all&submit=\"}",
                exploreUrl = listOf(
                    "最近更新::/top/lastupdate/{{page}}.html",
                    "完本排行::/quanben/{{page}}.html",
                    "玄幻小说::/fenlei/1/{{page}}/",
                    "仙侠小说::/fenlei/2/{{page}}/",
                    "都市小说::/fenlei/3/{{page}}/",
                    "历史小说::/fenlei/4/{{page}}/",
                    "科幻小说::/fenlei/6/{{page}}/",
                    "言情小说::/fenlei/7/{{page}}/"
                ).joinToString("\n"),
                comment = "导入自开源仓库 XIU2/Yuedu，分类齐全。",
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
            // 4. 全本同人 —— 来自 GitHub liufuyou/read (Yiove)
            NovelSource(
                id = "git_liufuyou_qbtr",
                name = "全本同人 (GitHub/liufuyou)",
                baseUrl = "https://www.qbtr.me",
                group = "精选直连 (GitHub 实测)",
                sourceType = NovelSourceType.LEGADO_HTML,
                charset = "GB2312",
                searchUrl = "/e/search/index.php,{\"charset\":\"gb2312\",\"method\":\"POST\",\"body\":\"keyboard={{key}}&show=title&classid=0\"}",
                exploreUrl = listOf(
                    "同人小说::/tongren/<,index_{{page}}.html>",
                    "常规小说::/changgui/<,index_{{page}}.html>",
                    "同人榜单::/hot/<,index_{{page}}.html>"
                ).joinToString("\n"),
                comment = "导入自开源仓库 liufuyou/read，主打完本与同人精品小说。",
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
            // 5. ESJZone 轻小说 —— 来自 GitHub liufuyou/read
            NovelSource(
                id = "git_liufuyou_esjzone",
                name = "ESJZone 轻小说 (GitHub/liufuyou)",
                baseUrl = "https://www.esjzone.one",
                group = "轻小说专区 (GitHub 实测)",
                sourceType = NovelSourceType.LEGADO_HTML,
                searchUrl = "/tags-01/{{key}}/{{page}}.html",
                exploreUrl = listOf(
                    "最新更新::/list-01/{{page}}.html",
                    "最新上架::/list-02/{{page}}.html",
                    "热门排行::/list-03/{{page}}.html"
                ).joinToString("\n"),
                comment = "导入自开源仓库 liufuyou/read，日系/翻译轻小说聚合站。",
                subscriptionUrl = "https://fastly.jsdelivr.net/gh/liufuyou/read@main/sources/yiove-novel.json",
                ruleSearch = NovelRuleSearch(
                    bookList = "div.col-lg-3",
                    name = ".card-title@a@text",
                    author = ".card-author@a@text",
                    bookUrl = ".card-title@a@href",
                    coverUrl = "div.lazyload@data-src||img@src",
                    lastChapter = ".card-ep@a@text"
                ),
                ruleBookInfo = NovelRuleBookInfo(
                    author = "ul.list-unstyled@li.1@a@text",
                    coverUrl = ".product-gallery@img@src",
                    intro = "div.description@html"
                ),
                ruleToc = NovelRuleToc(
                    chapterList = "#chapterList a",
                    chapterName = "text",
                    chapterUrl = "href"
                ),
                ruleContent = NovelRuleContent(
                    content = "div.forum-content@html"
                )
            ),
            // 6. 手机小说网 —— 来自 GitHub XIU2/Yuedu
            NovelSource(
                id = "git_xiu2_shoujix",
                name = "手机小说 (GitHub/XIU2)",
                baseUrl = "https://www.shoujix.com",
                group = "精选直连 (GitHub 实测)",
                sourceType = NovelSourceType.LEGADO_HTML,
                charset = "GBK",
                searchUrl = "/modules/article/search.php,{\"charset\":\"gbk\",\"method\":\"POST\",\"body\":\"searchkey={{key}}\"}",
                comment = "导入自开源仓库 XIU2/Yuedu。",
                subscriptionUrl = "https://fastly.jsdelivr.net/gh/XIU2/Yuedu@master/shuyuan",
                ruleSearch = NovelRuleSearch(
                    bookList = "class.novel-item",
                    name = "tag.img.0@alt||tag.a.0@text",
                    author = "class.novel-author.0@text",
                    bookUrl = "tag.a.0@href",
                    coverUrl = "tag.img.0@src",
                    intro = "class.novel-intro.0@text"
                ),
                ruleBookInfo = NovelRuleBookInfo(
                    author = "//meta[@property='og:novel:author']/@content",
                    coverUrl = "//meta[@property='og:image']/@content",
                    intro = "//meta[@property='og:description']/@content"
                ),
                ruleToc = NovelRuleToc(
                    chapterList = "//div[@id=\"lbks\"]/dl/dt[last()]/following-sibling::dd/a",
                    chapterName = "text",
                    chapterUrl = "href"
                ),
                ruleContent = NovelRuleContent(
                    content = "id.zjny.0@html"
                )
            ),
            // 7. 爱尚小说 —— 来自 GitHub liufuyou/read
            NovelSource(
                id = "git_liufuyou_aishang",
                name = "爱尚小说 (GitHub/liufuyou)",
                baseUrl = "http://www.23hh.la",
                group = "精选直连 (GitHub 实测)",
                sourceType = NovelSourceType.LEGADO_HTML,
                charset = "GBK",
                searchUrl = "http://www.23hh.la/modules/article/search.php,{\"charset\":\"gbk\",\"method\":\"POST\",\"body\":\"searchkey={{key}}&action=login\"}",
                comment = "导入自开源仓库 liufuyou/read。",
                subscriptionUrl = "https://fastly.jsdelivr.net/gh/liufuyou/read@main/sources/yiove-novel.json",
                ruleSearch = NovelRuleSearch(
                    bookList = "class.c_row",
                    name = "tag.span.0@tag.a.0@text||tag.a.0@text",
                    author = "tag.span.3@text",
                    bookUrl = "tag.span.0@tag.a.0@href||tag.a.0@href",
                    coverUrl = "tag.img.0@src"
                ),
                ruleBookInfo = NovelRuleBookInfo(
                    coverUrl = "class.pic@tag.img.0@src",
                    intro = "class.bookinfo_intro@textNodes"
                ),
                ruleToc = NovelRuleToc(
                    chapterList = "class.book_list@tag.li@tag.a",
                    chapterName = "text",
                    chapterUrl = "href"
                ),
                ruleContent = NovelRuleContent(
                    content = "id.htmlContent@textNodes"
                )
            ),
            // 8. 英文原版小说 (Tingroom) —— 来自 GitHub liufuyou/read
            NovelSource(
                id = "git_liufuyou_tingroom",
                name = "英文原版小说 (GitHub/liufuyou)",
                baseUrl = "http://novel.tingroom.com",
                group = "双语/外文阅读",
                sourceType = NovelSourceType.LEGADO_HTML,
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
