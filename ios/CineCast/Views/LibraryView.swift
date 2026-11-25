import SwiftUI

struct LibraryView: View {
    @EnvironmentObject var store: AppStore
    let onOpenVod: (String, String) -> Void

    @State private var showRestoreSheet = false
    @State private var restoreText = ""

    var body: some View {
        NavigationStack {
            List {
                // Watch History
                Section {
                    if store.history.isEmpty {
                        Text("暂无观看历史，开始播放后自动记录进度支持断点续播")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    } else {
                        ForEach(store.history.prefix(20)) { item in
                            Button {
                                onOpenVod(item.sourceId, item.vodId)
                            } label: {
                                VStack(alignment: .leading, spacing: 5) {
                                    HStack {
                                        Text(item.name).font(.subheadline.weight(.medium)).foregroundColor(.primary)
                                        Spacer()
                                        Text("\(item.episodeName.isEmpty ? "正片" : item.episodeName) · \(TimeFormatter.formatMs(item.positionMs))")
                                            .font(.caption)
                                            .foregroundColor(.googleBlue)
                                    }
                                    if item.durationMs > 0 {
                                        ProgressView(value: Double(item.positionMs), total: Double(item.durationMs))
                                            .tint(.googleBlue)
                                    }
                                }
                                .padding(.vertical, 2)
                            }
                        }
                    }
                } header: {
                    HStack {
                        Text("继续观看 (\(store.history.count))")
                        Spacer()
                        if !store.history.isEmpty {
                            Button("清空") { store.clearHistory() }.font(.caption)
                        }
                    }
                }

                // Favorites
                Section("我的收藏 (\(store.favorites.count))") {
                    if store.favorites.isEmpty {
                        Text("暂无收藏影片，在播放页点击「收藏」即可加入")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    } else {
                        ForEach(store.favorites) { fav in
                            Button {
                                onOpenVod(fav.sourceId, fav.vodId)
                            } label: {
                                HStack {
                                    Image(systemName: "bookmark.fill").foregroundColor(.googleBlue)
                                    VStack(alignment: .leading) {
                                        Text(fav.name).foregroundColor(.primary)
                                        Text("\(fav.sourceName) \(fav.remarks)").font(.caption).foregroundColor(.secondary)
                                    }
                                }
                            }
                        }
                    }
                }

                // Appearance & Player Settings
                Section("外观与播放设置") {
                    Picker("主题模式", selection: Binding(
                        get: { store.settings.themeMode },
                        set: { mode in store.updateSettings { $0.themeMode = mode } }
                    )) {
                        ForEach(ThemeMode.allCases) { m in Text(m.label).tag(m) }
                    }

                    Toggle("自动连播下一集", isOn: Binding(
                        get: { store.settings.autoNextEpisode },
                        set: { val in store.updateSettings { $0.autoNextEpisode = val } }
                    ))
                }

                // Cross-Platform Backup & Restore (Compatible with Android CineCast!)
                Section("配置备份与恢复 (兼容安卓版配置互导)") {
                    Button {
                        UIPasteboard.general.string = store.exportBackupJson()
                        store.showToast("完整配置 JSON 已复制到剪贴板")
                    } label: {
                        Label("复制导出完整配置", systemImage: "square.and.arrow.up")
                    }

                    Button {
                        restoreText = UIPasteboard.general.string ?? ""
                        showRestoreSheet = true
                    } label: {
                        Label("粘贴恢复配置 JSON", systemImage: "square.and.arrow.down")
                    }
                }

                Section {
                    Text("极光视界 CineCast iOS v1.1.1 · SwiftUI · AVPlayer · AirPlay & DLNA")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
            }
            .navigationTitle("我的媒体库")
            .navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $showRestoreSheet) {
                NavigationStack {
                    TextEditor(text: $restoreText).padding()
                        .navigationTitle("粘贴备份 JSON")
                        .toolbar {
                            ToolbarItem(placement: .topBarLeading) { Button("取消") { showRestoreSheet = false } }
                            ToolbarItem(placement: .topBarTrailing) {
                                Button("恢复") {
                                    store.importBackupJson(restoreText)
                                    showRestoreSheet = false
                                }
                            }
                        }
                }
            }
        }
    }
}
