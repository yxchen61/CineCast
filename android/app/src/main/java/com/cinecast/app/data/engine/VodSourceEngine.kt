package com.cinecast.app.data.engine

import android.net.Uri
import android.util.Xml
import com.cinecast.app.data.model.*
import com.cinecast.app.data.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URLEncoder

data class VodPageResult(
    val items: List<VodItem>,
    val categories: List<VodCategory> = emptyList(),
    val page: Int = 1,
    val pageCount: Int = 1
)

class VodSourceEngine {

    suspend fun fetchVodList(
        source: VideoSource,
        categoryId: String? = null,
        page: Int = 1,
        childCategoryIds: List<String> = emptyList()
    ): VodPageResult = withContext(Dispatchers.IO) {
        when (source.type) {
            SourceType.MACCMS_JSON -> fetchMacCmsJsonList(source, categoryId, page, childCategoryIds)
            SourceType.MACCMS_XML -> fetchMacCmsXmlList(source, categoryId, page)
            SourceType.T4_API -> fetchT4List(source, categoryId, page)
            else -> VodPageResult(emptyList())
        }
    }

    suspend fun search(
        source: VideoSource,
        keyword: String,
        page: Int = 1
    ): List<VodItem> = withContext(Dispatchers.IO) {
        val trimmed = keyword.trim()
        if (trimmed.isBlank()) return@withContext emptyList()
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        when (source.type) {
            SourceType.MACCMS_JSON -> {
                val detailRes = runCatching {
                    val body = NetworkClient.getString(appendQuery(source.apiUrl, "ac=detail&wd=$encoded&pg=$page"), source.headers)
                    parseMacCmsJsonItems(body, source).items
                }.getOrDefault(emptyList())
                if (detailRes.isNotEmpty()) return@withContext detailRes

                val videoListRes = runCatching {
                    val body = NetworkClient.getString(appendQuery(source.apiUrl, "ac=videolist&wd=$encoded&pg=$page"), source.headers)
                    parseMacCmsJsonItems(body, source).items
                }.getOrDefault(emptyList())
                if (videoListRes.isNotEmpty()) return@withContext videoListRes

                // Final fallback: ac=list&wd= then hydrate posters via ac=detail&ids=
                val listRes = runCatching {
                    val body = NetworkClient.getString(appendQuery(source.apiUrl, "ac=list&wd=$encoded&pg=$page"), source.headers)
                    parseMacCmsJsonItems(body, source).items
                }.getOrDefault(emptyList())
                if (listRes.isEmpty()) return@withContext emptyList()

                val ids = listRes.take(20).joinToString(",") { it.id }
                val hydrated = runCatching {
                    val body = NetworkClient.getString(appendQuery(source.apiUrl, "ac=detail&ids=$ids"), source.headers)
                    parseMacCmsJsonItems(body, source).items
                }.getOrDefault(emptyList())
                if (hydrated.isNotEmpty()) hydrated else listRes
            }
            SourceType.MACCMS_XML -> {
                val url = appendQuery(source.apiUrl, "ac=videolist&wd=$encoded&pg=$page")
                val body = NetworkClient.getString(url, source.headers)
                parseMacCmsXml(body, source).items
            }
            SourceType.T4_API -> {
                val url = appendQuery(source.apiUrl, "wd=$encoded&pg=$page")
                val body = NetworkClient.getString(url, source.headers)
                parseMacCmsJsonItems(body, source).items
            }
            else -> emptyList()
        }
    }

