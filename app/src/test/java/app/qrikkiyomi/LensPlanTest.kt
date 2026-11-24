package app.qrikkiyomi

import app.qrikkiyomi.core.LensPlan
import app.qrikkiyomi.core.PhysicalLens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.4.6: the preset → physical-lens plan. A preset switches the
 * lens the way the stock camera does (closest lens at or below the
 * preset, remainder covered by that lens's zoom ratio); devices
 * whose lenses cannot be enumerated — or do not span a real range —
 * get no plan at all and keep the logical-camera behaviour.
 */
class LensPlanTest {

    // A Pixel-class trio: 13mm ultrawide, 24mm wide, 120mm tele.
    private val uw = PhysicalLens("uw", 13f, hasFlash = false)
    private val wide = PhysicalLens("wide", 24f, hasFlash = true)
    private val tele = PhysicalLens("tele", 120f, hasFlash = false)
    private val trio = listOf(uw, wide, tele)

    @Test
    fun equivFocalFromFocalAndSensorSize() {
        // 6.0mm real focal on a 9.6×7.2mm sensor (12mm diagonal).
        assertEquals(21.6f, LensPlan.equivFocalMm(6f, 9.6f, 7.2f), 0.1f)
        // A full-frame sensor keeps the focal length as-is.
        assertEquals(24f, LensPlan.equivFocalMm(24f, 36f, 24f), 0.1f)
        // Unusable inputs read as 0, never as a lens.
        assertEquals(0f, LensPlan.equivFocalMm(6f, 0f, 0f), 0.0001f)
        assertEquals(0f, LensPlan.equivFocalMm(0f, 9.6f, 7.2f), 0.0001f)
    }

    @Test
    fun baseIsTheLensClosestTo26mm() {
        assertEquals(wide, LensPlan.base(trio))
        // 24mm (distance 2) beats 13mm (13) and 48mm (22).
        val other = listOf(
            PhysicalLens("a", 13f, false),
            PhysicalLens("b", 24f, true),
            PhysicalLens("c", 48f, false),
        )
        assertEquals("b", LensPlan.base(other)?.cameraId)
        // A tie (20mm and 32mm are both 6 away) goes to the wider lens.
        val tie = listOf(
            PhysicalLens("t", 32f, false),
            PhysicalLens("w", 20f, false),
        )
        assertEquals("w", LensPlan.base(tie)?.cameraId)
        assertNull(LensPlan.base(emptyList()))
    }

    @Test
    fun ratiosAreAgainstTheBaseLens() {
        val b = LensPlan.base(trio)!!
        assertEquals(1f, LensPlan.ratio(wide, b), 0.0001f)
        assertEquals(13f / 24f, LensPlan.ratio(uw, b), 0.0001f)
        assertEquals(5f, LensPlan.ratio(tele, b), 0.0001f)
        assertEquals(13f / 24f, LensPlan.minRatio(trio), 0.0001f)
        assertEquals(5f, LensPlan.maxRatio(trio), 0.0001f)
    }

    @Test
    fun usableNeedsTwoLensesSpanningARealRange() {
        assertTrue(LensPlan.isUsable(trio))
        assertFalse(LensPlan.isUsable(listOf(wide)))
        assertFalse(LensPlan.isUsable(emptyList()))
        // Two "lenses" at nearly the same focal length are one lens
        // read twice — no plan.
        assertFalse(
            LensPlan.isUsable(
                listOf(PhysicalLens("a", 24f, true), PhysicalLens("b", 26f, true))
            )
        )
        // A two-lens phone (ultrawide + wide) spans enough.
        assertTrue(LensPlan.isUsable(listOf(uw, wide)))
    }

    @Test
    fun presetAtALensPointBindsThatLensAtResidualOne() {
        val half = LensPlan.choose(0.5f, trio)!!
        assertEquals("uw", half.lens.cameraId)
        assertEquals(1f, half.residualRatio, 0.1f) // 0.5 / 0.542 ≈ 0.92 → lands at 0.54
        val one = LensPlan.choose(1f, trio)!!
        assertEquals("wide", one.lens.cameraId)
        assertEquals(1f, one.residualRatio, 0.0001f)
        val five = LensPlan.choose(5f, trio)!!
        assertEquals("tele", five.lens.cameraId)
        assertEquals(1f, five.residualRatio, 0.0001f)
    }

    @Test
    fun presetBetweenLensesRidesTheClosestLensBelow() {
        // No dedicated 2× or 3× lens: the base lens covers them with
        // its own zoom ratio, like the stock camera does.
        val two = LensPlan.choose(2f, trio)!!
        assertEquals("wide", two.lens.cameraId)
        assertEquals(2f, two.residualRatio, 0.0001f)
        val three = LensPlan.choose(3f, trio)!!
        assertEquals("wide", three.lens.cameraId)
        assertEquals(3f, three.residualRatio, 0.0001f)
        // Just under the tele point still rides the base lens.
        val almost = LensPlan.choose(4.5f, trio)!!
        assertEquals("wide", almost.lens.cameraId)
        assertEquals(4.5f, almost.residualRatio, 0.0001f)
    }

    @Test
    fun presetBelowEveryLensLandsOnTheWidest() {
        // 0.5× on a phone whose ultrawide is a 0.54× lens: the preset
        // lands on the ultrawide itself (a physical lens cannot zoom
        // below its own 1.0), i.e. an effective 0.54×.
        assertEquals(13f / 24f, LensPlan.achievableRatio(0.5f, trio)!!, 0.0001f)
        assertEquals(1f, LensPlan.achievableRatio(1f, trio)!!, 0.0001f)
        assertEquals(5f, LensPlan.achievableRatio(5f, trio)!!, 0.0001f)
    }

    @Test
    fun noPlanForUnusableLensSets() {
        assertNull(LensPlan.choose(2f, listOf(wide)))
        assertNull(LensPlan.choose(2f, emptyList()))
        assertNull(LensPlan.achievableRatio(2f, listOf(wide)))
    }

    @Test
    fun visiblePresetsOnlyOfferWhatTheLensesCanReach() {
        assertEquals(
            listOf(0.5f, 1f, 2f, 3f, 5f),
            LensPlan.visiblePresets(trio),
        )
        // No ultrawide: 0.5× could only ever land on 1×, so the chip
        // is not offered; 5× rides the 3× lens at a residual zoom.
        val noUw = listOf(
            PhysicalLens("wide", 24f, true),
            PhysicalLens("tele", 72f, false),
        )
        assertEquals(listOf(1f, 2f, 3f, 5f), LensPlan.visiblePresets(noUw))
        // Ultrawide + wide only: tele presets ride the wide lens.
        assertEquals(
            listOf(0.5f, 1f, 2f, 3f, 5f),
            LensPlan.visiblePresets(listOf(uw, wide)),
        )
        // Nothing usable → no chips from the plan at all.
        assertEquals(emptyList<Float>(), LensPlan.visiblePresets(listOf(wide)))
    }
}
