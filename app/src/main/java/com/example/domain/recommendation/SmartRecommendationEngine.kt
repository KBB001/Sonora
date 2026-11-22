package com.example.domain.recommendation

import com.example.core.common.NetworkMonitor
import com.example.domain.model.Track
import com.example.domain.repository.YouTubeRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmartRecommendationEngine @Inject constructor(
    private val moodProfiler: TrackMoodProfiler,
    private val tasteSignalTracker: TasteSignalTracker,
    private val youTubeRepository: YouTubeRepository,
    private val networkMonitor: NetworkMonitor
) {
    // Session-only deduplication store (cleared only on explicit reset, never capped by artist streak)
    private val sessionHeardIds = ConcurrentHashMap.newKeySet<String>()

    fun markHeardInSession(trackId: String) {
        if (trackId.isNotBlank()) {
            sessionHeardIds.add(trackId)
        }
    }

    fun isHeardInSession(trackId: String): Boolean = trackId in sessionHeardIds

    fun resetSessionHistory() {
        sessionHeardIds.clear()
    }

    fun sessionHeardCount(): Int = sessionHeardIds.size

    suspend fun generateNextQueueTracks(
        seedTrack: Track,
        targetCount: Int = 12,
        excludeIds: Set<String> = emptySet(),
        recentSkippedTracks: List<Track> = emptyList()
    ): List<Track> {
        if (targetCount <= 0 || !networkMonitor.isOnline.value) return emptyList()
        tasteSignalTracker.ensureHydrated()

        val blockedIds = HashSet<String>(excludeIds.size + sessionHeardIds.size + 1)
        blockedIds.addAll(excludeIds)
        blockedIds.addAll(sessionHeardIds)
        blockedIds.add(seedTrack.id)

        val candidates = LinkedHashMap<String, Track>()
        val related = youTubeRepository.getRelatedTracks(seedTrack.id).getOrDefault(emptyList())
        related.forEach { track ->
            if (track.id.isNotBlank() && track.id !in blockedIds) {
                candidates.putIfAbsent(track.id, track)
            }
        }

        val seedProfile = moodProfiler.profile(seedTrack)
        val skippedProfiles = recentSkippedTracks.map { moodProfiler.profile(it) }
        val rejectedMoods = skippedProfiles.map { it.mood }.toSet()

        // If related pool is small or user skipped recent mood 2+ times, enrich via targeted mood search
        if (candidates.size < targetCount || skippedProfiles.size >= 2) {
            val targetMood = if (skippedProfiles.size >= 2) {
                val contrasting = moodProfiler.contrastingMoodsFor(skippedProfiles.last().mood)
                tasteSignalTracker.topPreferredMood(excludingMoods = rejectedMoods)
                    ?: contrasting.firstOrNull()
                    ?: seedProfile.mood
            } else {
                seedProfile.mood
            }
            val query = moodProfiler.searchQueryForMood(targetMood)
            val fallbackTracks = youTubeRepository.searchTracks(query).getOrDefault(emptyList())
            fallbackTracks.forEach { track ->
                if (track.id.isNotBlank() && track.id !in blockedIds) {
                    candidates.putIfAbsent(track.id, track)
                }
            }
        }

        if (candidates.isEmpty()) return emptyList()
        return rankCandidates(
            candidates = candidates.values.toList(),
            seedTrack = seedTrack,
            recentSkippedTracks = recentSkippedTracks,
            preferContrasting = skippedProfiles.size >= 2
        ).take(targetCount)
    }

    /**
     * Softly shifts unplayed tracks in the existing queue without wiping or replacing them.
     * Tracks matching recently quick-skipped moods/artists move gently down, while contrasting
     * or higher-affinity tracks bubble up cleanly.
     */
    fun softRerankUpcoming(
        upcomingTracks: List<Track>,
        seedTrack: Track,
        recentSkippedTracks: List<Track>
    ): List<Track> {
        if (upcomingTracks.size <= 1) return upcomingTracks
        return rankCandidates(
            candidates = upcomingTracks,
            seedTrack = seedTrack,
            recentSkippedTracks = recentSkippedTracks,
            preferContrasting = recentSkippedTracks.size >= 2
        )
    }

    fun rankCandidates(
        candidates: List<Track>,
        seedTrack: Track,
        recentSkippedTracks: List<Track> = emptyList(),
        preferContrasting: Boolean = false
    ): List<Track> {
        if (candidates.size <= 1) return candidates
        val seedProfile = moodProfiler.profile(seedTrack)
        val skippedProfiles = recentSkippedTracks.map { moodProfiler.profile(it) }
        val rejectedMoods = skippedProfiles.map { it.mood }.toSet()
        val rejectedArtists = skippedProfiles
            .map { it.normalizedArtist }
            .filter { it != "unknown" }
            .toSet()

        val scored = candidates.mapIndexed { originalIndex, track ->
            val profile = moodProfiler.profile(track)
            val soundMatch = moodProfiler.similarityScore(seedProfile, profile)
            val tasteBonus = tasteSignalTracker.getPersonalAffinityBonus(track)

            val skipPenalty = when {
                profile.mood in rejectedMoods && preferContrasting -> -0.55f
                profile.mood in rejectedMoods -> -0.22f
                else -> 0f
            } + if (profile.normalizedArtist in rejectedArtists) -0.35f else 0f

            val contrastBoost = if (preferContrasting && profile.mood !in rejectedMoods) 0.25f else 0f

            // Weight sound similarity strongly, modulated by learned personal habit score and skip shift
            val totalScore = (soundMatch * 0.65f) + (tasteBonus * 0.45f) + skipPenalty + contrastBoost
            ScoredTrack(track = track, profile = profile, score = totalScore, originalIndex = originalIndex)
        }.sortedWith(compareByDescending<ScoredTrack> { it.score }.thenBy { it.originalIndex })

        // No hard artist limit per user choice: only preserve natural ranked order with soft tie-break
        return scored.map { it.track }
    }

    private data class ScoredTrack(
        val track: Track,
        val profile: com.example.domain.model.TrackAudioProfile,
        val score: Float,
        val originalIndex: Int
    )
}
