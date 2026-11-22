package com.example.domain.recommendation

import com.example.data.local.dao.TrackSignalDao
import com.example.data.local.entity.TrackAffinityEntity
import com.example.domain.model.ImplicitSignalType
import com.example.domain.model.MoodCategory
import com.example.domain.model.TempoTier
import com.example.domain.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

@Singleton
class TasteSignalTracker @Inject constructor(
    private val trackSignalDao: TrackSignalDao,
    private val moodProfiler: TrackMoodProfiler
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    @Volatile
    private var hydrated = false

    private val trackScores = ConcurrentHashMap<String, TrackAffinityEntity>()
    private val artistScores = ConcurrentHashMap<String, Float>()
    private val moodScores = ConcurrentHashMap<MoodCategory, Float>()
    private val tempoScores = ConcurrentHashMap<TempoTier, Float>()

    init {
        scope.launch { ensureHydrated() }
    }

    suspend fun ensureHydrated() {
        if (hydrated) return
        writeMutex.withLock {
            if (hydrated) return
            val all = runCatching { trackSignalDao.getAllAffinities() }.getOrDefault(emptyList())
            val now = System.currentTimeMillis()
            trackScores.clear()
            artistScores.clear()
            moodScores.clear()
            tempoScores.clear()

            all.forEach { entity ->
                trackScores[entity.trackId] = entity
                val decay = timeDecayFactor(entity.lastInteractedAt, now)
                val decayedScore = entity.affinityScore * decay
                if (entity.normalizedArtist.isNotBlank() && entity.normalizedArtist != "unknown") {
                    artistScores[entity.normalizedArtist] =
                        ((artistScores[entity.normalizedArtist] ?: 0f) + decayedScore).coerceIn(-12f, 20f)
                }
                val mood = runCatching { MoodCategory.valueOf(entity.moodName) }.getOrDefault(MoodCategory.GENERAL)
                moodScores[mood] = ((moodScores[mood] ?: 0f) + decayedScore * 0.5f).coerceIn(-12f, 20f)
                val tempo = runCatching { TempoTier.valueOf(entity.tempoName) }.getOrDefault(TempoTier.MEDIUM)
                tempoScores[tempo] = ((tempoScores[tempo] ?: 0f) + decayedScore * 0.35f).coerceIn(-12f, 20f)
            }
            hydrated = true
        }
    }

    fun recordSignalAsync(track: Track, signalType: ImplicitSignalType) {
        scope.launch {
            recordSignal(track, signalType)
        }
    }

    suspend fun recordSignal(track: Track, signalType: ImplicitSignalType) {
        if (track.id.isBlank()) return
        ensureHydrated()
        val profile = moodProfiler.profile(track)
        val delta = weightForSignal(signalType)
        val now = System.currentTimeMillis()

        writeMutex.withLock {
            val existing = trackScores[track.id] ?: runCatching { trackSignalDao.getAffinity(track.id) }.getOrNull()
            val decayedExisting = if (existing != null) {
                existing.affinityScore * timeDecayFactor(existing.lastInteractedAt, now)
            } else {
                0f
            }
            val newAffinity = (decayedExisting + delta).coerceIn(-8f, 15f)
            val updated = TrackAffinityEntity(
                trackId = track.id,
                normalizedArtist = profile.normalizedArtist,
                moodName = profile.mood.name,
                tempoName = profile.tempo.name,
                completedCount = (existing?.completedCount ?: 0) +
                    if (signalType == ImplicitSignalType.COMPLETE_PLAY) 1 else 0,
                repeatCount = (existing?.repeatCount ?: 0) +
                    if (signalType == ImplicitSignalType.REPEAT_PLAY) 1 else 0,
                quickSkipCount = (existing?.quickSkipCount ?: 0) +
                    if (signalType == ImplicitSignalType.QUICK_SKIP) 1 else 0,
                partialSkipCount = (existing?.partialSkipCount ?: 0) +
                    if (signalType == ImplicitSignalType.PARTIAL_SKIP) 1 else 0,
                affinityScore = newAffinity,
                lastInteractedAt = now
            )

            trackScores[track.id] = updated
            if (profile.normalizedArtist != "unknown") {
                artistScores[profile.normalizedArtist] =
                    ((artistScores[profile.normalizedArtist] ?: 0f) + delta).coerceIn(-12f, 20f)
            }
            moodScores[profile.mood] =
                ((moodScores[profile.mood] ?: 0f) + delta * 0.5f).coerceIn(-12f, 20f)
            tempoScores[profile.tempo] =
                ((tempoScores[profile.tempo] ?: 0f) + delta * 0.35f).coerceIn(-12f, 20f)

            runCatching { trackSignalDao.upsertAffinity(updated) }
        }
    }

    fun getPersonalAffinityBonus(track: Track): Float {
        val profile = moodProfiler.profile(track)
        val now = System.currentTimeMillis()
        val trackEntity = trackScores[track.id]
        val trackComponent = if (trackEntity != null) {
            (trackEntity.affinityScore * timeDecayFactor(trackEntity.lastInteractedAt, now)) / 10f
        } else {
            0f
        }
        val artistComponent = ((artistScores[profile.normalizedArtist] ?: 0f) / 16f).coerceIn(-0.45f, 0.55f)
        val moodComponent = ((moodScores[profile.mood] ?: 0f) / 20f).coerceIn(-0.35f, 0.45f)
        val tempoComponent = ((tempoScores[profile.tempo] ?: 0f) / 24f).coerceIn(-0.20f, 0.30f)

        return (trackComponent * 0.45f + artistComponent * 0.30f + moodComponent * 0.15f + tempoComponent * 0.10f)
            .coerceIn(-0.85f, 1.0f)
    }

    fun topPreferredMood(excludingMoods: Set<MoodCategory> = emptySet()): MoodCategory? {
        return moodScores.entries
            .asSequence()
            .filter { it.key !in excludingMoods && it.value > 0.25f }
            .maxByOrNull { it.value }
            ?.key
    }

    private fun weightForSignal(signalType: ImplicitSignalType): Float = when (signalType) {
        ImplicitSignalType.REPEAT_PLAY -> 2.5f
        ImplicitSignalType.FAVORITE_ADDED -> 2.0f
        ImplicitSignalType.DOWNLOADED -> 1.8f
        ImplicitSignalType.COMPLETE_PLAY -> 1.5f
        ImplicitSignalType.PARTIAL_SKIP -> -0.45f
        ImplicitSignalType.FAVORITE_REMOVED -> -1.5f
        ImplicitSignalType.QUICK_SKIP -> -2.2f
    }

    private fun timeDecayFactor(lastTimestampMs: Long, nowMs: Long): Float {
        val elapsedDays = ((nowMs - lastTimestampMs).coerceAtLeast(0L)).toDouble() / MS_PER_DAY
        // Half-life ~14 days so old random skips gradually fade while recent taste dominates
        return exp(-0.05 * elapsedDays).toFloat().coerceIn(0.15f, 1.0f)
    }

    private companion object {
        const val MS_PER_DAY = 86_400_000.0
    }
}
