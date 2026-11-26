import Foundation

enum MusicSourceType: String, Codable, CaseIterable, Identifiable {
    case VKEYS_API
    case KUWO_DIRECT
    case METING_API
    case GDSTUDIO_API
    case AUDIUS_API
    case RADIO_BROWSER
    case M3U_AUDIO

    var id: String { rawValue }
    var badge: String {
        switch self {
        case .VKEYS_API: return "极速"
        case .KUWO_DIRECT: return "酷我直连"
        case .METING_API: return "Meting"
        case .GDSTUDIO_API: return "GD API"
        case .AUDIUS_API: return "Audius"
        case .RADIO_BROWSER: return "电台"
        case .M3U_AUDIO: return "M3U"
        }
    }
}

struct MusicChartCategory: Codable, Identifiable, Equatable {
    var id: String { "\(title)_\(idOrQuery)" }
    var title: String
    var idOrQuery: String
}

enum ArtistCategory: String, CaseIterable, Identifiable {
    case all = "全部热门"
    case cnMale = "华语男歌手"
    case cnFemale = "华语女歌手"
    case cnGroup = "华语组合"
    case western = "欧美流行"
    case jpKr = "日韩歌手"
    case instrumental = "轻音乐/纯音"

    var id: String { rawValue }
}

struct MusicArtistProfile: Identifiable {
    var id: String { "\(category.rawValue)_\(name)" }
    let name: String
    let category: ArtistCategory
    let tag: String
}

struct MusicSource: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var baseUrl: String
    var sourceType: MusicSourceType = .METING_API
    var platform: String = "netease"
    var group: String = "开源音乐源"
    var enabled: Bool = true
    var searchable: Bool = true
    var charts: [MusicChartCategory] = []
    var comment: String = ""
    var subscriptionUrl: String = ""
    var latencyMs: Int? = nil
}

struct MusicSubscription: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var url: String
    var repoHint: String = ""
    var lastSyncedAt: Int64 = 0
    var importedCount: Int = 0
}

struct MusicTrack: Codable, Identifiable, Equatable {
    var id: String
    var sourceId: String
    var sourceName: String
    var title: String
    var artist: String = "未知歌手"
    var album: String = ""
    var coverUrl: String = ""
    var streamUrl: String = ""
    var lyricUrl: String = ""
    var rawLyric: String = ""
    var durationMs: Int64 = 0
    var platform: String = ""
    var picId: String = ""
    var urlId: String = ""
    var lyricId: String = ""
    var isLiveRadio: Bool = false

    var uniqueKey: String { "\(sourceId)::\(id)" }
}

struct LrcLine: Identifiable, Equatable {
    var id: String { "\(timeMs)_\(text)" }
    let timeMs: Int64
    let text: String
}

struct MusicPlaylist: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var tracks: [MusicTrack] = []
    var createdAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
}
