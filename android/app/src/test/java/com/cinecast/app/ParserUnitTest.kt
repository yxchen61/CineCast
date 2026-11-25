package com.cinecast.app

import com.cinecast.app.data.engine.LiveParser
import com.cinecast.app.data.engine.TvBoxConfigParser
import com.cinecast.app.data.model.SourceType
import org.junit.Assert.*
import org.junit.Test

class ParserUnitTest {

    @Test
    fun testTvBoxConfigParserWithCommentsAndCyao2qFormat() {
        val sampleJson = """
        {
            // Comment line like cyao2q/files/m.json
            "spider": "./jar/Bee.jar;md5;1234",
            "sites": [
                {
                    "key": "非凡影视",
                    "name": "非凡影视",
                    "type": 0,
                    "api": "http://cj.ffzyapi.com/api.php/provide/vod/at/xml/",
                    "searchable": 1,
                    "categories": ["国产剧", "动作片"]
                },
                {
                    "key": "量子JSON",
                    "name": "量子资源",
                    "type": 1,
                    "api": "https://cj.lziapi.com/api.php/provide/vod/",
                    "searchable": 1
                },
                {
                    "key": "糯米秒播",
                    "name": "糯米秒播",
                    "type": 3,
                    "api": "csp_NmyswvGuard"
                }
            ],
            "parses": [
                {"name": "xmflv", "type": 0, "url": "https://jx.xmflv.com/?url="}
            ],
            "lives": [
                {"name": "Kimentanm", "type": 0, "url": "https://example.com/iptv.m3u"}
            ],
            "ads": ["hm.baidu.com", "s23.cnzz.com"]
        }
        """.trimIndent()

        val parser = TvBoxConfigParser()
        val result = parser.parseJsonText(sampleJson, "https://cyao2q.github.io/files/m.json")

        assertEquals(3, result.totalRawSites)
        assertEquals(2, result.directPlayableCount)
        assertEquals(SourceType.MACCMS_XML, result.sources[0].type)
        assertEquals(SourceType.MACCMS_JSON, result.sources[1].type)
        assertEquals(SourceType.TVBOX_SPIDER, result.sources[2].type)
        assertEquals(1, result.parses.size)
        assertEquals(1, result.liveSources.size)
        assertEquals(2, result.adHosts.size)
    }

    @Test
    fun testLiveParserM3uAndTxt() {
        val parser = LiveParser()

        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-logo="https://logo.png" group-title="央视",CCTV-1 综合
            https://stream.example.com/cctv1.m3u8
            #EXTINF:-1 group-title="央视",CCTV-1 综合
            https://backup.example.com/cctv1.m3u8
            #EXTINF:-1 group-title="卫视",湖南卫视
            https://stream.example.com/hunan.m3u8
        """.trimIndent()

        val m3uChannels = parser.parse(m3u)
        assertEquals(2, m3uChannels.size)
        assertEquals("CCTV-1 综合", m3uChannels[0].name)
        assertEquals(2, m3uChannels[0].urls.size)

        val txt = """
            央视高清,#genre#
            CCTV-5,https://live.example.com/cctv5_a.m3u8#https://live.example.com/cctv5_b.m3u8
            体育频道,#genre#
            五星体育,https://live.example.com/sports.m3u8
        """.trimIndent()

        val txtChannels = parser.parse(txt)
        assertEquals(2, txtChannels.size)
        assertEquals("央视高清", txtChannels[0].group)
        assertEquals(2, txtChannels[0].urls.size)
    }
}
