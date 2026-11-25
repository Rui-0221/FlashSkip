package com.flashskip.core

import org.junit.Assert.*
import org.junit.Test

class FreeformSnapshotTest {
    private val frame = RectBounds(126, 561, 1074, 2102)
    private val raw = RectBounds(126, 561, 1326, 2512)
    private val bar = RectBounds(126, 561, 1074, 656)
    private val viewport = FreeformViewport(frame, raw, 22, bar, emptyList())
    private val root = NodeSnapshot(0, -1, "FrameLayout", "", "", "", viewport.local(raw), true, true, true, 0)
    private val button = NodeSnapshot(1, 0, "Button", "example:id/skip", "跳过 3s", "",
        RectBounds(960, 135, 1170, 225), true, true, true, 1)
    private val snapshot = WindowSnapshot("example", "SplashActivity", 1200, 2664, false,
        listOf(root, button), windowId = 21, freeform = viewport)

    private fun match(s: WindowSnapshot) = SkipMatcher.findBestMatch(s, RuleIndex.defaultBuiltin())

    @Test fun scaledOffsetTaskMapsButtonInsideThePhysicalWindow() {
        val hit = match(snapshot)!!
        assertEquals(RectBounds(884, 668, 1050, 739), viewport.screenTarget(hit.targetBounds))
        assertNull(match(snapshot.copy(freeform = null)))
        assertFalse(snapshot.isFullScreen)
    }

    @Test fun windowCenterAndOversizedAncestorUseTaskDimensions() {
        val centered = button.copy(bounds = RectBounds(520, 940, 680, 1030))
        assertNull(match(snapshot.copy(nodes = listOf(root, centered))))
        val offsetCenter = centered.copy(bounds = RectBounds(520, 1470, 680, 1560), text = "跳过")
        assertNull(match(snapshot.copy(nodes = listOf(root, offsetCenter))))
        val hit = match(snapshot.copy(nodes = listOf(root, button.copy(isClickable = false))))!!
        assertEquals(1, hit.clickNodeIndex)
        assertTrue(hit.forceGestureOnly)
        assertNull(match(snapshot.copy(nodes = listOf(root, button.copy(text = "跳过引导", viewId = "example:id/guide")))))
    }

    @Test fun captionSystemOverlayAndClippedTargetsCannotReceiveInput() {
        assertNull(match(snapshot.copy(nodes = listOf(root, button.copy(bounds = RectBounds(980, 0, 1160, 70))))))
        val occluded = viewport.copy(obstructions = listOf(RectBounds(880, 660, 1060, 749)))
        assertNull(match(snapshot.copy(freeform = occluded)))
        assertNull(match(snapshot.copy(nodes = listOf(root, button.copy(bounds = RectBounds(1110, 135, 1290, 225))))))
        assertFalse(FreeformViewport.aligned(frame, raw.copy(top = raw.top + 18), 1200, 2664))
        assertFalse(FreeformViewport.aligned(frame, raw.copy(bottom = raw.bottom - 155), 1200, 2664))
    }

    @Test fun attemptCanOnlyUseTheArmedWindow() {
        val machine = SkipStateMachine()
        machine.onWindowStateChanged("example", "SplashActivity", true, 1000, windowId = 21)
        fun evaluate(s: WindowSnapshot) = machine.evaluateSnapshot(s, 1250, true, false, 6000)
        assertEquals(EvaluationResult.NoOp, evaluate(snapshot.copy(windowId = 19)))
        assertTrue(evaluate(snapshot) is EvaluationResult.ExecuteClick)
    }

    @Test fun retryComparesBothCoordinateBasesAndRequiresTheSameTask() {
        val evidence = TargetEvidence(snapshot, match(snapshot)!!, allowBoundedMovement = true)
        val scaled = viewport.copy(nodeRoot = frame)
        val sameButton = button.copy(bounds = scaled.local(viewport.screenTarget(button.bounds)!!))
        val updated = snapshot.copy(nodes = listOf(root.copy(bounds = scaled.local(frame)), sameButton), freeform = scaled)
        assertTrue(evidence.allowsRetry(updated, sameButton))
        assertFalse(evidence.allowsRetry(updated.copy(complete = false), sameButton))
        assertFalse(evidence.allowsRetry(updated.copy(freeform = scaled.copy(captionId = 85)), sameButton))
        assertFalse(evidence.allowsRetry(updated.copy(nodes = updated.nodes + sameButton.copy(index = 2)), sameButton))
    }

    @Test fun closureNeedsSameTaskDecorAndCompleteCleanTree() {
        val evidence = TargetEvidence(snapshot, match(snapshot)!!)
        val clean = snapshot.copy(nodes = listOf(root))
        assertEquals(TargetObservation.TARGET_PRESENT, evidence.observe(snapshot))
        assertEquals(TargetObservation.ABSENT, evidence.observe(clean))
        assertEquals(TargetObservation.UNOBSERVED, evidence.observe(clean.copy(complete = false)))
        assertEquals(TargetObservation.UNOBSERVED, evidence.observe(clean.copy(freeform = null, isFullScreen = true)))
        val restored = clean.copy(windowId = 19)
        assertEquals(TargetObservation.ABSENT, evidence.observeReturn(restored))
        assertEquals(TargetObservation.UNOBSERVED, evidence.observeReturn(restored.copy(freeform = viewport.copy(captionId = 98))))
        assertEquals(TargetObservation.UNOBSERVED, evidence.observeReturn(restored.copy(freeform = null, isFullScreen = true)))
        assertEquals(TargetObservation.AD_PRESENT, evidence.observeReturn(snapshot.copy(windowId = 19)))
    }
}
