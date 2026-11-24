package app.qrikkiyomi.core

import kotlin.math.abs
import kotlin.math.hypot

/**
 * One physical lens behind the device's back logical multi-camera
 * (v0.4.6). [equivFocalMm] is the 35mm-equivalent focal length, the
 * one number that makes lenses from different sensors comparable;
 * [hasFlash] says whether this lens has its own flash unit, which is
 * what the torch actually lights from.
 */
data class PhysicalLens(
    val cameraId: String,
    val equivFocalMm: Float,
    val hasFlash: Boolean,
)

/**
 * v0.4.6: the lens maths behind the zoom preset chips. The user's
 * direction: a preset (0.5×／1×／2×／5×) must switch the way the
 * device's own camera does — by changing the physical lens — while
 * pinch and the slider keep doing plain zoom-ratio scaling inside
 * whichever lens is bound.
 *
 * The plan is a pure function of the enumerated lenses: pick the
 * base lens (the phone's main wide lens, = 1×), express every other
 * lens as a native ratio against it (ultrawide ≈ 0.5×, tele ≈ 5×),
 * and for a preset choose the closest lens at or below it, covering
 * the remainder with that lens's own zoom ratio (a preset with no
 * dedicated lens, e.g. 2× on a 0.5/1/5 phone, rides the base lens at
 * a residual 2.0 — exactly what stock cameras do between lens
 * points). No Android types here, so the rules are unit-testable;
 * enumeration and binding live in the scan screen.
 */
object LensPlan {

    /** The main wide lens of a phone sits near 24–28mm equivalent;
     * the base lens is the one closest to this anchor. */
    const val BASE_TARGET_MM = 26f

    /** A lens counts as "at" a preset when its native ratio is at
     * most this far above it — slack for the rounding in the
     * equivalent-focal computation (a 13mm ultrawide against a 24mm
     * base is 0.542×, and it IS the device's 0.5× lens). */
    private const val RATIO_EPS = 0.03f

    /** A preset reads as reachable when the ratio it can actually
     * land on is this close — the same tolerance the chips use to
     * mark the current preset (ZoomControl's selection tolerance). */
    private const val REACH_TOLERANCE = 0.1f

    /** Below this span between the widest and longest lens, a
     * "multi-lens" enumeration is not trusted as one (the focal
     * lengths were probably all read off the same sensor). */
    private const val MIN_SPAN = 1.5f

    /** 35mm-equivalent focal length from a lens's real focal length
     * and its sensor's physical size: focal × (full-frame diagonal
     * 43.27mm ÷ sensor diagonal). 0 when the size is unusable. */
    fun equivFocalMm(focalMm: Float, sensorWidthMm: Float, sensorHeightMm: Float): Float {
        val diag = hypot(sensorWidthMm, sensorHeightMm)
        if (diag <= 0f || focalMm <= 0f) return 0f
        return focalMm * 43.27f / diag
    }

    /** The base (1×) lens: closest equivalent focal to
     * [BASE_TARGET_MM]; ties go to the wider lens. */
    fun base(lenses: List<PhysicalLens>): PhysicalLens? =
        lenses.minWithOrNull(
            compareBy<PhysicalLens> { abs(it.equivFocalMm - BASE_TARGET_MM) }
                .thenBy { it.equivFocalMm }
        )

    /** A lens's native ratio against the base lens (base = 1×). */
    fun ratio(lens: PhysicalLens, base: PhysicalLens): Float =
        if (base.equivFocalMm > 0f) lens.equivFocalMm / base.equivFocalMm else 1f

    /** Whether this lens set can drive preset switching at all:
     * at least two lenses spanning a real range. Anything else —
     * single-lens phones, broken enumeration — keeps the v0.4.5
     * logical-camera behaviour. */
    fun isUsable(lenses: List<PhysicalLens>): Boolean {
        if (lenses.size < 2) return false
        val b = base(lenses) ?: return false
        val rs = lenses.map { ratio(it, b) }
        val min = rs.minOrNull() ?: return false
        val max = rs.maxOrNull() ?: return false
        return min > 0f && max / min >= MIN_SPAN
    }

    /** What a preset resolves to: the lens to bind, its native
     * ratio, and the zoom ratio still to apply on top of it. */
    data class Choice(
        val lens: PhysicalLens,
        val lensRatio: Float,
        val residualRatio: Float,
    )

    /**
     * The lens for [preset]: the one with the largest native ratio
     * at or below the preset (with [RATIO_EPS] slack), or the widest
     * lens when the preset sits below every lens. Null when the lens
     * set is not usable — the caller then just zooms the logical
     * camera, as before.
     */
    fun choose(preset: Float, lenses: List<PhysicalLens>): Choice? {
        if (!isUsable(lenses)) return null
        val b = base(lenses) ?: return null
        val sorted = lenses
            .map { it to ratio(it, b) }
            .sortedBy { it.second }
        val atOrBelow = sorted.filter { it.second <= preset + RATIO_EPS }
        val (lens, r) = if (atOrBelow.isNotEmpty()) atOrBelow.last() else sorted.first()
        return Choice(lens, r, if (r > 0f) preset / r else 1f)
    }

    /**
     * The effective ratio a preset actually lands on. A physical
     * lens cannot zoom below its own 1.0, so a preset below the
     * widest lens lands on that lens's native ratio instead.
     */
    fun achievableRatio(preset: Float, lenses: List<PhysicalLens>): Float? {
        val c = choose(preset, lenses) ?: return null
        return c.lensRatio * maxOf(c.residualRatio, 1f)
    }

    /**
     * The preset chips this lens set may honestly offer: candidates
     * whose achievable ratio is the preset itself (within
     * [REACH_TOLERANCE]). A 0.5× chip is not offered on a phone with
     * no ultrawide lens, where it could only ever land on 1×.
     */
    fun visiblePresets(lenses: List<PhysicalLens>): List<Float> =
        ZoomControl.PRESET_CANDIDATES.filter { p ->
            val a = achievableRatio(p, lenses)
            a != null && abs(a - p) <= REACH_TOLERANCE
        }

    /** The widest lens's native ratio (the plan's effective floor). */
    fun minRatio(lenses: List<PhysicalLens>): Float {
        val b = base(lenses) ?: return 1f
        return lenses.minOfOrNull { ratio(it, b) } ?: 1f
    }

    /** The longest lens's native ratio. */
    fun maxRatio(lenses: List<PhysicalLens>): Float {
        val b = base(lenses) ?: return 1f
        return lenses.maxOfOrNull { ratio(it, b) } ?: 1f
    }
}
