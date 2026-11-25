import SwiftUI

private enum FeedSortMode: String, CaseIterable, Identifiable {
    case defaultLatest = "默认最新"
    case scoreDesc = "高分优先"
    case yearDesc = "年份最新"
    var id: String { rawValue }
}

struct HomeView: View {
    @EnvironmentObject var store: AppStore
    let onOpenVod: (String, String) -> Void
    let onOpenWebSniffer: (String, String) -> Void
    let onOpenCast: () -> Void

    @State private var isSearchMode = false
    @State private var showSourcePicker = false
    @State private var showCategorySheet = false
    @State private var selectedYear = "全部"
    @State private var sortMode: FeedSortMode = .defaultLatest
    @State private var homeSectionTab = 0 // 0 = 最新热播, 1 = 我的收藏
    @FocusState private var searchFocused: Bool

    private var hasExtraFilter: Bool {
        selectedYear != "全部" || sortMode != .defaultLatest
    }

    private var topCategories: [VodCategory] {
        let roots = store.categories.filter { $0.pid == "0" || $0.pid.isEmpty }
        return roots.count >= 2 ? roots : store.categories
    }

    private var activeParentId: String? {
        guard let sel = store.categories.first(where: { $0.id == store.selectedCategoryId }) else { return nil }
        if !sel.childIds.isEmpty { return sel.id }
        if sel.pid != "0" && !sel.pid.isEmpty { return sel.pid }
        return nil
    }

    private var subCategories: [VodCategory] {
        guard let pid = activeParentId else { return [] }
        return store.categories.filter { $0.pid == pid }
    }

    private var displayItems: [VodItem] {
        let filtered: [VodItem]
        switch selectedYear {
        case "全部": filtered = store.feedItems
        case "更早": filtered = store.feedItems.filter { (Int($0.year) ?? 9999) < 2023 }
        default: filtered = store.feedItems.filter { $0.year == selectedYear }
        }
        switch sortMode {
        case .defaultLatest: return filtered
        case .scoreDesc: return filtered.sorted { (Double($0.score) ?? 0) > (Double($1.score) ?? 0) }
        case .yearDesc: return filtered.sorted { (Int($0.year) ?? 0) > (Int($1.year) ?? 0) }
        }
    }

    private var heroSpotlightItems: [HeroSpotlightItem] {
        let continueItems = store.history.prefix(5).map { h -> HeroSpotlightItem in
            let progress: Double? = h.durationMs > 0 ? min(1, max(0, Double(h.positionMs) / Double(h.durationMs))) : nil
            let timeInfo = h.durationMs > 0
                ? "\(TimeFormatter.formatMs(h.positionMs)) / \(TimeFormatter.formatMs(h.durationMs))"
                : TimeFormatter.formatMs(h.positionMs)
            return HeroSpotlightItem(
                vodId: h.vodId,
                sourceId: h.sourceId,
                name: h.name,
                pic: h.pic,
                badgeText: "继续观看",
                subtitle: "上次看到：\(h.episodeName.isEmpty ? "正片" : h.episodeName) · \(timeInfo)",
                buttonText: "继续播放",
                isContinueWatching: true,
                progressFraction: progress
            )
        }
        let seen = Set(continueItems.map { "\($0.sourceId)_\($0.vodId)" })
        let need = max(0, 5 - continueItems.count)
        let featuredFill = displayItems
            .filter { !seen.contains($0.uniqueKey) }
            .prefix(need)
            .map { item -> HeroSpotlightItem in
                let meta = [item.typeName, item.year == "0" ? "" : item.year, item.remarks]
                    .filter { !$0.isEmpty }.joined(separator: " · ")
                return HeroSpotlightItem(
                    vodId: item.id,
                    sourceId: item.sourceId,
                    name: item.name,
                    pic: item.pic,
                    badgeText: "今日精选",
                    subtitle: meta.isEmpty ? "高清直链 · 支持 AirPlay / DLNA 投屏" : meta,
                    buttonText: "播放",
                    isContinueWatching: false,
                    progressFraction: nil
                )
            }
        return continueItems + featuredFill
    }

