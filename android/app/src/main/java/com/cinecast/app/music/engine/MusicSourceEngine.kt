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
        val raw = when (source.sourceType) {
            MusicSourceType.BILIBILI_MUSIC -> searchBilibiliMusic(source, category.idOrQuery, page)
            MusicSourceType.VKEYS_API -> searchVkeys(source, category.idOrQuery)
            MusicSourceType.KUWO_DIRECT -> fetchKuwoBang(source, category.idOrQuery)
            MusicSourceType.METING_API -> fetchMetingPlaylist(source, category.idOrQuery)
            MusicSourceType.GDSTUDIO_API -> searchGdStudio(source, category.idOrQuery, page)
            MusicSourceType.AUDIUS_API -> fetchAudiusChart(source, category.idOrQuery)
            MusicSourceType.RADIO_BROWSER -> fetchRadioBrowser(source, category.idOrQuery)
            MusicSourceType.M3U_AUDIO -> fetchM3uTracks(source, category.idOrQuery.ifBlank { source.baseUrl })
        }
        if (source.sourceType == MusicSourceType.RADIO_BROWSER || source.sourceType == MusicSourceType.M3U_AUDIO) {
            raw
        } else {
            filterFullVocalTracks(raw, category.title)
        }
    }

    suspend fun search(
        source: MusicSource,
        keyword: String,
        page: Int = 1
    ): List<MusicTrack> = withContext(Dispatchers.IO) {
        val q = keyword.trim()
        if (q.isBlank()) return@withContext emptyList()
        val raw = when (source.sourceType) {
            MusicSourceType.BILIBILI_MUSIC -> searchBilibiliMusic(source, q, page)
            MusicSourceType.VKEYS_API -> searchVkeys(source, q)
            MusicSourceType.KUWO_DIRECT -> searchKuwoDirect(source, q)
            MusicSourceType.GDSTUDIO_API -> searchGdStudio(source, q, page)
            MusicSourceType.METING_API -> searchMetingOrFallback(source, q)
            MusicSourceType.AUDIUS_API -> searchAudius(source, q)
            MusicSourceType.RADIO_BROWSER -> searchRadioBrowser(source, q)
            MusicSourceType.M3U_AUDIO -> fetchM3uTracks(source, source.baseUrl).filter {
                it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true)
            }
        }
        if (source.sourceType == MusicSourceType.RADIO_BROWSER || source.sourceType == MusicSourceType.M3U_AUDIO) {
            raw
        } else {
            filterFullVocalTracks(raw, q)
        }
    }

    /**
     * 第一阶段极速解析：仅获取可播音频直链（不阻塞等待歌词和封面，实现秒播）
     */
    suspend fun resolveStreamUrlFast(
        source: MusicSource,
        track: MusicTrack,
        preferHighBitrate: Boolean = true
    ): MusicTrack = withContext(Dispatchers.IO) {
        if (track.streamUrl.isNotBlank()) return@withContext track

        when (source.sourceType) {
            MusicSourceType.BILIBILI_MUSIC -> {
                resolveBilibiliAudioUrl(source, track, preferHighBitrate)
            }

            MusicSourceType.VKEYS_API -> {
                val base = source.baseUrl.trimEnd('/')
                val q = if (preferHighBitrate) "8" else "4"
                val obj = runCatching {
                    JSONObject(NetworkClient.getString("$base?id=${encode(track.id)}&quality=$q", source.headers))
                }.getOrNull()
                val dataObj = obj?.optJSONObject("data")
                var url = dataObj?.optString("url").orEmpty().trim()
                if (url.isBlank()) {
                    val fb = runCatching {
                        JSONObject(NetworkClient.getString("$base?id=${encode(track.id)}&quality=4", source.headers))
                    }.getOrNull()?.optJSONObject("data")
                    url = fb?.optString("url").orEmpty().trim()
                }
                val cover = dataObj?.optString("cover").orEmpty().ifBlank { track.coverUrl }
                track.copy(streamUrl = url, coverUrl = cover)
            }

            MusicSourceType.KUWO_DIRECT -> {
                val url = runCatching {
                    NetworkClient.getString(
                        "http://antiserver.kuwo.cn/anti.s?type=convert_url&rid=MUSIC_${track.id}&format=mp3&response=url",
                        source.headers
                    ).trim()
                }.getOrDefault("")
                track.copy(streamUrl = if (url.startsWith("http")) url else "")
            }

            MusicSourceType.GDSTUDIO_API -> {
                val base = source.baseUrl.trimEnd('/')
                val plat = track.platform.ifBlank { source.platform.ifBlank { "netease" } }
                val br = if (preferHighBitrate) "320" else "192"
                var resolvedUrl = runCatching {
                    JSONObject(NetworkClient.getString("$base?types=url&source=$plat&id=${encode(track.urlId)}&br=$br", source.headers))
                        .optString("url").trim()
                }.getOrDefault("")
                if (resolvedUrl.isBlank() && br != "128") {
                    resolvedUrl = runCatching {
                        JSONObject(NetworkClient.getString("$base?types=url&source=$plat&id=${encode(track.urlId)}&br=128", source.headers))
                            .optString("url").trim()
                    }.getOrDefault("")
                }
                track.copy(streamUrl = resolvedUrl)
            }

            else -> track
        }
    }

    /**
     * 第二阶段异步补全：在音频已开始播放后并行拉取高清封面与 LRC 同步歌词（零阻塞）
     */
    suspend fun resolveExtrasAsync(
        source: MusicSource,
        track: MusicTrack
    ): Pair<String, List<LrcLine>> = withContext(Dispatchers.IO) {
        var cover = track.coverUrl
        var lrcLines = parseLrc(track.rawLyric)

        when (source.sourceType) {
            MusicSourceType.BILIBILI_MUSIC -> {
                // Background best-effort LRC lookup via Kuwo so Bilibili full-length tracks also show synced lyrics
                if (lrcLines.isEmpty()) {
                    lrcLines = runCatching {
                        val cleanQuery = "${track.title} ${track.artist.takeIf { it != "未知歌手" }.orEmpty()}".trim()
                        val searchRaw = NetworkClient.getString(
                            "http://search.kuwo.cn/r.s?all=${encode(cleanQuery)}&ft=music&rformat=json&encoding=utf8&rn=3&pn=0"
                        ).trim()
                        val firstId = JSONObject(searchRaw).optJSONArray("abslist")
                            ?.optJSONObject(0)
                            ?.let { it.optString("DC_TARGETID").ifBlank { it.optString("MUSICRID").removePrefix("MUSIC_") } }
                            .orEmpty().trim()
                        if (firstId.isNotBlank()) {
                            val infoObj = JSONObject(
                                NetworkClient.getString("http://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=$firstId")
                            ).optJSONObject("data")
                            val arr = infoObj?.optJSONArray("lrclist")
                            if (arr != null) {
                                (0 until arr.length()).mapNotNull { i ->
                                    val item = arr.optJSONObject(i) ?: return@mapNotNull null
                                    val text = item.optString("lineLyric").trim()
                                    val sec = item.optString("time").toDoubleOrNull() ?: return@mapNotNull null
                                    if (text.isNotBlank()) LrcLine((sec * 1000).toLong(), text) else null
                                }.sortedBy { it.timeMs }
                            } else emptyList()
                        } else emptyList()
                    }.getOrDefault(emptyList())
                }
            }

            MusicSourceType.VKEYS_API -> {
                if (lrcLines.isEmpty()) {
                    val base = source.baseUrl.trimEnd('/')
                    val lrcText = runCatching {
                        JSONObject(NetworkClient.getString("$base/lyric?id=${encode(track.id)}", source.headers))
                            .optJSONObject("data")?.optString("lrc").orEmpty()
                    }.getOrDefault("")
                    lrcLines = parseLrc(lrcText)
                }
            }

            MusicSourceType.KUWO_DIRECT -> {
                val infoJson = runCatching {
                    JSONObject(NetworkClient.getString("http://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=${track.id}", source.headers))
                }.getOrNull()
                val dataObj = infoJson?.optJSONObject("data")
                if (cover.isBlank()) {
                    cover = dataObj?.optJSONObject("songinfo")?.optString("pic").orEmpty().trim()
                }
                if (lrcLines.isEmpty()) {
                    val arr = dataObj?.optJSONArray("lrclist")
                    if (arr != null) {
                        val parsed = mutableListOf<LrcLine>()
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val text = item.optString("lineLyric").trim()
                            val sec = item.optString("time").toDoubleOrNull() ?: continue
                            if (text.isNotBlank()) parsed.add(LrcLine((sec * 1000).toLong(), text))
                        }
                        lrcLines = parsed.sortedBy { it.timeMs }
                    }
                }
            }

            MusicSourceType.GDSTUDIO_API -> {
                val base = source.baseUrl.trimEnd('/')
                val plat = track.platform.ifBlank { source.platform.ifBlank { "netease" } }
                if (cover.isBlank() && track.picId.isNotBlank()) {
                    cover = runCatching {
                        JSONObject(NetworkClient.getString("$base?types=pic&source=$plat&id=${encode(track.picId)}&size=500", source.headers))
                            .optString("url").trim()
                    }.getOrDefault("")
                }
                if (lrcLines.isEmpty() && track.lyricId.isNotBlank()) {
                    val lrcText = runCatching {
                        JSONObject(NetworkClient.getString("$base?types=lyric&source=$plat&id=${encode(track.lyricId)}", source.headers))
                            .optString("lyric").trim()
                    }.getOrDefault("")
                    lrcLines = parseLrc(lrcText)
                }
            }

            MusicSourceType.METING_API -> {
                if (lrcLines.isEmpty() && track.lyricUrl.startsWith("http")) {
                    val lrcText = runCatching { NetworkClient.getString(track.lyricUrl, source.headers) }.getOrDefault("")
                    lrcLines = parseLrc(lrcText)
                }
            }

            else -> Unit
        }
        cover to lrcLines
    }

    suspend fun resolvePlayableTrack(
        source: MusicSource,
        track: MusicTrack,
        preferHighBitrate: Boolean = true
    ): MusicTrack {
        return resolveStreamUrlFast(source, track, preferHighBitrate)
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

    @Volatile
    private var cachedBiliSpiCookie: String = ""

    private fun bilibiliHeaders(extra: Map<String, String>): Map<String, String> {
        val pcUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        if (cachedBiliSpiCookie.isBlank()) {
            cachedBiliSpiCookie = runCatching {
                val raw = NetworkClient.getString(
                    "https://api.bilibili.com/x/frontend/finger/spi",
                    mapOf("User-Agent" to pcUa, "Referer" to "https://www.bilibili.com/")
                )
                val d = JSONObject(raw).optJSONObject("data")
                val b3 = d?.optString("b_3").orEmpty()
                val b4 = d?.optString("b_4").orEmpty()
                if (b3.isNotBlank()) "buvid3=$b3; buvid4=$b4" else ""
            }.getOrDefault("")
        }
        val base = linkedMapOf(
            "User-Agent" to pcUa,
            "Referer" to "https://www.bilibili.com/",
            "Origin" to "https://www.bilibili.com",
            "Cookie" to cachedBiliSpiCookie.ifBlank { "buvid3=11111111-2222-3333-4444-555555555555infoc" }
        )
        base.putAll(extra)
        return base
    }

    private fun searchBilibiliMusic(source: MusicSource, keyword: String, page: Int): List<MusicTrack> {
        val base = source.baseUrl.trimEnd('/').ifBlank { "https://api.bilibili.com" }
        val qLower = keyword.lowercase()
        val allowLongMix = listOf("串烧", "合集", "歌单", "演唱会", "live", "mix", "dj").any { qLower.contains(it) }
        val url = "$base/x/web-interface/search/type?search_type=video&keyword=${encode(keyword)}&page=${page.coerceAtLeast(1)}"
        val raw = NetworkClient.getString(url, bilibiliHeaders(source.headers))
        val arr = JSONObject(raw.trim()).optJSONObject("data")?.optJSONArray("result") ?: return emptyList()

        val list = mutableListOf<MusicTrack>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val bvid = obj.optString("bvid").trim()
            val rawTitle = stripHtmlEntities(obj.optString("title"))
            if (bvid.isBlank() || rawTitle.isBlank()) continue

            val durationMs = parseColonDurationToMs(obj.optString("duration"))
            // Skip short clips (< 75s) and long multi-hour compilations (> 12min) unless user asked for a mix
            if (durationMs in 1L..74_999L) continue
            if (!allowLongMix && durationMs > 720_000L) continue

            val rawPic = obj.optString("pic").trim()
            val coverUrl = when {
                rawPic.startsWith("//") -> "https:$rawPic"
                rawPic.startsWith("http://") -> rawPic.replaceFirst("http://", "https://")
                else -> rawPic
            }
            val author = obj.optString("author").trim().ifBlank { "B站音乐" }
            val (cleanTitle, cleanArtist) = parseBilibiliMusicTitleArtist(rawTitle, author)
            list.add(
                MusicTrack(
                    id = bvid,
                    sourceId = source.id,
                    sourceName = source.name,
                    title = cleanTitle,
                    artist = cleanArtist,
                    album = "B站全曲 · ${formatDurationLabel(durationMs)}",
                    coverUrl = coverUrl,
                    durationMs = durationMs,
                    platform = "bilibili"
                )
            )
        }
        return list.distinctBy { it.id }
    }

    private fun resolveBilibiliAudioUrl(
        source: MusicSource,
        track: MusicTrack,
        preferHighBitrate: Boolean
    ): MusicTrack {
        val base = source.baseUrl.trimEnd('/').ifBlank { "https://api.bilibili.com" }
        val headers = bilibiliHeaders(source.headers)
        val viewObj = JSONObject(
            NetworkClient.getString("$base/x/web-interface/view?bvid=${encode(track.id)}", headers)
        ).optJSONObject("data") ?: return track

        val cid = viewObj.optLong("cid", 0L)
        if (cid <= 0L) return track
        val viewPic = viewObj.optString("pic").trim().let {
            if (it.startsWith("http://")) it.replaceFirst("http://", "https://") else it
        }
        val durationSec = viewObj.optLong("duration", 0L)

        val playObj = JSONObject(
            NetworkClient.getString(
                "$base/x/player/playurl?bvid=${encode(track.id)}&cid=$cid&fnval=16&otype=json",
                headers
            )
        ).optJSONObject("data") ?: return track

        var audioUrl = ""
        val audioArr = playObj.optJSONObject("dash")?.optJSONArray("audio")
        if (audioArr != null && audioArr.length() > 0) {
            val candidates = (0 until audioArr.length()).mapNotNull { idx ->
                val a = audioArr.optJSONObject(idx) ?: return@mapNotNull null
                val url = a.optString("baseUrl").ifBlank { a.optString("base_url") }.trim()
                val backup = a.optJSONArray("backupUrl")?.optString(0)
                    ?.ifBlank { a.optJSONArray("backup_url")?.optString(0) }.orEmpty().trim()
                val best = url.ifBlank { backup }
                if (best.startsWith("http")) (a.optLong("bandwidth", 0L) to best) else null
            }
            audioUrl = if (preferHighBitrate) {
                candidates.maxByOrNull { it.first }?.second.orEmpty()
            } else {
                candidates.minByOrNull { it.first }?.second.orEmpty()
            }
        }
        if (audioUrl.isBlank()) {
            audioUrl = playObj.optJSONArray("durl")?.optJSONObject(0)?.optString("url").orEmpty().trim()
        }

        return track.copy(
            streamUrl = audioUrl,
            coverUrl = track.coverUrl.ifBlank { viewPic },
            durationMs = if (durationSec > 0L) durationSec * 1000L else track.durationMs
        )
    }

    private fun searchVkeys(source: MusicSource, keyword: String): List<MusicTrack> {
        val base = source.baseUrl.trimEnd('/')
        val url = "$base?word=${encode(keyword)}&num=25"
        val raw = NetworkClient.getString(url, source.headers)
        val arr = JSONObject(raw.trim()).optJSONArray("data") ?: return emptyList()
        val list = mutableListOf<MusicTrack>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id").trim()
            val title = obj.optString("song").ifBlank { obj.optString("name") }.trim()
            if (id.isBlank() || title.isBlank()) continue
            list.add(
                MusicTrack(
                    id = id,
                    sourceId = source.id,
                    sourceName = source.name,
                    title = title,
                    artist = obj.optString("singer").ifBlank { "未知歌手" },
                    album = obj.optString("album").trim(),
                    coverUrl = obj.optString("cover").trim(),
                    platform = source.platform.ifBlank { "tencent" }
                )
            )
        }
        return list
    }

    private fun fetchKuwoBang(source: MusicSource, bangId: String): List<MusicTrack> {
        val url = "http://kbangserver.kuwo.cn/ksong.s?from=pc&fmt=json&type=bang&data=content&id=$bangId&rn=35&pn=0"
        val raw = NetworkClient.getString(url, source.headers)
        val arr = JSONObject(raw.trim()).optJSONArray("musiclist") ?: return emptyList()
        val list = mutableListOf<MusicTrack>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id").trim()
            val title = obj.optString("name").trim()
            if (id.isBlank() || title.isBlank()) continue
            val picShort = obj.optString("web_albumpic_short").trim()
            val cover = when {
                picShort.startsWith("http") -> picShort
                picShort.isNotBlank() -> "https://img4.kuwo.cn/star/albumcover/$picShort"
                else -> ""
            }
            list.add(
                MusicTrack(
                    id = id,
                    sourceId = source.id,
                    sourceName = source.name,
                    title = title,
                    artist = obj.optString("artist").ifBlank { "未知歌手" },
                    album = obj.optString("album").trim(),
                    coverUrl = cover,
                    durationMs = obj.optLong("duration", 0L) * 1000L,
                    platform = "kuwo"
                )
            )
        }
        return list
    }

    private fun searchKuwoDirect(source: MusicSource, keyword: String): List<MusicTrack> {
        val url = "http://search.kuwo.cn/r.s?all=${encode(keyword)}&ft=music&rformat=json&encoding=utf8&rn=25&pn=0"
        val raw = NetworkClient.getString(url, source.headers).trim()
        // Kuwo search returns single-quoted JSON keys; org.json JSONObject accepts single quotes natively
        val root = JSONObject(raw)
        val arr = root.optJSONArray("abslist") ?: return emptyList()
        val list = mutableListOf<MusicTrack>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("DC_TARGETID")
                .ifBlank { obj.optString("MUSICRID").removePrefix("MUSIC_") }
                .trim()
            val title = obj.optString("NAME").ifBlank { obj.optString("SONGNAME") }.trim()
            if (id.isBlank() || title.isBlank()) continue
            val picShort = obj.optString("web_albumpic_short").trim()
            val cover = when {
                picShort.startsWith("http") -> picShort
                picShort.isNotBlank() -> "https://img4.kuwo.cn/star/albumcover/$picShort"
                else -> ""
            }
            list.add(
                MusicTrack(
                    id = id,
                    sourceId = source.id,
                    sourceName = source.name,
                    title = title.replace("&nbsp;", " "),
                    artist = obj.optString("ARTIST").replace("&nbsp;", " ").ifBlank { "未知歌手" },
                    album = obj.optString("ALBUM").replace("&nbsp;", " ").trim(),
                    coverUrl = cover,
                    durationMs = obj.optLong("DURATION", 0L) * 1000L,
                    platform = "kuwo"
                )
            )
        }
        return list
    }

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
        private val htmlTagRegex = Regex("""<[^>]+>""")

        /** 伴奏 / 消音 / KTV / 试听片段过滤关键词 */
        private val accompanimentKeywords = listOf(
            "伴奏", "ktv", "消音", "纯伴奏", "和声伴奏", "原版伴奏", "高清伴奏", "钢琴伴奏", "吉他伴奏",
            "无主唱", "无人声", "去人声", "卡拉ok", "karaoke", "backing track", "off vocal",
            "inst.", "(inst)", "（inst）", "试听版", "试听片段", "30秒试听", "铃声版", "手机铃声"
        )

        private val allowAccompanimentQueryHints = listOf(
            "伴奏", "ktv", "消音", "karaoke", "backing", "off vocal", "卡拉ok", "铃声"
        )

        /**
         * 智能过滤伴奏曲目与超短试听片段（<= 65s），确保返回完整版人声歌曲。
         * 若用户主动搜索含「伴奏/KTV」关键词，则自动放行伴奏结果。
         */
        fun filterFullVocalTracks(tracks: List<MusicTrack>, query: String = ""): List<MusicTrack> {
            if (tracks.isEmpty()) return emptyList()
            val qLower = query.lowercase()
            val userExplicitlyWantsAccompaniment = allowAccompanimentQueryHints.any { qLower.contains(it) }
            val userWantsInstrumental = listOf("纯音乐", "轻音乐", "纯音", "钢琴曲", "instrumental").any { qLower.contains(it) }

            val filtered = tracks.filter { track ->
                if (track.isLiveRadio) return@filter true
                // 1. Filter out known short VIP preview clips (<= 65s)
                if (track.durationMs in 1L..65_000L) return@filter false
                // 2. Filter out accompaniments / karaoke / backing tracks unless user explicitly requested them
                if (!userExplicitlyWantsAccompaniment) {
                    val text = "${track.title} ${track.album}".lowercase()
                    if (accompanimentKeywords.any { text.contains(it) }) return@filter false
                    if (!userWantsInstrumental && text.contains("instrumental")) return@filter false
                }
                true
            }
            return filtered
        }

        fun stripHtmlEntities(raw: String): String {
            return raw.replace(htmlTagRegex, "")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                .trim()
        }

        fun parseColonDurationToMs(durationStr: String): Long {
            val parts = durationStr.trim().split(":").mapNotNull { it.toLongOrNull() }
            return when (parts.size) {
                2 -> (parts[0] * 60L + parts[1]) * 1000L
                3 -> (parts[0] * 3600L + parts[1] * 60L + parts[2]) * 1000L
                else -> 0L
            }
        }

        private fun formatDurationLabel(durationMs: Long): String {
            if (durationMs <= 0L) return "完整版"
            val totalSec = durationMs / 1000L
            return "%d:%02d".format(totalSec / 60L, totalSec % 60L)
        }

        private fun parseBilibiliMusicTitleArtist(rawTitle: String, fallbackUp: String): Pair<String, String> {
            val cleaned = rawTitle
                .replace(Regex("""[【\[](Hi-Res|FLAC|无损音质|无损|高清|4K|MV|完整版|歌词版|纯享版|官方MV|Official|HQ|SQ)[】\]]""", RegexOption.IGNORE_CASE), "")
                .trim()
            val quoted = Regex("""《([^》]+)》""").find(cleaned)?.groupValues?.get(1)?.trim()
            if (!quoted.isNullOrBlank()) {
                val prefix = cleaned.substringBefore("《")
                    .replace(Regex("""[【\[].*?[】\]]"""), "")
                    .replace(Regex("""[-—_|·/]+$"""), "")
                    .trim()
                val artist = if (prefix.length in 1..18) prefix else fallbackUp
                return quoted to artist
            }
            val dashParts = cleaned.split(" - ", " — ", limit = 2)
            if (dashParts.size == 2 && dashParts[0].trim().length in 1..18 && dashParts[1].trim().isNotBlank()) {
                return dashParts[1].trim() to dashParts[0].trim()
            }
            return cleaned.ifBlank { rawTitle } to fallbackUp
        }

        /**
         * 跨音源同曲容灾匹配算法：从候选列表中挑选与目标歌曲最匹配的同一首完整版歌曲
         */
        fun pickBestSameSongCandidate(target: MusicTrack, candidates: List<MusicTrack>): MusicTrack? {
            if (candidates.isEmpty()) return null
            val vocalCandidates = filterFullVocalTracks(candidates, target.title).ifEmpty { candidates }
            val targetTitle = normalizeSongToken(target.title)
            val targetArtist = normalizeSongToken(target.artist.substringBefore("/").substringBefore("·"))
            val unwantedKeywords = listOf("伴奏", "纯音乐", "dj版", "铃声", "翻唱", "karaoke", "instrumental", "ktv", "消音")
            val targetRawLower = target.title.lowercase()

            return vocalCandidates
                .map { cand ->
                    val candTitle = normalizeSongToken(cand.title)
                    val candArtist = normalizeSongToken(cand.artist)
                    val candRawLower = cand.title.lowercase()
                    var score = when {
                        candTitle.isEmpty() || targetTitle.isEmpty() -> 0
                        candTitle == targetTitle -> 100
                        candTitle.contains(targetTitle) || targetTitle.contains(candTitle) -> {
                            val ratio = minOf(candTitle.length, targetTitle.length).toFloat() /
                                maxOf(candTitle.length, targetTitle.length).toFloat()
                            if (ratio >= 0.45f) 65 else 0
                        }
                        else -> 0
                    }
                    if (score > 0 && targetArtist.isNotEmpty() && targetArtist != "未知歌手") {
                        score += when {
                            candArtist == targetArtist -> 50
                            candArtist.contains(targetArtist) || targetArtist.contains(candArtist) -> 30
                            else -> 0
                        }
                    }
                    if (score > 0) {
                        if (cand.durationMs in 1L..65_000L) score -= 80
                        else if (cand.durationMs in 90_000L..600_000L) score += 15
                        for (kw in unwantedKeywords) {
                            if (candRawLower.contains(kw) && !targetRawLower.contains(kw)) {
                                score -= 45
                            }
                        }
                    }
                    cand to score
                }
                .filter { it.second >= 50 }
                .maxByOrNull { it.second }
                ?.first
        }

        private fun normalizeSongToken(raw: String): String {
            return raw.lowercase()
                .replace(Regex("""[（(【\[].*?[）)】\]]"""), "")
                .replace(Regex("""[\s\-_·.,!?'":;《》<>]"""), "")
                .trim()
        }

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
