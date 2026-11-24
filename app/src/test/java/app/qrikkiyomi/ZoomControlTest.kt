package app.qrikkiyomi

import app.qrikkiyomi.core.ZoomControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.4.5: the clamp / pinch / preset rules shared by the scan screen's
 * three zoom controls (preset chips, pinch, slider) — FR: zoom follows
 * the bound camera's real range, not a hard-coded one.
 */
class ZoomControlTest {

    @Test
    fun clampKeepsValuesInsideTheRange() {
        assertEquals(2f, ZoomControl.clamp(2f, 1f, 6f), 0.0001f)
        assertEquals(1f, ZoomControl.clamp(0.2f, 1f, 6f), 0.0001f)
        assertEquals(6f, ZoomControl.clamp(30f, 1f, 6f), 0.0001f)
    }

    @Test
    fun clampHonoursAWideCameraRange() {
        // A camera that can go below 1x (ultrawide) and far past 6x.
        assertEquals(0.5f, ZoomControl.clamp(0.5f, 0.5f, 10f), 0.0001f)
        assertEquals(0.5f, ZoomControl.clamp(0.1f, 0.5f, 10f), 0.0001f)
        assertEquals(10f, ZoomControl.clamp(99f, 0.5f, 10f), 0.0001f)
    }

    @Test
    fun clampFallsBackWhenTheRangeIsBroken() {
        assertEquals(1f, ZoomControl.clamp(0.5f, 6f, 1f), 0.0001f)
        assertEquals(6f, ZoomControl.clamp(9f, 6f, 1f), 0.0001f)
    }

    @Test
    fun pinchMultipliesAndClampsAtBothEnds() {
        assertEquals(2f, ZoomControl.scaled(1f, 2f, 1f, 6f), 0.0001f)
        assertEquals(6f, ZoomControl.scaled(4f, 2f, 1f, 6f), 0.0001f)
        assertEquals(1f, ZoomControl.scaled(1.5f, 0.5f, 1f, 6f), 0.0001f)
        assertEquals(0.5f, ZoomControl.scaled(1f, 0.4f, 0.5f, 10f), 0.0001f)
    }

    @Test
    fun pinchStepsCompose() {
        // Two half-zoom-out steps from 4x land on 1x, not below.
        val once = ZoomControl.scaled(4f, 0.5f, 1f, 6f)
        assertEquals(1f, ZoomControl.scaled(once, 0.5f, 1f, 6f), 0.0001f)
    }

    @Test
    fun presetsForTheDefaultRange() {
        assertEquals(
            listOf(1f, 2f, 5f),
            ZoomControl.visiblePresets(ZoomControl.DEFAULT_MIN, ZoomControl.DEFAULT_MAX),
        )
    }

    @Test
    fun presetsIncludeHalfWhenTheCameraReachesIt() {
        assertEquals(
            listOf(0.5f, 1f, 2f, 5f),
            ZoomControl.visiblePresets(0.5f, 10f),
        )
    }

    @Test
    fun presetsShrinkToANarrowRange() {
        assertEquals(listOf(2f), ZoomControl.visiblePresets(2f, 4f))
        assertEquals(listOf(1f), ZoomControl.visiblePresets(1f, 1.5f))
        // A camera whose minimum sits just above 0.5x does not get a
        // 0.5x chip it cannot honour.
        assertEquals(
            listOf(1f, 2f, 5f),
            ZoomControl.visiblePresets(0.6f, 8f),
        )
    }

    @Test
    fun presetsNeverOffer3x() {
        // v0.4.7: the preset set is the stock camera's {0.5, 1, 2, 5}.
        // v0.4.5–0.4.6 offered a 3× chip no stock camera shows; the
        // user asked why it was there. Pin the set so 3× cannot
        // return through either the candidates or any visible range.
        assertEquals(
            listOf(0.5f, 1f, 2f, 5f),
            ZoomControl.PRESET_CANDIDATES,
        )
        assertFalse(ZoomControl.visiblePresets(0.5f, 30f).contains(3f))
        assertFalse(ZoomControl.visiblePresets(2.5f, 3.5f).contains(3f))
    }

    @Test
    fun selectedMatchesThePresetWithinTolerance() {
        assertTrue(ZoomControl.isSelected(2f, 2f))
        assertTrue(ZoomControl.isSelected(2.05f, 2f))
        assertTrue(ZoomControl.isSelected(0.5f, 0.5f))
        assertFalse(ZoomControl.isSelected(2.3f, 2f))
        assertFalse(ZoomControl.isSelected(1f, 2f))
    }

    @Test
    fun labelsDropTheDecimalPointForWholeRatios() {
        assertEquals("0.5×", ZoomControl.label(0.5f))
        assertEquals("1×", ZoomControl.label(1f))
        assertEquals("2×", ZoomControl.label(2f))
        assertEquals("5×", ZoomControl.label(5f))
    }
}
