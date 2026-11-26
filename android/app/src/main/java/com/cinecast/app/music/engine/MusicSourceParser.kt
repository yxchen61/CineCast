package com.cinecast.app.music.engine

import com.cinecast.app.data.network.NetworkClient
import com.cinecast.app.music.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class MusicImportResult(
    val subscription: MusicSubscription,
    val sources: List<MusicSource>,
    val totalRawCount: Int
)

/**
 * GitHub 开源音乐源导入解析器
 * 兼容：
 * 1. CineCast 通用音乐源 JSON 数组（Meting / GDStudio / Audius / RadioBrowser / M3U）
 * 2. MusicFree 社区 `plugins.json` 订阅清单（自动映射主流平台至原生可播放音乐引擎）
 * 3. 单条 Meting API URL 或开源 M3U 音乐/电台歌单
 */
class MusicSourceParser {

    suspend fun importFromUrl(url: String, customName: String = ""): MusicImportResult =
        withContext(Dispatchers.IO) {
            val raw = NetworkClient.getString(url)
            parseImportText(raw, url, customName)
        }

    fun parseImportText(rawText: String, sourceUrl: String = "", customName: String = ""): MusicImportResult {
        val trimmed = rawText.trim().removePrefix("\uFEFF")

        // Case 1: M3U / M3U8 radio or music playlist URL
        if (trimmed.startsWith("#EXTM3U", ignoreCase = true) ||
            sourceUrl.endsWith(".m3u", ignoreCase = true) ||
            sourceUrl.endsWith(".m3u8", ignoreCase = true)
        ) {
            val name = customName.ifBlank {
                sourceUrl.substringAfterLast("/").substringBefore(".").ifBlank { "导入 M3U 音乐电台" }
            }
            val src = MusicSource(
                id = "m3u_${shortHash(sourceUrl.ifBlank { trimmed.take(64) })}",
                name = name,
                baseUrl = sourceUrl.ifBlank { "inline://m3u" },
                sourceType = MusicSourceType.M3U_AUDIO,
                group = "GitHub 导入电台/歌单",
                searchable = true,
                subscriptionUrl = sourceUrl,
                charts = listOf(MusicChartCategory("全部频道/曲目", sourceUrl))
            )
            val sub = MusicSubscription(
                id = "msub_${shortHash(sourceUrl.ifBlank { name })}",
                name = name,
                url = sourceUrl,
                lastSyncedAt = System.currentTimeMillis(),
                importedCount = 1
            )
            return MusicImportResult(sub, listOf(src), 1)
        }

        val parsedSources = mutableListOf<MusicSource>()
        var rawCount = 0

        if (trimmed.startsWith("{")) {
            val root = JSONObject(trimmed)
            // Case 2: MusicFree plugins.json format: { "plugins": [ { "name": "...", "url": "..." } ] }
            val pluginsArr = root.optJSONArray("plugins")
            if (pluginsArr != null) {
                rawCount = pluginsArr.length()
                for (i in 0 until pluginsArr.length()) {
                    val p = pluginsArr.optJSONObject(i) ?: continue
                    val pName = p.optString("name").trim()
                    val pUrl = p.optString("url").trim()
                    val pVer = p.optString("version").trim()
                    if (pName.isBlank()) continue
                    mapMusicFreePluginToNativeSource(pName, pUrl, pVer, sourceUrl)?.let {
                        parsedSources.add(it)
                    }
                }
            } else {
                // Single JSON source object
                rawCount = 1
                parseCustomSourceObject(root, sourceUrl)?.let { parsedSources.add(it) }
            }
        } else if (trimmed.startsWith("[")) {
            val arr = JSONArray(trimmed)
            rawCount = arr.length()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                parseCustomSourceObject(obj, sourceUrl)?.let { parsedSources.add(it) }
            }
        } else {
            throw IllegalArgumentException("不支持的音乐源格式，请提供 JSON 订阅、MusicFree plugins.json 或 M3U 链接")
        }

