package app.qrikkiyomi.core

import kotlin.math.abs

/**
 * v0.4.5: the zoom maths behind the scan screen's three zoom controls,
 * which the user explicitly asked to keep side by side — preset chips
 * on the preview, pinch on the preview, and the toolbar slider. All
 * three read and write the same ratio; everything funnels through
 * here so the clamp rules live in exactly one place.
 *
 * Pure JVM logic (no Android types) so it is unit-testable as-is. The
 * ratio range itself comes from the bound camera's zoomState; until a
 * camera reports one, [DEFAULT_MIN]..[DEFAULT_MAX] applies (the range
 * the slider has always used).
 */
object ZoomControl {
    const val DEFAULT_MIN = 1f
    const val DEFAULT_MAX = 6f

    /** Candidate preset ratios, ascending. Only those inside the
     * camera's real range are offered (see [visiblePresets]). */
    val PRESET_CANDIDATES: List<Float> = listOf(0.5f, 1f, 2f, 3f, 5f)

    /** A preset counts as inside the range even if the camera reports
     * its limit a hair inside the preset (float noise in zoomState). */
    private const val RANGE_EPS = 0.01f

    /** A chip reads as "current" when the live ratio is this close —
     * a pinch rarely lands on the exact preset value. */
    private const val SELECTED_TOLERANCE = 0.1f

    /** Clamps [value] into [min]..[max]. A broken range (min > max,
     * e.g. a zoomState that was never really read) falls back to the
     * default range rather than trapping the ratio. */
    fun clamp(value: Float, min: Float, max: Float): Float {
        val lo = if (min <= max) min else DEFAULT_MIN
        val hi = if (min <= max) max else DEFAULT_MAX
        return value.coerceIn(lo, hi)
    }

    /** One pinch step: scale [current] by the detector's incremental
     * [factor], clamped. Applying it per event (rather than from the
     * gesture's start value) means reversing direction at a clamp edge
     * responds immediately. */
    fun scaled(current: Float, factor: Float, min: Float, max: Float): Float =
        clamp(current * factor, min, max)

    /** The presets this camera can actually reach, ascending. */
    fun visiblePresets(min: Float, max: Float): List<Float> =
        PRESET_CANDIDATES.filter { it >= min - RANGE_EPS && it <= max + RANGE_EPS }

    /** Whether [preset] is the ratio currently in effect. */
    fun isSelected(zoom: Float, preset: Float): Boolean =
        abs(zoom - preset) <= SELECTED_TOLERANCE

    /** Chip label: whole ratios carry no decimal point ("1×", "2×");
     * the one fractional preset keeps it ("0.5×"). */
    fun label(preset: Float): String =
        if (preset == preset.toInt().toFloat()) "${preset.toInt()}×" else "$preset×"
}
