package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What goes in the incline and speed quick-pick columns.
 *
 * This exists because both columns went blank on real hardware while every other part of the overlay
 * was working. The corner toggle lit up, `dumpsys window` showed both rail windows created at their
 * full 132×722, and there was nothing drawn inside either one — a control that opens onto nothing.
 *
 * The cause was an empty list being believed. GlassOS answers `GetControls` out of the current
 * workout, so an idle console returns a successful, well-formed `ControlList` with no controls in
 * it. That is a true statement about right now and a false one about the machine, and the overlay
 * rendered it literally.
 */
class RailPresetsTest {

    private val inclineLadder =
        listOf(12.0, 10.0, 8.0, 6.0, 5.0, 4.0, 3.0, 2.0, 1.0, 0.0, -1.0, -2.0, -3.0)

    private val speedLadder = listOf(12.0, 10.0, 9.0, 8.0, 7.0, 6.0, 5.0, 4.0, 3.0, 2.0, 1.0)

    /** The regression, stated directly: an empty answer must never produce an empty column. */
    @Test
    fun `a machine that published no presets still gets a usable column`() {
        val entries = railPresetEntries(
            published = emptyList(),
            ladder = speedLadder,
            floor = null,
            ceiling = null,
        )
        assertEquals(listOf("12", "10", "9", "8", "7", "6", "5", "4", "3", "2", "1"), entries)
    }

    /** Not asked yet behaves the same way, and always did. */
    @Test
    fun `an unasked machine gets the ladder`() {
        val entries = railPresetEntries(
            published = null,
            ladder = inclineLadder,
            floor = null,
            ceiling = null,
        )
        assertEquals(inclineLadder.size, entries.size)
        assertEquals("12", entries.first())
        assertEquals("-3", entries.last())
    }

    /** What the machine actually publishes still wins, in the order it published it. */
    @Test
    fun `published presets are used as given`() {
        val entries = railPresetEntries(
            published = listOf(10.0, 7.5, 5.0),
            ladder = speedLadder,
            floor = null,
            ceiling = null,
        )
        assertEquals(listOf("10", "7.5", "5"), entries)
    }

    /** A real range still narrows the ladder — that behaviour is the point of having limits. */
    @Test
    fun `limits narrow the ladder`() {
        val entries = railPresetEntries(
            published = null,
            ladder = inclineLadder,
            floor = 0.0,
            ceiling = 6.0,
        )
        assertEquals(listOf("6", "5", "4", "3", "2", "1", "0"), entries)
    }

    /**
     * A nonsensical range must not be able to empty the column.
     *
     * A zeroed limits struct, or one read before a probe finished, would otherwise filter every
     * speed away and reproduce the original bug from the other direction — this time on a machine
     * that had published nothing wrong at all.
     */
    @Test
    fun `limits that exclude everything fall back to the whole ladder`() {
        val entries = railPresetEntries(
            published = null,
            ladder = speedLadder,
            floor = 40.0,
            ceiling = 50.0,
        )
        assertEquals(speedLadder.size, entries.size)
        assertTrue(entries.contains("1"))
    }

    /** Half steps survive; rounding them made two pills with one label and two meanings. */
    @Test
    fun `half steps keep their decimal`() {
        assertEquals("0.5", formatRailPreset(0.5))
        assertEquals("1", formatRailPreset(1.0))
        assertEquals("-3", formatRailPreset(-3.0))
        assertEquals("2.5", formatRailPreset(2.5))
    }
}
