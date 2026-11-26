import SwiftUI

struct NovelReaderView: View {
    @EnvironmentObject var novelStore: NovelStore
    @Environment(\.dismiss) private var dismiss

    @State private var showControls = false
    @State private var showToc = false
    @State private var showSettings = false
    @State private var showSwitchSource = false

    private var theme: ReaderTheme { novelStore.readingSettings.readerTheme }

    var body: some View {
        ZStack {
            theme.bgColor.ignoresSafeArea()

            VStack(spacing: 0) {
                // Running Header
                HStack {
                    Text(novelStore.chapters.indices.contains(novelStore.currentChapterIndex)
                         ? novelStore.chapters[novelStore.currentChapterIndex].title
                         : (novelStore.activeBook?.title ?? ""))
                        .font(.caption2)
                        .foregroundColor(theme.secondaryColor)
                        .lineLimit(1)
                    Spacer()
                    if !novelStore.chapters.isEmpty {
                        Text("\(novelStore.currentChapterIndex + 1) / \(novelStore.chapters.count)")
                            .font(.caption2)
                            .foregroundColor(theme.secondaryColor)
                    }
                }
                .padding(.horizontal, novelStore.readingSettings.horizontalMarginDp)
                .padding(.vertical, 8)

                if novelStore.isLoadingReader {
                    Spacer(); ProgressView("加载章节正文..."); Spacer()
                } else if let err = novelStore.readerError {
                    Spacer()
                    VStack(spacing: 12) {
                        Text(err).font(.subheadline).foregroundColor(theme.textColor)
                        HStack {
                            Button("重试") { Task { await novelStore.loadChapter(index: novelStore.currentChapterIndex) } }
                                .buttonStyle(.bordered)
                            Button("换源阅读") {
                                showSwitchSource = true
                                Task { await novelStore.searchAlternativeSources() }
                            }
                            .buttonStyle(.borderedProminent).tint(.googleBlue)
                        }
                    }
                    .padding()
                    Spacer()
                } else {
                    ScrollView {
                        VStack(alignment: .leading, spacing: novelStore.readingSettings.paragraphSpacingDp) {
                            if novelStore.chapters.indices.contains(novelStore.currentChapterIndex) {
                                Text(novelStore.chapters[novelStore.currentChapterIndex].title)
                                    .font(.system(size: novelStore.readingSettings.fontSizeSp + 5, weight: .bold, design: novelStore.readingSettings.useSerifFont ? .serif : .default))
                                    .foregroundColor(theme.textColor)
                                    .padding(.bottom, 8)
                            }
                            ForEach(novelStore.chapterContent.components(separatedBy: "\n\n").filter { !$0.isEmpty }, id: \.self) { para in
                                Text("　　\(para)")
                                    .font(.system(size: novelStore.readingSettings.fontSizeSp, design: novelStore.readingSettings.useSerifFont ? .serif : .default))
                                    .lineSpacing(novelStore.readingSettings.fontSizeSp * (novelStore.readingSettings.lineHeightMultiplier - 1.0))
                                    .foregroundColor(theme.textColor)
                            }

                            HStack(spacing: 12) {
                                Button("上一章") {
                                    Task { await novelStore.loadChapter(index: novelStore.currentChapterIndex - 1) }
                                }
                                .buttonStyle(.bordered)
                                .disabled(novelStore.currentChapterIndex <= 0)
                                .frame(maxWidth: .infinity)

                                Button("下一章") {
                                    Task { await novelStore.loadChapter(index: novelStore.currentChapterIndex + 1) }
                                }
                                .buttonStyle(.borderedProminent)
                                .tint(.googleBlue)
                                .disabled(novelStore.currentChapterIndex + 1 >= novelStore.chapters.count)
                                .frame(maxWidth: .infinity)
                            }
                            .padding(.top, 24)
                            .padding(.bottom, 36)
                        }
                        .padding(.horizontal, novelStore.readingSettings.horizontalMarginDp)
                    }
                    .onTapGesture {
                        withAnimation(.easeInOut(duration: 0.2)) { showControls.toggle() }
                    }
                }
            }

            // Floating Controls Overlay
            if showControls {
                VStack {
                    HStack {
                        Button { dismiss() } label: {
                            Image(systemName: "chevron.left").font(.headline).foregroundColor(theme.textColor)
                        }
                        VStack(alignment: .leading) {
                            Text(novelStore.activeBook?.title ?? "").font(.subheadline.weight(.semibold)).foregroundColor(theme.textColor).lineLimit(1)
                            Text(novelStore.activeReaderSource?.name ?? "").font(.caption2).foregroundColor(theme.secondaryColor)
                        }
                        Spacer()
                        Button("换源") {
                            showSwitchSource = true
                            Task { await novelStore.searchAlternativeSources() }
                        }
                        .font(.subheadline.weight(.medium))
                        .foregroundColor(.googleBlue)
                    }
                    .padding()
                    .background(.ultraThinMaterial)

                    Spacer()

                    HStack(spacing: 28) {
                        Button { showToc = true } label: {
                            VStack(spacing: 4) { Image(systemName: "list.bullet"); Text("目录").font(.caption2) }
                        }
                        Button { showSettings = true } label: {
                            VStack(spacing: 4) { Image(systemName: "textformat.size"); Text("排版").font(.caption2) }
                        }
                        Button {
                            novelStore.updateReadingSettings { $0.readerTheme = ($0.readerTheme == .NIGHT) ? .PAPER : .NIGHT }
                        } label: {
                            VStack(spacing: 4) {
                                Image(systemName: theme == .NIGHT ? "sun.max.fill" : "moon.fill")
                                Text(theme == .NIGHT ? "日间" : "夜间").font(.caption2)
                            }
                        }
                        Button {
                            Task { await novelStore.loadChapter(index: novelStore.currentChapterIndex) }
                        } label: {
                            VStack(spacing: 4) { Image(systemName: "arrow.clockwise"); Text("刷新").font(.caption2) }
                        }
                    }
                    .foregroundColor(theme.textColor)
                    .padding(.vertical, 14)
                    .frame(maxWidth: .infinity)
                    .background(.ultraThinMaterial)
                }
            }
        }
        .sheet(isPresented: $showToc) {
            NavigationStack {
                List(novelStore.chapters) { ch in
                    Button {
                        showToc = false
                        showControls = false
                        Task { await novelStore.loadChapter(index: ch.index) }
                    } label: {
                        HStack {
                            Text(ch.title)
                                .foregroundColor(ch.index == novelStore.currentChapterIndex ? .googleBlue : .primary)
                                .fontWeight(ch.index == novelStore.currentChapterIndex ? .semibold : .regular)
                            Spacer()
                        }
                    }
                }
                .navigationTitle("目录 (\(novelStore.chapters.count) 章)")
                .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("关闭") { showToc = false } } }
            }
        }
        .sheet(isPresented: $showSettings) {
            NavigationStack {
                Form {
                    Section("阅读主题 (Google 护眼纸感)") {
                        Picker("主题", selection: Binding(
                            get: { novelStore.readingSettings.readerTheme },
                            set: { t in novelStore.updateReadingSettings { $0.readerTheme = t } }
                        )) {
                            ForEach(ReaderTheme.allCases) { t in Text(t.label).tag(t) }
                        }
                        .pickerStyle(.segmented)
                    }
                    Section("字号与行距") {
                        HStack {
                            Text("字号 \(Int(novelStore.readingSettings.fontSizeSp))")
                            Slider(value: Binding(
                                get: { novelStore.readingSettings.fontSizeSp },
                                set: { v in novelStore.updateReadingSettings { $0.fontSizeSp = v } }
                            ), in: 14...28, step: 1)
                        }
                        Toggle("衬线书籍字体 (Serif)", isOn: Binding(
                            get: { novelStore.readingSettings.useSerifFont },
                            set: { v in novelStore.updateReadingSettings { $0.useSerifFont = v } }
                        ))
                    }
                }
                .navigationTitle("排版设置")
                .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("完成") { showSettings = false } } }
            }
            .presentationDetents([.medium])
        }
        .sheet(isPresented: $showSwitchSource) {
            NavigationStack {
                List {
                    if novelStore.isSearchingSwitch { ProgressView("正在全书源查找同名书籍...") }
                    ForEach(novelStore.switchCandidates) { cand in
                        Button {
                            showSwitchSource = false
                            showControls = false
                            let cur = novelStore.currentChapterIndex
                            Task { await novelStore.openBookInReader(cand, startChapter: cur) }
                        } label: {
                            VStack(alignment: .leading) {
                                Text(cand.sourceName).font(.subheadline.weight(.semibold)).foregroundColor(.googleBlue)
                                Text("\(cand.title) · \(cand.author)").font(.caption).foregroundColor(.secondary)
                            }
                        }
                    }
                }
                .navigationTitle("换源阅读")
                .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("关闭") { showSwitchSource = false } } }
            }
        }
    }
}