        val deduped = parsedSources.distinctBy { "${it.sourceType}_${it.baseUrl}_${it.platform}_${it.name}" }
        val subName = customName.ifBlank {
            when {
                sourceUrl.contains("Huibq/keep-alive", ignoreCase = true) -> "Huibq MusicFree 社区源"
                sourceUrl.contains("MusicFreePlugins", ignoreCase = true) -> "MusicFree 官方插件源"
                sourceUrl.contains("meting", ignoreCase = true) -> "Meting 开源音乐源"
                sourceUrl.isNotBlank() -> sourceUrl.substringAfter("://").substringBefore("/")
                else -> "本地粘贴音乐源"
            }
        }

        val sub = MusicSubscription(
            id = "msub_${shortHash(sourceUrl.ifBlank { subName })}",
            name = subName,
            url = sourceUrl,
            lastSyncedAt = System.currentTimeMillis(),
            importedCount = deduped.size
        )

        return MusicImportResult(sub, deduped, rawCount)
    }

    private fun parseCustomSourceObject(obj: JSONObject, subUrl: String): MusicSource? {
        val name = obj.optString("name").ifBlank { obj.optString("title") }.trim()
        val baseUrl = obj.optString("baseUrl").ifBlank { obj.optString("url") }.trim()
        if (name.isBlank() || !baseUrl.startsWith("http")) return null

        val typeStr = obj.optString("sourceType").ifBlank { obj.optString("type") }.uppercase()
        val sourceType = when {
            typeStr.contains("GDSTUDIO") || baseUrl.contains("gdstudio") -> MusicSourceType.GDSTUDIO_API
            typeStr.contains("METING") || baseUrl.contains("meting") -> MusicSourceType.METING_API
            typeStr.contains("AUDIUS") || baseUrl.contains("audius") -> MusicSourceType.AUDIUS_API
            typeStr.contains("RADIO") || baseUrl.contains("radio-browser") -> MusicSourceType.RADIO_BROWSER
            typeStr.contains("M3U") || baseUrl.endsWith(".m3u", true) -> MusicSourceType.M3U_AUDIO
            else -> MusicSourceType.METING_API
        }
        val platform = obj.optString("platform").ifBlank {
            obj.optString("server").ifBlank { "netease" }
        }
        val charts = mutableListOf<MusicChartCategory>()
        obj.optJSONArray("charts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val t = c.optString("title").trim()
                val q = c.optString("idOrQuery").ifBlank { c.optString("id") }.trim()
                if (t.isNotBlank() && q.isNotBlank()) charts.add(MusicChartCategory(t, q))
            }
        }
        if (charts.isEmpty()) {
            charts.addAll(defaultChartsFor(sourceType, baseUrl))
        }

        return MusicSource(
            id = obj.optString("id").ifBlank { "msrc_${shortHash("$baseUrl|$platform|$name")}" },
            name = name,
            baseUrl = baseUrl,
            sourceType = sourceType,
            platform = platform,
            group = obj.optString("group").ifBlank { "GitHub 导入音乐源" },
            enabled = obj.optBoolean("enabled", true),
            searchable = obj.optBoolean("searchable", true),
            charts = charts,
            comment = obj.optString("comment").trim(),
            subscriptionUrl = subUrl
        )
    }

    /**
     * 将 MusicFree `plugins.json` 中声明的社区音源智能映射到原生可直连播放的音乐引擎
     */
    private fun mapMusicFreePluginToNativeSource(
        pluginName: String,
        pluginUrl: String,
        version: String,
        subUrl: String
    ): MusicSource? {
        val lower = pluginName.lowercase()
        val suffix = if (version.isNotBlank()) " v$version" else ""
        return when {
            lower.contains("网易") || lower.contains("小芸") || lower.contains("netease") || lower.contains("yun") ->
                MusicSource(
                    id = "mf_${shortHash(pluginName + pluginUrl)}",
                    name = "$pluginName (MusicFree$suffix)",
                    baseUrl = "https://music-api.gdstudio.xyz/api.php",
                    sourceType = MusicSourceType.GDSTUDIO_API,
                    platform = "netease",
                    group = "GitHub MusicFree 订阅",
                    charts = defaultChartsFor(MusicSourceType.GDSTUDIO_API, ""),
                    comment = "映射自 MusicFree 插件：$pluginUrl",
                    subscriptionUrl = subUrl
                )
            lower.contains("joox") || lower.contains("港台") || lower.contains("小秋") ->
                MusicSource(
                    id = "mf_${shortHash(pluginName + pluginUrl)}",
                    name = "$pluginName (MusicFree$suffix)",
                    baseUrl = "https://api.injahow.cn/meting/",
                    sourceType = MusicSourceType.METING_API,
                    platform = "netease",
                    group = "GitHub MusicFree 订阅",
                    charts = defaultChartsFor(MusicSourceType.METING_API, ""),
                    comment = "映射自 MusicFree 插件：$pluginUrl",
                    subscriptionUrl = subUrl
                )
            lower.contains("电台") || lower.contains("radio") || lower.contains("fm") ->
                MusicSource(
                    id = "mf_${shortHash(pluginName + pluginUrl)}",
                    name = "$pluginName (MusicFree$suffix)",
                    baseUrl = "https://de1.api.radio-browser.info/json",
                    sourceType = MusicSourceType.RADIO_BROWSER,
                    platform = "cn",
                    group = "GitHub MusicFree 订阅",
                    charts = defaultChartsFor(MusicSourceType.RADIO_BROWSER, ""),
                    comment = "映射自 MusicFree 插件：$pluginUrl",
                    subscriptionUrl = subUrl
                )
            lower.contains("audiomack") || lower.contains("audius") || lower.contains("欧美") || lower.contains("独立") ->
                MusicSource(
                    id = "mf_${shortHash(pluginName + pluginUrl)}",
                    name = "$pluginName (MusicFree$suffix)",
                    baseUrl = "https://discoveryprovider.audius.co/v1",
                    sourceType = MusicSourceType.AUDIUS_API,
                    platform = "global",
                    group = "GitHub MusicFree 订阅",
                    charts = defaultChartsFor(MusicSourceType.AUDIUS_API, ""),
                    comment = "映射自 MusicFree 插件：$pluginUrl",
                    subscriptionUrl = subUrl
                )
            else -> null
        }
    }

    private fun defaultChartsFor(type: MusicSourceType, baseUrl: String): List<MusicChartCategory> = when (type) {
        MusicSourceType.METING_API -> listOf(
            MusicChartCategory("热歌榜", "3778678"),
            MusicChartCategory("飙升榜", "19723756"),
            MusicChartCategory("新歌榜", "3779629"),
            MusicChartCategory("原创榜", "2884035")
        )
        MusicSourceType.GDSTUDIO_API -> listOf(
            MusicChartCategory("华语热歌", "热门"),
            MusicChartCategory("经典怀旧", "经典"),
            MusicChartCategory("治愈轻音", "轻音乐"),
            MusicChartCategory("影视原声", "影视原声")
        )
        MusicSourceType.AUDIUS_API -> listOf(
            MusicChartCategory("Trending 热门", "trending"),
            MusicChartCategory("Electronic 电子", "Electronic"),
            MusicChartCategory("Lo-Fi 专注", "Lo-Fi"),
            MusicChartCategory("Pop 流行", "Pop")
        )
        MusicSourceType.RADIO_BROWSER -> listOf(
            MusicChartCategory("华语音乐台", "cn"),
            MusicChartCategory("全球热播台", "top"),
            MusicChartCategory("爵士与古典", "jazz"),
            MusicChartCategory("Lo-Fi 助眠", "lofi")
        )
        MusicSourceType.M3U_AUDIO -> listOf(
            MusicChartCategory("全部音频", baseUrl)
        )
    }

    private fun shortHash(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.take(6).joinToString("") { "%02x".format(it) }
    }
}
