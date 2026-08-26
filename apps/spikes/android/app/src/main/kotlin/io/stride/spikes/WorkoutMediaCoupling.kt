package io.stride.spikes

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log

object WorkoutMediaCoupling {
    private const val TAG = "WorkoutMediaCoupling"

    private val pausedForWorkoutResume = HashSet<MediaSession.Token>()

    @Volatile
    private var attached = false

    private var lastState = WorkoutSession.state
    private var listener: WorkoutSession.Listener? = null
    private var loggedListenerUnavailable = false

    @Synchronized
    fun attach(context: Context) {
        if (attached) return
        val appContext = context.applicationContext
        lastState = WorkoutSession.state
        val stateListener = WorkoutSession.Listener { next, _ ->
            onWorkoutTransition(appContext, next)
        }
        listener = stateListener
        WorkoutSession.addListener(stateListener)
        attached = true
    }

    fun activeControllers(context: Context): List<MediaController> {
        if (!StrideNotificationListener.isConnected) {
            logListenerUnavailable("Notification listener is not connected; skipping media session control.")
            return emptyList()
        }
        loggedListenerUnavailable = false
        return try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(context, StrideNotificationListener::class.java)
            msm.getActiveSessions(component)
        } catch (se: SecurityException) {
            logListenerUnavailable(
                "Notification listener permission is not available; skipping media session control.",
                se,
            )
            emptyList()
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to read active media sessions; skipping media session control.", t)
            emptyList()
        }
    }

    private fun onWorkoutTransition(context: Context, next: WorkoutSession.State) {
        val previous = synchronized(this) {
            val state = lastState
            lastState = next
            state
        }

        try {
            when {
                // An end now arrives as STOPPING — the rider pressed End, and the stop is on the
                // wire — so the media stops then rather than a confirmation later. Waiting for IDLE
                // would leave a film playing over a treadmill the rider has finished with for as
                // long as it takes to answer for the belt. The IDLE arm still covers an abandon,
                // which goes straight there; `previous != STOPPING` keeps the settle that follows
                // an end from asking twice.
                next == WorkoutSession.State.STOPPING ||
                    (next == WorkoutSession.State.IDLE &&
                        previous != WorkoutSession.State.IDLE &&
                        previous != WorkoutSession.State.STOPPING) ->
                    pausePlayingForStoppedWorkout(context)

                previous == WorkoutSession.State.RUNNING && next == WorkoutSession.State.PAUSED ->
                    pausePlayingForWorkoutPause(context)

                previous == WorkoutSession.State.PAUSED && next == WorkoutSession.State.RUNNING ->
                    resumePausedForWorkout(context)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Workout media coupling skipped after a workout state change.", t)
        }
    }

    private fun pausePlayingForWorkoutPause(context: Context) {
        val controllers = activeControllers(context)
        val playingTokens = controllers
            .filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            .map { it.sessionToken }

        synchronized(this) {
            pausedForWorkoutResume.clear()
            pausedForWorkoutResume.addAll(playingTokens)
        }

        val pausedPackages = MediaOwnershipTracker.pauseAllPlaying(controllers)
        if (pausedPackages.isNotEmpty()) {
            Log.i(TAG, "Paused media for workout pause: ${pausedPackages.joinToString()}")
        }
    }

    private fun resumePausedForWorkout(context: Context) {
        val tokensToResume = synchronized(this) {
            pausedForWorkoutResume.toSet().also { pausedForWorkoutResume.clear() }
        }
        if (tokensToResume.isEmpty()) return

        val controllers = activeControllers(context)
        MediaOwnershipTracker.sync(controllers)
        val resumedPackages = mutableListOf<String>()
        for (controller in controllers) {
            if (controller.sessionToken !in tokensToResume) continue
            if (!MediaOwnershipTracker.isOwned(controller.sessionToken)) continue
            if (controller.playbackState?.state != PlaybackState.STATE_PAUSED) continue
            controller.transportControls.play()
            resumedPackages.add(controller.packageName)
        }
        if (resumedPackages.isNotEmpty()) {
            Log.i(TAG, "Resumed media after workout resume: ${resumedPackages.joinToString()}")
        }
    }

    private fun pausePlayingForStoppedWorkout(context: Context) {
        synchronized(this) {
            pausedForWorkoutResume.clear()
        }

        val pausedPackages = pausePlayingWithoutOwnership(activeControllers(context))
        if (pausedPackages.isNotEmpty()) {
            Log.i(TAG, "Paused media for workout stop: ${pausedPackages.joinToString()}")
        }
    }

    private fun pausePlayingWithoutOwnership(controllers: List<MediaController>): List<String> {
        val pausedPackages = mutableListOf<String>()
        for (controller in controllers) {
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                controller.transportControls.pause()
                pausedPackages.add(controller.packageName)
            }
        }
        return pausedPackages
    }

    @Synchronized
    private fun logListenerUnavailable(message: String, throwable: Throwable? = null) {
        if (loggedListenerUnavailable) return
        if (throwable == null) Log.i(TAG, message) else Log.w(TAG, message, throwable)
        loggedListenerUnavailable = true
    }
}
