import Foundation
import SwiftUI

struct ActivePlayerSession: Identifiable {
    let id = UUID()
    let sourceId: String?
    let vodId: String?
    let directTitle: String
    let directUrls: [String]
    let isLive: Bool
}

@MainActor
final class AppStore: ObservableObject {
    @Published var sources: [VideoSource] = []
    @Published var activeSourceId: String = ""
    @Published var subscriptions: [SubscriptionConfig] = []
    @Published var liveSources: [LiveSource] = []
    @Published var parses: [ParseInterface] = []
    @Published var history: [WatchHistoryItem] = []
    @Published var favorites: [FavoriteItem] = []
    @Published var settings: AppSettings = AppSettings()

    // Home Feed
    @Published var categories: [VodCategory] = []
    @Published var selectedCategoryId: String? = nil
    @Published var feedItems: [VodItem] = []
    @Published var isLoadingFeed: Bool = false
    @Published var isLoadingMore: Bool = false
    @Published var feedPage: Int = 1
    @Published var feedPageCount: Int = 1
    @Published var feedError: String? = nil

    // Progressive Search
    @Published var searchQuery: String = ""
    @Published var searchAllSources: Bool = true
    @Published var isSearching: Bool = false
    @Published var searchResults: [VodItem] = []
    @Published var searchError: String? = nil

    // Live TV
    @Published var selectedLiveSourceId: String = ""
    @Published var liveChannels: [LiveChannel] = []
    @Published var liveGroups: [String] = []
    @Published var selectedLiveGroup: String = "全部"
    @Published var isLoadingLive: Bool = false
    @Published var liveError: String? = nil

