import SwiftUI

struct NovelHubView: View {
    @EnvironmentObject var novelStore: NovelStore
    let onOpenReader: () -> Void

    @State private var subTab = 1 // 0=书架 1=书城 2=书源
    @State private var isSearchMode = false
    @State private var showSourcePicker = false
    @State private var subUrlInput = "https://fastly.jsdelivr.net/gh/XIU2/Yuedu@master/shuyuan"
    @State private var showPasteSheet = false
    @State private var pasteJsonText = ""

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Google Play Books Style Segmented Header
                VStack(spacing: 10) {
                    if isSearchMode {
                        HStack(spacing: 8) {
                            Button {
                                isSearchMode = false
                                novelStore.clearSearch()
                            } label: {
                                Image(systemName: "chevron.left").font(.body.weight(.semibold))
                            }
                            TextField("搜书名、作者（多书源聚合）...", text: $novelStore.searchQuery)
                                .textFieldStyle(.plain)
                                .submitLabel(.search)
                                .onSubmit { novelStore.executeSearch() }
                            if !novelStore.searchQuery.isEmpty {
                                Button { novelStore.searchQuery = "" } label: {
                                    Image(systemName: "xmark.circle.fill").foregroundColor(.secondary)
                                }
                            }
                            Button("搜书") { novelStore.executeSearch() }
                                .font(.subheadline.weight(.semibold))
                                .foregroundColor(.googleBlue)
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .background(Color(.secondarySystemBackground))
                        .clipShape(Capsule())
                    } else {
                        HStack {
                            Label("极光阅读", systemImage: "book.fill")
                                .font(.title3.weight(.semibold))
                                .foregroundColor(.primary)
                            if subTab == 1, let active = novelStore.activeSource {
                                Button { showSourcePicker = true } label: {
                                    HStack(spacing: 4) {
                                        Text(active.name.components(separatedBy: " ").first ?? active.name)
                                            .font(.caption.weight(.semibold))
                                            .lineLimit(1)
                                        Image(systemName: "chevron.down").font(.caption2)
                                    }
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 5)
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

                        Picker("阅读分区", selection: $subTab) {
                            Text("书架(\(novelStore.bookshelf.count))").tag(0)
                            Text("书城").tag(1)
                            Text("书源(\(novelStore.enabledSources.count))").tag(2)
                        }
                        .pickerStyle(.segmented)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(Color(.systemBackground))

                Divider()

                if isSearchMode {
                    searchBody
                } else {
                    switch subTab {
                    case 0: bookshelfBody
                    case 1: discoverBody
                    default: sourcesBody
                    }
                }
            }
            .onAppear {
                if !novelStore.bookshelf.isEmpty && subTab == 1 { subTab = 0 }
            }
            .confirmationDialog("切换小说书源", isPresented: $showSourcePicker) {
                ForEach(novelStore.enabledSources) { src in
                    Button(src.name) { novelStore.selectSource(src.id) }
                }
            }
            .sheet(isPresented: $showPasteSheet) {
                NavigationStack {
                    TextEditor(text: $pasteJsonText).padding()
                        .navigationTitle("粘贴 Legado 书源 JSON")
                        .toolbar {
                            ToolbarItem(placement: .topBarLeading) { Button("取消") { showPasteSheet = false } }
                            ToolbarItem(placement: .topBarTrailing) {
                                Button("导入") {
                                    novelStore.importLegadoJson(pasteJsonText)
                                    pasteJsonText = ""
                                    showPasteSheet = false
                                }
                            }
                        }
                }
            }
        }
    }

    // MARK: - 书架
    private var bookshelfBody: some View {
        Group {
            if novelStore.bookshelf.isEmpty {
                VStack(spacing: 12) {
                    Spacer()
                    Image(systemName: "books.vertical.fill").font(.system(size: 46)).foregroundColor(.googleBlue)
                    Text("书架空空如也").font(.headline)
                    Text("去「书城」挑选小说，或点击搜索跨多书源找书").font(.caption).foregroundColor(.secondary)
                    Button("逛逛书城") { subTab = 1 }.buttonStyle(.borderedProminent).tint(.googleBlue)
                    Spacer()
                }
            } else {
                List {
                    ForEach(novelStore.bookshelf) { item in
                        Button {
                            Task {
                                await novelStore.openShelfItem(item)
                                onOpenReader()
                            }
                        } label: {
                            HStack(spacing: 12) {
                                coverBox(title: item.title, url: item.coverUrl)
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(item.title).font(.subheadline.weight(.semibold)).foregroundColor(.primary).lineLimit(1)
                                    Text(item.currentChapterTitle.isEmpty ? "第 \(item.currentChapterIndex + 1) 章" : item.currentChapterTitle)
                                        .font(.caption).foregroundColor(.googleBlue).lineLimit(1)
                                    Text(item.sourceName).font(.caption2).foregroundColor(.secondary)
                                }
                                Spacer()
                                Image(systemName: "chevron.right").foregroundColor(.secondary)
                            }
                            .padding(.vertical, 4)
                        }
                        .swipeActions {
                            Button("移出", role: .destructive) { novelStore.removeFromBookshelf(item.bookKey) }
                        }
                    }
                }
                .listStyle(.insetGrouped)
            }
        }
    }

    // MARK: - 书城
    private var discoverBody: some View {
        VStack(spacing: 0) {
            if !novelStore.categories.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(Array(novelStore.categories.enumerated()), id: \.offset) { idx, cat in
                            Button(cat.title) { novelStore.selectCategory(idx) }
                                .font(.caption.weight(.medium))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(novelStore.selectedCategoryIndex == idx ? Color.googleBlue : Color(.secondarySystemBackground))
                                .foregroundColor(novelStore.selectedCategoryIndex == idx ? .white : .primary)
                                .clipShape(Capsule())
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                }
            }

            if novelStore.isLoadingExplore {
                Spacer(); ProgressView("加载精选小说..."); Spacer()
            } else if let err = novelStore.exploreError, novelStore.exploreBooks.isEmpty {
                Spacer()
                Text(err).font(.caption).foregroundColor(.secondary).padding()
                Button("重试") { Task { await novelStore.loadExplore(reset: true) } }
                Spacer()
            } else {
                List(novelStore.exploreBooks) { book in
                    bookRow(book)
                }
                .listStyle(.plain)
            }
        }
    }

