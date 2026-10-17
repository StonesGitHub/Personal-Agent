import Foundation

enum ClientError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case let .message(text) = self { return text }; return nil }
}

enum Policy {
    static func baseURL(_ value: String) throws -> URL {
        guard var c = URLComponents(string: value.trimmingCharacters(in: .whitespacesAndNewlines)),
              c.scheme == "https", let host = c.host, !host.isEmpty,
              c.user == nil, c.password == nil, c.query == nil, c.fragment == nil,
              !c.path.contains(".."), !c.percentEncodedPath.contains("%") else {
            throw ClientError.message("请输入 HTTPS 服务地址，不要包含账号、查询参数或片段。")
        }
        c.path = c.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if !c.path.isEmpty { c.path = "/" + c.path }
        guard let url = c.url else { throw ClientError.message("服务地址无效。") }
        return url
    }

    static func allowed(_ path: String, method: String) -> Bool {
        guard ["GET", "POST"].contains(method), !path.contains("%"), !path.contains(".."),
              !path.contains("#"), !path.contains("\\") else { return false }
        let id = "[a-f0-9]{32}"
        let requestID = "[a-zA-Z0-9_-]{16,80}"
        let get = ["/v1/account/archives", "/v1/account/archives/\(id)/chunks\\?seq=[0-9]{1,5}", "/v1/me", "/v1/agent/status", "/v1/skills", "/v1/reminders", "/v1/health-records",
                   "/v1/agent/runs(?:\\?limit=[0-9]{1,3}(?:&before=[0-9]{1,19})?)?",
                   "/v1/agent/runs/\(id)", "/v1/agent/runs/\(id)/events(?:\\?after=[0-9]{1,19})?",
                   "/v1/agent/requests/\(requestID)", "/v1/agent/artifacts/\(id)"]
        let post = ["/v1/agent/runs", "/v1/agent/runs/\(id)/(?:cancel|resume)",
                    "/v1/agent/requests/\(requestID)/cancel", "/v1/reminders/\(id)/cancel"]
        return (method == "GET" ? get : post).contains { path.range(of: "^(?:\($0))$", options: .regularExpression) != nil }
    }

    static func externalURL(_ text: String) -> URL? {
        guard let url = URL(string: text), let scheme = url.scheme?.lowercased(),
              ["https", "http"].contains(scheme), url.host != nil,
              url.user == nil, url.password == nil else { return nil }
        return url
    }

    static func filename(_ text: String) -> String {
        let name = URL(fileURLWithPath: text).lastPathComponent
            .components(separatedBy: .controlCharacters).joined().replacingOccurrences(of: ":", with: "_")
        return name.isEmpty || name == "." || name == ".." ? "墨知文件.txt" : String(name.prefix(180))
    }
}
