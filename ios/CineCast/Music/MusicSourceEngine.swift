import Foundation

struct MusicImportResult {
    let subscription: MusicSubscription
    let sources: [MusicSource]
    let totalRawCount: Int
}

final class MusicSourceEngine {
    static let shared = MusicSourceEngine()
    private init() {}

    func fetchChart(source: MusicSource, chart: MusicChartCategory) async throws -> [MusicTrack] {
        let raw: [MusicTrack]
        switch source.sourceType {
        case .BILIBILI_MUSIC:
            raw = try await searchBilibiliMusic(source: source, keyword: chart.idOrQuery)
        case .VKEYS_API:
            raw = try await searchVkeys(source: source, keyword: chart.idOrQuery)
        case .KUWO_DIRECT:
            raw = try await fetchKuwoBang(source: source, bangId: chart.idOrQuery)
        case .METING_API:
            raw = try await fetchMetingPlaylist(source: source, playlistId: chart.idOrQuery)
        case .GDSTUDIO_API:
            raw = try await searchGdStudio(source: source, keyword: chart.idOrQuery)
        case .AUDIUS_API:
            raw = try await fetchAudius(source: source, genre: chart.idOrQuery)
        case .RADIO_BROWSER:
            return try await fetchRadio(source: source, category: chart.idOrQuery)
        case .M3U_AUDIO:
            return try await fetchM3u(source: source, url: chart.idOrQuery.isEmpty ? source.baseUrl : chart.idOrQuery)
        }
        return Self.filterFullVocalTracks(raw, query: chart.title)
    }

