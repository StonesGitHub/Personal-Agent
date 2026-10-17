import Foundation

// Credentials never enter page state; redirects cannot forward the bearer token.
final class Gateway: NSObject, URLSessionTaskDelegate {
    lazy var session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 90
        config.timeoutIntervalForResource = 120
        config.httpCookieStorage = nil
        config.urlCache = nil
        return URLSession(configuration: config)
    }()

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }

    func send(base: URL, token: String, path: String, method: String = "GET", body: Data? = nil,
              contentType: String = "application/json") async throws -> Any {
        guard let url = URL(string: base.absoluteString + path) else { throw ClientError.message("请求路径无效。") }
        var request = URLRequest(url: url)
        request.httpMethod = method
        if !token.isEmpty { request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body = body { request.httpBody = body; request.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        let (data, response) = try await session.data(for: request, delegate: self)
        guard let http = response as? HTTPURLResponse else { throw ClientError.message("服务端响应无效。") }
        guard data.count <= 8 * 1024 * 1024 else { throw ClientError.message("服务端响应过大。") }
        let value = try? JSONSerialization.jsonObject(with: data)
        guard (200..<300).contains(http.statusCode) else {
            let detail = (value as? [String: Any])?["detail"] as? String
            let fallback = http.statusCode == 401 ? "用户凭证无效或已过期，请重新连接。" :
                http.statusCode == 404 ? "此接口暂不可用，请检查服务地址或服务端版本。" :
                (300..<400).contains(http.statusCode) ? "服务地址发生重定向，请填写最终的 HTTPS API 地址。" : "服务请求失败（\(http.statusCode)）。"
            throw ClientError.message(detail ?? fallback)
        }
        guard let value = value else { throw ClientError.message("服务端没有返回 JSON，请检查 API 地址。") }
        return value
    }
}
