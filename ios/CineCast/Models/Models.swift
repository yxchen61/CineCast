import Foundation
import SwiftUI

enum SourceType: String, Codable, CaseIterable, Identifiable {
    case MACCMS_JSON
    case MACCMS_XML
    case T4_API
    case WEB_SNIFFER
    case TVBOX_SPIDER

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .MACCMS_JSON: return "苹果CMS JSON接口 (Type 1)"
        case .MACCMS_XML: return "苹果CMS XML接口 (Type 0)"
        case .T4_API: return "TVBox T4远程接口 (Type 4)"
        case .WEB_SNIFFER: return "网页嗅探视频站"
        case .TVBOX_SPIDER: return "TVBox 爬虫站点 (Type 3)"
        }
    }

    var badge: String {
        switch self {
        case .MACCMS_JSON: return "JSON"
        case .MACCMS_XML: return "XML"
        case .T4_API: return "T4"
        case .WEB_SNIFFER: return "网页嗅探"
        case .TVBOX_SPIDER: return "Spider"
        }
    }

    var isDirectApi: Bool {
        self == .MACCMS_JSON || self == .MACCMS_XML || self == .T4_API
    }
}

struct VideoSource: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var type: SourceType = .MACCMS_JSON
    var apiUrl: String
    var searchable: Bool = true
    var enabled: Bool = true
    var group: String = "默认"
    var categories: [String] = []
    var headers: [String: String] = [:]
    var ext: String = ""
    var subscriptionUrl: String = ""
    var latencyMs: Int? = nil
}

struct SubscriptionConfig: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var url: String
    var lastSyncedAt: Int64 = 0
    var siteCount: Int = 0
    var liveCount: Int = 0
    var parseCount: Int = 0
}

struct ParseInterface: Codable, Identifiable, Equatable {
    var id: String { url }
    var name: String
    var type: Int = 0
    var url: String
}

struct LiveSource: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var url: String
    var epg: String = ""
}

struct LiveChannel: Identifiable, Equatable {
    var id: String { "\(group)_\(name)" }
    let group: String
    let name: String
    let urls: [String]
    let logo: String
}

struct VodCategory: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var pid: String = "0"
    var childIds: [String] = []
}

struct VodItem: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var pic: String = ""
    var remarks: String = ""
    var year: String = ""
    var area: String = ""
    var typeName: String = ""
    var sourceId: String = ""
    var sourceName: String = ""
    var score: String = ""

    var uniqueKey: String { "\(sourceId)_\(id)" }
}

struct VodEpisode: Codable, Identifiable, Equatable {
    var id: String { "\(name)_\(url)" }
    var name: String
    var url: String
}

struct VodPlaySource: Codable, Identifiable, Equatable {
    var id: String { name }
    var name: String
    var episodes: [VodEpisode]
}

struct VodDetail: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var pic: String = ""
    var remarks: String = ""
    var year: String = ""
    var area: String = ""
    var actor: String = ""
    var director: String = ""
    var content: String = ""
    var typeName: String = ""
    var sourceId: String = ""
    var sourceName: String = ""
    var playSources: [VodPlaySource] = []
}

struct WatchHistoryItem: Codable, Identifiable, Equatable {
    var id: String { "\(sourceId)_\(vodId)" }
    var vodId: String
    var sourceId: String
    var sourceName: String
    var name: String
    var pic: String = ""
    var episodeIndex: Int = 0
    var episodeName: String = ""
    var playSourceIndex: Int = 0
    var positionMs: Int64 = 0
    var durationMs: Int64 = 0
    var updatedAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
}

struct FavoriteItem: Codable, Identifiable, Equatable {
    var id: String { "\(sourceId)_\(vodId)" }
    var vodId: String
    var sourceId: String
    var sourceName: String
    var name: String
    var pic: String = ""
    var remarks: String = ""
    var addedAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
}

enum ThemeMode: String, Codable, CaseIterable, Identifiable {
    case SYSTEM
    case LIGHT
    case DARK

    var id: String { rawValue }

    var label: String {
        switch self {
        case .SYSTEM: return "跟随系统"
        case .LIGHT: return "浅色模式"
        case .DARK: return "深色模式"
        }
    }

    var colorScheme: ColorScheme? {
        switch self {
        case .SYSTEM: return nil
        case .LIGHT: return .light
        case .DARK: return .dark
        }
    }
}

struct AppSettings: Codable, Equatable {
    var themeMode: ThemeMode = .SYSTEM
    var autoNextEpisode: Bool = true
    var defaultSpeed: Double = 1.0
    var adBlockHosts: [String] = [
        "mimg.0c1q0l.cn", "www.googletagmanager.com", "www.google-analytics.com",
        "hm.baidu.com", "s23.cnzz.com", "z3.cnzz.com", "c.cnzz.com", "s9.cnzz.com"
    ]
}

struct BackupBundle: Codable {
    var version: Int = 1
    var exportedAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    var sources: [VideoSource] = []
    var subscriptions: [SubscriptionConfig] = []
    var liveSources: [LiveSource] = []
    var parses: [ParseInterface] = []
    var settings: AppSettings = AppSettings()
}

struct HeroSpotlightItem: Identifiable, Equatable {
    var id: String { "\(sourceId)_\(vodId)_\(isContinueWatching)" }
    let vodId: String
    let sourceId: String
    let name: String
    let pic: String
    let badgeText: String
    let subtitle: String
    let buttonText: String
    let isContinueWatching: Bool
    let progressFraction: Double?
}

enum TimeFormatter {
    static func formatMs(_ ms: Int64) -> String {
        let totalSec = max(0, Int(ms / 1000))
        let h = totalSec / 3600
        let m = (totalSec % 3600) / 60
        let s = totalSec % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%02d:%02d", m, s)
    }
}

// Google Material Design 3 Accent Tokens
extension Color {
    static let googleBlue = Color(red: 0.102, green: 0.451, blue: 0.910)      // #1A73E8
    static let googleBlueSoft = Color(red: 0.824, green: 0.890, blue: 0.988)  // #D2E3FC
    static let googleGreen = Color(red: 0.094, green: 0.502, blue: 0.220)     // #188038
    static let googleYellow = Color(red: 0.984, green: 0.737, blue: 0.016)    // #FBBC04
}
