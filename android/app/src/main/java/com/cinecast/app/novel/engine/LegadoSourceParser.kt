package com.cinecast.app.novel.engine

import com.cinecast.app.data.network.NetworkClient
import com.cinecast.app.novel.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class NovelImportResult(
    val subscription: NovelSubscription,
    val sources: List<NovelSource>,
    val totalRawCount: Int,
    val skippedJsOnlyCount: Int
)

/**
 * 开源 Legado（阅读 3.0）书源 JSON 解析器
 * 兼容 GitHub 上主流书源仓库：XIU2/Yuedu、liufuyou/read、openrepos/booksource 等
 */
class LegadoSourceParser {

    suspend fun importFromUrl(url: String, customName: String = ""): NovelImportResult =
        withContext(Dispatchers.IO) {
            val raw = NetworkClient.getString(url)
            parseJsonText(raw, url, customName)
        }

    fun parseJsonText(rawText: String, sourceUrl: String = "", customName: String = ""): NovelImportResult {
        val trimmed = rawText.trim().removePrefix("\uFEFF")
        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> JSONArray().put(JSONObject(trimmed))
            else -> throw IllegalArgumentException("书源内容不是合法的 Legado JSON 数组或对象")
        }

        val parsed = mutableListOf<NovelSource>()
        var skippedJsOnly = 0

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            // bookSourceType: 0 = 文本小说, 1 = 音频, 2 = 图片/漫画, 3 = 文件
            val bsType = obj.optInt("bookSourceType", 0)
            if (bsType != 0) continue

            val name = obj.optString("bookSourceName").trim()
            val baseUrl = obj.optString("bookSourceUrl").trim().substringBefore("#").trimEnd('/')
            if (name.isBlank() || !baseUrl.startsWith("http")) continue

            val searchUrl = obj.optString("searchUrl").trim()
            val exploreUrl = obj.optString("exploreUrl").trim()

            // Skip sources whose searchUrl AND exploreUrl are both purely dynamic JS scripts
            val searchIsPureJs = searchUrl.startsWith("@js:") || searchUrl.startsWith("<js>")
            val exploreIsPureJs = exploreUrl.startsWith("@js:") || exploreUrl.startsWith("<js>")
            val rsObj = obj.optJSONObject("ruleSearch")
            val rcObj = obj.optJSONObject("ruleContent")
            val contentIsPureJs = rcObj?.optString("content").orEmpty().trim().let {
                it.startsWith("@js:") || it.startsWith("<js>")
            }
            if ((searchIsPureJs && (exploreUrl.isBlank() || exploreIsPureJs)) || contentIsPureJs) {
                skippedJsOnly++
                continue
            }

            val ruleSearch = parseRuleSearch(rsObj)
            val ruleBookInfo = parseRuleBookInfo(obj.optJSONObject("ruleBookInfo"))
            val ruleToc = parseRuleToc(obj.optJSONObject("ruleToc"))
            val ruleContent = parseRuleContent(rcObj)

            val isJsonApi = ruleSearch.bookList.trim().startsWith("$") ||
                ruleToc.chapterList.trim().startsWith("$") ||
                ruleContent.content.trim().startsWith("$")

            val headers = parseHeaderField(obj.optString("header"))
            val charset = detectCharsetHint(searchUrl)
            val group = obj.optString("bookSourceGroup").trim().ifBlank {
                if (isJsonApi) "API 书源" else "GitHub 导入书源"
            }

