import Foundation
import Security
import CryptoKit

enum Vault {
    static let service = "com.mozhi.assistant.mac"

    static func get(_ account: String) throws -> Data? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service, kSecAttrAccount as String: account,
            kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw ClientError.message("无法读取钥匙串，请在系统设置中允许墨知访问。") }
        return result as? Data
    }

    static func set(_ account: String, _ data: Data) throws {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service, kSecAttrAccount as String: account]
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var item = query
            item[kSecValueData as String] = data
            item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else { throw ClientError.message("无法保存到系统钥匙串。") }
        } else if status != errSecSuccess { throw ClientError.message("无法更新系统钥匙串。") }
    }

    static func delete(_ account: String) throws {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service, kSecAttrAccount as String: account]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw ClientError.message("无法从钥匙串移除凭证。") }
    }

    static func stateURL(_ scope: String) throws -> URL {
        let directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                    appropriateFor: nil, create: true).appendingPathComponent("Mozhi", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        return directory.appendingPathComponent(scope + ".sealed")
    }

    static func key(create: Bool) throws -> SymmetricKey {
        if let data = try get("vault-key") { return SymmetricKey(data: data) }
        guard create else { throw ClientError.message("加密记录的钥匙串密钥丢失。请保留原文件，不要覆盖。") }
        let key = SymmetricKey(size: .bits256)
        try set("vault-key", key.withUnsafeBytes { Data($0) })
        return key
    }

    static func read(_ scope: String) throws -> Any {
        let url = try stateURL(scope)
        guard FileManager.default.fileExists(atPath: url.path) else { return [String: Any]() }
        let sealed = try Data(contentsOf: url)
        do {
            let data = try AES.GCM.open(AES.GCM.SealedBox(combined: sealed), using: key(create: false), authenticating: Data(scope.utf8))
            return try JSONSerialization.jsonObject(with: data)
        } catch { throw ClientError.message("本机加密记录读取失败，原文件已保留。请勿清空钥匙串。") }
    }

    static func write(_ scope: String, value: Any) throws {
        guard JSONSerialization.isValidJSONObject(value) else { throw ClientError.message("记录格式无效。") }
        let data = try JSONSerialization.data(withJSONObject: value)
        guard data.count <= 20 * 1024 * 1024 else { throw ClientError.message("本机记录已达 20 MiB，请先导出并整理。") }
        let url = try stateURL(scope)
        let exists = FileManager.default.fileExists(atPath: url.path)
        let sealed = try AES.GCM.seal(data, using: key(create: !exists), authenticating: Data(scope.utf8))
        guard let combined = sealed.combined else { throw ClientError.message("记录加密失败。") }
        try combined.write(to: url, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }

    static func scope(base: URL, user: String) -> String {
        SHA256.hash(data: Data((base.absoluteString + "\n" + user).utf8)).map { String(format: "%02x", $0) }.joined()
    }
}
