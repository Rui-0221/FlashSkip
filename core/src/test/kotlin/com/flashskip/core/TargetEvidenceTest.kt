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
    fun `return window uses its current visible semantics and requires stable clean evidence`() {
        val evidence = TargetEvidence(window(button, marker), decision)
        val content = marker.copy(text = "首页")
        val destination = window(content).copy(windowId = 8)
        assertEquals(TargetObservation.ABSENT, evidence.observeReturn(destination))
        assertEquals(TargetObservation.UNOBSERVED, evidence.observeReturn(destination.copy(complete = false)))
        assertEquals(TargetObservation.AD_PRESENT, evidence.observeReturn(window(content.copy(text = "推广")).copy(windowId = 8)))
        val stability = evidence.crossWindowClosureCheck(1000L)
        assertFalse(stability.record(TargetObservation.ABSENT, 1120L))
        assertFalse(stability.record(TargetObservation.AD_PRESENT, 1230L))
        assertFalse(stability.record(TargetObservation.ABSENT, 1240L))
        assertTrue(stability.record(TargetObservation.ABSENT, 1340L))
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
        assertTrue(countdown.sameControlIdentity(countdown.copy(text = "跳过 2s")))
        assertFalse(countdown.sameControlIdentity(countdown.copy(text = "跳过引导")))
        assertFalse(button.sameControlIdentity(replacement))
        val noId = TargetEvidence(window(countdown), decision)
        assertTrue(noId.matches(countdown.copy(text = "跳过 2s")))
        assertFalse(noId.matches(countdown.copy(text = "关闭广告")))
        assertFalse(noId.matches(countdown.copy(bounds = RectBounds(850, 100, 1040, 200))))
        val unlabeled = countdown.copy(text = "")
        assertFalse(TargetEvidence(window(unlabeled), decision).matches(unlabeled))
    }

    @Test
    fun `only a unique identified nearby control may be reacquired after it moves`() {
        val evidence = TargetEvidence(window(button), decision, allowBoundedMovement = true)
        val moved = button.copy(bounds = RectBounds(820, 100, 960, 200))
        assertTrue(evidence.allowsRetry(window(moved), moved))
        assertFalse(evidence.allowsRetry(window(moved, complete = false), moved))
        assertFalse(evidence.allowsRetry(window(moved, button.copy(index = 2)), moved))
        assertFalse(evidence.matches(moved.copy(viewId = "demo:id/replacement")))
        assertFalse(evidence.matches(moved.copy(bounds = RectBounds(600, 100, 740, 200))))
        assertFalse(TargetEvidence(window(button), decision).matches(moved))
    }

    @Test
    fun `device splash countdown stays identified through its measured layout change`() {
        // Reduced from two snapshots in the same Android 16 launch.
        val start = recordedCountdown(RectBounds(1101, 157, 1167, 239), "5 | 跳过")
        val settled = recordedCountdown(RectBounds(949, 399, 1168, 481), "4 | 跳过")
        val machine = SkipStateMachine()
        machine.onWindowStateChanged(start.packageName, start.activityName, true, 1_000L, windowId = start.windowId)
        fun scan(snapshot: WindowSnapshot, at: Long) =
            machine.evaluateSnapshot(snapshot, at, true, false, 8000)

        assertEquals(EvaluationResult.AwaitStableLayout, scan(start, 5_858))
        assertEquals(EvaluationResult.NoOp, scan(settled.copy(complete = false), 5_910))
        assertEquals(EvaluationResult.AwaitStableLayout, scan(settled, 5_980))
        assertEquals(EvaluationResult.AwaitStableLayout, scan(settled, 6_012))
        val command = (scan(settled, 6_048) as EvaluationResult.ExecuteClick).command
        assertEquals(ClickMethod.NODE_ACTION, command.preferredMethod)
        assertFalse(command.allowImmediateGestureFallback)
        assertEquals(settled.nodes[2].bounds, command.decision.targetBounds)
    }

    @Test
    fun `measured splash relayout requires the same window control and independent ad marker`() {
        val start = recordedCountdown(RectBounds(1101, 157, 1167, 239), "5 | 跳过")
        val settled = recordedCountdown(RectBounds(949, 399, 1168, 481), "4 | 跳过")
        val decision = requireNotNull(SkipMatcher.findBestMatch(start, RuleIndex.defaultBuiltin()))
        val origin = TargetEvidence(start, decision, allowBoundedMovement = true)
        val target = settled.nodes[2]
        assertTrue(origin.allowsRetry(settled, target))
        assertFalse(origin.allowsRetry(settled.copy(windowId = 8), target))
        assertFalse(origin.allowsRetry(settled.copy(nodes = settled.nodes.filter { it.index != 3 }), target))
        assertFalse(origin.allowsRetry(settled.copy(nodes = settled.nodes + target.copy(index = 6)), target))
        assertFalse(origin.matches(target.copy(bounds = RectBounds(949, 920, 1168, 1002))))
        assertFalse(origin.matches(target.copy(viewId = "demo:id/other_splash_jump")))
    }

    @Test
    fun `recorded same window banner edge leaves skip reachable and a covered center is rejected`() {
        val captured = recordedCountdown(RectBounds(949, 399, 1168, 481), "4 | 跳过")
        val rules = RuleIndex.defaultBuiltin()
        assertEquals(listOf(4), SkipMatcher.findBestMatch(captured, rules)?.bannerNodeIndices)
        val nearEdge = captured.copy(nodes = captured.nodes.map {
            if (it.index == 4) it.copy(bounds = it.bounds.copy(bottom = 418)) else it
        })
        assertEquals(2, SkipMatcher.findBestMatch(nearEdge, rules)?.targetNodeIndex)
        val covered = captured.copy(nodes = captured.nodes.map {
            if (it.index == 4) it.copy(bounds = it.bounds.copy(bottom = 460)) else it
        })
        assertEquals(null, SkipMatcher.findBestMatch(covered, rules))
    }

    @Test
    fun `device SDK skip uses the labeled child instead of its moving outer area`() {
        val frame = RectBounds(0, 0, 1200, 2664)
        val area = root.copy(index = 1, parentIndex = 0, depth = 1, className = "android.widget.FrameLayout",
            viewId = "demo:id/sdm_myoffer_splash_skip_area", bounds = RectBounds(890, 0, 1200, 183), isClickable = true)
        val countdown = button.copy(index = 2, parentIndex = 1, depth = 2, className = "android.widget.TextView",
            viewId = "demo:id/sdm_myoffer_splash_skip", text = "5s | 跳过", bounds = RectBounds(929, 52, 1161, 131),
            isClickable = false)
        val badge = marker.copy(index = 3, parentIndex = 0, text = "", viewId = "demo:id/sdm_myoffer_ad_logo",
            bounds = RectBounds(1040, 2313, 1161, 2352))
        val before = WindowSnapshot("demo", "", 1200, 2664, true,
            listOf(root.copy(bounds = frame), area, countdown, badge), windowId = 7)
        val rules = RuleIndex.defaultBuiltin()
        assertEquals(null, SkipMatcher.findBestMatch(before.copy(nodes = listOf(before.nodes[0], area,
            badge.copy(index = 2))), rules))
        val decision = requireNotNull(SkipMatcher.findBestMatch(before, rules))
        assertEquals(countdown.index, decision.targetNodeIndex)
        val moved = countdown.copy(bounds = RectBounds(929, 173, 1161, 252))
        val after = before.copy(nodes = listOf(before.nodes[0],
            area.copy(bounds = RectBounds(890, 121, 1200, 304)), moved, badge))
        val evidence = TargetEvidence(before, decision, allowBoundedMovement = true)
        assertTrue(evidence.waitsForStableLayout)
        assertTrue(evidence.allowsRetry(after, moved))

        val machine = SkipStateMachine().apply {
            onWindowStateChanged("demo", "demo.SplashActivity", true, 1000L, windowId = 7)
        }
        fun scan(snapshot: WindowSnapshot, now: Long) = machine.evaluateSnapshot(snapshot, now, true, false, 8000)
        assertFalse(scan(before, 1500L) is EvaluationResult.ExecuteClick)
        assertFalse(scan(after, 1540L) is EvaluationResult.ExecuteClick)
        assertFalse(scan(after, 1570L) is EvaluationResult.ExecuteClick)
        assertTrue(scan(after, 1600L) is EvaluationResult.ExecuteClick)
    }

    @Test
    fun `same window banner uses only the labeled native close and never escalates to a tap`() {
        val ad = recordedCountdown(RectBounds(949, 435, 1168, 517), "5 | 跳过")
        val machine = SkipStateMachine().apply {
            onWindowStateChanged(ad.packageName, ad.activityName, true, 1000, windowId = ad.windowId)
        }
        fun scan(snapshot: WindowSnapshot, time: Long) = machine.evaluateSnapshot(snapshot, time, true, false, 8000)
        val beforeBannerIsInTheTree = ad.copy(nodes = ad.nodes.take(4))
        assertEquals(EvaluationResult.AwaitStableLayout, scan(beforeBannerIsInTheTree, 6000))
        val command = (scan(ad, 6250) as EvaluationResult.ExecuteClick).command
        assertEquals(ClickMethod.NODE_ACTION, command.preferredMethod)
        assertFalse(command.allowImmediateGestureFallback)
        machine.onActionDispatched(command.sessionId, command.attemptId, ClickMethod.NODE_ACTION, true)
        machine.finishVerification(command.sessionId, command.attemptId, VerifyOutcome.STILL_PRESENT,
            TargetEvidence(ad, command.decision))
        assertFalse(machine.armed)
    }

    @Test
    fun `late startup requires an early loading surface and a later independently marked SDK countdown`() {
        val ad = recordedCountdown(RectBounds(949, 399, 1168, 481), "4 | 跳过")
        val start = ad.copy(nodes = listOf(ad.nodes[0]))
        fun machine() = SkipStateMachine().apply {
            onWindowStateChanged(ad.packageName, ad.activityName, true, 1000, windowId = ad.windowId)
        }
        fun scan(machine: SkipStateMachine, snapshot: WindowSnapshot, time: Long) =
            machine.evaluateSnapshot(snapshot, time, true, false, 8000)

        val waiting = machine()
        assertEquals(EvaluationResult.NoOp, scan(waiting, start, 1200))
        assertEquals(EvaluationResult.NoOp, scan(waiting, ad.copy(complete = false), 9200))
        assertEquals(EvaluationResult.AwaitStableLayout, scan(waiting, ad, 10000))
        assertTrue(scan(waiting, ad, 10066) is EvaluationResult.ExecuteClick)

        val business = machine()
        assertEquals(EvaluationResult.NoOp, scan(business, start, 1250))
        assertEquals(EvaluationResult.NoOp, scan(business, start.copy(nodes = listOf(
            start.nodes.single().copy(text = "首页")
        )), 3600))
        assertTrue(scan(business, ad, 10000) is EvaluationResult.OutOfWindow)
        assertTrue(scan(machine(), ad, 10000) is EvaluationResult.OutOfWindow)
        val timedOut = machine()
        assertEquals(EvaluationResult.NoOp, scan(timedOut, start, 1250))
        assertTrue(scan(timedOut, ad, 13100) is EvaluationResult.OutOfWindow)
    }

    @Test
    fun `late floating splash uses the visible SDK close container within the existing startup grace`() {
        val frame = RectBounds(-24, 807, 816, 2172)
        val surface = FreeformViewport(frame, RectBounds(-23, 807, 816, 2172), 4,
            RectBounds(-23, 807, 816, 891), emptyList())
        val content = RectBounds(0, 0, 839, 1365)
        val close = RectBounds(727, 84, 809, 166)
        val ad = window().copy(screenWidth = 1200, screenHeight = 2664, isFullScreen = false, freeform = surface, nodes = listOf(
            root.copy(bounds = content),
            root.copy(index = 1, parentIndex = 0, depth = 1, bounds = RectBounds(0, 0, 839, 1159)),
            button.copy(index = 2, parentIndex = 1, depth = 2, className = "android.widget.RelativeLayout",
                viewId = "id/ms_skipView_container", text = "", bounds = close, isClickable = false),
            button.copy(index = 3, parentIndex = 2, depth = 3, className = "android.view.View",
                viewId = "id/ms_skipView", text = "", bounds = close, isClickable = false),
            marker.copy(index = 4, parentIndex = 1, depth = 2, text = "", viewId = "id/ms_ad_tag_layout",
                bounds = RectBounds(728, 1099, 839, 1159)),
            marker.copy(index = 5, parentIndex = 1, depth = 2, text = "摇一摇查看详情", viewId = "id/ms_shake_text",
                bounds = RectBounds(300, 934, 530, 994)),
            marker.copy(index = 6, parentIndex = 4, depth = 3, text = "", viewId = "id/ms_img_meishu_ad_tag",
                className = "android.widget.ImageView", bounds = RectBounds(728, 1099, 809, 1127))
        ))
        fun machine(withPlaceholder: Boolean = true) = SkipStateMachine().apply {
            onWindowStateChanged(ad.packageName, ad.activityName, true, 1000L, windowId = ad.windowId)
            if (withPlaceholder) {
                val placeholder = ad.copy(nodes = listOf(ad.nodes.first()))
                assertEquals(EvaluationResult.NoOp, evaluateSnapshot(placeholder, 1200L, true, false, 8000L))
            }
        }
        fun scan(machine: SkipStateMachine, snapshot: WindowSnapshot = ad, now: Long = 11016L) =
            machine.evaluateSnapshot(snapshot, now, true, false, 8000L)

        val ready = machine()
        assertEquals(EvaluationResult.AwaitStableLayout, scan(ready))
        val result = scan(ready, now = 11090L) as EvaluationResult.ExecuteClick
        assertEquals(2, result.command.decision.targetNodeIndex)
        assertEquals(ClickMethod.GESTURE, result.command.preferredMethod)
        assertTrue(scan(machine(false)) is EvaluationResult.OutOfWindow)
        assertTrue(scan(machine(), ad.copy(nodes = ad.nodes.dropLast(1))) is EvaluationResult.OutOfWindow)
        assertEquals(EvaluationResult.NoOp, scan(machine(), ad.copy(complete = false)))
        assertEquals(EvaluationResult.NoOp, scan(machine(), ad.copy(windowId = 9)))
        assertTrue(scan(machine(), now = 15001L) is EvaluationResult.OutOfWindow)
    }

    private fun recordedCountdown(bounds: RectBounds, text: String): WindowSnapshot {
        val screen = RectBounds(0, 0, 1200, 2664)
        return WindowSnapshot("demo", "demo.StartupActivity", 1200, 2664, true, listOf(
            root.copy(bounds = screen),
            root.copy(index = 1, parentIndex = 0, depth = 1, className = "android.widget.RelativeLayout",
                viewId = "demo:id/wm_splash_container", bounds = RectBounds(0, 0, 1200, 2369)),
            button.copy(index = 2, parentIndex = 1, depth = 2, className = "android.widget.TextView",
                viewId = "demo:id/wm_splash_jump_tv", text = text, bounds = bounds),
            marker.copy(index = 3, parentIndex = 1, depth = 2, bounds = RectBounds(1075, 2283, 1153, 2336)),
            root.copy(index = 4, parentIndex = 1, depth = 2, className = "android.widget.LinearLayout",
                bounds = RectBounds(33, 153, 1167, 366), isVisibleToUser = !text.startsWith("5")),
            marker.copy(index = 5, parentIndex = 4, depth = 3, text = "刚刚", viewId = "",
                bounds = RectBounds(1068, 186, 1134, 230), isVisibleToUser = !text.startsWith("5"))
        ), windowId = 7)
    }
}
