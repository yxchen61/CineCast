import Foundation

struct TvBoxImportResult {
    let subscription: SubscriptionConfig
    let sources: [VideoSource]
    let liveSources: [LiveSource]
    let parses: [ParseInterface]
    let adHosts: [String]
    let totalRawSites: Int
    let directPlayableCount: Int
}

final class TvBoxConfigParser {
    static let shared = TvBoxConfigParser()

    func importFromUrl(_ urlString: String, customName: String = "") async throws -> TvBoxImportResult {
        let raw = try await NetworkService.shared.fetchString(url: urlString)
        return try parseJsonText(raw, baseUrl: urlString, customName: customName)
    }

    func parseJsonText(_ rawText: String, baseUrl: String = "", customName: String = "") throws -> TvBoxImportResult {
        let cleaned = stripJsonComments(rawText)
        guard let data = cleaned.data(using: .utf8),
              let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw NSError(domain: "CineCast", code: -1, userInfo: [NSLocalizedDescriptionKey: "JSON 格式无效"])
        }

        let subName: String = {
            if !customName.isEmpty { return customName }
            if let u = URL(string: baseUrl), let host = u.host {
                let last = u.lastPathComponent.isEmpty ? "TVBox配置" : u.lastPathComponent
                return "\(host) / \(last)"
            }
            return "本地导入配置"
        }()

        // 1. Sites
        let sitesArr = root["sites"] as? [[String: Any]] ?? []
        var sources: [VideoSource] = []
        var directCount = 0

        for (i, s) in sitesArr.enumerated() {
            let key = (s["key"] as? String) ?? "site_\(i)"
            let name = ((s["name"] as? String) ?? key).trimmingCharacters(in: .whitespacesAndNewlines)
            let typeInt = (s["type"] as? Int) ?? 1
            let api = ((s["api"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let searchable = ((s["searchable"] as? Int) ?? 1) != 0
            let categories = s["categories"] as? [String] ?? []
            let resolvedApi = resolveRelative(base: baseUrl, target: api)

            let sourceType: SourceType
            switch typeInt {
            case 0: sourceType = .MACCMS_XML
            case 1: sourceType = .MACCMS_JSON
            case 4: sourceType = .T4_API
            default:
                sourceType = (resolvedApi.hasPrefix("http") && !resolvedApi.lowercased().hasSuffix(".js"))
                    ? .WEB_SNIFFER : .TVBOX_SPIDER
            }
            if sourceType.isDirectApi { directCount += 1 }
            if !api.isEmpty {
                sources.append(VideoSource(
                    id: "sub_\(abs(key.hashValue))_\(i)",
                    name: name,
                    type: sourceType,
                    apiUrl: resolvedApi,
                    searchable: searchable,
                    enabled: sourceType != .TVBOX_SPIDER,
                    group: subName,
                    categories: categories,
                    subscriptionUrl: baseUrl
                ))
            }
        }

        // 2. Lives
        let livesArr = root["lives"] as? [[String: Any]] ?? []
        var lives: [LiveSource] = []
        for (i, l) in livesArr.enumerated() {
            let lName = (l["name"] as? String) ?? (l["group"] as? String) ?? "直播源 \(i + 1)"
            let lUrl = resolveRelative(base: baseUrl, target: (l["url"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines))
            let lEpg = (l["epg"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if lUrl.hasPrefix("http") {
                lives.append(LiveSource(id: "live_\(abs(lUrl.hashValue))", name: lName, url: lUrl, epg: lEpg))
            }
        }

        // 3. Parses
        let parsesArr = root["parses"] as? [[String: Any]] ?? []
        var parses: [ParseInterface] = []
        for (i, p) in parsesArr.enumerated() {
            let pName = (p["name"] as? String) ?? "解析 \(i + 1)"
            let pType = (p["type"] as? Int) ?? 0
            let pUrl = (p["url"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if pUrl.hasPrefix("http") {
                parses.append(ParseInterface(name: pName, type: pType, url: pUrl))
            }
        }

        // 4. Ads
        let adHosts = (root["ads"] as? [String] ?? []).map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }

        let sub = SubscriptionConfig(
            id: baseUrl.isEmpty ? UUID().uuidString : "sub_\(abs(baseUrl.hashValue))",
            name: subName,
            url: baseUrl,
            lastSyncedAt: Int64(Date().timeIntervalSince1970 * 1000),
            siteCount: sources.count,
            liveCount: lives.count,
            parseCount: parses.count
        )

        return TvBoxImportResult(
            subscription: sub,
            sources: sources,
            liveSources: lives,
            parses: parses,
            adHosts: adHosts,
            totalRawSites: sitesArr.count,
            directPlayableCount: directCount
        )
    }

    private func resolveRelative(base: String, target: String) -> String {
        guard target.hasPrefix("."), let baseURL = URL(string: base) else { return target }
        return URL(string: target, relativeTo: baseURL)?.absoluteString ?? target
    }

    private func stripJsonComments(_ input: String) -> String {
        var result = ""
        result.reserveCapacity(input.count)
        let chars = Array(input)
        var i = 0
        var inString = false
        var escaped = false

        while i < chars.count {
            let c = chars[i]
            if inString {
                result.append(c)
                if escaped { escaped = false }
                else if c == "\\" { escaped = true }
                else if c == "\"" { inString = false }
                i += 1
            } else {
                if c == "\"" {
                    inString = true
                    result.append(c)
                    i += 1
                } else if c == "/" && i + 1 < chars.count && chars[i + 1] == "/" {
                    while i < chars.count && chars[i] != "\n" { i += 1 }
                } else if c == "/" && i + 1 < chars.count && chars[i + 1] == "*" {
                    i += 2
                    while i + 1 < chars.count && !(chars[i] == "*" && chars[i + 1] == "/") { i += 1 }
                    i += 2
                } else {
                    result.append(c)
                    i += 1
                }
            }
        }
        return result.replacingOccurrences(of: ",\\s*([}\\]])", with: "$1", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
