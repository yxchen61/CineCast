package com.cinecast.app.novel.engine

import com.cinecast.app.data.network.NetworkClient
import com.cinecast.app.novel.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

/**
 * 独立小说源抓取与解析引擎
 * 支持：
 * - Legado JSON API 书源（如 GitHub XIU2/Yuedu 酷我小说 API）
 * - Legado HTML 规则书源（GET/POST、GBK/UTF-8 自动解码、分页目录与分页正文）
 * - Gutendex 开源中文/世界文学名著 API（自动切分章节，永久直连可用）
 */
class NovelSourceEngine {

    private val legadoParser = LegadoSourceParser()

    // In-memory cache for Gutendex full-text chapters so opening chapter 2..N is instant
    private val gutenbergChaptersCache = ConcurrentHashMap<String, List<NovelChapter>>()

    fun getExploreCategories(source: NovelSource): List<NovelExploreCategory> {
        if (source.sourceType == NovelSourceType.CLASSICS_5000YAN) {
            return listOf(
                NovelExploreCategory("四大名著", "yan5000_cat://sida"),
                NovelExploreCategory("古典小说名著", "yan5000_cat://classical"),
                NovelExploreCategory("史学与神话", "yan5000_cat://history"),
                NovelExploreCategory("国学经典与诗词", "yan5000_cat://guoxue")
            )
        }
        if (source.sourceType == NovelSourceType.XIMALAYA_PINGSHU) {
            return listOf(
                NovelExploreCategory("热门免费评书", "xm_cat://hot"),
                NovelExploreCategory("单田芳经典", "xm_cat://shantianfang"),
                NovelExploreCategory("历史演义", "xm_cat://history"),
                NovelExploreCategory("武侠公案", "xm_cat://wuxia"),
                NovelExploreCategory("单口评书", "xm_cat://guodegang")
            )
        }
        if (source.sourceType == NovelSourceType.KUWO_PINGSHU) {
            return listOf(
                NovelExploreCategory("单田芳全集", "kw_cat://shantianfang"),
                NovelExploreCategory("连丽如/刘兰芳", "kw_cat://masters"),
                NovelExploreCategory("三国/红楼/水浒", "kw_cat://classics"),
                NovelExploreCategory("郭德纲评书", "kw_cat://guodegang")
            )
        }
        if (source.sourceType == NovelSourceType.WIKISOURCE_API) {
            return listOf(
                NovelExploreCategory("四大名著与演义", "ws_cat://sida"),
                NovelExploreCategory("古典小说名著", "ws_cat://classical"),
                NovelExploreCategory("鲁迅与现当代经典", "ws_cat://modern"),
                NovelExploreCategory("史学典籍与兵法", "ws_cat://history"),
                NovelExploreCategory("国学哲学与诗词", "ws_cat://poetry")
            )
        }
        if (source.sourceType == NovelSourceType.GUTENDEX_API) {
            return listOf(
                NovelExploreCategory("中文古典名著", "https://gutendex.com/books/?languages=zh&sort=popular&page={{page}}"),
                NovelExploreCategory("世界必读名著", "https://gutendex.com/books/?sort=popular&page={{page}}"),
                NovelExploreCategory("侦探推理名著", "https://gutendex.com/books/?topic=detective&sort=popular&page={{page}}"),
                NovelExploreCategory("科幻冒险经典", "https://gutendex.com/books/?topic=science+fiction&sort=popular&page={{page}}"),
                NovelExploreCategory("历史哲学典籍", "https://gutendex.com/books/?topic=history&sort=popular&page={{page}}"),
                NovelExploreCategory("童话与成长文学", "https://gutendex.com/books/?topic=children&sort=popular&page={{page}}"),
                NovelExploreCategory("世界短篇与戏剧", "https://gutendex.com/books/?topic=short+stories&sort=popular&page={{page}}")
            )
        }
        return legadoParser.parseExploreCategories(source.exploreUrl)
    }

    suspend fun fetchExplore(
        source: NovelSource,
        categoryUrl: String,
        page: Int = 1
    ): List<NovelBook> = withContext(Dispatchers.IO) {
        if (source.sourceType == NovelSourceType.CLASSICS_5000YAN) {
            if (page > 1) return@withContext emptyList()
            return@withContext getCurated5000yanBooks(source, categoryUrl)
        }
        if (source.sourceType == NovelSourceType.XIMALAYA_PINGSHU) {
            return@withContext fetchXimalayaPingshuExplore(source, categoryUrl, page)
        }
        if (source.sourceType == NovelSourceType.KUWO_PINGSHU) {
            return@withContext fetchKuwoPingshuExplore(source, categoryUrl, page)
        }
        if (source.sourceType == NovelSourceType.WIKISOURCE_API) {
            if (page > 1) return@withContext emptyList()
            return@withContext getCuratedWikisourceBooks(source, categoryUrl)
        }
        if (source.sourceType == NovelSourceType.GUTENDEX_API) {
            val url = expandUrlTemplate(categoryUrl, "", page, "UTF-8")
            return@withContext parseGutendexBooks(source, executeRequest(url, source))
        }

        val urlSpec = expandUrlTemplate(categoryUrl, "", page, source.charset)
        val body = executeRequest(urlSpec, source)
        val resolvedBase = resolveSpecUrl(source.baseUrl, urlSpec)
        parseBookList(source, body, resolvedBase)
    }

    suspend fun search(
        source: NovelSource,
        keyword: String,
        page: Int = 1
    ): List<NovelBook> = withContext(Dispatchers.IO) {
        val q = keyword.trim()
        if (q.isBlank()) return@withContext emptyList()

        if (source.sourceType == NovelSourceType.CLASSICS_5000YAN) {
            return@withContext search5000yanBooks(source, q)
        }
        if (source.sourceType == NovelSourceType.XIMALAYA_PINGSHU) {
            return@withContext searchXimalayaPingshu(source, q)
        }
        if (source.sourceType == NovelSourceType.KUWO_PINGSHU) {
            return@withContext searchKuwoPingshu(source, q)
        }
        if (source.sourceType == NovelSourceType.WIKISOURCE_API) {
            return@withContext searchWikisourceBooks(source, q)
        }
        if (source.sourceType == NovelSourceType.GUTENDEX_API) {
            val encoded = URLEncoder.encode(q, "UTF-8")
            val url = "https://gutendex.com/books/?search=$encoded&page=$page"
            return@withContext parseGutendexBooks(source, executeRequest(url, source))
        }

        if (source.searchUrl.isBlank()) return@withContext emptyList()
        val urlSpec = expandUrlTemplate(source.searchUrl, q, page, source.charset)
        val body = executeRequest(urlSpec, source)
        val resolvedBase = resolveSpecUrl(source.baseUrl, urlSpec)
        parseBookList(source, body, resolvedBase)
    }

    suspend fun fetchBookDetailAndToc(
        source: NovelSource,
        book: NovelBook
    ): Pair<NovelBook, List<NovelChapter>> = withContext(Dispatchers.IO) {
        if (source.sourceType == NovelSourceType.CLASSICS_5000YAN || book.bookUrl.contains(".5000yan.com")) {
            val html = executeRequest(book.bookUrl, source)
            val chapters = parse5000yanToc(html, book.bookUrl)
            return@withContext book.copy(
                latestChapter = chapters.lastOrNull()?.title?.ifBlank { "全本典藏" } ?: "全本典藏"
            ) to chapters
        }
        if (source.sourceType == NovelSourceType.XIMALAYA_PINGSHU || book.bookUrl.startsWith("ximalaya_album://")) {
            val episodes = fetchXimalayaPingshuEpisodes(source, book)
            return@withContext book.copy(latestChapter = "共 ${episodes.size} 回") to episodes
        }
        if (source.sourceType == NovelSourceType.KUWO_PINGSHU ||
            book.bookUrl.startsWith("kuwo_album://") ||
            book.bookUrl.startsWith("kuwo_search://")
        ) {
            val episodes = fetchKuwoPingshuEpisodes(source, book)
            return@withContext book.copy(latestChapter = "共 ${episodes.size} 回") to episodes
        }
        if (source.sourceType == NovelSourceType.WIKISOURCE_API || book.bookUrl.startsWith("wikisource://")) {
            val chapters = fetchWikisourceChapters(source, book)
            return@withContext book.copy(latestChapter = chapters.lastOrNull()?.title ?: "全本典藏") to chapters
        }
        if (source.sourceType == NovelSourceType.GUTENDEX_API) {
            val chapters = fetchGutendexChapters(book)
            return@withContext book.copy(latestChapter = chapters.lastOrNull()?.title ?: book.latestChapter) to chapters
        }

        val detailBody = executeRequest(book.bookUrl, source)
        val updatedBook = enrichBookInfo(source, book, detailBody)

        // Determine TOC URL
        val tocUrl = updatedBook.tocUrl.ifBlank {
            if (source.sourceType == NovelSourceType.LEGADO_JSON && source.baseUrl.contains("appi.kuwo.cn")) {
                "${book.bookUrl.trimEnd('/')}/chapters?pi=1&ps=2000"
            } else {
                book.bookUrl
            }
        }

        val tocBody = if (tocUrl == book.bookUrl) detailBody else executeRequest(tocUrl, source)
        val chapters = parseChapters(source, tocBody, tocUrl)
        val finalBook = updatedBook.copy(
            tocUrl = tocUrl,
            latestChapter = chapters.lastOrNull()?.title?.ifBlank { updatedBook.latestChapter } ?: updatedBook.latestChapter
        )
        finalBook to chapters
    }

