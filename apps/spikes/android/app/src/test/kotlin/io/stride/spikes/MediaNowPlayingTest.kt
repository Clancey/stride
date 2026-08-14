package io.stride.spikes

import android.media.AudioAttributes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The only part of [MediaNowPlaying] that is testable without a live [android.media.session.MediaController]
 * is the video decision, so it is factored into [MediaNowPlaying.classifyVideo] and pinned here.
 * The public `isVideo` path calls the same function, so these cases guard real behaviour.
 */
class MediaNowPlayingTest {

    @Test
    fun `movie content type while playing is video`() {
        assertTrue(
            MediaNowPlaying.classifyVideo(
                contentType = AudioAttributes.CONTENT_TYPE_MOVIE,
                isPlaying = true,
                title = "Some Show",
                artist = null,
                album = null,
            )
        )
    }

    @Test
    fun `movie content type while paused is not playing video`() {
        assertFalse(
            MediaNowPlaying.classifyVideo(
                contentType = AudioAttributes.CONTENT_TYPE_MOVIE,
                isPlaying = false,
                title = "Some Show",
                artist = null,
                album = null,
            )
        )
    }

    @Test
    fun `music content type is never video`() {
        assertFalse(
            MediaNowPlaying.classifyVideo(
                contentType = AudioAttributes.CONTENT_TYPE_MUSIC,
                isPlaying = true,
                title = "A Song",
                artist = null,
                album = null,
            )
        )
    }

    @Test
    fun `unknown content type with an artist is not video`() {
        assertFalse(
            MediaNowPlaying.classifyVideo(
                contentType = AudioAttributes.CONTENT_TYPE_UNKNOWN,
                isPlaying = true,
                title = "A Song",
                artist = "An Artist",
                album = null,
            )
        )
    }

    @Test
    fun `unknown content type with an album is not video`() {
        assertFalse(
            MediaNowPlaying.classifyVideo(
                contentType = AudioAttributes.CONTENT_TYPE_UNKNOWN,
                isPlaying = true,
                title = "A Track",
                artist = null,
                album = "An Album",
            )
        )
    }

    @Test
    fun `unknown content type with a title but no artist or album while playing is video`() {
        assertTrue(
            MediaNowPlaying.classifyVideo(
                contentType = AudioAttributes.CONTENT_TYPE_UNKNOWN,
                isPlaying = true,
                title = "Big Buck Bunny",
                artist = null,
                album = null,
            )
        )
    }

    @Test
    fun `unknown content type with no title is not video even when playing`() {
        assertFalse(
            MediaNowPlaying.classifyVideo(
                contentType = AudioAttributes.CONTENT_TYPE_UNKNOWN,
                isPlaying = true,
                title = null,
                artist = null,
                album = null,
            )
        )
    }

    @Test
    fun `unknown content type is not video when nothing is playing`() {
        assertFalse(
            MediaNowPlaying.classifyVideo(
                contentType = AudioAttributes.CONTENT_TYPE_UNKNOWN,
                isPlaying = false,
                title = "Big Buck Bunny",
                artist = null,
                album = null,
            )
        )
    }
}
