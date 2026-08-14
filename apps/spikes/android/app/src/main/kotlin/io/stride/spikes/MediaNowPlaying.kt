package io.stride.spikes

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.util.Log

/**
 * S5 spike: a read-only view of "what is playing right now" plus transport controls.
 *
 * All media access is gated on [StrideNotificationListener] being a connected notification
 * listener, so every function here reuses [WorkoutMediaCoupling.activeControllers] rather than
 * re-enumerating sessions itself.
 */
object MediaNowPlaying {
    private const val TAG = "MediaNowPlaying"

    /** An immutable snapshot of whatever is playing right now. */
    data class Snapshot(
        val packageName: String,
        val title: String?,
        val artist: String?,
        val album: String?,
        val isPlaying: Boolean,
        val isVideo: Boolean,
        val durationMs: Long,     // -1 when unknown
        val positionMs: Long,     // -1 when unknown
        val canSkipNext: Boolean,
        val canSkipPrevious: Boolean,
    )

    /**
     * The most interesting active session, or null when nothing is loaded.
     *
     * Selection rule: prefer a session that is actually [PlaybackState.STATE_PLAYING]; if none is
     * playing, fall back to the most recently active session that carries metadata. The list from
     * [WorkoutMediaCoupling.activeControllers] is already ordered by system priority, so the first
     * match in each pass wins.
     */
    fun snapshot(context: Context): Snapshot? {
        return try {
            val chosen = chooseController(context) ?: return null
            toSnapshot(chosen)
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to read the current media snapshot.", t)
            null
        }
    }

    /** Album/cover art for the current session, or null. Caller must not recycle the bitmap. */
    fun artwork(context: Context): Bitmap? {
        return try {
            val metadata = chooseController(context)?.metadata ?: return null
            // Prefer full album art; fall back through the smaller keys some apps set instead. The
            // bitmap is owned by the session's metadata, so we neither scale nor recycle it.
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to read media artwork.", t)
            null
        }
    }

    /** True when something is actively playing *video*. */
    fun videoIsPlaying(context: Context): Boolean {
        return try {
            val snap = snapshot(context) ?: return false
            snap.isPlaying && snap.isVideo
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to determine whether video is playing.", t)
            false
        }
    }

    /** Transport controls. Each returns true when a session accepted the command. */
    fun playPause(context: Context): Boolean {
        return try {
            val controller = chooseController(context) ?: return false
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                controller.transportControls.pause()
            } else {
                controller.transportControls.play()
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to toggle play/pause.", t)
            false
        }
    }

    fun skipNext(context: Context): Boolean {
        return try {
            val controller = chooseController(context) ?: return false
            controller.transportControls.skipToNext()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to skip to next.", t)
            false
        }
    }

    fun skipPrevious(context: Context): Boolean {
        return try {
            val controller = chooseController(context) ?: return false
            controller.transportControls.skipToPrevious()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to skip to previous.", t)
            false
        }
    }

    /**
     * Decide whether a session is playing video from what the app itself declares, not from a
     * hardcoded package allowlist.
     *
     * [AudioAttributes.CONTENT_TYPE_MOVIE] is the app's own declaration: ExoPlayer-based video apps
     * (Jellyfin, Netflix) set it, while music apps declare `CONTENT_TYPE_MUSIC` or leave it
     * `CONTENT_TYPE_UNKNOWN`. This survives rebrands and new apps that an allowlist would miss.
     *
     * Fallback heuristic for apps that never set a content type: an actively-playing session with a
     * title but no artist and no album is almost certainly video, because a music track essentially
     * always carries at least an artist or an album, whereas a raw video stream usually carries
     * neither. It can misfire on a poorly-tagged audio file, hence it is only consulted when the app
     * gave us `CONTENT_TYPE_UNKNOWN`, and it demands genuine playback plus a title so an empty or
     * idle session never reports video merely because it is bare.
     */
    internal fun classifyVideo(
        contentType: Int,
        isPlaying: Boolean,
        title: String?,
        artist: String?,
        album: String?,
    ): Boolean {
        if (contentType == AudioAttributes.CONTENT_TYPE_MOVIE) return isPlaying
        if (contentType == AudioAttributes.CONTENT_TYPE_UNKNOWN) {
            return isPlaying && !title.isNullOrBlank() && artist.isNullOrBlank() && album.isNullOrBlank()
        }
        return false
    }

    /**
     * Controllers are never cached in fields: a [MediaController] goes stale the moment its session
     * is replaced (which happens routinely on app death), so every call re-reads the live list.
     */
    private fun chooseController(context: Context): MediaController? {
        val controllers = WorkoutMediaCoupling.activeControllers(context)
        if (controllers.isEmpty()) return null
        controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }?.let { return it }
        return controllers.firstOrNull { it.metadata != null }
    }

    private fun toSnapshot(controller: MediaController): Snapshot {
        val metadata = controller.metadata
        val playbackState = controller.playbackState
        val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)

        val contentType = controller.playbackInfo?.audioAttributes?.contentType
            ?: AudioAttributes.CONTENT_TYPE_UNKNOWN

        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L
        val position = playbackState?.position ?: -1L
        val actions = playbackState?.actions ?: 0L

        return Snapshot(
            packageName = controller.packageName,
            title = title,
            artist = artist,
            album = album,
            isPlaying = isPlaying,
            isVideo = classifyVideo(contentType, isPlaying, title, artist, album),
            durationMs = if (duration > 0L) duration else -1L,
            positionMs = position,
            canSkipNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
            canSkipPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
        )
    }
}
