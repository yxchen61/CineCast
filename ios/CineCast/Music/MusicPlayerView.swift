import SwiftUI

struct MusicPlayerView: View {
    @EnvironmentObject var musicStore: MusicStore
    @Environment(\.dismiss) private var dismiss
    @State private var showLyrics = false

    var body: some View {
        VStack(spacing: 16) {
            HStack {
                Button { dismiss() } label: { Image(systemName: "chevron.down").font(.title3) }
                Spacer()
                VStack {
                    Text("正在播放").font(.caption).foregroundColor(.secondary)
                    Text(musicStore.currentTrack?.sourceName ?? "极光音乐").font(.caption2).foregroundColor(.googleBlue)
                }
                Spacer()
                Button(showLyrics ? "封面" : "歌词") { showLyrics.toggle() }
                    .font(.subheadline.weight(.medium)).foregroundColor(.googleBlue)
            }
            .padding(.horizontal)

            Spacer()

            if showLyrics {
                if musicStore.lyrics.isEmpty {
                    Text("暂无同步歌词").foregroundColor(.secondary)
                } else {
                    ScrollView {
                        VStack(spacing: 14) {
                            ForEach(musicStore.lyrics) { line in
                                Text(line.text)
                                    .font(.body)
                                    .multilineTextAlignment(.center)
                                    .foregroundColor(abs(line.timeMs - musicStore.positionMs) < 3000 ? .googleBlue : .secondary)
                            }
                        }.padding()
                    }
                }
            } else {
                ZStack {
                    RoundedRectangle(cornerRadius: 24).fill(Color.googleBlue.opacity(0.85))
                    Image(systemName: "music.note").font(.system(size: 64)).foregroundColor(.white)
                    if let s = musicStore.currentTrack?.coverUrl, let u = URL(string: s), s.hasPrefix("http") {
                        AsyncImage(url: u) { img in img.resizable().scaledToFill() } placeholder: { EmptyView() }
                    }
                }
                .frame(width: 260, height: 260)
                .clipShape(RoundedRectangle(cornerRadius: 24))
                .shadow(radius: 12)
            }

            Spacer()

            VStack(spacing: 6) {
                Text(musicStore.currentTrack?.title ?? "未播放").font(.title3.weight(.bold)).lineLimit(1)
                Text(musicStore.currentTrack?.artist ?? "").font(.subheadline).foregroundColor(.secondary).lineLimit(1)
            }
            .padding(.horizontal)

            if musicStore.durationMs > 0 {
                Slider(
                    value: Binding(
                        get: { Double(musicStore.positionMs) },
                        set: { musicStore.seek(toMs: Int64($0)) }
                    ),
                    in: 0...Double(max(1, musicStore.durationMs))
                )
                .tint(.googleBlue)
                .padding(.horizontal)
            }

            HStack(spacing: 36) {
                Button { musicStore.prevTrack() } label: { Image(systemName: "backward.fill").font(.title) }
                Button { musicStore.togglePlayPause() } label: {
                    Image(systemName: musicStore.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                        .font(.system(size: 64))
                        .foregroundColor(.googleBlue)
                }
                Button { musicStore.nextTrack() } label: { Image(systemName: "forward.fill").font(.title) }
            }
            .padding(.bottom, 24)
        }
        .padding(.top, 12)
    }
}
