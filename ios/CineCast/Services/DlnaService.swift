import Foundation
import Darwin

struct DlnaDevice: Identifiable, Equatable {
    var id: String { udn }
    let udn: String
    let friendlyName: String
    let manufacturer: String
    let location: String
    let avTransportControlUrl: String
    let renderingControlUrl: String?
}

struct CastSessionState: Equatable {
    var connectedDevice: DlnaDevice? = nil
    var mediaTitle: String = ""
    var mediaUrl: String = ""
    var transportState: String = "STOPPED"
    var positionMs: Int64 = 0
    var durationMs: Int64 = 0
    var volume: Int = 50
    var errorMessage: String? = nil
    var isBusy: Bool = false
}

@MainActor
final class DlnaService: ObservableObject {
    static let shared = DlnaService()

    @Published var devices: [DlnaDevice] = []
    @Published var isScanning: Bool = false
    @Published var session: CastSessionState = CastSessionState()

    private var scanTask: Task<Void, Never>?
    private var pollTask: Task<Void, Never>?

    func startDiscovery(timeoutSeconds: Double = 4.5) {
        scanTask?.cancel()
        scanTask = Task {
            self.isScanning = true
            let discoveredLocations = await Task.detached(priority: .utility) {
                Self.performSsdpSearch(timeoutSeconds: timeoutSeconds)
            }.value

            var found: [String: DlnaDevice] = [:]
            await withTaskGroup(of: DlnaDevice?.self) { group in
                for loc in discoveredLocations {
                    group.addTask {
                        await Self.fetchDeviceDescription(location: loc)
                    }
                }
                for await dev in group {
                    if let dev {
                        found[dev.udn] = dev
                    }
                }
            }
            self.devices = Array(found.values).sorted { $0.friendlyName < $1.friendlyName }
            self.isScanning = false
        }
    }

    func castMedia(device: DlnaDevice, mediaUrl: String, title: String) {
        Task {
            self.session.connectedDevice = device
            self.session.mediaTitle = title
            self.session.mediaUrl = mediaUrl
            self.session.isBusy = true
            self.session.errorMessage = nil
            self.session.transportState = "TRANSITIONING"
            do {
                _ = try? await Self.sendSoap(
                    controlUrl: device.avTransportControlUrl,
                    serviceType: "urn:schemas-upnp-org:service:AVTransport:1",
                    action: "Stop",
                    innerArgs: "<InstanceID>0</InstanceID>"
                )
                let metadata = Self.buildDidlLite(title: title, url: mediaUrl)
                let setArgs = """
                <InstanceID>0</InstanceID>
                <CurrentURI>\(Self.escapeXml(mediaUrl))</CurrentURI>
                <CurrentURIMetaData>\(Self.escapeXml(metadata))</CurrentURIMetaData>
                """
                _ = try await Self.sendSoap(
                    controlUrl: device.avTransportControlUrl,
                    serviceType: "urn:schemas-upnp-org:service:AVTransport:1",
                    action: "SetAVTransportURI",
                    innerArgs: setArgs
                )
                _ = try await Self.sendSoap(
                    controlUrl: device.avTransportControlUrl,
                    serviceType: "urn:schemas-upnp-org:service:AVTransport:1",
                    action: "Play",
                    innerArgs: "<InstanceID>0</InstanceID><Speed>1</Speed>"
                )
                self.session.isBusy = false
                self.session.transportState = "PLAYING"
                self.startPolling(device: device)
            } catch {
                self.session.isBusy = false
                self.session.transportState = "STOPPED"
                self.session.errorMessage = "DLNA 投屏失败：\(error.localizedDescription)"
            }
        }
    }

    func play() {
        guard let device = session.connectedDevice else { return }
        Task {
            do {
                _ = try await Self.sendSoap(
                    controlUrl: device.avTransportControlUrl,
                    serviceType: "urn:schemas-upnp-org:service:AVTransport:1",
                    action: "Play",
                    innerArgs: "<InstanceID>0</InstanceID><Speed>1</Speed>"
                )
                self.session.transportState = "PLAYING"
                self.session.errorMessage = nil
            } catch {
                self.session.errorMessage = "继续播放失败"
            }
        }
    }

    func pause() {
        guard let device = session.connectedDevice else { return }
        Task {
            do {
                _ = try await Self.sendSoap(
                    controlUrl: device.avTransportControlUrl,
                    serviceType: "urn:schemas-upnp-org:service:AVTransport:1",
                    action: "Pause",
                    innerArgs: "<InstanceID>0</InstanceID>"
                )
                self.session.transportState = "PAUSED_PLAYBACK"
                self.session.errorMessage = nil
            } catch {
                self.session.errorMessage = "暂停失败"
            }
        }
    }

