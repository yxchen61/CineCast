package com.cinecast.app.data.engine

import com.cinecast.app.data.model.LiveChannel
import com.cinecast.app.data.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LiveParser {

    suspend fun fetchChannels(url: String): List<LiveChannel> = withContext(Dispatchers.IO) {
        val raw = NetworkClient.getString(url)
        parse(raw)
    }

    fun parse(raw: String): List<LiveChannel> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return emptyList()
        return if (trimmed.contains("#EXTM3U", ignoreCase = true) || trimmed.contains("#EXTINF", ignoreCase = true)) {
            parseM3u(trimmed)
        } else {
            parseTvBoxTxt(trimmed)
        }
    }

    private fun parseM3u(raw: String): List<LiveChannel> {
        val lines = raw.lines()
        val channelMap = LinkedHashMap<String, MutableChannel>()
        var currentName = ""
        var currentGroup = "默认频道"
        var currentLogo = ""

        val groupRegex = Regex("""group-title="([^"]*)"""")
        val logoRegex = Regex("""tvg-logo="([^"]*)"""")

        for (line in lines) {
            val t = line.trim()
            if (t.isBlank()) continue
            if (t.startsWith("#EXTINF", ignoreCase = true)) {
                currentGroup = groupRegex.find(t)?.groupValues?.getOrNull(1)?.ifBlank { "默认频道" } ?: "默认频道"
                currentLogo = logoRegex.find(t)?.groupValues?.getOrNull(1).orEmpty()
                currentName = t.substringAfterLast(',').trim().ifBlank { "未知频道" }
            } else if (!t.startsWith("#") && (t.startsWith("http", ignoreCase = true) || t.startsWith("rtmp", ignoreCase = true) || t.startsWith("rtsp", ignoreCase = true))) {
                val key = "$currentGroup|$currentName"
                val entry = channelMap.getOrPut(key) {
                    MutableChannel(currentGroup, currentName.ifBlank { "直播频道" }, currentLogo)
                }
                if (t !in entry.urls) entry.urls.add(t)
            }
        }
        return channelMap.values.map { LiveChannel(it.group, it.name, it.urls.toList(), it.logo) }
    }

    private fun parseTvBoxTxt(raw: String): List<LiveChannel> {
        val lines = raw.lines()
        val channelMap = LinkedHashMap<String, MutableChannel>()
        var currentGroup = "精选频道"

        for (line in lines) {
            val t = line.trim()
            if (t.isBlank() || t.startsWith("#")) continue
            if (t.contains(",#genre#", ignoreCase = true)) {
                currentGroup = t.substringBefore(",#genre#").trim().ifBlank { "精选频道" }
                continue
            }
            val commaIdx = t.indexOf(',')
            if (commaIdx <= 0) continue
            val name = t.substring(0, commaIdx).trim()
            val urlPart = t.substring(commaIdx + 1).trim()
            val urls = urlPart.split("#").map { it.trim() }.filter {
                it.startsWith("http", ignoreCase = true) || it.startsWith("rtmp", ignoreCase = true)
            }
            if (name.isNotBlank() && urls.isNotEmpty()) {
                val key = "$currentGroup|$name"
                val entry = channelMap.getOrPut(key) { MutableChannel(currentGroup, name, "") }
                urls.forEach { if (it !in entry.urls) entry.urls.add(it) }
            }
        }
        return channelMap.values.map { LiveChannel(it.group, it.name, it.urls.toList(), it.logo) }
    }

    private class MutableChannel(
        val group: String,
        val name: String,
        val logo: String,
        val urls: MutableList<String> = mutableListOf()
    )
}