    var body: some View {
        VStack(spacing: 0) {
            // Compact Google TV Header
            VStack(spacing: 8) {
                if isSearchMode {
                    searchHeaderBar
                } else {
                    normalHeaderBar
                    if !store.categories.isEmpty {
                        categoryBar
                    }
                }
            }
            .padding(.top, 8)
            .padding(.bottom, 6)
            .background(Color(.systemBackground))

            if isSearchMode {
                searchBodyView
            } else {
                discoveryBodyView
            }
        }
        .sheet(isPresented: $showSourcePicker) { sourcePickerSheet }
        .sheet(isPresented: $showCategorySheet) { categoryFilterSheet }
    }

    // MARK: - Header Bars

    private var normalHeaderBar: some View {
        HStack(spacing: 10) {
            Button { showSourcePicker = true } label: {
                HStack(spacing: 6) {
                    Image(systemName: "play.circle.fill")
                        .foregroundColor(.googleBlue)
                    Text(store.activeSource?.name.components(separatedBy: " ").first ?? "选择片源")
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(.primary)
                        .lineLimit(1)
                    Image(systemName: "chevron.down")
                        .font(.caption.weight(.semibold))
                        .foregroundColor(.secondary)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color.googleBlueSoft.opacity(0.45))
                .clipShape(Capsule())
            }

            Spacer()

            Button {
                isSearchMode = true
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { searchFocused = true }
            } label: {
                Image(systemName: "magnifyingglass")
                    .font(.body.weight(.medium))
                    .foregroundColor(.primary)
                    .frame(width: 36, height: 36)
                    .background(Color(.secondarySystemBackground))
                    .clipShape(Circle())
            }

            Button { onOpenWebSniffer("https://m.bilibili.com/", "网页嗅探浏览器") } label: {
                Image(systemName: "globe")
                    .font(.body)
                    .foregroundColor(.secondary)
                    .frame(width: 36, height: 36)
            }

            Button { onOpenCast() } label: {
                Image(systemName: "tv")
                    .font(.body)
                    .foregroundColor(DlnaService.shared.session.connectedDevice != nil ? .googleBlue : .secondary)
                    .frame(width: 36, height: 36)
            }
        }
        .padding(.horizontal, 14)
    }

    private var searchHeaderBar: some View {
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                Button {
                    isSearchMode = false
                    store.clearSearch()
                    searchFocused = false
                } label: {
                    Image(systemName: "chevron.left")
                        .font(.body.weight(.semibold))
                        .foregroundColor(.primary)
                        .padding(6)
                }

                TextField("搜索电影、剧集、动漫、演员...", text: $store.searchQuery)
                    .focused($searchFocused)
                    .submitLabel(.search)
                    .onSubmit {
                        searchFocused = false
                        store.executeSearch()
                    }

                if !store.searchQuery.isEmpty {
                    Button { store.searchQuery = "" } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundColor(.secondary)
                    }
                }

                Button("搜索") {
                    searchFocused = false
                    store.executeSearch()
                }
                .font(.subheadline.weight(.semibold))
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .background(Color.googleBlue)
                .foregroundColor(.white)
                .clipShape(Capsule())
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(Color(.secondarySystemBackground))
            .clipShape(Capsule())
            .padding(.horizontal, 14)

