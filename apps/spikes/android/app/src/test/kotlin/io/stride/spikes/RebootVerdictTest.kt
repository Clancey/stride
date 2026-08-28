package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Whether a stop-escalation warning that survived a reboot should clear itself.
 *
 * The glue around this ([StopEscalation.restore], the boot-count comparison, the timeout) reads
 * `Context`/`Settings.Global`/`Handler`, which this module's plain JVM unit tests stub to defaults
 * rather than really running (`testOptions.unitTests.isReturnDefaultValues`, see build.gradle.kts) —
 * so that wiring is verified on hardware instead, the same way this project verifies anything else
 * a stub Android jar cannot. [rebootVerdict] is the actual decision, has no such dependency, and is
 * exactly the kind of thing this project always pulls out and pins with a table.
 */
class RebootVerdictTest {

    @Test
    fun `no fresh reading yet means keep waiting`() {
        assertNull(rebootVerdict(null))
    }

    @Test
    fun `every no-workout state clears the warning`() {
        for (state in NO_WORKOUT_STATES) {
            assertEquals("$state must confirm rest", true, rebootVerdict(state))
        }
    }

    @Test
    fun `a live workout state keeps the warning up`() {
        for (state in listOf("WORKOUT", "PAUSED", "WORKOUT_RESULTS", "WARM_UP", "COOL_DOWN", "RESUME")) {
            assertEquals("$state must not clear a warning nobody has explained", false, rebootVerdict(state))
        }
    }

    /** An unrecognised state is not one of the enumerated safe ones, so it must not clear anything. */
    @Test
    fun `an unrecognised state is treated as unresolved, not as rest`() {
        assertEquals(false, rebootVerdict("SOME_FUTURE_STATE_THIS_BUILD_DOES_NOT_KNOW"))
    }
}