    // Import & Toast
    @Published var isImportingSub: Bool = false
    @Published var toastMessage: String? = nil

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
        Task { await loadHomeFeed(reset: true, autoFallback: true) }
    }

    var activeApiSources: [VideoSource] {
        sources.filter { $0.enabled && $0.type.isDirectApi }
    }

    var webSources: [VideoSource] {
        sources.filter { $0.enabled && $0.type == .WEB_SNIFFER }
    }

    var activeSource: VideoSource? {
        sources.first { $0.id == activeSourceId } ?? activeApiSources.first
    }

    func showToast(_ message: String) {
        toastMessage = message
        Task {
            try? await Task.sleep(nanoseconds: 2_600_000_000)
            if toastMessage == message { toastMessage = nil }
        }
    }

    // MARK: - Home Feed

    func selectSource(_ id: String) {
        activeSourceId = id
        selectedCategoryId = nil
        categories = []
        feedItems = []
        Task { await loadHomeFeed(reset: true, autoFallback: false) }
    }

    func selectCategory(_ id: String?) {
        selectedCategoryId = id
        feedItems = []
        Task { await loadHomeFeed(reset: true, autoFallback: false) }
    }

    func loadHomeFeed(reset: Bool = false, autoFallback: Bool = false) async {
        if selectedCategoryId == VodSourceEngine.yangyangCategoryId {
            await loadYangyangEnglishCartoons(reset: reset)
            return
        }
        guard let source = activeSource else {
            feedError = "请先在「影视源」页面启用或添加视频源"
            return
        }
        let nextPage = reset ? 1 : feedPage + 1
        if !reset && (isLoadingMore || feedPage >= feedPageCount) { return }

        if reset {
            isLoadingFeed = true
            feedError = nil
        } else {
            isLoadingMore = true
        }

        let childIds = categories.first { $0.id == selectedCategoryId }?.childIds ?? []

        do {
            let res = try await VodSourceEngine.shared.fetchVodList(
                source: source,
                categoryId: selectedCategoryId,
                page: nextPage,
                childCategoryIds: childIds
            )
            if reset {
                feedItems = res.items
            } else {
                var seen = Set(feedItems.map(\.id))
                feedItems.append(contentsOf: res.items.filter { seen.insert($0.id).inserted })
            }
            if !res.categories.isEmpty { categories = res.categories }
            feedPage = res.page
            feedPageCount = max(1, res.pageCount)
            isLoadingFeed = false
            isLoadingMore = false
            if feedItems.isEmpty { feedError = "该分类暂无影片内容" }
        } catch {
            if autoFallback && reset {
                for alt in activeApiSources where alt.id != source.id {
                    if let altRes = try? await VodSourceEngine.shared.fetchVodList(source: alt, page: 1),
                       !altRes.items.isEmpty {
                        activeSourceId = alt.id
                        categories = altRes.categories
                        feedItems = altRes.items
                        feedPage = altRes.page
                        feedPageCount = max(1, altRes.pageCount)
                        isLoadingFeed = false
                        isLoadingMore = false
                        showToast("已自动切换至高速可用源：\(alt.name)")
                        return
                    }
                }
            }
            isLoadingFeed = false
            isLoadingMore = false
            feedError = "加载失败：\(error.localizedDescription)"
        }
    }

    private func loadYangyangEnglishCartoons(reset: Bool) async {
        let nextPage = reset ? 1 : feedPage + 1
        if !reset && (isLoadingMore || feedPage >= feedPageCount) { return }
        if reset { isLoadingFeed = true; feedError = nil } else { isLoadingMore = true }
        let preferred = ["hongniu_json", "zy360_json", "jszy_json", "huya_json", "rycj_json", "ikun_json", "subo_en_cartoon", "guangsu_en_cartoon"]
        var targets: [VideoSource] = preferred.compactMap { pid in activeApiSources.first { $0.id == pid } }
        for s in activeApiSources where !targets.contains(where: { $0.id == s.id }) { targets.append(s) }
        targets = Array(targets.prefix(5))

        var acc = reset ? [VodItem]() : feedItems
        await withTaskGroup(of: [VodItem].self) { group in
            for src in targets {
                group.addTask {
                    guard let root = try? await VodSourceEngine.shared.fetchVodList(source: src, page: 1) else { return [] }
                    let cats = root.categories.filter { $0.name.contains("欧美动漫") || $0.name.contains("动画片") }.prefix(2)
                    var out: [VodItem] = []
                    for c in cats {
                        if let res = try? await VodSourceEngine.shared.fetchVodList(source: src, categoryId: c.id, page: nextPage) {
                            out.append(contentsOf: res.items)
                        }
                    }
                    return out.filter { VodSourceEngine.isStrictEnglishCartoon($0) }
                }
            }
            for await batch in group {
                if !batch.isEmpty {
                    acc.append(contentsOf: batch)
                    var seen = Set<String>()
                    self.feedItems = acc.filter { seen.insert($0.name.lowercased()).inserted }
                }
            }
        }
        feedPage = nextPage
        feedPageCount = max(nextPage + 1, 5)
        isLoadingFeed = false
        isLoadingMore = false
        if feedItems.isEmpty { feedError = "暂未拉取到英文动画片，点击重试" }
    }

    // MARK: - Progressive Streaming Multi-Source Search

    func executeSearch(keyword: String? = nil) {
        let q = (keyword ?? searchQuery).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return }
        searchQuery = q

        let targets: [VideoSource]
        if searchAllSources {
            let active = activeSource
            let all = activeApiSources.filter(\.searchable)
            var ordered: [VideoSource] = []
            if let active, active.searchable { ordered.append(active) }
            for s in all where !ordered.contains(where: { $0.id == s.id }) { ordered.append(s) }
            targets = Array(ordered.prefix(9))
        } else {
            targets = activeSource.map { [$0] } ?? []
        }

        guard !targets.isEmpty else {
            searchError = "没有可搜索的视频源"
            return
        }

        searchTask?.cancel()
        searchTask = Task {
            isSearching = true
            searchError = nil
            searchResults = []

            var accumulated: [VodItem] = []

            await withTaskGroup(of: [VodItem].self) { group in
                for src in targets {
                    group.addTask {
                        (try? await VodSourceEngine.shared.search(source: src, keyword: q, page: 1)) ?? []
                    }
                }
                for await batch in group {
                    guard !Task.isCancelled else { return }
                    if !batch.isEmpty {
                        accumulated.append(contentsOf: batch)
                        var seen = Set<String>()
                        let deduped = accumulated.filter { seen.insert($0.uniqueKey).inserted }
                        self.searchResults = deduped.sorted { a, b in
                            Self.matchScore(a.name, query: q) > Self.matchScore(b.name, query: q)
                        }
                        self.searchError = nil
                    }
                }
            }

            isSearching = false
            if searchResults.isEmpty {
                searchError = "未找到「\(q)」相关影片，可尝试缩短关键词或在「影视源」中添加更多源"
            }
        }
    }

    func clearSearch() {
        searchTask?.cancel()
        searchQuery = ""
        searchResults = []
        searchError = nil
        isSearching = false
    }

    private static func matchScore(_ title: String, query: String) -> Int {
        let t = title.lowercased(); let q = query.lowercased()
        if t == q { return 3 }
        if t.hasPrefix(q) { return 2 }
        if t.contains(q) { return 1 }
        return 0
    }

    // MARK: - Live TV

    func loadLiveSource(_ live: LiveSource, autoFallback: Bool = false) async {
        selectedLiveSourceId = live.id
        isLoadingLive = true
        liveError = nil
        do {
            let channels = try await LiveParser.shared.fetchChannels(url: live.url)
            if channels.isEmpty { throw NSError(domain: "Live", code: -1, userInfo: [NSLocalizedDescriptionKey: "直播列表为空"]) }
            liveChannels = channels
            var grpOrder: [String] = []
            for ch in channels where !grpOrder.contains(ch.group) { grpOrder.append(ch.group) }
            liveGroups = ["全部"] + grpOrder
            selectedLiveGroup = "全部"
            isLoadingLive = false
        } catch {
            if autoFallback {
                for alt in liveSources where alt.id != live.id {
                    if let chs = try? await LiveParser.shared.fetchChannels(url: alt.url), !chs.isEmpty {
                        selectedLiveSourceId = alt.id
                        liveChannels = chs
                        var grpOrder: [String] = []
                        for ch in chs where !grpOrder.contains(ch.group) { grpOrder.append(ch.group) }
                        liveGroups = ["全部"] + grpOrder
                        selectedLiveGroup = "全部"
                        isLoadingLive = false
                        showToast("已自动切换至可用直播源：\(alt.name)")
                        return
                    }
                }
            }
            isLoadingLive = false
            liveError = "加载直播源失败：\(error.localizedDescription)"
        }
    }

    // MARK: - History & Favorites

    func saveHistory(_ item: WatchHistoryItem) {
        history.removeAll { $0.sourceId == item.sourceId && $0.vodId == item.vodId }
        var updated = item
        updated.updatedAt = Int64(Date().timeIntervalSince1970 * 1000)
        history.insert(updated, at: 0)
        if history.count > 120 { history = Array(history.prefix(120)) }
        persist(history, file: "history.json")
    }

    func historyItem(sourceId: String, vodId: String) -> WatchHistoryItem? {
        history.first { $0.sourceId == sourceId && $0.vodId == vodId }
    }

    func clearHistory() {
        history = []
        persist(history, file: "history.json")
        showToast("已清空观看历史")
    }

    func isFavorite(sourceId: String, vodId: String) -> Bool {
        favorites.contains { $0.sourceId == sourceId && $0.vodId == vodId }
    }

    func toggleFavorite(detail: VodDetail) {
        if isFavorite(sourceId: detail.sourceId, vodId: detail.id) {
            favorites.removeAll { $0.sourceId == detail.sourceId && $0.vodId == detail.id }
            showToast("已取消收藏")
        } else {
            favorites.insert(FavoriteItem(
                vodId: detail.id,
                sourceId: detail.sourceId,
                sourceName: detail.sourceName,
                name: detail.name,
                pic: detail.pic,
                remarks: detail.remarks
            ), at: 0)
            showToast("已加入收藏夹")
        }
        persist(favorites, file: "favorites.json")
    }

    // MARK: - Sources & TVBox Import

    func upsertSource(_ source: VideoSource) {
        if let idx = sources.firstIndex(where: { $0.id == source.id }) {
            sources[idx] = source
        } else {
            sources.insert(source, at: 0)
        }
        persist(sources, file: "sources.json")
    }

    func deleteSource(_ id: String) {
        sources.removeAll { $0.id == id }
        persist(sources, file: "sources.json")
        if activeSourceId == id { activeSourceId = activeApiSources.first?.id ?? "" }
        showToast("已删除视频源")
    }

    func testSource(_ source: VideoSource) {
        Task {
            let ms = await VodSourceEngine.shared.pingSource(source)
            if let idx = sources.firstIndex(where: { $0.id == source.id }) {
                sources[idx].latencyMs = ms
                persist(sources, file: "sources.json")
            }
            if ms == -2 { showToast("\(source.name) 为 TVBox 爬虫源") }
            else if ms < 0 { showToast("\(source.name) 连接超时") }
            else { showToast("\(source.name) 响应正常 (\(ms) ms)") }
        }
    }

    func importTvBoxUrl(_ urlString: String) {
        let trimmed = urlString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        Task {
            isImportingSub = true
            do {
                let res = try await TvBoxConfigParser.shared.importFromUrl(trimmed)
                mergeTvBoxResult(res)
                showToast("导入成功：直连源 \(res.directPlayableCount) 个、直播 \(res.liveSources.count) 个、解析 \(res.parses.count) 个")
            } catch {
                showToast("订阅导入失败：\(error.localizedDescription)")
            }
            isImportingSub = false
        }
    }

    func importTvBoxJson(_ rawJson: String) {
        guard !rawJson.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        do {
            let res = try TvBoxConfigParser.shared.parseJsonText(rawJson)
            mergeTvBoxResult(res)
            showToast("JSON 导入成功：新增 \(res.sources.count) 个站点")
        } catch {
            showToast("JSON 解析失败：\(error.localizedDescription)")
        }
    }

    func addLiveSource(name: String, url: String) {
        let live = LiveSource(id: "live_\(UUID().uuidString.prefix(8))", name: name.isEmpty ? "自定义直播源" : name, url: url)
        liveSources.insert(live, at: 0)
        persist(liveSources, file: "lives.json")
        showToast("已添加直播源：\(live.name)")
    }

    func exportBackupJson() -> String {
        let bundle = BackupBundle(sources: sources, subscriptions: subscriptions, liveSources: liveSources, parses: parses, settings: settings)
        guard let data = try? encoder.encode(bundle), let str = String(data: data, encoding: .utf8) else { return "{}" }
        return str
    }

    func importBackupJson(_ raw: String) {
        guard let data = raw.data(using: .utf8), let bundle = try? decoder.decode(BackupBundle.self, from: data) else {
            showToast("备份 JSON 格式错误")
            return
        }
        if !bundle.sources.isEmpty { sources = bundle.sources; persist(sources, file: "sources.json") }
        if !bundle.subscriptions.isEmpty { subscriptions = bundle.subscriptions; persist(subscriptions, file: "subs.json") }
        if !bundle.liveSources.isEmpty { liveSources = bundle.liveSources; persist(liveSources, file: "lives.json") }
        if !bundle.parses.isEmpty { parses = bundle.parses; persist(parses, file: "parses.json") }
        settings = bundle.settings; persist(settings, file: "settings.json")
        showToast("配置恢复成功")
        Task { await loadHomeFeed(reset: true) }
    }

    func updateSettings(_ mutate: (inout AppSettings) -> Void) {
        mutate(&settings)
        persist(settings, file: "settings.json")
    }

    private func mergeTvBoxResult(_ res: TvBoxImportResult) {
        if let idx = subscriptions.firstIndex(where: { $0.url == res.subscription.url || $0.id == res.subscription.id }) {
            subscriptions[idx] = res.subscription
        } else {
            subscriptions.insert(res.subscription, at: 0)
        }
        let existingUrls = Set(sources.map(\.apiUrl))
        sources.append(contentsOf: res.sources.filter { !existingUrls.contains($0.apiUrl) })

        let existingLives = Set(liveSources.map(\.url))
        liveSources.append(contentsOf: res.liveSources.filter { !existingLives.contains($0.url) })

        let existingParses = Set(parses.map(\.url))
        parses.append(contentsOf: res.parses.filter { !existingParses.contains($0.url) })

        if !res.adHosts.isEmpty {
            settings.adBlockHosts = Array(Set(settings.adBlockHosts + res.adHosts))
            persist(settings, file: "settings.json")
        }
        persist(subscriptions, file: "subs.json")
        persist(sources, file: "sources.json")
        persist(liveSources, file: "lives.json")
        persist(parses, file: "parses.json")
    }

    // MARK: - Persistence & Defaults

    private func loadAll() {
        let defaults = Self.defaultSources()
        let deadHosts = ["caiji.dyttzyapi.com", "api.1080zyku.com"]
        if let saved: [VideoSource] = read("sources.json") {
            let filtered = saved.filter { src in !deadHosts.contains(where: { src.apiUrl.contains($0) }) }
            let existingUrls = Set(filtered.map(\.apiUrl))
            let missing = defaults.filter { !existingUrls.contains($0.apiUrl) }
            sources = missing.isEmpty ? filtered : (filtered + missing)
            if sources != saved { persist(sources, file: "sources.json") }
        } else {
            sources = defaults
            persist(sources, file: "sources.json")
        }
        activeSourceId = activeApiSources.first?.id ?? sources.first?.id ?? ""
        subscriptions = read("subs.json") ?? Self.defaultSubscriptions()
        liveSources = read("lives.json") ?? Self.defaultLiveSources()
        parses = read("parses.json") ?? Self.defaultParses()
        history = read("history.json") ?? []
        favorites = read("favorites.json") ?? []
        settings = read("settings.json") ?? AppSettings()
    }

    private func read<T: Decodable>(_ file: String) -> T? {
        let url = docsDir.appendingPathComponent(file)
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? decoder.decode(T.self, from: data)
    }

    private func persist<T: Encodable>(_ value: T, file: String) {
        let url = docsDir.appendingPathComponent(file)
        if let data = try? encoder.encode(value) {
            try? data.write(to: url, options: .atomic)
        }
    }

    static func defaultSources() -> [VideoSource] {
        [
            VideoSource(id: "jszy_json", name: "极速资源 (高清秒播)", type: .MACCMS_JSON, apiUrl: "https://jszyapi.com/api.php/provide/vod/", group: "内置精选 (实测直连)"),
            VideoSource(id: "huya_json", name: "虎牙资源 (高清直链)", type: .MACCMS_JSON, apiUrl: "https://www.huyaapi.com/api.php/provide/vod/", group: "内置精选 (实测直连)"),
            VideoSource(id: "rycj_json", name: "如意资源 (高清直链)", type: .MACCMS_JSON, apiUrl: "https://cj.rycjapi.com/api.php/provide/vod/", group: "内置精选 (实测直连)"),
            VideoSource(id: "zy360_json", name: "360资源 (高清M3U8)", type: .MACCMS_JSON, apiUrl: "https://360zy.com/api.php/provide/vod/", group: "内置精选 (实测直连)"),
            VideoSource(id: "hongniu_json", name: "红牛资源 (高清直链)", type: .MACCMS_JSON, apiUrl: "https://www.hongniuzy2.com/api.php/provide/vod/", group: "内置精选 (实测直连)"),
            VideoSource(id: "ikun_json", name: "iKun资源 (高清直链)", type: .MACCMS_JSON, apiUrl: "https://ikunzyapi.com/api.php/provide/vod/", group: "内置精选 (实测直连)"),
            VideoSource(id: "xinlang_json", name: "新浪资源 (高清直链)", type: .MACCMS_JSON, apiUrl: "https://api.xinlangapi.com/xinlangapi.php/provide/vod/", group: "内置精选 (实测直连)"),
            VideoSource(id: "modu_json", name: "魔都资源 (动漫影视)", type: .MACCMS_JSON, apiUrl: "https://caiji.moduapi.cc/api.php/provide/vod/", group: "内置精选 (实测直连)"),
            VideoSource(id: "maoyan_json", name: "猫眼资源 (高清直链)", type: .MACCMS_JSON, apiUrl: "https://api.maoyanapi.top/api.php/provide/vod/", group: "内置精选 (实测直连)"),
            VideoSource(id: "subo_en_cartoon", name: "速播动漫 (英文动画/欧美动漫)", type: .MACCMS_JSON, apiUrl: "https://subocaiji.com/api.php/provide/vod/", group: "英文动画 / 欧美动漫精选"),
            VideoSource(id: "guangsu_en_cartoon", name: "光速动漫 (英文版/迪士尼动画)", type: .MACCMS_JSON, apiUrl: "https://api.guangsuapi.com/api.php/provide/vod/", group: "英文动画 / 欧美动漫精选"),
            VideoSource(id: "huohu_en_cartoon", name: "火狐动漫 (英文动画/海绵宝宝/布鲁伊)", type: .MACCMS_JSON, apiUrl: "https://hhzyapi.com/api.php/provide/vod/", group: "英文动画 / 欧美动漫精选"),
            VideoSource(id: "web_bili_en_cartoon", name: "B站英文动画专区 (网页嗅探)", type: .WEB_SNIFFER, apiUrl: "https://m.bilibili.com/search?keyword=%E8%8B%B1%E6%96%87%E5%8A%A8%E7%94%BB%E7%89%87", group: "英文动画 / 欧美动漫精选"),
            VideoSource(id: "web_pbs_kids", name: "PBS Kids 英语原版动画 (网页嗅探)", type: .WEB_SNIFFER, apiUrl: "https://pbskids.org/video/", group: "英文动画 / 欧美动漫精选"),
            VideoSource(id: "web_bilibili", name: "哔哩哔哩 (网页嗅探)", type: .WEB_SNIFFER, apiUrl: "https://m.bilibili.com/", group: "网页嗅探站点"),
            VideoSource(id: "web_qq", name: "腾讯视频 (解析/嗅探)", type: .WEB_SNIFFER, apiUrl: "https://m.v.qq.com/", group: "网页嗅探站点"),
            VideoSource(id: "web_iqiyi", name: "爱奇艺 (解析/嗅探)", type: .WEB_SNIFFER, apiUrl: "https://m.iqiyi.com/", group: "网页嗅探站点")
        ]
    }

    static func defaultSubscriptions() -> [SubscriptionConfig] {
        [
            SubscriptionConfig(id: "sub_cyao_m", name: "cyao2q/files 主配置 (m.json)", url: "https://cyao2q.github.io/files/m.json"),
            SubscriptionConfig(id: "sub_cyao_xs", name: "cyao2q/files 扩展配置 (xs.json)", url: "https://cyao2q.github.io/files/xs.json")
        ]
    }

    static func defaultLiveSources() -> [LiveSource] {
        [
            LiveSource(id: "live_zbds_v4", name: "精选 IPv4 央视卫视 (实时校验)", url: "https://live.zbds.top/tv/iptv4.m3u", epg: "http://epg.51zmt.top:8000/e.xml"),
            LiveSource(id: "live_guovin_fastly", name: "全国高清直播 (Fastly 加速)", url: "https://fastly.jsdelivr.net/gh/Guovin/iptv-api@gd/output/result.m3u"),
            LiveSource(id: "live_suxuang_v4", name: "4K/高清 IPv4 频道 (Fastly)", url: "https://fastly.jsdelivr.net/gh/suxuang/myIPTV@main/ipv4.m3u", epg: "http://epg.51zmt.top:8000/e.xml"),
            LiveSource(id: "live_guovin_gcore", name: "全国高清直播 (Gcore 备用)", url: "https://gcore.jsdelivr.net/gh/Guovin/iptv-api@gd/output/result.m3u")
        ]
    }

    static func defaultParses() -> [ParseInterface] {
        [
            ParseInterface(name: "xmflv 解析", type: 0, url: "https://jx.xmflv.com/?url="),
            ParseInterface(name: "pouyun 解析", type: 0, url: "https://www.pouyun.com/?url="),
            ParseInterface(name: "xymp4 解析", type: 0, url: "https://jx.xymp4.cc/?url="),
            ParseInterface(name: "yparse 解析", type: 0, url: "https://jx.yparse.com/index.php?url="),
            ParseInterface(name: "8090g 解析", type: 0, url: "https://www.8090g.cn/?url=")
        ]
    }
}
