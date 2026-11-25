import Foundation

final class LiveParser {
    static let shared = LiveParser()

    func fetchChannels(url: String) async throws -> [LiveChannel] {
        let raw = try await NetworkService.shared.fetchString(url: url, timeout: 10)
        return parse(raw)
    }

    func parse(_ raw: String) -> [LiveChannel] {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return [] }
        if trimmed.localizedCaseInsensitiveContains("#EXTM3U") || trimmed.localizedCaseInsensitiveContains("#EXTINF") {
            return parseM3u(trimmed)
        }
        return parseTvBoxTxt(trimmed)
    }

    private func parseM3u(_ raw: String) -> [LiveChannel] {
        var order: [String] = []
        var map: [String: (group: String, name: String, logo: String, urls: [String])] = [:]
        var curGroup = "默认频道"
        var curName = ""
        var curLogo = ""

        for line in raw.components(separatedBy: .newlines) {
            let t = line.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !t.isEmpty else { continue }
            if t.uppercased().hasPrefix("#EXTINF") {
                curGroup = extractAttr(t, key: "group-title") ?? "默认频道"
                if curGroup.isEmpty { curGroup = "默认频道" }
                curLogo = extractAttr(t, key: "tvg-logo") ?? ""
                if let comma = t.lastIndex(of: ",") {
                    curName = String(t[t.index(after: comma)...]).trimmingCharacters(in: .whitespacesAndNewlines)
                }
            } else if !t.hasPrefix("#") && (t.lowercased().hasPrefix("http") || t.lowercased().hasPrefix("rtmp")) {
                let name = curName.isEmpty ? "直播频道" : curName
                let key = "\(curGroup)|\(name)"
                if map[key] == nil {
                    order.append(key)
                    map[key] = (curGroup, name, curLogo, [t])
                } else if !map[key]!.urls.contains(t) {
                    map[key]!.urls.append(t)
                }
            }
        }
        return order.compactMap { key in
            guard let v = map[key] else { return nil }
            return LiveChannel(group: v.group, name: v.name, urls: v.urls, logo: v.logo)
        }
    }

    private func parseTvBoxTxt(_ raw: String) -> [LiveChannel] {
        var order: [String] = []
        var map: [String: (group: String, name: String, urls: [String])] = [:]
        var curGroup = "精选频道"

        for line in raw.components(separatedBy: .newlines) {
            let t = line.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !t.isEmpty && !t.hasPrefix("#") else { continue }
            if t.localizedCaseInsensitiveContains(",#genre#") {
                let g = t.components(separatedBy: ",#genre#").first?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                if !g.isEmpty { curGroup = g }
                continue
            }
            guard let comma = t.firstIndex(of: ",") else { continue }
            let name = String(t[..<comma]).trimmingCharacters(in: .whitespacesAndNewlines)
            let urlPart = String(t[t.index(after: comma)...]).trimmingCharacters(in: .whitespacesAndNewlines)
            let urls = urlPart.components(separatedBy: "#")
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { $0.lowercased().hasPrefix("http") || $0.lowercased().hasPrefix("rtmp") }
            guard !name.isEmpty && !urls.isEmpty else { continue }
            let key = "\(curGroup)|\(name)"
            if map[key] == nil {
                order.append(key)
                map[key] = (curGroup, name, urls)
            } else {
                for u in urls where !map[key]!.urls.contains(u) {
                    map[key]!.urls.append(u)
                }
            }
        }
        return order.compactMap { key in
            guard let v = map[key] else { return nil }
            return LiveChannel(group: v.group, name: v.name, urls: v.urls, logo: "")
        }
    }

    private func extractAttr(_ line: String, key: String) -> String? {
        let pattern = "\(key)=\"([^\"]*)\""
        guard let range = line.range(of: pattern, options: .regularExpression) else { return nil }
        let matched = String(line[range])
        return matched.replacingOccurrences(of: "\(key)=\"", with: "").replacingOccurrences(of: "\"", with: "")
    }
}
