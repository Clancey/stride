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

    @Test
    fun `published presets outside installation clamps use an honest fallback`() {
        val entries = railPresetEntries(
            published = listOf(90.0, 60.0, 50.0),
            ladder = inclineLadder,
            floor = MachineCoordinator.MIN_INCLINE,
            ceiling = MachineCoordinator.MAX_INCLINE,
        )
        assertEquals(inclineLadder.map(::formatRailPreset), entries)
    }

    @Test
    fun `published presets cannot collapse into duplicate clamp commands`() {
        val entries = railPresetEntries(
            published = listOf(90.0, 40.0, 20.0, 12.0, 12.0, 10.0, -6.0, -3.0),
            ladder = inclineLadder,
            floor = MachineCoordinator.MIN_INCLINE,
            ceiling = MachineCoordinator.MAX_INCLINE,
        )
        assertEquals(listOf("40", "20", "12", "10", "-6", "-3"), entries)
    }

    @Test
    fun `range validation uses the displayed command rather than the raw endpoint`() {
        val entries = railPresetEntries(
            published = listOf(2.57),
            ladder = listOf(2.57),
            floor = 2.55,
            ceiling = 2.57,
        )
        assertTrue("2.57 displays and parses as an out-of-range 2.6", entries.isEmpty())
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
     * A non-null range with no overlap is different from limits that have not arrived yet.
     *
     * Null limits use the installation range and keep the fallback usable. Once a machine explicitly
     * reports a disjoint range, there is no value the column can honestly offer.
     */
    @Test
    fun `an empty effective intersection offers no dishonest buttons`() {
        val entries = railPresetEntries(
            published = null,
            ladder = speedLadder,
            floor = 40.0,
            ceiling = 50.0,
        )
        assertTrue(entries.isEmpty())
    }

    /** Half steps survive; rounding them made two pills with one label and two meanings. */
    @Test
    fun `half steps keep their decimal`() {
        assertEquals("0.5", formatRailPreset(0.5))
        assertEquals("1", formatRailPreset(1.0))
        assertEquals("-3", formatRailPreset(-3.0))
        assertEquals("2.5", formatRailPreset(2.5))
    }

    /**
     * The bug this was written for: [MachinePresets.railRange]'s ceiling has to agree with what
     * [MachinePresets.ladder] rounds its own top rung to, or the filter silently drops the rung the
     * ladder just restored. Confirmed live on an X22i: a 19.31 kph reported maximum decodes to
     * 11.9987 mph; the ladder correctly puts a rung at 12.0, but a ceiling built from the raw
     * 11.9987 instead of [MachinePresets.railRange] would fail `12.0 <= 11.9987` and filter it out,
     * leaving 11 as the visible top button.
     */
    @Test
    fun `a reported max one quantization step short of a round number keeps that rung`() {
        val (floor, ceiling) = MachinePresets.railRange(
            reportedMin = 0.4970,
            reportedMax = 11.9987,
            installMin = MachineCoordinator.MIN_SPEED_MPH,
            installMax = MachineCoordinator.MAX_SPEED_MPH,
        )
        val entries = railPresetEntries(
            published = null,
            ladder = MachinePresets.speedLadder(0.4970, 11.9987),
            floor = floor,
            ceiling = ceiling,
        )
        assertEquals("12", entries.first())
    }

    /** The install clamp still wins when the machine claims more range than Stride will command. */
    @Test
    fun `railRange never widens past the installation clamp`() {
        val (floor, ceiling) = MachinePresets.railRange(
            reportedMin = -20.0,
            reportedMax = 60.0,
            installMin = MachineCoordinator.MIN_INCLINE,
            installMax = MachineCoordinator.MAX_INCLINE,
        )
        assertEquals(MachineCoordinator.MIN_INCLINE, floor, 0.0)
        assertEquals(MachineCoordinator.MAX_INCLINE, ceiling, 0.0)
    }

    /** A genuinely lower limit, not mere register noise, still narrows the column. */
    @Test
    fun `railRange still narrows for a real machine limit`() {
        val (floor, ceiling) = MachinePresets.railRange(
            reportedMin = 1.0,
            reportedMax = 8.3,
            installMin = MachineCoordinator.MIN_SPEED_MPH,
            installMax = MachineCoordinator.MAX_SPEED_MPH,
        )
        assertEquals(1.0, floor, 0.0)
        assertEquals(8.3, ceiling, 1e-9)
    }

    /** Missing limits fall back to the installation range, same as before this existed. */
    @Test
    fun `railRange with no reported limits uses the installation range`() {
        val (floor, ceiling) = MachinePresets.railRange(
            reportedMin = null,
            reportedMax = null,
            installMin = MachineCoordinator.MIN_SPEED_MPH,
            installMax = MachineCoordinator.MAX_SPEED_MPH,
        )
        assertEquals(MachineCoordinator.MIN_SPEED_MPH, floor, 0.0)
        assertEquals(MachineCoordinator.MAX_SPEED_MPH, ceiling, 0.0)
    }
}