    suspend fun fetchDetail(
        source: VideoSource,
        vodId: String
    ): VodDetail = withContext(Dispatchers.IO) {
        when (source.type) {
            SourceType.MACCMS_JSON -> {
                val url = appendQuery(source.apiUrl, "ac=detail&ids=$vodId")
                val body = NetworkClient.getString(url, source.headers)
                parseMacCmsJsonDetail(body, source)
            }
            SourceType.MACCMS_XML -> {
                val url = appendQuery(source.apiUrl, "ac=videolist&ids=$vodId")
                val body = NetworkClient.getString(url, source.headers)
                parseMacCmsXmlDetail(body, source)
            }
            SourceType.T4_API -> {
                val url = appendQuery(source.apiUrl, "ac=detail&ids=$vodId")
                val body = NetworkClient.getString(url, source.headers)
                parseMacCmsJsonDetail(body, source)
            }
            else -> throw IllegalStateException("该源类型为 ${source.type.displayName}，请通过网页嗅探打开")
        }
    }

    suspend fun pingSource(source: VideoSource): Long = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        return@withContext try {
            val testUrl = when (source.type) {
                SourceType.MACCMS_JSON -> appendQuery(source.apiUrl, "ac=list&pg=1")
                SourceType.MACCMS_XML -> appendQuery(source.apiUrl, "ac=list&pg=1")
                SourceType.T4_API -> appendQuery(source.apiUrl, "ac=list&pg=1")
                SourceType.WEB_SNIFFER -> source.apiUrl
                SourceType.TVBOX_SPIDER -> return@withContext -2L
            }
            val body = NetworkClient.getString(testUrl, source.headers)
            if (body.isNotBlank()) System.currentTimeMillis() - start else -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private suspend fun fetchMacCmsJsonList(
        source: VideoSource,
        categoryId: String?,
        page: Int,
        childCategoryIds: List<String> = emptyList()
    ): VodPageResult = coroutineScope {
        val catsDeferred: kotlinx.coroutines.Deferred<List<VodCategory>>? = if (page == 1 && categoryId.isNullOrBlank()) {
            async(Dispatchers.IO) {
                runCatching {
                    val catBody = NetworkClient.getString(appendQuery(source.apiUrl, "ac=list&pg=1"), source.headers)
                    parseMacCmsCategories(catBody, source)
                }.getOrDefault(emptyList())
            }
        } else null

        // If a parent container category (like "电影"/"电视剧" with type_pid=0) has children,
        // MacCMS stores videos in the child categories, so aggregate them in parallel!
        if (childCategoryIds.isNotEmpty()) {
            val targets = childCategoryIds.take(4)
            val pages = targets.map { cid ->
                async(Dispatchers.IO) {
                    runCatching {
                        val b = NetworkClient.getString(appendQuery(source.apiUrl, "ac=detail&pg=$page&t=$cid"), source.headers)
                        parseMacCmsJsonItems(b, source)
                    }.getOrNull()
                }
            }.mapNotNull { it.await() }

            // Interleave items from child categories so variety is rich
            val interleaved = mutableListOf<VodItem>()
            val maxSize = pages.maxOfOrNull { it.items.size } ?: 0
            for (i in 0 until maxSize) {
                for (p in pages) {
                    p.items.getOrNull(i)?.let { interleaved.add(it) }
                }
            }
            val deduped = interleaved.distinctBy { it.id }
            val maxPageCount = pages.maxOfOrNull { it.pageCount } ?: 1
            return@coroutineScope VodPageResult(
                items = deduped,
                categories = catsDeferred?.await().orEmpty(),
                page = page,
                pageCount = maxPageCount
            )
        }

        val q = buildString {
            append("ac=detail&pg=").append(page)
            if (!categoryId.isNullOrBlank()) append("&t=").append(categoryId)
        }
        val body = NetworkClient.getString(appendQuery(source.apiUrl, q), source.headers)
        val parsed = parseMacCmsJsonItems(body, source)
        val categories = catsDeferred?.await().orEmpty()
        val mergedCategories = if (categories.isNotEmpty()) categories else parsed.categories
        parsed.copy(categories = mergedCategories)
    }

    private suspend fun fetchMacCmsXmlList(
        source: VideoSource,
        categoryId: String?,
        page: Int
    ): VodPageResult = coroutineScope {
        val catsDeferred: kotlinx.coroutines.Deferred<List<VodCategory>>? = if (page == 1) {
            async(Dispatchers.IO) {
                runCatching {
                    val catBody = NetworkClient.getString(appendQuery(source.apiUrl, "ac=list&pg=1"), source.headers)
                    parseMacCmsXml(catBody, source).categories
                }.getOrDefault(emptyList())
            }
        } else null

        val q = buildString {
            append("ac=videolist&pg=").append(page)
            if (!categoryId.isNullOrBlank()) append("&t=").append(categoryId)
        }
        val body = NetworkClient.getString(appendQuery(source.apiUrl, q), source.headers)
        val parsed = parseMacCmsXml(body, source)
        val categories = catsDeferred?.await().orEmpty()
        parsed.copy(categories = if (categories.isNotEmpty()) categories else parsed.categories)
    }

    private fun fetchT4List(
        source: VideoSource,
        categoryId: String?,
        page: Int
    ): VodPageResult {
        val q = if (categoryId.isNullOrBlank()) {
            "ac=list&pg=$page"
        } else {
            "ac=detail&t=$categoryId&pg=$page"
        }
        val body = NetworkClient.getString(appendQuery(source.apiUrl, q), source.headers)
        return parseMacCmsJsonItems(body, source)
    }

    private fun parseMacCmsCategories(raw: String, source: VideoSource): List<VodCategory> {
        val obj = JSONObject(raw.trim())
        val arr = obj.optJSONArray("class") ?: return emptyList()
        val rawList = mutableListOf<VodCategory>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val id = c.optString("type_id").ifBlank { c.optInt("type_id").toString() }
            val pid = c.optString("type_pid").ifBlank { c.optInt("type_pid", 0).toString() }
            val name = c.optString("type_name").trim()
            if (id.isNotBlank() && name.isNotBlank()) {
                rawList.add(VodCategory(id = id, name = name, pid = pid))
            }
        }
        val safeList = filterCategories(rawList, source.categories)
        val childrenByPid = safeList.filter { it.pid != "0" && it.pid.isNotBlank() }.groupBy { it.pid }
        return safeList.map { cat ->
            val children = childrenByPid[cat.id].orEmpty().map { it.id }
            if (children.isNotEmpty()) cat.copy(childIds = children) else cat
        }
    }

