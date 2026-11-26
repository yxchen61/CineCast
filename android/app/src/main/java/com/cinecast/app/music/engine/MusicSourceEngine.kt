package com.cinecast.app.music.engine

import com.cinecast.app.data.network.NetworkClient
import com.cinecast.app.music.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 独立多源音乐抓取与歌词解析引擎
 * 支持 5 大开源音乐协议：
 * - Meting API (injahow/meting-api & metowolf/Meting)
 * - GDStudio Music API (music-api.gdstudio.xyz)
 * - Audius Decentralized Music API
 * - Radio-Browser Open FM API
 * - 标准 M3U 音频歌单/电台
 */
class MusicSourceEngine {

    suspend fun fetchChart(
        source: MusicSource,
        category: MusicChartCategory,
        page: Int = 1
    ): List<MusicTrack> = withContext(Dispatchers.IO) {
        when (source.sourceType) {
            MusicSourceType.METING_API -> fetchMetingPlaylist(source, category.idOrQuery)
            MusicSourceType.GDSTUDIO_API -> searchGdStudio(source, category.idOrQuery, page)
            MusicSourceType.AUDIUS_API -> fetchAudiusChart(source, category.idOrQuery)
            MusicSourceType.RADIO_BROWSER -> fetchRadioBrowser(source, category.idOrQuery)
            MusicSourceType.M3U_AUDIO -> fetchM3uTracks(source, category.idOrQuery.ifBlank { source.baseUrl })
        }
    }

