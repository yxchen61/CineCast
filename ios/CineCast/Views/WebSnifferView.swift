import SwiftUI
import WebKit

struct SnifferWebViewRepresentable: UIViewRepresentable {
    let urlString: String
    let adHosts: Set<String>
    let onSniffed: (String) -> Void
    let onTitleChanged: (String) -> Void
    let onUrlChanged: (String) -> Void
    @Binding var reloadTrigger: Int

    func makeCoordinator() -> Coordinator {
        Coordinator(adHosts: adHosts, onSniffed: onSniffed, onTitleChanged: onTitleChanged, onUrlChanged: onUrlChanged)
    }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []

        // Inject lightweight JS sniffer for dynamic fetch/XHR/video src (.m3u8/.mp4)
        let js = """
        (function(){
          function check(u){
            if(!u || typeof u !== 'string') return;
            var l = u.toLowerCase();
            if(l.indexOf('.m3u8')>=0 || l.indexOf('.mp4')>=0 || l.indexOf('.flv')>=0){
              window.webkit.messageHandlers.sniffer.postMessage(u);
            }
          }
          var origOpen = XMLHttpRequest.prototype.open;
          XMLHttpRequest.prototype.open = function(m, u){ check(u); return origOpen.apply(this, arguments); };
          var origFetch = window.fetch;
          window.fetch = function(u){ check(typeof u === 'string' ? u : (u && u.url)); return origFetch.apply(this, arguments); };
          setInterval(function(){
            document.querySelectorAll('video, source').forEach(function(el){ check(el.currentSrc || el.src); });
          }, 1500);
        })();
        """
        let script = WKUserScript(source: js, injectionTime: .atDocumentStart, forMainFrameOnly: false)
        config.userContentController.addUserScript(script)
        config.userContentController.add(context.coordinator, name: "sniffer")

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        webView.customUserAgent = NetworkService.defaultUA
        if let url = URL(string: urlString) {
            webView.load(URLRequest(url: url))
        }
        context.coordinator.lastLoadedUrl = urlString
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        if context.coordinator.lastLoadedUrl != urlString, let url = URL(string: urlString) {
            context.coordinator.lastLoadedUrl = urlString
            uiView.load(URLRequest(url: url))
        } else if context.coordinator.lastReloadTrigger != reloadTrigger {
            context.coordinator.lastReloadTrigger = reloadTrigger
            uiView.reload()
        }
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        let adHosts: Set<String>
        let onSniffed: (String) -> Void
        let onTitleChanged: (String) -> Void
        let onUrlChanged: (String) -> Void
        var lastLoadedUrl: String = ""
        var lastReloadTrigger: Int = 0

        init(adHosts: Set<String>, onSniffed: @escaping (String) -> Void, onTitleChanged: @escaping (String) -> Void, onUrlChanged: @escaping (String) -> Void) {
            self.adHosts = adHosts
            self.onSniffed = onSniffed
            self.onTitleChanged = onTitleChanged
            self.onUrlChanged = onUrlChanged
        }

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            if let str = message.body as? String, str.hasPrefix("http") {
                DispatchQueue.main.async { self.onSniffed(str) }
            }
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            if let host = navigationAction.request.url?.host, adHosts.contains(host) {
                decisionHandler(.cancel)
                return
            }
            if let urlStr = navigationAction.request.url?.absoluteString {
                let lower = urlStr.lowercased()
                if lower.contains(".m3u8") || lower.contains(".mp4") {
                    DispatchQueue.main.async { self.onSniffed(urlStr) }
                }
            }
            decisionHandler(.allow)
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            if let t = webView.title, !t.isEmpty { DispatchQueue.main.async { self.onTitleChanged(t) } }
            if let u = webView.url?.absoluteString { DispatchQueue.main.async { self.onUrlChanged(u) } }
        }
    }
}

struct WebSnifferView: View {
    @EnvironmentObject var store: AppStore
    @Environment(\.dismiss) private var dismiss

    let initialUrl: String
    let initialTitle: String
    let onPlaySniffed: (String, String) -> Void

    @State private var currentUrl: String = ""
    @State private var urlInput: String = ""
    @State private var pageTitle: String = ""
    @State private var sniffedUrls: [String] = []
    @State private var showSniffedSheet = false
    @State private var reloadTrigger = 0

    var body: some View {
        NavigationStack {
            ZStack(alignment: .bottomTrailing) {
                VStack(spacing: 0) {
                    HStack(spacing: 8) {
                        TextField("输入任意视频网站 URL", text: $urlInput)
                            .textFieldStyle(.roundedBorder)
                            .keyboardType(.URL)
                            .autocorrectionDisabled()
                            .submitLabel(.go)
                            .onSubmit {
                                let fixed = urlInput.hasPrefix("http") ? urlInput : "https://\(urlInput)"
                                currentUrl = fixed
                            }

                        Menu {
                            ForEach(store.parses) { p in
                                Button(p.name) {
                                    if let enc = currentUrl.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) {
                                        currentUrl = "\(p.url)\(enc)"
                                    }
                                }
                            }
                        } label: {
                            Image(systemName: "wand.and.stars")
                                .foregroundColor(.googleBlue)
                        }

                        Button { reloadTrigger += 1 } label: {
                            Image(systemName: "arrow.clockwise")
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)

                    SnifferWebViewRepresentable(
                        urlString: currentUrl.isEmpty ? initialUrl : currentUrl,
                        adHosts: Set(store.settings.adBlockHosts),
                        onSniffed: { u in
                            if !sniffedUrls.contains(u) { sniffedUrls.insert(u, at: 0) }
                        },
                        onTitleChanged: { pageTitle = $0 },
                        onUrlChanged: { u in currentUrl = u; urlInput = u },
                        reloadTrigger: $reloadTrigger
                    )
                }

                if !sniffedUrls.isEmpty {
                    Button { showSniffedSheet = true } label: {
                        Label("已嗅探 \(sniffedUrls.count) 条高清流", systemImage: "play.circle.fill")
                            .font(.subheadline.weight(.semibold))
                            .padding(.horizontal, 16)
                            .padding(.vertical, 11)
                            .background(Color.googleBlue)
                            .foregroundColor(.white)
                            .clipShape(Capsule())
                            .shadow(radius: 6)
                    }
                    .padding(18)
                }
            }
            .navigationTitle(pageTitle.isEmpty ? initialTitle : pageTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("关闭") { dismiss() } }
            }
            .onAppear {
                currentUrl = initialUrl
                urlInput = initialUrl
                pageTitle = initialTitle
            }
            .sheet(isPresented: $showSniffedSheet) {
                NavigationStack {
                    List(sniffedUrls, id: \.self) { stream in
                        Button {
                            showSniffedSheet = false
                            dismiss()
                            onPlaySniffed(pageTitle.isEmpty ? "嗅探高清视频" : pageTitle, stream)
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(stream.lowercased().contains(".m3u8") ? "HLS 高清流 (.m3u8)" : "直链视频流")
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundColor(.googleBlue)
                                Text(stream)
                                    .font(.caption2)
                                    .foregroundColor(.secondary)
                                    .lineLimit(2)
                            }
                        }
                    }
                    .navigationTitle("嗅探到的视频流 (\(sniffedUrls.count))")
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) {
                            Button("清空") { sniffedUrls.removeAll(); showSniffedSheet = false }
                        }
                    }
                }
            }
        }
    }
}
