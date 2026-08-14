package io.stride.spikes

import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

/**
 * S5 spike: honest media pause/resume ownership.
 *
 * Plan section 3.2 / Phase 3 promises Stride "pauses only what is playing, remembers exactly what it
 * paused, and resumes only those, only if the user did not intervene". The naive package-keyed
 * approach cannot deliver that, so this tracker does the real work:
 *
 *  - Identity is the [MediaSession.Token], not the package name. Two sessions from one package stay
 *    distinct, and a replacement session created after app death gets a fresh token that never
 *    matches an owned one - so we never resume something we did not pause.
 *  - Ownership is recorded only after a registered [MediaController.Callback] OBSERVES the playing
 *    -> paused transition we asked for. A pause that is ignored never becomes ownership.
 *  - Any intervening user-driven transition (the user pressing play, or the session stopping)
 *    invalidates ownership, so we will not fight the user or resume something they touched.
 *  - Ownership is dropped when the session is destroyed (onSessionDestroyed), not merely when it
 *    momentarily disappears from getActiveSessions.
 *
 * This is a process-wide singleton so ownership survives SpikeBridge / activity recreation. It never
 * touches motor state; it only observes and toggles third-party media playback.
 */
object MediaOwnershipTracker {

    private val handler = Handler(Looper.getMainLooper())

    private class Tracked(
        val token: MediaSession.Token,
        val controller: MediaController,
        val callback: MediaController.Callback,
        var lastState: Int,
        /** We asked this session to pause and are waiting to observe it actually pause. */
        var pendingPause: Boolean,
        /** We paused it and observed the pause; a resume should restore exactly this. */
        var ownedByUs: Boolean,
    )

    private val sessions = HashMap<MediaSession.Token, Tracked>()

    private fun stateOf(controller: MediaController): Int =
        controller.playbackState?.state ?: PlaybackState.STATE_NONE

    private fun isTerminal(state: Int): Boolean =
        state == PlaybackState.STATE_STOPPED ||
            state == PlaybackState.STATE_NONE ||
            state == PlaybackState.STATE_ERROR

    /**
     * Reconcile our tracked set with the currently active controllers, registering a callback for
     * any session we have not seen before. Sessions that merely vanish from this list are NOT
     * dropped here - only onSessionDestroyed drops them, per the requirement above.
     */
    @Synchronized
    fun sync(controllers: List<MediaController>) {
        for (controller in controllers) {
            val token = controller.sessionToken
            if (sessions.containsKey(token)) continue

            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    onStateChanged(token, state?.state ?: PlaybackState.STATE_NONE)
                }

                override fun onSessionDestroyed() {
                    onDestroyed(token)
                }
            }
            controller.registerCallback(callback, handler)
            sessions[token] = Tracked(
                token = token,
                controller = controller,
                callback = callback,
                lastState = stateOf(controller),
                pendingPause = false,
                ownedByUs = false,
            )
        }
    }

    @Synchronized
    private fun onStateChanged(token: MediaSession.Token, newState: Int) {
        val tracked = sessions[token] ?: return
        tracked.lastState = newState

        when {
            tracked.pendingPause -> when {
                // Observed the pause we requested: we now genuinely own this session.
                newState == PlaybackState.STATE_PAUSED -> {
                    tracked.pendingPause = false
                    tracked.ownedByUs = true
                }
                // It kept/resumed playing, or ended, without pausing for us: we do not own it.
                newState == PlaybackState.STATE_PLAYING || isTerminal(newState) -> {
                    tracked.pendingPause = false
                    tracked.ownedByUs = false
                }
                // Transient states (buffering, connecting): keep waiting.
            }

            tracked.ownedByUs -> {
                // The user (or the app) moved it while we owned it. Either way it is no longer ours
                // to resume: a manual play means they want it playing, a terminal state means the
                // thing we paused is gone.
                if (newState == PlaybackState.STATE_PLAYING || isTerminal(newState)) {
                    tracked.ownedByUs = false
                }
            }
        }
    }

    @Synchronized
    private fun onDestroyed(token: MediaSession.Token) {
        val tracked = sessions.remove(token) ?: return
        try {
            tracked.controller.unregisterCallback(tracked.callback)
        } catch (_: Exception) {
            // Best effort.
        }
    }

    /**
     * Request a pause on everything currently playing. Ownership is NOT recorded here; it is recorded
     * later, only if [onStateChanged] observes the pause. Returns the packages we sent a pause to.
     */
    @Synchronized
    fun pauseAllPlaying(controllers: List<MediaController>): List<String> {
        sync(controllers)
        val requested = mutableListOf<String>()
        for (controller in controllers) {
            val tracked = sessions[controller.sessionToken] ?: continue
            if (stateOf(controller) == PlaybackState.STATE_PLAYING) {
                tracked.pendingPause = true
                tracked.ownedByUs = false
                controller.transportControls.pause()
                requested.add(controller.packageName)
            }
        }
        return requested
    }

    /**
     * Resume exactly the sessions we paused and still own, and only if they are still paused. Drops
     * ownership as it goes so a second resume is a no-op.
     */
    @Synchronized
    fun resumePausedByUs(controllers: List<MediaController>): List<String> {
        sync(controllers)
        val resumed = mutableListOf<String>()
        for (tracked in sessions.values.toList()) {
            if (!tracked.ownedByUs) continue
            if (stateOf(tracked.controller) == PlaybackState.STATE_PAUSED) {
                tracked.controller.transportControls.play()
                resumed.add(tracked.controller.packageName)
            }
            tracked.ownedByUs = false
            tracked.pendingPause = false
        }
        return resumed
    }

    /** Whether a given session token is currently owned (paused and observed) by Stride. */
    @Synchronized
    fun isOwned(token: MediaSession.Token): Boolean = sessions[token]?.ownedByUs == true
}
