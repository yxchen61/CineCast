import Foundation

struct NovelImportResult {
    let subscription: NovelSubscription
    let sources: [NovelSource]
    let totalRawCount: Int
    let skippedJsOnlyCount: Int
}

final class NovelSourceEngine {
    static let shared = NovelSourceEngine()
    private var gutenbergCache: [String: [NovelChapter]] = [:]

    private init() {}

    // MARK: - Explore Categories

    func getExploreCategories(source: NovelSource) -> [NovelExploreCategory] {
        if source.sourceType == .CLASSICS_5000YAN {
            return [
                NovelExploreCategory(title: "四大名著", url: "yan5000_cat://sida"),
                NovelExploreCategory(title: "古典小说名著", url: "yan5000_cat://classical"),
                NovelExploreCategory(title: "史学与神话", url: "yan5000_cat://history"),
                NovelExploreCategory(title: "国学经典与诗词", url: "yan5000_cat://guoxue")
            ]
        }
        if source.sourceType == .XIMALAYA_PINGSHU {
            return [
                NovelExploreCategory(title: "热门免费评书", url: "xm_cat://hot"),
                NovelExploreCategory(title: "单田芳经典", url: "xm_cat://shantianfang"),
                NovelExploreCategory(title: "历史演义", url: "xm_cat://history")
            ]
        }
        if source.sourceType == .KUWO_PINGSHU {
            return [
                NovelExploreCategory(title: "单田芳全集", url: "kw_cat://shantianfang"),
                NovelExploreCategory(title: "连丽如/刘兰芳", url: "kw_cat://masters"),
                NovelExploreCategory(title: "郭德纲评书", url: "kw_cat://guodegang")
            ]
        }
        if source.sourceType == .WIKISOURCE_API {
            return [
                NovelExploreCategory(title: "四大名著与演义", url: "ws_cat://sida"),
                NovelExploreCategory(title: "古典小说名著", url: "ws_cat://classical"),
                NovelExploreCategory(title: "鲁迅与现当代经典", url: "ws_cat://modern"),
                NovelExploreCategory(title: "史学典籍与兵法", url: "ws_cat://history"),
                NovelExploreCategory(title: "国学哲学与诗词", url: "ws_cat://poetry")
            ]
        }
        if source.sourceType == .GUTENDEX_API {
            return [
                NovelExploreCategory(title: "中文古典名著", url: "https://gutendex.com/books/?languages=zh&sort=popular&page={{page}}"),
                NovelExploreCategory(title: "世界必读名著", url: "https://gutendex.com/books/?sort=popular&page={{page}}"),
                NovelExploreCategory(title: "侦探推理名著", url: "https://gutendex.com/books/?topic=detective&sort=popular&page={{page}}"),
                NovelExploreCategory(title: "科幻冒险经典", url: "https://gutendex.com/books/?topic=science+fiction&sort=popular&page={{page}}"),
                NovelExploreCategory(title: "历史哲学典籍", url: "https://gutendex.com/books/?topic=history&sort=popular&page={{page}}"),
                NovelExploreCategory(title: "童话与成长文学", url: "https://gutendex.com/books/?topic=children&sort=popular&page={{page}}")
            ]
        }
        let trimmed = source.exploreUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !trimmed.hasPrefix("@js:"), !trimmed.hasPrefix("<js>") else { return [] }

        if trimmed.hasPrefix("["),
           let data = trimmed.data(using: .utf8),
           let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] {
            let list = arr.compactMap { item -> NovelExploreCategory? in
                let title = (item["title"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                let url = (item["url"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                return (!title.isEmpty && !url.isEmpty) ? NovelExploreCategory(title: title, url: url) : nil
            }
            if !list.isEmpty { return list }
        }

        return trimmed.components(separatedBy: .newlines).compactMap { line in
            let parts = line.trimmingCharacters(in: .whitespaces).components(separatedBy: "::")
            guard parts.count >= 2 else { return nil }
            let title = parts[0].trimmingCharacters(in: .whitespaces)
            let url = parts[1].trimmingCharacters(in: .whitespaces)
            return (!title.isEmpty && !url.isEmpty) ? NovelExploreCategory(title: title, url: url) : nil
        }
    }

    // MARK: - Explore & Search

    func fetchExplore(source: NovelSource, categoryUrl: String, page: Int = 1) async throws -> [NovelBook] {
        if source.sourceType == .CLASSICS_5000YAN {
            return page > 1 ? [] : curated5000yanBooks(source: source, categoryUrl: categoryUrl)
        }
        if source.sourceType == .XIMALAYA_PINGSHU {
            return await fetchXimalayaPingshuExplore(source: source, categoryUrl: categoryUrl, page: page)
        }
        if source.sourceType == .KUWO_PINGSHU {
            return curatedKuwoPingshu(source: source, categoryUrl: categoryUrl)
        }
        if source.sourceType == .WIKISOURCE_API {
            return page > 1 ? [] : curatedWikisourceBooks(source: source, categoryUrl: categoryUrl)
        }
        if source.sourceType == .GUTENDEX_API {
            let url = expandTemplate(categoryUrl, keyword: "", page: page)
            let raw = try await fetchText(urlSpec: url, source: source)
            return parseGutendexBooks(source: source, rawJson: raw)
        }
        let spec = expandTemplate(categoryUrl, keyword: "", page: page)
        let raw = try await fetchText(urlSpec: spec, source: source)
        return parseBookList(source: source, rawBody: raw)
    }

    func search(source: NovelSource, keyword: String, page: Int = 1) async throws -> [NovelBook] {
        let q = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return [] }
        if source.sourceType == .CLASSICS_5000YAN {
            return curated5000yanBooks(source: source, categoryUrl: "all").filter {
                $0.title.localizedCaseInsensitiveContains(q) || $0.author.localizedCaseInsensitiveContains(q) || $0.kind.localizedCaseInsensitiveContains(q)
            }
        }
        if source.sourceType == .WIKISOURCE_API {
            return await searchWikisourceBooks(source: source, keyword: q)
        }
        if source.sourceType == .GUTENDEX_API {
            let enc = q.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? q
            let raw = try await fetchText(urlSpec: "https://gutendex.com/books/?search=\(enc)&page=\(page)", source: source)
            return parseGutendexBooks(source: source, rawJson: raw)
        }
        guard !source.searchUrl.isEmpty else { return [] }
        let spec = expandTemplate(source.searchUrl, keyword: q, page: page)
        let raw = try await fetchText(urlSpec: spec, source: source)
        return parseBookList(source: source, rawBody: raw)
    }

    func fetchBookDetailAndToc(source: NovelSource, book: NovelBook) async throws -> (NovelBook, [NovelChapter]) {
        if source.sourceType == .CLASSICS_5000YAN || book.bookUrl.contains(".5000yan.com") {
            let html = try await fetchText(urlSpec: book.bookUrl, source: source)
            let chs = parse5000yanToc(html: html, baseUrl: book.bookUrl)
            var updated = book
            updated.latestChapter = chs.last?.title ?? "全本典藏"
            return (updated, chs)
        }
        if source.sourceType == .XIMALAYA_PINGSHU || book.bookUrl.hasPrefix("ximalaya_album://") {
            let eps = await fetchXimalayaPingshuEpisodes(source: source, book: book)
            return (book, eps)
        }
        if source.sourceType == .KUWO_PINGSHU || book.bookUrl.hasPrefix("kuwo_album://") || book.bookUrl.hasPrefix("kuwo_search://") {
            let eps = await fetchKuwoPingshuEpisodes(source: source, book: book)
            return (book, eps)
        }
        if source.sourceType == .WIKISOURCE_API || book.bookUrl.hasPrefix("wikisource://") {
            let chs = await fetchWikisourceChapters(source: source, book: book)
            var updated = book
            updated.latestChapter = chs.last?.title ?? "全本典藏"
            return (updated, chs)
        }
        if source.sourceType == .GUTENDEX_API {
            let chapters = try await fetchGutendexChapters(book: book)
            var updated = book
            if let last = chapters.last { updated.latestChapter = last.title }
            return (updated, chapters)
        }

        let detailRaw = try await fetchText(urlSpec: book.bookUrl, source: source)
        var updated = book
        if source.sourceType == .LEGADO_JSON || detailRaw.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("{") {
            if let data = detailRaw.data(using: .utf8),
               let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                let target: Any = !source.ruleBookInfo.`init`.isEmpty
                    ? (evalJsonPath(obj, source.ruleBookInfo.`init`) ?? obj)
                    : obj
                let intro = evalJsonString(target, source.ruleBookInfo.intro)
                if !intro.isEmpty { updated.intro = intro }
                let tocRaw = evalJsonString(target, source.ruleBookInfo.tocUrl)
                if !tocRaw.isEmpty { updated.tocUrl = resolveUrl(base: source.baseUrl, relative: tocRaw) }
            }
        }

        let tocUrl: String
        if !updated.tocUrl.isEmpty {
            tocUrl = updated.tocUrl
        } else if source.sourceType == .LEGADO_JSON && source.baseUrl.contains("appi.kuwo.cn") {
            tocUrl = "\(book.bookUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/")))/chapters?pi=1&ps=2000"
        } else {
            tocUrl = book.bookUrl
        }

        let tocRaw = (tocUrl == book.bookUrl) ? detailRaw : (try await fetchText(urlSpec: tocUrl, source: source))
        let chapters = parseChapters(source: source, rawBody: tocRaw, baseUrl: tocUrl)
        updated.tocUrl = tocUrl
        if let last = chapters.last { updated.latestChapter = last.title }
        return (updated, chapters)
    }

    func fetchChapterContent(source: NovelSource, chapter: NovelChapter) async throws -> String {
        if let inline = chapter.inlineContent, !inline.isEmpty {
            return inline
        }
        if source.sourceType == .CLASSICS_5000YAN || chapter.url.contains(".5000yan.com") {
            let html = try await fetchText(urlSpec: chapter.url, source: source)
            let extracted = parse5000yanChapter(html: html)
            if extracted.isEmpty {
                throw NSError(domain: "CineCastNovel", code: -1, userInfo: [NSLocalizedDescriptionKey: "未能提取正文"])
            }
            return cleanParagraphs(extracted)
        }
        if source.sourceType == .WIKISOURCE_API || chapter.url.hasPrefix("wikisource://") {
            let page = String(chapter.url.dropFirst("wikisource://".count))
            return try await fetchWikisourceChapterText(source: source, pageTitle: page)
        }
        let raw = try await fetchText(urlSpec: chapter.url, source: source)
        if source.sourceType == .LEGADO_JSON || raw.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("{") {
            if let data = raw.data(using: .utf8),
               let obj = try? JSONSerialization.jsonObject(with: data) {
                let text = evalJsonString(obj, source.ruleContent.content.isEmpty ? "$.data.content" : source.ruleContent.content)
                if !text.isEmpty { return cleanParagraphs(text) }
            }
        }
        // HTML paragraph fallback extractor
        let extracted = extractReadableHtmlContent(raw)
        if extracted.isEmpty {
            throw NSError(domain: "CineCastNovel", code: -1, userInfo: [NSLocalizedDescriptionKey: "未能提取章节正文，请尝试换源"])
        }
        return cleanParagraphs(extracted)
    }

    func pingSource(_ source: NovelSource) async -> Int {
        let start = Date()
        do {
            let probe: String
            switch source.sourceType {
            case .CLASSICS_5000YAN: probe = "https://sanguo.5000yan.com/"
            case .WIKISOURCE_API: probe = "https://zh.wikisource.org/w/api.php?action=query&meta=siteinfo&format=json"
            case .GUTENDEX_API: probe = "https://gutendex.com/books/?languages=zh&page=1"
            default: probe = source.baseUrl
            }
            let txt = try await fetchText(urlSpec: probe, source: source)
            return txt.count < 24 ? -1 : max(1, Int(Date().timeIntervalSince(start) * 1000))
        } catch {
            return -1
        }
    }

    func resolvePingshuAudioUrl(source: NovelSource, episode: NovelChapter) async throws -> String {
        let url = episode.url.trimmingCharacters(in: .whitespacesAndNewlines)
        if url.hasPrefix("http") { return url }
        if url.hasPrefix("ximalaya_track://") {
            let tid = String(url.dropFirst("ximalaya_track://".count))
            let raw = try await fetchText(urlSpec: "https://m.ximalaya.com/tracks/\(tid).json", source: source)
            if let data = raw.data(using: .utf8),
               let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                for key in ["play_path_64", "play_path", "play_path_32"] {
                    if let s = obj[key] as? String, s.hasPrefix("http") { return s }
                }
            }
            throw NSError(domain: "CineCastPingshu", code: -1, userInfo: [NSLocalizedDescriptionKey: "音频直链不可用"])
        }
        if url.hasPrefix("kuwo_track://") {
            let rid = String(url.dropFirst("kuwo_track://".count))
            let direct = try await fetchText(urlSpec: "http://antiserver.kuwo.cn/anti.s?type=convert_url&rid=MUSIC_\(rid)&format=mp3&response=url", source: source).trimmingCharacters(in: .whitespacesAndNewlines)
            if direct.hasPrefix("http") { return direct }
        }
        throw NSError(domain: "CineCastPingshu", code: -1, userInfo: [NSLocalizedDescriptionKey: "无法解析评书音频"])
    }

    private func fetchXimalayaPingshuExplore(source: NovelSource, categoryUrl: String, page: Int) async -> [NovelBook] {
        let curated = [
            NovelBook(id: "xm_2677356", sourceId: source.id, sourceName: source.name, title: "白眉大侠（单田芳经典全本）", author: "单田芳", intro: "全 401 回免费直连。", kind: "武侠公案", latestChapter: "全 401 回", bookUrl: "ximalaya_album://2677356", tocUrl: "ximalaya_album://2677356"),
            NovelBook(id: "xm_3820", sourceId: source.id, sourceName: source.name, title: "乱世枭雄（单田芳经典全本）", author: "单田芳", intro: "全 485 回免费直连。", kind: "历史演义", latestChapter: "全 485 回", bookUrl: "ximalaya_album://3820", tocUrl: "ximalaya_album://3820"),
            NovelBook(id: "xm_9742745", sourceId: source.id, sourceName: source.name, title: "郭德纲单口相声与评书精选", author: "郭德纲", intro: "194 回免费直连。", kind: "单口评书", latestChapter: "共 194 回", bookUrl: "ximalaya_album://9742745", tocUrl: "ximalaya_album://9742745")
        ]
        var live: [NovelBook] = []
        if let raw = try? await fetchText(urlSpec: "https://www.ximalaya.com/revision/category/v2/albums?pageNum=\(page)&pageSize=20&sort=1&categoryId=12", source: source),
           let data = raw.data(using: .utf8),
           let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let d = obj["data"] as? [String: Any],
           let arr = d["albums"] as? [[String: Any]] {
            for a in arr {
                if (a["isPaid"] as? Bool) == true { continue }
                guard let aid = a["albumId"] as? Int, let title = a["albumTitle"] as? String else { continue }
                let count = a["albumTrackCount"] as? Int ?? 0
                live.append(NovelBook(id: "xm_\(aid)", sourceId: source.id, sourceName: source.name, title: title, author: a["albumUserNickName"] as? String ?? "评书名家", intro: "全 \(count) 回免费评书", kind: "免费评书", latestChapter: "共 \(count) 回", bookUrl: "ximalaya_album://\(aid)", tocUrl: "ximalaya_album://\(aid)"))
            }
        }
        var seen = Set<String>()
        return (curated + live).filter { seen.insert($0.bookUrl).inserted }
    }

    private func fetchXimalayaPingshuEpisodes(source: NovelSource, book: NovelBook) async -> [NovelChapter] {
        let aid = String(book.bookUrl.dropFirst("ximalaya_album://".count))
        guard let raw = try? await fetchText(urlSpec: "https://mobile.ximalaya.com/mobile/v1/album/track?albumId=\(aid)&pageId=1&pageSize=100&isAsc=true", source: source),
              let data = raw.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let d = obj["data"] as? [String: Any],
              let arr = d["list"] as? [[String: Any]] else { return [] }
        return arr.enumerated().compactMap { idx, t in
            guard let tid = t["trackId"] as? Int, let title = t["title"] as? String else { return nil }
            return NovelChapter(index: idx, title: title, url: "ximalaya_track://\(tid)")
        }
    }

    private func curatedKuwoPingshu(source: NovelSource, categoryUrl: String) -> [NovelBook] {
        [
            NovelBook(id: "kw_alb_74399040", sourceId: source.id, sourceName: source.name, title: "隋唐演义（单田芳 · 142回）", author: "单田芳", intro: "酷我高速 MP3 直连。", kind: "单田芳", latestChapter: "全 142 回", bookUrl: "kuwo_album://74399040", tocUrl: "kuwo_album://74399040"),
            NovelBook(id: "kw_alb_46299165", sourceId: source.id, sourceName: source.name, title: "雍正剑侠图（连丽如 · 378回）", author: "连丽如", intro: "侠客童林江湖传奇全本。", kind: "连丽如", latestChapter: "全 378 回", bookUrl: "kuwo_album://46299165", tocUrl: "kuwo_album://46299165"),
            NovelBook(id: "kw_alb_51220830", sourceId: source.id, sourceName: source.name, title: "大唐侠女（刘兰芳 · 62回）", author: "刘兰芳", intro: "刘兰芳播讲经典评书。", kind: "刘兰芳", latestChapter: "全 62 回", bookUrl: "kuwo_album://51220830", tocUrl: "kuwo_album://51220830"),
            NovelBook(id: "kw_alb_56364758", sourceId: source.id, sourceName: source.name, title: "红楼梦评书（66回全本）", author: "名家评书", intro: "四大名著有声评书版。", kind: "名著评书", latestChapter: "全 66 回", bookUrl: "kuwo_album://56364758", tocUrl: "kuwo_album://56364758")
        ]
    }

    private func fetchKuwoPingshuEpisodes(source: NovelSource, book: NovelBook) async -> [NovelChapter] {
        let aid = String(book.bookUrl.dropFirst("kuwo_album://".count))
        guard let raw = try? await fetchText(urlSpec: "http://search.kuwo.cn/r.s?stype=albuminfo&albumid=\(aid)&show_copyright_off=1&alflac=1&vipver=1&mobi=1&rn=150&pn=0", source: source),
              let data = raw.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let arr = obj["musiclist"] as? [[String: Any]] else { return [] }
        return arr.enumerated().compactMap { idx, m in
            guard let id = m["id"] as? String, let title = m["name"] as? String else { return nil }
            return NovelChapter(index: idx, title: title.replacingOccurrences(of: "&nbsp;", with: " "), url: "kuwo_track://\(id)")
        }
    }

    // MARK: - Chinese Wikisource Classics Adapter

    private static let wsCatalog: [String: [(title: String, page: String, author: String, kind: String, intro: String)]] = [
        "ws_cat://sida": [
            ("三国演义", "三國演義", "罗贯中", "四大名著", "魏蜀吴三国鼎立波澜壮阔的历史长篇演义（全120回简体）。"),
            ("红楼梦", "紅樓夢", "曹雪芹", "四大名著", "中国古典小说巅峰之作（全120回简体）。"),
            ("西游记", "西遊記", "吴承恩", "四大名著", "唐僧师徒西天取经神魔经典（全100回简体）。"),
            ("水浒传", "水滸傳 (100回本)", "施耐庵", "四大名著", "梁山泊一百零八好汉英雄传奇（100回本）。"),
            ("封神演义", "封神演義", "许仲琳", "神魔演义", "姜子牙伐纣封神的古典神魔名著。"),
            ("隋唐演义", "隋唐演義", "褚人获", "历史演义", "瓦岗群雄与大唐盛世英雄演义。"),
            ("东周列国志", "東周列國志", "冯梦龙", "历史演义", "春秋战国五百余年诸侯争霸长卷。")
        ],
        "ws_cat://classical": [
            ("儒林外史", "儒林外史", "吴敬梓", "讽刺小说", "清代长篇讽刺小说高峰。"),
            ("聊斋志异", "聊齋志異", "蒲松龄", "志怪短篇", "清代文言短篇志怪小说集大成之作。"),
            ("镜花缘", "鏡花緣", "李汝珍", "奇幻古典", "海外游历奇异邦国的浪漫讽刺名著。"),
            ("三侠五义", "三俠五義", "石玉昆", "侠义公案", "包拯断案与展昭白玉堂侠义传奇。"),
            ("老残游记", "老殘遊記", "刘鹗", "晚清谴责", "游方郎中老残见闻录。"),
            ("喻世明言", "喻世明言", "冯梦龙", "三言二拍", "明代白话短篇话本经典。"),
            ("警世通言", "警世通言", "冯梦龙", "三言二拍", "收录白娘子、杜十娘等传世话本。")
        ],
        "ws_cat://modern": [
            ("呐喊", "吶喊", "鲁迅", "现代名著", "收录《狂人日记》《孔乙己》《阿Q正传》等名篇。"),
            ("彷徨", "彷徨", "鲁迅", "现代名著", "收录《祝福》《伤逝》《孤独者》等名篇。"),
            ("朝花夕拾", "朝花夕拾", "鲁迅", "散文经典", "收录《从百草园到三味书屋》《藤野先生》等回忆散文。"),
            ("野草", "野草", "鲁迅", "散文诗集", "收录《秋夜》《雪》《好的故事》等散文诗。"),
            ("孔乙己（单篇）", "孔乙己", "鲁迅", "短篇经典", "鲁迅短篇小说不朽代表作。"),
            ("阿Q正传（全本）", "阿Q正傳", "鲁迅", "中篇经典", "国民性批判经典杰作。")
        ],
        "ws_cat://history": [
            ("史记", "史記", "司马迁", "史学名著", "中国第一部纪传体通史。"),
            ("世说新语", "世說新語", "刘义庆", "魏晋笔记", "魏晋名士风度与清谈轶事。"),
            ("孙子兵法", "孫子兵法", "孙武", "兵学圣典", "世界最古老的军事战略巨著。"),
            ("山海经", "山海經", "先秦佚名", "上古神话", "上古神话与地理异兽奇书。"),
            ("搜神记", "搜神記", "干宝", "志怪典籍", "东晋志怪传说名著。")
        ],
        "ws_cat://poetry": [
            ("论语", "論語", "孔子及其弟子", "儒家经典", "核心国学思想典籍。"),
            ("道德经", "道德經", "老子", "道家经典", "五千言东方哲学智慧。"),
            ("庄子", "莊子", "庄周", "道家经典", "《逍遥游》《秋水》等哲学与文学瑰宝。"),
            ("菜根谭", "菜根譚", "洪应明", "修身格言", "儒释道处世修身经典语录。"),
            ("古文观止", "古文觀止", "吴楚材 编", "古文选集", "传世经典古文精选。"),
            ("唐诗三百首", "唐詩三百首", "蘅塘退士 编", "古典诗词", "家喻户晓的唐代诗歌精选。"),
            ("诗经", "詩經", "先秦总集", "古典诗词", "中国最早的诗歌总集。")
        ]
    ]

    private func curatedWikisourceBooks(source: NovelSource, categoryUrl: String) -> [NovelBook] {
        let list = Self.wsCatalog[categoryUrl] ?? Self.wsCatalog.values.flatMap { $0 }
        return list.map { e in
            NovelBook(
                id: "ws_\(abs(e.page.hashValue))",
                sourceId: source.id,
                sourceName: source.name,
                title: e.title,
                author: e.author,
                intro: e.intro,
                kind: e.kind,
                latestChapter: "维基文库全本 · 自动简体",
                bookUrl: "wikisource://\(e.page)",
                tocUrl: "wikisource://\(e.page)"
            )
        }
    }

    private func searchWikisourceBooks(source: NovelSource, keyword: String) async -> [NovelBook] {
        let local = Self.wsCatalog.values.flatMap { $0 }
            .filter { $0.title.localizedCaseInsensitiveContains(keyword) || $0.author.localizedCaseInsensitiveContains(keyword) }
            .map { e in
                NovelBook(id: "ws_\(abs(e.page.hashValue))", sourceId: source.id, sourceName: source.name, title: e.title, author: e.author, intro: e.intro, kind: e.kind, latestChapter: "维基文库全本", bookUrl: "wikisource://\(e.page)", tocUrl: "wikisource://\(e.page)")
            }
        var remote: [NovelBook] = []
        let enc = keyword.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? keyword
        if let raw = try? await fetchText(urlSpec: "https://zh.wikisource.org/w/api.php?action=query&list=search&srsearch=\(enc)&srnamespace=0&srlimit=18&format=json", source: wsSource(source)),
           let data = raw.data(using: .utf8),
           let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let q = obj["query"] as? [String: Any],
           let arr = q["search"] as? [[String: Any]] {
            var seen = Set<String>()
            for item in arr {
                let full = (item["title"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                let root = full.components(separatedBy: "/").first ?? ""
                guard !root.isEmpty, seen.insert(root).inserted else { continue }
                remote.append(NovelBook(id: "ws_\(abs(root.hashValue))", sourceId: source.id, sourceName: source.name, title: root, author: "维基文库典藏", intro: stripHtmlTags(item["snippet"] as? String ?? ""), kind: "公版名著", latestChapter: "维基文库全本", bookUrl: "wikisource://\(root)", tocUrl: "wikisource://\(root)"))
            }
        }
        var seenUrls = Set<String>()
        return (local + remote).filter { seenUrls.insert($0.bookUrl).inserted }
    }

    private func fetchWikisourceChapters(source: NovelSource, book: NovelBook) async -> [NovelChapter] {
        let pageTitle = String(book.bookUrl.dropFirst("wikisource://".count))
        let encPrefix = "\(pageTitle)/".addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? pageTitle
        if let raw = try? await fetchText(urlSpec: "https://zh.wikisource.org/w/api.php?action=query&list=allpages&apprefix=\(encPrefix)&aplimit=250&format=json", source: wsSource(source)),
           let data = raw.data(using: .utf8),
           let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let q = obj["query"] as? [String: Any],
           let pages = q["allpages"] as? [[String: Any]],
           pages.count >= 2 {
            let titles = pages.compactMap { $0["title"] as? String }
            return titles.enumerated().map { idx, full in
                let display = full.replacingOccurrences(of: "\(pageTitle)/", with: "")
                return NovelChapter(index: idx, title: display.isEmpty ? full : display, url: "wikisource://\(full)")
            }
        }
        return [NovelChapter(index: 0, title: book.title, url: "wikisource://\(pageTitle)")]
    }

    private func fetchWikisourceChapterText(source: NovelSource, pageTitle: String) async throws -> String {
        let enc = pageTitle.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? pageTitle
        let raw = try await fetchText(urlSpec: "https://zh.wikisource.org/w/api.php?action=parse&page=\(enc)&prop=text&variant=zh-cn&redirects=1&format=json", source: wsSource(source))
        guard let data = raw.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let parse = obj["parse"] as? [String: Any],
              let textObj = parse["text"] as? [String: Any],
              let html = textObj["*"] as? String else {
            throw NSError(domain: "CineCastNovel", code: -1, userInfo: [NSLocalizedDescriptionKey: "未能解析维基文库正文"])
        }
        let noTables = html.replacingOccurrences(of: #"<table[\s\S]*?</table>"#, with: "", options: .regularExpression)
        let extracted = extractReadableHtmlContent(noTables)
        return cleanParagraphs(extracted)
    }

    private func wsSource(_ source: NovelSource) -> NovelSource {
        var copy = source
        copy.headers["User-Agent"] = "CineCastReader/1.4 (https://github.com/yxchen61/CineCast; iOS)"
        return copy
    }

    // MARK: - GitHub Legado Book Source JSON Importer

    func importLegadoUrl(_ urlString: String) async throws -> NovelImportResult {
        let raw = try await NetworkService.shared.fetchString(url: urlString, timeout: 20)
        return try parseLegadoJsonText(raw, sourceUrl: urlString)
    }

    func parseLegadoJsonText(_ rawText: String, sourceUrl: String = "") throws -> NovelImportResult {
        let trimmed = rawText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let data = trimmed.data(using: .utf8),
              let parsedObj = try? JSONSerialization.jsonObject(with: data) else {
            throw NSError(domain: "LegadoParser", code: -1, userInfo: [NSLocalizedDescriptionKey: "JSON 格式错误"])
        }
        let array: [[String: Any]]
        if let arr = parsedObj as? [[String: Any]] {
            array = arr
        } else if let one = parsedObj as? [String: Any] {
            array = [one]
        } else {
            throw NSError(domain: "LegadoParser", code: -1, userInfo: [NSLocalizedDescriptionKey: "内容不是 Legado 书源 JSON"])
        }

        var result: [NovelSource] = []
        var skippedJs = 0

        for item in array {
            if (item["bookSourceType"] as? Int ?? 0) != 0 { continue }
            let name = (item["bookSourceName"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let rawUrl = (item["bookSourceUrl"] as? String ?? "").components(separatedBy: "#").first ?? ""
            let baseUrl = rawUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/ \n\r\t"))
            guard !name.isEmpty, baseUrl.hasPrefix("http") else { continue }

            let searchUrl = (item["searchUrl"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let exploreUrl = (item["exploreUrl"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let rsDict = item["ruleSearch"] as? [String: Any] ?? [:]
            let riDict = item["ruleBookInfo"] as? [String: Any] ?? [:]
            let rtDict = item["ruleToc"] as? [String: Any] ?? [:]
            let rcDict = item["ruleContent"] as? [String: Any] ?? [:]

            let searchJs = searchUrl.hasPrefix("@js:") || searchUrl.hasPrefix("<js>")
            let exploreJs = exploreUrl.hasPrefix("@js:") || exploreUrl.hasPrefix("<js>")
            let contentStr = (rcDict["content"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if (searchJs && (exploreUrl.isEmpty || exploreJs)) || contentStr.hasPrefix("@js:") || contentStr.hasPrefix("<js>") {
                skippedJs += 1
                continue
            }

            let bookListRule = cleanRule(rsDict["bookList"] as? String ?? "")
            let tocListRule = cleanRule(rtDict["chapterList"] as? String ?? "")
            let contentRule = cleanRule(contentStr)
            let isJson = bookListRule.hasPrefix("$") || tocListRule.hasPrefix("$") || contentRule.hasPrefix("$")
            let group = (item["bookSourceGroup"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)

            result.append(
                NovelSource(
                    id: "legado_\(abs((baseUrl + "|" + name).hashValue))",
                    name: name,
                    baseUrl: baseUrl,
                    group: group.isEmpty ? (isJson ? "API 书源" : "GitHub 导入书源") : group,
                    sourceType: isJson ? .LEGADO_JSON : .LEGADO_HTML,
                    enabled: item["enabled"] as? Bool ?? true,
                    searchable: !searchUrl.isEmpty && !searchJs && searchUrl != "-",
                    searchUrl: searchJs ? "" : searchUrl,
                    exploreUrl: exploreJs ? "" : exploreUrl,
                    charset: searchUrl.lowercased().contains("gb") ? "GBK" : "UTF-8",
                    subscriptionUrl: sourceUrl,
                    ruleSearch: NovelRuleSearch(
                        bookList: bookListRule,
                        name: cleanRule(rsDict["name"] as? String ?? ""),
                        author: cleanRule(rsDict["author"] as? String ?? ""),
                        bookUrl: cleanRule(rsDict["bookUrl"] as? String ?? ""),
                        coverUrl: cleanRule(rsDict["coverUrl"] as? String ?? ""),
                        intro: cleanRule(rsDict["intro"] as? String ?? ""),
                        kind: cleanRule(rsDict["kind"] as? String ?? ""),
                        lastChapter: cleanRule(rsDict["lastChapter"] as? String ?? ""),
                        wordCount: cleanRule(rsDict["wordCount"] as? String ?? "")
                    ),
                    ruleBookInfo: NovelRuleBookInfo(
                        `init`: cleanRule(riDict["init"] as? String ?? ""),
                        name: cleanRule(riDict["name"] as? String ?? ""),
                        author: cleanRule(riDict["author"] as? String ?? ""),
                        coverUrl: cleanRule(riDict["coverUrl"] as? String ?? ""),
                        intro: cleanRule(riDict["intro"] as? String ?? ""),
                        kind: cleanRule(riDict["kind"] as? String ?? ""),
                        lastChapter: cleanRule(riDict["lastChapter"] as? String ?? ""),
                        tocUrl: cleanRule(riDict["tocUrl"] as? String ?? "")
                    ),
                    ruleToc: NovelRuleToc(
                        chapterList: tocListRule,
                        chapterName: cleanRule(rtDict["chapterName"] as? String ?? ""),
                        chapterUrl: cleanRule(rtDict["chapterUrl"] as? String ?? ""),
                        nextTocUrl: cleanRule(rtDict["nextTocUrl"] as? String ?? "")
                    ),
                    ruleContent: NovelRuleContent(
                        content: contentRule,
                        nextContentUrl: cleanRule(rcDict["nextContentUrl"] as? String ?? ""),
                        replaceRegex: rcDict["replaceRegex"] as? String ?? ""
                    )
                )
            )
        }

        let subName: String
        if sourceUrl.localizedCaseInsensitiveContains("XIU2/Yuedu") { subName = "XIU2/Yuedu 精选书源" }
        else if sourceUrl.localizedCaseInsensitiveContains("yck-novel") { subName = "liufuyou/read YCK 小说书源" }
        else if sourceUrl.localizedCaseInsensitiveContains("yiove-novel") { subName = "liufuyou/read Yiove 书源" }
        else { subName = sourceUrl.isEmpty ? "本地粘贴书源" : (URL(string: sourceUrl)?.host ?? "GitHub 书源") }

        let sub = NovelSubscription(
            id: "nsub_\(abs((sourceUrl.isEmpty ? subName : sourceUrl).hashValue))",
            name: subName,
            url: sourceUrl,
            lastSyncedAt: Int64(Date().timeIntervalSince1970 * 1000),
            importedCount: result.count
        )
        return NovelImportResult(subscription: sub, sources: result, totalRawCount: array.count, skippedJsOnlyCount: skippedJs)
    }

    // MARK: - Parsers (JSON / HTML / Gutendex)

    private func parseBookList(source: NovelSource, rawBody: String) -> [NovelBook] {
        let trimmed = rawBody.trimmingCharacters(in: .whitespacesAndNewlines)
        if source.sourceType == .LEGADO_JSON || trimmed.hasPrefix("{") || trimmed.hasPrefix("[") {
            guard let data = trimmed.data(using: .utf8),
                  let root = try? JSONSerialization.jsonObject(with: data) else { return [] }
            let listRule = source.ruleSearch.bookList.isEmpty ? "$.data" : source.ruleSearch.bookList
            let items = evalJsonList(root, listRule)
            return items.compactMap { node in
                let title = evalJsonString(node, source.ruleSearch.name.isEmpty ? "$.title" : source.ruleSearch.name)
                let rawUrl = evalJsonString(node, source.ruleSearch.bookUrl)
                guard !title.isEmpty, !rawUrl.isEmpty else { return nil }
                let resolved = resolveUrl(base: source.baseUrl, relative: rawUrl)
                return NovelBook(
                    id: "\(source.id)_\(abs(resolved.hashValue))",
                    sourceId: source.id,
                    sourceName: source.name,
                    title: title,
                    author: evalJsonString(node, source.ruleSearch.author),
                    coverUrl: resolveUrl(base: source.baseUrl, relative: evalJsonString(node, source.ruleSearch.coverUrl)),
                    intro: evalJsonString(node, source.ruleSearch.intro),
                    kind: evalJsonString(node, source.ruleSearch.kind),
                    latestChapter: evalJsonString(node, source.ruleSearch.lastChapter),
                    wordCount: evalJsonString(node, source.ruleSearch.wordCount),
                    bookUrl: resolved
                )
            }
        }

        // Generic HTML link extraction fallback for Legado HTML sources on iOS
        let pattern = #"<a[^>]+href=["']([^"']+)["'][^>]*>([^<]{2,40})</a>"#
        guard let regex = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive) else { return [] }
        let ns = rawBody as NSString
        var books: [NovelBook] = []
        var seen = Set<String>()
        for m in regex.matches(in: rawBody, range: NSRange(location: 0, length: ns.length)) {
            let href = ns.substring(with: m.range(at: 1))
            let title = stripHtmlTags(ns.substring(with: m.range(at: 2))).trimmingCharacters(in: .whitespacesAndNewlines)
            guard !title.isEmpty,
                  (href.contains("/book") || href.contains("/novel") || href.contains("/xiaoshuo") || href.contains("/tongren") || href.contains(".html")),
                  !href.contains("javascript") else { continue }
            let resolved = resolveUrl(base: source.baseUrl, relative: href)
            guard seen.insert(resolved).inserted else { continue }
            books.append(
                NovelBook(
                    id: "\(source.id)_\(abs(resolved.hashValue))",
                    sourceId: source.id,
                    sourceName: source.name,
                    title: title,
                    bookUrl: resolved
                )
            )
            if books.count >= 30 { break }
        }
        return books
    }

    private func parseChapters(source: NovelSource, rawBody: String, baseUrl: String) -> [NovelChapter] {
        let trimmed = rawBody.trimmingCharacters(in: .whitespacesAndNewlines)
        if source.sourceType == .LEGADO_JSON || trimmed.hasPrefix("{") || trimmed.hasPrefix("[") {
            guard let data = trimmed.data(using: .utf8),
                  let root = try? JSONSerialization.jsonObject(with: data) else { return [] }
            let list = evalJsonList(root, source.ruleToc.chapterList.isEmpty ? "$.data" : source.ruleToc.chapterList)
            return list.enumerated().compactMap { idx, node in
                let title = evalJsonString(node, source.ruleToc.chapterName.isEmpty ? "$.chapter_title" : source.ruleToc.chapterName)
                let rawUrl = evalJsonString(node, source.ruleToc.chapterUrl)
                guard !title.isEmpty, !rawUrl.isEmpty else { return nil }
                return NovelChapter(index: idx, title: title, url: resolveUrl(base: source.baseUrl, relative: rawUrl))
            }
        }

        let pattern = #"<a[^>]+href=["']([^"']+)["'][^>]*>([^<]{2,60})</a>"#
        guard let regex = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive) else { return [] }
        let ns = rawBody as NSString
        var chapters: [NovelChapter] = []
        var seen = Set<String>()
        for m in regex.matches(in: rawBody, range: NSRange(location: 0, length: ns.length)) {
            let href = ns.substring(with: m.range(at: 1))
            let title = stripHtmlTags(ns.substring(with: m.range(at: 2))).trimmingCharacters(in: .whitespacesAndNewlines)
            guard title.contains("章") || title.contains("回") || title.contains("节") || title.localizedCaseInsensitiveContains("chapter") else { continue }
            let resolved = resolveUrl(base: baseUrl, relative: href)
            guard seen.insert(resolved).inserted else { continue }
            chapters.append(NovelChapter(index: chapters.count, title: title, url: resolved))
        }
        return chapters
    }

    private func parseGutendexBooks(source: NovelSource, rawJson: String) -> [NovelBook] {
        guard let data = rawJson.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let results = obj["results"] as? [[String: Any]] else { return [] }
        return results.compactMap { item in
            let id = item["id"] as? Int ?? 0
            let title = (item["title"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let formats = item["formats"] as? [String: String] ?? [:]
            let textUrl = formats["text/plain; charset=utf-8"]
                ?? formats["text/plain; charset=us-ascii"]
                ?? formats["text/plain"]
                ?? formats.first(where: { $0.key.hasPrefix("text/plain") && !$0.value.hasSuffix(".zip") })?.value
            guard !title.isEmpty, let textUrl, !textUrl.hasSuffix(".zip") else { return nil }
            let authors = (item["authors"] as? [[String: Any]] ?? []).compactMap { $0["name"] as? String }.joined(separator: " / ")
            let downloads = item["download_count"] as? Int ?? 0
            return NovelBook(
                id: "gutendex_\(id)",
                sourceId: source.id,
                sourceName: source.name,
                title: title,
                author: authors.isEmpty ? "公版经典" : authors,
                coverUrl: formats["image/jpeg"] ?? "",
                intro: "Project Gutenberg 开源公版名著 · 全球累计下载 \(downloads) 次",
                kind: "开源名著",
                latestChapter: "全文完结",
                bookUrl: textUrl,
                tocUrl: textUrl
            )
        }
    }

    private func fetchGutendexChapters(book: NovelBook) async throws -> [NovelChapter] {
        if let cached = gutenbergCache[book.bookUrl] { return cached }
        let raw = try await NetworkService.shared.fetchString(url: book.bookUrl, timeout: 25)
        let paragraphs = raw.replacingOccurrences(of: "\r\n", with: "\n")
            .components(separatedBy: "\n")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        var chapters: [NovelChapter] = []
        var buf = ""
        for p in paragraphs {
            if buf.count > 2600 {
                let idx = chapters.count
                chapters.append(NovelChapter(index: idx, title: "第 \(idx + 1) 节", url: "\(book.bookUrl)#sec_\(idx)", inlineContent: buf.trimmingCharacters(in: .whitespacesAndNewlines)))
                buf = ""
            }
            buf += p + "\n\n"
        }
        if !buf.isEmpty {
            let idx = chapters.count
            chapters.append(NovelChapter(index: idx, title: "第 \(idx + 1) 节", url: "\(book.bookUrl)#sec_\(idx)", inlineContent: buf.trimmingCharacters(in: .whitespacesAndNewlines)))
        }
        gutenbergCache[book.bookUrl] = chapters
        return chapters
    }

    // MARK: - JSONPath & Networking Helpers

    private func evalJsonList(_ root: Any, _ path: String) -> [Any] {
        let val = evalJsonPath(root, path)
        if let arr = val as? [Any] { return arr }
        if let dict = val as? [String: Any] { return [dict] }
        return []
    }

    private func evalJsonString(_ node: Any, _ rule: String) -> String {
        let base = rule.components(separatedBy: "##").first?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !base.isEmpty else { return "" }
        if base.contains("{{") {
            let regex = try? NSRegularExpression(pattern: #"\{\{([^}]+)\}\}"#)
            let ns = base as NSString
            var out = base
            let matches = regex?.matches(in: base, range: NSRange(location: 0, length: ns.length)) ?? []
            for m in matches.reversed() {
                let inner = ns.substring(with: m.range(at: 1)).trimmingCharacters(in: .whitespaces)
                let val = stringifyJson(evalJsonPath(node, inner))
                if let range = Range(m.range, in: out) { out.replaceSubrange(range, with: val) }
            }
            return out.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return stringifyJson(evalJsonPath(node, base)).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func evalJsonPath(_ root: Any?, _ path: String) -> Any? {
        guard let root else { return nil }
        var p = path.trimmingCharacters(in: .whitespacesAndNewlines)
        if p == "$" || p == "$[*]" || p.isEmpty { return root }
        if p.hasPrefix("$.") { p.removeFirst(2) }
        else if p.hasPrefix("$") { p.removeFirst(1) }
        var cur: Any? = root
        for seg in p.components(separatedBy: ".") where !seg.isEmpty {
            if let dict = cur as? [String: Any] {
                cur = dict[seg]
            } else if let arr = cur as? [Any], let idx = Int(seg), idx < arr.count {
                cur = arr[idx]
            } else {
                return nil
            }
        }
        return cur
    }

    private func stringifyJson(_ value: Any?) -> String {
        if let s = value as? String { return s }
        if let n = value as? NSNumber { return n.stringValue }
        return ""
    }

    private func fetchText(urlSpec: String, source: NovelSource) async throws -> String {
        let parts = urlSpec.components(separatedBy: ",{")
        let rawUrl = parts[0].trimmingCharacters(in: .whitespacesAndNewlines)
        let fullUrl = resolveUrl(base: source.baseUrl, relative: rawUrl)
        guard let url = URL(string: fullUrl) else { throw URLError(.badURL) }

        var req = URLRequest(url: url, timeoutInterval: 14)
        req.setValue(source.headers["User-Agent"] ?? NetworkService.defaultUA, forHTTPHeaderField: "User-Agent")
        for (k, v) in source.headers where k.lowercased() != "user-agent" {
            req.setValue(v, forHTTPHeaderField: k)
        }

        if parts.count > 1,
           let optData = ("{" + parts[1]).replacingOccurrences(of: "'", with: "\"").data(using: .utf8),
           let opt = try? JSONSerialization.jsonObject(with: optData) as? [String: Any] {
            if (opt["method"] as? String)?.uppercased() == "POST" {
                req.httpMethod = "POST"
                let body = opt["body"] as? String ?? ""
                req.httpBody = body.data(using: .utf8)
                req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            }
        }

        let (data, resp) = try await NetworkService.shared.session.data(for: req)
        if let http = resp as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            throw NSError(domain: "CineCastNovelHTTP", code: http.statusCode, userInfo: [NSLocalizedDescriptionKey: "HTTP \(http.statusCode)"])
        }
        if let utf8 = String(data: data, encoding: .utf8) { return utf8 }
        let gbkEnc = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue)))
        return String(data: data, encoding: gbkEnc) ?? String(decoding: data, as: UTF8.self)
    }

    private func expandTemplate(_ template: String, keyword: String, page: Int) -> String {
        let enc = keyword.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? keyword
        return template
            .replacingOccurrences(of: "{{key}}", with: enc)
            .replacingOccurrences(of: "{{page}}", with: "\(page)")
    }

    private func resolveUrl(base: String, relative: String) -> String {
        let rel = relative.trimmingCharacters(in: .whitespacesAndNewlines)
        if rel.isEmpty { return "" }
        if rel.hasPrefix("http://") || rel.hasPrefix("https://") { return rel }
        if rel.hasPrefix("//") { return "https:" + rel }
        if let baseURL = URL(string: base), let resolved = URL(string: rel, relativeTo: baseURL)?.absoluteString {
            return resolved
        }
        return base.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + (rel.hasPrefix("/") ? rel : "/\(rel)")
    }

    private func cleanRule(_ raw: String) -> String {
        let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if let range = t.range(of: "@js:") { return String(t[..<range.lowerBound]).trimmingCharacters(in: .whitespaces) }
        if let range = t.range(of: "<js>") { return String(t[..<range.lowerBound]).trimmingCharacters(in: .whitespaces) }
        return t
    }

    private func extractReadableHtmlContent(_ html: String) -> String {
        let noScripts = html
            .replacingOccurrences(of: #"<script[\s\S]*?</script>"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"<style[\s\S]*?</style>"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"<br\s*/?>"#, with: "\n", options: .regularExpression)
            .replacingOccurrences(of: #"</p>"#, with: "\n", options: .regularExpression)
        let stripped = stripHtmlTags(noScripts)
        return stripped.components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { $0.count >= 12 }
            .joined(separator: "\n\n")
    }

    private func stripHtmlTags(_ html: String) -> String {
        html.replacingOccurrences(of: #"<[^>]+>"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&emsp;", with: " ")
            .replacingOccurrences(of: "&amp;", with: "&")
    }

    private func cleanParagraphs(_ text: String) -> String {
        text.replacingOccurrences(of: "\r\n", with: "\n")
            .components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: "\n\n")
    }

    // MARK: - 五千言中华古典名著馆 (5000yan.com 国内直连)

    private func curated5000yanBooks(source: NovelSource, categoryUrl: String) -> [NovelBook] {
        let items: [(cat: String, title: String, sub: String, author: String, kind: String, hint: String, intro: String)] = [
            ("yan5000_cat://sida", "三国演义", "sanguo", "罗贯中", "四大名著", "全 120 回", "东汉末年群雄逐鹿历史长篇演义（国内直连简体全本）。"),
            ("yan5000_cat://sida", "红楼梦", "hongloumeng", "曹雪芹", "四大名著", "全 120 回", "中国古典长篇小说巅峰之作。"),
            ("yan5000_cat://sida", "西游记", "xiyouji", "吴承恩", "四大名著", "全 100 回", "唐僧师徒四人西天取经神魔古典名著。"),
            ("yan5000_cat://sida", "水浒传", "shuihu", "施耐庵", "四大名著", "全 120 回", "北宋梁山泊一百零八位好汉英雄传奇。"),
            ("yan5000_cat://classical", "封神演义", "fengshen", "许仲琳", "古典神魔", "全 100 回", "姜子牙辅佐武王伐纣封神史诗。"),
            ("yan5000_cat://classical", "聊斋志异", "liaozhai", "蒲松龄", "文言志怪", "全 494 篇", "文言短篇志怪小说巅峰巨著。"),
            ("yan5000_cat://classical", "儒林外史", "rulin", "吴敬梓", "古典讽刺", "全 56 回", "古代知识分子命运与世态人情讽刺小说典范。"),
            ("yan5000_cat://classical", "隋唐演义", "suitang", "褚人获", "历史演义", "全 100 回", "瓦岗寨群雄辅佐大唐开国传奇。"),
            ("yan5000_cat://classical", "镜花缘", "jinghuayuan", "李汝珍", "奇幻古典", "全 100 回", "海外奇国游记与神魔幻想清代奇书。"),
            ("yan5000_cat://classical", "官场现形记", "guanchang", "李伯元", "晚清谴责", "全 60 回", "晚清四大谴责小说之首。"),
            ("yan5000_cat://history", "战国策", "zhanguoce", "刘向 编订", "史学谋略", "全 147 篇", "先秦纵横家谋略与外交智慧典籍。"),
            ("yan5000_cat://history", "搜神记", "soushenji", "干宝", "志怪神话", "全 463 篇", "东晋志怪神话集大成之作。"),
            ("yan5000_cat://guoxue", "论语", "lunyu", "孔子及其弟子", "儒家经典", "全 323 篇", "儒家核心经典简体原文。"),
            ("yan5000_cat://guoxue", "菜根谭", "caigentan", "洪应明", "处世修身", "全 352 篇", "明代融汇儒释道智慧格言集。"),
            ("yan5000_cat://guoxue", "唐诗三百首", "tangshi", "蘅塘退士 编", "古典诗词", "精选全集", "唐代传世名诗精选。"),
            ("yan5000_cat://guoxue", "宋词三百首", "songci", "朱孝臧 编", "古典诗词", "精选全集", "两宋词坛巅峰佳作。"),
            ("yan5000_cat://guoxue", "诗经", "shijing", "先秦民歌与雅颂", "风雅经典", "全 270 篇", "中国最早的诗歌总集。"),
            ("yan5000_cat://guoxue", "楚辞", "chuci", "屈原 等", "浪漫诗祖", "全 45 篇", "收录《离骚》《九歌》等名篇。"),
            ("yan5000_cat://guoxue", "三字经", "sanzijing", "王应麟", "国学蒙学", "全 142 节", "中华传统启蒙经典。")
        ]
        let filtered = categoryUrl == "all" ? items : items.filter { $0.cat == categoryUrl }
        return filtered.map { e in
            let url = "https://\(e.sub).5000yan.com/"
            return NovelBook(id: "yan5000_\(e.sub)", sourceId: source.id, sourceName: source.name, title: e.title, author: e.author, intro: e.intro, kind: e.kind, latestChapter: e.hint, bookUrl: url, tocUrl: url)
        }
    }

    private func parse5000yanToc(html: String, baseUrl: String) -> [NovelChapter] {
        guard let regex = try? NSRegularExpression(pattern: #"<a[^>]+href=["']([^"']*?/\d+\.html)["'][^>]*>([\s\S]*?)</a>"#, options: [.caseInsensitive]) else {
            return []
        }
        let ns = html as NSString
        var seen = Set<String>()
        var out: [NovelChapter] = []
        for match in regex.matches(in: html, range: NSRange(location: 0, length: ns.length)) {
            guard match.numberOfRanges >= 3 else { continue }
            let href = ns.substring(with: match.range(at: 1))
            let title = stripHtmlTags(ns.substring(with: match.range(at: 2))).trimmingCharacters(in: .whitespacesAndNewlines)
            let absUrl = resolveUrl(base: baseUrl, relative: href)
            guard !title.isEmpty, absUrl.contains(".5000yan.com/"), seen.insert(absUrl).inserted else { continue }
            out.append(NovelChapter(index: out.count, title: title, url: absUrl))
        }
        return out
    }

    private func parse5000yanChapter(html: String) -> String {
        if let regex = try? NSRegularExpression(pattern: #"<article[^>]*class=["'][^"']*reading-content[^"']*["'][^>]*>([\s\S]*?)</article>"#, options: [.caseInsensitive]),
           let m = regex.firstMatch(in: html, range: NSRange(location: 0, length: (html as NSString).length)),
           m.numberOfRanges >= 2 {
            let inner = (html as NSString).substring(with: m.range(at: 1))
            return extractReadableHtmlContent(inner)
        }
        return extractReadableHtmlContent(html)
    }
}