            HStack(spacing: 8) {
                pillToggle("全源聚合搜索", selected: store.searchAllSources) {
                    store.searchAllSources = true
                    if !store.searchQuery.isEmpty { store.executeSearch() }
                }
                pillToggle("仅当前源", selected: !store.searchAllSources) {
                    store.searchAllSources = false
                    if !store.searchQuery.isEmpty { store.executeSearch() }
                }
                Spacer()
            }
            .padding(.horizontal, 14)
        }
    }

    private var categoryBar: some View {
        VStack(spacing: 6) {
            HStack(spacing: 6) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        primaryCategoryPill("推荐", selected: store.selectedCategoryId == nil) {
                            selectedYear = "全部"
                            store.selectCategory(nil)
                        }
                        ForEach(topCategories) { cat in
                            let selected = store.selectedCategoryId == cat.id || activeParentId == cat.id
                            primaryCategoryPill(cat.name, selected: selected) {
                                selectedYear = "全部"
                                store.selectCategory(cat.id)
                            }
                        }
                    }
                    .padding(.leading, 14)
                }

                Button { showCategorySheet = true } label: {
                    ZStack(alignment: .topTrailing) {
                        Image(systemName: "slider.horizontal.3")
                            .font(.body.weight(.medium))
                            .foregroundColor(hasExtraFilter ? .googleBlue : .secondary)
                            .padding(8)
                        if hasExtraFilter {
                            Circle()
                                .fill(Color.googleBlue)
                                .frame(width: 7, height: 7)
                                .offset(x: -4, y: 4)
                        }
                    }
                }
                .padding(.trailing, 8)
            }

            if !subCategories.isEmpty, let pid = activeParentId {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        subCategoryChip("全部", selected: store.selectedCategoryId == pid) {
                            store.selectCategory(pid)
                        }
                        ForEach(subCategories) { sub in
                            subCategoryChip(sub.name, selected: store.selectedCategoryId == sub.id) {
                                store.selectCategory(sub.id)
                            }
                        }
                    }
                    .padding(.horizontal, 14)
                }
                .transition(.opacity)
            }
        }
    }

    // MARK: - Discovery Body

    private var discoveryBodyView: some View {
        Group {
            if store.isLoadingFeed && store.feedItems.isEmpty {
                VStack(spacing: 12) {
                    Spacer()
                    ProgressView().scaleEffect(1.2)
                    Text("正在加载高清片库...").font(.subheadline).foregroundColor(.secondary)
                    Spacer()
                }
            } else if let err = store.feedError, store.feedItems.isEmpty {
                VStack(spacing: 14) {
                    Spacer()
                    Image(systemName: "wifi.exclamationmark").font(.largeTitle).foregroundColor(.secondary)
                    Text(err).font(.subheadline).multilineTextAlignment(.center).padding(.horizontal, 28)
                    Button("重新加载") { Task { await store.loadHomeFeed(reset: true) } }
                        .buttonStyle(.borderedProminent)
                        .tint(.googleBlue)
                    Spacer()
                }
            } else {
                let showHero = store.selectedCategoryId == nil && !hasExtraFilter && (!displayItems.isEmpty || !store.history.isEmpty)
                let heroFeaturedIds = Set(heroSpotlightItems.filter { !$0.isContinueWatching }.map(\.vodId))
                let hotGridItems = (showHero && displayItems.count > 6)
                    ? displayItems.filter { !heroFeaturedIds.contains($0.id) }
                    : displayItems
                let favItems = store.favorites.map { f in
                    VodItem(id: f.vodId, name: f.name, pic: f.pic, remarks: f.remarks, sourceId: f.sourceId, sourceName: f.sourceName)
                }

                ScrollView {
                    VStack(spacing: 14) {
                        if showHero {
                            HeroCarouselView(items: heroSpotlightItems) { item in
                                onOpenVod(item.sourceId, item.vodId)
                            }

                            HStack {
                                HStack(spacing: 8) {
                                    sectionTabPill("最新热播", selected: homeSectionTab == 0) { homeSectionTab = 0 }
                                    sectionTabPill(store.favorites.isEmpty ? "我的收藏" : "我的收藏 (\(store.favorites.count))", selected: homeSectionTab == 1) {
                                        homeSectionTab = 1
                                    }
                                }
                                Spacer()
                                if homeSectionTab == 0 {
                                    Button {
                                        Task { await store.loadHomeFeed(reset: true) }
                                    } label: {
                                        Label("换一批", systemImage: "arrow.clockwise")
                                            .font(.caption.weight(.medium))
                                            .foregroundColor(.googleBlue)
                                    }
                                }
                            }
                            .padding(.horizontal, 14)
                        }

                        if showHero && homeSectionTab == 1 {
                            if favItems.isEmpty {
                                VStack(spacing: 10) {
                                    Image(systemName: "bookmark").font(.largeTitle).foregroundColor(.secondary)
                                    Text("还没有收藏影片").font(.subheadline.weight(.medium))
                                    Text("在任意影片播放页点击「收藏」后，将直接在此快捷展示")
                                        .font(.caption).foregroundColor(.secondary)
                                }
                                .padding(32)
                                .frame(maxWidth: .infinity)
                                .background(Color(.secondarySystemBackground))
                                .clipShape(RoundedRectangle(cornerRadius: 20))
                                .padding(.horizontal, 14)
                            } else {
                                posterGrid(favItems, showSource: true, enablePaging: false)
                            }
                        } else {
                            posterGrid(hotGridItems, showSource: false, enablePaging: true)
                        }
                    }
                    .padding(.vertical, 8)
                }
                .refreshable {
                    await store.loadHomeFeed(reset: true)
                }
            }
        }
    }

    // MARK: - Search Body

    private var searchBodyView: some View {
        VStack(spacing: 0) {
            if store.isSearching {
                ProgressView().progressViewStyle(.linear).tint(.googleBlue)
            }
            if !store.searchResults.isEmpty {
                ScrollView {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(store.isSearching
                             ? "已找到 \(store.searchResults.count) 条结果，正在继续检索其余视频源..."
                             : "共找到 \(store.searchResults.count) 条高清结果")
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .padding(.horizontal, 16)
                            .padding(.top, 8)
                        posterGrid(store.searchResults, showSource: true, enablePaging: false)
                    }
                    .padding(.bottom, 20)
                }
            } else if store.isSearching {
                VStack(spacing: 12) {
                    Spacer()
                    ProgressView()
                    Text("正在 9 源并发流式搜片...").font(.subheadline).foregroundColor(.secondary)
                    Spacer()
                }
            } else if let err = store.searchError {
                VStack(spacing: 12) {
                    Spacer()
                    Image(systemName: "magnifyingglass").font(.largeTitle).foregroundColor(.secondary)
                    Text(err).font(.subheadline).multilineTextAlignment(.center).padding(.horizontal, 28)
                    Button("重试") { store.executeSearch() }.buttonStyle(.borderedProminent).tint(.googleBlue)
                    Spacer()
                }
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("热门搜索").font(.subheadline.weight(.semibold)).foregroundColor(.secondary)
                        let hots = ["遮天", "庆余年", "斗破苍穹", "凡人修仙传", "流浪地球", "周星驰", "柯南", "海贼王"]
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4), spacing: 8) {
                            ForEach(hots, id: \.self) { kw in
                                Button(kw) {
                                    searchFocused = false
                                    store.executeSearch(keyword: kw)
                                }
                                .font(.caption.weight(.medium))
                                .padding(.vertical, 8)
                                .frame(maxWidth: .infinity)
                                .background(Color(.secondarySystemBackground))
                                .foregroundColor(.primary)
                                .clipShape(Capsule())
                            }
                        }
                    }
                    .padding(16)
                }
            }
        }
    }

    // MARK: - Grid & Components

    private func posterGrid(_ items: [VodItem], showSource: Bool, enablePaging: Bool) -> some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 3), spacing: 14) {
            ForEach(items, id: \.uniqueKey) { item in
                PosterCardView(item: item, showSourceBadge: showSource)
                    .onTapGesture { onOpenVod(item.sourceId, item.id) }
                    .onAppear {
                        if enablePaging && item.uniqueKey == items.last?.uniqueKey {
                            Task { await store.loadHomeFeed(reset: false) }
                        }
                    }
            }
        }
        .padding(.horizontal, 14)
    }

    private func primaryCategoryPill(_ label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.subheadline.weight(selected ? .semibold : .medium))
                .padding(.horizontal, 16)
                .padding(.vertical, 7)
                .background(selected ? Color.googleBlue : Color(.secondarySystemBackground))
                .foregroundColor(selected ? .white : .secondary)
                .clipShape(Capsule())
        }
    }

    private func subCategoryChip(_ label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.caption.weight(selected ? .semibold : .regular))
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(selected ? Color.googleBlueSoft.opacity(0.6) : Color.clear)
                .foregroundColor(selected ? .googleBlue : .secondary)
                .clipShape(RoundedRectangle(cornerRadius: 12))
        }
    }

    private func sectionTabPill(_ label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.subheadline.weight(selected ? .bold : .medium))
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(selected ? Color.googleBlueSoft.opacity(0.65) : Color.clear)
                .foregroundColor(selected ? .googleBlue : .secondary)
                .clipShape(RoundedRectangle(cornerRadius: 14))
        }
    }

    private func pillToggle(_ label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.caption.weight(.medium))
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(selected ? Color.googleBlueSoft.opacity(0.7) : Color(.secondarySystemBackground))
                .foregroundColor(selected ? .googleBlue : .secondary)
                .clipShape(Capsule())
        }
    }

    // MARK: - Sheets

    private var sourcePickerSheet: some View {
        NavigationStack {
            List {
                Section("高清直连接口 (\(store.activeApiSources.count))") {
                    ForEach(store.activeApiSources) { src in
                        Button {
                            selectedYear = "全部"
                            sortMode = .defaultLatest
                            store.selectSource(src.id)
                            showSourcePicker = false
                        } label: {
                            HStack {
                                Image(systemName: "play.circle.fill").foregroundColor(.googleBlue)
                                VStack(alignment: .leading) {
                                    Text(src.name).foregroundColor(.primary)
                                    Text("\(src.type.badge) · \(src.group)").font(.caption).foregroundColor(.secondary)
                                }
                                Spacer()
                                if src.id == store.activeSource?.id {
                                    Image(systemName: "checkmark.circle.fill").foregroundColor(.googleBlue)
                                }
                            }
                        }
                    }
                }
                if !store.webSources.isEmpty {
                    Section("网页嗅探站点") {
                        ForEach(store.webSources) { src in
                            Button {
                                showSourcePicker = false
                                onOpenWebSniffer(src.apiUrl, src.name)
                            } label: {
                                HStack {
                                    Image(systemName: "globe").foregroundColor(.googleGreen)
                                    Text(src.name).foregroundColor(.primary)
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("切换视频源")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("完成") { showSourcePicker = false } }
            }
        }
    }

    private var categoryFilterSheet: some View {
        NavigationStack {
            List {
                Section("排序方式") {
                    Picker("排序", selection: $sortMode) {
                        ForEach(FeedSortMode.allCases) { m in Text(m.rawValue).tag(m) }
                    }
                    .pickerStyle(.segmented)
                }
                Section("按年份筛选") {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(["全部", "2026", "2025", "2024", "2023", "更早"], id: \.self) { yr in
                                pillToggle(yr, selected: selectedYear == yr) { selectedYear = yr }
                            }
                        }
                    }
                }
                Section("频道分类总览") {
                    ForEach(topCategories) { parent in
                        let kids = store.categories.filter { $0.pid == parent.id }
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Text(parent.name).font(.subheadline.weight(.semibold)).foregroundColor(.googleBlue)
                                Spacer()
                                Button("查看全部 ›") {
                                    store.selectCategory(parent.id)
                                    showCategorySheet = false
                                }
                                .font(.caption)
                            }
                            if !kids.isEmpty {
                                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 3), spacing: 6) {
                                    ForEach(kids) { kid in
                                        Button(kid.name) {
                                            store.selectCategory(kid.id)
                                            showCategorySheet = false
                                        }
                                        .font(.caption)
                                        .padding(.vertical, 6)
                                        .frame(maxWidth: .infinity)
                                        .background(store.selectedCategoryId == kid.id ? Color.googleBlue : Color(.secondarySystemBackground))
                                        .foregroundColor(store.selectedCategoryId == kid.id ? .white : .primary)
                                        .clipShape(RoundedRectangle(cornerRadius: 8))
                                    }
                                }
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }
            }
            .navigationTitle("全部分类与筛选")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("重置") {
                        selectedYear = "全部"
                        sortMode = .defaultLatest
                        store.selectCategory(nil)
                        showCategorySheet = false
                    }
                }
                ToolbarItem(placement: .topBarTrailing) { Button("完成") { showCategorySheet = false } }
            }
        }
    }
}

