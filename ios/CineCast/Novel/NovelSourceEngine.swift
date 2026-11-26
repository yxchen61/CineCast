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
        if source.sourceType == .GUTENDEX_API {
            return [
                NovelExploreCategory(title: "中文经典名著", url: "https://gutendex.com/books/?languages=zh&page={{page}}"),
                NovelExploreCategory(title: "世界热门文学", url: "https://gutendex.com/books/?sort=popular&page={{page}}"),
                NovelExploreCategory(title: "科幻与冒险", url: "https://gutendex.com/books/?topic=science+fiction&page={{page}}"),
                NovelExploreCategory(title: "侦探与悬疑", url: "https://gutendex.com/books/?topic=detective&page={{page}}")
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
            let probe = source.sourceType == .GUTENDEX_API
                ? "https://gutendex.com/books/?languages=zh&page=1"
                : source.baseUrl
            let txt = try await fetchText(urlSpec: probe, source: source)
            return txt.count < 24 ? -1 : max(1, Int(Date().timeIntervalSince(start) * 1000))
        } catch {
            return -1
        }
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
}
