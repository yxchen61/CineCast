import Foundation
import SwiftUI

// MARK: - 独立小说模块数据模型（与影视模型完全隔离）

enum NovelSourceType: String, Codable, CaseIterable, Identifiable {
    case WIKISOURCE_API
    case GUTENDEX_API
    case LEGADO_JSON
    case LEGADO_HTML

    var id: String { rawValue }

    var badge: String {
        switch self {
        case .WIKISOURCE_API: return "典藏"
        case .GUTENDEX_API: return "名著"
        case .LEGADO_JSON: return "API"
        case .LEGADO_HTML: return "规则"
        }
    }
}

struct NovelRuleSearch: Codable, Equatable {
    var bookList: String = ""
    var name: String = ""
    var author: String = ""
    var bookUrl: String = ""
    var coverUrl: String = ""
    var intro: String = ""
    var kind: String = ""
    var lastChapter: String = ""
    var wordCount: String = ""
}

struct NovelRuleBookInfo: Codable, Equatable {
    var `init`: String = ""
    var name: String = ""
    var author: String = ""
    var coverUrl: String = ""
    var intro: String = ""
    var kind: String = ""
    var lastChapter: String = ""
    var tocUrl: String = ""
}

struct NovelRuleToc: Codable, Equatable {
    var chapterList: String = ""
    var chapterName: String = ""
    var chapterUrl: String = ""
    var nextTocUrl: String = ""
}

struct NovelRuleContent: Codable, Equatable {
    var content: String = ""
    var nextContentUrl: String = ""
    var replaceRegex: String = ""
}

struct NovelSource: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var baseUrl: String
    var group: String = "开源书源"
    var sourceType: NovelSourceType = .LEGADO_HTML
    var enabled: Bool = true
    var searchable: Bool = true
    var searchUrl: String = ""
    var exploreUrl: String = ""
    var headers: [String: String] = [:]
    var charset: String = "UTF-8"
    var comment: String = ""
    var subscriptionUrl: String = ""
    var latencyMs: Int? = nil
    var ruleSearch: NovelRuleSearch = NovelRuleSearch()
    var ruleBookInfo: NovelRuleBookInfo = NovelRuleBookInfo()
    var ruleToc: NovelRuleToc = NovelRuleToc()
    var ruleContent: NovelRuleContent = NovelRuleContent()
}

struct NovelSubscription: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var url: String
    var repoHint: String = ""
    var lastSyncedAt: Int64 = 0
    var importedCount: Int = 0
}

struct NovelExploreCategory: Identifiable, Equatable {
    var id: String { "\(title)_\(url)" }
    let title: String
    let url: String
}

struct NovelBook: Codable, Identifiable, Equatable {
    var id: String
    var sourceId: String
    var sourceName: String
    var title: String
    var author: String = ""
    var coverUrl: String = ""
    var intro: String = ""
    var kind: String = ""
    var latestChapter: String = ""
    var wordCount: String = ""
    var bookUrl: String
    var tocUrl: String = ""

    var uniqueKey: String { "\(sourceId)::\(bookUrl)" }
}

struct NovelChapter: Codable, Identifiable, Equatable {
    var id: String { "\(index)_\(url)" }
    var index: Int
    var title: String
    var url: String
    var inlineContent: String? = nil
}

struct BookshelfItem: Codable, Identifiable, Equatable {
    var id: String { bookKey }
    var bookKey: String
    var sourceId: String
    var sourceName: String
    var title: String
    var author: String = ""
    var coverUrl: String = ""
    var intro: String = ""
    var kind: String = ""
    var latestChapter: String = ""
    var bookUrl: String
    var tocUrl: String = ""
    var currentChapterIndex: Int = 0
    var currentChapterTitle: String = ""
    var totalChapters: Int = 0
    var scrollOffset: Int = 0
    var addedAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    var lastReadAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
}

enum ReaderTheme: String, Codable, CaseIterable, Identifiable {
    case PAPER
    case SEPIA
    case SAGE
    case NIGHT

    var id: String { rawValue }

    var label: String {
        switch self {
        case .PAPER: return "纯净纸白"
        case .SEPIA: return "暖黄羊皮"
        case .SAGE: return "护眼豆沙"
        case .NIGHT: return "深邃暗夜"
        }
    }

    var bgColor: Color {
        switch self {
        case .PAPER: return Color(red: 0.988, green: 0.988, blue: 0.980)
        case .SEPIA: return Color(red: 0.965, green: 0.937, blue: 0.878)
        case .SAGE: return Color(red: 0.898, green: 0.941, blue: 0.890)
        case .NIGHT: return Color(red: 0.071, green: 0.075, blue: 0.086)
        }
    }

    var textColor: Color {
        switch self {
        case .PAPER: return Color(red: 0.122, green: 0.122, blue: 0.122)
        case .SEPIA: return Color(red: 0.243, green: 0.184, blue: 0.110)
        case .SAGE: return Color(red: 0.122, green: 0.200, blue: 0.133)
        case .NIGHT: return Color(red: 0.855, green: 0.863, blue: 0.878)
        }
    }

    var secondaryColor: Color {
        switch self {
        case .PAPER: return Color(red: 0.373, green: 0.388, blue: 0.408)
        case .SEPIA: return Color(red: 0.478, green: 0.408, blue: 0.333)
        case .SAGE: return Color(red: 0.322, green: 0.416, blue: 0.337)
        case .NIGHT: return Color(red: 0.604, green: 0.627, blue: 0.651)
        }
    }
}

struct NovelReadingSettings: Codable, Equatable {
    var readerTheme: ReaderTheme = .PAPER
    var fontSizeSp: Double = 18.0
    var lineHeightMultiplier: Double = 1.75
    var paragraphSpacingDp: Double = 12.0
    var horizontalMarginDp: Double = 20.0
    var useSerifFont: Bool = false
    var keepScreenOn: Bool = true
}

struct NovelBackupBundle: Codable {
    var version: Int = 1
    var exportedAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    var novelSources: [NovelSource] = []
    var novelSubscriptions: [NovelSubscription] = []
    var bookshelf: [BookshelfItem] = []
    var readingSettings: NovelReadingSettings = NovelReadingSettings()
}
