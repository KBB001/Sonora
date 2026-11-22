package com.example

import com.example.core.common.DurationFormatter
import com.example.core.common.FriendlyErrorMapper
import com.example.core.common.SemverComparator
import com.example.data.remote.LyricsRepository
import com.example.data.remote.YouTubeMusicAccountApi
import com.example.data.remote.YouTubeRepositoryImpl
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class ExampleUnitTest {
    @Test
    fun durationFormatter_formatsMinutesAndHours() {
        assertEquals("0:00", DurationFormatter.formatSeconds(0))
        assertEquals("3:45", DurationFormatter.formatSeconds(225))
        assertEquals("1:02:05", DurationFormatter.formatSeconds(3725))
    }

    @Test
    fun semverComparator_comparesTagsAccurately() {
        assertTrue(SemverComparator.isNewerVersion("v1.0.1", "1.0.0"))
        assertTrue(SemverComparator.isNewerVersion("v1.0.10", "1.0.2"))
        assertTrue(SemverComparator.isNewerVersion("2.0", "1.9.9"))
        assertTrue(SemverComparator.isNewerVersion("v1.2.0", "v1.2.0-beta.1"))
        assertFalse(SemverComparator.isNewerVersion("v1.0.0", "1.0.0"))
        assertFalse(SemverComparator.isNewerVersion("v1.0", "1.0.0"))
        assertFalse(SemverComparator.isNewerVersion("v0.9.9", "1.0.0"))
        assertFalse(SemverComparator.isNewerVersion("invalid-tag", "1.0.0"))
    }

    @Test
    fun youtubeRepository_handlesEmptyInputGracefully() = runTest {
        val repo = YouTubeRepositoryImpl(OkHttpClient())
        val searchResult = repo.searchTracks("   ")
        assertTrue(searchResult.isSuccess)
        assertEquals(emptyList<Any>(), searchResult.getOrThrow())

        val streamResult = repo.getAudioStreamUrl("   ")
        assertTrue(streamResult.isFailure)

        val playlistResult = repo.getPlaylistTracks("")
        assertTrue(playlistResult.isFailure)
    }

    @Test
    fun youtubeAccountApi_validatesCookiesAndBuildsSapisidHash() {
        val validCookies = "SID=abc123; SAPISID=xyz789/hash; HSID=def456"
        assertTrue(YouTubeMusicAccountApi.hasAuthCookies(validCookies))
        assertEquals("xyz789/hash", YouTubeMusicAccountApi.extractSapisid(validCookies))

        val authHeader = YouTubeMusicAccountApi.buildSapisidHashHeader(
            sapisid = "xyz789/hash",
            origin = YouTubeMusicAccountApi.YOUTUBE_MUSIC_ORIGIN,
            timestampSeconds = 1700000000L
        )
        assertTrue(authHeader.startsWith("SAPISIDHASH 1700000000_"))
        assertEquals(40, authHeader.substringAfter('_').length)

        val netscapeCookies = """
            # Netscape HTTP Cookie File
            .youtube.com	TRUE	/	TRUE	1800000000	__Secure-3PAPISID	sapi_val
            .youtube.com	TRUE	/	TRUE	1800000000	__Secure-3PSID	sid_val
        """.trimIndent()
        val normalized = YouTubeMusicAccountApi.normalizeCookieHeader(netscapeCookies)
        assertTrue(YouTubeMusicAccountApi.hasAuthCookies(normalized))
        assertEquals("sapi_val", YouTubeMusicAccountApi.extractSapisid(normalized))
        assertFalse(YouTubeMusicAccountApi.hasAuthCookies("VISITOR_INFO1_LIVE=only_guest"))

        // Personal account DATASYNC_ID ("112233||") must NOT produce a delegated Brand Page ID
        org.junit.Assert.assertNull(YouTubeMusicAccountApi.extractDelegatedBrandPageId("112233445566||"))
        org.junit.Assert.assertNull(YouTubeMusicAccountApi.extractDelegatedBrandPageId("112233445566||112233445566"))
        assertEquals("1099887766", YouTubeMusicAccountApi.extractDelegatedBrandPageId("1099887766||112233445566"))

        val multiAuth = YouTubeMusicAccountApi.buildSapisidAuthorizationHeader(
            cookieHeader = "SAPISID=a; __Secure-1PAPISID=b; __Secure-3PAPISID=c; SID=s",
            timestampSeconds = 1700000000L
        ).orEmpty()
        assertTrue(multiAuth.contains("SAPISIDHASH 1700000000_"))
        assertTrue(multiAuth.contains("SAPISID1PHASH 1700000000_"))
        assertTrue(multiAuth.contains("SAPISID3PHASH 1700000000_"))
    }

    @Test
    fun friendlyErrorMapper_mapsNetworkAndPreservesRussianErrors() {
        val fallback = "Резервное сообщение"
        assertTrue(
            FriendlyErrorMapper.map(UnknownHostException("Unable to resolve host"), fallback)
                .contains("Нет подключения к интернету")
        )
        assertTrue(
            FriendlyErrorMapper.map(SocketTimeoutException("timeout"), fallback)
                .contains("Сервер долго не отвечает")
        )
        assertTrue(
            FriendlyErrorMapper.map(SSLException("handshake"), fallback)
                .contains("SSL")
        )
        assertTrue(
            FriendlyErrorMapper.map(IllegalStateException("HTTP 403 Forbidden"), fallback)
                .contains("Доступ к потоку")
        )
        assertTrue(
            FriendlyErrorMapper.map(IllegalStateException("HTTP 429"), fallback)
                .contains("Слишком много запросов")
        )
        assertEquals(
            "Трек временно недоступен",
            FriendlyErrorMapper.map(IllegalStateException("Трек временно недоступен"), fallback)
        )
        assertEquals(
            fallback,
            FriendlyErrorMapper.map(NullPointerException("java.lang.NullPointerException"), fallback)
        )
    }

    @Test
    fun lyricsRepository_parsesMultiTimestampLrcAndHandlesBlankTitle() = runTest {
        val repo = LyricsRepository(OkHttpClient())
        val lrc = """
            [ti:Test Song]
            [00:12.00][00:45.5]Chorus line
            [00:20.123]Verse line
            [00:30.00]
        """.trimIndent()

        val lines = repo.parseLrcLines(lrc)
        assertEquals(3, lines.size)
        assertEquals(12_000L, lines[0].timeMs)
        assertEquals("Chorus line", lines[0].text)
        assertEquals(20_123L, lines[1].timeMs)
        assertEquals("Verse line", lines[1].text)
        assertEquals(45_500L, lines[2].timeMs)
        assertEquals("Chorus line", lines[2].text)

        val blankResult = repo.fetchLyrics("   ", "Artist", 180L)
        assertTrue(blankResult.isFailure)
        assertEquals("Текст песни не найден", blankResult.exceptionOrNull()?.message)
    }

    @Test
    fun trackMoodProfiler_classifiesMoodsTempoAndComputesSimilarity() {
        val profiler = com.example.domain.recommendation.TrackMoodProfiler()
        val phonk = com.example.domain.model.Track(
            id = "p1",
            title = "Midnight Drift Phonk Gym",
            artist = "FastArtist",
            durationSeconds = 140,
            thumbnailUrl = ""
        )
        val lofi1 = com.example.domain.model.Track(
            id = "l1",
            title = "Rainy Window Lofi Beats to Study",
            artist = "ChillArtist - Topic",
            durationSeconds = 190,
            thumbnailUrl = ""
        )
        val lofi2 = com.example.domain.model.Track(
            id = "l2",
            title = "Warm Coffee Chillhop Instrumental",
            artist = "OtherChill",
            durationSeconds = 210,
            thumbnailUrl = ""
        )

        val phonkProfile = profiler.profile(phonk)
        val lofi1Profile = profiler.profile(lofi1)
        val lofi2Profile = profiler.profile(lofi2)

        assertEquals(com.example.domain.model.MoodCategory.WORKOUT_DRIVE, phonkProfile.mood)
        assertEquals(com.example.domain.model.TempoTier.HIGH_ENERGY, phonkProfile.tempo)
        assertEquals(com.example.domain.model.MoodCategory.CHILL_FOCUS, lofi1Profile.mood)
        assertEquals("chillartist", lofi1Profile.normalizedArtist)

        val sameMoodSim = profiler.similarityScore(lofi1Profile, lofi2Profile)
        val crossMoodSim = profiler.similarityScore(lofi1Profile, phonkProfile)
        assertTrue("Expected chill tracks similarity ($sameMoodSim) > cross mood ($crossMoodSim)", sameMoodSim > crossMoodSim)

        val contrasting = profiler.contrastingMoodsFor(com.example.domain.model.MoodCategory.WORKOUT_DRIVE)
        assertTrue(com.example.domain.model.MoodCategory.CHILL_FOCUS in contrasting)
        assertFalse(com.example.domain.model.MoodCategory.WORKOUT_DRIVE in contrasting)
    }
}