    func seekTo(positionMs: Int64) {
        guard let device = session.connectedDevice else { return }
        let target = Self.formatUpnpTime(positionMs)
        Task {
            _ = try? await Self.sendSoap(
                controlUrl: device.avTransportControlUrl,
                serviceType: "urn:schemas-upnp-org:service:AVTransport:1",
                action: "Seek",
                innerArgs: "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>\(target)</Target>"
            )
            self.session.positionMs = positionMs
        }
    }

    func adjustVolume(delta: Int) {
        guard let device = session.connectedDevice, let ctrl = device.renderingControlUrl else { return }
        let next = min(100, max(0, session.volume + delta))
        Task {
            _ = try? await Self.sendSoap(
                controlUrl: ctrl,
                serviceType: "urn:schemas-upnp-org:service:RenderingControl:1",
                action: "SetVolume",
                innerArgs: "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>\(next)</DesiredVolume>"
            )
            self.session.volume = next
        }
    }

    func stopAndDisconnect() {
        let dev = session.connectedDevice
        pollTask?.cancel()
        Task {
            if let dev {
                _ = try? await Self.sendSoap(
                    controlUrl: dev.avTransportControlUrl,
                    serviceType: "urn:schemas-upnp-org:service:AVTransport:1",
                    action: "Stop",
                    innerArgs: "<InstanceID>0</InstanceID>"
                )
            }
            self.session = CastSessionState()
        }
    }

    private func startPolling(device: DlnaDevice) {
        pollTask?.cancel()
        pollTask = Task {
            while !Task.isCancelled && self.session.connectedDevice?.udn == device.udn {
                if let posXml = try? await Self.sendSoap(
                    controlUrl: device.avTransportControlUrl,
                    serviceType: "urn:schemas-upnp-org:service:AVTransport:1",
                    action: "GetPositionInfo",
                    innerArgs: "<InstanceID>0</InstanceID>"
                ) {
                    let rel = Self.extractTag(posXml, "RelTime")
                    let dur = Self.extractTag(posXml, "TrackDuration")
                    let relMs = Self.parseUpnpTime(rel)
                    let durMs = Self.parseUpnpTime(dur)
                    if relMs >= 0 { self.session.positionMs = relMs }
                    if durMs > 0 { self.session.durationMs = durMs }
                }
                if let infoXml = try? await Self.sendSoap(
                    controlUrl: device.avTransportControlUrl,
                    serviceType: "urn:schemas-upnp-org:service:AVTransport:1",
                    action: "GetTransportInfo",
                    innerArgs: "<InstanceID>0</InstanceID>"
                ) {
                    let st = Self.extractTag(infoXml, "CurrentTransportState")
                    if !st.isEmpty { self.session.transportState = st }
                }
                try? await Task.sleep(nanoseconds: 2_000_000_000)
            }
        }
    }

    // MARK: - BSD Socket SSDP Discovery (Non-isolated)

