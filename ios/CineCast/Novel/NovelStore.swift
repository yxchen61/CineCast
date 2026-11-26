import Foundation
import SwiftUI

/// 独立小说阅读模块状态与本地持久化存储（与影视 AppStore 完全分离）
@MainActor
final class NovelStore: ObservableObject {
    @Published var sources: [NovelSource] = []
    @Published var activeSourceId: String = ""
    @Published var subscriptions: [NovelSubscription] = []
    @Published var bookshelf: [BookshelfItem] = []
    @Published var readingSettings: NovelReadingSettings = NovelReadingSettings()

    // Explore
    @Published var categories: [NovelExploreCategory] = []
    @Published var selectedCategoryIndex: Int = 0
    @Published var exploreBooks: [NovelBook] = []
    @Published var explorePage: Int = 1
    @Published var isLoadingExplore: Bool = false
    @Published var exploreError: String? = nil

    // Multi-source Search
    @Published var searchQuery: String = ""
    @Published var searchAllSources: Bool = true
    @Published var isSearching: Bool = false
    @Published var searchResults: [NovelBook] = []
    @Published var searchError: String? = nil

    // Reader
    @Published var activeBook: NovelBook? = nil
    @Published var activeReaderSource: NovelSource? = nil
    @Published var chapters: [NovelChapter] = []
    @Published var currentChapterIndex: Int = 0
    @Published var chapterContent: String = ""
    @Published var isLoadingReader: Bool = false
    @Published var readerError: String? = nil
    @Published var switchCandidates: [NovelBook] = []
    @Published var isSearchingSwitch: Bool = false

    @Published var isImporting: Bool = false
    @Published var toastMessage: String? = nil

