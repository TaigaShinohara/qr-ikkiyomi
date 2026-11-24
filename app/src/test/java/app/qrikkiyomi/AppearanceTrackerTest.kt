package app.qrikkiyomi

import app.qrikkiyomi.core.AppearanceTracker
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v0.4.2: auto-save-on-detect must fire once per appearance — the pure
 * dedupe logic behind it (FR-SCAN-07 revised).
 */
class AppearanceTrackerTest {

    @Test
    fun firstAppearanceReturnsEverything() {
        val tracker = AppearanceTracker()
        assertEquals(listOf("a", "b"), tracker.onDetections(listOf("a", "b")))
    }

    @Test
    fun stayingInViewDoesNotRefire() {
        val tracker = AppearanceTracker()
        tracker.onDetections(listOf("a", "b"))
        assertEquals(emptyList<String>(), tracker.onDetections(listOf("a", "b")))
        assertEquals(emptyList<String>(), tracker.onDetections(listOf("b", "a")))
    }

    @Test
    fun onlyTheNewcomerFires() {
        val tracker = AppearanceTracker()
        tracker.onDetections(listOf("a"))
        assertEquals(listOf("b"), tracker.onDetections(listOf("a", "b")))
    }

    @Test
    fun leavingAndReturningIsANewAppearance() {
        val tracker = AppearanceTracker()
        tracker.onDetections(listOf("a", "b"))
        assertEquals(emptyList<String>(), tracker.onDetections(listOf("a")))
        // "b" left the set, so its return counts as a new appearance.
        assertEquals(listOf("b"), tracker.onDetections(listOf("a", "b")))
    }

    @Test
    fun duplicatesWithinOneFrameFireOnce() {
        val tracker = AppearanceTracker()
        assertEquals(listOf("a"), tracker.onDetections(listOf("a", "a", "a")))
    }

    @Test
    fun emptyFrameClearsPresence() {
        val tracker = AppearanceTracker()
        tracker.onDetections(listOf("a"))
        assertEquals(emptyList<String>(), tracker.onDetections(emptyList()))
        assertEquals(listOf("a"), tracker.onDetections(listOf("a")))
    }

    @Test
    fun resetForgetsEverything() {
        val tracker = AppearanceTracker()
        tracker.onDetections(listOf("a", "b"))
        tracker.reset()
        assertEquals(listOf("a", "b"), tracker.onDetections(listOf("a", "b")))
    }
}
