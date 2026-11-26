import Foundation

struct VodPageResult {
    let items: [VodItem]
    let categories: [VodCategory]
    let page: Int
    let pageCount: Int
}

final class VodSourceEngine {
    static let shared = VodSourceEngine()

    func fetchVodList(
        source: VideoSource,
        categoryId: String? = nil,
        page: Int = 1,
        childCategoryIds: [String] = []
    ) async throws -> VodPageResult {
        switch source.type {
        case .MACCMS_JSON:
            return try await fetchMacCmsJsonList(source: source, categoryId: categoryId, page: page, childCategoryIds: childCategoryIds)
        case .MACCMS_XML:
            return try await fetchMacCmsXmlList(source: source, categoryId: categoryId, page: page)
        case .T4_API:
            let q = (categoryId == nil || categoryId!.isEmpty) ? "ac=list&pg=\(page)" : "ac=detail&t=\(categoryId!)&pg=\(page)"
            let body = try await NetworkService.shared.fetchString(url: appendQuery(source.apiUrl, q), headers: source.headers)
            return parseMacCmsJsonItems(raw: body, source: source)
        default:
            return VodPageResult(items: [], categories: [], page: 1, pageCount: 1)
        }
    }

    func search(source: VideoSource, keyword: String, page: Int = 1) async throws -> [VodItem] {
        let trimmed = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let encoded = trimmed.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) else {
            return []
        }
        switch source.type {
        case .MACCMS_JSON:
            if let body = try? await NetworkService.shared.fetchString(url: appendQuery(source.apiUrl, "ac=detail&wd=\(encoded)&pg=\(page)"), headers: source.headers) {
                let res = parseMacCmsJsonItems(raw: body, source: source).items
                if !res.isEmpty { return res }
            }
            if let body = try? await NetworkService.shared.fetchString(url: appendQuery(source.apiUrl, "ac=videolist&wd=\(encoded)&pg=\(page)"), headers: source.headers) {
                let res = parseMacCmsJsonItems(raw: body, source: source).items
                if !res.isEmpty { return res }
            }
            if let body = try? await NetworkService.shared.fetchString(url: appendQuery(source.apiUrl, "ac=list&wd=\(encoded)&pg=\(page)"), headers: source.headers) {
                let listItems = parseMacCmsJsonItems(raw: body, source: source).items
                if listItems.isEmpty { return [] }
                let ids = listItems.prefix(20).map(\.id).joined(separator: ",")
                if let detailBody = try? await NetworkService.shared.fetchString(url: appendQuery(source.apiUrl, "ac=detail&ids=\(ids)"), headers: source.headers) {
                    let hydrated = parseMacCmsJsonItems(raw: detailBody, source: source).items
                    if !hydrated.isEmpty { return hydrated }
                }
                return listItems
            }
            return []
        case .MACCMS_XML:
            let body = try await NetworkService.shared.fetchString(url: appendQuery(source.apiUrl, "ac=videolist&wd=\(encoded)&pg=\(page)"), headers: source.headers)
            return MacCmsXmlParser(source: source).parseList(raw: body).items
        case .T4_API:
            let body = try await NetworkService.shared.fetchString(url: appendQuery(source.apiUrl, "wd=\(encoded)&pg=\(page)"), headers: source.headers)
            return parseMacCmsJsonItems(raw: body, source: source).items
        default:
            return []
        }
    }

    func fetchDetail(source: VideoSource, vodId: String) async throws -> VodDetail {
        switch source.type {
        case .MACCMS_JSON, .T4_API:
            let body = try await NetworkService.shared.fetchString(url: appendQuery(source.apiUrl, "ac=detail&ids=\(vodId)"), headers: source.headers)
            return try parseMacCmsJsonDetail(raw: body, source: source)
        case .MACCMS_XML:
            let body = try await NetworkService.shared.fetchString(url: appendQuery(source.apiUrl, "ac=videolist&ids=\(vodId)"), headers: source.headers)
            guard let detail = MacCmsXmlParser(source: source).parseDetail(raw: body) else {
                throw NSError(domain: "CineCast", code: -1, userInfo: [NSLocalizedDescriptionKey: "XML 详情解析失败"])
            }
            return detail
        default:
            throw NSError(domain: "CineCast", code: -2, userInfo: [NSLocalizedDescriptionKey: "请使用网页嗅探模式打开该站点"])
        }
    }

    func pingSource(_ source: VideoSource) async -> Int {
        if source.type == .TVBOX_SPIDER { return -2 }
        let start = Date()
        let url: String
        switch source.type {
        case .WEB_SNIFFER: url = source.apiUrl
        default: url = appendQuery(source.apiUrl, "ac=list&pg=1")
        }
        do {
            let body = try await NetworkService.shared.fetchString(url: url, headers: source.headers, timeout: 6)
            return body.isEmpty ? -1 : Int(Date().timeIntervalSince(start) * 1000)
        } catch {
            return -1
        }
    }

    // MARK: - Private JSON & XML Helpers

    private func fetchMacCmsJsonList(
        source: VideoSource,
        categoryId: String?,
        page: Int,
        childCategoryIds: [String]
    ) async throws -> VodPageResult {
        async let categoriesTask: [VodCategory] = {
            if page == 1 && (categoryId == nil || categoryId!.isEmpty) {
                if let body = try? await NetworkService.shared.fetchString(url: appendQuery(source.apiUrl, "ac=list&pg=1"), headers: source.headers) {
                    return parseMacCmsCategories(raw: body, source: source)
                }
            }
            return []
        }()

        // Parent category aggregation (MacCMS stores videos in child categories)
        if !childCategoryIds.isEmpty {
            let targets = Array(childCategoryIds.prefix(4))
            var childPages: [VodPageResult] = []
            await withTaskGroup(of: VodPageResult?.self) { group in
                for cid in targets {
                    group.addTask {
                        guard let body = try? await NetworkService.shared.fetchString(
                            url: self.appendQuery(source.apiUrl, "ac=detail&pg=\(page)&t=\(cid)"),
                            headers: source.headers
                        ) else { return nil }
                        return self.parseMacCmsJsonItems(raw: body, source: source)
                    }
                }
                for await res in group {
                    if let res { childPages.append(res) }
                }
            }
            var interleaved: [VodItem] = []
            let maxCount = childPages.map { $0.items.count }.max() ?? 0
            for idx in 0..<maxCount {
                for p in childPages where idx < p.items.count {
                    interleaved.append(p.items[idx])
                }
            }
            var seen = Set<String>()
            let deduped = interleaved.filter { seen.insert($0.id).inserted }
            let cats = await categoriesTask
            return VodPageResult(
                items: deduped,
                categories: cats,
                page: page,
                pageCount: childPages.map(\.pageCount).max() ?? 1
            )
        }

        var query = "ac=detail&pg=\(page)"
        if let cid = categoryId, !cid.isEmpty {
            query += "&t=\(cid)"
        }
        let body = try await NetworkService.shared.fetchString(url: appendQuery(source.apiUrl, query), headers: source.headers)
        let parsed = parseMacCmsJsonItems(raw: body, source: source)
        let cats = await categoriesTask
        return VodPageResult(
            items: parsed.items,
            categories: cats.isEmpty ? parsed.categories : cats,
            page: parsed.page,
            pageCount: parsed.pageCount
        )
    }

    private func fetchMacCmsXmlList(source: VideoSource, categoryId: String?, page: Int) async throws -> VodPageResult {
        var query = "ac=videolist&pg=\(page)"
        if let cid = categoryId, !cid.isEmpty { query += "&t=\(cid)" }
        let body = try await NetworkService.shared.fetchString(url: appendQuery(source.apiUrl, query), headers: source.headers)
        return MacCmsXmlParser(source: source).parseList(raw: body)
    }

    private func parseMacCmsCategories(raw: String, source: VideoSource) -> [VodCategory] {
        guard let data = raw.trimmingCharacters(in: .whitespacesAndNewlines).data(using: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let classArr = dict["class"] as? [[String: Any]] else {
            return []
        }
        var rawList: [VodCategory] = []
        for c in classArr {
            let id = stringValue(c["type_id"])
            let pid = stringValue(c["type_pid"]).isEmpty ? "0" : stringValue(c["type_pid"])
            let name = stringValue(c["type_name"]).trimmingCharacters(in: .whitespacesAndNewlines)
            if !id.isEmpty && !name.isEmpty {
                rawList.append(VodCategory(id: id, name: name, pid: pid))
            }
        }
        let safe = filterCategories(rawList, allowed: source.categories)
        let childrenByPid = Dictionary(grouping: safe.filter { $0.pid != "0" && !$0.pid.isEmpty }, by: \.pid)
        return safe.map { cat in
            let kids = childrenByPid[cat.id]?.map(\.id) ?? []
            return VodCategory(id: cat.id, name: cat.name, pid: cat.pid, childIds: kids)
        }
    }

    private func parseMacCmsJsonItems(raw: String, source: VideoSource) -> VodPageResult {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("<") {
            return MacCmsXmlParser(source: source).parseList(raw: trimmed)
        }
        guard let data = trimmed.data(using: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return VodPageResult(items: [], categories: [], page: 1, pageCount: 1)
        }
        let categories = parseMacCmsCategories(raw: trimmed, source: source)
        let listArr = dict["list"] as? [[String: Any]] ?? []
        let items: [VodItem] = listArr.compactMap { v in
            let id = stringValue(v["vod_id"])
            let name = stringValue(v["vod_name"]).trimmingCharacters(in: .whitespacesAndNewlines)
            guard !id.isEmpty && !name.isEmpty else { return nil }
            let score = stringValue(v["vod_douban_score"]).isEmpty ? stringValue(v["vod_score"]) : stringValue(v["vod_douban_score"])
            return VodItem(
                id: id,
                name: name,
                pic: fixImageUrl(stringValue(v["vod_pic"])),
                remarks: stringValue(v["vod_remarks"]).trimmingCharacters(in: .whitespacesAndNewlines),
                year: stringValue(v["vod_year"]).trimmingCharacters(in: .whitespacesAndNewlines),
                area: stringValue(v["vod_area"]).trimmingCharacters(in: .whitespacesAndNewlines),
                lang: stringValue(v["vod_lang"]).trimmingCharacters(in: .whitespacesAndNewlines),
                typeName: stringValue(v["type_name"]).trimmingCharacters(in: .whitespacesAndNewlines),
                sourceId: source.id,
                sourceName: source.name,
                score: score.trimmingCharacters(in: .whitespacesAndNewlines)
            )
        }
        let page = Int(stringValue(dict["page"])) ?? 1
        let pageCount = Int(stringValue(dict["pagecount"])) ?? 1
        return VodPageResult(items: items, categories: categories, page: page, pageCount: max(1, pageCount))
    }

    private func parseMacCmsJsonDetail(raw: String, source: VideoSource) throws -> VodDetail {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("<"), let xmlDetail = MacCmsXmlParser(source: source).parseDetail(raw: trimmed) {
            return xmlDetail
        }
        guard let data = trimmed.data(using: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let listArr = dict["list"] as? [[String: Any]],
              let v = listArr.first else {
            throw NSError(domain: "CineCast", code: -1, userInfo: [NSLocalizedDescriptionKey: "未找到影片详情"])
        }
        let playSources = Self.parsePlaySources(
            playFrom: stringValue(v["vod_play_from"]),
            playUrl: stringValue(v["vod_play_url"])
        )
        let rawContent = stringValue(v["vod_content"]).isEmpty ? stringValue(v["vod_blurb"]) : stringValue(v["vod_content"])
        return VodDetail(
            id: stringValue(v["vod_id"]),
            name: stringValue(v["vod_name"]).trimmingCharacters(in: .whitespacesAndNewlines),
            pic: fixImageUrl(stringValue(v["vod_pic"])),
            remarks: stringValue(v["vod_remarks"]),
            year: stringValue(v["vod_year"]),
            area: stringValue(v["vod_area"]),
            actor: stringValue(v["vod_actor"]),
            director: stringValue(v["vod_director"]),
            content: Self.cleanHtml(rawContent),
            typeName: stringValue(v["type_name"]),
            sourceId: source.id,
            sourceName: source.name,
            playSources: playSources
        )
    }

    static func parsePlaySources(playFrom: String, playUrl: String) -> [VodPlaySource] {
        guard !playUrl.isEmpty else { return [] }
        let froms = playFrom.components(separatedBy: "$$$").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        let groups = playUrl.components(separatedBy: "$$$")
        var result: [VodPlaySource] = []
        for (idx, group) in groups.enumerated() {
            let eps = parseEpisodes(group: group)
            if !eps.isEmpty {
                let name = idx < froms.count && !froms[idx].isEmpty ? froms[idx] : "线路\(idx + 1)"
                result.append(VodPlaySource(name: name, episodes: eps))
            }
        }
        // Prefer direct m3u8 / mp4 lines first
        return result.sorted { a, b in
            scoreLine(a) > scoreLine(b)
        }
    }

    private static func scoreLine(_ ps: VodPlaySource) -> Int {
        let first = ps.episodes.first?.url.lowercased() ?? ""
        if ps.name.lowercased().contains("m3u8") || first.contains(".m3u8") { return 3 }
        if first.contains(".mp4") { return 2 }
        return 1
    }

    static func parseEpisodes(group: String) -> [VodEpisode] {
        group.components(separatedBy: "#").compactMap { raw in
            let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !t.isEmpty else { return nil }
            let parts = t.components(separatedBy: "$")
            if parts.count >= 2 {
                let name = parts[0].trimmingCharacters(in: .whitespacesAndNewlines)
                let url = parts[1].trimmingCharacters(in: .whitespacesAndNewlines)
                guard url.lowercased().hasPrefix("http") else { return nil }
                return VodEpisode(name: name.isEmpty ? "正片" : name, url: url)
            } else if t.lowercased().hasPrefix("http") {
                return VodEpisode(name: "正片", url: t)
            }
            return nil
        }
    }

    private func filterCategories(_ all: [VodCategory], allowed: [String]) -> [VodCategory] {
        let blocked = ["伦理", "福利", "写真", "成人"]
        let safe = all.filter { cat in !blocked.contains(where: { cat.name.contains($0) }) }
        guard !allowed.isEmpty else { return safe }
        let allowedSet = Set(allowed)
        let filtered = safe.filter { allowedSet.contains($0.name) }
        return filtered.isEmpty ? safe : filtered
    }

    private func appendQuery(_ base: String, _ query: String) -> String {
        let clean = base.trimmingCharacters(in: .whitespacesAndNewlines)
        return clean.contains("?") ? "\(clean)&\(query)" : "\(clean)?\(query)"
    }

    private func fixImageUrl(_ url: String) -> String {
        let t = url.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.hasPrefix("//") ? "https:\(t)" : t
    }

    private func stringValue(_ any: Any?) -> String {
        if let s = any as? String { return s }
        if let n = any as? NSNumber { return n.stringValue }
        return ""
    }

    static let yangyangCategoryId = "__yangyang_en_cartoons__"

    static func isStrictEnglishCartoon(_ item: VodItem) -> Bool {
        let lang = item.lang.lowercased()
        let name = item.name.lowercased()
        let type = item.typeName.lowercased()
        let area = item.area.lowercased()
        let remarks = item.remarks.lowercased()
        if type.contains("伦理") || type.contains("福利") || type.contains("综艺") { return false }
        let animHints = ["小猪佩奇", "佩奇", "布鲁伊", "汪汪队", "海绵宝宝", "数字积木", "嘟吉", "迪士尼", "皮克斯", "神偷奶爸", "史努比", "猫和老鼠", "冰雪奇缘", "米奇", "辛普森", "动画", "动漫", "卡通", "peppa", "bluey", "disney"]
        let isAnim = type.contains("动漫") || type.contains("动画") || type.contains("少儿") || animHints.contains(where: { name.contains($0) })
        guard isAnim else { return false }
        let nonEn = ["国语", "中文版", "汉语", "普通话", "粤语", "国配", "中配", "日语", "韩语"]
        let isExplicitNonEn = nonEn.contains(where: { name.contains($0) || remarks.contains($0) })
        let isExplicitEn = name.contains("英文") || name.contains("英语") || name.contains("english")
        if isExplicitNonEn && !isExplicitEn { return false }
        let langEn = (lang.contains("英语") || lang.contains("英文") || lang.contains("english")) && !lang.contains("国语") && !lang.contains("日语")
        if langEn || isExplicitEn { return true }
        if lang.isEmpty && type.contains("欧美动漫") && !isExplicitNonEn {
            return area.isEmpty || area.contains("美国") || area.contains("英国") || area.contains("欧美")
        }
        return false
    }

    static func cleanHtml(_ raw: String) -> String {
        raw.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
            .replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

// MARK: - Lightweight MacCMS XML Parser (Type 0)
private final class MacCmsXmlParser: NSObject, XMLParserDelegate {
    private let source: VideoSource
    private var items: [VodItem] = []
    private var categories: [VodCategory] = []
    private var page = 1
    private var pageCount = 1
    private var detail: VodDetail?

    private var currentTag = ""
    private var currentText = ""
    private var currentAttrId = ""
    private var currentAttrFlag = ""

    private var vId = ""; private var vName = ""; private var vPic = ""
    private var vNote = ""; private var vYear = ""; private var vArea = ""
    private var vType = ""; private var vActor = ""; private var vDirector = ""; private var vDes = ""
    private var playSources: [VodPlaySource] = []

    init(source: VideoSource) { self.source = source }

    func parseList(raw: String) -> VodPageResult {
        guard let data = raw.data(using: .utf8) else { return VodPageResult(items: [], categories: [], page: 1, pageCount: 1) }
        let parser = XMLParser(data: data)
        parser.delegate = self
        parser.parse()
        return VodPageResult(items: items, categories: categories, page: page, pageCount: max(1, pageCount))
    }

    func parseDetail(raw: String) -> VodDetail? {
        _ = parseList(raw: raw)
        return detail
    }

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
        currentTag = elementName
        currentText = ""
        if elementName == "list" {
            page = Int(attributeDict["page"] ?? "1") ?? 1
            pageCount = Int(attributeDict["pagecount"] ?? "1") ?? 1
        } else if elementName == "ty" {
            currentAttrId = attributeDict["id"] ?? ""
        } else if elementName == "dd" {
            currentAttrFlag = attributeDict["flag"] ?? "默认线路"
        } else if elementName == "video" {
            vId = ""; vName = ""; vPic = ""; vNote = ""; vYear = ""; vArea = ""
            vType = ""; vActor = ""; vDirector = ""; vDes = ""; playSources = []
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        currentText += string
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        let t = currentText.trimmingCharacters(in: .whitespacesAndNewlines)
        switch elementName {
        case "ty":
            if !currentAttrId.isEmpty && !t.isEmpty { categories.append(VodCategory(id: currentAttrId, name: t)) }
        case "id": vId = t
        case "name": vName = t
        case "pic": vPic = t.hasPrefix("//") ? "https:\(t)" : t
        case "note": vNote = t
        case "year": vYear = t
        case "area": vArea = t
        case "type": vType = t
        case "actor": vActor = t
        case "director": vDirector = t
        case "des": vDes = VodSourceEngine.cleanHtml(t)
        case "dd":
            let eps = VodSourceEngine.parseEpisodes(group: t)
            if !eps.isEmpty { playSources.append(VodPlaySource(name: currentAttrFlag, episodes: eps)) }
        case "video":
            if !vId.isEmpty && !vName.isEmpty {
                items.append(VodItem(id: vId, name: vName, pic: vPic, remarks: vNote, year: vYear, area: vArea, typeName: vType, sourceId: source.id, sourceName: source.name))
                detail = VodDetail(id: vId, name: vName, pic: vPic, remarks: vNote, year: vYear, area: vArea, actor: vActor, director: vDirector, content: vDes, typeName: vType, sourceId: source.id, sourceName: source.name, playSources: playSources)
            }
        default: break
        }
        currentTag = ""
        currentText = ""
    }
}
