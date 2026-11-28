package com.flashskip.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipStateMachineTest {

    private val screenW = 1080
    private val screenH = 2400
    private val topRight = RectBounds(860, 120, 1040, 210)

    private fun splashWindow(pkg: String = "com.demo.news", activity: String = "com.demo.news.SplashActivity"): WindowSnapshot {
        return WindowSnapshot(
            packageName = pkg,
            activityName = activity,
            screenWidth = screenW,
            screenHeight = screenH,
            isFullScreen = true,
            windowId = 3,
            nodes = listOf(
                NodeSnapshot(
                    index = 0,
                    parentIndex = -1,
                    className = "android.widget.Button",
                    viewId = "$pkg:id/skip",
                    text = "跳过 3s",
                    contentDescription = "",
                    bounds = topRight,
                    isVisibleToUser = true,
                    isClickable = true,
                    isEnabled = true,
                    depth = 0
                )
            )
        )
    }

    @Test
    fun `transient overlays and non-fullscreen dialogs do NOT reset window or attempts`() {
        val fsm = SkipStateMachine()
        fsm.onWindowStateChanged("com.demo.news", "com.demo.news.MainActivity", true, nowMs = 1000L)
        assertTrue(fsm.armed)

        // First click uses attempt 1
        val r1 = fsm.evaluateSnapshot(
            splashWindow(), nowMs = 1200L,
            masterEnabled = true, observeOnly = false, windowMs = 6000L
        )
        assertTrue(r1 is EvaluationResult.ExecuteClick)
        assertEquals(1, fsm.attempts)

        // IME keyboard pops up -> must NOT reset launchAt or attempts
        fsm.onWindowStateChanged("com.baidu.input", "android.inputmethodservice.SoftInputWindow", false, nowMs = 5000L)
        assertEquals("com.demo.news", fsm.activePackage)
        assertEquals(1000L, fsm.launchAtMs)
        assertEquals(1, fsm.attempts)

        // AlertDialog inside same app -> must NOT reset launchAt or attempts
        fsm.onWindowStateChanged("com.demo.news", "androidx.appcompat.app.AlertDialog", false, nowMs = 5500L)
        assertEquals(1000L, fsm.launchAtMs)
        assertEquals(1, fsm.attempts)

        // After 6s from original 1000L launch -> out of window, must NOT allow click at 7500L
        val rLate = fsm.evaluateSnapshot(
            splashWindow(), nowMs = 7500L,
            masterEnabled = true, observeOnly = false, windowMs = 6000L
        )
        assertTrue(rLate is EvaluationResult.OutOfWindow)
        assertFalse(fsm.armed)
    }

    @Test
    fun `fake-success ACTION_CLICK escalates to GESTURE on second attempt`() {
        val fsm = SkipStateMachine()
        fsm.onWindowStateChanged("com.demo.news", "com.demo.news.SplashActivity", true, nowMs = 1000L)

        // Attempt 1 tries NODE_ACTION
        val res1 = fsm.evaluateSnapshot(
            splashWindow(), nowMs = 1200L,
            masterEnabled = true, observeOnly = false, windowMs = 6000L
        ) as EvaluationResult.ExecuteClick
        assertEquals(ClickMethod.NODE_ACTION, res1.command.preferredMethod)
        fsm.onActionDispatched(res1.command.sessionId, res1.command.attemptId, ClickMethod.NODE_ACTION, ok = true)

        // Delayed verify finds skip button is STILL present (fake-success!)
        val verify1 = fsm.finishVerification(
            sessionId = res1.command.sessionId,
            attemptId = res1.command.attemptId,
            outcome = VerifyOutcome.STILL_PRESENT,
            retryTarget = TargetEvidence(splashWindow(), res1.command.decision)
        )
        assertEquals(VerifyOutcome.STILL_PRESENT, verify1)
        assertTrue(fsm.armed)

        // A verified retry of the same target can run before the normal cooldown.
        val res2 = fsm.evaluateSnapshot(
            splashWindow(), nowMs = 1285L,
            masterEnabled = true, observeOnly = false, windowMs = 6000L
        ) as EvaluationResult.ExecuteClick
        assertEquals(ClickMethod.GESTURE, res2.command.preferredMethod)
    }

    @Test
    fun `cross-package navigation clears pending attempt and stale verification leaves next session armed`() {
        val fsm = SkipStateMachine()
        fsm.onWindowStateChanged("com.app.one", "com.app.one.SplashActivity", true, nowMs = 1000L)
        val cmd = (fsm.evaluateSnapshot(
            splashWindow("com.app.one"), nowMs = 1100L,
            masterEnabled = true, observeOnly = false, windowMs = 6000L
        ) as EvaluationResult.ExecuteClick).command

        // User rapidly switches to com.app.two before the 400ms verify runs
        val navigation = fsm.onWindowStateChanged("com.app.two", "com.app.two.SplashActivity", true, nowMs = 1200L)
        assertEquals(VerifyOutcome.NAVIGATED, navigation)
        assertFalse(fsm.armed)
        assertEquals("com.app.two", fsm.activePackage)
        fsm.onWindowStateChanged("com.app.three", "com.app.three.SplashActivity", true, nowMs = 1250L)

        // Stale verify callback for com.app.one arrives after com.app.two started a new session
        val outcome = fsm.finishVerification(
            sessionId = cmd.sessionId,
            attemptId = cmd.attemptId,
            outcome = VerifyOutcome.VERIFIED_CLOSED
        )
        assertEquals(VerifyOutcome.IGNORED_STALE, outcome)
        // Crucial: com.app.two MUST remain armed!
        assertTrue(fsm.armed)
        assertEquals("com.app.three", fsm.activePackage)
    }

    @Test
    fun `same-package landing activity is navigation without verified closure`() {
        val fsm = SkipStateMachine()
        fsm.onWindowStateChanged("com.app.one", "com.app.one.SplashActivity", true, nowMs = 1000L)
        fsm.evaluateSnapshot(
            splashWindow("com.app.one"), nowMs = 1100L,
            masterEnabled = true, observeOnly = false, windowMs = 6000L
        ) as EvaluationResult.ExecuteClick

        val outcome = fsm.onWindowStateChanged("com.app.one", "com.app.one.LandingActivity", true, nowMs = 1200L)
        assertEquals(VerifyOutcome.NAVIGATED, outcome)
        assertFalse(fsm.armed)
    }

    @Test
    fun `verified foreground return rearms a previously disarmed Activity with the same window ID`() {
        val window = splashWindow()
        val id = requireNotNull(window.windowId)
        val machine = SkipStateMachine()
        machine.onWindowStateChanged(window.packageName, window.activityName, true, 1000L, windowId = id)
        machine.onNavigationObserved(ForegroundWindow(window.packageName, id))
        val previousSession = machine.sessionId
        machine.onWindowStateChanged(
            window.packageName, window.activityName, true, 32000L, windowId = id, confirmedFullscreenActivity = true
        )
        assertFalse(machine.armed)
        assertEquals(previousSession, machine.sessionId)
        machine.onWindowStateChanged(
            window.packageName, window.activityName, true, 32050L, windowId = id, confirmedForegroundEntry = true
        )
        assertTrue(machine.armed)
        assertEquals(32050L, machine.launchAtMs)
        assertTrue(machine.evaluateSnapshot(window, 32500L, true, false, 6000L) is EvaluationResult.ExecuteClick)
    }

    @Test
    fun `same package resume window changes keep the timer and reject stale nodes`() {
        val initial = splashWindow()
        val state = SkipStateMachine()
        state.onWindowStateChanged(initial.packageName, initial.activityName, true, 1000L,
            windowId = initial.windowId, confirmedForegroundEntry = true)
        val session = state.sessionId
        assertTrue(state.moveWithinResume(ForegroundWindow(initial.packageName, 4), 2200L, 6000L))
        assertEquals(session, state.sessionId)
        assertEquals(1000L, state.launchAtMs)
        assertEquals(EvaluationResult.NoOp, state.evaluateSnapshot(initial, 2500L, true, false, 6000L))
        assertTrue(state.evaluateSnapshot(initial.copy(windowId = 4), 2600L, true, false, 6000L)
            is EvaluationResult.ExecuteClick)
        assertFalse(state.moveWithinResume(ForegroundWindow(initial.packageName, 5), 2700L, 6000L))
    }

    @Test
    fun `foreground return still excludes sensitive packages and overlays`() {
        val machine = SkipStateMachine()
        val window = splashWindow()
        machine.onWindowStateChanged(window.packageName, window.activityName, true, 1000L)
        val session = machine.sessionId
        machine.onWindowStateChanged("com.android.systemui", "", true, 2000L, confirmedForegroundEntry = true)
        assertEquals(session, machine.sessionId)
        machine.onWindowStateChanged("com.tencent.mm", "", true, 3000L, confirmedForegroundEntry = true)
        assertFalse(machine.armed)
    }

    @Test
    fun `unobserved attempt disarms and prevents an unverified retry`() {
        val fsm = SkipStateMachine()
        fsm.onWindowStateChanged("com.demo.news", "com.demo.news.SplashActivity", true, nowMs = 1000L)
        val cmd = (fsm.evaluateSnapshot(splashWindow(), 1100L, true, false, 6000L)
            as EvaluationResult.ExecuteClick).command
        assertEquals(
            VerifyOutcome.UNOBSERVED,
            fsm.finishVerification(cmd.sessionId, cmd.attemptId, VerifyOutcome.UNOBSERVED)
        )
        assertFalse(fsm.armed)
        assertEquals(EvaluationResult.NoOp, fsm.evaluateSnapshot(splashWindow(), 1600L, true, false, 6000L))
    }

    @Test
    fun `a missing native target before input can be reacquired only once`() {
        val initial = splashWindow().let { snapshot ->
            snapshot.copy(nodes = listOf(snapshot.nodes.single().copy(text = "3 | 跳过")))
        }
        val fsm = SkipStateMachine()
        fsm.onWindowStateChanged(initial.packageName, initial.activityName, true, 1000L)
        val first = (fsm.evaluateSnapshot(initial, 1100L, true, false, 6000L) as EvaluationResult.ExecuteClick).command
        val evidence = TargetEvidence(initial, first.decision)
        assertEquals(ClickMethod.GESTURE, first.preferredMethod)
        fsm.onActionDispatched(first.sessionId, first.attemptId, ClickMethod.GESTURE, false, evidence)

        val fresh = initial.copy(nodes = listOf(initial.nodes.single().copy(text = "2 | 跳过")))
        val retry = (fsm.evaluateSnapshot(fresh, 1120L, true, false, 6000L) as EvaluationResult.ExecuteClick).command
        assertEquals(evidence, retry.retryTarget)
        assertEquals(2, retry.attemptNumber)
        fsm.onActionDispatched(retry.sessionId, retry.attemptId, ClickMethod.GESTURE, false, evidence)
        assertFalse(fsm.armed)
        assertEquals(EvaluationResult.NoOp, fsm.evaluateSnapshot(fresh, 1180L, true, false, 6000L))
    }

    @Test
    fun `split countdown can reacquire its native parent after refresh rejects an early layout`() {
        val sample = splashWindow()
        val template = sample.nodes.single()
        val root = template.copy(index = 0, parentIndex = -1, depth = 0, text = "", viewId = "",
            bounds = RectBounds(0, 0, screenW, screenH), isClickable = false)
        val anchor = template.copy(index = 1, parentIndex = 0, depth = 1, text = "",
            viewId = "test:id/closeContainer", bounds = RectBounds(930, 112, 1080, 188))
        val label = template.copy(index = 2, parentIndex = 1, depth = 2, viewId = "", text = "跳过",
            bounds = RectBounds(970, 128, 1040, 173), isClickable = false)
        val marker = label.copy(index = 3, parentIndex = 0, depth = 1, text = "广告",
            bounds = RectBounds(50, 1800, 100, 1850))
        val initial = sample.copy(nodes = listOf(root, anchor, label, marker))
        val machine = SkipStateMachine()
        machine.onWindowStateChanged(initial.packageName, initial.activityName, true, 1000L)
        val first = (machine.evaluateSnapshot(initial, 1100L, true, false, 6000L) as EvaluationResult.ExecuteClick).command
        assertEquals(ClickMethod.NODE_ACTION, first.preferredMethod)
        val evidence = requireNotNull(TargetEvidence.forNativeReacquisition(initial, first.decision))
        machine.onActionDispatched(first.sessionId, first.attemptId, ClickMethod.NODE_ACTION, false, evidence)

        val currentLabel = label.copy(bounds = RectBounds(955, 128, 1025, 173))
        val countdown = label.copy(index = 4, text = "4", bounds = RectBounds(1025, 128, 1045, 173))
        val movedAnchor = anchor.copy(bounds = RectBounds(908, 112, 1080, 188))
        val current = initial.copy(nodes = listOf(root, movedAnchor, currentLabel, marker, countdown))
        val rules = RuleIndex.defaultBuiltin()
        val decision = requireNotNull(SkipMatcher.findBestMatch(current, rules))
        assertTrue(decision.forceGestureOnly)
        assertFalse(evidence.allowsRetry(current.copy(complete = false), currentLabel, decision))
        assertFalse(evidence.allowsRetry(current.copy(windowId = 9), currentLabel, decision))
        assertFalse(evidence.allowsRetry(current.copy(nodes = current.nodes + movedAnchor.copy(index = 5)), currentLabel, decision))
        assertFalse(evidence.allowsRetry(current.copy(nodes = current.nodes.map { if (it == marker) it.copy(text = "") else it }), currentLabel, decision))
        assertFalse(evidence.allowsRetry(current.copy(obstructions = listOf(movedAnchor.bounds)), currentLabel, decision))
        assertEquals(EvaluationResult.NoOp, machine.evaluateSnapshot(current, 1120L, true, false, 6000L))
        assertEquals(EvaluationResult.NoOp, machine.evaluateSnapshot(current, 1150L, true, false, 6000L))
        val retry = (machine.evaluateSnapshot(current, 1180L, true, false, 6000L) as EvaluationResult.ExecuteClick).command
        assertEquals(ClickMethod.NODE_ACTION, retry.preferredMethod)
        assertFalse(retry.allowImmediateGestureFallback)
        machine.onActionDispatched(retry.sessionId, retry.attemptId, ClickMethod.NODE_ACTION, false, evidence)
        assertFalse(machine.armed)
    }

    @Test
    fun `visible ad marker and incomplete absence are different from confirmed absence`() {
        val target = splashWindow().nodes.single().copy(index = 1, parentIndex = 0, depth = 1)
        val root = target.copy(index = 0, parentIndex = -1, className = "android.widget.FrameLayout",
            viewId = "", text = "", bounds = RectBounds(0, 0, screenW, screenH), depth = 0)
        val marker = target.copy(index = 2, viewId = "", text = "广告", bounds = RectBounds(30, 30, 100, 100))
        val initial = splashWindow().copy(nodes = listOf(root, target, marker))
        val decision = requireNotNull(SkipMatcher.findBestMatch(initial, RuleIndex.defaultBuiltin()))
        val evidence = TargetEvidence(initial, decision)
        val newCountdown = target.copy(text = "跳过 2s")
        assertEquals(TargetObservation.TARGET_PRESENT, evidence.observe(initial.copy(nodes = listOf(root, newCountdown))))
        assertEquals(TargetObservation.AD_PRESENT, evidence.observe(initial.copy(nodes = listOf(root, marker))))
        assertEquals(TargetObservation.UNOBSERVED, evidence.observe(initial.copy(nodes = listOf(root), complete = false)))
        assertEquals(TargetObservation.ABSENT, evidence.observe(initial.copy(nodes = listOf(root))))
    }

    @Test
    fun `fast retry rejects replacements and same-package window switches`() {
        val initial = splashWindow()
        val original = initial.nodes.single()
        val unsafeSnapshots = listOf(
            initial.copy(nodes = listOf(original.copy(viewId = "com.demo.news:id/replacement"))),
            initial.copy(nodes = listOf(original.copy(bounds = original.bounds.copy(left = original.bounds.left + 5)))),
            initial.copy(windowId = 4),
            initial.copy(windowId = null)
        )
        for (snapshot in unsafeSnapshots) {
            val fsm = SkipStateMachine()
            fsm.onWindowStateChanged(initial.packageName, initial.activityName, true, 1000L)
            val attempt = (fsm.evaluateSnapshot(initial, 1100L, true, false, 6000L) as EvaluationResult.ExecuteClick).command
            val evidence = TargetEvidence(initial, attempt.decision)
            fsm.finishVerification(attempt.sessionId, attempt.attemptId, VerifyOutcome.STILL_PRESENT, evidence)
            assertEquals(EvaluationResult.NoOp, fsm.evaluateSnapshot(snapshot, 1200L, true, false, 6000L))
            assertFalse(fsm.armed)
            assertEquals(1, fsm.attempts)
        }
    }

    @Test
    fun `widget state from own lab window retains the armed activity and real panel disarms`() {
        val app = "com.flashskip"
        val lab = "$app.TestSplashActivity"
        val fsm = SkipStateMachine()
        fsm.onWindowStateChanged(app, lab, true, 1000L, selfTestPackage = app, selfTestActivity = lab)
        fsm.onWindowStateChanged(
            app, "android.widget.FrameLayout", true, 1010L, selfTestPackage = app, selfTestActivity = lab
        )
        assertTrue(fsm.armed)
        assertEquals(lab, fsm.activeActivity)
        assertEquals(1000L, fsm.launchAtMs)
        fsm.onWindowStateChanged(app, "$app.MainActivity", true, 1100L, selfTestPackage = app, selfTestActivity = lab)
        assertFalse(fsm.armed)
    }

    @Test
    fun `switching to excluded banking app disarms machine immediately`() {
        val fsm = SkipStateMachine()
        fsm.onWindowStateChanged("com.demo.news", "com.demo.news.SplashActivity", true, nowMs = 1000L)
        assertTrue(fsm.armed)

        fsm.onWindowStateChanged("com.eg.android.AlipayGphone", "com.alipay.mobile.quinox.LauncherActivity", true, nowMs = 1500L)
        assertFalse(fsm.armed)
    }

    @Test
    fun `same-package Activity transition arms even if OEM reports isFullScreen false`() {
        val fsm = SkipStateMachine()
        fsm.onWindowStateChanged(
            pkg = "com.flashskip",
            className = "com.flashskip.MainActivity",
            isFullScreen = false,
            nowMs = 1000L,
            selfTestPackage = "com.flashskip",
            selfTestActivity = "com.flashskip.TestSplashActivity"
        )
        assertFalse(fsm.armed)

        fsm.onWindowStateChanged(
            pkg = "com.flashskip",
            className = "com.flashskip.TestSplashActivity",
            isFullScreen = false,
            nowMs = 1200L,
            selfTestPackage = "com.flashskip",
            selfTestActivity = "com.flashskip.TestSplashActivity"
        )
        assertTrue(fsm.armed)
    }

    @Test
    fun `observe mode detects without incrementing attempts or arming clicks`() {
        val fsm = SkipStateMachine()
        fsm.onWindowStateChanged("com.demo.news", "com.demo.news.SplashActivity", true, nowMs = 1000L)

        val res = fsm.evaluateSnapshot(
            splashWindow(), nowMs = 1300L,
            masterEnabled = true, observeOnly = true, windowMs = 6000L
        )
        assertTrue(res is EvaluationResult.ObserveDetected)
        assertEquals(0, fsm.attempts)
        assertTrue(fsm.armed)
    }

    @Test
    fun `shake ad uses GESTURE directly on first attempt to avoid fake-success delay`() {
        val fsm = SkipStateMachine()
        fsm.onWindowStateChanged("com.demo.news", "com.demo.news.SplashActivity", true, nowMs = 1000L)

        val shakeWin = WindowSnapshot(
            packageName = "com.demo.news",
            activityName = "com.demo.news.SplashActivity",
            screenWidth = screenW,
            screenHeight = screenH,
            isFullScreen = true,
            nodes = listOf(
                NodeSnapshot(
                    index = 0, parentIndex = -1, className = "android.widget.FrameLayout",
                    viewId = "", text = "", contentDescription = "",
                    bounds = RectBounds(0, 0, screenW, screenH),
                    isVisibleToUser = true, isClickable = false, isEnabled = true, depth = 0
                ),
                NodeSnapshot(
                    index = 1, parentIndex = 0, className = "android.widget.TextView",
                    viewId = "", text = "摇一摇跳转详情", contentDescription = "",
                    bounds = RectBounds(300, 1200, 780, 1300),
                    isVisibleToUser = true, isClickable = false, isEnabled = true, depth = 1
                ),
                NodeSnapshot(
                    index = 2, parentIndex = 0, className = "android.widget.Button",
                    viewId = "com.demo.news:id/skip", text = "跳过 5", contentDescription = "",
                    bounds = topRight,
                    isVisibleToUser = true, isClickable = true, isEnabled = true, depth = 1
                )
            )
        )

        val res = fsm.evaluateSnapshot(
            shakeWin, nowMs = 1200L,
            masterEnabled = true, observeOnly = false, windowMs = 6000L
        ) as EvaluationResult.ExecuteClick
        assertTrue(res.command.decision.isShakeAd)
        assertEquals(ClickMethod.GESTURE, res.command.preferredMethod)
    }

    @Test
    fun `splash activity allows 10s window while non-splash expires at 6s`() {
        val fsm = SkipStateMachine()
        fsm.onWindowStateChanged("com.demo.news", "com.demo.news.splash.SplashActivity", true, nowMs = 1000L)
        val res = fsm.evaluateSnapshot(
            splashWindow(activity = "com.demo.news.splash.SplashActivity"),
            nowMs = 8500L,
            masterEnabled = true, observeOnly = false, windowMs = 6000L
        )
        assertTrue(res is EvaluationResult.ExecuteClick)
    }

    @Test
    fun `screen off disarms and user unlock re-arms hot-start ad without stale window expiry`() {
        val fsm = SkipStateMachine()
        fsm.onWindowStateChanged("com.demo.news", "com.demo.news.HomeActivity", true, nowMs = 1000L)
        assertTrue(fsm.armed)

        // Screen turns off -> disarms
        fsm.onScreenOff()
        assertFalse(fsm.armed)

        // 6 minutes later (363889ms), user unlocks directly into com.demo.news showing hot-start ad
        fsm.onUserPresent("com.demo.news", nowMs = 364889L)
        assertTrue(fsm.armed)
        assertEquals(364889L, fsm.launchAtMs)

        val hotAd = splashWindow("com.demo.news", "com.demo.news.HomeActivity")
        val res = fsm.evaluateSnapshot(
            hotAd.copy(nodes = hotAd.nodes + hotAd.nodes.single().copy(
                index = 1, text = "广告", viewId = "", bounds = RectBounds(20, 2050, 76, 2110), isClickable = false
            )),
            nowMs = 365100L,
            masterEnabled = true, observeOnly = false, windowMs = 6000L
        )
        assertTrue(res is EvaluationResult.ExecuteClick)

        // Unlocking directly into an excluded banking app must stay disarmed
        fsm.onUserPresent("com.eg.android.AlipayGphone", nowMs = 400000L)
        assertFalse(fsm.armed)
    }

    @Test
    fun `same-class replacement needs a newly verified launch window rather than an overlay`() {
        val fsm = SkipStateMachine()
        val window = splashWindow()
        val pkg = window.packageName
        val cls = window.activityName
        fsm.onWindowStateChanged(pkg, cls, true, 1000, windowId = 3, confirmedFullscreenActivity = true)
        val action = fsm.evaluateSnapshot(window, 1050, true, false, 6000) as EvaluationResult.ExecuteClick
        fsm.finishVerification(action.command.sessionId, action.command.attemptId, VerifyOutcome.VERIFIED_CLOSED)
        fsm.onWindowStateChanged(pkg, cls, true, 1200, windowId = 4)
        assertFalse(fsm.armed)
        fsm.onWindowStateChanged(pkg, cls, true, 1250, windowId = 3, confirmedFullscreenActivity = true)
        assertFalse(fsm.armed)
        fsm.onWindowStateChanged(pkg, cls, true, 1400, windowId = 5, confirmedFullscreenActivity = true)
        assertTrue(fsm.armed)
        assertEquals(1400L, fsm.launchAtMs)
    }

    @Test
    fun `observed destination cannot rearm from a late state of the same foreground window`() {
        val fsm = SkipStateMachine()
        val destination = ForegroundWindow("com.example.ad", 5)
        fsm.onNavigationObserved(destination)
        fsm.onWindowStateChanged(destination.packageName, "com.example.ad.LandingActivity", true, 1000,
            windowId = destination.id, confirmedFullscreenActivity = true)
        assertFalse(fsm.armed)
        fsm.onWindowStateChanged("com.example.other", "com.example.other.MainActivity", true, 1200, windowId = 6)
        assertTrue(fsm.armed)
    }
}
