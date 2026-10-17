import AppKit
import WebKit
import UniformTypeIdentifiers

final class Desktop: NSObject, NSApplicationDelegate, WKScriptMessageHandlerWithReply, WKNavigationDelegate {
    var window: NSWindow!
    var web: WKWebView!
    let gateway = Gateway()
    var base: URL?
    var token = ""
    var scope = ""
    var nickname = ""
    var phoneMasked = ""
    var storageReadable = true

    func applicationDidFinishLaunching(_ notification: Notification) {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        config.userContentController.addScriptMessageHandler(self, contentWorld: .page, name: "mozhi")
        config.preferences.javaScriptCanOpenWindowsAutomatically = false
        web = WKWebView(frame: .zero, configuration: config)
        web.navigationDelegate = self
        web.allowsBackForwardNavigationGestures = false
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1220, height: 820),
            styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
        window.title = "墨知"
        window.minSize = NSSize(width: 880, height: 620)
        window.contentView = web
        window.center()
        let appMenu = NSMenu()
        let root = NSMenuItem(); appMenu.addItem(root)
        let menu = NSMenu(); root.submenu = menu
        menu.addItem(withTitle: "关于墨知", action: #selector(NSApplication.orderFrontStandardAboutPanel(_:)), keyEquivalent: "")
        menu.addItem(NSMenuItem.separator())
        menu.addItem(withTitle: "设置…", action: #selector(settings), keyEquivalent: ",").target = self
        menu.addItem(withTitle: "新建对话", action: #selector(newChat), keyEquivalent: "n").target = self
        menu.addItem(NSMenuItem.separator())
        menu.addItem(withTitle: "隐藏墨知", action: #selector(NSApplication.hide(_:)), keyEquivalent: "h")
        menu.addItem(withTitle: "退出墨知", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        let editItem = NSMenuItem(); appMenu.addItem(editItem)
        let edit = NSMenu(title: "编辑"); editItem.submenu = edit
        for (title, selector, key) in [("撤销", "undo:", "z"), ("剪切", "cut:", "x"), ("复制", "copy:", "c"),
                                      ("粘贴", "paste:", "v"), ("全选", "selectAll:", "a")] {
            edit.addItem(withTitle: title, action: Selector(selector), keyEquivalent: key)
        }
        NSApplication.shared.mainMenu = appMenu
        guard let resources = Bundle.main.resourceURL else { NSApplication.shared.terminate(nil); return }
        let rootURL = resources.appendingPathComponent("Web", isDirectory: true)
        web.loadFileURL(rootURL.appendingPathComponent("index.html"), allowingReadAccessTo: rootURL)
        window.makeKeyAndOrderFront(nil)
        NSApplication.shared.activate(ignoringOtherApps: true)
        if CommandLine.arguments.contains("--smoke-test") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { self.checkSmoke(remaining: 30) }
        }
    }

    // CI exercises the real WebKit -> Swift bootstrap without a service account.
    func checkSmoke(remaining: Int) {
        web.evaluateJavaScript("!!window.MozhiCore && !!document.querySelector('.welcome') && document.getElementById('error-banner').hidden") { result, error in
            if result as? Bool == true {
                self.web.takeSnapshot(with: nil) { picture, snapshotError in
                    if let path = ProcessInfo.processInfo.environment["MOZHI_MAC_SMOKE_SNAPSHOT"],
                       let tiff = picture?.tiffRepresentation, let bitmap = NSBitmapImageRep(data: tiff),
                       let png = bitmap.representation(using: .png, properties: [:]) {
                        do { try png.write(to: URL(fileURLWithPath: path)) } catch { print(error); exit(1) }
                    }
                    print("Native WebKit bootstrap and app launch passed")
                    exit(snapshotError == nil ? 0 : 1)
                }
            } else if remaining > 0 {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { self.checkSmoke(remaining: remaining - 1) }
            } else {
                self.web.evaluateJavaScript("document.getElementById('error-banner')?.textContent || 'Renderer did not become ready'") { text, _ in
                    print("Native smoke failed:", text ?? "unknown", error?.localizedDescription ?? "")
                    exit(1)
                }
            }
        }
    }

    @objc func settings() { web.evaluateJavaScript("window.desktopAction?.('settings')", completionHandler: nil) }
    @objc func newChat() { web.evaluateJavaScript("window.desktopAction?.('new')", completionHandler: nil) }
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        window.makeKeyAndOrderFront(nil); return true
    }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        if let url = navigationAction.request.url, url.isFileURL,
           url.standardizedFileURL.path == Bundle.main.resourceURL?.appendingPathComponent("Web/index.html").standardizedFileURL.path {
            decisionHandler(.allow)
        } else { decisionHandler(.cancel) }
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage,
                               replyHandler: @escaping (Any?, String?) -> Void) {
        guard message.frameInfo.isMainFrame, message.webView === web,
              let url = message.frameInfo.request.url, url.isFileURL,
              url.standardizedFileURL.path == Bundle.main.resourceURL?.appendingPathComponent("Web/index.html").standardizedFileURL.path,
              let body = message.body as? [String: Any], let action = body["action"] as? String else {
            replyHandler(nil, "不允许的页面请求。"); return
        }
        Task { @MainActor in
            do { replyHandler(try await handle(action, body["payload"] as? [String: Any] ?? [:]), nil) }
            catch { replyHandler(nil, error.localizedDescription) }
        }
    }

    func publicConfig() -> [String: Any] {
        ["base": base?.absoluteString ?? "", "connected": !token.isEmpty,
         "scope": scope, "nickname": nickname, "phoneMasked": phoneMasked, "version": "0.2.0"]
    }
    func requireAccount() throws -> (URL, String, String) {
        guard let base = base, !token.isEmpty, !scope.isEmpty else { throw ClientError.message("请先在设置中连接墨知服务。") }
        return (base, token, scope)
    }

    func connect(_ address: URL, secret: String, user: [String: Any], id: String) throws -> [String: Any] {
        let name = user["nickname"] as? String ?? "墨知用户"
        let phone = user["phone_masked"] as? String ?? ""
        let newScope = Vault.scope(base: address, user: id)
        let state = try Vault.read(newScope)
        let saved = ["base": address.absoluteString, "token": secret, "user": id, "nickname": name, "phoneMasked": phone]
        try Vault.set("connection", JSONSerialization.data(withJSONObject: saved))
        base = address; token = secret; scope = newScope; nickname = name; phoneMasked = phone; storageReadable = true
        return ["config": publicConfig(), "state": state]
    }

    @MainActor func handle(_ action: String, _ p: [String: Any]) async throws -> Any {
        switch action {
        case "bootstrap":
            if let data = try Vault.get("connection"),
               let saved = try JSONSerialization.jsonObject(with: data) as? [String: String],
               let address = saved["base"], let user = saved["user"], let secret = saved["token"] {
                base = try Policy.baseURL(address); token = secret; nickname = saved["nickname"] ?? ""
                phoneMasked = saved["phoneMasked"] ?? ""
                scope = Vault.scope(base: base!, user: user)
            }
            var result: [String: Any] = ["config": publicConfig(), "state": [String: Any]()]
            if !scope.isEmpty {
                do { result["state"] = try Vault.read(scope); storageReadable = true }
                catch { storageReadable = false; result["storageError"] = error.localizedDescription }
            }
            return result
        case "configure":
            let address = try Policy.baseURL(p["base"] as? String ?? "")
            let supplied = (p["token"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let secret = supplied.isEmpty && address == base ? token : supplied
            guard !secret.isEmpty, secret.count <= 2048, !secret.contains("\n"), !secret.contains("\r") else {
                throw ClientError.message("请输入个人服务凭证。这里不使用大模型 API Key。")
            }
            guard let me = try await gateway.send(base: address, token: secret, path: "/v1/me") as? [String: Any],
                  let user = me["id"] as? String else { throw ClientError.message("无法确认服务账号。") }
            return try connect(address, secret: secret, user: me, id: user)
        case "phoneCode":
            let address = try Policy.baseURL(p["base"] as? String ?? "")
            let data = try JSONSerialization.data(withJSONObject: ["phone": p["phone"] as? String ?? ""])
            return try await gateway.send(base: address, token: "", path: "/v1/auth/code", method: "POST", body: data)
        case "phoneVerify":
            let address = try Policy.baseURL(p["base"] as? String ?? "")
            let bind = p["bindLegacy"] as? Bool ?? false
            if bind && (address != base || token.isEmpty) { throw ClientError.message("绑定旧账号须使用原服务地址和凭证。") }
            let data = try JSONSerialization.data(withJSONObject: ["phone": p["phone"] as? String ?? "",
                "code": p["code"] as? String ?? "", "challenge_id": p["challenge"] as? String ?? "", "bind_legacy": bind])
            guard let result = try await gateway.send(base: address, token: bind ? token : "", path: "/v1/auth/verify", method: "POST", body: data) as? [String: Any],
                  let secret = result["token"] as? String, let me = result["user"] as? [String: Any],
                  let user = me["id"] as? String, me["phone_verified"] as? Bool == true else { throw ClientError.message("手机号验证未完成。") }
            return try connect(address, secret: secret, user: me, id: user)
        case "logout":
            if let address = base, !phoneMasked.isEmpty {
                _ = try? await gateway.send(base: address, token: token, path: "/v1/auth/logout", method: "POST", body: Data("{}".utf8))
            }
            try Vault.delete("connection"); base = nil; token = ""; scope = ""; nickname = ""; phoneMasked = ""
            return ["ok": true]
        case "save":
            let (_, _, current) = try requireAccount()
            guard storageReadable, p["scope"] as? String == current, let value = p["state"] as? [String: Any] else {
                throw ClientError.message("账号已改变或存储不可用，未写入记录。")
            }
            try Vault.write(current, value: value); return ["ok": true]
        case "api":
            let (address, secret, current) = try requireAccount()
            let method = p["method"] as? String ?? "GET"
            let path = p["path"] as? String ?? ""
            guard Policy.allowed(path, method: method) else { throw ClientError.message("此接口不对客户端开放。") }
            let data = try p["body"].map { try JSONSerialization.data(withJSONObject: $0) }
            if let data = data, data.count > 290000 { throw ClientError.message("本次上下文过长，请新建对话。") }
            let value = try await gateway.send(base: address, token: secret, path: path, method: method, body: data)
            guard current == scope else { throw ClientError.message("账号已切换，请刷新。") }; return value
        case "attachments":
            let (address, secret, current) = try requireAccount()
            let count = max(0, min(3, p["remaining"] as? Int ?? 3))
            guard count > 0 else { throw ClientError.message("每条消息最多 3 份附件。") }
            let panel = NSOpenPanel(); panel.title = "添加 Word、Excel 或图片"
            panel.allowsMultipleSelection = true; panel.canChooseDirectories = false
            let extensions = ["docx", "xlsx", "png", "jpg", "jpeg", "gif", "webp"]
            panel.allowedContentTypes = extensions.compactMap { UTType(filenameExtension: $0) }
            guard await panel.beginSheetModal(for: window) == .OK else { return ["items": [], "errors": []] }
            guard panel.urls.count <= count else { throw ClientError.message("还可以添加 \(count) 份附件，请重新选择。") }
            // Validate the whole selection before any upload or vision charge.
            for url in panel.urls {
                let values = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
                guard values.isRegularFile == true, let size = values.fileSize, size > 0, size <= 8 * 1024 * 1024,
                      extensions.contains(url.pathExtension.lowercased()) else { throw ClientError.message("附件只支持 DOCX、XLSX 和常见图片，每份不超过 8 MiB。") }
            }
            var items: [Any] = []; var errors: [String] = []
            for url in panel.urls {
                guard current == scope else { throw ClientError.message("账号已切换，已停止上传。") }
                do {
                    let data = try Data(contentsOf: url)
                    guard data.count <= 8 * 1024 * 1024 else { throw ClientError.message("文件超过 8 MiB。") }
                    let name = Policy.filename(url.lastPathComponent).replacingOccurrences(of: "\"", with: "_")
                    let boundary = "Mozhi-" + UUID().uuidString
                    var body = Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"\(name)\"\r\nContent-Type: application/octet-stream\r\n\r\n".utf8)
                    body.append(data); body.append(Data("\r\n--\(boundary)--\r\n".utf8))
                    items.append(try await gateway.send(base: address, token: secret, path: "/v1/agent/attachments", method: "POST", body: body, contentType: "multipart/form-data; boundary=\(boundary)"))
                } catch { errors.append(url.lastPathComponent + "：" + error.localizedDescription) }
            }
            guard current == scope else { throw ClientError.message("账号已切换，请重新选择附件。") }
            return ["items": items, "errors": errors]
        case "download":
            let (address, secret, current) = try requireAccount()
            let id = p["id"] as? String ?? ""
            guard Policy.allowed("/v1/agent/artifacts/" + id, method: "GET"),
                  let value = try await gateway.send(base: address, token: secret, path: "/v1/agent/artifacts/" + id) as? [String: Any],
                  let content = value["content"] as? String, current == scope else { throw ClientError.message("文件不可用。") }
            return try await saveText(content, name: value["name"] as? String ?? "墨知文件.txt")
        case "export":
            guard let content = p["text"] as? String, content.utf8.count <= 4 * 1024 * 1024 else { throw ClientError.message("导出内容无效。") }
            return try await saveText(content, name: Policy.filename(p["name"] as? String ?? "墨知对话.md"))
        case "external":
            guard let url = Policy.externalURL(p["url"] as? String ?? "") else { throw ClientError.message("只能打开 HTTP / HTTPS 网页。") }
            NSWorkspace.shared.open(url); return ["ok": true]
        case "copy":
            let text = p["text"] as? String ?? ""
            NSPasteboard.general.clearContents(); NSPasteboard.general.setString(text, forType: .string)
            return ["ok": true]
        default: throw ClientError.message("不支持的操作。")
        }
    }

    @MainActor func saveText(_ text: String, name: String) async throws -> Any {
        let panel = NSSavePanel(); panel.nameFieldStringValue = Policy.filename(name)
        guard await panel.beginSheetModal(for: window) == .OK, let url = panel.url else { return ["saved": false] }
        try Data(text.utf8).write(to: url, options: .atomic)
        return ["saved": true]
    }
}

let app = NSApplication.shared
let desktop = Desktop()
app.delegate = desktop
app.setActivationPolicy(.regular)
app.run()
