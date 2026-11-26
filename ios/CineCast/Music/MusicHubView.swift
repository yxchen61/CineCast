import SwiftUI

struct MusicHubView: View {
    @EnvironmentObject var musicStore: MusicStore
    let onOpenPlayer: () -> Void

    @State private var subTab = 0 // 0=音乐馆 1=歌手 2=我喜欢 3=音乐源
    @State private var selectedArtistCategory: ArtistCategory = .all
    @State private var artistInput = ""
    @State private var isSearchMode = false
    @State private var showSourcePicker = false
    @State private var subUrlInput = "https://fastly.jsdelivr.net/gh/Huibq/keep-alive/Music_Free/myPlugins.json"

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                VStack(spacing: 10) {
                    if isSearchMode {
                        HStack(spacing: 8) {
                            Button { isSearchMode = false; musicStore.searchQuery = ""; musicStore.searchResults = [] } label: {
                                Image(systemName: "chevron.left").font(.body.weight(.semibold))
                            }
                            TextField("搜歌曲、歌手、电台...", text: $musicStore.searchQuery)
                                .textFieldStyle(.plain)
                                .submitLabel(.search)
                                .onSubmit { musicStore.executeSearch() }
                            Button("搜歌") { musicStore.executeSearch() }
                                .font(.subheadline.weight(.semibold))
                                .foregroundColor(.googleBlue)
                        }
                        .padding(.horizontal, 14).padding(.vertical, 10)
                        .background(Color(.secondarySystemBackground))
                        .clipShape(Capsule())
                    } else {
                        HStack {
                            Label("极光音乐", systemImage: "waveform")
                                .font(.title3.weight(.semibold))
                            if subTab == 0, let active = musicStore.activeSource {
                                Button { showSourcePicker = true } label: {
                                    HStack(spacing: 4) {
                                        Text(active.name.components(separatedBy: " ").first ?? active.name)
                                            .font(.caption.weight(.semibold)).lineLimit(1)
                                        Image(systemName: "chevron.down").font(.caption2)
                                    }
                                    .padding(.horizontal, 10).padding(.vertical, 5)
                                    .background(Color.googleBlueSoft.opacity(0.65))
                                    .foregroundColor(.googleBlue)
                                    .clipShape(Capsule())
                                }
                            }
                            Spacer()
                            Button { isSearchMode = true } label: {
                                Image(systemName: "magnifyingglass")
                                    .padding(8)
                                    .background(Color(.secondarySystemBackground))
                                    .clipShape(Circle())
                            }
                        }

                        Picker("音乐分区", selection: $subTab) {
                            Text("音乐馆").tag(0)
                            Text("歌手").tag(1)
                            Text("喜欢(\(musicStore.favorites.count))").tag(2)
                            Text("音源(\(musicStore.enabledSources.count))").tag(3)
                        }
                        .pickerStyle(.segmented)
                    }
                }
                .padding(.horizontal, 16).padding(.vertical, 10)

                Divider()

                if isSearchMode {
                    List(Array(musicStore.searchResults.enumerated()), id: \.offset) { idx, t in
                        trackRow(t, list: musicStore.searchResults, idx: idx)
                    }.listStyle(.plain)
                } else {
                    switch subTab {
                    case 0: discoverBody
                    case 1: artistsBody
                    case 2: favoritesBody
                    default: sourcesBody
                    }
                }

