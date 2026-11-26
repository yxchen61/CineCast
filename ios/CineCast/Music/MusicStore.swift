import AVFoundation
import Foundation
import SwiftUI

/// 独立音乐播放状态与持久化存储（与视频 AppStore、小说 NovelStore 完全隔离）
@MainActor
final class MusicStore: ObservableObject {
    @Published var sources: [MusicSource] = []
    @Published var activeSourceId: String = ""
    @Published var subscriptions: [MusicSubscription] = []
    @Published var favorites: [MusicTrack] = []
    @Published var playlists: [MusicPlaylist] = []

    // Discover & Search
    @Published var selectedChartIndex: Int = 0
    @Published var discoverTracks: [MusicTrack] = []
    @Published var isLoadingDiscover: Bool = false
    @Published var discoverError: String? = nil

    @Published var searchQuery: String = ""
    @Published var isSearching: Bool = false
    @Published var searchResults: [MusicTrack] = []
    @Published var searchError: String? = nil

    // Audio Player State
    @Published var queue: [MusicTrack] = []
    @Published var currentIndex: Int = -1
    @Published var currentTrack: MusicTrack? = nil
    @Published var lyrics: [LrcLine] = []
    @Published var isPlaying: Bool = false
    @Published var isResolving: Bool = false
    @Published var positionMs: Int64 = 0
    @Published var durationMs: Int64 = 0
    @Published var isImporting: Bool = false
    @Published var toastMessage: String? = nil

