package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The provider query itself needs a device, but the value handling is where the mistakes are: a
 * hex id pasted into Google's page is rejected with no useful explanation, and a zero id is
 * accepted and does nothing at all. Both are cheap to pin down here.
 */
class PlayCertificationTest {

    @Test
    fun `a normal id passes through as decimal`() {
        assertEquals("3546817354917981234", PlayCertification.decimal("3546817354917981234"))
    }

    @Test
    fun `surrounding whitespace does not lose the id`() {
        assertEquals("1234567890", PlayCertification.decimal("  1234567890\n"))
    }

    @Test
    fun `an id with the high bit set reads as unsigned, not negative`() {
        // GSF stores a 64-bit value in a signed column, so half the id space comes back negative.
        // Handing "-1234..." to the registration page is a guaranteed rejection.
        assertEquals(
            "18446744073709551615",
            PlayCertification.decimal("-1"),
        )
    }

    @Test
    fun `zero is treated as absent`() {
        // GSF writes 0 before it has checked in with Google. Registering it looks like success and
        // achieves nothing, which is worse for a tester than being told to wait.
        assertNull(PlayCertification.decimal("0"))
    }

    @Test
    fun `missing or non-numeric values are absent rather than fatal`() {
        assertNull(PlayCertification.decimal(null))
        assertNull(PlayCertification.decimal(""))
        assertNull(PlayCertification.decimal("   "))
        assertNull(PlayCertification.decimal("not-an-id"))
    }

    @Test
    fun `hex is the same value, zero padded to sixteen digits`() {
        assertEquals("00000000499602d2", PlayCertification.hex("1234567890"))
        assertEquals("ffffffffffffffff", PlayCertification.hex("18446744073709551615"))
    }

    @Test
    fun `hex of an absent id is absent`() {
        assertNull(PlayCertification.hex(null))
    }

    @Test
    fun `decimal and hex describe the same number`() {
        val decimal = PlayCertification.decimal("-8603657889541918977")
        assertEquals("8899aabbccddeeff", PlayCertification.hex(decimal))
    }
}