    // MARK: - 搜索结果
    private var searchBody: some View {
        VStack(spacing: 0) {
            if novelStore.isSearching { ProgressView().padding(8) }
            if novelStore.searchResults.isEmpty && !novelStore.isSearching {
                Spacer()
                Text(novelStore.searchError ?? "输入书名或作者，跨 GitHub 多书源并发搜书")
                    .font(.caption).foregroundColor(.secondary).padding()
                Spacer()
            } else {
                List(novelStore.searchResults) { book in
                    bookRow(book)
                }
                .listStyle(.plain)
            }
        }
    }

    // MARK: - 独立书源管理
    private var sourcesBody: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 10) {
                    Label("导入 GitHub 开源书源 (Legado 3.0 JSON)", systemImage: "icloud.and.arrow.down.fill")
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(.googleBlue)
                    Text("独立管理小说书源，与视频源完全分离。兼容 XIU2/Yuedu、liufuyou/read 等仓库。")
                        .font(.caption).foregroundColor(.secondary)
                    TextField("GitHub Raw / jsDelivr 书源 JSON 地址", text: $subUrlInput)
                        .textFieldStyle(.roundedBorder)
                        .keyboardType(.URL)
                        .autocorrectionDisabled()
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(novelStore.subscriptions) { sub in
                                Button(sub.name) { subUrlInput = sub.url }
                                    .font(.caption2)
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 5)
                                    .background(Color(.tertiarySystemBackground))
                                    .clipShape(Capsule())
                            }
                        }
                    }
                    HStack {
                        Button {
                            novelStore.importLegadoUrl(subUrlInput)
                        } label: {
                            HStack {
                                if novelStore.isImporting { ProgressView().tint(.white) }
                                Text(novelStore.isImporting ? "拉取中..." : "从 Git 拉取导入")
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(.googleBlue)
                        .disabled(novelStore.isImporting || subUrlInput.isEmpty)

                        Button("粘贴 JSON") { showPasteSheet = true }.buttonStyle(.bordered)
                    }
                }
                .padding(.vertical, 4)
            }

            Section("小说书源列表 (\(novelStore.sources.count))") {
                ForEach(novelStore.sources) { src in
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                HStack(spacing: 6) {
                                    Text(src.name).font(.subheadline.weight(.semibold)).lineLimit(1)
                                    Text(src.sourceType.badge)
                                        .font(.caption2.weight(.medium))
                                        .padding(.horizontal, 6).padding(.vertical, 2)
                                        .background(Color.googleBlueSoft.opacity(0.6))
                                        .foregroundColor(.googleBlue)
                                        .clipShape(RoundedRectangle(cornerRadius: 6))
                                    if let ms = src.latencyMs {
                                        Text(ms < 0 ? "超时" : "\(ms)ms")
                                            .font(.caption2)
                                            .foregroundColor(ms > 0 ? .googleGreen : .red)
                                    }
                                }
                                Text(src.baseUrl).font(.caption2).foregroundColor(.secondary).lineLimit(1)
                            }
                            Spacer()
                            Toggle("", isOn: Binding(
                                get: { src.enabled },
                                set: { val in
                                    var u = src; u.enabled = val
                                    novelStore.upsertSource(u)
                                }
                            )).labelsHidden()
                        }
                        HStack(spacing: 16) {
                            Spacer()
                            Button("测速") { novelStore.testSource(src) }.font(.caption)
                            Button("删除", role: .destructive) { novelStore.deleteSource(src.id) }.font(.caption)
                        }
                        .buttonStyle(.borderless)
                    }
                }
            }
        }
    }

    private func bookRow(_ book: NovelBook) -> some View {
        Button {
            Task {
                await novelStore.openBookInReader(book)
                onOpenReader()
            }
        } label: {
            HStack(alignment: .top, spacing: 12) {
                coverBox(title: book.title, url: book.coverUrl)
                VStack(alignment: .leading, spacing: 4) {
                    Text(book.title).font(.subheadline.weight(.semibold)).foregroundColor(.primary).lineLimit(1)
                    Text([book.author, book.kind, book.sourceName.components(separatedBy: " ").first ?? ""].filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(.caption).foregroundColor(.googleBlue).lineLimit(1)
                    if !book.intro.isEmpty {
                        Text(book.intro).font(.caption2).foregroundColor(.secondary).lineLimit(2)
                    }
                }
            }
            .padding(.vertical, 4)
        }
    }

    private func coverBox(title: String, url: String) -> some View {
        ZStack {
            RoundedRectangle(cornerRadius: 10).fill(Color.googleBlue.opacity(0.85))
            Text(String(title.prefix(4))).font(.caption2.weight(.bold)).foregroundColor(.white).padding(4)
            if let imgUrl = URL(string: url), url.hasPrefix("http") {
                AsyncImage(url: imgUrl) { img in
                    img.resizable().scaledToFill()
                } placeholder: { EmptyView() }
            }
        }
        .frame(width: 56, height: 78)
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }
}