            parsed.add(
                NovelSource(
                    id = "legado_${shortHash(baseUrl + "|" + name)}",
                    name = name,
                    baseUrl = baseUrl,
                    group = group,
                    sourceType = if (isJsonApi) NovelSourceType.LEGADO_JSON else NovelSourceType.LEGADO_HTML,
                    enabled = obj.optBoolean("enabled", true),
                    searchable = searchUrl.isNotBlank() && !searchIsPureJs && searchUrl != "-",
                    searchUrl = if (searchIsPureJs) "" else searchUrl,
                    exploreUrl = if (exploreIsPureJs) "" else exploreUrl,
                    headers = headers,
                    charset = charset,
                    comment = obj.optString("bookSourceComment").trim().take(240),
                    subscriptionUrl = sourceUrl,
                    ruleSearch = ruleSearch,
                    ruleBookInfo = ruleBookInfo,
                    ruleToc = ruleToc,
                    ruleContent = ruleContent
                )
            )
        }

        val subName = customName.ifBlank {
            when {
                sourceUrl.contains("XIU2/Yuedu", ignoreCase = true) -> "XIU2/Yuedu 精选书源"
                sourceUrl.contains("yck-novel", ignoreCase = true) -> "liufuyou/read YCK 小说书源"
                sourceUrl.contains("yiove-novel", ignoreCase = true) -> "liufuyou/read Yiove 书源"
                sourceUrl.contains("openrepos", ignoreCase = true) -> "openrepos 通用书源"
                sourceUrl.isNotBlank() -> sourceUrl.substringAfter("://").substringBefore("/")
                else -> "本地粘贴书源"
            }
        }

        val sub = NovelSubscription(
            id = "nsub_${shortHash(sourceUrl.ifBlank { subName })}",
            name = subName,
            url = sourceUrl,
            lastSyncedAt = System.currentTimeMillis(),
            importedCount = parsed.size
        )

        return NovelImportResult(
            subscription = sub,
            sources = parsed.distinctBy { it.baseUrl + "|" + it.name },
            totalRawCount = array.length(),
            skippedJsOnlyCount = skippedJsOnly
        )
    }

    fun parseExploreCategories(exploreUrl: String): List<NovelExploreCategory> {
        val trimmed = exploreUrl.trim()
        if (trimmed.isBlank() || trimmed.startsWith("@js:") || trimmed.startsWith("<js>")) {
            return emptyList()
        }
        val list = mutableListOf<NovelExploreCategory>()
        if (trimmed.startsWith("[")) {
            runCatching {
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val title = item.optString("title").trim()
                    val url = item.optString("url").trim()
                    if (title.isNotBlank() && url.isNotBlank()) {
                        list.add(NovelExploreCategory(title, url))
                    }
                }
            }
            if (list.isNotEmpty()) return list
        }
        // Standard Legado line format: 标题::URL
        trimmed.lines().forEach { line ->
            val parts = line.trim().split("::", limit = 2)
            if (parts.size == 2) {
                val title = parts[0].trim()
                val url = parts[1].trim()
                if (title.isNotBlank() && url.isNotBlank()) {
                    list.add(NovelExploreCategory(title, url))
                }
            }
        }
        return list
    }

    private fun parseRuleSearch(obj: JSONObject?): NovelRuleSearch {
        if (obj == null) return NovelRuleSearch()
        return NovelRuleSearch(
            bookList = cleanRule(obj.optString("bookList")),
            name = cleanRule(obj.optString("name")),
            author = cleanRule(obj.optString("author")),
            bookUrl = cleanRule(obj.optString("bookUrl")),
            coverUrl = cleanRule(obj.optString("coverUrl")),
            intro = cleanRule(obj.optString("intro")),
            kind = cleanRule(obj.optString("kind")),
            lastChapter = cleanRule(obj.optString("lastChapter")),
            wordCount = cleanRule(obj.optString("wordCount"))
        )
    }

    private fun parseRuleBookInfo(obj: JSONObject?): NovelRuleBookInfo {
        if (obj == null) return NovelRuleBookInfo()
        return NovelRuleBookInfo(
            init = cleanRule(obj.optString("init")),
            name = cleanRule(obj.optString("name")),
            author = cleanRule(obj.optString("author")),
            coverUrl = cleanRule(obj.optString("coverUrl")),
            intro = cleanRule(obj.optString("intro")),
            kind = cleanRule(obj.optString("kind")),
            lastChapter = cleanRule(obj.optString("lastChapter")),
            tocUrl = cleanRule(obj.optString("tocUrl"))
        )
    }

    private fun parseRuleToc(obj: JSONObject?): NovelRuleToc {
        if (obj == null) return NovelRuleToc()
        return NovelRuleToc(
            chapterList = cleanRule(obj.optString("chapterList")),
            chapterName = cleanRule(obj.optString("chapterName")),
            chapterUrl = cleanRule(obj.optString("chapterUrl")),
            nextTocUrl = cleanRule(obj.optString("nextTocUrl"))
        )
    }

    private fun parseRuleContent(obj: JSONObject?): NovelRuleContent {
        if (obj == null) return NovelRuleContent()
        return NovelRuleContent(
            content = cleanRule(obj.optString("content")),
            nextContentUrl = cleanRule(obj.optString("nextContentUrl")),
            replaceRegex = obj.optString("replaceRegex").trim()
        )
    }

    /**
     * 剥离末尾可选的 @js: 后处理脚本，保留前置声明式规则，提升对社区书源的兼容率
     */
    private fun cleanRule(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        val jsIdx = trimmed.indexOf("@js:")
        if (jsIdx > 0) return trimmed.substring(0, jsIdx).trim()
        val tagJsIdx = trimmed.indexOf("<js>")
        if (tagJsIdx > 0) return trimmed.substring(0, tagJsIdx).trim()
        if (jsIdx == 0 || tagJsIdx == 0) return ""
        return trimmed
    }

    private fun parseHeaderField(headerRaw: String): Map<String, String> {
        val cleaned = headerRaw.trim()
        if (cleaned.isBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        runCatching {
            val normalized = if (cleaned.startsWith("{") && cleaned.contains("'")) {
                cleaned.replace("'", "\"")
            } else cleaned
            val obj = JSONObject(normalized)
            obj.keys().forEach { key ->
                val v = obj.optString(key).trim()
                if (v.isNotBlank()) map[key] = v
            }
        }
        return map
    }

    private fun detectCharsetHint(searchUrl: String): String {
        val lower = searchUrl.lowercase()
        return when {
            lower.contains("gbk") -> "GBK"
            lower.contains("gb2312") -> "GB2312"
            lower.contains("gb18030") -> "GB18030"
            else -> "UTF-8"
        }
    }

    private fun shortHash(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.take(6).joinToString("") { "%02x".format(it) }
    }
}
