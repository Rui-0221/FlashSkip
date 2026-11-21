package com.flashskip.experimental

import com.flashskip.core.ForegroundWindow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipActionGateTest {
    private val gate = SkipActionGate()
    private val accessibility = Any()
    private val shizuku = Any()
    private val splash = ForegroundWindow("example.target", 12)
    private val nextLaunch = ForegroundWindow("example.target", 18)

    @Test
    fun preventsAnEngineChangeWhileAnActionIsInProgress() {
        assertTrue(gate.begin(shizuku, splash, 1_000L) { true })
        assertFalse(gate.begin(accessibility, nextLaunch, 2_000L) { true })
        gate.finish(accessibility, 2_010L)
        assertFalse(gate.begin(accessibility, nextLaunch, 2_020L) { true })
    }

    @Test
    fun retainsOriginalWindowOwnerAndLetsTheNewEngineHandleLaterLaunches() {
        assertTrue(gate.begin(accessibility, splash, 1_000L) { true })
        gate.finish(accessibility, 1_050L)
        assertFalse(gate.begin(shizuku, nextLaunch, 1_200L) { true })
        assertFalse(gate.begin(shizuku, splash, 2_000L) { true })
        assertTrue(gate.begin(shizuku, nextLaunch, 2_000L) { true })
    }

    @Test
    fun permitsAValidatedRetryByTheSameEngineAndHonorsCurrentSelection() {
        assertFalse(gate.begin(shizuku, splash, 1_000L) { false })
        assertTrue(gate.begin(accessibility, splash, 1_050L) { true })
        gate.finish(accessibility, 1_055L)
        assertTrue(gate.begin(accessibility, splash, 1_080L) { true })
    }
}
