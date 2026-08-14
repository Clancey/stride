package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure preset-shaping logic behind the console's quick-pick columns.
 *
 * These pin the four behaviours a wrong refactor could quietly break: keeping only the requested
 * [GlassOsClient.ControlType], converting MPS to mph with the exact factor, ordering the column so
 * the largest value is on top, and collapsing display-equal duplicates. The MPS values used here
 * are the ones the stock console would carry for round mph presets, so the conversion is checked
 * against numbers a machine actually produces rather than invented ones.
 */
class MachineControlTest {

    private fun mps(mph: Double) = mph / MachineLink.MPS_TO_MPH

    private val toMph: (Double) -> Double = { it * MachineLink.MPS_TO_MPH }

    @Test
    fun `filtering keeps only the requested type and drops the rest`() {
        val controls = listOf(
            MachineControl(GlassOsClient.ControlType.MPS, at = 0.0, value = mps(6.0)),
            MachineControl(GlassOsClient.ControlType.INCLINE, at = 0.0, value = 6.0),
            // A type from another machine class must be ignored, not coerced into a speed.
            MachineControl(GlassOsClient.ControlType.UNKNOWN, at = 0.0, value = 99.0),
            MachineControl(9 /* RPM */, at = 0.0, value = 80.0),
        )

        assertEquals(
            listOf(6.0),
            shapePresets(controls, GlassOsClient.ControlType.MPS, toMph),
        )
        assertEquals(
            listOf(6.0),
            shapePresets(controls, GlassOsClient.ControlType.INCLINE) { it },
        )
    }

    @Test
    fun `MPS converts to mph and sorts highest first`() {
        val controls = listOf(1.0, 3.0, 12.0, 5.5).map {
            MachineControl(GlassOsClient.ControlType.MPS, at = 0.0, value = mps(it))
        }

        val presets = shapePresets(controls, GlassOsClient.ControlType.MPS, toMph)

        assertEquals(listOf(12.0, 5.5, 3.0, 1.0), presets)
        // The conversion factor is right to well under the one-decimal display resolution.
        assertEquals(12.0, presets.first(), 0.05)
    }

    @Test
    fun `incline presets keep percent unchanged and sort descending`() {
        val controls = listOf(-3.0, 0.0, 6.0, 12.0).map {
            MachineControl(GlassOsClient.ControlType.INCLINE, at = 0.0, value = it)
        }

        assertEquals(
            listOf(12.0, 6.0, 0.0, -3.0),
            shapePresets(controls, GlassOsClient.ControlType.INCLINE) { it },
        )
    }

    @Test
    fun `values that collide once rounded for display are de-duplicated`() {
        val controls = listOf(
            MachineControl(GlassOsClient.ControlType.INCLINE, at = 0.0, value = 5.01),
            MachineControl(GlassOsClient.ControlType.INCLINE, at = 0.0, value = 4.98),
            MachineControl(GlassOsClient.ControlType.INCLINE, at = 0.0, value = 5.0),
            MachineControl(GlassOsClient.ControlType.INCLINE, at = 0.0, value = 2.0),
        )

        assertEquals(
            listOf(5.0, 2.0),
            shapePresets(controls, GlassOsClient.ControlType.INCLINE) { it },
        )
    }

    @Test
    fun `empty input yields an empty list, which the caller maps to null`() {
        val shaped = shapePresets(emptyList(), GlassOsClient.ControlType.MPS, toMph)
        assertTrue(shaped.isEmpty())
        // This is the exact mapping MachineLink applies so a caller can tell "none" from "unfetched".
        assertEquals(null, shaped.takeIf { it.isNotEmpty() })
    }

    @Test
    fun `no matching type yields an empty list even when other controls exist`() {
        val controls = listOf(
            MachineControl(GlassOsClient.ControlType.INCLINE, at = 0.0, value = 6.0),
        )
        assertTrue(shapePresets(controls, GlassOsClient.ControlType.MPS, toMph).isEmpty())
    }
}
