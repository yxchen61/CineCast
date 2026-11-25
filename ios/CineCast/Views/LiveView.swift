import SwiftUI

struct LiveView: View {
    @EnvironmentObject var store: AppStore
    let onPlayChannel: (String, [String]) -> Void
    let onCastChannel: (String, String) -> Void

    @State private var showAddDialog = false
    @State private var newName = ""
    @State private var newUrl = ""

    private var filteredChannels: [LiveChannel] {
        if store.selectedLiveGroup == "全部" { return store.liveChannels }
        return store.liveChannels.filter { $0.group == store.selectedLiveGroup }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Live Source Switcher
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(store.liveSources) { src in
                            let selected = src.id == store.selectedLiveSourceId
                            Button {
                                Task { await store.loadLiveSource(src, autoFallback: false) }
                            } label: {
                                Label(src.name, systemImage: "tv")
                                    .font(.caption.weight(.medium))
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 7)
                                    .background(selected ? Color.googleBlue : Color(.secondarySystemBackground))
                                    .foregroundColor(selected ? .white : .primary)
                                    .clipShape(Capsule())
                            }
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                }

                // Group Filter Chips
                if store.liveGroups.count > 1 {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(store.liveGroups, id: \.self) { grp in
                                Button(grp) { store.selectedLiveGroup = grp }
                                    .font(.caption)
                                    .padding(.horizontal, 11)
                                    .padding(.vertical, 5)
                                    .background(grp == store.selectedLiveGroup ? Color.googleBlueSoft.opacity(0.7) : Color(.secondarySystemBackground))
                                    .foregroundColor(grp == store.selectedLiveGroup ? .googleBlue : .secondary)
                                    .clipShape(Capsule())
                            }
                        }
                        .padding(.horizontal, 14)
                        .padding(.bottom, 8)
                    }
                }

                if store.isLoadingLive {
                    Spacer()
                    ProgressView("正在解析 M3U / TXT 直播频道...")
                    Spacer()
                } else if let err = store.liveError {
                    Spacer()
                    VStack(spacing: 12) {
                        Image(systemName: "tv.slash").font(.largeTitle).foregroundColor(.secondary)
                        Text(err).font(.subheadline).multilineTextAlignment(.center).padding(.horizontal, 24)
                        Button("重试") {
                            if let cur = store.liveSources.first(where: { $0.id == store.selectedLiveSourceId }) ?? store.liveSources.first {
                                Task { await store.loadLiveSource(cur, autoFallback: true) }
                            }
                        }
                        .buttonStyle(.borderedProminent).tint(.googleBlue)
                    }
                    Spacer()
                } else {
                    List(filteredChannels) { ch in
                        HStack(spacing: 12) {
                            Image(systemName: "play.tv.fill")
                                .foregroundColor(.googleBlue)
                                .font(.title3)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(ch.name).font(.subheadline.weight(.medium))
                                Text("\(ch.group) · \(ch.urls.count) 条高清源")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            Spacer()
                            Button { onCastChannel(ch.name, ch.urls.first ?? "") } label: {
                                Image(systemName: "tv").foregroundColor(.secondary)
                            }
                            .buttonStyle(.borderless)
                            Button { onPlayChannel(ch.name, ch.urls) } label: {
                                Image(systemName: "play.circle.fill")
                                    .font(.title2)
                                    .foregroundColor(.googleBlue)
                            }
                            .buttonStyle(.borderless)
                        }
                        .contentShape(Rectangle())
                        .onTapGesture { onPlayChannel(ch.name, ch.urls) }
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("高清直播")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showAddDialog = true } label: { Image(systemName: "plus") }
                }
            }
            .task {
                if store.selectedLiveSourceId.isEmpty, let first = store.liveSources.first {
                    await store.loadLiveSource(first, autoFallback: true)
                }
            }
            .sheet(isPresented: $showAddDialog) {
                NavigationStack {
                    Form {
                        TextField("直播源名称", text: $newName)
                        TextField("M3U / TXT 订阅地址", text: $newUrl)
                            .keyboardType(.URL)
                            .autocorrectionDisabled()
                    }
                    .navigationTitle("添加直播源")
                    .toolbar {
                        ToolbarItem(placement: .topBarLeading) { Button("取消") { showAddDialog = false } }
                        ToolbarItem(placement: .topBarTrailing) {
                            Button("保存") {
                                if !newUrl.isEmpty {
                                    store.addLiveSource(name: newName, url: newUrl)
                                    newName = ""; newUrl = ""
                                    showAddDialog = false
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