    func search(source: MusicSource, keyword: String) async throws -> [MusicTrack] {
        let q = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return [] }
        let raw: [MusicTrack]
        switch source.sourceType {
        case .BILIBILI_MUSIC:
            raw = try await searchBilibiliMusic(source: source, keyword: q)
        case .VKEYS_API:
            raw = try await searchVkeys(source: source, keyword: q)
        case .KUWO_DIRECT:
            raw = try await searchVkeys(source: MusicSource(id: source.id, name: source.name, baseUrl: "https://api.vkeys.cn/v2/music/tencent", sourceType: .VKEYS_API), keyword: q)
        case .GDSTUDIO_API:
            raw = try await searchGdStudio(source: source, keyword: q)
        case .AUDIUS_API:
            let enc = q.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? q
            let base = source.baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            let body = try await NetworkService.shared.fetchString(url: "\(base)/tracks/search?query=\(enc)&limit=25&app_name=CineCast")
            raw = parseAudius(source: source, raw: body)
        case .RADIO_BROWSER:
            let enc = q.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? q
            let base = source.baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            let body = try await NetworkService.shared.fetchString(url: "\(base)/stations/byname/\(enc)?limit=25&order=votes&reverse=true&hidebroken=true")
            return parseRadio(source: source, raw: body)
        case .METING_API:
            guard let first = source.charts.first else { return [] }
            let list = try await fetchMetingPlaylist(source: source, playlistId: first.idOrQuery)
            raw = list.filter { $0.title.localizedCaseInsensitiveContains(q) || $0.artist.localizedCaseInsensitiveContains(q) }
        case .M3U_AUDIO:
            let list = try await fetchM3u(source: source, url: source.baseUrl)
            return list.filter { $0.title.localizedCaseInsensitiveContains(q) || $0.artist.localizedCaseInsensitiveContains(q) }
        }
        return Self.filterFullVocalTracks(raw, query: q)
    }

    static func filterFullVocalTracks(_ tracks: [MusicTrack], query: String = "") -> [MusicTrack] {
        let qLower = query.lowercased()
        let wantsAcc = ["伴奏", "ktv", "消音", "karaoke", "backing", "off vocal"].contains { qLower.contains($0) }
        let blocked = ["伴奏", "ktv", "消音", "纯伴奏", "无主唱", "无人声", "去人声", "karaoke", "backing track", "off vocal", "试听版", "试听片段", "铃声版"]
        return tracks.filter { t in
            if t.isLiveRadio { return true }
            if t.durationMs > 0 && t.durationMs <= 65_000 { return false }
            if !wantsAcc {
                let text = "\(t.title) \(t.album)".lowercased()
                if blocked.contains(where: { text.contains($0) }) { return false }
            }
            return true
        }
    }

    static let bilibiliHeaders: [String: String] = [
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Referer": "https://www.bilibili.com/",
        "Origin": "https://www.bilibili.com",
        "Cookie": "buvid3=11111111-2222-3333-4444-555555555555infoc"
    ]

    func resolvePlayableTrack(source: MusicSource, track: MusicTrack) async -> MusicTrack {
        var out = track
        if source.sourceType == .BILIBILI_MUSIC {
            let base = source.baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            if out.streamUrl.isEmpty,
               let viewRaw = try? await NetworkService.shared.fetchString(url: "\(base)/x/web-interface/view?bvid=\(track.id)", headers: Self.bilibiliHeaders),
               let vData = viewRaw.data(using: .utf8),
               let vObj = try? JSONSerialization.jsonObject(with: vData) as? [String: Any],
               let d = vObj["data"] as? [String: Any],
               let cid = d["cid"] as? Int64 {
                if let playRaw = try? await NetworkService.shared.fetchString(url: "\(base)/x/player/playurl?bvid=\(track.id)&cid=\(cid)&fnval=16&otype=json", headers: Self.bilibiliHeaders),
                   let pData = playRaw.data(using: .utf8),
                   let pObj = try? JSONSerialization.jsonObject(with: pData) as? [String: Any],
                   let pd = pObj["data"] as? [String: Any],
                   let dash = pd["dash"] as? [String: Any],
                   let audios = dash["audio"] as? [[String: Any]],
                   let best = audios.max(by: { ($0["bandwidth"] as? Int ?? 0) < ($1["bandwidth"] as? Int ?? 0) }),
                   let u = (best["baseUrl"] as? String) ?? (best["base_url"] as? String) {
                    out.streamUrl = u
                }
            }
            return out
        }
        if source.sourceType == .VKEYS_API {
            let base = source.baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            if out.streamUrl.isEmpty,
               let raw = try? await NetworkService.shared.fetchString(url: "\(base)?id=\(track.id)&quality=8"),
               let data = raw.data(using: .utf8),
               let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let d = obj["data"] as? [String: Any],
               let u = d["url"] as? String {
                out.streamUrl = u
            }
            if out.rawLyric.isEmpty,
               let raw = try? await NetworkService.shared.fetchString(url: "\(base)/lyric?id=\(track.id)"),
               let data = raw.data(using: .utf8),
               let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let d = obj["data"] as? [String: Any],
               let lrc = d["lrc"] as? String {
                out.rawLyric = lrc
            }
            return out
        }
        if source.sourceType == .KUWO_DIRECT {
            if out.streamUrl.isEmpty,
               let u = try? await NetworkService.shared.fetchString(url: "http://antiserver.kuwo.cn/anti.s?type=convert_url&rid=MUSIC_\(track.id)&format=mp3&response=url") {
                out.streamUrl = u.trimmingCharacters(in: .whitespacesAndNewlines)
            }
            return out
        }
        if source.sourceType == .GDSTUDIO_API {
            let base = source.baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            let plat = track.platform.isEmpty ? source.platform : track.platform
            if out.streamUrl.isEmpty, !track.urlId.isEmpty {
                let enc = track.urlId.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? track.urlId
                if let raw = try? await NetworkService.shared.fetchString(url: "\(base)?types=url&source=\(plat)&id=\(enc)&br=320"),
                   let data = raw.data(using: .utf8),
                   let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                   let u = obj["url"] as? String {
                    out.streamUrl = u
                }
            }
            if out.coverUrl.isEmpty, !track.picId.isEmpty {
                let enc = track.picId.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? track.picId
                if let raw = try? await NetworkService.shared.fetchString(url: "\(base)?types=pic&source=\(plat)&id=\(enc)&size=500"),
                   let data = raw.data(using: .utf8),
                   let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                   let u = obj["url"] as? String {
                    out.coverUrl = u
                }
            }
            if out.rawLyric.isEmpty, !track.lyricId.isEmpty {
                let enc = track.lyricId.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? track.lyricId
                if let raw = try? await NetworkService.shared.fetchString(url: "\(base)?types=lyric&source=\(plat)&id=\(enc)"),
                   let data = raw.data(using: .utf8),
                   let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                   let lrc = obj["lyric"] as? String {
                    out.rawLyric = lrc
                }
            }
        } else if source.sourceType == .METING_API, out.rawLyric.isEmpty, out.lyricUrl.hasPrefix("http") {
            if let lrc = try? await NetworkService.shared.fetchString(url: out.lyricUrl) {
                out.rawLyric = lrc
            }
        }
        return out
    }

    func importFromUrl(_ url: String) async throws -> MusicImportResult {
        let raw = try await NetworkService.shared.fetchString(url: url, timeout: 18)
        return try parseImportText(raw, sourceUrl: url)
    }

    func parseImportText(_ rawText: String, sourceUrl: String = "") throws -> MusicImportResult {
        let trimmed = rawText.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.uppercased().hasPrefix("#EXTM3U") || sourceUrl.lowercased().hasSuffix(".m3u") {
            let src = MusicSource(id: "m3u_\(abs(sourceUrl.hashValue))", name: "导入 M3U 电台", baseUrl: sourceUrl, sourceType: .M3U_AUDIO, charts: [MusicChartCategory(title: "全部频道", idOrQuery: sourceUrl)], subscriptionUrl: sourceUrl)
            let sub = MusicSubscription(id: "msub_\(abs(sourceUrl.hashValue))", name: "M3U 音频源", url: sourceUrl, importedCount: 1)
            return MusicImportResult(subscription: sub, sources: [src], totalRawCount: 1)
        }
        guard let data = trimmed.data(using: .utf8),
              let parsed = try? JSONSerialization.jsonObject(with: data) else {
            throw NSError(domain: "MusicParser", code: -1, userInfo: [NSLocalizedDescriptionKey: "JSON 格式错误"])
        }
        var results: [MusicSource] = []
        var total = 0
        if let dict = parsed as? [String: Any], let plugins = dict["plugins"] as? [[String: Any]] {
            total = plugins.count
            for p in plugins {
                let name = (p["name"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                let lower = name.lowercased()
                if lower.contains("网易") || lower.contains("小芸") || lower.contains("netease") {
                    results.append(MusicSource(id: "mf_\(abs(name.hashValue))", name: "\(name) (MusicFree)", baseUrl: "https://music-api.gdstudio.xyz/api.php", sourceType: .GDSTUDIO_API, platform: "netease", group: "GitHub MusicFree 订阅", charts: [MusicChartCategory(title: "华语热歌", idOrQuery: "热门")], subscriptionUrl: sourceUrl))
                } else if lower.contains("小秋") || lower.contains("joox") {
                    results.append(MusicSource(id: "mf_\(abs(name.hashValue))", name: "\(name) (MusicFree)", baseUrl: "https://api.injahow.cn/meting/", sourceType: .METING_API, platform: "netease", group: "GitHub MusicFree 订阅", charts: [MusicChartCategory(title: "热歌榜", idOrQuery: "3778678")], subscriptionUrl: sourceUrl))
                } else if lower.contains("电台") || lower.contains("radio") {
                    results.append(MusicSource(id: "mf_\(abs(name.hashValue))", name: "\(name) (MusicFree)", baseUrl: "https://de1.api.radio-browser.info/json", sourceType: .RADIO_BROWSER, platform: "cn", group: "GitHub MusicFree 订阅", charts: [MusicChartCategory(title: "华语音乐台", idOrQuery: "cn")], subscriptionUrl: sourceUrl))
                }
            }
        } else if let arr = parsed as? [[String: Any]] {
            total = arr.count
            for o in arr {
                let name = (o["name"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                let url = (o["baseUrl"] as? String ?? o["url"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                guard !name.isEmpty, url.hasPrefix("http") else { continue }
                let typeStr = (o["sourceType"] as? String ?? "").uppercased()
                let t: MusicSourceType = typeStr.contains("GDSTUDIO") ? .GDSTUDIO_API : (typeStr.contains("AUDIUS") ? .AUDIUS_API : (typeStr.contains("RADIO") ? .RADIO_BROWSER : .METING_API))
                results.append(MusicSource(id: "msrc_\(abs((url+name).hashValue))", name: name, baseUrl: url, sourceType: t, platform: (o["platform"] as? String ?? "netease"), group: "GitHub 导入音乐源", charts: [MusicChartCategory(title: "热歌榜", idOrQuery: t == .METING_API ? "3778678" : "热门")], subscriptionUrl: sourceUrl))
            }
        }
        let sub = MusicSubscription(id: "msub_\(abs(sourceUrl.hashValue))", name: sourceUrl.isEmpty ? "本地粘贴源" : (URL(string: sourceUrl)?.host ?? "GitHub 音乐源"), url: sourceUrl, lastSyncedAt: Int64(Date().timeIntervalSince1970 * 1000), importedCount: results.count)
        return MusicImportResult(subscription: sub, sources: results, totalRawCount: total)
    }

    // MARK: - Private Fetchers

    private func searchBilibiliMusic(source: MusicSource, keyword: String) async throws -> [MusicTrack] {
        let enc = keyword.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? keyword
        let base = source.baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let raw = try await NetworkService.shared.fetchString(
            url: "\(base)/x/web-interface/search/type?search_type=video&keyword=\(enc)&page=1",
            headers: Self.bilibiliHeaders
        )
        guard let data = raw.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let d = obj["data"] as? [String: Any],
              let arr = d["result"] as? [[String: Any]] else { return [] }
        return arr.compactMap { o in
            let bvid = (o["bvid"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let rawTitle = (o["title"] as? String ?? "")
                .replacingOccurrences(of: #"<[^>]+>"#, with: "", options: .regularExpression)
                .trimmingCharacters(in: .whitespacesAndNewlines)
            guard !bvid.isEmpty, !rawTitle.isEmpty else { return nil }
            let parts = (o["duration"] as? String ?? "").split(separator: ":").compactMap { Int64($0) }
            let durMs: Int64 = parts.count == 2 ? (parts[0] * 60 + parts[1]) * 1000 : (parts.count == 3 ? (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000 : 0)
            if durMs > 0 && (durMs < 75_000 || durMs > 720_000) { return nil }
            let rawPic = (o["pic"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let cover = rawPic.hasPrefix("//") ? "https:\(rawPic)" : rawPic
            return MusicTrack(id: bvid, sourceId: source.id, sourceName: source.name, title: rawTitle, artist: (o["author"] as? String ?? "B站音乐"), album: "B站全曲", coverUrl: cover, durationMs: durMs, platform: "bilibili")
        }
    }

    private func searchVkeys(source: MusicSource, keyword: String) async throws -> [MusicTrack] {
        let enc = keyword.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? keyword
        let base = source.baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let raw = try await NetworkService.shared.fetchString(url: "\(base)?word=\(enc)&num=25")
        guard let data = raw.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let arr = obj["data"] as? [[String: Any]] else { return [] }
        return arr.compactMap { o in
            let id = "\(o["id"] ?? "")"
            let title = (o["song"] as? String ?? o["name"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !id.isEmpty, !title.isEmpty else { return nil }
            return MusicTrack(id: id, sourceId: source.id, sourceName: source.name, title: title, artist: (o["singer"] as? String ?? "未知歌手"), album: (o["album"] as? String ?? ""), coverUrl: (o["cover"] as? String ?? ""), platform: source.platform)
        }
    }

    private func fetchKuwoBang(source: MusicSource, bangId: String) async throws -> [MusicTrack] {
        let raw = try await NetworkService.shared.fetchString(url: "http://kbangserver.kuwo.cn/ksong.s?from=pc&fmt=json&type=bang&data=content&id=\(bangId)&rn=35&pn=0")
        guard let data = raw.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let arr = obj["musiclist"] as? [[String: Any]] else { return [] }
        return arr.compactMap { o in
            let id = "\(o["id"] ?? "")"
            let title = (o["name"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !id.isEmpty, !title.isEmpty else { return nil }
            let picShort = (o["web_albumpic_short"] as? String ?? "")
            let cover = picShort.hasPrefix("http") ? picShort : (picShort.isEmpty ? "" : "https://img4.kuwo.cn/star/albumcover/\(picShort)")
            return MusicTrack(id: id, sourceId: source.id, sourceName: source.name, title: title, artist: (o["artist"] as? String ?? "未知歌手"), album: (o["album"] as? String ?? ""), coverUrl: cover, platform: "kuwo")
        }
    }

    private func fetchMetingPlaylist(source: MusicSource, playlistId: String) async throws -> [MusicTrack] {
        let sep = source.baseUrl.contains("?") ? "&" : "?"
        let raw = try await NetworkService.shared.fetchString(url: "\(source.baseUrl)\(sep)server=\(source.platform)&type=playlist&id=\(playlistId)")
        guard let data = raw.data(using: .utf8), let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        return arr.prefix(50).enumerated().compactMap { idx, o in
            let title = (o["name"] as? String ?? o["title"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let url = (o["url"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !title.isEmpty, !url.isEmpty else { return nil }
            return MusicTrack(id: "\(playlistId)_\(idx)", sourceId: source.id, sourceName: source.name, title: title, artist: (o["artist"] as? String ?? o["author"] as? String ?? "未知歌手"), coverUrl: (o["pic"] as? String ?? ""), streamUrl: url, lyricUrl: (o["lrc"] as? String ?? ""), platform: source.platform)
        }
    }

    private func searchGdStudio(source: MusicSource, keyword: String) async throws -> [MusicTrack] {
        let enc = keyword.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? keyword
        let base = source.baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let raw = try await NetworkService.shared.fetchString(url: "\(base)?types=search&source=\(source.platform)&name=\(enc)&count=25&pages=1")
        guard let data = raw.data(using: .utf8), let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        return arr.compactMap { o in
            let id = "\(o["id"] ?? "")"
            let title = (o["name"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !id.isEmpty, !title.isEmpty else { return nil }
            let artist: String
            if let a = o["artist"] as? [String] { artist = a.joined(separator: " / ") }
            else { artist = (o["artist"] as? String) ?? "未知歌手" }
            return MusicTrack(id: id, sourceId: source.id, sourceName: source.name, title: title, artist: artist, album: (o["album"] as? String ?? ""), platform: (o["source"] as? String ?? source.platform), picId: "\(o["pic_id"] ?? "")", urlId: "\(o["url_id"] ?? id)", lyricId: "\(o["lyric_id"] ?? id)")
        }
    }

    private func fetchAudius(source: MusicSource, genre: String) async throws -> [MusicTrack] {
        let base = source.baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let url = genre.lowercased() == "trending"
            ? "\(base)/tracks/trending?limit=30&app_name=CineCast"
            : "\(base)/tracks/trending?genre=\(genre.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? genre)&limit=30&app_name=CineCast"
        let raw = try await NetworkService.shared.fetchString(url: url)
        return parseAudius(source: source, raw: raw)
    }

    private func parseAudius(source: MusicSource, raw: String) -> [MusicTrack] {
        let base = source.baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let data = raw.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let arr = obj["data"] as? [[String: Any]] else { return [] }
        return arr.compactMap { o in
            guard let id = o["id"] as? String, let title = o["title"] as? String else { return nil }
            let artist = (o["user"] as? [String: Any])?["name"] as? String ?? "Independent Artist"
            let art = (o["artwork"] as? [String: Any])?["480x480"] as? String ?? ""
            let dur = Int64((o["duration"] as? Int ?? 0) * 1000)
            return MusicTrack(id: id, sourceId: source.id, sourceName: source.name, title: title, artist: artist, coverUrl: art, streamUrl: "\(base)/tracks/\(id)/stream?app_name=CineCast", durationMs: dur, platform: "audius")
        }
    }

    private func fetchRadio(source: MusicSource, category: String) async throws -> [MusicTrack] {
        let base = source.baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let url: String
        switch category.lowercased() {
        case "cn": url = "\(base)/stations/bycountrycodeexact/CN?limit=30&order=votes&reverse=true&hidebroken=true"
        case "top": url = "\(base)/stations/topvote/30?hidebroken=true"
        default: url = "\(base)/stations/bytag/\(category)?limit=30&order=votes&reverse=true&hidebroken=true"
        }
        return parseRadio(source: source, raw: try await NetworkService.shared.fetchString(url: url))
    }

    private func parseRadio(source: MusicSource, raw: String) -> [MusicTrack] {
        guard let data = raw.data(using: .utf8), let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        return arr.compactMap { o in
            let name = (o["name"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let stream = (o["url_resolved"] as? String ?? o["url"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty, stream.hasPrefix("http") else { return nil }
            return MusicTrack(id: (o["stationuuid"] as? String ?? stream), sourceId: source.id, sourceName: source.name, title: name, artist: (o["country"] as? String ?? "在线电台"), coverUrl: (o["favicon"] as? String ?? ""), streamUrl: stream, platform: "radio", isLiveRadio: true)
        }
    }

    private func fetchM3u(source: MusicSource, url: String) async throws -> [MusicTrack] {
        let raw = try await NetworkService.shared.fetchString(url: url)
        var list: [MusicTrack] = []
        var title = ""
        for line in raw.components(separatedBy: .newlines) {
            let t = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if t.hasPrefix("#EXTINF") { title = t.components(separatedBy: ",").last ?? "" }
            else if t.hasPrefix("http") {
                list.append(MusicTrack(id: "m3u_\(list.count)", sourceId: source.id, sourceName: source.name, title: title.isEmpty ? "电台 \(list.count+1)" : title, artist: "M3U 音频源", streamUrl: t, isLiveRadio: true))
                title = ""
            }
        }
        return list
    }

    static func parseLrc(_ raw: String) -> [LrcLine] {
        guard !raw.isEmpty, let regex = try? NSRegularExpression(pattern: #"\[(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?\]"#) else { return [] }
        var out: [LrcLine] = []
        for line in raw.components(separatedBy: .newlines) {
            let ns = line as NSString
            let matches = regex.matches(in: line, range: NSRange(location: 0, length: ns.length))
            guard !matches.isEmpty else { continue }
            let text = regex.stringByReplacingMatches(in: line, range: NSRange(location: 0, length: ns.length), withTemplate: "").trimmingCharacters(in: .whitespaces)
            guard !text.isEmpty else { continue }
            for m in matches {
                let min = Int64(ns.substring(with: m.range(at: 1))) ?? 0
                let sec = Int64(ns.substring(with: m.range(at: 2))) ?? 0
                out.append(LrcLine(timeMs: min * 60_000 + sec * 1000, text: text))
            }
        }
        return out.sorted { $0.timeMs < $1.timeMs }
    }
}
