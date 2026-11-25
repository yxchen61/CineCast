import SwiftUI

struct SourcesView: View {
    @EnvironmentObject var store: AppStore
    let onOpenWebSniffer: (String, String) -> Void

    @State private var subUrlInput = "https://cyao2q.github.io/files/m.json"
    @State private var filterTab = 0 // 0=全部 1=直连 2=网页嗅探 3=Spider
    @State private var showAddSheet = false
    @State private var showPasteSheet = false
    @State private var pasteJsonText = ""

    @State private var editId: String? = nil
    @State private var editName = ""
    @State private var editUrl = ""
    @State private var editGroup = "自定义源"
    @State private var editType: SourceType = .MACCMS_JSON

    private var filteredSources: [VideoSource] {
        switch filterTab {
        case 1: return store.sources.filter { $0.type.isDirectApi }
        case 2: return store.sources.filter { $0.type == .WEB_SNIFFER }
        case 3: return store.sources.filter { $0.type == .TVBOX_SPIDER }
        default: return store.sources
        }
    }

    var body: some View {
        NavigationStack {
            List {
                // TVBox Subscription Import Card (cyao2q/files compatible)
                Section {
                    VStack(alignment: .leading, spacing: 10) {
                        Label("导入 TVBox 配置订阅 (兼容 cyao2q/files)", systemImage: "icloud.and.arrow.down.fill")
                            .font(.subheadline.weight(.semibold))
                            .foregroundColor(.googleBlue)
                        Text("自动解析配置中的直连影视源 (Type 0/1/4)、直播源 (lives)、VIP 解析接口 (parses) 及广告过滤规则 (ads)。")
                            .font(.caption)
                            .foregroundColor(.secondary)

                        TextField("配置订阅 JSON URL", text: $subUrlInput)
                            .textFieldStyle(.roundedBorder)
                            .keyboardType(.URL)
                            .autocorrectionDisabled()

                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 6) {
                                ForEach(store.subscriptions) { sub in
                                    Button(sub.name) { subUrlInput = sub.url }
                                        .font(.caption2)
                                        .padding(.horizontal, 10)
                                        .padding(.vertical, 5)
                                        .background(Color(.tertiarySystemBackground))
                                        .clipShape(Capsule())
                                }
                            }
                        }

                        HStack(spacing: 10) {
                            Button {
                                store.importTvBoxUrl(subUrlInput)
                            } label: {
                                HStack {
                                    if store.isImportingSub { ProgressView().tint(.white) }
                                    Text(store.isImportingSub ? "正在同步..." : "拉取并导入")
                                }
                                .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.borderedProminent)
                            .tint(.googleBlue)
                            .disabled(store.isImportingSub || subUrlInput.isEmpty)

                            Button("粘贴 JSON") { showPasteSheet = true }
                                .buttonStyle(.bordered)
                        }
                    }
                    .padding(.vertical, 6)
                }

                // Filter Picker
                Section {
                    Picker("筛选", selection: $filterTab) {
                        Text("全部(\(store.sources.count))").tag(0)
                        Text("直连").tag(1)
                        Text("网页嗅探").tag(2)
                        Text("爬虫").tag(3)
                    }
                    .pickerStyle(.segmented)
                }

                // Sources List
                Section("视频源列表 (\(filteredSources.count))") {
                    ForEach(filteredSources) { src in
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    HStack(spacing: 6) {
                                        Text(src.name).font(.subheadline.weight(.semibold)).lineLimit(1)
                                        Text(src.type.badge)
                                            .font(.caption2.weight(.medium))
                                            .padding(.horizontal, 6)
                                            .padding(.vertical, 2)
                                            .background(Color.googleBlueSoft.opacity(0.6))
                                            .foregroundColor(.googleBlue)
                                            .clipShape(RoundedRectangle(cornerRadius: 6))
                                        if let ms = src.latencyMs {
                                            Text(ms == -2 ? "仅TVBox" : (ms < 0 ? "超时" : "\(ms)ms"))
                                                .font(.caption2)
                                                .foregroundColor(ms > 0 ? .googleGreen : .red)
                                        }
                                    }
                                    Text(src.apiUrl)
                                        .font(.caption2)
                                        .foregroundColor(.secondary)
                                        .lineLimit(1)
                                }
                                Spacer()
                                Toggle("", isOn: Binding(
                                    get: { src.enabled },
                                    set: { val in
                                        var updated = src
                                        updated.enabled = val
                                        store.upsertSource(updated)
                                    }
                                ))
                                .labelsHidden()
                            }

                            HStack(spacing: 16) {
                                Spacer()
                                if src.type == .WEB_SNIFFER {
                                    Button("打开嗅探") { onOpenWebSniffer(src.apiUrl, src.name) }
                                        .font(.caption)
                                }
                                Button("测速") { store.testSource(src) }.font(.caption)
                                Button("编辑") {
                                    editId = src.id; editName = src.name; editUrl = src.apiUrl
                                    editGroup = src.group; editType = src.type
                                    showAddSheet = true
                                }.font(.caption)
                                Button("删除", role: .destructive) { store.deleteSource(src.id) }.font(.caption)
                            }
                            .buttonStyle(.borderless)
                        }
                        .padding(.vertical, 4)
                    }
                }
            }
            .navigationTitle("视频源配置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        editId = nil; editName = ""; editUrl = ""; editGroup = "自定义源"; editType = .MACCMS_JSON
                        showAddSheet = true
                    } label: {
                        Image(systemName: "plus")
                    }
                }
            }
            .sheet(isPresented: $showAddSheet) {
                NavigationStack {
                    Form {
                        TextField("视频源名称", text: $editName)
                        TextField("接口地址 / 视频网站 URL", text: $editUrl)
                            .keyboardType(.URL)
                            .autocorrectionDisabled()
                        TextField("分组名称", text: $editGroup)
                        Picker("协议类型", selection: $editType) {
                            ForEach([SourceType.MACCMS_JSON, .MACCMS_XML, .T4_API, .WEB_SNIFFER]) { t in
                                Text(t.displayName).tag(t)
                            }
                        }
                    }
                    .navigationTitle(editId == nil ? "添加自定义视频源" : "编辑视频源")
                    .toolbar {
                        ToolbarItem(placement: .topBarLeading) { Button("取消") { showAddSheet = false } }
                        ToolbarItem(placement: .topBarTrailing) {
                            Button("保存") {
                                if !editName.isEmpty && !editUrl.isEmpty {
                                    let s = VideoSource(
                                        id: editId ?? "custom_\(UUID().uuidString.prefix(8))",
                                        name: editName,
                                        type: editType,
                                        apiUrl: editUrl,
                                        group: editGroup.isEmpty ? "自定义源" : editGroup
                                    )
                                    store.upsertSource(s)
                                    store.showToast("已保存视频源：\(s.name)")
                                    showAddSheet = false
                                }
                            }
                        }
                    }
                }
            }
            .sheet(isPresented: $showPasteSheet) {
                NavigationStack {
                    VStack {
                        TextEditor(text: $pasteJsonText)
                            .padding()
                    }
                    .navigationTitle("粘贴 TVBox JSON")
                    .toolbar {
                        ToolbarItem(placement: .topBarLeading) { Button("取消") { showPasteSheet = false } }
                        ToolbarItem(placement: .topBarTrailing) {
                            Button("导入") {
                                store.importTvBoxJson(pasteJsonText)
                                pasteJsonText = ""
                                showPasteSheet = false
                            }
                        }
                    }
                }
            }
        }
    }
}