    private fun parseMacCmsJsonItems(raw: String, source: VideoSource): VodPageResult {
        val trimmed = raw.trim()
        if (trimmed.startsWith("<")) {
            return parseMacCmsXml(trimmed, source)
        }
        val obj = JSONObject(trimmed)
        val categories = parseMacCmsCategories(trimmed, source)
        val listArr = obj.optJSONArray("list") ?: JSONArray()
        val items = mutableListOf<VodItem>()
        for (i in 0 until listArr.length()) {
            val v = listArr.optJSONObject(i) ?: continue
            val id = v.optString("vod_id").ifBlank { v.optLong("vod_id").toString() }
            val name = v.optString("vod_name").trim()
            if (id.isBlank() || name.isBlank()) continue
            items.add(
                VodItem(
                    id = id,
                    name = name,
                    pic = fixImageUrl(v.optString("vod_pic")),
                    remarks = v.optString("vod_remarks").trim(),
                    year = v.optString("vod_year").trim(),
                    area = v.optString("vod_area").trim(),
                    typeName = v.optString("type_name").trim(),
                    sourceId = source.id,
                    sourceName = source.name,
                    score = v.optString("vod_douban_score").ifBlank { v.optString("vod_score") }.trim()
                )
            )
        }
        val page = obj.optString("page").toIntOrNull() ?: obj.optInt("page", 1)
        val pageCount = obj.optString("pagecount").toIntOrNull() ?: obj.optInt("pagecount", 1)
        return VodPageResult(items, categories, page, pageCount)
    }

