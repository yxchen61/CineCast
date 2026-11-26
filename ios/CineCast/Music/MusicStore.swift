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
        Task { await playIndex(index) }
    }

    func playIndex(_ index: Int) async {
        guard index >= 0, index < queue.count else { return }
        let rawTrack = queue[index]
        guard let src = sources.first(where: { $0.id == rawTrack.sourceId }) ?? activeSource else { return }
        currentIndex = index
        currentTrack = rawTrack
        isResolving = true
        let resolved = await MusicSourceEngine.shared.resolvePlayableTrack(source: src, track: rawTrack)
        isResolving = false
        queue[index] = resolved
        currentTrack = resolved
        lyrics = MusicSourceEngine.parseLrc(resolved.rawLyric)
        guard let url = URL(string: resolved.streamUrl), !resolved.streamUrl.isEmpty else {
            showToast("当前音源暂无此曲音频流")
            return
        }
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        try? AVAudioSession.sharedInstance().setActive(true)
        if let obs = timeObserver { player?.removeTimeObserver(obs); timeObserver = nil }
        let item = AVPlayerItem(url: url)
        let p = AVPlayer(playerItem: item)
        player = p
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
        Task { await playIndex((currentIndex + 1) % queue.count) }
    }

    func prevTrack() {
        guard !queue.isEmpty else { return }
        let idx = currentIndex <= 0 ? queue.count - 1 : currentIndex - 1
        Task { await playIndex(idx) }
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
        sources = read("music_sources.json") ?? Self.defaultSources()
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
            MusicSource(id: "git_meting_injahow", name: "云音乐榜单 (GitHub/Meting-Injahow)", baseUrl: "https://api.injahow.cn/meting/", sourceType: .METING_API, platform: "netease", group: "精选直连 (GitHub 实测)", charts: [MusicChartCategory(title: "热歌榜", idOrQuery: "3778678"), MusicChartCategory(title: "飙升榜", idOrQuery: "19723756"), MusicChartCategory(title: "新歌榜", idOrQuery: "3779629")]),
            MusicSource(id: "git_gdstudio_netease", name: "GD音乐台 · 曲库搜歌 (GitHub/GDStudio)", baseUrl: "https://music-api.gdstudio.xyz/api.php", sourceType: .GDSTUDIO_API, platform: "netease", group: "精选直连 (GitHub 实测)", charts: [MusicChartCategory(title: "华语热歌", idOrQuery: "热门"), MusicChartCategory(title: "经典金曲", idOrQuery: "周杰伦"), MusicChartCategory(title: "治愈轻音", idOrQuery: "轻音乐")]),
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
