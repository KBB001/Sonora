import Foundation

struct Track: Identifiable, Codable, Hashable {
    let id: String
    var title: String
    var artist: String
    var durationSeconds: Int64
    var thumbnailUrl: String

    var formattedDuration: String {
        guard durationSeconds > 0 else { return "--:--" }
        let hours = durationSeconds / 3600
        let mins = (durationSeconds % 3600) / 60
        let secs = durationSeconds % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, mins, secs)
        }
        return String(format: "%d:%02d", mins, secs)
    }
}

struct Playlist: Identifiable, Codable, Hashable {
    let id: String
    var name: String
    var description: String
    var coverUrl: String?
    var youtubePlaylistId: String?
    var tracks: [Track]
    var createdAt: Int64
}

struct YouTubeAccount: Identifiable, Codable, Hashable {
    let id: String
    var name: String
    var handle: String
    var email: String
    var avatarUrl: String
    var cookieHeader: String
    var visitorData: String
    var dataSyncId: String
    var authUserIndex: Int
    var syncedLikedCount: Int
    var syncedPlaylistsCount: Int
    var lastSyncTimestamp: Int64
}

struct YouTubeUserPlaylist: Identifiable, Hashable {
    var id: String { playlistId }
    let playlistId: String
    let title: String
    let thumbnailUrl: String
    let trackCount: Int
}

struct AccountSyncSummary: Hashable {
    let importedLiked: Int
    let uploadedLiked: Int
    let importedPlaylists: Int
    let uploadedPlaylists: Int
    let importedHistory: Int
    let mergedAccountsCount: Int
}

enum AccountSyncState: Equatable {
    case idle
    case syncing(stage: String, progress: Double)
    case completed(AccountSyncSummary)
    case error(String)
}

enum AudioQuality: String, CaseIterable, Codable, Identifiable {
    case low = "LOW"
    case medium = "MEDIUM"
    case high = "HIGH"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .low: return "Экономия трафика (~64 кбит/с)"
        case .medium: return "Обычное (~128 кбит/с)"
        case .high: return "Высокое (~256+ кбит/с)"
        }
    }

    var maxBitrateBps: Int64 {
        switch self {
        case .low: return 72_000
        case .medium: return 140_000
        case .high: return 320_000
        }
    }
}

enum AppThemeMode: String, CaseIterable, Codable, Identifiable {
    case dark = "DARK"
    case light = "LIGHT"
    case system = "SYSTEM"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .dark: return "Тёмная"
        case .light: return "Светлая"
        case .system: return "Системная"
        }
    }
}

struct LyricLine: Identifiable, Hashable {
    let id = UUID()
    let timeMs: Int64
    let text: String
}

struct BackupPlaylist: Codable {
    let name: String
    let description: String
    let tracks: [Track]
}

struct LibraryBackup: Codable {
    let favorites: [Track]
    let history: [Track]
    let playlists: [BackupPlaylist]
}