    private fun parseMacCmsJsonDetail(raw: String, source: VideoSource): VodDetail {
        val trimmed = raw.trim()
        if (trimmed.startsWith("<")) {
            return parseMacCmsXmlDetail(trimmed, source)
        }
        val obj = JSONObject(trimmed)
        val listArr = obj.optJSONArray("list") ?: throw IllegalStateException("未找到影片详情")
        val v = listArr.optJSONObject(0) ?: throw IllegalStateException("影片数据为空")
        val playFrom = v.optString("vod_play_from")
        val playUrl = v.optString("vod_play_url")
        val playSources = parsePlaySources(playFrom, playUrl)
        return VodDetail(
            id = v.optString("vod_id").ifBlank { v.optLong("vod_id").toString() },
            name = v.optString("vod_name").trim(),
            pic = fixImageUrl(v.optString("vod_pic")),
            remarks = v.optString("vod_remarks").trim(),
            year = v.optString("vod_year").trim(),
            area = v.optString("vod_area").trim(),
            actor = v.optString("vod_actor").trim(),
            director = v.optString("vod_director").trim(),
            content = cleanHtml(v.optString("vod_content").ifBlank { v.optString("vod_blurb") }),
            typeName = v.optString("type_name").trim(),
            sourceId = source.id,
            sourceName = source.name,
            playSources = playSources
        )
    }

    private fun parseMacCmsXml(raw: String, source: VideoSource): VodPageResult {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(raw.trim()))
        val items = mutableListOf<VodItem>()
        val categories = mutableListOf<VodCategory>()
        var page = 1
        var pageCount = 1

