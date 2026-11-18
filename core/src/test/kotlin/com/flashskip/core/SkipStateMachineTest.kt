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
        val verify1 = fsm.onVerifyCheck(
            sessionId = res1.command.sessionId,
            attemptId = res1.command.attemptId,
            clickedPkg = "com.demo.news",
            currentForegroundPkg = "com.demo.news",
            stillHasSkipTarget = true
        )
        assertEquals(VerifyOutcome.STILL_PRESENT, verify1)
        assertTrue(fsm.armed)

        // Attempt 2 after cooldown MUST escalate directly to GESTURE
        val res2 = fsm.evaluateSnapshot(
            splashWindow(), nowMs = 1900L,
            masterEnabled = true, observeOnly = false, windowMs = 6000L
        ) as EvaluationResult.ExecuteClick
        assertEquals(ClickMethod.GESTURE, res2.command.preferredMethod)
    }

    @Test
    fun `stale verify from previous app does NOT disarm newly opened app`() {
        val fsm = SkipStateMachine()
        fsm.onWindowStateChanged("com.app.one", "com.app.one.SplashActivity", true, nowMs = 1000L)
        val cmd = (fsm.evaluateSnapshot(
            splashWindow("com.app.one"), nowMs = 1100L,
            masterEnabled = true, observeOnly = false, windowMs = 6000L
        ) as EvaluationResult.ExecuteClick).command

        // User rapidly switches to com.app.two before the 400ms verify runs
        fsm.onWindowStateChanged("com.app.two", "com.app.two.SplashActivity", true, nowMs = 1200L)
        assertTrue(fsm.armed)
        assertEquals("com.app.two", fsm.activePackage)

        // Stale verify callback for com.app.one arrives after com.app.two started a new session
        val outcome = fsm.onVerifyCheck(
            sessionId = cmd.sessionId,
            attemptId = cmd.attemptId,
            clickedPkg = "com.app.one",
            currentForegroundPkg = "com.app.two",
            stillHasSkipTarget = false
        )
        assertEquals(VerifyOutcome.IGNORED_STALE, outcome)
        // Crucial: com.app.two MUST remain armed!
        assertTrue(fsm.armed)
        assertEquals("com.app.two", fsm.activePackage)
    }

    @Test
    fun `same-session verify when foreground changed to home closes session cleanly`() {
        val fsm = SkipStateMachine()
        fsm.onWindowStateChanged("com.app.one", "com.app.one.SplashActivity", true, nowMs = 1000L)
        val cmd = (fsm.evaluateSnapshot(
            splashWindow("com.app.one"), nowMs = 1100L,
            masterEnabled = true, observeOnly = false, windowMs = 6000L
        ) as EvaluationResult.ExecuteClick).command

        val outcome = fsm.onVerifyCheck(
            sessionId = cmd.sessionId,
            attemptId = cmd.attemptId,
            clickedPkg = "com.app.one",
            currentForegroundPkg = "com.android.launcher",
            stillHasSkipTarget = false
        )
        assertEquals(VerifyOutcome.VERIFIED_CLOSED, outcome)
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

        val res = fsm.evaluateSnapshot(
            splashWindow("com.demo.news", "com.demo.news.HomeActivity"),
            nowMs = 365100L,
            masterEnabled = true, observeOnly = false, windowMs = 6000L
        )
        assertTrue(res is EvaluationResult.ExecuteClick)

        // Unlocking directly into an excluded banking app must stay disarmed
        fsm.onUserPresent("com.eg.android.AlipayGphone", nowMs = 400000L)
        assertFalse(fsm.armed)
    }
}