                // Persistent Mini-Player Bar
                if let current = musicStore.currentTrack {
                    Divider()
                    HStack(spacing: 12) {
                        Button(action: onOpenPlayer) {
                            HStack(spacing: 10) {
                                coverThumb(current.coverUrl)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(current.title).font(.subheadline.weight(.semibold)).foregroundColor(.primary).lineLimit(1)
                                    Text(current.artist).font(.caption2).foregroundColor(.secondary).lineLimit(1)
                                }
                            }
                        }
                        Spacer()
                        Button { musicStore.togglePlayPause() } label: {
                            Image(systemName: musicStore.isPlaying ? "pause.fill" : "play.fill").font(.title3)
                        }
                        Button { musicStore.nextTrack() } label: {
                            Image(systemName: "forward.fill").font(.body)
                        }
                    }
                    .padding(.horizontal, 14).padding(.vertical, 8)
                    .background(Color(.secondarySystemBackground))
                }
            }
            .confirmationDialog("切换音乐源", isPresented: $showSourcePicker) {
                ForEach(musicStore.enabledSources) { src in
                    Button(src.name) { musicStore.selectSource(src.id) }
                }
            }
        }
    }

    private var discoverBody: some View {
        VStack(spacing: 0) {
            if let charts = musicStore.activeSource?.charts, !charts.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(Array(charts.enumerated()), id: \.offset) { idx, c in
                            Button(c.title) { musicStore.selectChart(idx) }
                                .font(.caption.weight(.medium))
                                .padding(.horizontal, 12).padding(.vertical, 6)
                                .background(musicStore.selectedChartIndex == idx ? Color.googleBlue : Color(.secondarySystemBackground))
                                .foregroundColor(musicStore.selectedChartIndex == idx ? .white : .primary)
                                .clipShape(Capsule())
                        }
                    }.padding(.horizontal, 14).padding(.vertical, 8)
                }
            }
            if musicStore.isLoadingDiscover {
                Spacer(); ProgressView("加载榜单..."); Spacer()
            } else {
                List(Array(musicStore.discoverTracks.enumerated()), id: \.offset) { idx, t in
                    trackRow(t, list: musicStore.discoverTracks, idx: idx)
                }.listStyle(.plain)
            }
        }
    }

    private var artistsBody: some View {
        let artists: [MusicArtistProfile] = [
            MusicArtistProfile(name: "周杰伦", category: .cnMale, tag: "华语流行天王"),
            MusicArtistProfile(name: "陈奕迅", category: .cnMale, tag: "粤语/国语金曲"),
            MusicArtistProfile(name: "林俊杰", category: .cnMale, tag: "行走的CD"),
            MusicArtistProfile(name: "薛之谦", category: .cnMale, tag: "深情创作歌手"),
            MusicArtistProfile(name: "周深", category: .cnMale, tag: "天籁国风声线"),
            MusicArtistProfile(name: "毛不易", category: .cnMale, tag: "治愈民谣男声"),
            MusicArtistProfile(name: "邓紫棋", category: .cnFemale, tag: "铁肺创作天后"),
            MusicArtistProfile(name: "王菲", category: .cnFemale, tag: "华语传奇天后"),
            MusicArtistProfile(name: "孙燕姿", category: .cnFemale, tag: "治愈系天后"),
            MusicArtistProfile(name: "张韶涵", category: .cnFemale, tag: "励志清亮高音"),
            MusicArtistProfile(name: "五月天", category: .cnGroup, tag: "华语摇滚天团"),
            MusicArtistProfile(name: "Beyond", category: .cnGroup, tag: "殿堂级粤语乐队"),
            MusicArtistProfile(name: "告五人", category: .cnGroup, tag: "新世代人气乐团"),
            MusicArtistProfile(name: "Taylor Swift", category: .western, tag: "全球流行天后"),
            MusicArtistProfile(name: "Ed Sheeran", category: .western, tag: "英伦唱作人"),
            MusicArtistProfile(name: "Coldplay", category: .western, tag: "英伦摇滚天团"),
            MusicArtistProfile(name: "YOASOBI", category: .jpKr, tag: "日本现象级双人组"),
            MusicArtistProfile(name: "米津玄师", category: .jpKr, tag: "全能创作鬼才"),
            MusicArtistProfile(name: "IU", category: .jpKr, tag: "韩国国民创作女声"),
            MusicArtistProfile(name: "久石让", category: .instrumental, tag: "宫崎骏配乐大师"),
            MusicArtistProfile(name: "坂本龙一", category: .instrumental, tag: "现代钢琴大师"),
            MusicArtistProfile(name: "班得瑞", category: .instrumental, tag: "自然治愈轻音乐")
        ]
        let filtered = artists.filter {
            (selectedArtistCategory == .all || $0.category == selectedArtistCategory) &&
            (artistInput.isEmpty || $0.name.localizedCaseInsensitiveContains(artistInput))
        }
        return VStack(spacing: 0) {
            HStack {
                TextField("输入歌手名精准搜歌...", text: $artistInput)
                    .textFieldStyle(.roundedBorder)
                Button("查歌") {
                    if !artistInput.isEmpty {
                        musicStore.searchQuery = artistInput
                        isSearchMode = true
                        musicStore.executeSearch()
                    }
                }
                .buttonStyle(.borderedProminent).tint(.googleBlue)
            }
            .padding(.horizontal, 14).padding(.vertical, 8)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(ArtistCategory.allCases) { cat in
                        Button(cat.rawValue) { selectedArtistCategory = cat }
                            .font(.caption.weight(.medium))
                            .padding(.horizontal, 12).padding(.vertical, 6)
                            .background(selectedArtistCategory == cat ? Color.googleBlue : Color(.secondarySystemBackground))
                            .foregroundColor(selectedArtistCategory == cat ? .white : .primary)
                            .clipShape(Capsule())
                    }
                }.padding(.horizontal, 14).padding(.vertical, 4)
            }

            List(filtered) { artist in
                Button {
                    musicStore.searchQuery = artist.name
                    isSearchMode = true
                    musicStore.executeSearch()
                } label: {
                    HStack(spacing: 12) {
                        ZStack {
                            Circle().fill(Color.googleBlue.opacity(0.85))
                            Text(String(artist.name.prefix(2))).font(.caption.weight(.bold)).foregroundColor(.white)
                        }
                        .frame(width: 40, height: 40)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(artist.name).font(.subheadline.weight(.semibold)).foregroundColor(.primary)
                            Text(artist.tag).font(.caption2).foregroundColor(.secondary)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").foregroundColor(.secondary)
                    }
                }
            }.listStyle(.plain)
        }
    }

    private var favoritesBody: some View {
        Group {
            if musicStore.favorites.isEmpty {
                VStack { Spacer(); Text("暂无喜欢的音乐").font(.caption).foregroundColor(.secondary); Spacer() }
            } else {
                List(Array(musicStore.favorites.enumerated()), id: \.offset) { idx, t in
                    trackRow(t, list: musicStore.favorites, idx: idx)
                }.listStyle(.plain)
            }
        }
    }

    private var sourcesBody: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 10) {
                    Label("导入 GitHub 开源音乐源 (MusicFree / JSON / M3U)", systemImage: "icloud.and.arrow.down.fill")
                        .font(.subheadline.weight(.semibold)).foregroundColor(.googleBlue)
                    TextField("GitHub 音乐源订阅地址", text: $subUrlInput)
                        .textFieldStyle(.roundedBorder).keyboardType(.URL).autocorrectionDisabled()
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(musicStore.subscriptions) { sub in
                                Button(sub.name) { subUrlInput = sub.url }
                                    .font(.caption2).padding(.horizontal, 10).padding(.vertical, 5)
                                    .background(Color(.tertiarySystemBackground)).clipShape(Capsule())
                            }
                        }
                    }
                    Button { musicStore.importUrl(subUrlInput) } label: {
                        Text(musicStore.isImporting ? "拉取中..." : "从 Git 拉取导入").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent).tint(.googleBlue)
                }.padding(.vertical, 4)
            }

            Section("音乐源列表 (\(musicStore.sources.count))") {
                ForEach(musicStore.sources) { src in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(src.name).font(.subheadline.weight(.semibold)).lineLimit(1)
                            Text("\(src.sourceType.badge) · \(src.baseUrl)").font(.caption2).foregroundColor(.secondary).lineLimit(1)
                        }
                        Spacer()
                        Toggle("", isOn: Binding(get: { src.enabled }, set: { v in var u = src; u.enabled = v; musicStore.upsertSource(u) })).labelsHidden()
                    }
                }
            }
        }
    }

    private func trackRow(_ t: MusicTrack, list: [MusicTrack], idx: Int) -> some View {
        HStack(spacing: 12) {
            Button {
                musicStore.playFromList(list, index: idx)
                onOpenPlayer()
            } label: {
                HStack(spacing: 12) {
                    coverThumb(t.coverUrl)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(t.title).font(.subheadline.weight(.semibold)).foregroundColor(.primary).lineLimit(1)
                        Text(t.artist).font(.caption).foregroundColor(.secondary).lineLimit(1)
                    }
                }
            }
            Spacer()
            Button { musicStore.toggleFavorite(t) } label: {
                Image(systemName: musicStore.isFavorite(t) ? "heart.fill" : "heart")
                    .foregroundColor(musicStore.isFavorite(t) ? .red : .secondary)
            }.buttonStyle(.borderless)
        }
    }

    private func coverThumb(_ urlStr: String) -> some View {
        ZStack {
            RoundedRectangle(cornerRadius: 10).fill(Color.googleBlue.opacity(0.85))
            Image(systemName: "music.note").foregroundColor(.white)
            if let u = URL(string: urlStr), urlStr.hasPrefix("http") {
                AsyncImage(url: u) { img in img.resizable().scaledToFill() } placeholder: { EmptyView() }
            }
        }
        .frame(width: 44, height: 44)
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }
}
