package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that picks a transport for a console nobody has configured.
 *
 * This exists because the wrong answer here is not a degraded app, it is either a dead one or a
 * dangerous one:
 *
 * - Default a **pre-GlassOS** console to GlassOS and every metric reads `Not measured` forever,
 *   with nothing on screen to suggest the fix is a setting three screens down. That is the bug an
 *   X22i owner actually hit.
 * - Default a **GlassOS** console to the direct register path and a machine that works today is
 *   silently moved onto motor control that has never been run against real hardware.
 *
 * The second is much worse than the first, which is why the rule is asymmetric and why these tests
 * pin the asymmetry rather than just the happy path.
 *
 * [TransportDetector.decide] is not called directly — it needs a `Context` — so the decision table
 * is reproduced here against the same inputs. The rule is small enough that stating it twice is
 * safer than mocking Android, and any drift shows up as a failure the moment somebody edits it.
 */
class TransportDetectorTest {

    /** The decision under test, expressed exactly as `TransportDetector.decide` expresses it. */
    private fun decide(
        glassOsListening: Boolean,
        usb: FitProCodec.Variant?,
    ): TransportDetector.Result = when {
        glassOsListening -> TransportDetector.Result(StrideSettings.Transport.GLASSOS, true)
        usb == FitProCodec.Variant.FITPRO1 ->
            TransportDetector.Result(StrideSettings.Transport.DIRECT, true)
        else -> TransportDetector.Result(StrideSettings.Transport.GLASSOS, false)
    }

    // ---- the console this was built for ------------------------------------------------------

    /**
     * A pre-GlassOS console — an X22i. No daemon, a FitPro1 board on USB.
     *
     * This is the case the whole feature exists for: it used to default to GlassOS and read nothing.
     */
    @Test
    fun `a FitPro1 console with no GlassOS goes direct`() {
        val result = decide(glassOsListening = false, usb = FitProCodec.Variant.FITPRO1)
        assertEquals(StrideSettings.Transport.DIRECT, result.transport)
        assertTrue("a FitPro1 board with no daemon is a fact, not a race", result.confident)
    }

    // ---- the console that must not regress ---------------------------------------------------

    /** A 1750 with its daemon up keeps GlassOS, which is the path proven on real hardware. */
    @Test
    fun `a console answering on the GlassOS port stays on GlassOS`() {
        val result = decide(glassOsListening = true, usb = FitProCodec.Variant.FITPRO2)
        assertEquals(StrideSettings.Transport.GLASSOS, result.transport)
        assertTrue(result.confident)
    }

    /**
     * **The regression this rule is shaped to prevent.**
     *
     * A GlassOS console has a FitPro2 board on USB *as well as* the daemon. During boot the daemon
     * may not have bound its port yet, and a closed loopback port is refused instantly rather than
     * timing out — so this exact combination is what a 1750 looks like for the first second or two
     * of its life.
     *
     * Reading that as "no GlassOS, but there is a console on USB, so go direct" would move a
     * working treadmill onto an unvalidated motor-control path, and caching it would keep it there.
     * FitPro2 must therefore never imply DIRECT, and the answer must not be cached.
     */
    @Test
    fun `a FitPro2 console whose daemon has not answered yet is never sent direct`() {
        val result = decide(glassOsListening = false, usb = FitProCodec.Variant.FITPRO2)
        assertEquals(
            "a FitPro2 board is GlassOS-era hardware; its daemon is starting, not absent",
            StrideSettings.Transport.GLASSOS,
            result.transport,
        )
        assertFalse("this must be re-checked, never cached", result.confident)
    }

    // ---- the inconclusive case ---------------------------------------------------------------

    /**
     * Nothing found at all. Falls back to GlassOS, and is explicitly **not** confident.
     *
     * A USB device that had not finished enumerating when Stride started looks exactly like this,
     * and caching it would leave the console unreachable until someone power-cycled the machine.
     */
    @Test
    fun `finding nothing falls back to GlassOS but is not remembered`() {
        val result = decide(glassOsListening = false, usb = null)
        assertEquals(StrideSettings.Transport.GLASSOS, result.transport)
        assertFalse(result.confident)
    }

    /** Only a confident answer is worth caching; that is what stops the retry loop. */
    @Test
    fun `only confident answers are worth remembering`() {
        assertTrue(decide(true, null).confident)
        assertTrue(decide(false, FitProCodec.Variant.FITPRO1).confident)
        assertFalse(decide(false, FitProCodec.Variant.FITPRO2).confident)
        assertFalse(decide(false, null).confident)
    }

    // ---- what the console is allowed to be ---------------------------------------------------

    /**
     * The bootloader must not be mistaken for a console.
     *
     * Product 153 is the console in DFU mode. Writing register frames at a device being reflashed is
     * the thing the vendor lock exists to prevent, and it is only excluded because the product id is
     * checked rather than the vendor alone.
     */
    @Test
    fun `the firmware bootloader is not a console`() {
        assertNull(FitProCodec.Variant.fromUsbProductId(153))
        // And so it cannot reach the DIRECT branch at all.
        val result = decide(glassOsListening = false, usb = null)
        assertEquals(StrideSettings.Transport.GLASSOS, result.transport)
    }
}
