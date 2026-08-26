package io.stride.spikes.appstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which way [StrideAppstoreService] asks the platform to start it.
 *
 * The bug this pins is issue #26: a `RemoteServiceException` -
 * "Context.startForegroundService() did not then call Service.startForeground()" - that killed the
 * launcher once on an X22i, right after a cold launch, and never reproduced.
 *
 * It was never a missing call. `onCreate` calls `startForeground()` first thing. It is that
 * `startForegroundService()` starts a *ten second clock*, and the launcher was starting the clock
 * from inside `FlutterActivity.onCreate` - so the ten seconds went on Flutter engine attach, first
 * layout and `onResume`, all of it queued on the main thread ahead of the `onCreate` message that
 * would have stopped it. Nothing the service does can win a race it is not yet in.
 *
 * A race is not testable here (there is no Robolectric on this classpath, and a saturated main
 * thread is not something a JVM test can stage). What is testable is the decision that removes the
 * race: *do not start the clock unless the platform gives us no other way in*. That decision has
 * exactly three inputs, and two of them are refusals that look nothing alike.
 */
class ForegroundStartTest {

    private class Recorder {
        var plainCalls = 0
        var promisedCalls = 0
    }

    /** The launch path, and the one that crashed: the app is on screen, so no clock is started. */
    @Test
    fun `an accepted plain start is the end of it`() {
        val r = Recorder()
        val route = ForegroundStart.run(
            plain = { r.plainCalls++; STARTED },
            promised = { r.promisedCalls++ },
        )

        assertEquals(ForegroundStart.Route.PLAIN, route)
        assertEquals(1, r.plainCalls)
        assertEquals(
            "a plain start that worked must never be followed by a timed one",
            0,
            r.promisedCalls,
        )
    }

    /**
     * The refusal an app targeting O+ actually gets: `IllegalStateException("Not allowed to start
     * service ...: app is in background uid ...")`. This is `AppstoreWorker`'s periodic check
     * running while Stride is nothing but a process, and it is the one case where the promise is
     * the only way in - so it must still be made.
     */
    @Test
    fun `a background refusal falls back to the timed promise`() {
        val r = Recorder()
        val route = ForegroundStart.run(
            plain = {
                r.plainCalls++
                throw IllegalStateException(
                    "Not allowed to start service Intent { ... }: app is in background uid null",
                )
            },
            promised = { r.promisedCalls++ },
        )

        assertEquals(ForegroundStart.Route.PROMISED, route)
        assertEquals(1, r.plainCalls)
        assertEquals(
            "the periodic check must still run when the plain start is refused",
            1,
            r.promisedCalls,
        )
    }

    /**
     * The refusal that is easy to miss, and the reason this is not a bare try/catch: under forced
     * app-standby the activity manager does not throw at all. It drops the start and hands back a
     * null `ComponentName`. Treating that as success would leave the update check silently never
     * running on exactly the consoles the standby bucket applies to.
     */
    @Test
    fun `a silently dropped plain start is a refusal, not a success`() {
        val r = Recorder()
        val route = ForegroundStart.run(
            plain = { r.plainCalls++; null },
            promised = { r.promisedCalls++ },
        )

        assertEquals(ForegroundStart.Route.PROMISED, route)
        assertEquals(1, r.promisedCalls)
    }

    /**
     * Only `IllegalStateException` means "in the background". Anything else is a real fault - an
     * unresolvable component, a manifest mistake - and retrying it as a foreground start would
     * raise the identical exception, having first told the platform to expect a `startForeground()`
     * that is now certain never to arrive. That is the crash, manufactured on purpose.
     */
    @Test
    fun `a fault that is not a background refusal is not converted into a promise`() {
        val r = Recorder()
        var thrown: Throwable? = null
        try {
            ForegroundStart.run(
                plain = { throw SecurityException("not exported") },
                promised = { r.promisedCalls++ },
            )
        } catch (e: Throwable) {
            thrown = e
        }

        assertTrue("the fault must surface", thrown is SecurityException)
        assertEquals(
            "no promise may be made on behalf of a start that never happened",
            0,
            r.promisedCalls,
        )
    }

    /** Callers read the route to log which way in they took; it must describe what really ran. */
    @Test
    fun `the route reports the start that actually reached the platform`() {
        assertFalse(
            ForegroundStart.run(plain = { STARTED }, promised = { }) ==
                ForegroundStart.run(plain = { null }, promised = { }),
        )
    }

    /**
     * Stands in for the `ComponentName` the platform returns. [ForegroundStart] only asks whether
     * something came back, which is what lets this be tested at all - every `android.jar` type is
     * an unusable stub on the unit-test classpath.
     */
    private companion object {
        val STARTED = Any()
    }
}
