package com.flashskip.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetEvidenceTest {
    private val root = NodeSnapshot(0, -1, "FrameLayout", "", "", "",
        RectBounds(0, 0, 1080, 2400), true, false, true, 0)
    private val button = NodeSnapshot(1, 0, "Button", "demo:id/original", "跳过", "",
        RectBounds(900, 100, 1040, 200), true, true, true, 1)
    private val marker = NodeSnapshot(2, 0, "TextView", "demo:id/badge", "广告", "",
        RectBounds(10, 10, 80, 80), true, false, true, 1)
    private val decision = MatchDecision(1, 1, "跳过", "text", false, false, button.bounds, 100)

    private fun window(vararg nodes: NodeSnapshot, complete: Boolean = true): WindowSnapshot =
        WindowSnapshot("demo", "demo.SplashActivity", 1080, 2400, true, listOf(root, *nodes), complete, 2)

    @Test
    fun `replacement label or a newly exposed ad badge both prove the creative remains`() {
        val original = TargetEvidence(window(button, marker), decision)
        assertEquals(TargetObservation.AD_PRESENT, original.observe(window(marker.copy(text = "推广"))))
        assertEquals(TargetObservation.AD_PRESENT, original.observe(window(marker.copy(text = "正在播放"))))
        val noBadge = TargetEvidence(window(button), decision)
        val check = noBadge.closureCheck(1000L)
        assertFalse(check.record(noBadge.observe(window()), 1024L))
        assertFalse(check.record(noBadge.observe(window()), 1060L))
        assertFalse(check.record(noBadge.observe(window(marker.copy(text = "推广"))), 1090L))
        assertFalse(check.record(noBadge.observe(window()), 1120L))
        assertTrue(check.record(noBadge.observe(window()), 1145L))
    }

    @Test
    fun `only consecutive complete absences confirm genuine closure`() {
        val evidence = TargetEvidence(window(button, marker), decision)
        val check = evidence.closureCheck(1000L)
        assertFalse(check.record(evidence.observe(window()), 1032L))
        assertEquals(TargetObservation.UNOBSERVED, evidence.observe(window(complete = false)))
        assertFalse(check.record(evidence.observe(window(complete = false)), 1072L))
        assertFalse(check.record(evidence.observe(window()), 1096L))
        assertTrue(check.record(evidence.observe(window()), 1120L))
        assertEquals(TargetObservation.UNOBSERVED, evidence.observe(window().copy(windowId = 4)))
    }

    @Test
    fun `IDs never fall back to labels and ID-less countdowns require stable semantic identity`() {
        val evidence = TargetEvidence(window(button), decision)
        val replacement = button.copy(viewId = "demo:id/replacement")
        assertFalse(evidence.matches(replacement))
        assertFalse(evidence.matches(button.copy(viewId = "")))
        assertFalse(evidence.matches(button.copy(text = "领取")))
        assertEquals(TargetObservation.AD_PRESENT, evidence.observe(window(replacement)))

        val countdown = button.copy(viewId = "", text = "跳过 3s")
        val noId = TargetEvidence(window(countdown), decision)
        assertTrue(noId.matches(countdown.copy(text = "跳过 2s")))
        assertFalse(noId.matches(countdown.copy(text = "关闭广告")))
        assertFalse(noId.matches(countdown.copy(bounds = RectBounds(850, 100, 1040, 200))))
        val unlabeled = countdown.copy(text = "")
        assertFalse(TargetEvidence(window(unlabeled), decision).matches(unlabeled))
    }
}
