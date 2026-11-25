import SwiftUI
import AVKit
import AVFoundation

struct NativeAVPlayerView: UIViewControllerRepresentable {
    let player: AVPlayer

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        controller.allowsPictureInPicturePlayback = true
        controller.showsPlaybackControls = true
        controller.canStartPictureInPictureAutomaticallyFromInline = true
        return controller
    }

    func updateUIViewController(_ uiViewController: AVPlayerViewController, context: Context) {
        if uiViewController.player !== player {
            uiViewController.player = player
        }
    }
}

struct PlayerView: View {
    @EnvironmentObject var store: AppStore
    @Environment(\.dismiss) private var dismiss

    let session: ActivePlayerSession

    @State private var detail: VodDetail? = nil
    @State private var isLoadingDetail: Bool = false
    @State private var errorMessage: String? = nil

    @State private var playSourceIndex: Int = 0
    @State private var episodeIndex: Int = 0
    @State private var liveLineIndex: Int = 0
    @State private var playbackRate: Float = 1.0
    @State private var showCastSheet: Bool = false

    @State private var player = AVPlayer()
    @State private var timeObserver: Any? = nil
    @State private var endObserver: NSObjectProtocol? = nil

    private var currentPlaySource: VodPlaySource? {
        guard let d = detail, playSourceIndex < d.playSources.count else { return nil }
        return d.playSources[playSourceIndex]
    }

    private var currentEpisode: VodEpisode? {
        guard let ps = currentPlaySource, episodeIndex < ps.episodes.count else { return nil }
        return ps.episodes[episodeIndex]
    }

    private var currentStreamUrl: String {
        if !session.directUrls.isEmpty {
            let idx = min(liveLineIndex, session.directUrls.count - 1)
            return session.directUrls[idx]
        }
        return currentEpisode?.url ?? ""
    }

    private var displayTitle: String {
        if !session.directTitle.isEmpty { return session.directTitle }
        if let d = detail, let ep = currentEpisode { return "\(d.name) · \(ep.name)" }
        return detail?.name ?? "正在加载..."
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Native Hardware-Accelerated AVPlayer (16:9)
                ZStack {
                    Color.black
                    NativeAVPlayerView(player: player)
                    if isLoadingDetail {
                        ProgressView().tint(.white)
                    }
                }
                .aspectRatio(16 / 9, contentMode: .fit)

                // Quick Control Bar (Speed / AirPlay / DLNA Cast / Favorite)
                HStack(spacing: 12) {
                    Menu {
                        ForEach([0.5, 0.75, 1.0, 1.25, 1.5, 2.0], id: \.self) { rate in
                            Button("\(String(format: "%.2gx", rate)) 倍速") {
                                playbackRate = Float(rate)
                                player.rate = playbackRate
                            }
                        }
                    } label: {
                        Label("\(String(format: "%.2gx", playbackRate))", systemImage: "gauge.with.dots.needle.67percent")
                            .font(.subheadline.weight(.medium))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 7)
                            .background(Color(.secondarySystemBackground))
                            .clipShape(Capsule())
                    }

                    Button {
                        showCastSheet = true
                    } label: {
                        Label("投屏", systemImage: "tv")
                            .font(.subheadline.weight(.medium))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 7)
                            .background(Color.googleBlueSoft.opacity(0.6))
                            .foregroundColor(.googleBlue)
                            .clipShape(Capsule())
                    }

                    AirPlayPickerView()
                        .frame(width: 32, height: 32)

                    Spacer()

