package com.cinecast.app.data.engine

import com.cinecast.app.data.model.*
import com.cinecast.app.data.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

data class TvBoxImportResult(
    val subscription: SubscriptionConfig,
    val sources: List<VideoSource>,
    val liveSources: List<LiveSource>,
    val parses: List<ParseInterface>,
    val adHosts: List<String>,
    val totalRawSites: Int,
    val directPlayableCount: Int
)

class TvBoxConfigParser {

    suspend fun importFromUrl(url: String, customName: String = ""): TvBoxImportResult =
        withContext(Dispatchers.IO) {
            val raw = NetworkClient.getString(url.trim())
            parseJsonText(raw, url.trim(), customName)
        }

    fun parseJsonText(
        rawText: String,
        baseUrl: String = "",
        customName: String = ""
    ): TvBoxImportResult {
        val cleaned = stripJsonComments(rawText)
        val root = JSONObject(cleaned)

        val subName = customName.ifBlank {
            if (baseUrl.isNotBlank()) {
                try {
                    val uri = URI(baseUrl)
                    val file = uri.path.substringAfterLast('/').ifBlank { uri.host ?: "TVBox配置" }
                    "${uri.host ?: "订阅"} / $file"
                } catch (_: Exception) {
                    "TVBox 订阅配置"
                }
            } else {
                "本地导入配置"
            }
        }

        // 1. Parse sites
        val sitesArr = root.optJSONArray("sites") ?: JSONArray()
        val parsedSources = mutableListOf<VideoSource>()
        var directPlayableCount = 0

        for (i in 0 until sitesArr.length()) {
            val s = sitesArr.optJSONObject(i) ?: continue
            val key = s.optString("key").ifBlank { "site_$i" }
            val name = s.optString("name").ifBlank { key }.trim()
            val typeInt = s.optInt("type", 1)
            val api = s.optString("api").trim()
            val searchable = s.optInt("searchable", 1) != 0

            val catList = mutableListOf<String>()
            val catsArr = s.optJSONArray("categories")
            if (catsArr != null) {
                for (c in 0 until catsArr.length()) {
                    val catName = catsArr.optString(c).trim()
                    if (catName.isNotBlank()) catList.add(catName)
                }
            }

            val extRaw = when (val extObj = s.opt("ext")) {
                is String -> extObj
                is JSONObject -> extObj.toString()
                else -> ""
            }

            val resolvedApi = resolveRelativeUrl(baseUrl, api)
            val sourceType = when (typeInt) {
                0 -> SourceType.MACCMS_XML
                1 -> SourceType.MACCMS_JSON
                4 -> SourceType.T4_API
                else -> {
                    if (resolvedApi.startsWith("http") && !resolvedApi.endsWith(".js", ignoreCase = true)) {
                        SourceType.WEB_SNIFFER
                    } else {
                        SourceType.TVBOX_SPIDER
                    }
                }
            }

            // If it's a Type 0 MacCMS XML URL that also supports standard JSON API, keep accurate type
            if (sourceType in setOf(SourceType.MACCMS_JSON, SourceType.MACCMS_XML, SourceType.T4_API)) {
                directPlayableCount++
            }

            if (api.isNotBlank()) {
                parsedSources.add(
                    VideoSource(
                        id = "sub_${key.hashCode().toUInt()}_$i",
                        name = name,
                        type = sourceType,
                        apiUrl = resolvedApi,
                        searchable = searchable,
                        enabled = sourceType != SourceType.TVBOX_SPIDER,
                        group = subName,
                        categories = catList,
                        ext = extRaw,
                        subscriptionUrl = baseUrl
                    )
                )
            }
        }

        // 2. Parse lives
        val livesArr = root.optJSONArray("lives") ?: JSONArray()
        val parsedLives = mutableListOf<LiveSource>()
        for (i in 0 until livesArr.length()) {
            val l = livesArr.optJSONObject(i) ?: continue
            val lName = l.optString("name").ifBlank { l.optString("group") }.ifBlank { "直播源 ${i + 1}" }
            val lUrl = resolveRelativeUrl(baseUrl, l.optString("url").trim())
            val lEpg = l.optString("epg").trim()
            if (lUrl.startsWith("http")) {
                parsedLives.add(
                    LiveSource(
                        id = "live_${lUrl.hashCode().toUInt()}",
                        name = lName,
                        url = lUrl,
                        epg = lEpg
                    )
                )
            } else {
                // Handle TVBox nested channels array format
                val channels = l.optJSONArray("channels")
                if (channels != null) {
                    for (j in 0 until channels.length()) {
                        val ch = channels.optJSONObject(j) ?: continue
                        val urls = ch.optJSONArray("urls") ?: continue
                        for (k in 0 until urls.length()) {
                            val rawUrl = urls.optString(k)
                            val cleanUrl = rawUrl.substringAfter("ext=").ifBlank { rawUrl }
                            val resolved = resolveRelativeUrl(baseUrl, cleanUrl)
                            if (resolved.startsWith("http")) {
                                parsedLives.add(
                                    LiveSource(
                                        id = "live_${resolved.hashCode().toUInt()}",
                                        name = ch.optString("name").ifBlank { lName },
                                        url = resolved,
                                        epg = lEpg
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Parse parses (VIP video parsers)
        val parsesArr = root.optJSONArray("parses") ?: JSONArray()
        val parsedParses = mutableListOf<ParseInterface>()
        for (i in 0 until parsesArr.length()) {
            val p = parsesArr.optJSONObject(i) ?: continue
            val pName = p.optString("name").ifBlank { "解析 ${i + 1}" }
            val pType = p.optInt("type", 0)
            val pUrl = p.optString("url").trim()
            if (pUrl.startsWith("http")) {
                parsedParses.add(ParseInterface(pName, pType, pUrl))
            }
        }

        // 4. Parse ads hosts
        val adsArr = root.optJSONArray("ads") ?: JSONArray()
        val adHosts = mutableListOf<String>()
        for (i in 0 until adsArr.length()) {
            val host = adsArr.optString(i).trim()
            if (host.isNotBlank()) adHosts.add(host)
        }

        val sub = SubscriptionConfig(
            id = if (baseUrl.isNotBlank()) "sub_${baseUrl.hashCode().toUInt()}" else UUID.randomUUID().toString(),
            name = subName,
            url = baseUrl,
            lastSyncedAt = System.currentTimeMillis(),
            siteCount = parsedSources.size,
            liveCount = parsedLives.size,
            parseCount = parsedParses.size
        )

        return TvBoxImportResult(
            subscription = sub,
            sources = parsedSources,
            liveSources = parsedLives,
            parses = parsedParses,
            adHosts = adHosts,
            totalRawSites = sitesArr.length(),
            directPlayableCount = directPlayableCount
        )
    }

    private fun resolveRelativeUrl(baseUrl: String, target: String): String {
        if (target.isBlank() || target.startsWith("http://") || target.startsWith("https://") || !target.startsWith(".")) {
            return target
        }
        return try {
            if (baseUrl.isBlank()) target else URI(baseUrl).resolve(target).toString()
        } catch (_: Exception) {
            target
        }
    }

    private fun stripJsonComments(input: String): String {
        val sb = StringBuilder(input.length)
        var inString = false
        var escaped = false
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (inString) {
                sb.append(c)
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                i++
            } else {
                if (c == '"') {
                    inString = true
                    sb.append(c)
                    i++
                } else if (c == '/' && i + 1 < input.length && input[i + 1] == '/') {
                    while (i < input.length && input[i] != '\n') i++
                } else if (c == '/' && i + 1 < input.length && input[i + 1] == '*') {
                    i += 2
                    while (i + 1 < input.length && !(input[i] == '*' && input[i + 1] == '/')) i++
                    i += 2
                } else {
                    sb.append(c)
                    i++
                }
            }
        }
        // Remove trailing commas before } or ]
        return sb.toString().replace(Regex(",\\s*([}\\]])"), "$1").trim()
    }
}