    suspend fun fetchChapterContent(
        source: NovelSource,
        chapter: NovelChapter
    ): String = withContext(Dispatchers.IO) {
        if (!chapter.inlineContent.isNullOrBlank()) {
            return@withContext chapter.inlineContent
        }
        if (source.sourceType == NovelSourceType.CLASSICS_5000YAN || chapter.url.contains(".5000yan.com")) {
            val html = executeRequest(chapter.url, source)
            val text = parse5000yanChapterHtml(html)
            if (text.isBlank()) throw IllegalStateException("未能提取该回正文，请刷新重试")
            return@withContext formatCleanParagraphs(text)
        }
        if (source.sourceType == NovelSourceType.WIKISOURCE_API || chapter.url.startsWith("wikisource://")) {
            return@withContext fetchWikisourceChapterText(source, chapter.url.removePrefix("wikisource://"))
        }

        val pages = mutableListOf<String>()
        var nextUrl: String? = chapter.url
        val visited = mutableSetOf<String>()
        var pageGuard = 0

        while (!nextUrl.isNullOrBlank() && nextUrl !in visited && pageGuard < 6) {
            visited.add(nextUrl)
            pageGuard++
            val body = executeRequest(nextUrl, source)
            val (text, nextPage) = parseSingleContentPage(source, body, nextUrl)
            if (text.isNotBlank()) pages.add(text)
            nextUrl = nextPage?.takeIf { it.isNotBlank() && it != nextUrl }
        }

        val combined = pages.joinToString("\n\n").trim()
        if (combined.isBlank()) {
            throw IllegalStateException("未能提取章节正文，请尝试切换其他书源")
        }
        formatCleanParagraphs(combined)
    }

