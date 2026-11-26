package com.cinecast.app

import com.cinecast.app.novel.engine.LegadoSourceParser
import com.cinecast.app.novel.engine.NovelRuleEngine
import com.cinecast.app.novel.engine.NovelSourceEngine
import com.cinecast.app.novel.model.NovelSourceType
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class NovelEngineUnitTest {

    @Test
    fun testLegadoSourceParserWithXiu2AndLiufuyouFormats() {
        val sampleLegadoJson = """
        [
          {
            "bookSourceName": "酷我小说",
            "bookSourceUrl": "http://appi.kuwo.cn",
            "bookSourceGroup": "API",
            "bookSourceType": 0,
            "enabled": true,
            "searchUrl": "/novels/api/book/search?keyword={{key}}&pi={{page}}&ps=30",
            "ruleSearch": {
              "bookList": "${'$'}.data",
              "name": "${'$'}.title",
              "author": "${'$'}.author_name",
              "bookUrl": "/novels/api/book/{{${'$'}.book_id}}"
            },
            "ruleToc": {
              "chapterList": "${'$'}.data",
              "chapterName": "${'$'}.chapter_title",
              "chapterUrl": "/novels/api/book/{{${'$'}.book_id}}/chapters/{{${'$'}.chapter_id}}"
            },
            "ruleContent": {
              "content": "${'$'}.data.content"
            }
          },
          {
            "bookSourceName": "全本同人",
            "bookSourceUrl": "https://www.qbtr.me",
            "bookSourceGroup": "小说源",
            "bookSourceType": 0,
            "enabled": true,
            "searchUrl": "/e/search/index.php,{\"charset\":\"gb2312\",\"method\":\"POST\",\"body\":\"keyboard={{key}}\"}",
            "exploreUrl": "同人小说::/tongren/<,index_{{page}}.html>\n常规小说::/changgui/<,index_{{page}}.html>",
            "ruleSearch": {
              "bookList": ".bk",
              "name": "h3@text",
              "bookUrl": "a.0@href"
            },
            "ruleToc": {
              "chapterList": ".book_list a",
              "chapterName": "text",
              "chapterUrl": "href"
            },
            "ruleContent": {
              "content": ".read_chapterDetail@html"
            }
          },
          {
            "bookSourceName": "纯JS动态源",
            "bookSourceUrl": "https://js-only.example.com",
            "bookSourceType": 0,
            "searchUrl": "@js:return 'https://x'",
            "ruleContent": {
              "content": "<js>eval(result)</js>"
            }
          },
          {
            "bookSourceName": "有声听书源",
            "bookSourceUrl": "https://audio.example.com",
            "bookSourceType": 1,
            "searchUrl": "/s?q={{key}}"
          }
        ]
        """.trimIndent()

        val parser = LegadoSourceParser()
        val result = parser.parseJsonText(
            sampleLegadoJson,
            "https://fastly.jsdelivr.net/gh/XIU2/Yuedu@master/shuyuan"
        )

        assertEquals(4, result.totalRawCount)
        assertEquals(1, result.skippedJsOnlyCount)
        assertEquals(2, result.sources.size)

        val kuwo = result.sources[0]
        assertEquals("酷我小说", kuwo.name)
        assertEquals(NovelSourceType.LEGADO_JSON, kuwo.sourceType)

        val qbtr = result.sources[1]
        assertEquals("全本同人", qbtr.name)
        assertEquals(NovelSourceType.LEGADO_HTML, qbtr.sourceType)
        assertEquals("GB2312", qbtr.charset)

        val exploreCats = parser.parseExploreCategories(qbtr.exploreUrl)
        assertEquals(2, exploreCats.size)
        assertEquals("同人小说", exploreCats[0].title)
        assertEquals("/tongren/<,index_{{page}}.html>", exploreCats[0].url)
    }

    @Test
    fun testNovelRuleEngineJsonPathAndTemplates() {
        val raw = """
        {
          "code": 200,
          "data": [
            {
              "book_id": "22255080301908204",
              "title": "斗罗：顶级天赋",
              "author_name": "临界水水"
            }
          ]
        }
        """.trimIndent()
        val root = JSONObject(raw)
        val list = NovelRuleEngine.selectJsonList(root, "$.data")
        assertEquals(1, list.size)
        assertEquals("斗罗：顶级天赋", NovelRuleEngine.evalJsonString(list[0], "$.title"))
        assertEquals(
            "/novels/api/book/22255080301908204",
            NovelRuleEngine.evalJsonString(list[0], "/novels/api/book/{{$.book_id}}")
        )
    }

    @Test
    fun testNovelRuleEngineHtmlLegadoChainsAndXPath() {
        val html = """
        <html>
          <head>
            <meta property="og:novel:author" content="唐家三少" />
          </head>
          <body>
            <div class="bk">
              <h3><a href="/book/1001.html">绝世唐门(全本)</a></h3>
              <p>简介：少年崛起的故事</p>
            </div>
            <div id="list">
              <a href="/book/1001/1.html">第一章 觉醒</a>
              <a href="/book/1001/2.html">第二章 史莱克</a>
            </div>
            <div class="con">
              <p>第一段正文内容。</p>
              <p>第二段正文内容。(本章完)</p>
            </div>
          </body>
        </html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://example.com")

        // XPath meta extraction
        assertEquals("唐家三少", NovelRuleEngine.evalHtmlString(doc, "//meta[@property='og:novel:author']/@content"))

        // Legado CSS + regex stripping
        val books = NovelRuleEngine.selectElements(doc, ".bk")
        assertEquals(1, books.size)
        assertEquals("绝世唐门", NovelRuleEngine.evalHtmlString(books[0], "h3@text##\\(\\S+"))
        assertEquals("https://example.com/book/1001.html", NovelRuleEngine.evalHtmlString(books[0], "a.0@href", "https://example.com"))

        // TOC extraction
        val tocEls = NovelRuleEngine.selectElements(doc, "id.list.0@tag.a")
        assertEquals(2, tocEls.size)
        assertEquals("第一章 觉醒", NovelRuleEngine.evalHtmlString(tocEls[0], "text"))
        assertEquals("https://example.com/book/1001/2.html", NovelRuleEngine.evalHtmlString(tocEls[1], "href", "https://example.com"))

        // Content extraction + regex cleanup
        val content = NovelRuleEngine.evalHtmlString(doc, "class.con.0@html##[\\(（]本章完[\\)）]$")
        assertTrue(content.contains("第一段正文内容。"))
        assertTrue(content.contains("第二段正文内容。"))
        assertFalse(content.contains("本章完"))
    }

    @Test
    fun testUrlExpansionAndGutenbergChapterSplitting() {
        val engine = NovelSourceEngine()
        assertEquals("/tongren/", engine.expandUrlTemplate("/tongren/<,index_{{page}}.html>", "", 1, "UTF-8"))
        assertEquals("/tongren/index_2.html", engine.expandUrlTemplate("/tongren/<,index_{{page}}.html>", "", 2, "UTF-8"))

        val sampleClassic = """
            第一回 灵根育孕源流出
            东胜神洲傲来国海边有一花果山，山上有一块仙石，内育仙胞，一日迸裂，产一石卵。

            第二回 悟彻菩提真妙理
            美猴王漂洋过海寻访仙师，终至灵台方寸山斜月三星洞，拜菩提祖师为师。

            第三回 四海千山皆拱伏
            悟空学成七十二变与筋斗云，回到花果山，闯东海龙宫得如意金箍棒。
        """.trimIndent()

        val chapters = engine.splitPlainTextIntoChapters(sampleClassic, "https://gutendex.com/test.txt")
        assertEquals(3, chapters.size)
        assertTrue(chapters[0].title.contains("第一回"))
        assertTrue(chapters[2].inlineContent.orEmpty().contains("如意金箍棒"))
    }

    @Test
    fun testWikisourceHtmlCleaningAndClassicsCategories() {
        val engine = NovelSourceEngine()
        val wsSource = com.cinecast.app.novel.model.NovelSource(
            id = "open_wikisource_classics",
            name = "维基文库名著典藏",
            baseUrl = "https://zh.wikisource.org",
            sourceType = NovelSourceType.WIKISOURCE_API
        )
        val cats = engine.getExploreCategories(wsSource)
        assertEquals(5, cats.size)
        assertEquals("四大名著与演义", cats[0].title)

        val rawHtml = """
            <div class="mw-parser-output">
              <table class="headerContainer"><tr><td>导航栏上一回下一回</td></tr></table>
              <span class="mw-editsection">[编辑]</span>
              <p>话说天下大势，分久必合，合久必分。<sup class="reference">[1]</sup></p>
              <p>周末七国分争，并入于秦。及秦灭之后，楚、汉分争，又并入于汉。</p>
            </div>
        """.trimIndent()
        val cleaned = engine.parseWikisourceHtml(rawHtml)
        assertTrue(cleaned.contains("话说天下大势，分久必合，合久必分。"))
        assertTrue(cleaned.contains("周末七国分争"))
        assertFalse(cleaned.contains("导航栏"))
        assertFalse(cleaned.contains("[编辑]"))
        assertFalse(cleaned.contains("[1]"))
    }

    @Test
    fun testYangyangStrictEnglishCartoonFilterAndXimalayaPingshuParser() {
        val enCartoon = com.cinecast.app.data.model.VodItem(
            id = "1", name = "数字积木第五季", lang = "英语", typeName = "欧美动漫", area = "英国"
        )
        val cnDubCartoon = com.cinecast.app.data.model.VodItem(
            id = "2", name = "小猪佩奇第十二季国语", lang = "国语", typeName = "欧美动漫", area = "英国"
        )
        val jpAnime = com.cinecast.app.data.model.VodItem(
            id = "3", name = "名侦探柯南", lang = "日语", typeName = "日本动漫", area = "日本"
        )
        val enLiveDrama = com.cinecast.app.data.model.VodItem(
            id = "4", name = "绝命毒师", lang = "英语", typeName = "欧美剧", area = "美国"
        )
        val explicitEnCartoon = com.cinecast.app.data.model.VodItem(
            id = "5", name = "成龙历险记全季合集（英文版）", lang = "", typeName = "动画片", area = "美国"
        )

        assertTrue(com.cinecast.app.data.engine.VodSourceEngine.isStrictEnglishCartoon(enCartoon))
        assertTrue(com.cinecast.app.data.engine.VodSourceEngine.isStrictEnglishCartoon(explicitEnCartoon))
        assertFalse(com.cinecast.app.data.engine.VodSourceEngine.isStrictEnglishCartoon(cnDubCartoon))
        assertFalse(com.cinecast.app.data.engine.VodSourceEngine.isStrictEnglishCartoon(jpAnime))
        assertFalse(com.cinecast.app.data.engine.VodSourceEngine.isStrictEnglishCartoon(enLiveDrama))

        // Verify Ximalaya free pingshu album parser filters out paid albums cleanly
        val xmJson = """
        {
          "ret": 200,
          "data": {
            "albums": [
              {"albumId": 2677356, "albumTitle": "白眉大侠 | 单田芳评书", "albumUserNickName": "单田芳评书", "albumTrackCount": 401, "albumPlayCount": 4010570782, "isPaid": false, "albumCoverPath": "//imagev2.xmcdn.com/test.png"},
              {"albumId": 15358207, "albumTitle": "付费三国演义", "albumUserNickName": "某主播", "albumTrackCount": 280, "albumPlayCount": 10000, "isPaid": true}
            ]
          }
        }
        """.trimIndent()
        val engine = NovelSourceEngine()
        val src = com.cinecast.app.novel.model.NovelSource(
            id = "open_ximalaya_pingshu",
            name = "喜马拉雅评书馆",
            baseUrl = "https://www.ximalaya.com",
            sourceType = NovelSourceType.XIMALAYA_PINGSHU
        )
        val parsed = engine.parseXimalayaCategoryAlbums(src, xmJson)
        assertEquals(1, parsed.size)
        assertEquals("ximalaya_album://2677356", parsed[0].bookUrl)
        assertEquals("单田芳评书", parsed[0].author)
    }
}
