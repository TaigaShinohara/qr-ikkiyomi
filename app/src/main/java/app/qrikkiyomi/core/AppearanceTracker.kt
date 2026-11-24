package app.qrikkiyomi.core

/**
 * v0.4.2: tracks which raw values are "present" in the detection set so
 * auto-save-on-scan fires exactly once per appearance.
 *
 * Feed it the raw values of the current detection set on every update;
 * it returns the ones that were not present in the previous update —
 * i.e. the codes that have just appeared. A code that stays in view is
 * returned only the first time; once it leaves the set, a later return
 * counts as a new appearance (the library layer still dedupes against
 * history, so a re-appearance never creates a duplicate row).
 *
 * Pure JVM logic (no Android types) so it is unit-testable as-is.
 */
class AppearanceTracker {
    private val present = LinkedHashSet<String>()

    /**
     * Returns the raw values in [current] that were not present in the
     * previous call, in first-seen order, duplicates within [current]
     * collapsed. The present set is then replaced by [current].
     */
    fun onDetections(current: Collection<String>): List<String> {
        val fresh = current.filter { it !in present }.distinct()
        present.clear()
        present.addAll(current)
        return fresh
    }

    /** Forgets everything present (e.g. a fresh image was loaded). */
    fun reset() {
        present.clear()
    }
}
