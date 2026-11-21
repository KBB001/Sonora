import Foundation
import Security

final class KeychainAccountStore {
    private let service = "com.aistudio.sonora.ios.accounts"
    private let accountsKey = "youtube_accounts_v1"
    private let activeIdKey = "youtube_active_account_id_v1"
    private let autoSyncKey = "youtube_auto_sync_v1"

    func loadAccounts() -> [YouTubeAccount] {
        guard let data = readKeychainData(account: accountsKey) else { return [] }
        return (try? JSONDecoder().decode([YouTubeAccount].self, from: data)) ?? []
    }

    func saveAccounts(_ accounts: [YouTubeAccount]) {
        guard let data = try? JSONEncoder().encode(accounts) else { return }
        writeKeychainData(data, account: accountsKey)
    }

    func loadActiveAccountId() -> String? {
        guard let data = readKeychainData(account: activeIdKey) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func saveActiveAccountId(_ id: String?) {
        if let id, let data = id.data(using: .utf8) {
            writeKeychainData(data, account: activeIdKey)
        } else {
            deleteKeychainItem(account: activeIdKey)
        }
    }

    func loadAutoSyncEnabled() -> Bool {
        if UserDefaults.standard.object(forKey: autoSyncKey) == nil {
            return true
        }
        return UserDefaults.standard.bool(forKey: autoSyncKey)
    }

    func saveAutoSyncEnabled(_ enabled: Bool) {
        UserDefaults.standard.set(enabled, forKey: autoSyncKey)
    }

    func clearAll() {
        deleteKeychainItem(account: accountsKey)
        deleteKeychainItem(account: activeIdKey)
    }

    private func readKeychainData(account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess else { return nil }
        return result as? Data
    }

    private func writeKeychainData(_ data: Data, account: String) {
        deleteKeychainItem(account: account)
        let attributes: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        SecItemAdd(attributes as CFDictionary, nil)
    }

    private func deleteKeychainItem(account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(query as CFDictionary)
    }
}
