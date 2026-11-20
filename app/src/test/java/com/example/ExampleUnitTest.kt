package com.example

import com.example.core.common.DurationFormatter
import com.example.core.common.SemverComparator
import com.example.data.remote.YouTubeMusicAccountApi
import com.example.data.remote.YouTubeRepositoryImpl
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        val repo = YouTubeRepositoryImpl(okhttp3.OkHttpClient())
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
}
