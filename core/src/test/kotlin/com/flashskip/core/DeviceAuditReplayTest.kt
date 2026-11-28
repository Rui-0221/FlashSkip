package com.flashskip.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAuditReplayTest {
    private val rules = RuleIndex.defaultBuiltin()

    @Test
    fun `recorded pills dispatch a gesture on the first ad frame without an activity name`() {
        for (round in listOf(29, 31, 32, 36, 37, 38, 40, 77, 79)) {
            val tree = replay(round)
            val machine = start(tree)
            val command = (evaluate(machine, tree, 3500) as EvaluationResult.ExecuteClick).command
            assertEquals("round $round", "rule:sdk-layout-skip", command.decision.matchedVia)
            assertEquals(ClickMethod.GESTURE, command.preferredMethod)
            assertEquals("layout_skip", tree.nodes[command.decision.targetNodeIndex].shortId)
        }
    }

    @Test
    fun `notification creatives with a promotional countdown have no confirmed close control`() {
        for (round in listOf(3, 14, 64, 67, 70, 71)) {
            val tree = replay(round)
            assertNull("round $round", SkipMatcher.findBestMatch(tree, rules))
            assertEquals(EvaluationResult.NoOp, evaluate(start(tree), tree, 3500))
        }
        for (round in listOf(3, 70)) {
            val tree = replay(round, "-hidden")
            assertNull("round $round with hidden banner", SkipMatcher.findBestMatch(tree, rules))
        }
    }

    @Test
    fun `partially visible self drawn target can dispatch during floating startup grace`() {
        val loading = replay(52, "-loading")
        val tree = replay(52)
        val machine = start(loading)
        assertEquals(EvaluationResult.NoOp, evaluate(machine, loading, 1100))
        val command = (evaluate(machine, tree, 8200) as EvaluationResult.ExecuteClick).command
        assertEquals("self_drawn_skip", command.decision.matchedVia)
        assertEquals(ClickMethod.GESTURE, command.preferredMethod)
        val viewport = requireNotNull(tree.freeform)
        val visible = viewport.screenTarget(command.decision.targetBounds)
        assertEquals(0, visible?.left)
        assertEquals(83, visible?.right)
        assertTrue(requireNotNull(visible).centerX > 0)
        assertNull(SkipMatcher.findBestMatch(tree.copy(freeform = viewport.copy(obstructions = listOf(visible))), rules))
    }

    @Test
    fun `new physical window takes ownership of the next launch without using the old deadline`() {
        val previous = replay(53, "-loading")
        val current = replay(54, "-loading")
        val tree = replay(54)
        val machine = start(previous)
        evaluate(machine, previous, 1100)
        assertEquals(EvaluationResult.NoOp, evaluate(machine, current, 7500))
        val oldSession = machine.sessionId
        machine.onWindowStateChanged(tree.packageName, "", false, 6900, windowId = tree.windowId,
            confirmedFloatingWindow = true)
        assertTrue(machine.sessionId > oldSession)
        assertEquals(6900L, machine.launchAtMs)
        assertEquals(EvaluationResult.NoOp, evaluate(machine, current, 7513))
        val command = (evaluate(machine, tree, 13700) as EvaluationResult.ExecuteClick).command
        assertEquals(ClickMethod.GESTURE, command.preferredMethod)
        assertEquals("layout_skip", tree.nodes[command.decision.targetNodeIndex].shortId)
    }

    @Test
    fun `foreground recheck can reacquire the same anonymous close after tiny bounds changes`() {
        val tree = replay(52)
        val machine = start(tree)
        val first = (evaluate(machine, tree, 2000) as EvaluationResult.ExecuteClick).command
        val evidence = TargetEvidence(tree, first.decision, allowBoundedMovement = true, followsVisibleAd = true)
        val target = tree.nodes[first.decision.targetNodeIndex]
        val moved = target.copy(bounds = target.bounds.copy(left = target.bounds.left + 2, right = target.bounds.right + 2))
        val next = tree.copy(nodes = tree.nodes.map { if (it.index == target.index) moved else it })
        assertTrue(evidence.matches(target))
        assertTrue(evidence.matches(moved))
        assertEquals(TargetObservation.TARGET_PRESENT, evidence.observe(next))
        machine.onActionDispatched(first.sessionId, first.attemptId, ClickMethod.GESTURE, ok = false,
            refreshTargetBeforeInput = evidence)
        assertEquals(EvaluationResult.AwaitStableLayout, evaluate(machine, next, 2100))
        val retry = (evaluate(machine, next, 2160) as EvaluationResult.ExecuteClick).command
        assertEquals(2, retry.attemptNumber)
        assertEquals(ClickMethod.GESTURE, retry.preferredMethod)
    }

    @Test
    fun `the same capsule keeps its deadline and can retry after a no-op gesture and native click`() {
        val tree = replay(31)
        val machine = start(tree)
        val first = (evaluate(machine, tree, 2000) as EvaluationResult.ExecuteClick).command
        val evidence = TargetEvidence(tree, first.decision, allowBoundedMovement = true, followsVisibleAd = true)
        val current = tree.copy(nodes = tree.nodes.map { if (it.shortId == "tv_close_time") it.copy(text = "(4)") else it })
        var command = first
        for (number in 2..6) {
            machine.onActionDispatched(command.sessionId, command.attemptId, command.preferredMethod, ok = true)
            machine.finishVerification(command.sessionId, command.attemptId, VerifyOutcome.STILL_PRESENT, evidence)
            val at = 2000L + (number - 2) * 160
            assertEquals(EvaluationResult.AwaitStableLayout, evaluate(machine, current, at + 100))
            command = (evaluate(machine, current, at + 160) as EvaluationResult.ExecuteClick).command
            assertEquals(number, command.attemptNumber)
            assertEquals(if (number == 2) ClickMethod.NODE_ACTION else ClickMethod.GESTURE, command.preferredMethod)
        }
        assertEquals(1000L, machine.launchAtMs)
        assertNotNull(command.retryTarget)
        machine.finishVerification(command.sessionId, command.attemptId, VerifyOutcome.NAVIGATED, evidence)
        assertEquals(EvaluationResult.NoOp, evaluate(machine, current, 4000))
    }

    @Test
    fun `interactive video close is recognized below a slightly overlapping system caption`() {
        val tree = replay(7, "-c6")
        val placeholder = replay(7, "-c6-placeholder")
        val decision = requireNotNull(SkipMatcher.findBestMatch(tree, rules))
        assertEquals(13, decision.targetNodeIndex)
        assertTrue(decision.nativeOnly)
        assertTrue(decision.provenStartupAd)
        val machine = start(tree)
        assertEquals(EvaluationResult.NoOp, evaluate(machine, placeholder, 1200))
        assertEquals(EvaluationResult.AwaitStableLayout, evaluate(machine, tree, 7600))
        val click = (evaluate(machine, tree, 7680) as EvaluationResult.ExecuteClick).command
        assertEquals(ClickMethod.NODE_ACTION, click.preferredMethod)
        val withoutSdkBadge = tree.copy(nodes = tree.nodes.map {
            if (it.index == 31) it.copy(viewId = "") else it
        })
        assertTrue(SkipMatcher.findBestMatch(withoutSdkBadge, rules)?.provenStartupAd != true)
        val late = start(tree)
        evaluate(late, placeholder, 1200)
        assertEquals(EvaluationResult.OutOfWindow(6600), evaluate(late, withoutSdkBadge, 7600))
    }

    @Test
    fun `interactive video retries the same proven native control until the ad evidence disappears`() {
        val tree = replay(7, "-c6")
        val machine = start(tree)
        evaluate(machine, replay(7, "-c6-placeholder"), 1200)
        assertEquals(EvaluationResult.AwaitStableLayout, evaluate(machine, tree, 7600))
        val first = (evaluate(machine, tree, 7680) as EvaluationResult.ExecuteClick).command
        val evidence = requireNotNull(TargetEvidence.forStructuredNativeRetry(tree, first.decision))
        machine.onActionDispatched(first.sessionId, first.attemptId, ClickMethod.NODE_ACTION, ok = false,
            refreshTargetBeforeInput = evidence)
        val second = (evaluate(machine, tree, 7860) as EvaluationResult.ExecuteClick).command
        assertEquals(ClickMethod.NODE_ACTION, second.preferredMethod)
        assertFalse(second.allowImmediateGestureFallback)
        machine.onActionDispatched(second.sessionId, second.attemptId, second.preferredMethod, ok = true)
        machine.finishVerification(second.sessionId, second.attemptId, VerifyOutcome.STILL_PRESENT, evidence)
        val third = (evaluate(machine, tree, 8040) as EvaluationResult.ExecuteClick).command
        assertEquals(ClickMethod.NODE_ACTION, third.preferredMethod)
        assertFalse(third.allowImmediateGestureFallback)
        machine.onActionDispatched(third.sessionId, third.attemptId, third.preferredMethod, ok = true)
        machine.finishVerification(third.sessionId, third.attemptId, VerifyOutcome.STILL_PRESENT, evidence)
        val withoutSdkBadge = tree.copy(nodes = tree.nodes.map {
            if (it.shortId == "sig_ad_privacy_ad_logo") it.copy(viewId = "") else it
        })
        assertEquals(EvaluationResult.OutOfWindow(7220), evaluate(machine, withoutSdkBadge, 8220))
    }

    @Test
    fun `native pill may expand left as its verified countdown appears`() {
        val early = replay(5, "-ptg-early")
        val late = replay(5, "-ptg-settled")
        val decision = requireNotNull(SkipMatcher.findBestMatch(early, rules))
        val proof = requireNotNull(TargetEvidence.forNativeReacquisition(early, decision))
        val anchor = late.nodes.single { it.shortId == "ptgskiplayout" }
        val children = late.nodes.filter { it.parentIndex == anchor.index }
        val countdown = children.single { it.effectiveText == "5" }
        val label = children.single { it.effectiveText == "跳过" }
        assertTrue(proof.confirmsNativePillRelayout(anchor, children))
        assertFalse(proof.confirmsNativePillRelayout(anchor, listOf(label, countdown.copy(text = "购买"))))
        assertFalse(proof.confirmsNativePillRelayout(anchor, listOf(label, countdown.copy(isClickable = true))))
        assertFalse(proof.confirmsNativePillRelayout(anchor, children + countdown))
        assertFalse(proof.confirmsNativePillRelayout(anchor.copy(bounds = anchor.bounds.copy(right = 1168)), children))
    }

    @Test
    fun `recorded native pill lacking its ad badge can be reacquired in the verified creative`() {
        val early = replay(9, "-ptg-unlabeled").let { it.copy(activityName = "${it.packageName}.SplashActivity") }
        val later = replay(5, "-ptg-settled").copy(windowId = early.windowId, activityName = early.activityName)
        val machine = SkipStateMachine().apply {
            onWindowStateChanged(early.packageName, early.activityName, true, 1000, windowId = early.windowId)
        }
        val initial = (evaluate(machine, early, 1100) as EvaluationResult.ExecuteClick).command
        assertEquals(ClickMethod.NODE_ACTION, initial.preferredMethod)
        val proof = requireNotNull(TargetEvidence.forNativeReacquisition(early, initial.decision))

        val withoutCreative = early.copy(nodes = early.nodes.map {
            if (it.shortId == "ptgsplashiv") it.copy(viewId = "") else it
        })
        assertNull(TargetEvidence.forNativeReacquisition(withoutCreative, initial.decision))
        val obstructed = early.copy(obstructions = listOf(early.nodes[initial.decision.clickNodeIndex].bounds))
        assertNull(TargetEvidence.forNativeReacquisition(obstructed, initial.decision))

        machine.onActionDispatched(initial.sessionId, initial.attemptId, initial.preferredMethod, false, proof)
        assertEquals(EvaluationResult.NoOp, evaluate(machine, later, 1120))
        val retried = (evaluate(machine, later, 1180) as EvaluationResult.ExecuteClick).command
        assertEquals(ClickMethod.NODE_ACTION, retried.preferredMethod)
        assertEquals(proof, retried.retryTarget)
        assertFalse(retried.allowImmediateGestureFallback)
    }

    @Test
    fun `resized floating pill reacquires the same native parent below the caption`() {
        val early = replay(6, "-ptg-floating").let { it.copy(activityName = "${it.packageName}.SplashActivity") }
        val viewport = requireNotNull(early.freeform)
        val machine = SkipStateMachine().apply {
            onWindowStateChanged(early.packageName, early.activityName, false, 1000, windowId = early.windowId,
                confirmedFloatingWindow = true)
        }
        val first = (evaluate(machine, early, 1100) as EvaluationResult.ExecuteClick).command
        val proof = requireNotNull(TargetEvidence.forNativeReacquisition(early, first.decision))
        assertEquals(ClickMethod.NODE_ACTION, first.preferredMethod)
        val anchor = early.nodes[first.decision.clickNodeIndex]
        val physical = requireNotNull(viewport.screenTarget(anchor.bounds))
        assertEquals(viewport.caption.bottom, physical.top)
        assertNull(TargetEvidence.forNativeReacquisition(early.copy(obstructions = listOf(physical)), first.decision))

        val moved = early.copy(nodes = early.nodes.map {
            when (it.index) {
                15 -> it.copy(bounds = RectBounds(707, 81, 824, 141))
                17 -> it.copy(bounds = RectBounds(730, 93, 785, 130))
                18 -> it.copy(bounds = RectBounds(785, 93, 801, 130), text = "5", isVisibleToUser = true)
                else -> it
            }
        })
        val liveAnchor = moved.nodes[anchor.index]
        assertTrue(proof.confirmsNativePillRelayout(liveAnchor, moved.nodes.filter { it.parentIndex == anchor.index }))
        machine.onActionDispatched(first.sessionId, first.attemptId, first.preferredMethod, false, proof)
        assertEquals(EvaluationResult.NoOp, evaluate(machine, moved, 1120))
        val retry = (evaluate(machine, moved, 1180) as EvaluationResult.ExecuteClick).command
        assertEquals(ClickMethod.NODE_ACTION, retry.preferredMethod)
        assertFalse(retry.allowImmediateGestureFallback)
    }

    @Test
    fun `recorded daily check-in dialog does not match after a foreground entry`() {
        val tree = replay(58)
        assertNull(SkipMatcher.findBestMatch(tree, rules))
        assertEquals(EvaluationResult.NoOp, evaluate(start(tree), tree, 1500))
    }

    private fun start(tree: WindowSnapshot) = SkipStateMachine().apply {
        onWindowStateChanged(tree.packageName, "", true, 1000, windowId = tree.windowId,
            confirmedForegroundEntry = true)
    }

    private fun evaluate(machine: SkipStateMachine, tree: WindowSnapshot, now: Long) =
        machine.evaluateSnapshot(tree, now, masterEnabled = true, observeOnly = false, windowMs = 6000)

    private fun replay(round: Int, suffix: String = ""): WindowSnapshot {
        val path = "/audit-20261128/r${round.toString().padStart(3, '0')}$suffix.tsv"
        val lines = requireNotNull(javaClass.getResourceAsStream(path)).bufferedReader().use { it.readLines() }
        val fields = lines.first().split('\t')
        val viewport = if (fields.size > 6) FreeformViewport(rect(fields[6]), rect(fields[7]),
            fields[8].toInt(), rect(fields[9]), emptyList(), RectBounds(0, 0, fields[0].toInt(), fields[1].toInt()))
        else null
        val nodes = lines.drop(1).map { line ->
            val node = line.split('\t')
            val raw = rect(node[7])
            NodeSnapshot(node[0].toInt(), node[1].toInt(), node[3], node[4], node[5], node[6],
                viewport?.local(raw) ?: raw, node[8] == "1", node[9] == "1", node[10] == "1", node[2].toInt())
        }
        return WindowSnapshot("com.example.audit", "", fields[0].toInt(), fields[1].toInt(), fields[2] == "1",
            nodes, fields[4] == "1", fields[3].toInt(), viewport)
    }

    private fun rect(value: String): RectBounds {
        val (left, top, right, bottom) = value.split(',').map(String::toInt)
        return RectBounds(left, top, right, bottom)
    }
}