    suspend fun pingSource(source: NovelSource): Long = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val probeUrl = when {
                source.sourceType == NovelSourceType.CLASSICS_5000YAN ->
                    "https://sanguo.5000yan.com/"
                source.sourceType == NovelSourceType.XIMALAYA_PINGSHU ->
                    "https://www.ximalaya.com/revision/category/v2/albums?pageNum=1&pageSize=3&sort=1&categoryId=12"
                source.sourceType == NovelSourceType.KUWO_PINGSHU ->
                    "http://search.kuwo.cn/r.s?all=%E5%8D%95%E7%94%B0%E8%8A%B3&ft=album&rformat=json&encoding=utf8&rn=3&pn=0"
                source.sourceType == NovelSourceType.WIKISOURCE_API ->
                    "https://zh.wikisource.org/w/api.php?action=query&meta=siteinfo&format=json"
                source.sourceType == NovelSourceType.GUTENDEX_API ->
                    "https://gutendex.com/books/?languages=zh&page=1"
                source.exploreUrl.isNotBlank() -> {
                    val first = getExploreCategories(source).firstOrNull()?.url
                    if (!first.isNullOrBlank()) expandUrlTemplate(first, "", 1, source.charset)
                    else source.baseUrl
                }
                else -> source.baseUrl
            }
            val body = executeRequest(probeUrl, source)
            if (body.length < 32) -1L else (System.currentTimeMillis() - start).coerceAtLeast(1L)
        } catch (_: Exception) {
            -1L
        }
    }

    // ==================== Parsing Helpers ====================

    private fun parseBookList(source: NovelSource, rawBody: String, baseUrl: String): List<NovelBook> {
        val rule = source.ruleSearch
        if (source.sourceType == NovelSourceType.LEGADO_JSON || rawBody.trimStart().let { it.startsWith("{") || it.startsWith("[") }) {
            return parseBookListJson(source, rawBody, baseUrl)
        }

        val doc = Jsoup.parse(rawBody, baseUrl)
        val elements = NovelRuleEngine.selectElements(doc, rule.bookList)
        val results = mutableListOf<NovelBook>()
        for (el in elements) {
            val title = NovelRuleEngine.evalHtmlString(el, rule.name, baseUrl)
                .ifBlank { el.selectFirst("h3, h2, h4, a")?.text().orEmpty().trim() }
            val bookUrl = NovelRuleEngine.evalHtmlString(el, rule.bookUrl, baseUrl)
                .ifBlank { el.selectFirst("a[href]")?.absUrl("href").orEmpty() }
            if (title.isBlank() || bookUrl.isBlank()) continue

            val author = NovelRuleEngine.evalHtmlString(el, rule.author, baseUrl)
                .removePrefix("作者：").removePrefix("作者:").trim()
            val cover = NovelRuleEngine.evalHtmlString(el, rule.coverUrl, baseUrl)
            val intro = NovelRuleEngine.evalHtmlString(el, rule.intro, baseUrl)
            val kind = NovelRuleEngine.evalHtmlString(el, rule.kind, baseUrl)
            val latest = NovelRuleEngine.evalHtmlString(el, rule.lastChapter, baseUrl)
            val words = NovelRuleEngine.evalHtmlString(el, rule.wordCount, baseUrl)

            results.add(
                NovelBook(
                    id = "${source.id}_${bookUrl.hashCode()}",
                    sourceId = source.id,
                    sourceName = source.name,
                    title = title,
                    author = author,
                    coverUrl = cover,
                    intro = intro,
                    kind = kind,
                    latestChapter = latest,
                    wordCount = words,
                    bookUrl = NovelRuleEngine.resolveUrl(baseUrl, bookUrl)
                )
            )
        }
        return results.distinctBy { it.bookUrl }
    }

    private fun parseBookListJson(source: NovelSource, rawBody: String, baseUrl: String): List<NovelBook> {
        val rule = source.ruleSearch
        val trimmed = rawBody.trim()
        val root: Any = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
        val items = NovelRuleEngine.selectJsonList(root, rule.bookList.ifBlank { "$.data" })
        val results = mutableListOf<NovelBook>()

        for (node in items) {
            val title = NovelRuleEngine.evalJsonString(node, rule.name.ifBlank { "$.title" })
            val rawUrl = NovelRuleEngine.evalJsonString(node, rule.bookUrl)
            if (title.isBlank() || rawUrl.isBlank()) continue

            val resolvedUrl = NovelRuleEngine.resolveUrl(source.baseUrl, rawUrl)
            val author = NovelRuleEngine.evalJsonString(node, rule.author)
            val cover = NovelRuleEngine.resolveUrl(source.baseUrl, NovelRuleEngine.evalJsonString(node, rule.coverUrl))
            val intro = NovelRuleEngine.evalJsonString(node, rule.intro)
            val kind = NovelRuleEngine.evalJsonString(node, rule.kind)
                .replace("30", "连载").replace("50", "完结")
            val latest = NovelRuleEngine.evalJsonString(node, rule.lastChapter)
            val words = NovelRuleEngine.evalJsonString(node, rule.wordCount).let { w ->
                val num = w.toLongOrNull()
                if (num != null && num > 10000) "${String.format("%.1f", num / 10000.0)}万字" else w
            }

            results.add(
                NovelBook(
                    id = "${source.id}_${resolvedUrl.hashCode()}",
                    sourceId = source.id,
                    sourceName = source.name,
                    title = title,
                    author = author,
                    coverUrl = cover,
                    intro = intro,
                    kind = kind,
                    latestChapter = latest,
                    wordCount = words,
                    bookUrl = resolvedUrl
                )
            )
        }
        return results.distinctBy { it.bookUrl }
    }

    private fun enrichBookInfo(source: NovelSource, book: NovelBook, body: String): NovelBook {
        val rule = source.ruleBookInfo
        if (source.sourceType == NovelSourceType.LEGADO_JSON || body.trimStart().startsWith("{")) {
            return runCatching {
                val root = JSONObject(body)
                val target = if (rule.init.isNotBlank()) {
                    NovelRuleEngine.evalJsonPath(root, rule.init) ?: root
                } else root
                val tocRaw = NovelRuleEngine.evalJsonString(target, rule.tocUrl)
                book.copy(
                    title = NovelRuleEngine.evalJsonString(target, rule.name).ifBlank { book.title },
                    author = NovelRuleEngine.evalJsonString(target, rule.author).ifBlank { book.author },
                    coverUrl = NovelRuleEngine.evalJsonString(target, rule.coverUrl)
                        .let { if (it.isNotBlank()) NovelRuleEngine.resolveUrl(source.baseUrl, it) else book.coverUrl },
                    intro = NovelRuleEngine.evalJsonString(target, rule.intro).ifBlank { book.intro },
                    kind = NovelRuleEngine.evalJsonString(target, rule.kind).ifBlank { book.kind },
                    latestChapter = NovelRuleEngine.evalJsonString(target, rule.lastChapter).ifBlank { book.latestChapter },
                    tocUrl = if (tocRaw.isNotBlank()) NovelRuleEngine.resolveUrl(source.baseUrl, tocRaw) else book.tocUrl
                )
            }.getOrDefault(book)
        }

        val doc = Jsoup.parse(body, book.bookUrl)
        val tocRaw = NovelRuleEngine.evalHtmlString(doc, rule.tocUrl, book.bookUrl)
        return book.copy(
            title = NovelRuleEngine.evalHtmlString(doc, rule.name, book.bookUrl).ifBlank { book.title },
            author = NovelRuleEngine.evalHtmlString(doc, rule.author, book.bookUrl)
                .removePrefix("作者：").removePrefix("作者:").trim().ifBlank { book.author },
            coverUrl = NovelRuleEngine.evalHtmlString(doc, rule.coverUrl, book.bookUrl).ifBlank { book.coverUrl },
            intro = NovelRuleEngine.evalHtmlString(doc, rule.intro, book.bookUrl).ifBlank { book.intro },
            kind = NovelRuleEngine.evalHtmlString(doc, rule.kind, book.bookUrl).ifBlank { book.kind },
            latestChapter = NovelRuleEngine.evalHtmlString(doc, rule.lastChapter, book.bookUrl).ifBlank { book.latestChapter },
            tocUrl = if (tocRaw.isNotBlank()) NovelRuleEngine.resolveUrl(book.bookUrl, tocRaw) else book.tocUrl
        )
    }

    private fun parseChapters(source: NovelSource, rawBody: String, baseUrl: String): List<NovelChapter> {
        val rule = source.ruleToc
        if (source.sourceType == NovelSourceType.LEGADO_JSON || rawBody.trimStart().let { it.startsWith("{") || it.startsWith("[") }) {
            val trimmed = rawBody.trim()
            val root: Any = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
            val list = NovelRuleEngine.selectJsonList(root, rule.chapterList.ifBlank { "$.data" })
            return list.mapIndexedNotNull { idx, node ->
                val title = NovelRuleEngine.evalJsonString(node, rule.chapterName.ifBlank { "$.chapter_title" })
                val url = NovelRuleEngine.evalJsonString(node, rule.chapterUrl)
                if (title.isBlank() || url.isBlank()) null
                else NovelChapter(
                    index = idx,
                    title = title,
                    url = NovelRuleEngine.resolveUrl(source.baseUrl, url)
                )
            }
        }

        val doc = Jsoup.parse(rawBody, baseUrl)
        val elements = NovelRuleEngine.selectElements(doc, rule.chapterList)
        val chapters = mutableListOf<NovelChapter>()
        val seenUrls = mutableSetOf<String>()
        for (el in elements) {
            val title = NovelRuleEngine.evalHtmlString(el, rule.chapterName.ifBlank { "text" }, baseUrl)
            val rawHref = NovelRuleEngine.evalHtmlString(el, rule.chapterUrl.ifBlank { "href" }, baseUrl)
            val url = NovelRuleEngine.resolveUrl(baseUrl, rawHref)
            if (title.isBlank() || url.isBlank() || url in seenUrls) continue
            seenUrls.add(url)
            chapters.add(NovelChapter(index = chapters.size, title = title, url = url))
        }
        return chapters
    }

    private fun parseSingleContentPage(source: NovelSource, rawBody: String, pageUrl: String): Pair<String, String?> {
        val rule = source.ruleContent
        if (source.sourceType == NovelSourceType.LEGADO_JSON || rawBody.trimStart().startsWith("{")) {
            val root = JSONObject(rawBody)
            var text = NovelRuleEngine.evalJsonString(root, rule.content.ifBlank { "$.data.content" })
            if (rule.replaceRegex.isNotBlank()) {
                val (_, pat, repl) = NovelRuleEngine.splitRegexSuffix(rule.replaceRegex)
                text = NovelRuleEngine.applyRegex(text, pat, repl)
            }
            return text to null
        }

        val doc = Jsoup.parse(rawBody, pageUrl)
        var text = NovelRuleEngine.evalHtmlString(doc, rule.content, pageUrl)
        if (text.isBlank()) {
            // Heuristic fallback if a site changed its wrapper slightly
            val fallbackEl = doc.selectFirst("#content, .content, #chaptercontent, .read-content, .article-content, #nr1, .con")
            if (fallbackEl != null) {
                text = NovelRuleEngine.htmlToParagraphs(fallbackEl.html())
            }
        }
        if (rule.replaceRegex.isNotBlank()) {
            val (_, pat, repl) = NovelRuleEngine.splitRegexSuffix(rule.replaceRegex)
            text = NovelRuleEngine.applyRegex(text, pat, repl)
        }
        val nextUrl = if (rule.nextContentUrl.isNotBlank()) {
            NovelRuleEngine.evalHtmlString(doc, rule.nextContentUrl, pageUrl)
                .takeIf { it.startsWith("http") && it != pageUrl }
        } else null
        return text to nextUrl
    }

    /**
     * 解析评书单回可播放音频直链（喜马拉雅 M4A CDN / 酷我 MP3 CDN）
     */
    suspend fun resolvePingshuAudioUrl(
        source: NovelSource,
        episode: NovelChapter
    ): String = withContext(Dispatchers.IO) {
        val url = episode.url.trim()
        if (url.startsWith("http://") || url.startsWith("https://")) return@withContext url

        if (url.startsWith("ximalaya_track://")) {
            val trackId = url.removePrefix("ximalaya_track://").trim()
            val raw = executeRequest("https://m.ximalaya.com/tracks/$trackId.json", source)
            val obj = JSONObject(raw)
            val audio = obj.optString("play_path_64").ifBlank {
                obj.optString("play_path").ifBlank { obj.optString("play_path_32") }
            }.trim()
            if (audio.startsWith("http")) return@withContext audio
            throw IllegalStateException("该回音频需 VIP 或暂不可播，可尝试酷我评书源")
        }

        if (url.startsWith("kuwo_track://")) {
            val rid = url.removePrefix("kuwo_track://").trim()
            val direct = executeRequest(
                "http://antiserver.kuwo.cn/anti.s?type=convert_url&rid=MUSIC_$rid&format=mp3&response=url",
                source
            ).trim()
            if (direct.startsWith("http")) return@withContext direct
            throw IllegalStateException("酷我评书音频直链获取失败")
        }

        throw IllegalStateException("不支持的评书音频协议：$url")
    }

    // ==================== 喜马拉雅评书音频源适配器 ====================

    private fun fetchXimalayaPingshuExplore(source: NovelSource, categoryUrl: String, page: Int): List<NovelBook> {
        val curated = listOf(
            NovelBook(
                id = "xm_2677356", sourceId = source.id, sourceName = source.name,
                title = "白眉大侠（单田芳经典全本）", author = "单田芳",
                coverUrl = "https://imagev2.xmcdn.com/storages/f962-audiofreehighqps/D3/57/GMCoOSUFh1yoAAICLwEAQjMm.png",
                intro = "单田芳最具代表性的侠义武侠评书，白眉大侠徐良与开封府群雄斗智斗勇（全401回免费直连）。",
                kind = "武侠公案", latestChapter = "全 401 回", bookUrl = "ximalaya_album://2677356", tocUrl = "ximalaya_album://2677356"
            ),
            NovelBook(
                id = "xm_3820", sourceId = source.id, sourceName = source.name,
                title = "乱世枭雄（单田芳经典全本）", author = "单田芳",
                coverUrl = "https://imagev2.xmcdn.com/group56/M03/3D/26/wKgLdlx7e5zB0z60AAH9a4f8l18389.jpg",
                intro = "讲述东北王张作霖跌宕起伏传奇人生的传世评书名作（全485回免费直连）。",
                kind = "历史演义", latestChapter = "全 485 回", bookUrl = "ximalaya_album://3820", tocUrl = "ximalaya_album://3820"
            ),
            NovelBook(
                id = "xm_9742745", sourceId = source.id, sourceName = source.name,
                title = "郭德纲单口相声与评书精选", author = "郭德纲",
                intro = "德云社郭德纲经典单口相声与长篇评书合集，幽默风趣、包袱不断（194回免费直连）。",
                kind = "单口评书", latestChapter = "共 194 回", bookUrl = "ximalaya_album://9742745", tocUrl = "ximalaya_album://9742745"
            )
        )

        val liveAlbums = runCatching {
            val apiUrl = "https://www.ximalaya.com/revision/category/v2/albums?pageNum=$page&pageSize=24&sort=1&categoryId=12"
            val xmSource = source.copy(headers = source.headers + ("Referer" to "https://www.ximalaya.com/"))
            val raw = executeRequest(apiUrl, xmSource)
            parseXimalayaCategoryAlbums(source, raw)
        }.getOrDefault(emptyList())

        val combined = if (page == 1) (curated + liveAlbums).distinctBy { it.bookUrl } else liveAlbums
        return when (categoryUrl) {
            "xm_cat://shantianfang" -> combined.filter { it.title.contains("单田芳") || it.author.contains("单田芳") }.ifEmpty { curated.take(2) }
            "xm_cat://history" -> combined.filter {
                listOf("三国", "隋唐", "水浒", "大明", "枭雄", "岳飞", "杨家将", "历史", "演义", "汉", "唐", "宋", "清").any { k ->
                    it.title.contains(k) || it.intro.contains(k)
                }
            }.ifEmpty { combined }
            "xm_cat://wuxia" -> combined.filter {
                listOf("大侠", "三侠", "五义", "剑侠", "童林", "侠", "武", "公案", "包公", "水浒").any { k ->
                    it.title.contains(k) || it.intro.contains(k)
                }
            }.ifEmpty { combined }
            "xm_cat://guodegang" -> combined.filter {
                it.title.contains("郭德纲") || it.author.contains("郭德纲") || it.title.contains("相声") || it.title.contains("单口")
            }.ifEmpty { listOf(curated.last()) + combined.take(6) }
            else -> combined
        }
    }

    internal fun parseXimalayaCategoryAlbums(source: NovelSource, rawJson: String): List<NovelBook> {
        val arr = JSONObject(rawJson).optJSONObject("data")?.optJSONArray("albums") ?: return emptyList()
        val list = mutableListOf<NovelBook>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            if (a.optBoolean("isPaid", false)) continue // 只展示免费可听评书，避免点开受限
            val albumId = a.optLong("albumId", 0L)
            val title = a.optString("albumTitle").trim()
            if (albumId <= 0L || title.isBlank()) continue
            val rawCover = a.optString("albumCoverPath").trim()
            val cover = when {
                rawCover.startsWith("http") -> rawCover
                rawCover.startsWith("//") -> "https:$rawCover"
                rawCover.isNotBlank() -> "https://imagev2.xmcdn.com/$rawCover"
                else -> ""
            }
            val count = a.optInt("albumTrackCount", 0)
            val playCnt = a.optLong("albumPlayCount", 0L)
            val playStr = if (playCnt >= 100_000_000L) "${String.format("%.1f", playCnt / 100_000_000.0)}亿播放"
            else if (playCnt >= 10_000L) "${playCnt / 10_000L}万播放" else ""
            list.add(
                NovelBook(
                    id = "xm_$albumId",
                    sourceId = source.id,
                    sourceName = source.name,
                    title = title,
                    author = a.optString("albumUserNickName").ifBlank { "评书名家" },
                    coverUrl = cover,
                    intro = listOfNotNull(
                        if (count > 0) "全 $count 回免费评书" else null,
                        playStr.takeIf { it.isNotBlank() }
                    ).joinToString(" · "),
                    kind = "免费评书",
                    latestChapter = if (count > 0) "共 $count 回" else "更新中",
                    bookUrl = "ximalaya_album://$albumId",
                    tocUrl = "ximalaya_album://$albumId"
                )
            )
        }
        return list
    }

    private fun searchXimalayaPingshu(source: NovelSource, keyword: String): List<NovelBook> {
        val page1 = fetchXimalayaPingshuExplore(source, "xm_cat://hot", 1)
        val page2 = runCatching { fetchXimalayaPingshuExplore(source, "xm_cat://hot", 2) }.getOrDefault(emptyList())
        return (page1 + page2).distinctBy { it.bookUrl }.filter {
            it.title.contains(keyword, ignoreCase = true) || it.author.contains(keyword, ignoreCase = true)
        }
    }

    private fun fetchXimalayaPingshuEpisodes(source: NovelSource, book: NovelBook): List<NovelChapter> {
        val albumId = book.bookUrl.removePrefix("ximalaya_album://").trim()
        val episodes = mutableListOf<NovelChapter>()
        // Load up to 2 pages (200 episodes) smoothly in ascending order
        for (pg in 1..2) {
            val url = "https://mobile.ximalaya.com/mobile/v1/album/track?albumId=$albumId&pageId=$pg&pageSize=100&isAsc=true"
            val raw = runCatching { executeRequest(url, source) }.getOrNull() ?: break
            val dataObj = JSONObject(raw).optJSONObject("data") ?: break
            val arr = dataObj.optJSONArray("list") ?: break
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val tid = t.optLong("trackId", 0L)
                val title = t.optString("title").trim()
                if (tid <= 0L || title.isBlank()) continue
                episodes.add(
                    NovelChapter(
                        index = episodes.size,
                        title = title,
                        url = "ximalaya_track://$tid"
                    )
                )
            }
            val maxPage = dataObj.optInt("maxPageId", 1)
            if (pg >= maxPage) break
        }
        return episodes
    }

    // ==================== 酷我评书名家音频源适配器 ====================

    private fun fetchKuwoPingshuExplore(source: NovelSource, categoryUrl: String, page: Int): List<NovelBook> {
        val curatedAll = listOf(
            NovelBook(
                id = "kw_alb_74399040", sourceId = source.id, sourceName = source.name,
                title = "隋唐演义（单田芳 · 142回全本）", author = "单田芳",
                coverUrl = "https://img4.kuwo.cn/star/albumcover/300/s4s50/63/3142603909.jpg",
                intro = "单田芳经典历史演义评书，瓦岗寨群雄并起、秦琼程咬金与李世民传奇故事。",
                kind = "单田芳", latestChapter = "全 142 回", bookUrl = "kuwo_album://74399040", tocUrl = "kuwo_album://74399040"
            ),
            NovelBook(
                id = "kw_srch_bmdx", sourceId = source.id, sourceName = source.name,
                title = "白眉大侠（单田芳评书精选）", author = "单田芳",
                coverUrl = "https://img4.kuwo.cn/star/albumcover/300/s4s94/75/3044960347.jpg",
                intro = "单田芳武侠评书巅峰，徐良、房书安、白云瑞闯荡江湖，酷我高速直连秒播。",
                kind = "单田芳", latestChapter = "精选全集", bookUrl = "kuwo_search://单田芳白眉大侠", tocUrl = "kuwo_search://单田芳白眉大侠"
            ),
            NovelBook(
                id = "kw_alb_46299165", sourceId = source.id, sourceName = source.name,
                title = "雍正剑侠图（连丽如 · 378回全本）", author = "连丽如",
                intro = "评书表演艺术家连丽如代表作，侠客童林江湖传奇，全 378 回畅听。",
                kind = "连丽如", latestChapter = "全 378 回", bookUrl = "kuwo_album://46299165", tocUrl = "kuwo_album://46299165"
            ),
            NovelBook(
                id = "kw_alb_51220830", sourceId = source.id, sourceName = source.name,
                title = "大唐侠女（刘兰芳 · 62回全本）", author = "刘兰芳",
                intro = "著名评书大师刘兰芳播讲，隋唐巾帼英雄传奇故事。",
                kind = "刘兰芳", latestChapter = "全 62 回", bookUrl = "kuwo_album://51220830", tocUrl = "kuwo_album://51220830"
            ),
            NovelBook(
                id = "kw_alb_49740893", sourceId = source.id, sourceName = source.name,
                title = "契丹萧太后（刘兰芳 · 70回全本）", author = "刘兰芳",
                intro = "刘兰芳播讲辽代名后萧绰传奇人生，荡气回肠的历史评书。",
                kind = "刘兰芳", latestChapter = "全 70 回", bookUrl = "kuwo_album://49740893", tocUrl = "kuwo_album://49740893"
            ),
            NovelBook(
                id = "kw_alb_12961316", sourceId = source.id, sourceName = source.name,
                title = "康熙私访（连丽如 · 42回全本）", author = "连丽如",
                intro = "连丽如经典公案评书，康熙微服私访惩奸除恶。",
                kind = "连丽如", latestChapter = "全 42 回", bookUrl = "kuwo_album://12961316", tocUrl = "kuwo_album://12961316"
            ),
            NovelBook(
                id = "kw_alb_56364758", sourceId = source.id, sourceName = source.name,
                title = "红楼梦评书（66回全本）", author = "名家评书",
                intro = "经典四大名著《红楼梦》评书演绎版，全 66 回直连畅听。",
                kind = "名著评书", latestChapter = "全 66 回", bookUrl = "kuwo_album://56364758", tocUrl = "kuwo_album://56364758"
            ),
            NovelBook(
                id = "kw_srch_sanguo", sourceId = source.id, sourceName = source.name,
                title = "三国演义评书（袁阔成/名家版）", author = "袁阔成等",
                intro = "滚滚长江东逝水，群雄逐鹿经典《三国演义》有声评书精选。",
                kind = "名著评书", latestChapter = "精选回次", bookUrl = "kuwo_search://袁阔成三国演义", tocUrl = "kuwo_search://袁阔成三国演义"
            ),
            NovelBook(
                id = "kw_srch_gdg", sourceId = source.id, sourceName = source.name,
                title = "济公传（郭德纲长篇评书）", author = "郭德纲",
                intro = "郭德纲剧场经典长篇评书《济公传》，爆笑幽默与江湖故事并重。",
                kind = "郭德纲", latestChapter = "精选全集", bookUrl = "kuwo_search://郭德纲济公传", tocUrl = "kuwo_search://郭德纲济公传"
            ),
            NovelBook(
                id = "kw_alb_54549089", sourceId = source.id, sourceName = source.name,
                title = "九一八风云（单田芳评书）", author = "单田芳",
                coverUrl = "https://img4.kuwo.cn/star/albumcover/300/s3s22/69/928406337.jpg",
                intro = "单田芳播讲近代抗战风云历史长篇评书。",
                kind = "单田芳", latestChapter = "全集直连", bookUrl = "kuwo_album://54549089", tocUrl = "kuwo_album://54549089"
            )
        )

        if (page > 1) return emptyList()
        return when (categoryUrl) {
            "kw_cat://shantianfang" -> curatedAll.filter { it.kind == "单田芳" } + searchKuwoPingshuAlbums(source, "单田芳")
            "kw_cat://masters" -> curatedAll.filter { it.kind == "连丽如" || it.kind == "刘兰芳" }
            "kw_cat://classics" -> curatedAll.filter { it.kind == "名著评书" || it.title.contains("隋唐") }
            "kw_cat://guodegang" -> curatedAll.filter { it.kind == "郭德纲" } + searchKuwoPingshuAlbums(source, "郭德纲评书")
            else -> curatedAll
        }.distinctBy { it.bookUrl }
    }

    private fun searchKuwoPingshu(source: NovelSource, keyword: String): List<NovelBook> {
        val albums = searchKuwoPingshuAlbums(source, keyword)
        val directCollection = NovelBook(
            id = "kw_srch_${keyword.hashCode()}",
            sourceId = source.id,
            sourceName = source.name,
            title = "「$keyword」酷我评书全集直连",
            author = "酷我评书检索",
            intro = "实时聚合酷我音频库中与「$keyword」相关的评书分回音频，点击立即连续收听。",
            kind = "实时检索",
            latestChapter = "直连秒播",
            bookUrl = "kuwo_search://$keyword",
            tocUrl = "kuwo_search://$keyword"
        )
        return (listOf(directCollection) + albums).distinctBy { it.bookUrl }
    }

    private fun searchKuwoPingshuAlbums(source: NovelSource, keyword: String): List<NovelBook> {
        return runCatching {
            val enc = URLEncoder.encode(keyword, "UTF-8")
            val raw = executeRequest(
                "http://search.kuwo.cn/r.s?all=$enc&ft=album&rformat=json&encoding=utf8&rn=12&pn=0",
                source
            ).trim()
            val arr = JSONObject(raw).optJSONArray("albumlist") ?: return emptyList()
            val list = mutableListOf<NovelBook>()
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                val aid = a.optString("albumid").trim()
                val title = a.optString("name").replace("&nbsp;", " ").trim()
                if (aid.isBlank() || title.isBlank()) continue
                val pic = a.optString("pic").trim().let {
                    when {
                        it.startsWith("http") -> it
                        it.isNotBlank() -> "https://img4.kuwo.cn/star/albumcover/$it"
                        else -> ""
                    }
                }
                list.add(
                    NovelBook(
                        id = "kw_alb_$aid",
                        sourceId = source.id,
                        sourceName = source.name,
                        title = title,
                        author = a.optString("artist").replace("&nbsp;", " ").ifBlank { "评书名家" },
                        coverUrl = pic,
                        intro = a.optString("info").replace("&nbsp;", " ").ifBlank { "酷我官方评书专辑，MP3 直连畅听。" },
                        kind = "酷我评书",
                        latestChapter = "专辑全集",
                        bookUrl = "kuwo_album://$aid",
                        tocUrl = "kuwo_album://$aid"
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    private fun fetchKuwoPingshuEpisodes(source: NovelSource, book: NovelBook): List<NovelChapter> {
        if (book.bookUrl.startsWith("kuwo_album://")) {
            val aid = book.bookUrl.removePrefix("kuwo_album://").trim()
            val url = "http://search.kuwo.cn/r.s?stype=albuminfo&albumid=$aid&show_copyright_off=1&alflac=1&vipver=1&mobi=1&rn=150&pn=0"
            val raw = executeRequest(url, source).trim()
            val arr = JSONObject(raw).optJSONArray("musiclist") ?: return emptyList()
            val eps = mutableListOf<NovelChapter>()
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val id = m.optString("id").ifBlank { m.optString("musicrid") }.trim()
                val title = m.optString("name").ifBlank { m.optString("songname") }.replace("&nbsp;", " ").trim()
                if (id.isBlank() || title.isBlank()) continue
                eps.add(NovelChapter(index = eps.size, title = title, url = "kuwo_track://$id"))
            }
            return eps.sortedBy { extractChapterOrderNumber(it.title) }
                .mapIndexed { idx, ch -> ch.copy(index = idx) }
        }

        // Direct kuwo_search:// episode list
        val kw = book.bookUrl.removePrefix("kuwo_search://").trim()
        val enc = URLEncoder.encode(kw, "UTF-8")
        val raw = executeRequest(
            "http://search.kuwo.cn/r.s?all=$enc&ft=music&rformat=json&encoding=utf8&rn=80&pn=0",
            source
        ).trim()
        val arr = JSONObject(raw).optJSONArray("abslist") ?: return emptyList()
        val eps = mutableListOf<NovelChapter>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val id = m.optString("DC_TARGETID").ifBlank { m.optString("MUSICRID").removePrefix("MUSIC_") }.trim()
            val title = m.optString("NAME").ifBlank { m.optString("SONGNAME") }.replace("&nbsp;", " ").trim()
            if (id.isBlank() || title.isBlank()) continue
            eps.add(NovelChapter(index = eps.size, title = title, url = "kuwo_track://$id"))
        }
        return eps.sortedBy { extractChapterOrderNumber(it.title) }
            .mapIndexed { idx, ch -> ch.copy(index = idx) }
    }

    // ==================== 中文维基文库 (Wikisource) 名著典藏适配器 ====================

    private data class WsCatalogEntry(
        val displayTitle: String,
        val wikiPage: String,
        val author: String,
        val kind: String,
        val intro: String
    )

    private val wikisourceCatalog: Map<String, List<WsCatalogEntry>> = mapOf(
        "ws_cat://sida" to listOf(
            WsCatalogEntry("三国演义", "三國演義", "罗贯中", "四大名著", "东汉末年群雄逐鹿，魏蜀吴三国鼎立波澜壮阔的历史长篇演义（全120回简体校注）。"),
            WsCatalogEntry("红楼梦", "紅樓夢", "曹雪芹", "四大名著", "中国古典小说巅峰之作，以贾宝玉、林黛玉、薛宝钗情感与贾府兴衰映照世态百相（全120回）。"),
            WsCatalogEntry("西游记", "西遊記", "吴承恩", "四大名著", "唐僧师徒四人历经九九八十一难西天取经的神魔奇幻经典（全100回）。"),
            WsCatalogEntry("水浒传", "水滸傳 (100回本)", "施耐庵", "四大名著", "梁山泊一百零八位好汉聚义反抗官逼民反的英雄传奇（全100回容与堂本）。"),
            WsCatalogEntry("封神演义", "封神演義", "许仲琳", "神魔演义", "商周更迭、姜子牙辅佐武王伐纣并封神榜敕封诸神的古典神魔名著（全100回）。"),
            WsCatalogEntry("隋唐演义", "隋唐演義", "褚人获", "历史演义", "瓦岗群雄、秦琼、程咬金与李世民开创大唐盛世的英雄演义。"),
            WsCatalogEntry("东周列国志", "東周列國志", "冯梦龙", "历史演义", "春秋战国五百余年诸侯争霸、纵横捭阖的宏大历史长卷。"),
            WsCatalogEntry("说岳全传", "說岳全傳", "钱彩", "历史演义", "民族英雄岳飞精忠报国、率领岳家军抗击金兵的传奇演义。")
        ),
        "ws_cat://classical" to listOf(
            WsCatalogEntry("儒林外史", "儒林外史", "吴敬梓", "讽刺小说", "清代长篇讽刺小说高峰，深刻描绘科举制度下士林众生相（范进中举等名篇）。"),
            WsCatalogEntry("聊斋志异", "聊齋志異", "蒲松龄", "志怪短篇", "清代文言短篇小说集大成之作，借花妖狐魅写尽人间情义与世道讽喻。"),
            WsCatalogEntry("镜花缘", "鏡花緣", "李汝珍", "奇幻古典", "唐敖海外游历女儿国、君子国等奇异邦国，融合才学与讽刺的浪漫名著。"),
            WsCatalogEntry("三侠五义", "三俠五義", "石玉昆", "侠义公案", "包拯断案与展昭、白玉堂等侠客义士行侠仗义的经典公案武侠小说。"),
            WsCatalogEntry("老残游记", "老殘遊記", "刘鹗", "晚清四大谴责", "游方郎中老残见闻录，白描大明湖风景与晚清官场弊政的文学名作。"),
            WsCatalogEntry("官场现形记", "官場現形記", "李伯元", "晚清四大谴责", "辛辣揭露晚清官僚腐败百态的现实主义长篇讽刺名著。"),
            WsCatalogEntry("喻世明言", "喻世明言", "冯梦龙", "三言二拍", "明代白话短篇小说经典，收录蒋兴哥重会珍珠衫等脍炙人口的名篇。"),
            WsCatalogEntry("警世通言", "警世通言", "冯梦龙", "三言二拍", "收录白娘子永镇雷峰塔、杜十娘怒沉百宝箱等传世话本故事。"),
            WsCatalogEntry("醒世恒言", "醒世恆言", "冯梦龙", "三言二拍", "描绘明代市井生活与人情冷暖的经典短篇话本集。"),
            WsCatalogEntry("初刻拍案惊奇", "初刻拍案驚奇", "凌濛初", "三言二拍", "情节曲折离奇、充满传奇色彩的明代拟话本小说集。")
        ),
        "ws_cat://modern" to listOf(
            WsCatalogEntry("呐喊", "吶喊", "鲁迅", "现代名著", "收录《狂人日记》《孔乙己》《药》《故乡》《阿Q正传》《社戏》等现代文学奠基名篇。"),
            WsCatalogEntry("彷徨", "彷徨", "鲁迅", "现代名著", "收录《祝福》《在酒楼上》《伤逝》《孤独者》等深刻剖析知识分子与社会命运的名篇。"),
            WsCatalogEntry("朝花夕拾", "朝花夕拾", "鲁迅", "散文经典", "收录《从百草园到三味书屋》《藤野先生》《阿长与〈山海经〉》等温暖深沉的回忆散文。"),
            WsCatalogEntry("野草", "野草", "鲁迅", "散文诗集", "收录《秋夜》《雪》《好的故事》《过客》等意境深邃的现代散文诗经典。"),
            WsCatalogEntry("故事新编", "故事新編", "鲁迅", "历史短篇", "以现代视角重述《补天》《奔月》《理水》《铸剑》等上古神话与历史寓言。"),
            WsCatalogEntry("孔乙己（单篇直读）", "孔乙己", "鲁迅", "短篇经典", "鲁迅短篇小说代表作，以咸亨酒店小伙计视角刻画旧时代读书人命运。"),
            WsCatalogEntry("阿Q正传（全本）", "阿Q正傳", "鲁迅", "中篇经典", "中国现代文学史上不朽的国民性批判杰作。"),
            WsCatalogEntry("祝福（单篇直读）", "祝福", "鲁迅", "短篇经典", "深刻揭示封建礼教悲剧的鲁迅代表作《祝福》。")
        ),
        "ws_cat://history" to listOf(
            WsCatalogEntry("史记", "史記", "司马迁", "史学名著", "中国第一部纪传体通史，“史家之绝唱，无韵之离骚”，含本纪、世家、列传。"),
            WsCatalogEntry("世说新语", "世說新語", "刘义庆", "魏晋笔记", "记录汉末至东晋名士风度、清谈轶事与魏晋风流的笔记小说经典。"),
            WsCatalogEntry("孙子兵法", "孫子兵法", "孙武", "兵学圣典", "世界最古老的军事战略巨著，十三篇字字珠玑，涵盖谋攻、虚实、用间。"),
            WsCatalogEntry("战国策", "戰國策", "刘向 编", "历史纵横", "记载战国策士纵横游说、机变智谋的经典历史散文集。"),
            WsCatalogEntry("山海经", "山海經", "先秦佚名", "上古神话", "保存夸父逐日、精卫填海、大禹治水等上古神话与地理异兽的奇书。"),
            WsCatalogEntry("搜神记", "搜神記", "干宝", "志怪典籍", "东晋志怪小说集，收录干将莫邪、董永与七仙女等流传千年的民间传说。"),
            WsCatalogEntry("梦溪笔谈", "夢溪筆談", "沈括", "科学笔记", "北宋百科全书式笔记巨著，涵盖天文、历法、活字印刷与艺术考据。"),
            WsCatalogEntry("徐霞客游记", "徐霞客遊記", "徐弘祖", "地理游记", "明代地理学家徐霞客三十余年万里考察山川风貌的壮丽游记。")
        ),
        "ws_cat://poetry" to listOf(
            WsCatalogEntry("论语", "論語", "孔子及其弟子", "儒家经典", "记录孔子言行与仁义礼智信思想的核心国学典籍。"),
            WsCatalogEntry("道德经", "道德經", "老子", "道家经典", "五千言哲学奇书，阐发“道法自然”“上善若水”的东方智慧。"),
            WsCatalogEntry("庄子", "莊子", "庄周", "道家经典", "《逍遥游》《齐物论》《秋水》——汪洋恣肆、充满想象力的哲学与文学瑰宝。"),
            WsCatalogEntry("菜根谭", "菜根譚", "洪应明", "修身格言", "融汇儒释道三家智慧的明代处世修身语录，“咬得菜根，百事可做”。"),
            WsCatalogEntry("古文观止", "古文觀止", "吴楚材 编", "古文选集", "精选自先秦至明代二百余篇传世名文，含《桃花源记》《岳阳楼记》《赤壁赋》。"),
            WsCatalogEntry("唐诗三百首", "唐詩三百首", "蘅塘退士 编", "古典诗词", "家喻户晓的唐代诗歌精选集，囊括李白、杜甫、王维、白居易等名家绝唱。"),
            WsCatalogEntry("诗经", "詩經", "先秦总集", "古典诗词", "中国最早的诗歌总集，分为风、雅、颂三部分，含《关雎》《蒹葭》等千古绝唱。"),
            WsCatalogEntry("楚辞", "楚辭", "屈原 等", "浪漫诗集", "中国浪漫主义诗歌源头，收录《离骚》《九歌》《天问》等瑰丽诗篇。")
        )
    )

    private fun getCuratedWikisourceBooks(source: NovelSource, categoryUrl: String): List<NovelBook> {
        val entries = wikisourceCatalog[categoryUrl] ?: wikisourceCatalog.values.flatten()
        return entries.map { e ->
            NovelBook(
                id = "ws_${e.wikiPage.hashCode()}",
                sourceId = source.id,
                sourceName = source.name,
                title = e.displayTitle,
                author = e.author,
                intro = e.intro,
                kind = e.kind,
                latestChapter = "维基文库全本 · 自动简体",
                bookUrl = "wikisource://${e.wikiPage}",
                tocUrl = "wikisource://${e.wikiPage}"
            )
        }
    }

    private fun searchWikisourceBooks(source: NovelSource, keyword: String): List<NovelBook> {
        val q = keyword.trim()
        // First include any matching curated classics immediately
        val localMatches = wikisourceCatalog.values.flatten()
            .filter {
                it.displayTitle.contains(q, ignoreCase = true) ||
                    it.author.contains(q, ignoreCase = true) ||
                    it.kind.contains(q, ignoreCase = true) ||
                    it.intro.contains(q, ignoreCase = true)
            }
            .map { e ->
                NovelBook(
                    id = "ws_${e.wikiPage.hashCode()}",
                    sourceId = source.id,
                    sourceName = source.name,
                    title = e.displayTitle,
                    author = e.author,
                    intro = e.intro,
                    kind = e.kind,
                    latestChapter = "维基文库全本 · 自动简体",
                    bookUrl = "wikisource://${e.wikiPage}",
                    tocUrl = "wikisource://${e.wikiPage}"
                )
            }

        // Then query live Chinese Wikisource MediaWiki Search API
        val remoteMatches = runCatching {
            val enc = URLEncoder.encode(q, "UTF-8")
            val url = "https://zh.wikisource.org/w/api.php?action=query&list=search&srsearch=$enc&srnamespace=0&srlimit=20&format=json"
            val raw = executeRequest(url, wikisourceHeadersSource(source))
            val arr = JSONObject(raw).optJSONObject("query")?.optJSONArray("search") ?: JSONArray()
            val seenRoots = mutableSetOf<String>()
            val out = mutableListOf<NovelBook>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val fullTitle = item.optString("title").trim()
                if (fullTitle.isBlank()) continue
                // Collapse chapter subpages like "三國演義/第001回" to root book "三國演義"
                val rootTitle = fullTitle.substringBefore("/").trim()
                if (!seenRoots.add(rootTitle)) continue
                val snippet = Jsoup.parse(item.optString("snippet")).text().trim()
                out.add(
                    NovelBook(
                        id = "ws_${rootTitle.hashCode()}",
                        sourceId = source.id,
                        sourceName = source.name,
                        title = rootTitle,
                        author = "维基文库典藏",
                        intro = snippet.ifBlank { "来自中文维基文库开源公版典籍，阅读时自动转换为简体中文。" },
                        kind = "公版名著",
                        latestChapter = "维基文库全本",
                        bookUrl = "wikisource://$rootTitle",
                        tocUrl = "wikisource://$rootTitle"
                    )
                )
            }
            out
        }.getOrDefault(emptyList())

        return (localMatches + remoteMatches).distinctBy { it.bookUrl }
    }

    private fun fetchWikisourceChapters(source: NovelSource, book: NovelBook): List<NovelChapter> {
        val pageTitle = book.bookUrl.removePrefix("wikisource://").trim()
        val wsSource = wikisourceHeadersSource(source)

        // 1. Check subpages via allpages prefix API (works for 三國演義/紅樓夢/西遊記/水滸傳/儒林外史/封神演義/史記/論語...)
        val encPrefix = URLEncoder.encode("$pageTitle/", "UTF-8")
        val subUrl = "https://zh.wikisource.org/w/api.php?action=query&list=allpages&apprefix=$encPrefix&aplimit=250&format=json"
        val subPages = runCatching {
            val raw = executeRequest(subUrl, wsSource)
            val arr = JSONObject(raw).optJSONObject("query")?.optJSONArray("allpages") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("title")?.trim()?.takeIf { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())

        if (subPages.size >= 2) {
            val sorted = subPages.sortedWith(compareBy<String>(
                { titleSortBucket(it.substringAfter("$pageTitle/")) },
                { extractChapterOrderNumber(it.substringAfter("$pageTitle/")) },
                { it }
            ))
            return sorted.mapIndexed { idx, fullSub ->
                val display = fullSub.substringAfter("$pageTitle/").replace("/", " · ")
                NovelChapter(
                    index = idx,
                    title = display.ifBlank { fullSub },
                    url = "wikisource://$fullSub"
                )
            }
        }

        // 2. For anthologies whose chapters are standalone pages linked from the index page (e.g. 吶喊 / 彷徨 / 朝花夕拾 / 聊齋志異)
        val mainLinks = runCatching {
            val encPage = URLEncoder.encode(pageTitle, "UTF-8")
            val linkUrl = "https://zh.wikisource.org/w/api.php?action=parse&page=$encPage&prop=links&format=json"
            val raw = executeRequest(linkUrl, wsSource)
            val arr = JSONObject(raw).optJSONObject("parse")?.optJSONArray("links") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                if (obj.optInt("ns", -1) != 0 || !obj.has("exists")) return@mapNotNull null
                val target = obj.optString("*").trim()
                if (target.isBlank() || target.startsWith("Author:") || target == pageTitle) null else target
            }
        }.getOrDefault(emptyList())

        if (mainLinks.size >= 3 && pageTitle !in setOf("孔乙己", "阿Q正傳", "祝福", "孫子兵法")) {
            return mainLinks.distinct().take(200).mapIndexed { idx, linked ->
                NovelChapter(
                    index = idx,
                    title = linked.substringAfter("$pageTitle/"),
                    url = "wikisource://$linked"
                )
            }
        }

        // 3. Single-page classic work (e.g. 孔乙己, 祝福, 孫子兵法)
        return listOf(
            NovelChapter(
                index = 0,
                title = book.title,
                url = "wikisource://$pageTitle"
            )
        )
    }

    private fun fetchWikisourceChapterText(source: NovelSource, pageTitle: String): String {
        val enc = URLEncoder.encode(pageTitle.trim(), "UTF-8")
        val url = "https://zh.wikisource.org/w/api.php?action=parse&page=$enc&prop=text&variant=zh-cn&redirects=1&format=json"
        val raw = executeRequest(url, wikisourceHeadersSource(source))
        val html = JSONObject(raw).optJSONObject("parse")?.optJSONObject("text")?.optString("*").orEmpty()
        val cleaned = parseWikisourceHtml(html)
        if (cleaned.isBlank()) {
            throw IllegalStateException("该维基文库页面暂无正文内容，可切换其他章节或书源")
        }
        return cleaned
    }

    internal fun parseWikisourceHtml(rawHtml: String): String {
        if (rawHtml.isBlank()) return ""
        val doc = Jsoup.parseBodyFragment(rawHtml)
        doc.select(
            "script, style, table, .mw-editsection, .ws-noexport, .noprint, " +
                ".prp-pagequality, .headerContainer, .footertemplate, #headerContainer, " +
                ".licenseContainer, sup.reference, .mw-empty-elt, .navigation-not-searchable"
        ).remove()
        doc.select("br").append("\n")
        val blocks = doc.select("p, dd, li, h2, h3, h4, div.poem")
        val paragraphs = if (blocks.isNotEmpty()) {
            blocks.map { it.text().trim() }.filter { it.length >= 2 }
        } else {
            doc.text().lines().map { it.trim() }.filter { it.isNotBlank() }
        }
        return formatCleanParagraphs(paragraphs.joinToString("\n\n"))
    }

    private fun wikisourceHeadersSource(source: NovelSource): NovelSource = source.copy(
        headers = source.headers + ("User-Agent" to "CineCastReader/1.4 (https://github.com/yxchen61/CineCast; Android)")
    )

    private fun titleSortBucket(name: String): Int = when {
        name.contains("序") || name.contains("凡例") || name.contains("引言") || name.contains("楔子") -> 0
        name.contains("附錄") || name.contains("附录") || name.contains("跋") || name.contains("后记") -> 2
        else -> 1
    }

    private fun extractChapterOrderNumber(name: String): Int {
        val digitMatch = Regex("""(\d+)""").find(name)
        if (digitMatch != null) return digitMatch.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE
        return Int.MAX_VALUE
    }

    // ==================== Gutendex 开源文学名著适配器 ====================

    private fun parseGutendexBooks(source: NovelSource, rawJson: String): List<NovelBook> {
        val obj = JSONObject(rawJson)
        val arr = obj.optJSONArray("results") ?: return emptyList()
        val books = mutableListOf<NovelBook>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val id = item.optInt("id", 0)
            val title = item.optString("title").trim()
            val formats = item.optJSONObject("formats") ?: continue
            val textUrl = findBestGutenbergTextUrl(formats) ?: continue
            val cover = formats.optString("image/jpeg").trim()
            val authorsArr = item.optJSONArray("authors")
            val author = buildString {
                if (authorsArr != null) {
                    for (a in 0 until authorsArr.length()) {
                        val name = authorsArr.optJSONObject(a)?.optString("name").orEmpty().trim()
                        if (name.isNotBlank()) {
                            if (isNotEmpty()) append(" / ")
                            append(name)
                        }
                    }
                }
            }.ifBlank { "公版经典" }
            val subjects = item.optJSONArray("subjects")?.let { sArr ->
                (0 until minOf(2, sArr.length())).mapNotNull { sArr.optString(it).takeIf { s -> s.isNotBlank() } }
                    .joinToString(" · ")
            }.orEmpty()
            val downloads = item.optInt("download_count", 0)

            books.add(
                NovelBook(
                    id = "gutendex_$id",
                    sourceId = source.id,
                    sourceName = source.name,
                    title = title,
                    author = author,
                    coverUrl = cover,
                    intro = "Project Gutenberg 公版开源名著 · 全球累计下载 $downloads 次" +
                        if (subjects.isNotBlank()) "\n分类主题：$subjects" else "",
                    kind = "开源名著",
                    latestChapter = "全文完结",
                    bookUrl = textUrl,
                    tocUrl = textUrl
                )
            )
        }
        return books
    }

    private fun findBestGutenbergTextUrl(formats: JSONObject): String? {
        val keys = listOf(
            "text/plain; charset=utf-8",
            "text/plain; charset=us-ascii",
            "text/plain"
        )
        for (k in keys) {
            val u = formats.optString(k).trim()
            if (u.startsWith("http") && !u.endsWith(".zip")) return u
        }
        val it = formats.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (k.startsWith("text/plain")) {
                val u = formats.optString(k).trim()
                if (u.startsWith("http") && !u.endsWith(".zip")) return u
            }
        }
        return null
    }

    private fun fetchGutendexChapters(book: NovelBook): List<NovelChapter> {
        gutenbergChaptersCache[book.bookUrl]?.let { return it }
        val raw = executeRequest(book.bookUrl, NovelSource(id = "gutendex", name = "Gutendex", baseUrl = "https://gutendex.com"))
        val cleaned = stripGutenbergBoilerplate(raw)
        val chapters = splitPlainTextIntoChapters(cleaned, book.bookUrl)
        gutenbergChaptersCache[book.bookUrl] = chapters
        return chapters
    }

    private fun stripGutenbergBoilerplate(text: String): String {
        val startMarker = Regex("""\*\*\*\s*START OF (THE|THIS) PROJECT GUTENBERG EBOOK.*?\*\*\*""", RegexOption.IGNORE_CASE)
        val endMarker = Regex("""\*\*\*\s*END OF (THE|THIS) PROJECT GUTENBERG EBOOK.*?\*\*\*""", RegexOption.IGNORE_CASE)
        val startIdx = startMarker.find(text)?.range?.last?.plus(1) ?: 0
        val endIdx = endMarker.find(text)?.range?.first ?: text.length
        return text.substring(startIdx.coerceIn(0, text.length), endIdx.coerceIn(startIdx, text.length)).trim()
    }

    fun splitPlainTextIntoChapters(fullText: String, baseUrl: String): List<NovelChapter> {
        val headingRegex = Regex(
            """^\s*(第[零一二三四五六七八九十百千万0-9]+[章回卷节部].*|CHAPTER\s+[IVXLCDM0-9]+.*|卷[零一二三四五六七八九十0-9]+.*)$""",
            setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)
        )
        val matches = headingRegex.findAll(fullText).toList()
        if (matches.size >= 3) {
            val list = mutableListOf<NovelChapter>()
            for (i in matches.indices) {
                val title = matches[i].value.trim().take(48)
                val start = matches[i].range.last + 1
                val end = if (i + 1 < matches.size) matches[i + 1].range.first else fullText.length
                val body = formatCleanParagraphs(fullText.substring(start, end))
                if (body.length >= 20) {
                    list.add(
                        NovelChapter(
                            index = list.size,
                            title = title,
                            url = "$baseUrl#ch_${list.size}",
                            inlineContent = body
                        )
                    )
                }
            }
            if (list.isNotEmpty()) return list
        }

        // Fallback: split evenly by ~3000 chars per chapter at paragraph boundaries
        val paragraphs = fullText.replace("\r\n", "\n").split(Regex("\n+")).map { it.trim() }.filter { it.isNotBlank() }
        val chapters = mutableListOf<NovelChapter>()
        val buf = StringBuilder()
        for (p in paragraphs) {
            if (buf.length > 2800) {
                val idx = chapters.size
                chapters.add(
                    NovelChapter(
                        index = idx,
                        title = "第 ${idx + 1} 节",
                        url = "$baseUrl#sec_$idx",
                        inlineContent = buf.toString().trim()
                    )
                )
                buf.clear()
            }
            buf.append(p).append("\n\n")
        }
        if (buf.isNotBlank()) {
            val idx = chapters.size
            chapters.add(
                NovelChapter(
                    index = idx,
                    title = "第 ${idx + 1} 节",
                    url = "$baseUrl#sec_$idx",
                    inlineContent = buf.toString().trim()
                )
            )
        }
        return chapters
    }

    // ==================== HTTP Request & Charset Helpers ====================

    internal fun expandUrlTemplate(template: String, keyword: String, page: Int, defaultCharset: String): String {
        val targetCharset = when {
            template.contains("gbk", ignoreCase = true) -> "GBK"
            template.contains("gb2312", ignoreCase = true) -> "GB2312"
            else -> defaultCharset.ifBlank { "UTF-8" }
        }
        val encodedKey = runCatching { URLEncoder.encode(keyword, targetCharset) }
            .getOrElse { URLEncoder.encode(keyword, "UTF-8") }

        // Handle Legado conditional page syntax: <page1Part,pageNPart>
        var expanded = Regex("""<([^,>]*),([^>]*)>""").replace(template) { m ->
            if (page <= 1) m.groupValues[1] else m.groupValues[2]
        }
        expanded = expanded
            .replace("{{key}}", encodedKey)
            .replace("{{page}}", page.toString())
        return expanded
    }

    private fun resolveSpecUrl(baseUrl: String, urlSpec: String): String {
        val rawUrl = splitUrlAndOptions(urlSpec).first
        return NovelRuleEngine.resolveUrl(baseUrl, rawUrl)
    }

    private fun splitUrlAndOptions(urlSpec: String): Pair<String, JSONObject?> {
        val commaBrace = urlSpec.indexOf(",{")
        val commaSpaceBrace = urlSpec.indexOf(", {")
        val splitIdx = when {
            commaBrace > 0 -> commaBrace
            commaSpaceBrace > 0 -> commaSpaceBrace
            else -> -1
        }
        if (splitIdx < 0) return urlSpec.trim() to null
        val urlPart = urlSpec.substring(0, splitIdx).trim()
        val optStr = urlSpec.substring(splitIdx + 1).trim()
        val optJson = runCatching {
            val normalized = if (optStr.contains("'") && !optStr.contains("\"")) {
                optStr.replace("'", "\"")
            } else optStr
            JSONObject(normalized)
        }.getOrNull()
        return urlPart to optJson
    }

    private fun executeRequest(urlSpec: String, source: NovelSource): String {
        val (rawUrl, options) = splitUrlAndOptions(urlSpec)
        val fullUrl = NovelRuleEngine.resolveUrl(source.baseUrl, rawUrl)
        val method = options?.optString("method")?.uppercase()?.ifBlank { "GET" } ?: "GET"
        val bodyStr = options?.optString("body").orEmpty()
        val charsetName = options?.optString("charset")?.ifBlank { source.charset } ?: source.charset

        val reqBuilder = Request.Builder()
            .url(fullUrl)
            .header("User-Agent", source.headers["User-Agent"] ?: NetworkClient.DEFAULT_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")

        source.headers.forEach { (k, v) ->
            if (!k.equals("User-Agent", ignoreCase = true)) reqBuilder.header(k, v)
        }
        options?.optJSONObject("headers")?.let { hObj ->
            hObj.keys().forEach { k ->
                val v = hObj.optString(k)
                if (v.isNotBlank()) reqBuilder.header(k, v)
            }
        }

        if (method == "POST") {
            val mediaType = if (bodyStr.trimStart().startsWith("{")) {
                "application/json; charset=utf-8".toMediaType()
            } else {
                "application/x-www-form-urlencoded; charset=$charsetName".toMediaType()
            }
            reqBuilder.post(bodyStr.toRequestBody(mediaType))
        }

        NetworkClient.client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}")
            }
            val bytes = resp.body?.bytes() ?: return ""
            val contentType = resp.header("Content-Type").orEmpty()
            return decodeResponseBytes(bytes, contentType, charsetName)
        }
    }

    private fun decodeResponseBytes(bytes: ByteArray, contentType: String, hintCharset: String): String {
        val headerCharset = Regex("""charset=([A-Za-z0-9\-_]+)""", RegexOption.IGNORE_CASE)
            .find(contentType)?.groupValues?.get(1)
        val headAscii = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
        val metaCharset = Regex("""charset=["']?([A-Za-z0-9\-_]+)""", RegexOption.IGNORE_CASE)
            .find(headAscii)?.groupValues?.get(1)

        val chosen = (headerCharset ?: metaCharset ?: hintCharset).uppercase()
        val charset = when {
            chosen.contains("GB") -> runCatching { Charset.forName("GB18030") }.getOrDefault(Charsets.UTF_8)
            else -> runCatching { Charset.forName(chosen) }.getOrDefault(Charsets.UTF_8)
        }
        return String(bytes, charset)
    }

    private fun formatCleanParagraphs(raw: String): String {
        return raw.replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u00A0', ' ')
            .replace('\u3000', ' ')
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }

    // ==================== 五千言中华古典名著馆 (5000yan.com 国内 36ms 直连) ====================

    private data class Yan5000BookEntry(
        val title: String,
        val subdomain: String,
        val author: String,
        val kind: String,
        val chaptersHint: String,
        val intro: String
    )

    private val yan5000Catalog: Map<String, List<Yan5000BookEntry>> = mapOf(
        "yan5000_cat://sida" to listOf(
            Yan5000BookEntry("三国演义", "sanguo", "罗贯中", "四大名著", "全 120 回", "东汉末年群雄逐鹿，魏蜀吴三国鼎立波澜壮阔的历史演义（国内直连·简体全本）。"),
            Yan5000BookEntry("红楼梦", "hongloumeng", "曹雪芹", "四大名著", "全 120 回", "中国古典长篇小说巅峰之作，以贾宝玉、林黛玉、薛宝钗情感与贾府兴衰映照世情百态。"),
            Yan5000BookEntry("西游记", "xiyouji", "吴承恩", "四大名著", "全 100 回", "唐僧师徒四人历经九九八十一难、降妖伏魔西天取经的神魔古典名著。"),
            Yan5000BookEntry("水浒传", "shuihu", "施耐庵", "四大名著", "全 120 回", "北宋末年梁山泊一百零八位好汉聚义反抗贪官污吏的英雄传奇。")
        ),
        "yan5000_cat://classical" to listOf(
            Yan5000BookEntry("封神演义", "fengshen", "许仲琳", "古典神魔", "全 100 回", "商周更替背景下姜子牙辅佐周武王伐纣、诸仙斗法封神的瑰丽神魔史诗。"),
            Yan5000BookEntry("聊斋志异", "liaozhai", "蒲松龄", "文言志怪", "全 494 篇", "写鬼写妖高人一等、刺贪刺虐入骨三分的中国文言短篇小说巅峰巨著。"),
            Yan5000BookEntry("儒林外史", "rulin", "吴敬梓", "古典讽刺", "全 56 回", "深刻描摹科举制度下古代知识分子命运与世态人情的讽刺小说典范。"),
            Yan5000BookEntry("隋唐演义", "suitang", "褚人获", "历史演义", "全 100 回", "讲述瓦岗寨群雄并起、秦琼程咬金等好汉辅佐大唐开国的英雄历史演义。"),
            Yan5000BookEntry("镜花缘", "jinghuayuan", "李汝珍", "奇幻古典", "全 100 回", "融合海外奇国游记、才女科举与神魔幻想的清代奇书。"),
            Yan5000BookEntry("官场现形记", "guanchang", "李伯元", "晚清谴责", "全 60 回", "晚清四大谴责小说之首，辛辣揭示旧官场众生相。")
        ),
        "yan5000_cat://history" to listOf(
            Yan5000BookEntry("战国策", "zhanguoce", "刘向 编订", "史学谋略", "全 147 篇", "先秦纵横家游说辞令与政治外交智慧的巅峰典籍。"),
            Yan5000BookEntry("搜神记", "soushenji", "干宝", "志怪神话", "全 463 篇", "东晋志怪小说集大成之作，收录干将莫邪、董永七仙女等众多中华神话典故。")
        ),
        "yan5000_cat://guoxue" to listOf(
            Yan5000BookEntry("论语", "lunyu", "孔子及其弟子", "儒家经典", "全 323 篇", "儒家核心经典，记录孔子及其弟子言行，附简体原文与注释。"),
            Yan5000BookEntry("菜根谭", "caigentan", "洪应明", "处世修身", "全 352 篇", "融汇儒释道三家智慧的明代格言警句集，咀嚼菜根体味人生真谛。"),
            Yan5000BookEntry("唐诗三百首", "tangshi", "蘅塘退士 编", "古典诗词", "精选全集", "收录李白、杜甫、王维、白居易等唐代诗坛名家传世名篇。"),
            Yan5000BookEntry("宋词三百首", "songci", "朱孝臧 编", "古典诗词", "精选全集", "汇集苏轼、辛弃疾、李清照、柳永、晏殊等两宋词坛巅峰佳作。"),
            Yan5000BookEntry("诗经", "shijing", "先秦民歌与雅颂", "风雅经典", "全 270 篇", "中国最早的诗歌总集，风、雅、颂三部分展现先秦社会生活画卷。"),
            Yan5000BookEntry("楚辞", "chuci", "屈原 等", "浪漫诗祖", "全 45 篇", "收录《离骚》《九歌》《天问》等中国浪漫主义诗歌源头名篇。"),
            Yan5000BookEntry("三字经", "sanzijing", "王应麟", "国学蒙学", "全 142 节", "中华传统启蒙经典，三字一句囊括历史、伦理、天文与文化常识。")
        )
    )

    private fun Yan5000BookEntry.toNovelBook(source: NovelSource): NovelBook {
        val url = "https://$subdomain.5000yan.com/"
        return NovelBook(
            id = "yan5000_$subdomain",
            sourceId = source.id,
            sourceName = source.name,
            title = title,
            author = author,
            intro = intro,
            kind = kind,
            latestChapter = chaptersHint,
            bookUrl = url,
            tocUrl = url
        )
    }

    private fun getCurated5000yanBooks(source: NovelSource, categoryUrl: String): List<NovelBook> {
        val entries = yan5000Catalog[categoryUrl] ?: yan5000Catalog.values.flatten()
        return entries.map { it.toNovelBook(source) }
    }

    private fun search5000yanBooks(source: NovelSource, keyword: String): List<NovelBook> {
        val q = keyword.trim().lowercase()
        return yan5000Catalog.values.flatten()
            .filter {
                it.title.lowercase().contains(q) ||
                    it.author.lowercase().contains(q) ||
                    it.kind.lowercase().contains(q) ||
                    it.intro.lowercase().contains(q)
            }
            .map { it.toNovelBook(source) }
    }

    companion object {
        private val yan5000ChapterUrlRegex = Regex("""^https?://[a-z0-9-]+\.5000yan\.com/\d+\.html$""", RegexOption.IGNORE_CASE)

        fun parse5000yanToc(html: String, baseUrl: String): List<NovelChapter> {
            val doc = Jsoup.parse(html, baseUrl)
            val seen = LinkedHashSet<String>()
            val chapters = mutableListOf<NovelChapter>()
            for (a in doc.select("a[href]")) {
                val absUrl = a.absUrl("href").trim()
                val title = a.text().trim()
                if (title.isBlank() || absUrl in seen) continue
                if (!yan5000ChapterUrlRegex.matches(absUrl)) continue
                seen.add(absUrl)
                chapters.add(NovelChapter(index = chapters.size, title = title, url = absUrl))
            }
            return chapters
        }

        fun parse5000yanChapterHtml(html: String): String {
            val doc = Jsoup.parse(html)
            doc.select("script, style, nav, header, footer, .ad, .ads, .breadcrumb").remove()
            val container = doc.selectFirst("article.reading-content")
                ?: doc.selectFirst(".grap")
                ?: doc.selectFirst("article")
                ?: doc.selectFirst(".content")
                ?: return ""
            val paragraphs = container.select("p")
                .map { it.text().trim() }
                .filter { it.isNotBlank() && !it.startsWith("上一") && !it.startsWith("下一") }
            return if (paragraphs.isNotEmpty()) {
                paragraphs.joinToString("\n\n")
            } else {
                NovelRuleEngine.htmlToParagraphs(container.html())
            }
        }
    }
}
