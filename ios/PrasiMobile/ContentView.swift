//
//  ContentView.swift
//  mobile
//
//  Created by Rizky Ramadhan on 23/03/25.
//

import SwiftUI
@preconcurrency
import WebKit

struct ContentView: View {
    var body: some View {
        ZStack {
            WebView()
        }
        .safeAreaPadding(.top, 4)
    }
}

struct WebView: UIViewRepresentable {
    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }
    
    func makeUIView(context: Context) -> WKWebView {
        // Configure URLCache
        let memoryCapacity = 20 * 1024 * 1024    // 20MB
        let diskCapacity = 100 * 1024 * 1024     // 100MB
        let cache = URLCache(memoryCapacity: memoryCapacity, diskCapacity: diskCapacity, diskPath: "webview_cache")
        URLCache.shared = cache
        
        let configuration = WKWebViewConfiguration()
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        
        // Disable zooming
        let source = """
            var meta = document.createElement('meta');
            meta.name = 'viewport';
            meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
            document.getElementsByTagName('head')[0].appendChild(meta);
        """
        let script = WKUserScript(source: source, injectionTime: .atDocumentEnd, forMainFrameOnly: true)
        webView.configuration.userContentController.addUserScript(script)
        
        // Disable overscroll/bounce
        webView.scrollView.bounces = false
        webView.scrollView.alwaysBounceVertical = false
        webView.scrollView.alwaysBounceHorizontal = false
        
        return webView
    }
    
    func updateUIView(_ webView: WKWebView, context: Context) {
        if let url = URL(string: "https://github.com") {
            var request = URLRequest(url: url)
            // Use cache with network load failure fallback
            request.cachePolicy = .returnCacheDataElseLoad
            webView.load(request)
        }
    }
    
    class Coordinator: NSObject, WKNavigationDelegate {
        var parent: WebView
        
        init(_ parent: WebView) {
            self.parent = parent
        }
        
        // Handle successful load
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            // Cache will be handled automatically by URLCache
        }
        
        // Handle failed load
        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            handleLoadError(webView: webView, error: error)
        }
        
        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            handleLoadError(webView: webView, error: error)
        }
        
        private func handleLoadError(webView: WKWebView, error: Error) {
            guard let url = webView.url else { return }
            
            // Check if we have a cached response
            let request = URLRequest(url: url, cachePolicy: .returnCacheDataDontLoad, timeoutInterval: 0)
            if let cachedResponse = URLCache.shared.cachedResponse(for: request) {
                // Load cached content
                webView.load(request)
            }
        }
        
        // Determine if response should be cached
        func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse, decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
            guard let response = navigationResponse.response as? HTTPURLResponse,
                  let url = response.url else {
                decisionHandler(.allow)
                return
            }
            
            // Only cache GET requests
            if let httpMethod = webView.url?.scheme?.uppercased(), httpMethod == "GET" {
                // Check content type
                if let contentType = response.value(forHTTPHeaderField: "Content-Type")?.lowercased() {
                    let shouldCache = contentType.contains("text/html") ||
                                    contentType.contains("text/css") ||
                                    contentType.contains("application/javascript") ||
                                    contentType.contains("image/")
                    
                    if shouldCache {
                        // URLCache will handle the caching automatically
                        decisionHandler(.allow)
                        return
                    }
                }
            }
            
            decisionHandler(.allow)
        }
    }
}

#Preview {
    ContentView()
}