// MARK: - Hero Spotlight Carousel & Poster Card

struct HeroCarouselView: View {
    let items: [HeroSpotlightItem]
    let onTap: (HeroSpotlightItem) -> Void
    @State private var currentIdx = 0

    var body: some View {
        VStack(spacing: 8) {
            TabView(selection: $currentIdx) {
                ForEach(Array(items.prefix(5).enumerated()), id: \.offset) { idx, item in
                    ZStack(alignment: .bottomLeading) {
                        AsyncImage(url: URL(string: item.pic)) { phase in
                            switch phase {
                            case .success(let img):
                                img.resizable().scaledToFill()
                            default:
                                Color(.secondarySystemBackground)
                            }
                        }
                        .frame(height: 196)
                        .clipped()

                        LinearGradient(
                            colors: [.black.opacity(0.08), .black.opacity(0.35), .black.opacity(0.88)],
                            startPoint: .top,
                            endPoint: .bottom
                        )

                        VStack(alignment: .leading) {
                            Text(item.badgeText)
                                .font(.caption2.weight(.semibold))
                                .foregroundColor(.white)
                                .padding(.horizontal, 9)
                                .padding(.vertical, 4)
                                .background(item.isContinueWatching ? Color.googleGreen.opacity(0.92) : Color.googleBlue.opacity(0.9))
                                .clipShape(RoundedRectangle(cornerRadius: 9))
                            Spacer()
                            HStack(alignment: .bottom) {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(item.name)
                                        .font(.title3.weight(.bold))
                                        .foregroundColor(.white)
                                        .lineLimit(1)
                                    Text(item.subtitle)
                                        .font(.caption)
                                        .foregroundColor(.white.opacity(0.88))
                                        .lineLimit(1)
                                }
                                Spacer()
                                Label(item.buttonText, systemImage: "play.fill")
                                    .font(.caption.weight(.semibold))
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 7)
                                    .background(Color.white)
                                    .foregroundColor(item.isContinueWatching ? .googleGreen : .googleBlue)
                                    .clipShape(Capsule())
                            }
                            if let progress = item.progressFraction {
                                ProgressView(value: progress)
                                    .tint(Color(red: 0.506, green: 0.788, blue: 0.584))
                                    .padding(.top, 4)
                            }
                        }
                        .padding(14)
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 22))
                    .padding(.horizontal, 14)
                    .onTapGesture { onTap(item) }
                    .tag(idx)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .frame(height: 200)

            if items.count > 1 {
                HStack(spacing: 5) {
                    ForEach(0..<min(5, items.count), id: \.self) { i in
                        Capsule()
                            .fill(i == currentIdx ? Color.googleBlue : Color.secondary.opacity(0.3))
                            .frame(width: i == currentIdx ? 18 : 6, height: 6)
                    }
                }
            }
        }
    }
}

