import SwiftUI
import AVKit

struct AirPlayPickerView: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let picker = AVRoutePickerView()
        picker.activeTintColor = UIColor(Color.googleBlue)
        picker.tintColor = UIColor.label
        picker.prioritizesVideoDevices = true
        return picker
    }

    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}

struct CastSheetView: View {
    @ObservedObject var dlna = DlnaService.shared
    let mediaTitle: String
    let mediaUrl: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                // Section 1: Native Apple AirPlay 2
                Section {
                    HStack(spacing: 14) {
                        Image(systemName: "airplayvideo")
                            .font(.title2)
                            .foregroundColor(.googleBlue)
                            .frame(width: 34)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("隔空播放 (AirPlay 2)")
                                .font(.headline)
                            Text("支持 Apple TV、支持隔空播放的智能电视与乐播投屏")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                        Spacer()
                        AirPlayPickerView()
                            .frame(width: 36, height: 36)
                    }
                    .padding(.vertical, 4)
                } header: {
                    Text("Apple 原生投屏")
                }

                // Section 2: Active DLNA Remote
                if let dev = dlna.session.connectedDevice {
                    Section {
                        VStack(alignment: .leading, spacing: 12) {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("正在 DLNA 投屏 · \(dev.friendlyName)")
                                        .font(.caption)
                                        .foregroundColor(.googleBlue)
                                    Text(dlna.session.mediaTitle.isEmpty ? "视频播放中" : dlna.session.mediaTitle)
                                        .font(.subheadline.weight(.semibold))
                                        .lineLimit(1)
                                }
                                Spacer()
                                Button("断开", role: .destructive) {
                                    dlna.stopAndDisconnect()
                                }
                                .buttonStyle(.bordered)
                            }

                            if dlna.session.durationMs > 0 {
                                HStack {
                                    Text(TimeFormatter.formatMs(dlna.session.positionMs)).font(.caption2)
                                    ProgressView(value: Double(dlna.session.positionMs), total: Double(max(1, dlna.session.durationMs)))
                                        .tint(.googleBlue)
                                    Text(TimeFormatter.formatMs(dlna.session.durationMs)).font(.caption2)
                                }
                            }

                            HStack(spacing: 24) {
                                Spacer()
                                Button { dlna.adjustVolume(delta: -8) } label: { Image(systemName: "speaker.wave.1.fill") }
                                Button { dlna.seekTo(positionMs: max(0, dlna.session.positionMs - 15_000)) } label: { Image(systemName: "gobackward.15") }
                                Button {
                                    if dlna.session.transportState == "PLAYING" { dlna.pause() } else { dlna.play() }
                                } label: {
                                    Image(systemName: dlna.session.transportState == "PLAYING" ? "pause.circle.fill" : "play.circle.fill")
                                        .font(.system(size: 36))
                                        .foregroundColor(.googleBlue)
                                }
                                Button { dlna.seekTo(positionMs: dlna.session.positionMs + 15_000) } label: { Image(systemName: "goforward.15") }
                                Button { dlna.adjustVolume(delta: 8) } label: { Image(systemName: "speaker.wave.3.fill") }
                                Spacer()
                            }
                            .buttonStyle(.plain)
                        }
                        .padding(.vertical, 6)
                    } header: {
                        Text("遥控器")
                    }
                }

                // Section 3: Discovered DLNA Devices
                Section {
                    if let err = dlna.session.errorMessage {
                        Text(err).font(.caption).foregroundColor(.red)
                    }
                    if dlna.devices.isEmpty {
                        HStack(spacing: 12) {
                            if dlna.isScanning {
                                ProgressView()
                            } else {
                                Image(systemName: "tv.slash").foregroundColor(.secondary)
                            }
                            Text(dlna.isScanning ? "正在局域网扫描 DLNA 电视/盒子..." : "未发现 DLNA 设备，请确认在同一 Wi-Fi 并点击刷新")
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                        }
                        .padding(.vertical, 8)
                    } else {
                        ForEach(dlna.devices) { device in
                            Button {
                                if !mediaUrl.isEmpty {
                                    dlna.castMedia(device: device, mediaUrl: mediaUrl, title: mediaTitle.isEmpty ? "极光视界视频" : mediaTitle)
                                }
                            } label: {
                                HStack {
                                    Image(systemName: "tv")
                                        .foregroundColor(.googleBlue)
                                    VStack(alignment: .leading) {
                                        Text(device.friendlyName).foregroundColor(.primary)
                                        Text(device.manufacturer).font(.caption).foregroundColor(.secondary)
                                    }
                                    Spacer()
                                    Text(dlna.session.connectedDevice?.udn == device.udn ? "已连接" : "点击投屏")
                                        .font(.caption.weight(.medium))
                                        .foregroundColor(.googleBlue)
                                }
                            }
                        }
                    }
                } header: {
                    HStack {
                        Text("局域网 DLNA 设备 (\(dlna.devices.count))")
                        Spacer()
                        Button("刷新") { dlna.startDiscovery() }
                            .font(.caption)
                    }
                }
            }
            .navigationTitle("投屏到电视")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") { dismiss() }
                }
            }
            .onAppear { dlna.startDiscovery() }
        }
    }
}
