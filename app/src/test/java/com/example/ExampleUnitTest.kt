package com.example

import com.example.core.common.DurationFormatter
import com.example.core.common.SemverComparator
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
}
