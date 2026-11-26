import SwiftUI

private struct WebSnifferTarget: Identifiable {
    let id = UUID()
    let url: String
    let title: String
}

private struct CastTarget: Identifiable {
    let id = UUID()
    let title: String
    let url: String
}

@main
struct CineCastApp: App {
    @StateObject private var store = AppStore()
    @StateObject private var novelStore = NovelStore()
    @StateObject private var musicStore = MusicStore()
    @State private var activePlayer: ActivePlayerSession? = nil
    @State private var showNovelReader: Bool = false
    @State private var showMusicPlayer: Bool = false
    @State private var showVideoSources: Bool = false
    @State private var webTarget: WebSnifferTarget? = nil
    @State private var castTarget: CastTarget? = nil

    var body: some Scene {
        WindowGroup {
            ZStack(alignment: .bottom) {
                TabView {
                    HomeView(
                        onOpenVod: { srcId, vodId in
                            musicStore.pauseAudio()
                            activePlayer = ActivePlayerSession(sourceId: srcId, vodId: vodId, directTitle: "", directUrls: [], isLive: false)
                        },
                        onOpenWebSniffer: { url, title in
                            webTarget = WebSnifferTarget(url: url, title: title)
                        },
                        onOpenCast: {
                            castTarget = CastTarget(title: "", url: "")
                        }
                    )
                    .tabItem { Label("影视", systemImage: "safari.fill") }

                    LiveView(
                        onPlayChannel: { title, urls in
                            musicStore.pauseAudio()
                            activePlayer = ActivePlayerSession(sourceId: nil, vodId: nil, directTitle: title, directUrls: urls, isLive: true)
                        },
                        onCastChannel: { title, url in
                            castTarget = CastTarget(title: title, url: url)
                        }
                    )
                    .tabItem { Label("直播", systemImage: "play.tv.fill") }

                    NovelHubView(
                        onOpenReader: { showNovelReader = true }
                    )
                    .environmentObject(novelStore)
                    .tabItem { Label("阅读", systemImage: "book.fill") }

                    MusicHubView(
                        onOpenPlayer: { showMusicPlayer = true }
                    )
                    .environmentObject(musicStore)
                    .tabItem { Label("音乐", systemImage: "music.note.list") }

                    LibraryView(
                        onOpenVod: { srcId, vodId in
                            musicStore.pauseAudio()
                            activePlayer = ActivePlayerSession(sourceId: srcId, vodId: vodId, directTitle: "", directUrls: [], isLive: false)
                        },
                        onOpenVideoSources: { showVideoSources = true }
                    )
                    .tabItem { Label("我的", systemImage: "person.fill") }
                }
                .tint(.googleBlue)
                .environmentObject(store)
                .preferredColorScheme(store.settings.themeMode.colorScheme)

                if let msg = store.toastMessage ?? novelStore.toastMessage ?? musicStore.toastMessage {
                    Text(msg)
                        .font(.caption.weight(.medium))
                        .foregroundColor(.white)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(Color.black.opacity(0.82))
                        .clipShape(Capsule())
                        .padding(.bottom, 64)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                        .animation(.easeInOut, value: msg)
                }
            }
            .fullScreenCover(item: $activePlayer) { session in
                PlayerView(session: session)
                    .environmentObject(store)
            }
            .fullScreenCover(isPresented: $showNovelReader) {
                NovelReaderView()
                    .environmentObject(novelStore)
            }
            .fullScreenCover(isPresented: $showMusicPlayer) {
                MusicPlayerView()
                    .environmentObject(musicStore)
            }
            .sheet(isPresented: $showVideoSources) {
                SourcesView(
                    onOpenWebSniffer: { url, title in
                        showVideoSources = false
                        webTarget = WebSnifferTarget(url: url, title: title)
                    }
                )
                .environmentObject(store)
            }
            .sheet(item: $webTarget) { target in
                WebSnifferView(
                    initialUrl: target.url,
                    initialTitle: target.title,
                    onPlaySniffed: { title, url in
                        activePlayer = ActivePlayerSession(sourceId: nil, vodId: nil, directTitle: title, directUrls: [url], isLive: false)
                    }
                )
                .environmentObject(store)
            }
            .sheet(item: $castTarget) { target in
                CastSheetView(mediaTitle: target.title, mediaUrl: target.url)
            }
        }
    }
}
