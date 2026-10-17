import Foundation

@main struct PolicyTests {
    static func main() throws {
        let id = String(repeating: "a", count: 32)
        let normalized = try Policy.baseURL("https://example.com/agent/")
        assert(normalized.absoluteString == "https://example.com/agent")
        for bad in ["http://example.com", "https://u:p@example.com", "https://example.com?token=secret", "file:///tmp", "https://example.com/%2e%2e"] {
            do { _ = try Policy.baseURL(bad); fatalError("Accepted invalid base URL") } catch {}
        }
        assert(Policy.allowed("/v1/agent/runs?limit=20&before=123", method: "GET"))
        assert(Policy.allowed("/v1/agent/runs/" + id + "/resume", method: "POST"))
        for bad in ["https://evil.test", "/admin/v1/users", "/v1/agent/runs/../../admin", "/v1/agent/runs?redirect=https://evil.test", "/v1/me%2f.."] {
            assert(!Policy.allowed(bad, method: "GET"))
        }
        assert(!Policy.allowed("/v1/me", method: "POST"))
        assert(Policy.externalURL("https://example.com") != nil)
        assert(Policy.externalURL("file:///etc/passwd") == nil)
        assert(Policy.externalURL("https://user:pass@example.com") == nil)
        assert(Policy.filename("../../report.txt") == "report.txt")
        print("Native policy tests passed")
    }
}