                    if let d = detail {
                        let fav = store.isFavorite(sourceId: d.sourceId, vodId: d.id)
                        Button {
                            store.toggleFavorite(detail: d)
                        } label: {
                            Label(fav ? "已收藏" : "收藏", systemImage: fav ? "bookmark.fill" : "bookmark")
                                .font(.subheadline.weight(.medium))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 7)
                                .background(Color(.secondarySystemBackground))
                                .foregroundColor(fav ? .googleBlue : .primary)
                                .clipShape(Capsule())
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(Color(.systemBackground))

                Divider()

                // Bottom Scrollable Details / Lines / Episodes
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        if let err = errorMessage {
                            Text(err)
                                .foregroundColor(.red)
                                .font(.subheadline)
                        } else if let d = detail {
                            VStack(alignment: .leading, spacing: 6) {
                                Text(d.name)
                                    .font(.title2.weight(.bold))
                                let meta = [d.year, d.area, d.typeName, d.remarks, d.sourceName]
                                    .filter { !$0.isEmpty }
                                    .joined(separator: " · ")
                                Text(meta)
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }

                            // Play source lines
                            if !d.playSources.isEmpty {
                                VStack(alignment: .leading, spacing: 8) {
                                    Text("播放线路 (\(d.playSources.count))")
                                        .font(.subheadline.weight(.semibold))
                                    ScrollView(.horizontal, showsIndicators: false) {
                                        HStack(spacing: 8) {
                                            ForEach(Array(d.playSources.enumerated()), id: \.offset) { idx, ps in
                                                Button {
                                                    playSourceIndex = idx
                                                    episodeIndex = min(episodeIndex, max(0, ps.episodes.count - 1))
                                                    loadCurrentStream(seekMs: 0)
                                                } label: {
                                                    Text("\(ps.name) (\(ps.episodes.count))")
                                                        .font(.caption.weight(.medium))
                                                        .padding(.horizontal, 12)
                                                        .padding(.vertical, 7)
                                                        .background(idx == playSourceIndex ? Color.googleBlue : Color(.secondarySystemBackground))
                                                        .foregroundColor(idx == playSourceIndex ? .white : .primary)
                                                        .clipShape(Capsule())
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Episodes grid
                            if let ps = currentPlaySource {
                                VStack(alignment: .leading, spacing: 8) {
                                    Text("选集 (\(ps.episodes.count))")
                                        .font(.subheadline.weight(.semibold))
                                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4), spacing: 8) {
                                        ForEach(Array(ps.episodes.enumerated()), id: \.offset) { idx, ep in
                                            Button {
                                                episodeIndex = idx
                                                loadCurrentStream(seekMs: 0)
                                            } label: {
                                                Text(ep.name)
                                                    .font(.caption.weight(.medium))
                                                    .lineLimit(1)
                                                    .frame(maxWidth: .infinity)
                                                    .padding(.vertical, 9)
                                                    .padding(.horizontal, 4)
                                                    .background(idx == episodeIndex ? Color.googleBlue : Color(.secondarySystemBackground))
                                                    .foregroundColor(idx == episodeIndex ? .white : .primary)
                                                    .clipShape(RoundedRectangle(cornerRadius: 10))
                                            }
                                        }
                                    }
                                }
                            }

                            // Synopsis
                            if !d.content.isEmpty || !d.actor.isEmpty {
                                VStack(alignment: .leading, spacing: 6) {
                                    Text("影片简介").font(.subheadline.weight(.semibold))
                                    if !d.director.isEmpty {
                                        Text("导演：\(d.director)").font(.caption).foregroundColor(.secondary)
                                    }
                                    if !d.actor.isEmpty {
                                        Text("主演：\(d.actor)").font(.caption).foregroundColor(.secondary)
                                    }
                                    if !d.content.isEmpty {
                                        Text(d.content).font(.subheadline).foregroundColor(.primary)
                                    }
                                }
                                .padding(14)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .background(Color(.secondarySystemBackground))
                                .clipShape(RoundedRectangle(cornerRadius: 16))
                            }
                        } else {
                            // Live TV / Direct Stream Info + Multi-Line Switcher
                            VStack(alignment: .leading, spacing: 12) {
                                Text(displayTitle).font(.title3.weight(.bold))
                                Text(session.isLive ? "实时高清直播流" : "直链高清视频播放")
                                    .font(.subheadline)
                                    .foregroundColor(.secondary)

                                if session.directUrls.count > 1 {
                                    Text("切换直播线路 (\(session.directUrls.count) 条可用)")
                                        .font(.subheadline.weight(.semibold))
                                    ScrollView(.horizontal, showsIndicators: false) {
                                        HStack(spacing: 8) {
                                            ForEach(0..<session.directUrls.count, id: \.self) { idx in
                                                Button {
                                                    liveLineIndex = idx
                                                    loadCurrentStream(seekMs: 0)
                                                } label: {
                                                    Text("线路 \(idx + 1)")
                                                        .font(.caption.weight(.medium))
                                                        .padding(.horizontal, 14)
                                                        .padding(.vertical, 7)
                                                        .background(idx == liveLineIndex ? Color.googleBlue : Color(.secondarySystemBackground))
                                                        .foregroundColor(idx == liveLineIndex ? .white : .primary)
                                                        .clipShape(Capsule())
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    .padding(16)
                }
            }
            .navigationTitle(displayTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { dismiss() } label: {
                        Image(systemName: "chevron.down")
                    }
                }
            }
            .sheet(isPresented: $showCastSheet) {
                CastSheetView(mediaTitle: displayTitle, mediaUrl: currentStreamUrl)
            }
            .task {
                configureAudioSession()
                await loadInitialContent()
            }
            .onDisappear {
                saveProgress()
                cleanupPlayer()
            }
        }
    }

    private func configureAudioSession() {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
    }

    private func loadInitialContent() async {
        if !session.directUrls.isEmpty {
            loadCurrentStream(seekMs: 0)
            return
        }
        guard let srcId = session.sourceId, let vodId = session.vodId,
              let src = store.sources.first(where: { $0.id == srcId }) ?? store.activeSource else {
            errorMessage = "找不到对应视频源"
            return
        }
        isLoadingDetail = true
        let saved = store.historyItem(sourceId: src.id, vodId: vodId)
        do {
            let fetched = try await VodSourceEngine.shared.fetchDetail(source: src, vodId: vodId)
            detail = fetched
            playSourceIndex = min(saved?.playSourceIndex ?? 0, max(0, fetched.playSources.count - 1))
            let epCount = fetched.playSources.indices.contains(playSourceIndex) ? fetched.playSources[playSourceIndex].episodes.count : 0
            episodeIndex = min(saved?.episodeIndex ?? 0, max(0, epCount - 1))
            isLoadingDetail = false
            loadCurrentStream(seekMs: saved?.positionMs ?? 0)
        } catch {
            isLoadingDetail = false
            errorMessage = "获取影片详情失败：\(error.localizedDescription)"
        }
    }

    private func loadCurrentStream(seekMs: Int64) {
        let urlStr = currentStreamUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: urlStr) else { return }
        let asset = AVURLAsset(url: url, options: [
            "AVURLAssetHTTPHeaderFieldsKey": ["User-Agent": NetworkService.defaultUA]
        ])
        let item = AVPlayerItem(asset: asset)
        player.replaceCurrentItem(with: item)
        if seekMs > 3000 && !session.isLive {
            player.seek(to: CMTime(value: seekMs, timescale: 1000))
        }
        player.play()
        player.rate = playbackRate

        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { _ in
            if store.settings.autoNextEpisode, let ps = currentPlaySource, episodeIndex + 1 < ps.episodes.count {
                episodeIndex += 1
                loadCurrentStream(seekMs: 0)
            }
        }

        if timeObserver == nil {
            timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 8, preferredTimescale: 1), queue: .main) { _ in
                saveProgress()
            }
        }
    }

    private func saveProgress() {
        guard let d = detail else { return }
        let curSec = CMTimeGetSeconds(player.currentTime())
        let durSec = CMTimeGetSeconds(player.currentItem?.duration ?? .zero)
        guard curSec.isFinite && curSec > 2 else { return }
        let posMs = Int64(curSec * 1000)
        let durMs = durSec.isFinite && durSec > 0 ? Int64(durSec * 1000) : 0
        store.saveHistory(WatchHistoryItem(
            vodId: d.id,
            sourceId: d.sourceId,
            sourceName: d.sourceName,
            name: d.name,
            pic: d.pic,
            episodeIndex: episodeIndex,
            episodeName: currentEpisode?.name ?? "正片",
            playSourceIndex: playSourceIndex,
            positionMs: posMs,
            durationMs: durMs
        ))
    }

    private func cleanupPlayer() {
        if let obs = timeObserver { player.removeTimeObserver(obs); timeObserver = nil }
        if let end = endObserver { NotificationCenter.default.removeObserver(end); endObserver = nil }
        player.pause()
        player.replaceCurrentItem(with: nil)
    }
}