    private var chapterCache: [String: String] = [:]
    private var searchTask: Task<Void, Never>?
    private let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = [.prettyPrinted]
        return e
    }()
    private let decoder = JSONDecoder()

    private var docsDir: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    init() {
        loadAll()
        Task { await loadExplore(reset: true, autoFallback: true) }
    }

    var enabledSources: [NovelSource] { sources.filter(\.enabled) }

    var activeSource: NovelSource? {
        sources.first { $0.id == activeSourceId } ?? enabledSources.first
    }

    func showToast(_ msg: String) {
        toastMessage = msg
        Task {
            try? await Task.sleep(nanoseconds: 2_600_000_000)
            if toastMessage == msg { toastMessage = nil }
        }
    }

    // MARK: - Explore

    func selectSource(_ id: String) {
        activeSourceId = id
        selectedCategoryIndex = 0
        exploreBooks = []
        Task { await loadExplore(reset: true, autoFallback: false) }
    }

    func selectCategory(_ idx: Int) {
        selectedCategoryIndex = idx
        Task { await loadExplore(reset: true, autoFallback: false) }
    }

    func loadExplore(reset: Bool = false, autoFallback: Bool = false) async {
        guard let source = activeSource else {
            exploreError = "请先在「书源」页启用或导入小说源"
            return
        }
        let cats = NovelSourceEngine.shared.getExploreCategories(source: source)
        categories = cats
        let idx = min(max(0, selectedCategoryIndex), max(0, cats.count - 1))
        guard idx < cats.count else {
            exploreError = "当前书源无分类，请直接使用顶部搜索框"
            return
        }
        let nextPage = reset ? 1 : explorePage + 1
        if reset {
            isLoadingExplore = true
            exploreError = nil
        }

        do {
            let list = try await NovelSourceEngine.shared.fetchExplore(source: source, categoryUrl: cats[idx].url, page: nextPage)
            if reset {
                exploreBooks = list
            } else {
                var seen = Set(exploreBooks.map(\.bookUrl))
                exploreBooks.append(contentsOf: list.filter { seen.insert($0.bookUrl).inserted })
            }
            explorePage = nextPage
            isLoadingExplore = false
            if exploreBooks.isEmpty { exploreError = "该分类暂无数据" }
        } catch {
            if autoFallback && reset {
                for alt in enabledSources where alt.id != source.id {
                    let altCats = NovelSourceEngine.shared.getExploreCategories(source: alt)
                    if let first = altCats.first,
                       let altBooks = try? await NovelSourceEngine.shared.fetchExplore(source: alt, categoryUrl: first.url, page: 1),
                       !altBooks.isEmpty {
                        activeSourceId = alt.id
                        categories = altCats
                        selectedCategoryIndex = 0
                        exploreBooks = altBooks
                        explorePage = 1
                        isLoadingExplore = false
                        showToast("已自动切换可用书源：\(alt.name)")
                        return
                    }
                }
            }
            isLoadingExplore = false
            exploreError = "加载失败：\(error.localizedDescription)"
        }
    }

    // MARK: - Multi-Source Concurrent Search

    func executeSearch(keyword: String? = nil) {
        let q = (keyword ?? searchQuery).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return }
        searchQuery = q
        let targets: [NovelSource] = searchAllSources
            ? Array(enabledSources.filter(\.searchable).prefix(8))
            : (activeSource.map { [$0] } ?? [])
        guard !targets.isEmpty else {
            searchError = "没有可搜索的小说源"
            return
        }
        searchTask?.cancel()
        searchTask = Task {
            isSearching = true
            searchError = nil
            searchResults = []
            var acc: [NovelBook] = []
            await withTaskGroup(of: [NovelBook].self) { group in
                for src in targets {
                    group.addTask {
                        (try? await NovelSourceEngine.shared.search(source: src, keyword: q, page: 1)) ?? []
                    }
                }
                for await batch in group {
                    guard !Task.isCancelled else { return }
                    if !batch.isEmpty {
                        acc.append(contentsOf: batch)
                        var seen = Set<String>()
                        self.searchResults = acc.filter { seen.insert($0.uniqueKey).inserted }
                        self.searchError = nil
                    }
                }
            }
            isSearching = false
            if searchResults.isEmpty { searchError = "未找到「\(q)」相关小说" }
        }
    }

    func clearSearch() {
        searchTask?.cancel()
        searchQuery = ""
        searchResults = []
        searchError = nil
        isSearching = false
    }

    // MARK: - Bookshelf & Reader

    func isInBookshelf(_ book: NovelBook) -> Bool {
        bookshelf.contains { $0.bookKey == book.uniqueKey }
    }

    func toggleBookshelf(_ book: NovelBook, totalChapters: Int = 0) {
        if isInBookshelf(book) {
            bookshelf.removeAll { $0.bookKey == book.uniqueKey }
            showToast("已移出书架")
        } else {
            addToBookshelf(book, chapterIndex: 0, chapterTitle: "", totalChapters: totalChapters)
            showToast("已加入书架")
        }
        persist(bookshelf, file: "novel_bookshelf.json")
    }

    func addToBookshelf(_ book: NovelBook, chapterIndex: Int, chapterTitle: String, totalChapters: Int) {
        let existing = bookshelf.first { $0.bookKey == book.uniqueKey }
        let item = BookshelfItem(
            bookKey: book.uniqueKey,
            sourceId: book.sourceId,
            sourceName: book.sourceName,
            title: book.title,
            author: book.author,
            coverUrl: book.coverUrl,
            intro: book.intro,
            kind: book.kind,
            latestChapter: book.latestChapter,
            bookUrl: book.bookUrl,
            tocUrl: book.tocUrl,
            currentChapterIndex: existing?.currentChapterIndex ?? chapterIndex,
            currentChapterTitle: (existing?.currentChapterTitle.isEmpty == false) ? existing!.currentChapterTitle : chapterTitle,
            totalChapters: totalChapters > 0 ? totalChapters : (existing?.totalChapters ?? 0),
            lastReadAt: Int64(Date().timeIntervalSince1970 * 1000)
        )
        bookshelf.removeAll { $0.bookKey == book.uniqueKey }
        bookshelf.insert(item, at: 0)
        persist(bookshelf, file: "novel_bookshelf.json")
    }

    func removeFromBookshelf(_ key: String) {
        bookshelf.removeAll { $0.bookKey == key }
        persist(bookshelf, file: "novel_bookshelf.json")
    }

    func openBookInReader(_ book: NovelBook, startChapter: Int? = nil) async {
        guard let source = sources.first(where: { $0.id == book.sourceId }) ?? activeSource else {
            readerError = "找不到对应书源"
            return
        }
        activeBook = book
        activeReaderSource = source
        isLoadingReader = true
        readerError = nil
        do {
            let (updated, chs) = try await NovelSourceEngine.shared.fetchBookDetailAndToc(source: source, book: book)
            activeBook = updated
            chapters = chs
            guard !chs.isEmpty else {
                isLoadingReader = false
                readerError = "目录为空，请点击右上角换源"
                return
            }
            let savedIdx = bookshelf.first(where: { $0.bookKey == updated.uniqueKey })?.currentChapterIndex ?? 0
            let target = min(max(0, startChapter ?? savedIdx), chs.count - 1)
            addToBookshelf(updated, chapterIndex: target, chapterTitle: chs[target].title, totalChapters: chs.count)
            await loadChapter(index: target)
        } catch {
            isLoadingReader = false
            readerError = "加载失败：\(error.localizedDescription)"
        }
    }

    func openShelfItem(_ item: BookshelfItem) async {
        let book = NovelBook(
            id: item.bookKey,
            sourceId: item.sourceId,
            sourceName: item.sourceName,
            title: item.title,
            author: item.author,
            coverUrl: item.coverUrl,
            intro: item.intro,
            kind: item.kind,
            latestChapter: item.latestChapter,
            bookUrl: item.bookUrl,
            tocUrl: item.tocUrl
        )
        await openBookInReader(book, startChapter: item.currentChapterIndex)
    }

    func loadChapter(index: Int) async {
        guard let source = activeReaderSource, let book = activeBook, index >= 0, index < chapters.count else { return }
        let ch = chapters[index]
        currentChapterIndex = index
        if let cached = chapterCache[ch.url] {
            chapterContent = cached
            isLoadingReader = false
            readerError = nil
            saveProgress(book: book, index: index, title: ch.title)
            return
        }
        isLoadingReader = true
        readerError = nil
        do {
            let text = try await NovelSourceEngine.shared.fetchChapterContent(source: source, chapter: ch)
            chapterCache[ch.url] = text
            chapterContent = text
            isLoadingReader = false
            saveProgress(book: book, index: index, title: ch.title)
        } catch {
            isLoadingReader = false
            readerError = "加载正文失败：\(error.localizedDescription)"
        }
    }

    private func saveProgress(book: NovelBook, index: Int, title: String) {
        if let idx = bookshelf.firstIndex(where: { $0.bookKey == book.uniqueKey }) {
            var item = bookshelf.remove(at: idx)
            item.currentChapterIndex = index
            item.currentChapterTitle = title
            item.totalChapters = chapters.count
            item.lastReadAt = Int64(Date().timeIntervalSince1970 * 1000)
            bookshelf.insert(item, at: 0)
            persist(bookshelf, file: "novel_bookshelf.json")
        }
    }

    func searchAlternativeSources() async {
        guard let book = activeBook else { return }
        isSearchingSwitch = true
        switchCandidates = []
        let targets = Array(enabledSources.filter { $0.searchable && $0.id != book.sourceId }.prefix(6))
        await withTaskGroup(of: NovelBook?.self) { group in
            for src in targets {
                group.addTask {
                    let res = (try? await NovelSourceEngine.shared.search(source: src, keyword: book.title, page: 1)) ?? []
                    return res.first
                }
            }
            for await cand in group {
                if let cand { switchCandidates.append(cand) }
            }
        }
        isSearchingSwitch = false
    }

    // MARK: - Sources & GitHub Import

    func importLegadoUrl(_ url: String) {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        Task {
            isImporting = true
            do {
                let res = try await NovelSourceEngine.shared.importLegadoUrl(trimmed)
                mergeImport(res)
                showToast("导入成功：解析 \(res.sources.count) 个可用小说源")
            } catch {
                showToast("拉取失败：\(error.localizedDescription)")
            }
            isImporting = false
        }
    }

    func importLegadoJson(_ raw: String) {
        do {
            let res = try NovelSourceEngine.shared.parseLegadoJsonText(raw)
            mergeImport(res)
            showToast("导入成功：新增 \(res.sources.count) 个小说源")
        } catch {
            showToast("解析失败：\(error.localizedDescription)")
        }
    }

    func upsertSource(_ src: NovelSource) {
        if let idx = sources.firstIndex(where: { $0.id == src.id }) {
            sources[idx] = src
        } else {
            sources.insert(src, at: 0)
        }
        persist(sources, file: "novel_sources.json")
    }

    func deleteSource(_ id: String) {
        sources.removeAll { $0.id == id }
        persist(sources, file: "novel_sources.json")
        if activeSourceId == id { activeSourceId = enabledSources.first?.id ?? "" }
    }

    func testSource(_ src: NovelSource) {
        Task {
            let ms = await NovelSourceEngine.shared.pingSource(src)
            if let idx = sources.firstIndex(where: { $0.id == src.id }) {
                sources[idx].latencyMs = ms
                persist(sources, file: "novel_sources.json")
            }
            showToast(ms < 0 ? "\(src.name) 连接超时" : "\(src.name) 响应正常 (\(ms)ms)")
        }
    }

    func updateReadingSettings(_ mutate: (inout NovelReadingSettings) -> Void) {
        mutate(&readingSettings)
        persist(readingSettings, file: "novel_settings.json")
    }

    private func mergeImport(_ res: NovelImportResult) {
        if let idx = subscriptions.firstIndex(where: { $0.url == res.subscription.url && !res.subscription.url.isEmpty }) {
            subscriptions[idx] = res.subscription
        } else {
            subscriptions.insert(res.subscription, at: 0)
        }
        let existingKeys = Set(sources.map { "\($0.baseUrl)|\($0.name)" })
        sources.append(contentsOf: res.sources.filter { !existingKeys.contains("\($0.baseUrl)|\($0.name)") })
        persist(subscriptions, file: "novel_subs.json")
        persist(sources, file: "novel_sources.json")
    }

    // MARK: - Persistence & Defaults

    private func loadAll() {
        let defaults = Self.defaultNovelSources()
        if let saved: [NovelSource] = read("novel_sources.json") {
            let isPreV140 = !saved.contains(where: { $0.id == "open_wikisource_classics" })
            let flakyIds: Set<String> = ["git_xiu2_sudugu", "git_liufuyou_qbtr"]
            let cleaned = saved.map { src -> NovelSource in
                if isPreV140 && flakyIds.contains(src.id) {
                    var c = src; c.enabled = false; c.group = "备用网页规则源 (按需手动开启)"; return c
                }
                return src
            }
            let existingIds = Set(cleaned.map(\.id))
            let missing = defaults.filter { !existingIds.contains($0.id) && $0.enabled }
            sources = missing + cleaned
            if !missing.isEmpty || isPreV140 { persist(sources, file: "novel_sources.json") }
        } else {
            sources = defaults
            persist(sources, file: "novel_sources.json")
        }
        activeSourceId = enabledSources.first?.id ?? sources.first?.id ?? ""
        subscriptions = read("novel_subs.json") ?? Self.defaultNovelSubscriptions()
        bookshelf = read("novel_bookshelf.json") ?? []
        readingSettings = read("novel_settings.json") ?? NovelReadingSettings()
    }

    private func read<T: Decodable>(_ file: String) -> T? {
        let url = docsDir.appendingPathComponent(file)
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? decoder.decode(T.self, from: data)
    }

    private func persist<T: Encodable>(_ value: T, file: String) {
        let url = docsDir.appendingPathComponent(file)
        if let data = try? encoder.encode(value) { try? data.write(to: url, options: .atomic) }
    }

    static func defaultNovelSources() -> [NovelSource] {
        [
            NovelSource(
                id: "open_wikisource_classics",
                name: "维基文库 · 中文名著典藏 (100%可读)",
                baseUrl: "https://zh.wikisource.org",
                group: "名著典藏 (永久直连)",
                sourceType: .WIKISOURCE_API,
                searchUrl: "https://zh.wikisource.org/w/api.php"
            ),
            NovelSource(
                id: "open_gutendex_classics",
                name: "古登堡世界名著 (中英公版经典)",
                baseUrl: "https://gutendex.com",
                group: "名著典藏 (永久直连)",
                sourceType: .GUTENDEX_API,
                searchUrl: "https://gutendex.com/books/?search={{key}}&page={{page}}"
            ),
            NovelSource(
                id: "git_xiu2_kuwo",
                name: "酷我小说书城 (API 极速直连)",
                baseUrl: "http://appi.kuwo.cn",
                group: "精选直连 (GitHub API)",
                sourceType: .LEGADO_JSON,
                searchUrl: "/novels/api/book/search?keyword={{key}}&pi={{page}}&ps=30",
                exploreUrl: "历史演义::/novels/api/book/search?keyword=三国&pi={{page}}&ps=20\n古典红楼::/novels/api/book/search?keyword=红楼&pi={{page}}&ps=20\n明清风云::/novels/api/book/search?keyword=明朝&pi={{page}}&ps=20\n热门玄幻::/novels/api/book/search?keyword=斗罗&pi={{page}}&ps=20\n修仙仙侠::/novels/api/book/search?keyword=修仙&pi={{page}}&ps=20\n都市异能::/novels/api/book/search?keyword=都市&pi={{page}}&ps=20",
                headers: ["User-Agent": "Dalvik/2.1.0 (Linux; U; Android 12; Pixel 7)"],
                ruleSearch: NovelRuleSearch(bookList: "$.data", name: "$.title", author: "$.author_name", bookUrl: "/novels/api/book/{{$.book_id}}", coverUrl: "$.cover_url", intro: "$.intro", kind: "$.category_name", wordCount: "$.all_words"),
                ruleBookInfo: NovelRuleBookInfo(`init`: "$.data", name: "$.title", author: "$.author_name", coverUrl: "$.cover_url", intro: "$.intro", kind: "$.category_name", lastChapter: "$.new_chapter_name", tocUrl: "/novels/api/book/{{$.book_id}}/chapters?pi=1&ps=2000"),
                ruleToc: NovelRuleToc(chapterList: "$.data", chapterName: "$.chapter_title", chapterUrl: "/novels/api/book/{{$.book_id}}/chapters/{{$.chapter_id}}"),
                ruleContent: NovelRuleContent(content: "$.data.content")
            ),
            NovelSource(
                id: "git_xiu2_kuwo_classics",
                name: "酷我历史与文学馆 (API 直连)",
                baseUrl: "http://appi.kuwo.cn",
                group: "名著典藏 (永久直连)",
                sourceType: .LEGADO_JSON,
                searchUrl: "/novels/api/book/search?keyword={{key}}&pi={{page}}&ps=30",
                exploreUrl: "三国两晋::/novels/api/book/search?keyword=三国&pi={{page}}&ps=20\n大明风华::/novels/api/book/search?keyword=明朝&pi={{page}}&ps=20\n盛唐气象::/novels/api/book/search?keyword=大唐&pi={{page}}&ps=20\n大宋风云::/novels/api/book/search?keyword=大宋&pi={{page}}&ps=20\n民国往事::/novels/api/book/search?keyword=民国&pi={{page}}&ps=20\n传统武侠::/novels/api/book/search?keyword=武侠&pi={{page}}&ps=20",
                headers: ["User-Agent": "Dalvik/2.1.0 (Linux; U; Android 12; Pixel 7)"],
                ruleSearch: NovelRuleSearch(bookList: "$.data", name: "$.title", author: "$.author_name", bookUrl: "/novels/api/book/{{$.book_id}}", coverUrl: "$.cover_url", intro: "$.intro", kind: "$.category_name", wordCount: "$.all_words"),
                ruleBookInfo: NovelRuleBookInfo(`init`: "$.data", name: "$.title", author: "$.author_name", coverUrl: "$.cover_url", intro: "$.intro", kind: "$.category_name", lastChapter: "$.new_chapter_name", tocUrl: "/novels/api/book/{{$.book_id}}/chapters?pi=1&ps=2000"),
                ruleToc: NovelRuleToc(chapterList: "$.data", chapterName: "$.chapter_title", chapterUrl: "/novels/api/book/{{$.book_id}}/chapters/{{$.chapter_id}}"),
                ruleContent: NovelRuleContent(content: "$.data.content")
            ),
            NovelSource(
                id: "git_xiu2_sudugu",
                name: "速读谷 (备用规则源)",
                baseUrl: "https://www.sudugu.co",
                group: "备用网页规则源 (按需手动开启)",
                sourceType: .LEGADO_HTML,
                enabled: false,
                searchUrl: "/modules/article/search.php,{\"method\":\"POST\",\"body\":\"searchkey={{key}}&action=login&searchtype=all&submit=\"}",
                exploreUrl: "最近更新::/top/lastupdate/{{page}}.html\n完本排行::/quanben/{{page}}.html"
            ),
            NovelSource(
                id: "git_liufuyou_qbtr",
                name: "全本同人 (备用规则源)",
                baseUrl: "https://www.qbtr.me",
                group: "备用网页规则源 (按需手动开启)",
                sourceType: .LEGADO_HTML,
                enabled: false,
                charset: "GB2312",
                searchUrl: "/e/search/index.php,{\"charset\":\"gb2312\",\"method\":\"POST\",\"body\":\"keyboard={{key}}&show=title&classid=0\"}",
                exploreUrl: "同人小说::/tongren/\n常规小说::/changgui/"
            )
        ]
    }

    static func defaultNovelSubscriptions() -> [NovelSubscription] {
        [
            NovelSubscription(id: "nsub_xiu2_fastly", name: "XIU2/Yuedu 精选书源 (CDN)", url: "https://fastly.jsdelivr.net/gh/XIU2/Yuedu@master/shuyuan", repoHint: "github.com/XIU2/Yuedu"),
            NovelSubscription(id: "nsub_liufuyou_yiove", name: "liufuyou/read Yiove 综合书源", url: "https://fastly.jsdelivr.net/gh/liufuyou/read@main/sources/yiove-novel.json", repoHint: "github.com/liufuyou/read"),
            NovelSubscription(id: "nsub_liufuyou_yck", name: "liufuyou/read YCK 全量书源", url: "https://fastly.jsdelivr.net/gh/liufuyou/read@main/sources/yck-novel.json", repoHint: "github.com/liufuyou/read"),
            NovelSubscription(id: "nsub_openrepos", name: "openrepos 通用阅读 3.0 书源", url: "https://fastly.jsdelivr.net/gh/openrepos/booksource@main/yuedu.json", repoHint: "github.com/openrepos/booksource")
        ]
    }
}