        var eventType = parser.eventType
        var inVideo = false
        var id = ""
        var name = ""
        var pic = ""
        var remarks = ""
        var year = ""
        var area = ""
        var typeName = ""
        var currentTag = ""

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    when (currentTag) {
                        "list" -> {
                            page = parser.getAttributeValue(null, "page")?.toIntOrNull() ?: 1
                            pageCount = parser.getAttributeValue(null, "pagecount")?.toIntOrNull() ?: 1
                        }
                        "ty" -> {
                            val tyId = parser.getAttributeValue(null, "id").orEmpty()
                            val tyName = parser.nextText().trim()
                            if (tyId.isNotBlank() && tyName.isNotBlank()) {
                                categories.add(VodCategory(tyId, tyName))
                            }
                        }
                        "video" -> {
                            inVideo = true
                            id = ""; name = ""; pic = ""; remarks = ""; year = ""; area = ""; typeName = ""
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inVideo) {
                        val text = parser.text.orEmpty().trim()
                        if (text.isNotEmpty()) {
                            when (currentTag) {
                                "id" -> id = text
                                "name" -> name = text
                                "pic" -> pic = fixImageUrl(text)
                                "note" -> remarks = text
                                "year" -> year = text
                                "area" -> area = text
                                "type" -> typeName = text
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "video" && inVideo) {
                        if (id.isNotBlank() && name.isNotBlank()) {
                            items.add(
                                VodItem(
                                    id = id,
                                    name = name,
                                    pic = pic,
                                    remarks = remarks,
                                    year = year,
                                    area = area,
                                    typeName = typeName,
                                    sourceId = source.id,
                                    sourceName = source.name
                                )
                            )
                        }
                        inVideo = false
                    }
                    currentTag = ""
                }
            }
            eventType = parser.next()
        }
        return VodPageResult(items, filterCategories(categories, source.categories), page, pageCount)
    }

    private fun parseMacCmsXmlDetail(raw: String, source: VideoSource): VodDetail {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(raw.trim()))
        var eventType = parser.eventType
        var id = ""
        var name = ""
        var pic = ""
        var remarks = ""
        var year = ""
        var area = ""
        var actor = ""
        var director = ""
        var content = ""
        var typeName = ""
        val playSources = mutableListOf<VodPlaySource>()
        var currentTag = ""
        var currentDdFlag = ""

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    if (currentTag == "dd") {
                        currentDdFlag = parser.getAttributeValue(null, "flag").orEmpty().ifBlank { "默认线路" }
                        val urlsText = parser.nextText().trim()
                        val eps = parseEpisodeList(urlsText)
                        if (eps.isNotEmpty()) {
                            playSources.add(VodPlaySource(currentDdFlag, eps))
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text.orEmpty().trim()
                    if (text.isNotEmpty()) {
                        when (currentTag) {
                            "id" -> id = text
                            "name" -> name = text
                            "pic" -> pic = fixImageUrl(text)
                            "note" -> remarks = text
                            "year" -> year = text
                            "area" -> area = text
                            "actor" -> actor = text
                            "director" -> director = text
                            "des" -> content = cleanHtml(text)
                            "type" -> typeName = text
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    currentTag = ""
                }
            }
            eventType = parser.next()
        }
        if (name.isBlank()) throw IllegalStateException("XML 详情解析失败")
        return VodDetail(
            id = id,
            name = name,
            pic = pic,
            remarks = remarks,
            year = year,
            area = area,
            actor = actor,
            director = director,
            content = content,
            typeName = typeName,
            sourceId = source.id,
            sourceName = source.name,
            playSources = sortPlaySources(playSources)
        )
    }

    private fun parsePlaySources(playFrom: String, playUrl: String): List<VodPlaySource> {
        if (playUrl.isBlank()) return emptyList()
        val fromList = playFrom.split("$$$").map { it.trim() }
        val urlGroups = playUrl.split("$$$")
        val result = mutableListOf<VodPlaySource>()
        urlGroups.forEachIndexed { idx, group ->
            val episodes = parseEpisodeList(group)
            if (episodes.isNotEmpty()) {
                val flagName = fromList.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: "线路${idx + 1}"
                result.add(VodPlaySource(flagName, episodes))
            }
        }
        return sortPlaySources(result)
    }

    private fun parseEpisodeList(group: String): List<VodEpisode> {
        return group.split("#").mapNotNull { entry ->
            val trimmed = entry.trim()
            if (trimmed.isBlank()) return@mapNotNull null
            val parts = trimmed.split("$")
            if (parts.size >= 2) {
                val epName = parts[0].trim().ifBlank { "正片" }
                val epUrl = parts[1].trim()
                if (epUrl.startsWith("http", ignoreCase = true)) VodEpisode(epName, epUrl) else null
            } else if (trimmed.startsWith("http", ignoreCase = true)) {
                VodEpisode("正片", trimmed)
            } else null
        }
    }

    private fun sortPlaySources(sources: List<VodPlaySource>): List<VodPlaySource> {
        return sources.sortedByDescending { ps ->
            val hasM3u8 = ps.name.contains("m3u8", ignoreCase = true) ||
                ps.episodes.firstOrNull()?.url?.contains(".m3u8", ignoreCase = true) == true
            val hasMp4 = ps.episodes.firstOrNull()?.url?.contains(".mp4", ignoreCase = true) == true
            when {
                hasM3u8 -> 3
                hasMp4 -> 2
                else -> 1
            }
        }
    }

    private fun filterCategories(
        all: List<VodCategory>,
        allowedNames: List<String>
    ): List<VodCategory> {
        val safe = all.filterNot {
            it.name.contains("伦理") || it.name.contains("福利") || it.name.contains("写真") || it.name.contains("成人")
        }
        if (allowedNames.isEmpty()) return safe
        val allowedSet = allowedNames.toSet()
        val filtered = safe.filter { it.name in allowedSet }
        return if (filtered.isNotEmpty()) filtered else safe
    }

    private fun appendQuery(baseUrl: String, query: String): String {
        val clean = baseUrl.trim()
        return if (clean.contains("?")) "$clean&$query" else "$clean?$query"
    }

    private fun fixImageUrl(url: String): String {
        val t = url.trim()
        return when {
            t.startsWith("//") -> "https:$t"
            else -> t
        }
    }

    private fun cleanHtml(raw: String): String {
        return raw.replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