    private var player: AVPlayer?
    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?
    private var failObserver: NSObjectProtocol?
    private var itemStatusObserver: NSKeyValueObservation?
    private var triedSourceIds: Set<String> = []
    private var consecutiveAutoSkipCount: Int = 0
    private let encoder: JSONEncoder = { let e = JSONEncoder(); e.outputFormatting = [.prettyPrinted]; return e }()
    private let decoder = JSONDecoder()
    private var docsDir: URL { FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0] }

    init() {
        loadAll()
        Task { await loadDiscover(autoFallback: true) }
    }

    var enabledSources: [MusicSource] { sources.filter(\.enabled) }
    var activeSource: MusicSource? { sources.first { $0.id == activeSourceId } ?? enabledSources.first }

    func showToast(_ msg: String) {
        toastMessage = msg
        Task {
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            if toastMessage == msg { toastMessage = nil }
        }
    }

    func selectSource(_ id: String) {
        activeSourceId = id
        selectedChartIndex = 0
        Task { await loadDiscover(autoFallback: false) }
    }

    func selectChart(_ idx: Int) {
        selectedChartIndex = idx
        Task { await loadDiscover(autoFallback: false) }
    }

    func loadDiscover(autoFallback: Bool = false) async {
        guard let src = activeSource, !src.charts.isEmpty else {
            discoverError = "请先在「音乐源」页启用开源音乐源"
            return
        }
        let idx = min(max(0, selectedChartIndex), src.charts.count - 1)
        isLoadingDiscover = true
        discoverError = nil
        do {
            let list = try await MusicSourceEngine.shared.fetchChart(source: src, chart: src.charts[idx])
            discoverTracks = list
            isLoadingDiscover = false
            if list.isEmpty { discoverError = "暂无曲目" }
        } catch {
            if autoFallback {
                for alt in enabledSources where alt.id != src.id && !alt.charts.isEmpty {
                    if let list = try? await MusicSourceEngine.shared.fetchChart(source: alt, chart: alt.charts[0]), !list.isEmpty {
                        activeSourceId = alt.id
                        selectedChartIndex = 0
                        discoverTracks = list
                        isLoadingDiscover = false
                        return
                    }
                }
            }
            isLoadingDiscover = false
            discoverError = "加载失败：\(error.localizedDescription)"
        }
    }

    func executeSearch() {
        let q = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return }
        let targets = Array(enabledSources.filter(\.searchable).prefix(6))
        Task {
            isSearching = true
            searchResults = []
            searchError = nil
            var acc: [MusicTrack] = []
            await withTaskGroup(of: [MusicTrack].self) { group in
                for src in targets {
                    group.addTask { (try? await MusicSourceEngine.shared.search(source: src, keyword: q)) ?? [] }
                }
                for await batch in group {
                    if !batch.isEmpty {
                        acc.append(contentsOf: batch)
                        self.searchResults = acc
                    }
                }
            }
            isSearching = false
            if searchResults.isEmpty { searchError = "未找到「\(q)」相关歌曲" }
        }
    }

    // MARK: - AVPlayer Audio Engine

    func playFromList(_ list: [MusicTrack], index: Int) {
        guard !list.isEmpty, index >= 0, index < list.count else { return }
        queue = list
        Task { await playIndex(index, resetFailover: true, isAutoAdvance: false) }
    }

    func playIndex(_ index: Int, resetFailover: Bool = true, isAutoAdvance: Bool = false) async {
        guard index >= 0, index < queue.count else { return }
        let rawTrack = queue[index]
        guard let src = sources.first(where: { $0.id == rawTrack.sourceId }) ?? activeSource else { return }
        if resetFailover { triedSourceIds.removeAll() }
        if !isAutoAdvance { consecutiveAutoSkipCount = 0 }
        triedSourceIds.insert(src.id)

        currentIndex = index
        currentTrack = rawTrack
        isResolving = true
        let resolved = await MusicSourceEngine.shared.resolvePlayableTrack(source: src, track: rawTrack)
        isResolving = false
        guard !resolved.streamUrl.isEmpty, URL(string: resolved.streamUrl) != nil else {
            await autoSwitchSameSongOrAdvance(index: index, failedTrack: rawTrack)
            return
        }
        startAvPlayback(index: index, resolved: resolved)
    }

    private func startAvPlayback(index: Int, resolved: MusicTrack) {
        guard let url = URL(string: resolved.streamUrl) else { return }
        if index >= 0 && index < queue.count { queue[index] = resolved }
        currentIndex = index
        currentTrack = resolved
        lyrics = MusicSourceEngine.parseLrc(resolved.rawLyric)

        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        try? AVAudioSession.sharedInstance().setActive(true)
        cleanupObservers()

        let host = url.host?.lowercased() ?? ""
        let item: AVPlayerItem
        if host.contains("bilivideo") || host.contains("hdslb") || host.contains("akamaized") || host.contains("bilibili") {
            let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": MusicSourceEngine.bilibiliHeaders])
            item = AVPlayerItem(asset: asset)
        } else {
            item = AVPlayerItem(url: url)
        }
        let p = AVPlayer(playerItem: item)
        player = p

        endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            Task { @MainActor in
                guard let self, !self.queue.isEmpty else { return }
                let nextIdx = (self.currentIndex + 1) % self.queue.count
                await self.playIndex(nextIdx, resetFailover: true, isAutoAdvance: true)
            }
        }
        failObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            Task { @MainActor in
                guard let self, let cur = self.currentTrack else { return }
                await self.autoSwitchSameSongOrAdvance(index: self.currentIndex, failedTrack: cur)
            }
        }
        itemStatusObserver = item.observe(\.status, options: [.new]) { [weak self] observedItem, _ in
            Task { @MainActor in
                guard let self else { return }
                if observedItem.status == .readyToPlay {
                    let durSec = observedItem.duration.seconds
                    if let cur = self.currentTrack, !cur.isLiveRadio, durSec.isFinite, durSec > 1, durSec <= 65 {
                        self.player?.pause()
                        self.showToast("检测到 \(Int(durSec))秒 试听片段，自动切换完整版音源...")
                        await self.autoSwitchSameSongOrAdvance(index: self.currentIndex, failedTrack: cur)
                    } else {
                        self.consecutiveAutoSkipCount = 0
                    }
                } else if observedItem.status == .failed, let cur = self.currentTrack {
                    await self.autoSwitchSameSongOrAdvance(index: self.currentIndex, failedTrack: cur)
                }
            }
        }

        timeObserver = p.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.4, preferredTimescale: 600), queue: .main) { [weak self] time in
            Task { @MainActor in
                guard let self else { return }
                self.positionMs = Int64(max(0, time.seconds) * 1000)
                if let dur = p.currentItem?.duration.seconds, dur.isFinite, dur > 0 {
                    self.durationMs = Int64(dur * 1000)
                }
            }
        }
        p.play()
        isPlaying = true
    }

    private func autoSwitchSameSongOrAdvance(index: Int, failedTrack: MusicTrack) async {
        let remaining = enabledSources.filter {
            $0.searchable && !triedSourceIds.contains($0.id) && $0.sourceType != .RADIO_BROWSER && $0.sourceType != .M3U_AUDIO
        }
        for altSrc in remaining {
            triedSourceIds.insert(altSrc.id)
            let q = "\(failedTrack.title) \(failedTrack.artist.components(separatedBy: "/").first ?? "")"
            let found = ((try? await MusicSourceEngine.shared.search(source: altSrc, keyword: q)) ?? [])
            let list = found.isEmpty ? ((try? await MusicSourceEngine.shared.search(source: altSrc, keyword: failedTrack.title)) ?? []) : found
            if let cand = list.first(where: { $0.title.localizedCaseInsensitiveContains(failedTrack.title) || failedTrack.title.localizedCaseInsensitiveContains($0.title) }) {
                let resolved = await MusicSourceEngine.shared.resolvePlayableTrack(source: altSrc, track: cand)
                if !resolved.streamUrl.isEmpty, URL(string: resolved.streamUrl) != nil {
                    showToast("音源异常，已自动切换「\(altSrc.name.components(separatedBy: " ").first ?? altSrc.name)」播放")
                    startAvPlayback(index: index, resolved: resolved)
                    return
                }
            }
        }
        let maxSkips = min(max(0, queue.count - 1), 5)
        if queue.count > 1 && consecutiveAutoSkipCount < maxSkips {
            consecutiveAutoSkipCount += 1
            showToast("《\(failedTrack.title)》暂无可用音源，自动播放下一首")
            await playIndex((index + 1) % queue.count, resetFailover: true, isAutoAdvance: true)
        } else {
            isPlaying = false
            showToast("所有可用音源均无法播放此曲")
        }
    }

    private func cleanupObservers() {
        if let obs = timeObserver { player?.removeTimeObserver(obs); timeObserver = nil }
        if let obs = endObserver { NotificationCenter.default.removeObserver(obs); endObserver = nil }
        if let obs = failObserver { NotificationCenter.default.removeObserver(obs); failObserver = nil }
        itemStatusObserver?.invalidate()
        itemStatusObserver = nil
    }

    func togglePlayPause() {
        guard let p = player else { return }
        if isPlaying { p.pause(); isPlaying = false } else { p.play(); isPlaying = true }
    }

    func pauseAudio() {
        player?.pause()
        isPlaying = false
    }

    func seek(toMs ms: Int64) {
        player?.seek(to: CMTime(seconds: Double(ms) / 1000.0, preferredTimescale: 600))
        positionMs = ms
    }

    func nextTrack() {
        guard !queue.isEmpty else { return }
        Task { await playIndex((currentIndex + 1) % queue.count, resetFailover: true, isAutoAdvance: false) }
    }

    func prevTrack() {
        guard !queue.isEmpty else { return }
        let idx = currentIndex <= 0 ? queue.count - 1 : currentIndex - 1
        Task { await playIndex(idx, resetFailover: true, isAutoAdvance: false) }
    }

    func isFavorite(_ track: MusicTrack) -> Bool {
        favorites.contains { $0.uniqueKey == track.uniqueKey }
    }

    func toggleFavorite(_ track: MusicTrack) {
        if isFavorite(track) {
            favorites.removeAll { $0.uniqueKey == track.uniqueKey }
            showToast("已取消喜欢")
        } else {
            favorites.insert(track, at: 0)
            showToast("已加入我喜欢的音乐")
        }
        persist(favorites, file: "music_favorites.json")
    }

    func importUrl(_ url: String) {
        Task {
            isImporting = true
            do {
                let res = try await MusicSourceEngine.shared.importFromUrl(url)
                let existing = Set(sources.map(\.name))
                sources.append(contentsOf: res.sources.filter { !existing.contains($0.name) })
                persist(sources, file: "music_sources.json")
                showToast("导入成功：解析 \(res.sources.count) 个可用音乐源")
            } catch {
                showToast("拉取失败：\(error.localizedDescription)")
            }
            isImporting = false
        }
    }

    func upsertSource(_ src: MusicSource) {
        if let idx = sources.firstIndex(where: { $0.id == src.id }) { sources[idx] = src }
        else { sources.insert(src, at: 0) }
        persist(sources, file: "music_sources.json")
    }

    func deleteSource(_ id: String) {
        sources.removeAll { $0.id == id }
        persist(sources, file: "music_sources.json")
    }

    private func loadAll() {
        let defaults = Self.defaultSources()
        if let saved: [MusicSource] = read("music_sources.json") {
            let stripped = saved.filter { $0.id != "fast_bilibili_music" && $0.sourceType != .BILIBILI_MUSIC }
            let needsV171 = stripped.count != saved.count || !stripped.contains(where: { $0.id == "git_gdstudio_joox" }) || !stripped.contains(where: { $0.id == "fast_imeto_meting" })
            let existingIds = Set(stripped.map(\.id))
            let missing = defaults.filter { !existingIds.contains($0.id) }
            if needsV171 || !missing.isEmpty {
                let updated = stripped.map { s -> MusicSource in
                    if s.id == "fast_vkeys_qq" { var c = s; c.enabled = false; return c }
                    return s
                }
                let order = defaults.map(\.id)
                var seen = Set<String>()
                sources = (missing + updated)
                    .filter { seen.insert($0.id).inserted }
                    .sorted { (order.firstIndex(of: $0.id) ?? 999) < (order.firstIndex(of: $1.id) ?? 999) }
                persist(sources, file: "music_sources.json")
            } else {
                sources = stripped
            }
        } else {
            sources = defaults
            persist(sources, file: "music_sources.json")
        }
        activeSourceId = enabledSources.first?.id ?? sources.first?.id ?? ""
        subscriptions = read("music_subs.json") ?? Self.defaultSubs()
        favorites = read("music_favorites.json") ?? []
        playlists = read("music_playlists.json") ?? []
    }

    private func read<T: Decodable>(_ file: String) -> T? {
        guard let data = try? Data(contentsOf: docsDir.appendingPathComponent(file)) else { return nil }
        return try? decoder.decode(T.self, from: data)
    }

    private func persist<T: Encodable>(_ value: T, file: String) {
        if let data = try? encoder.encode(value) { try? data.write(to: docsDir.appendingPathComponent(file), options: .atomic) }
    }

    static func defaultSources() -> [MusicSource] {
        [
            MusicSource(id: "fast_qijieya_meting", name: "奇杰雅云音乐 320K (默认推荐·全曲)", baseUrl: "https://api.qijieya.cn/meting/", sourceType: .METING_API, platform: "netease", group: "320K 完整全曲 (国内首选)", charts: [MusicChartCategory(title: "热歌榜", idOrQuery: "3778678"), MusicChartCategory(title: "飙升榜", idOrQuery: "19723756"), MusicChartCategory(title: "新歌榜", idOrQuery: "3779629")]),
            MusicSource(id: "fast_kuwo_direct", name: "酷我官方高速直连 (搜歌秒播·全曲)", baseUrl: "http://kbangserver.kuwo.cn", sourceType: .KUWO_DIRECT, platform: "kuwo", group: "320K 完整全曲 (国内首选)", charts: [MusicChartCategory(title: "酷我热歌榜", idOrQuery: "16"), MusicChartCategory(title: "酷我飙升榜", idOrQuery: "93"), MusicChartCategory(title: "酷我新歌榜", idOrQuery: "17")]),
            MusicSource(id: "fast_imeto_meting", name: "i-Meto 云音乐 320K (高速完整版)", baseUrl: "https://api.i-meto.com/meting/api", sourceType: .METING_API, platform: "netease", group: "320K 完整全曲 (国内首选)", charts: [MusicChartCategory(title: "热歌榜", idOrQuery: "3778678"), MusicChartCategory(title: "飙升榜", idOrQuery: "19723756"), MusicChartCategory(title: "新歌榜", idOrQuery: "3779629")]),
            MusicSource(id: "git_gdstudio_joox", name: "GD音乐台 · JOOX 320K (港台/周杰伦全曲)", baseUrl: "https://music-api.gdstudio.xyz/api.php", sourceType: .GDSTUDIO_API, platform: "joox", group: "320K 完整全曲 (国内首选)", charts: [MusicChartCategory(title: "周杰伦金曲", idOrQuery: "周杰伦"), MusicChartCategory(title: "陈奕迅经典", idOrQuery: "陈奕迅"), MusicChartCategory(title: "华语热歌", idOrQuery: "热门")]),
            MusicSource(id: "git_gdstudio_netease", name: "GD音乐台 · 网易云备用", baseUrl: "https://music-api.gdstudio.xyz/api.php", sourceType: .GDSTUDIO_API, platform: "netease", group: "开源聚合曲库 (备用)", charts: [MusicChartCategory(title: "华语热歌", idOrQuery: "热门"), MusicChartCategory(title: "治愈轻音", idOrQuery: "轻音乐")]),
            MusicSource(id: "open_audius_global", name: "Audius 全球独立音乐 (开源曲库)", baseUrl: "https://discoveryprovider.audius.co/v1", sourceType: .AUDIUS_API, platform: "global", group: "欧美/电子/独立音乐", charts: [MusicChartCategory(title: "全球热榜", idOrQuery: "trending"), MusicChartCategory(title: "Lo-Fi", idOrQuery: "Lo-Fi")]),
            MusicSource(id: "open_radiobrowser_fm", name: "Radio-Browser 在线音乐电台 (开源 FM)", baseUrl: "https://de1.api.radio-browser.info/json", sourceType: .RADIO_BROWSER, platform: "cn", group: "24h 在线电台", charts: [MusicChartCategory(title: "华语音乐台", idOrQuery: "cn"), MusicChartCategory(title: "全球人气电台", idOrQuery: "top")])
        ]
    }

    static func defaultSubs() -> [MusicSubscription] {
        [
            MusicSubscription(id: "msub_huibq_mf", name: "Huibq MusicFree 聚合源 (CDN)", url: "https://fastly.jsdelivr.net/gh/Huibq/keep-alive/Music_Free/myPlugins.json"),
            MusicSubscription(id: "msub_maotoumao_gitee", name: "MusicFree 官方插件源 (Gitee)", url: "https://gitee.com/maotoumao/MusicFreePlugins/raw/master/plugins.json")
        ]
    }
}