    suspend fun search(
        source: MusicSource,
        keyword: String,
        page: Int = 1
    ): List<MusicTrack> = withContext(Dispatchers.IO) {
        val q = keyword.trim()
        if (q.isBlank()) return@withContext emptyList()
        when (source.sourceType) {
            MusicSourceType.GDSTUDIO_API -> searchGdStudio(source, q, page)
            MusicSourceType.METING_API -> searchMetingOrFallback(source, q)
            MusicSourceType.AUDIUS_API -> searchAudius(source, q)
            MusicSourceType.RADIO_BROWSER -> searchRadioBrowser(source, q)
            MusicSourceType.M3U_AUDIO -> fetchM3uTracks(source, source.baseUrl).filter {
                it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true)
            }
        }
    }

    /**
     * 解析并补全曲目的真实可播音频流、高清封面与 LRC 同步歌词
     */
    suspend fun resolvePlayableTrack(
        source: MusicSource,
        track: MusicTrack,
        preferHighBitrate: Boolean = true
    ): MusicTrack = withContext(Dispatchers.IO) {
        if (source.sourceType == MusicSourceType.GDSTUDIO_API) {
            val base = source.baseUrl.trimEnd('/')
            val plat = track.platform.ifBlank { source.platform.ifBlank { "netease" } }
            val br = if (preferHighBitrate) "320" else "192"

            var resolvedUrl = track.streamUrl
            if (resolvedUrl.isBlank() && track.urlId.isNotBlank()) {
                val urlJson = runCatching {
                    JSONObject(NetworkClient.getString("$base?types=url&source=$plat&id=${encode(track.urlId)}&br=$br", source.headers))
                }.getOrNull()
                resolvedUrl = urlJson?.optString("url").orEmpty().trim()
                if (resolvedUrl.isBlank() && br != "128") {
                    val fallbackJson = runCatching {
                        JSONObject(NetworkClient.getString("$base?types=url&source=$plat&id=${encode(track.urlId)}&br=128", source.headers))
                    }.getOrNull()
                    resolvedUrl = fallbackJson?.optString("url").orEmpty().trim()
                }
            }

            var resolvedCover = track.coverUrl
            if (resolvedCover.isBlank() && track.picId.isNotBlank()) {
                val picJson = runCatching {
                    JSONObject(NetworkClient.getString("$base?types=pic&source=$plat&id=${encode(track.picId)}&size=500", source.headers))
                }.getOrNull()
                resolvedCover = picJson?.optString("url").orEmpty().trim()
            }

            var resolvedLrc = track.rawLyric
            if (resolvedLrc.isBlank() && track.lyricId.isNotBlank()) {
                val lrcJson = runCatching {
                    JSONObject(NetworkClient.getString("$base?types=lyric&source=$plat&id=${encode(track.lyricId)}", source.headers))
                }.getOrNull()
                resolvedLrc = lrcJson?.optString("lyric").orEmpty().trim()
            }

            return@withContext track.copy(
                streamUrl = resolvedUrl,
                coverUrl = resolvedCover,
                rawLyric = resolvedLrc
            )
        }

        // For Meting API: streamUrl is already direct redirectable audio URL; fetch LRC text if lyricUrl exists
        if (source.sourceType == MusicSourceType.METING_API && track.rawLyric.isBlank() && track.lyricUrl.startsWith("http")) {
            val lrcText = runCatching { NetworkClient.getString(track.lyricUrl, source.headers) }.getOrDefault("")
            return@withContext track.copy(rawLyric = lrcText)
        }

        track
    }

    suspend fun pingSource(source: MusicSource): Long = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val probe = source.charts.firstOrNull()
            val tracks = if (probe != null) fetchChart(source, probe, 1) else search(source, "love", 1)
            if (tracks.isEmpty()) -1L else (System.currentTimeMillis() - start).coerceAtLeast(1L)
        } catch (_: Exception) {
            -1L
        }
    }

    // ==================== Protocol Implementations ====================

    private fun fetchMetingPlaylist(source: MusicSource, playlistId: String): List<MusicTrack> {
        val sep = if (source.baseUrl.contains("?")) "&" else "?"
        val server = source.platform.ifBlank { "netease" }
        val url = "${source.baseUrl}${sep}server=$server&type=playlist&id=$playlistId"
        val raw = NetworkClient.getString(url, source.headers)
        val arr = JSONArray(raw.trim())
        val list = mutableListOf<MusicTrack>()
        for (i in 0 until minOf(arr.length(), 60)) {
            val obj = arr.optJSONObject(i) ?: continue
            val title = obj.optString("name").ifBlank { obj.optString("title") }.trim()
            val artist = obj.optString("artist").ifBlank { obj.optString("author") }.trim().ifBlank { "未知歌手" }
            val streamUrl = obj.optString("url").trim()
            val pic = obj.optString("pic").trim()
            val lrc = obj.optString("lrc").trim()
            if (title.isBlank() || streamUrl.isBlank()) continue
            val id = Regex("""id=([^&]+)""").find(streamUrl)?.groupValues?.get(1) ?: "${playlistId}_$i"
            list.add(
                MusicTrack(
                    id = id,
                    sourceId = source.id,
                    sourceName = source.name,
                    title = title,
                    artist = artist,
                    coverUrl = pic,
                    streamUrl = streamUrl,
                    lyricUrl = lrc,
                    platform = server
                )
            )
        }
        return list
    }

    private fun searchMetingOrFallback(source: MusicSource, keyword: String): List<MusicTrack> {
        // First try standard Meting search endpoint if supported, else filter cached chart or query GDStudio
        val sep = if (source.baseUrl.contains("?")) "&" else "?"
        val server = source.platform.ifBlank { "netease" }
        val searchTry = runCatching {
            val raw = NetworkClient.getString(
                "${source.baseUrl}${sep}server=$server&type=search&id=${encode(keyword)}",
                source.headers
            )
            if (raw.trimStart().startsWith("[")) {
                val arr = JSONArray(raw.trim())
                (0 until minOf(arr.length(), 25)).mapNotNull { idx ->
                    val o = arr.optJSONObject(idx) ?: return@mapNotNull null
                    val title = o.optString("name").ifBlank { o.optString("title") }.trim()
                    val url = o.optString("url").trim()
                    if (title.isBlank() || url.isBlank()) null
                    else MusicTrack(
                        id = "${source.id}_s_$idx",
                        sourceId = source.id,
                        sourceName = source.name,
                        title = title,
                        artist = o.optString("artist").ifBlank { o.optString("author") }.ifBlank { "未知歌手" },
                        coverUrl = o.optString("pic"),
                        streamUrl = url,
                        lyricUrl = o.optString("lrc"),
                        platform = server
                    )
                }
            } else emptyList()
        }.getOrDefault(emptyList())

        if (searchTry.isNotEmpty()) return searchTry

        // Fallback: filter current hot playlist if Meting instance disabled type=search
        val firstChart = source.charts.firstOrNull() ?: return emptyList()
        return runCatching { fetchMetingPlaylist(source, firstChart.idOrQuery) }.getOrDefault(emptyList())
            .filter { it.title.contains(keyword, true) || it.artist.contains(keyword, true) }
    }

    private fun searchGdStudio(source: MusicSource, keyword: String, page: Int): List<MusicTrack> {
        val base = source.baseUrl.trimEnd('/')
        val plat = source.platform.ifBlank { "netease" }
        val url = "$base?types=search&source=$plat&name=${encode(keyword)}&count=25&pages=$page"
        val raw = NetworkClient.getString(url, source.headers)
        val arr = JSONArray(raw.trim())
        val list = mutableListOf<MusicTrack>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id").trim()
            val title = obj.optString("name").trim()
            if (id.isBlank() || title.isBlank()) continue
            val artistArr = obj.optJSONArray("artist")
            val artist = if (artistArr != null) {
                (0 until artistArr.length()).mapNotNull { artistArr.optString(it).takeIf { s -> s.isNotBlank() } }
                    .joinToString(" / ")
            } else {
                obj.optString("artist").trim()
            }.ifBlank { "未知歌手" }

            val album = obj.optString("album").trim()
            val picId = obj.optString("pic_id").trim()
            val urlId = obj.optString("url_id").ifBlank { id }
            val lyricId = obj.optString("lyric_id").ifBlank { id }
            val actualPlat = obj.optString("source").ifBlank { plat }

            list.add(
                MusicTrack(
                    id = id,
                    sourceId = source.id,
                    sourceName = source.name,
                    title = title,
                    artist = artist,
                    album = album,
                    platform = actualPlat,
                    picId = picId,
                    urlId = urlId,
                    lyricId = lyricId
                )
            )
        }
        return list
    }

    private fun fetchAudiusChart(source: MusicSource, genreOrTrending: String): List<MusicTrack> {
        val base = source.baseUrl.trimEnd('/')
        val url = if (genreOrTrending.equals("trending", ignoreCase = true)) {
            "$base/tracks/trending?limit=30&app_name=CineCast"
        } else {
            "$base/tracks/trending?genre=${encode(genreOrTrending)}&limit=30&app_name=CineCast"
        }
        return parseAudiusTracks(source, NetworkClient.getString(url, source.headers))
    }

    private fun searchAudius(source: MusicSource, keyword: String): List<MusicTrack> {
        val base = source.baseUrl.trimEnd('/')
        val url = "$base/tracks/search?query=${encode(keyword)}&limit=25&app_name=CineCast"
        return parseAudiusTracks(source, NetworkClient.getString(url, source.headers))
    }

    private fun parseAudiusTracks(source: MusicSource, rawJson: String): List<MusicTrack> {
        val base = source.baseUrl.trimEnd('/')
        val data = JSONObject(rawJson).optJSONArray("data") ?: return emptyList()
        val list = mutableListOf<MusicTrack>()
        for (i in 0 until data.length()) {
            val obj = data.optJSONObject(i) ?: continue
            val id = obj.optString("id").trim()
            val title = obj.optString("title").trim()
            if (id.isBlank() || title.isBlank()) continue
            val artist = obj.optJSONObject("user")?.optString("name").orEmpty().ifBlank { "Independent Artist" }
            val artObj = obj.optJSONObject("artwork")
            val cover = artObj?.optString("480x480").orEmpty()
                .ifBlank { artObj?.optString("150x150").orEmpty() }
            val durationMs = obj.optLong("duration", 0L) * 1000L
            list.add(
                MusicTrack(
                    id = id,
                    sourceId = source.id,
                    sourceName = source.name,
                    title = title,
                    artist = artist,
                    album = obj.optString("genre").trim(),
                    coverUrl = cover,
                    streamUrl = "$base/tracks/$id/stream?app_name=CineCast",
                    durationMs = durationMs,
                    platform = "audius"
                )
            )
        }
        return list
    }

    private fun fetchRadioBrowser(source: MusicSource, category: String): List<MusicTrack> {
        val base = source.baseUrl.trimEnd('/')
        val url = when (category.lowercase()) {
            "cn" -> "$base/stations/bycountrycodeexact/CN?limit=35&order=votes&reverse=true&hidebroken=true"
            "top" -> "$base/stations/topvote/35?hidebroken=true"
            else -> "$base/stations/bytag/${encode(category)}?limit=35&order=votes&reverse=true&hidebroken=true"
        }
        return parseRadioStations(source, NetworkClient.getString(url, source.headers))
    }

    private fun searchRadioBrowser(source: MusicSource, keyword: String): List<MusicTrack> {
        val base = source.baseUrl.trimEnd('/')
        val url = "$base/stations/byname/${encode(keyword)}?limit=25&order=votes&reverse=true&hidebroken=true"
        return parseRadioStations(source, NetworkClient.getString(url, source.headers))
    }

    private fun parseRadioStations(source: MusicSource, rawJson: String): List<MusicTrack> {
        val arr = JSONArray(rawJson.trim())
        val list = mutableListOf<MusicTrack>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val uuid = obj.optString("stationuuid").ifBlank { "radio_$i" }
            val name = obj.optString("name").trim()
            val stream = obj.optString("url_resolved").ifBlank { obj.optString("url") }.trim()
            if (name.isBlank() || !stream.startsWith("http")) continue
            val tags = obj.optString("tags").split(",").take(3).filter { it.isNotBlank() }.joinToString(" · ")
            val country = obj.optString("country").trim()
            list.add(
                MusicTrack(
                    id = uuid,
                    sourceId = source.id,
                    sourceName = source.name,
                    title = name,
                    artist = listOf(country, tags).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "在线音乐电台" },
                    coverUrl = obj.optString("favicon").trim(),
                    streamUrl = stream,
                    platform = "radio",
                    isLiveRadio = true
                )
            )
        }
        return list.distinctBy { it.streamUrl }
    }

    private fun fetchM3uTracks(source: MusicSource, m3uUrl: String): List<MusicTrack> {
        val raw = NetworkClient.getString(m3uUrl, source.headers)
        val lines = raw.lines()
        val list = mutableListOf<MusicTrack>()
        var pendingTitle = ""
        var pendingArtist = "开源电台/歌单"
        var pendingLogo = ""
        for (line in lines) {
            val t = line.trim()
            if (t.isBlank()) continue
            if (t.startsWith("#EXTINF", ignoreCase = true)) {
                pendingLogo = Regex("""tvg-logo="([^"]+)"""").find(t)?.groupValues?.get(1).orEmpty()
                val group = Regex("""group-title="([^"]+)"""").find(t)?.groupValues?.get(1).orEmpty()
                if (group.isNotBlank()) pendingArtist = group
                pendingTitle = t.substringAfterLast(",").trim()
            } else if (t.startsWith("http://") || t.startsWith("https://")) {
                val title = pendingTitle.ifBlank { t.substringAfterLast("/").substringBefore("?") }
                list.add(
                    MusicTrack(
                        id = "m3u_${list.size}_${t.hashCode()}",
                        sourceId = source.id,
                        sourceName = source.name,
                        title = title,
                        artist = pendingArtist,
                        coverUrl = pendingLogo,
                        streamUrl = t,
                        isLiveRadio = t.endsWith(".m3u8", true)
                    )
                )
                pendingTitle = ""
            }
        }
        return list
    }

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

    companion object {
        private val lrcTimeRegex = Regex("""\[(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?]""")

        /**
         * 解析标准 LRC 同步歌词为按时间戳排序的歌词行列表
         */
        fun parseLrc(rawLrc: String): List<LrcLine> {
            if (rawLrc.isBlank()) return emptyList()
            val lines = mutableListOf<LrcLine>()
            for (rawLine in rawLrc.lines()) {
                val matches = lrcTimeRegex.findAll(rawLine).toList()
                if (matches.isEmpty()) continue
                val text = rawLine.replace(lrcTimeRegex, "").trim()
                if (text.isBlank()) continue
                for (m in matches) {
                    val min = m.groupValues[1].toLongOrNull() ?: 0L
                    val sec = m.groupValues[2].toLongOrNull() ?: 0L
                    val fracStr = m.groupValues[3]
                    val ms = when (fracStr.length) {
                        1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                        2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                        3 -> fracStr.toLongOrNull() ?: 0L
                        else -> 0L
                    }
                    lines.add(LrcLine(timeMs = min * 60_000L + sec * 1000L + ms, text = text))
                }
            }
            return lines.sortedBy { it.timeMs }
        }
    }
}