    private nonisolated static func performSsdpSearch(timeoutSeconds: Double) -> [String] {
        var locations = Set<String>()
        let sock = Darwin.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard sock >= 0 else { return [] }
        defer { Darwin.close(sock) }

        var tv = timeval(tv_sec: 0, tv_usec: 500_000)
        setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = in_port_t(1900).bigEndian
        inet_pton(AF_INET, "239.255.255.250", &addr.sin_addr)

        let targets = [
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1"
        ]
        for st in targets {
            let msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 3\r\nST: \(st)\r\n\r\n"
            let bytes = Array(msg.utf8)
            for _ in 0..<2 {
                _ = withUnsafePointer(to: &addr) { ptr in
                    ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockPtr in
                        Darwin.sendto(sock, bytes, bytes.count, 0, sockPtr, socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }
            }
        }

        let deadline = Date().addingTimeInterval(timeoutSeconds)
        var buffer = [UInt8](repeating: 0, count: 4096)
        while Date() < deadline {
            let received = Darwin.recv(sock, &buffer, buffer.count, 0)
            if received > 0, let text = String(bytes: buffer[0..<received], encoding: .utf8) {
                for line in text.components(separatedBy: "\r\n") {
                    if line.lowercased().hasPrefix("location:") {
                        let loc = String(line.dropFirst("location:".count)).trimmingCharacters(in: .whitespacesAndNewlines)
                        if loc.lowercased().hasPrefix("http") {
                            locations.insert(loc)
                        }
                    }
                }
            }
        }
        return Array(locations)
    }

    private nonisolated static func fetchDeviceDescription(location: String) async -> DlnaDevice? {
        guard let xml = try? await NetworkService.shared.fetchString(url: location, timeout: 4) else { return nil }
        let parser = DlnaDescriptionParser(location: location)
        return parser.parse(xml: xml)
    }

    private nonisolated static func sendSoap(controlUrl: String, serviceType: String, action: String, innerArgs: String) async throws -> String {
        guard let url = URL(string: controlUrl) else { throw URLError(.badURL) }
        let body = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
          <s:Body>
            <u:\(action) xmlns:u="\(serviceType)">
              \(innerArgs)
            </u:\(action)>
          </s:Body>
        </s:Envelope>
        """
        var req = URLRequest(url: url, timeoutInterval: 8)
        req.httpMethod = "POST"
        req.setValue("text/xml; charset=\"utf-8\"", forHTTPHeaderField: "Content-Type")
        req.setValue("\"\(serviceType)#\(action)\"", forHTTPHeaderField: "SOAPACTION")
        req.httpBody = body.data(using: .utf8)
        let (data, resp) = try await NetworkService.shared.session.data(for: req)
        if let http = resp as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            throw NSError(domain: "UPnP", code: http.statusCode, userInfo: [NSLocalizedDescriptionKey: "UPnP HTTP \(http.statusCode)"])
        }
        return String(data: data, encoding: .utf8) ?? ""
    }

    private nonisolated static func buildDidlLite(title: String, url: String) -> String {
        let lower = url.lowercased()
        let mime = lower.contains(".m3u8") ? "application/vnd.apple.mpegurl" : (lower.contains(".mp4") ? "video/mp4" : "video/*")
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>\(escapeXml(title))</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo=\"http-get:*:\(mime):*\">\(escapeXml(url))</res></item></DIDL-Lite>"
    }

    private nonisolated static func escapeXml(_ input: String) -> String {
        input.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
            .replacingOccurrences(of: "'", with: "&apos;")
    }

    private nonisolated static func extractTag(_ xml: String, _ tag: String) -> String {
        let pattern = "<\(tag)[^>]*>(.*?)</\(tag)>"
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive, .dotMatchesLineSeparators]),
              let match = regex.firstMatch(in: xml, range: NSRange(xml.startIndex..., in: xml)),
              let range = Range(match.range(at: 1), in: xml) else { return "" }
        return String(xml[range]).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private nonisolated static func formatUpnpTime(_ ms: Int64) -> String {
        let sec = max(0, Int(ms / 1000))
        return String(format: "%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
    }

    private nonisolated static func parseUpnpTime(_ str: String) -> Int64 {
        let parts = str.components(separatedBy: ":")
        guard parts.count == 3,
              let h = Int64(parts[0]),
              let m = Int64(parts[1]),
              let s = Int64(parts[2].components(separatedBy: ".").first ?? "") else { return -1 }
        return (h * 3600 + m * 60 + s) * 1000
    }
}

private final class DlnaDescriptionParser: NSObject, XMLParserDelegate {
    private let location: String
    private var tag = ""
    private var text = ""
    private var friendlyName = ""
    private var manufacturer = ""
    private var udn = ""
    private var inService = false
    private var serviceType = ""
    private var controlUrl = ""
    private var avTransportUrl: String?
    private var renderingUrl: String?

    init(location: String) {
        self.location = location
        self.udn = location
    }

    func parse(xml: String) -> DlnaDevice? {
        guard let data = xml.data(using: .utf8) else { return nil }
        let parser = XMLParser(data: data)
        parser.delegate = self
        parser.parse()
        guard let avUrl = avTransportUrl, !avUrl.isEmpty else { return nil }
        let hostFallback = URL(string: location)?.host ?? "DLNA 电视设备"
        return DlnaDevice(
            udn: udn,
            friendlyName: friendlyName.isEmpty ? hostFallback : friendlyName,
            manufacturer: manufacturer.isEmpty ? "Smart TV" : manufacturer,
            location: location,
            avTransportControlUrl: avUrl,
            renderingControlUrl: renderingUrl
        )
    }

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
        tag = elementName.lowercased()
        text = ""
        if tag == "service" {
            inService = true
            serviceType = ""
            controlUrl = ""
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) { text += string }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let el = elementName.lowercased()
        if !inService {
            if el == "friendlyname" && friendlyName.isEmpty { friendlyName = t }
            else if el == "manufacturer" && manufacturer.isEmpty { manufacturer = t }
            else if el == "udn" && !t.isEmpty { udn = t }
        } else {
            if el == "servicetype" { serviceType = t }
            else if el == "controlurl" { controlUrl = t }
            else if el == "service" {
                if serviceType.localizedCaseInsensitiveContains("AVTransport"), !controlUrl.isEmpty {
                    avTransportUrl = resolve(controlUrl)
                } else if serviceType.localizedCaseInsensitiveContains("RenderingControl"), !controlUrl.isEmpty {
                    renderingUrl = resolve(controlUrl)
                }
                inService = false
            }
        }
        tag = ""
        text = ""
    }

    private func resolve(_ rel: String) -> String {
        guard let base = URL(string: location) else { return rel }
        return URL(string: rel, relativeTo: base)?.absoluteString ?? rel
    }
}
