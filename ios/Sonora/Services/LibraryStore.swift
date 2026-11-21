import Foundation
import Combine

@MainActor
final class LibraryStore: ObservableObject {
    @Published private(set) var favorites: [Track] = []
    @Published private(set) var history: [Track] = []
    @Published private(set) var playlists: [Playlist] = []
    @Published private(set) var searchHistory: [String] = []
    @Published private(set) var downloadedTracks: [Track] = []
    @Published var audioQuality: AudioQuality = .high {
        didSet { saveSettings() }
    }
    @Published var themeMode: AppThemeMode = .dark {
        didSet { saveSettings() }
    }

    private struct Snapshot: Codable {
        var favorites: [Track]
        var history: [Track]
        var playlists: [Playlist]
        var searchHistory: [String]
        var downloadedTracks: [Track]
    }

    private struct StoredSettings: Codable {
        var audioQuality: AudioQuality
        var themeMode: AppThemeMode
    }

    private let fileManager = FileManager.default
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    var favoriteIds: Set<String> {
        Set(favorites.map(\.id))
    }

    var downloadedIds: Set<String> {
        Set(downloadedTracks.map(\.id))
    }

    init() {
        loadSettings()
        loadSnapshot()
    }

    private var appSupportDir: URL {
        let base = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? fileManager.temporaryDirectory
        let dir = base.appendingPathComponent("Sonora", isDirectory: true)
        if !fileManager.fileExists(atPath: dir.path) {
            try? fileManager.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        return dir
    }

    var offlineTracksDirectory: URL {
        let dir = appSupportDir.appendingPathComponent("offline_tracks", isDirectory: true)
        if !fileManager.fileExists(atPath: dir.path) {
            try? fileManager.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        return dir
    }

    private var libraryFileUrl: URL {
        appSupportDir.appendingPathComponent("sonora_library.json")
    }

    private var settingsFileUrl: URL {
        appSupportDir.appendingPathComponent("sonora_settings.json")
    }

    func isFavorite(_ trackId: String) -> Bool {
        favoriteIds.contains(trackId)
    }

    func toggleFavorite(_ track: Track) -> Bool {
        if let idx = favorites.firstIndex(where: { $0.id == track.id }) {
            favorites.remove(at: idx)
            saveSnapshot()
            return false
        } else {
            favorites.insert(track, at: 0)
            saveSnapshot()
            return true
        }
    }

    func recordPlayback(_ track: Track) {
        history.removeAll { $0.id == track.id }
        history.insert(track, at: 0)
        if history.count > 100 {
            history = Array(history.prefix(100))
        }
        saveSnapshot()
    }

    func clearHistory() {
        history.removeAll()
        saveSnapshot()
    }

    func recordSearchQuery(_ query: String) {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        searchHistory.removeAll { $0.caseInsensitiveCompare(trimmed) == .orderedSame }
        searchHistory.insert(trimmed, at: 0)
        if searchHistory.count > 20 {
            searchHistory = Array(searchHistory.prefix(20))
        }
        saveSnapshot()
    }

    func removeSearchQuery(_ query: String) {
        searchHistory.removeAll { $0 == query }
        saveSnapshot()
    }

    @discardableResult
    func createPlaylist(
        name: String,
        description: String = "",
        initialTracks: [Track] = [],
        youtubePlaylistId: String? = nil
    ) -> Playlist {
        let cleanName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let playlist = Playlist(
            id: UUID().uuidString,
            name: cleanName.isEmpty ? "Без названия" : cleanName,
            description: description.trimmingCharacters(in: .whitespacesAndNewlines),
            coverUrl: initialTracks.first?.thumbnailUrl,
            youtubePlaylistId: youtubePlaylistId,
            tracks: initialTracks,
            createdAt: Int64(Date().timeIntervalSince1970 * 1000)
        )
        playlists.insert(playlist, at: 0)
        saveSnapshot()
        return playlist
    }

    func deletePlaylist(id: String) {
        playlists.removeAll { $0.id == id }
        saveSnapshot()
    }

    func addTrack(_ track: Track, toPlaylistId playlistId: String) {
        guard let idx = playlists.firstIndex(where: { $0.id == playlistId }) else { return }
        if !playlists[idx].tracks.contains(where: { $0.id == track.id }) {
            playlists[idx].tracks.append(track)
            if playlists[idx].coverUrl == nil {
                playlists[idx].coverUrl = track.thumbnailUrl
            }
            saveSnapshot()
        }
    }

    func removeTrack(trackId: String, fromPlaylistId playlistId: String) {
        guard let idx = playlists.firstIndex(where: { $0.id == playlistId }) else { return }
        playlists[idx].tracks.removeAll { $0.id == trackId }
        saveSnapshot()
    }

    func updatePlaylistYouTubeId(_ localId: String, youtubePlaylistId: String) {
        guard let idx = playlists.firstIndex(where: { $0.id == localId }) else { return }
        playlists[idx].youtubePlaylistId = youtubePlaylistId
        saveSnapshot()
    }

    func mergeFavorites(_ incoming: [Track]) -> Int {
        let existing = favoriteIds
        var added = 0
        for track in incoming.reversed() where !existing.contains(track.id) && !track.id.isEmpty {
            favorites.insert(track, at: 0)
            added += 1
        }
        if added > 0 { saveSnapshot() }
        return added
    }

    func mergeHistory(_ incoming: [Track]) -> Int {
        let existing = Set(history.map(\.id))
        var added = 0
        for track in incoming.reversed() where !existing.contains(track.id) && !track.id.isEmpty {
            history.insert(track, at: 0)
            added += 1
        }
        if added > 0 {
            history = Array(history.prefix(150))
            saveSnapshot()
        }
        return added
    }

    func mergePlaylist(
        name: String,
        description: String,
        youtubePlaylistId: String?,
        tracks: [Track]
    ) -> Int {
        if let ytId = youtubePlaylistId, !ytId.isEmpty,
           let idx = playlists.firstIndex(where: { $0.youtubePlaylistId == ytId }) {
            return appendMissing(tracks, toIndex: idx, youtubePlaylistId: ytId)
        }
        if let idx = playlists.firstIndex(where: { $0.name.caseInsensitiveCompare(name) == .orderedSame }) {
            return appendMissing(tracks, toIndex: idx, youtubePlaylistId: youtubePlaylistId)
        }
        createPlaylist(
            name: name,
            description: description,
            initialTracks: tracks,
            youtubePlaylistId: youtubePlaylistId
        )
        return tracks.count
    }

    private func appendMissing(_ tracks: [Track], toIndex idx: Int, youtubePlaylistId: String?) -> Int {
        if let ytId = youtubePlaylistId, !ytId.isEmpty, (playlists[idx].youtubePlaylistId ?? "").isEmpty {
            playlists[idx].youtubePlaylistId = ytId
        }
        let existing = Set(playlists[idx].tracks.map(\.id))
        let missing = tracks.filter { !existing.contains($0.id) }
        if !missing.isEmpty {
            playlists[idx].tracks.append(contentsOf: missing)
            if playlists[idx].coverUrl == nil {
                playlists[idx].coverUrl = missing.first?.thumbnailUrl
            }
            saveSnapshot()
        }
        return missing.count
    }

    func offlineFileUrl(for trackId: String) -> URL? {
        let safeId = sanitizedTrackId(trackId)
        let url = offlineTracksDirectory.appendingPathComponent("\(safeId).m4a")
        return fileManager.fileExists(atPath: url.path) ? url : nil
    }

    func saveOfflineTrack(_ track: Track, fromTemporaryFile tempUrl: URL) throws {
        let safeId = sanitizedTrackId(track.id)
        let destination = offlineTracksDirectory.appendingPathComponent("\(safeId).m4a")
        let basePath = offlineTracksDirectory.standardizedFileURL.path + "/"
        guard destination.standardizedFileURL.path.hasPrefix(basePath) else {
            throw NSError(domain: "Sonora", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid file path"])
        }
        if fileManager.fileExists(atPath: destination.path) {
            try fileManager.removeItem(at: destination)
        }
        try fileManager.moveItem(at: tempUrl, to: destination)
        if !downloadedTracks.contains(where: { $0.id == track.id }) {
            downloadedTracks.insert(track, at: 0)
            saveSnapshot()
        }
    }

    func removeOfflineTrack(_ trackId: String) {
        let safeId = sanitizedTrackId(trackId)
        let url = offlineTracksDirectory.appendingPathComponent("\(safeId).m4a")
        try? fileManager.removeItem(at: url)
        downloadedTracks.removeAll { $0.id == trackId }
        saveSnapshot()
    }

    func exportBackupJson() throws -> Data {
        let backup = LibraryBackup(
            favorites: favorites,
            history: history,
            playlists: playlists.map {
                BackupPlaylist(name: $0.name, description: $0.description, tracks: $0.tracks)
            }
        )
        let prettyEncoder = JSONEncoder()
        prettyEncoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try prettyEncoder.encode(backup)
    }

    func importBackupJson(_ data: Data) throws -> Int {
        guard data.count <= 10 * 1024 * 1024 else {
            throw NSError(domain: "Sonora", code: -2, userInfo: [NSLocalizedDescriptionKey: "Файл слишком большой"])
        }
        let backup = try decoder.decode(LibraryBackup.self, from: data)
        var restored = 0
        restored += mergeFavorites(backup.favorites.prefix(5000).map { $0 })
        _ = mergeHistory(backup.history.prefix(5000).map { $0 })
        for item in backup.playlists.prefix(200) {
            restored += mergePlaylist(
                name: String(item.name.prefix(120)),
                description: String(item.description.prefix(500)),
                youtubePlaylistId: nil,
                tracks: Array(item.tracks.prefix(5000))
            )
        }
        return restored
    }

    private func sanitizedTrackId(_ id: String) -> String {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "_-"))
        let cleaned = id.unicodeScalars.map { allowed.contains($0) ? Character($0) : "_" }
        let result = String(cleaned)
        return result.isEmpty ? "track_\(abs(id.hashValue))" : result
    }

    private func loadSnapshot() {
        guard let data = try? Data(contentsOf: libraryFileUrl),
              let snap = try? decoder.decode(Snapshot.self, from: data) else {
            return
        }
        favorites = snap.favorites
        history = snap.history
        playlists = snap.playlists
        searchHistory = snap.searchHistory
        downloadedTracks = snap.downloadedTracks.filter { offlineFileUrl(for: $0.id) != nil }
    }

    private func saveSnapshot() {
        let snap = Snapshot(
            favorites: favorites,
            history: history,
            playlists: playlists,
            searchHistory: searchHistory,
            downloadedTracks: downloadedTracks
        )
        if let data = try? encoder.encode(snap) {
            try? data.write(to: libraryFileUrl, options: [.atomic, .completeFileProtection])
        }
    }

    private func loadSettings() {
        guard let data = try? Data(contentsOf: settingsFileUrl),
              let saved = try? decoder.decode(StoredSettings.self, from: data) else {
            return
        }
        audioQuality = saved.audioQuality
        themeMode = saved.themeMode
    }

    private func saveSettings() {
        let stored = StoredSettings(audioQuality: audioQuality, themeMode: themeMode)
        if let data = try? encoder.encode(stored) {
            try? data.write(to: settingsFileUrl, options: [.atomic])
        }
    }
}
