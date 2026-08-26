package io.stride.spikes.appstore

/**
 * How [StrideAppstoreService] gets started, and why it usually does *not* use
 * `Context.startForegroundService()`.
 *
 * WHY THIS EXISTS AT ALL: `startForegroundService()` is not a way to start a service. It is a
 * *promise*, with a ten second deadline, that `Service.startForeground()` will run. Break it and
 * the platform does not merely refuse - on API 28 `ActiveServices.serviceForegroundTimeout` tears
 * the record down with `fgRequired` still set, and `bringDownServiceLocked` then throws
 * `RemoteServiceException: Context.startForegroundService() did not then call
 * Service.startForeground()` on the app's *main* thread. On this console that is the launcher
 * dying, taking the only Back and Home the rider has with it.
 *
 * Nothing inside the service is slow: `startForeground()` is the first thing `onCreate` does. But
 * `onCreate` is a *message on the main thread*, and the caller that mattered
 * ([StrideAppstoreService.checkOnStart], from `MainActivity`) made the promise from inside
 * `FlutterActivity.onCreate`. The ten seconds are then spent on Flutter engine attach, first
 * layout, `onResume`, and everything else already queued - all of it ahead of our turn. That is
 * issue #26: a crash on a cold launch where the service kept its half of the bargain and simply
 * never got a turn to make the call. It happened once, on an X22i, and nobody could reproduce it,
 * which is what a saturated main thread looks like from the outside.
 *
 * `Context.startService()` carries no deadline whatsoever. A service started that way calls
 * `startForeground()` whenever `onCreate` finally runs, and lands in exactly the same state - a
 * real foreground service with the same lifetime. Its only limitation is that API 26+ refuses it
 * while the app is in the background, and that is precisely the one case where making the promise
 * is worth it, and the one case that is never on the launch path.
 *
 * So the rule is: ask for the deadline-free start, and hold a stopwatch over ourselves only when
 * the platform leaves us no other way in.
 */
internal object ForegroundStart {

    /** Which of the two starts actually reached the platform. */
    enum class Route {
        /** `startService` - accepted, and nothing is now waiting on us. */
        PLAIN,

        /** `startForegroundService` - accepted, and `startForeground()` is now owed within 10s. */
        PROMISED,
    }

    /**
     * Start without a deadline if the platform will allow it; fall back to the timed promise only
     * when it will not.
     *
     * [plain] is `Context.startService`, whose *two* refusal shapes are both handled here and are
     * easy to get wrong. For an app targeting O+ it throws
     * `IllegalStateException("Not allowed to start service ...: app is in background uid ...")`;
     * under forced app-standby the activity manager instead drops the start silently and hands back
     * a null `ComponentName`. Neither means "retry"; both mean "only a foreground start will do".
     *
     * [plain] is typed as `() -> Any?` rather than `() -> ComponentName?` so that this decision -
     * which is the whole of the fix - can be tested on the JVM, where every `android.jar` type is
     * an unusable stub. All this needs to know is whether the platform handed a component back, not
     * which one. (`org.json` is on the test classpath for the same underlying reason; see
     * `app/build.gradle.kts`.)
     *
     * Deliberately does not catch `SecurityException`: the only start we ever make is an explicit
     * intent at our own non-exported component, so a security failure is a manifest bug that should
     * surface, and retrying it as a foreground start would raise the identical exception anyway.
     *
     * Catching `IllegalStateException` also happens to be the right net if `targetSdk` is ever
     * raised past 30 (`-PstrideTargetSdk=35` exists for install-compatibility experiments):
     * `ForegroundServiceStartNotAllowedException` extends it, so an Android 12+ refusal of the
     * plain start degrades into the foreground start rather than escaping as a crash.
     */
    fun run(plain: () -> Any?, promised: () -> Unit): Route {
        val accepted = try {
            plain() != null
        } catch (e: IllegalStateException) {
            false
        }
        if (accepted) return Route.PLAIN
        promised()
        return Route.PROMISED
    }
}
