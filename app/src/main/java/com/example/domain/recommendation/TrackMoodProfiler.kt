package com.example.domain.recommendation

import com.example.domain.model.MoodCategory
import com.example.domain.model.TempoTier
import com.example.domain.model.Track
import com.example.domain.model.TrackAudioProfile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class TrackMoodProfiler @Inject constructor() {

    fun profile(track: Track): TrackAudioProfile {
        val text = "${track.title} ${track.artist}".lowercase()
        val tokens = tokenize(text)
        val normalizedArtist = normalizeArtist(track.artist)

        val moodScores = mutableMapOf<MoodCategory, Int>()
        MOOD_KEYWORDS.forEach { (mood, keywords) ->
            var score = 0
            keywords.forEach { kw ->
                if (kw.contains(' ')) {
                    if (text.contains(kw)) score += 3
                } else if (kw in tokens || text.contains(kw)) {
                    score += 2
                }
            }
            if (score > 0) moodScores[mood] = score
        }

        val detectedMood = moodScores.maxByOrNull { it.value }?.key ?: inferMoodFromDuration(track.durationSeconds)
        val (tempo, energy) = inferTempoAndEnergy(text, tokens, detectedMood, track.durationSeconds)
        val genreTokens = tokens.filter { it in GENRE_WHITELIST || it.length >= 4 }.take(12).toSet()

        return TrackAudioProfile(
            mood = detectedMood,
            tempo = tempo,
            energy = energy,
            genreTokens = genreTokens,
            normalizedArtist = normalizedArtist
        )
    }

    fun normalizeArtist(rawArtist: String): String {
        return rawArtist
            .lowercase()
            .replace(ARTIST_SUFFIX_REGEX, " ")
            .replace(NON_ALNUM_REGEX, " ")
            .trim()
            .replace(MULTI_SPACE_REGEX, " ")
            .ifBlank { "unknown" }
    }

    fun similarityScore(seed: TrackAudioProfile, candidate: TrackAudioProfile): Float {
        val moodScore = when {
            seed.mood == candidate.mood -> 1.0f
            candidate.mood in COMPATIBLE_MOODS[seed.mood].orEmpty() -> 0.65f
            seed.mood == MoodCategory.GENERAL || candidate.mood == MoodCategory.GENERAL -> 0.45f
            else -> 0.15f
        }

        val tempoDiff = abs(seed.tempo.ordinal - candidate.tempo.ordinal)
        val tempoScore = when (tempoDiff) {
            0 -> 1.0f
            1 -> 0.6f
            2 -> 0.25f
            else -> 0.05f
        }

        val energyScore = (1.0f - abs(seed.energy - candidate.energy)).coerceIn(0f, 1f)

        val tokenOverlap = if (seed.genreTokens.isNotEmpty() && candidate.genreTokens.isNotEmpty()) {
            val intersection = seed.genreTokens.intersect(candidate.genreTokens).size.toFloat()
            val union = seed.genreTokens.union(candidate.genreTokens).size.toFloat().coerceAtLeast(1f)
            (intersection / union).coerceIn(0f, 1f)
        } else {
            0f
        }

        val artistBonus = if (seed.normalizedArtist == candidate.normalizedArtist && seed.normalizedArtist != "unknown") {
            0.12f
        } else {
            0f
        }

        return (
            moodScore * 0.45f +
                tempoScore * 0.25f +
                energyScore * 0.20f +
                tokenOverlap * 0.10f +
                artistBonus
            ).coerceIn(0f, 1.15f)
    }

    fun searchQueryForMood(mood: MoodCategory): String = when (mood) {
        MoodCategory.ENERGETIC -> "upbeat electronic dance hits music audio"
        MoodCategory.WORKOUT_DRIVE -> "workout phonk energetic bass music audio"
        MoodCategory.CHILL_FOCUS -> "lofi chillhop study focus beats audio"
        MoodCategory.NIGHT_SYNTH -> "synthwave retrowave midnight electronic music"
        MoodCategory.ACOUSTIC_CALM -> "acoustic indie chill guitar mellow music"
        MoodCategory.MELANCHOLIC -> "emotional ambient indie ballad slowed music"
        MoodCategory.JAZZ_LOUNGE -> "smooth jazz cafe lounge instrumental music"
        MoodCategory.GENERAL -> "popular music mix audio"
    }

    fun contrastingMoodsFor(rejectedMood: MoodCategory): List<MoodCategory> = when (rejectedMood) {
        MoodCategory.WORKOUT_DRIVE, MoodCategory.ENERGETIC ->
            listOf(MoodCategory.CHILL_FOCUS, MoodCategory.ACOUSTIC_CALM, MoodCategory.NIGHT_SYNTH)
        MoodCategory.CHILL_FOCUS, MoodCategory.ACOUSTIC_CALM, MoodCategory.MELANCHOLIC ->
            listOf(MoodCategory.ENERGETIC, MoodCategory.NIGHT_SYNTH, MoodCategory.JAZZ_LOUNGE)
        MoodCategory.NIGHT_SYNTH ->
            listOf(MoodCategory.ACOUSTIC_CALM, MoodCategory.ENERGETIC, MoodCategory.CHILL_FOCUS)
        MoodCategory.JAZZ_LOUNGE ->
            listOf(MoodCategory.NIGHT_SYNTH, MoodCategory.ENERGETIC, MoodCategory.ACOUSTIC_CALM)
        MoodCategory.GENERAL ->
            listOf(MoodCategory.CHILL_FOCUS, MoodCategory.ENERGETIC, MoodCategory.NIGHT_SYNTH)
    }

    private fun inferMoodFromDuration(durationSeconds: Long): MoodCategory {
        return when {
            durationSeconds >= 420L -> MoodCategory.CHILL_FOCUS
            durationSeconds in 1L..165L -> MoodCategory.ENERGETIC
            else -> MoodCategory.GENERAL
        }
    }

    private fun inferTempoAndEnergy(
        text: String,
        tokens: Set<String>,
        mood: MoodCategory,
        durationSeconds: Long
    ): Pair<TempoTier, Float> {
        var energy = when (mood) {
            MoodCategory.WORKOUT_DRIVE -> 0.88f
            MoodCategory.ENERGETIC -> 0.78f
            MoodCategory.NIGHT_SYNTH -> 0.64f
            MoodCategory.GENERAL -> 0.54f
            MoodCategory.JAZZ_LOUNGE -> 0.36f
            MoodCategory.ACOUSTIC_CALM -> 0.30f
            MoodCategory.CHILL_FOCUS -> 0.28f
            MoodCategory.MELANCHOLIC -> 0.24f
        }

        if (SLOW_MODIFIERS.any { it in tokens || text.contains(it) }) {
            energy -= 0.22f
        }
        if (FAST_MODIFIERS.any { it in tokens || text.contains(it) }) {
            energy += 0.20f
        }
        if (durationSeconds in 1L..160L) {
            energy += 0.06f
        } else if (durationSeconds >= 360L) {
            energy -= 0.08f
        }

        val clamped = energy.coerceIn(0.05f, 0.98f)
        val tier = when {
            clamped >= 0.78f -> TempoTier.HIGH_ENERGY
            clamped >= 0.56f -> TempoTier.UPBEAT
            clamped >= 0.35f -> TempoTier.MEDIUM
            else -> TempoTier.SLOW
        }
        return tier to clamped
    }

    private fun tokenize(text: String): Set<String> {
        return text.split(TOKEN_SPLIT_REGEX)
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= 2 }
            .toSet()
    }

    private companion object {
        val TOKEN_SPLIT_REGEX = Regex("[^\\p{L}\\p{N}]+")
        val NON_ALNUM_REGEX = Regex("[^\\p{L}\\p{N}]+")
        val MULTI_SPACE_REGEX = Regex("\\s+")
        val ARTIST_SUFFIX_REGEX = Regex("\\b(topic|vevo|official|records|music)\\b", RegexOption.IGNORE_CASE)

        val SLOW_MODIFIERS = setOf(
            "slowed", "reverb", "acoustic", "piano", "lofi", "lo-fi", "chill", "ambient",
            "sleep", "study", "ballad", "sad", "unplugged", "lullaby", "медляк", "акустика", "спокойн"
        )
        val FAST_MODIFIERS = setOf(
            "remix", "nightcore", "sped up", "speed up", "phonk", "techno", "house", "dnb",
            "drum", "bass", "hardstyle", "workout", "gym", "club", "rave", "rock", "metal", "edm", "ремикс"
        )

        val MOOD_KEYWORDS: Map<MoodCategory, List<String>> = mapOf(
            MoodCategory.WORKOUT_DRIVE to listOf("phonk", "gym", "workout", "drift", "hardstyle", "bass boosted", "metal", "trap", "трениров", "спорт"),
            MoodCategory.ENERGETIC to listOf("dance", "edm", "house", "club", "party", "pop", "disco", "remix", "upbeat", "электрон", "драйв", "хит"),
            MoodCategory.NIGHT_SYNTH to listOf("synthwave", "retrowave", "cyberpunk", "vaporwave", "outrun", "night drive", "darksynth", "синтвейв"),
            MoodCategory.CHILL_FOCUS to listOf("lofi", "lo-fi", "chillhop", "focus", "study", "ambient", "instrumental", "beats to", "фокус", "чилл"),
            MoodCategory.ACOUSTIC_CALM to listOf("acoustic", "unplugged", "folk", "guitar", "indie", "singer", "piano", "акустик", "гитар"),
            MoodCategory.MELANCHOLIC to listOf("slowed", "reverb", "sad", "melancholy", "ballad", "cry", "lonely", "грустн", "лирик"),
            MoodCategory.JAZZ_LOUNGE to listOf("jazz", "blues", "bossa", "lounge", "sax", "soul", "джаз", "блюз")
        )

        val COMPATIBLE_MOODS: Map<MoodCategory, Set<MoodCategory>> = mapOf(
            MoodCategory.ENERGETIC to setOf(MoodCategory.WORKOUT_DRIVE, MoodCategory.NIGHT_SYNTH),
            MoodCategory.WORKOUT_DRIVE to setOf(MoodCategory.ENERGETIC, MoodCategory.NIGHT_SYNTH),
            MoodCategory.NIGHT_SYNTH to setOf(MoodCategory.ENERGETIC, MoodCategory.CHILL_FOCUS),
            MoodCategory.CHILL_FOCUS to setOf(MoodCategory.ACOUSTIC_CALM, MoodCategory.JAZZ_LOUNGE, MoodCategory.NIGHT_SYNTH),
            MoodCategory.ACOUSTIC_CALM to setOf(MoodCategory.CHILL_FOCUS, MoodCategory.MELANCHOLIC, MoodCategory.JAZZ_LOUNGE),
            MoodCategory.MELANCHOLIC to setOf(MoodCategory.ACOUSTIC_CALM, MoodCategory.CHILL_FOCUS),
            MoodCategory.JAZZ_LOUNGE to setOf(MoodCategory.CHILL_FOCUS, MoodCategory.ACOUSTIC_CALM),
            MoodCategory.GENERAL to setOf(MoodCategory.ENERGETIC, MoodCategory.CHILL_FOCUS, MoodCategory.ACOUSTIC_CALM)
        )

        val GENRE_WHITELIST = setOf(
            "rock", "pop", "rap", "hip", "hop", "jazz", "blues", "lofi", "synthwave",
            "house", "techno", "indie", "metal", "folk", "acoustic", "ambient", "phonk",
            "edm", "remix", "live", "instrumental", "piano", "guitar", "soul", "funk",
            "рок", "поп", "рэп", "джаз", "акустика", "инди", "электроника"
        )
    }
}