struct PosterCardView: View {
    let item: VodItem
    let showSourceBadge: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            ZStack {
                Color(.secondarySystemBackground)
                AsyncImage(url: URL(string: item.pic)) { phase in
                    switch phase {
                    case .success(let img): img.resizable().scaledToFill()
                    default: Image(systemName: "film").foregroundColor(.secondary.opacity(0.4))
                    }
                }
            }
            .aspectRatio(0.68, contentMode: .fill)
            .clipped()
            .overlay(alignment: .bottom) {
                LinearGradient(colors: [.clear, .black.opacity(0.75)], startPoint: .top, endPoint: .bottom)
                    .frame(height: 44)
            }
            .overlay(alignment: .bottomTrailing) {
                if !item.remarks.isEmpty {
                    Text(item.remarks)
                        .font(.system(size: 10, weight: .medium))
                        .foregroundColor(.white)
                        .lineLimit(1)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color.black.opacity(0.55))
                        .clipShape(RoundedRectangle(cornerRadius: 7))
                        .padding(5)
                }
            }
            .overlay(alignment: .topLeading) {
                if !item.score.isEmpty && item.score != "0.0" && item.score != "0" {
                    HStack(spacing: 2) {
                        Image(systemName: "star.fill").font(.system(size: 9)).foregroundColor(.googleYellow)
                        Text(item.score).font(.system(size: 10, weight: .bold)).foregroundColor(.white)
                    }
                    .padding(.horizontal, 5)
                    .padding(.vertical, 2)
                    .background(Color.black.opacity(0.75))
                    .clipShape(RoundedRectangle(cornerRadius: 7))
                    .padding(5)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.secondary.opacity(0.18), lineWidth: 0.5))

            Text(item.name)
                .font(.caption.weight(.medium))
                .lineLimit(1)
                .foregroundColor(.primary)

            let sub = [
                showSourceBadge ? item.sourceName.components(separatedBy: " ").first ?? "" : item.typeName,
                item.year == "0" ? "" : item.year
            ].filter { !$0.isEmpty }.joined(separator: " · ")

            if !sub.isEmpty {
                Text(sub)
                    .font(.system(size: 10))
                    .foregroundColor(.secondary)
                    .lineLimit(1)
            }
        }
    }
}
