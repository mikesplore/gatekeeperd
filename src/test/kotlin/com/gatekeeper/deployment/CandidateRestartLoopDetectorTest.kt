package com.gatekeeper.deployment

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CandidateRestartLoopDetectorTest {
    @Test
    fun `detects three restarts inside rolling window`() {
        val detector = CandidateRestartLoopDetector(restartLimit = 3, windowMillis = 60_000)

        assertFalse(detector.observe("candidate", 0, 0))
        assertFalse(detector.observe("candidate", 1, 5_000))
        assertFalse(detector.observe("candidate", 2, 10_000))
        assertTrue(detector.observe("candidate", 3, 15_000))
    }

    @Test
    fun `does not count old restarts after window expires`() {
        val detector = CandidateRestartLoopDetector(restartLimit = 3, windowMillis = 60_000)

        assertFalse(detector.observe("candidate", 1, 0))
        assertFalse(detector.observe("candidate", 2, 61_000))
        assertFalse(detector.observe("candidate", 3, 62_000))
        assertTrue(detector.observe("candidate", 4, 63_000))
    }

    @Test
    fun `reset starts a fresh observation for redeployed container name`() {
        val detector = CandidateRestartLoopDetector(restartLimit = 3, windowMillis = 60_000)

        assertFalse(detector.observe("candidate", 2, 0))
        detector.reset("candidate")
        assertFalse(detector.observe("candidate", 0, 1))
        assertFalse(detector.observe("candidate", 1, 2))
    }

    @Test
    fun `does not treat three slow restarts as a short restart loop`() {
        val detector = CandidateRestartLoopDetector(restartLimit = 3, windowMillis = 60_000)

        assertFalse(detector.observe("candidate", 1, 0))
        assertFalse(detector.observe("candidate", 2, 61_000))
        assertFalse(detector.observe("candidate", 3, 122_000))
    }
}
