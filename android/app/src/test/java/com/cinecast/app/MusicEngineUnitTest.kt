package com.cinecast.app

import com.cinecast.app.music.engine.MusicSourceEngine
import com.cinecast.app.music.engine.MusicSourceParser
import com.cinecast.app.music.model.MusicSourceType
import org.junit.Assert.*
import org.junit.Test

class MusicEngineUnitTest {

    @Test
    fun testMusicFreePluginsJsonImport() {
        val samplePluginsJson = """
        {
          "plugins": [
            {"name": "小芸网易音乐", "url": "https://fastly.jsdelivr.net/gh/Huibq/keep-alive/Music_Free/xiaoyun.js", "version": "0.3.0"},
            {"name": "小秋音乐", "url": "https://fastly.jsdelivr.net/gh/Huibq/keep-alive/Music_Free/xiaoqiu.js", "version": "0.3.0"},
            {"name": "全球在线电台FM", "url": "https://example.com/radio.js", "version": "1.0.0"},
            {"name": "Audiomack", "url": "https://example.com/am.js", "version": "0.1.0"}
          ]
        }
        """.trimIndent()

        val parser = MusicSourceParser()
        val res = parser.parseImportText(
            samplePluginsJson,
            "https://fastly.jsdelivr.net/gh/Huibq/keep-alive/Music_Free/myPlugins.json"
        )

        assertEquals(4, res.totalRawCount)
        assertEquals(4, res.sources.size)
        assertEquals(MusicSourceType.GDSTUDIO_API, res.sources[0].sourceType)
        assertEquals(MusicSourceType.METING_API, res.sources[1].sourceType)
        assertEquals(MusicSourceType.RADIO_BROWSER, res.sources[2].sourceType)
        assertEquals(MusicSourceType.AUDIUS_API, res.sources[3].sourceType)
    }

    @Test
    fun testCineCastMusicJsonAndM3uImport() {
        val customJson = """
        [
          {
            "name": "自建 Meting 音乐源",
            "baseUrl": "https://api.injahow.cn/meting/",
            "sourceType": "METING_API",
            "platform": "netease",
            "charts": [{"title": "热歌榜", "idOrQuery": "3778678"}]
          }
        ]
        """.trimIndent()

        val parser = MusicSourceParser()
        val res = parser.parseImportText(customJson, "https://example.com/music.json")
        assertEquals(1, res.sources.size)
        assertEquals("自建 Meting 音乐源", res.sources[0].name)
        assertEquals(MusicSourceType.METING_API, res.sources[0].sourceType)

        val m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="音乐台",怀集音乐之声
            https://stream.example.com/radio1.m3u8
        """.trimIndent()
        val m3uRes = parser.parseImportText(m3u, "https://example.com/radio.m3u")
        assertEquals(1, m3uRes.sources.size)
        assertEquals(MusicSourceType.M3U_AUDIO, m3uRes.sources[0].sourceType)
    }

    @Test
    fun testLrcSyncedLyricsParser() {
        val sampleLrc = """
            [00:00.00]作词 : 周杰伦
            [00:12.35]对这个世界如果你有太多的抱怨
            [00:15.80][01:20.50]跌倒了就不敢继续往前走
            [00:20.00]
        """.trimIndent()

        val parsed = MusicSourceEngine.parseLrc(sampleLrc)
        assertEquals(4, parsed.size)
        assertEquals(0L, parsed[0].timeMs)
        assertEquals("作词 : 周杰伦", parsed[0].text)
        assertEquals(12350L, parsed[1].timeMs)
        assertEquals(15800L, parsed[2].timeMs)
        assertEquals(80500L, parsed[3].timeMs)
        assertEquals("跌倒了就不敢继续往前走", parsed[3].text)
    }
}
