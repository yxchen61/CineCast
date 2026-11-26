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
        if (source.sourceType == NovelSourceType.GUTENDEX_API) {
            return listOf(
                NovelExploreCategory("中文经典名著", "https://gutendex.com/books/?languages=zh&page={{page}}"),
                NovelExploreCategory("世界热门文学", "https://gutendex.com/books/?sort=popular&page={{page}}"),
                NovelExploreCategory("科幻与冒险", "https://gutendex.com/books/?topic=science+fiction&page={{page}}"),
                NovelExploreCategory("侦探与悬疑", "https://gutendex.com/books/?topic=detective&page={{page}}"),
                NovelExploreCategory("历史与传记", "https://gutendex.com/books/?topic=history&page={{page}}")
            )
        }
        return legadoParser.parseExploreCategories(source.exploreUrl)
    }

    suspend fun fetchExplore(
        source: NovelSource,
        categoryUrl: String,
        page: Int = 1
    ): List<NovelBook> = withContext(Dispatchers.IO) {
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
}
