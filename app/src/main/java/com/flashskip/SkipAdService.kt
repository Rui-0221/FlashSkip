package com.flashskip

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.flashskip.a11y.ActionExecutor
import com.flashskip.a11y.NodeExtractor
import com.flashskip.core.EvaluationResult
import com.flashskip.core.PackagePolicy
import com.flashskip.core.RuleIndex
import com.flashskip.core.SkipMatcher
import com.flashskip.core.SkipStateMachine
import com.flashskip.core.SnapshotJson
import com.flashskip.core.VerifyOutcome
import com.flashskip.data.SkipLog
import com.flashskip.data.StatsStore
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FlashSkip Accessibility Service (thin Android adapter over pure-Kotlin [SkipStateMachine] & [SkipMatcher]).
 *
 * Engineering invariants:
 * - FIFO bounded queue for `TYPE_WINDOW_STATE_CHANGED`, coalesced latest-event flag for content scans;
 * - Zero off-window CPU/IPC burn;
 * - Screen-off disarm + user-unlock hot-start re-arm via `BroadcastReceiver`;
 * - `TYPE_WINDOWS_CHANGED` active-package sync (catches OEM gesture home transitions);
 * - Lightweight non-blocking click verification (never crawls full HomeActivity trees);
 * - Low-importance foreground notification keep-alive to prevent OEM background kills.
 */
class SkipAdService : AccessibilityService() {

    companion object {
        @Volatile
        var connected: Boolean = false
            private set

        private const val VERIFY_DELAY_MS = 250L
        private const val DIAG_THROTTLE_MS = 2500L
        private const val CHANNEL_ID = "flashskip_keepalive"
        private const val NOTIF_ID = 1001
    }

    private data class WindowStatePayload(
        val pkg: String,
        val cls: String,
        val isFullScreen: Boolean,
        val timestampMs: Long
    )

    private val pendingWindowStates = ConcurrentLinkedQueue<WindowStatePayload>()
    private val pendingWindowCheck = AtomicBoolean(false)
    private val pendingContentScan = AtomicBoolean(false)
    private val drainScheduled = AtomicBoolean(false)

    @Volatile
    private var isArmedFast: Boolean = false

    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null
    private var screenReceiverRegistered = false

    private lateinit var statsStore: StatsStore
    private lateinit var stateMachine: SkipStateMachine
    private val ruleIndex = RuleIndex.defaultBuiltin()
    private var lastDiagDumpAt = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val act = intent?.action ?: return
            val w = worker ?: return
            when (act) {
                Intent.ACTION_SCREEN_OFF -> {
                    w.post {
                        stateMachine.onScreenOff()
                        isArmedFast = false
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    w.post {
                        val root = try {
                            rootInActiveWindow
                        } catch (_: Exception) {
                            null
                        }
                        val fgPkg = root?.packageName?.toString().orEmpty()
                        if (root != null) safeRecycle(root)
                        stateMachine.onUserPresent(
                            foregroundPkg = fgPkg,
                            nowMs = SystemClock.elapsedRealtime(),
                            userDisabledPackages = statsStore.getUserDisabledPackages(),
                            selfTestPackage = packageName
                        )
                        isArmedFast = stateMachine.armed
                        if (isArmedFast) {
                            pendingContentScan.set(true)
                            scheduleDrain(w)
                        }
                    }
                }
            }
        }
    }

    private val drainRunnable = Runnable {
        drainScheduled.set(false)
        try {
            processPendingOnWorker()
        } catch (e: Exception) {
            SkipLog.log("ERROR worker: ${e.message}")
        }
        if (pendingWindowStates.isNotEmpty() || pendingWindowCheck.get() || pendingContentScan.get()) {
            scheduleDrain()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        statsStore = StatsStore(this)
        stateMachine = SkipStateMachine(ruleIndex = ruleIndex)

        val thread = HandlerThread("SkipAdWorker").apply { start() }
        workerThread = thread
        worker = Handler(thread.looper)
        connected = true
        isArmedFast = false

        registerScreenReceiver()
        startForegroundKeepAlive()

        SkipLog.log(
            "SERVICE connected v0.7 (window=${statsStore.windowMs}ms, cooldown=${SkipStateMachine.DEFAULT_COOLDOWN_MS}ms, maxAttempts=${SkipStateMachine.DEFAULT_MAX_ATTEMPTS})"
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val w = worker ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString().orEmpty()
                val cls = event.className?.toString().orEmpty()
                val full = event.isFullScreen
                if (pendingWindowStates.size < 32) {
                    pendingWindowStates.add(
                        WindowStatePayload(pkg, cls, full, SystemClock.elapsedRealtime())
                    )
                }
                pendingContentScan.set(true)
                scheduleDrain(w)
            }

            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (!statsStore.masterEnabled) return
                pendingWindowCheck.set(true)
                if (isArmedFast) {
                    pendingContentScan.set(true)
                }
                scheduleDrain(w)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (!isArmedFast || !statsStore.masterEnabled) return
                pendingContentScan.set(true)
                scheduleDrain(w)
            }
        }
    }

    private fun scheduleDrain(targetWorker: Handler? = worker) {
        val w = targetWorker ?: return
        if (drainScheduled.compareAndSet(false, true)) {
            w.post(drainRunnable)
        }
    }

    private fun processPendingOnWorker() {
        val shouldCheckWindows = pendingWindowCheck.getAndSet(false)
        var shouldScan = pendingContentScan.getAndSet(false)
        val disabledPkgs = statsStore.getUserDisabledPackages()
        var hadStateChange = false

        while (true) {
            val winEvent = pendingWindowStates.poll() ?: break
            hadStateChange = true
            val verify = stateMachine.onWindowStateChanged(
                pkg = winEvent.pkg,
                className = winEvent.cls,
                isFullScreen = winEvent.isFullScreen,
                nowMs = winEvent.timestampMs,
                userDisabledPackages = disabledPkgs,
                selfTestPackage = packageName,
                selfTestActivity = TestSplashActivity::class.java.name
            )
            if (verify == VerifyOutcome.VERIFIED_CLOSED) {
                val latency = (stateMachine.lastClickAtMs - stateMachine.launchAtMs).coerceAtLeast(0L)
                statsStore.recordVerifiedSkip(latency)
                SkipLog.log("VERIFIED pkg=${winEvent.pkg} (window changed, latency=${latency}ms)")
            }
            isArmedFast = stateMachine.armed
        }

        // Catch OEM gesture-home / window-reorder package switches that omit TYPE_WINDOW_STATE_CHANGED
        if (shouldCheckWindows && !hadStateChange) {
            val activeRoot = try {
                rootInActiveWindow
            } catch (_: Exception) {
                null
            }
            val fgPkg = activeRoot?.packageName?.toString().orEmpty()
            if (activeRoot != null) safeRecycle(activeRoot)
            if (fgPkg.isNotEmpty() && fgPkg != stateMachine.activePackage) {
                stateMachine.onWindowStateChanged(
                    pkg = fgPkg,
                    className = "",
                    isFullScreen = true,
                    nowMs = SystemClock.elapsedRealtime(),
                    userDisabledPackages = disabledPkgs,
                    selfTestPackage = packageName,
                    selfTestActivity = TestSplashActivity::class.java.name
                )
                isArmedFast = stateMachine.armed
                if (isArmedFast) shouldScan = true
            }
        }

        if (!shouldScan || !stateMachine.armed || !statsStore.masterEnabled) {
            isArmedFast = stateMachine.armed
            return
        }

        val (screenW, screenH) = getScreenSize()
        val now = SystemClock.elapsedRealtime()
        val windowMs = statsStore.windowMs
        val observeOnly = statsStore.observeOnly

        val candidates = collectCandidateRoots()
        if (candidates.isEmpty()) return

        var handled = false
        for (root in candidates) {
            if (handled) {
                safeRecycle(root)
                continue
            }
            NodeExtractor.extract(root, stateMachine.activeActivity, screenW, screenH).use { tree ->
                val snapshot = tree.snapshot
                if (statsStore.diagNodes && now - lastDiagDumpAt > DIAG_THROTTLE_MS &&
                    !PackagePolicy.isExcludedPackage(snapshot.packageName, disabledPkgs)
                ) {
                    lastDiagDumpAt = now
                    val json = SnapshotJson.toJson(snapshot)
                    val file = SkipLog.writeSnapshotFile(snapshot.packageName, json)
                    SkipLog.log("SNAPSHOT pkg=${snapshot.packageName} nodes=${snapshot.nodes.size} saved=${file?.name ?: "err"}")
                }

                when (val eval = stateMachine.evaluateSnapshot(
                    snapshot = snapshot,
                    nowMs = now,
                    masterEnabled = statsStore.masterEnabled,
                    observeOnly = observeOnly,
                    windowMs = windowMs,
                    userDisabledPackages = disabledPkgs,
                    selfTestPackage = packageName,
                    selfTestActivity = TestSplashActivity::class.java.name
                )) {
                    EvaluationResult.NoOp -> Unit
                    is EvaluationResult.OutOfWindow -> {
                        isArmedFast = false
                        SkipLog.log("WINDOW_EXPIRED pkg=${snapshot.packageName} after=${eval.sinceLaunchMs}ms (disarmed)")
                        handled = true
                    }
                    is EvaluationResult.ObserveDetected -> {
                        val d = eval.decision
                        SkipLog.log(
                            "DETECT pkg=${eval.packageName} text='${d.matchedText}' via=${d.matchedVia} shake=${d.isShakeAd} gestureOnly=${d.forceGestureOnly} sinceLaunch=${eval.sinceLaunchMs}ms (observe)"
                        )
                        handled = true
                    }
                    is EvaluationResult.ExecuteClick -> {
                        val cmd = eval.command
                        val w = worker ?: return@use
                        val exec = ActionExecutor.execute(
                            service = this,
                            tree = tree,
                            command = cmd,
                            screenWidth = screenW,
                            screenHeight = screenH,
                            callbackHandler = w,
                            onGestureOutcome = { sid, aid, completed ->
                                if (!completed) {
                                    SkipLog.log("GESTURE_RETRY_NEEDED session=$sid attempt=$aid")
                                }
                            }
                        )
                        stateMachine.onActionDispatched(cmd.sessionId, cmd.attemptId, exec.actualMethod, exec.dispatched)
                        val d = cmd.decision
                        if (exec.dispatched) {
                            val b = d.targetBounds
                            val clickAct = stateMachine.activeActivity
                            SkipLog.log(
                                "${exec.actualMethod.name} pkg=${cmd.packageName} text='${d.matchedText}' via=${d.matchedVia} shake=${d.isShakeAd} b=${b.left},${b.top},${b.right},${b.bottom} scr=${screenW}x${screenH} sinceLaunch=${cmd.sinceLaunchMs}ms attempt=${cmd.attemptNumber}"
                            )
                            w.postDelayed(
                                {
                                    verifyAttempt(
                                        sessionId = cmd.sessionId,
                                        attemptId = cmd.attemptId,
                                        clickedPkg = cmd.packageName,
                                        clickActivity = clickAct,
                                        sinceLaunchMs = cmd.sinceLaunchMs,
                                        matchedText = d.matchedText,
                                        matchedVia = d.matchedVia
                                    )
                                },
                                VERIFY_DELAY_MS
                            )
                        } else {
                            SkipLog.log("CLICK_FAILED pkg=${cmd.packageName} text='${d.matchedText}' method=${exec.actualMethod.name}")
                        }
                        handled = true
                    }
                }
            }
        }
        isArmedFast = stateMachine.armed
    }

    /**
     * Lightweight verification that avoids walking 2000-node HomeActivity trees during heavy
     * post-splash app startup (preventing multi-second Binder IPC stalls on worker thread).
     */
    private fun verifyAttempt(
        sessionId: Long,
        attemptId: Long,
        clickedPkg: String,
        clickActivity: String,
        sinceLaunchMs: Long,
        matchedText: String,
        matchedVia: String
    ) {
        if (stateMachine.sessionId != sessionId) return
        if (stateMachine.activePackage != clickedPkg || stateMachine.activeActivity != clickActivity) {
            val outcome = stateMachine.onVerifyCheck(
                sessionId = sessionId,
                attemptId = attemptId,
                clickedPkg = clickedPkg,
                currentForegroundPkg = stateMachine.activePackage,
                stillHasSkipTarget = false
            )
            isArmedFast = stateMachine.armed
            if (outcome == VerifyOutcome.VERIFIED_CLOSED) {
                statsStore.recordVerifiedSkip(sinceLaunchMs)
                SkipLog.log("VERIFIED pkg=$clickedPkg (activity transitioned, latency=${sinceLaunchMs}ms)")
            }
            return
        }

        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        }

        if (root == null) {
            val outcome = stateMachine.onVerifyCheck(
                sessionId = sessionId,
                attemptId = attemptId,
                clickedPkg = clickedPkg,
                currentForegroundPkg = null,
                stillHasSkipTarget = false
            )
            isArmedFast = stateMachine.armed
            if (outcome == VerifyOutcome.VERIFIED_CLOSED) {
                statsStore.recordVerifiedSkip(sinceLaunchMs)
                SkipLog.log("VERIFIED pkg=$clickedPkg (transition, latency=${sinceLaunchMs}ms)")
            }
            return
        }

        val currentPkg = root.packageName?.toString().orEmpty()
        if (currentPkg != clickedPkg) {
            safeRecycle(root)
            val outcome = stateMachine.onVerifyCheck(
                sessionId = sessionId,
                attemptId = attemptId,
                clickedPkg = clickedPkg,
                currentForegroundPkg = currentPkg,
                stillHasSkipTarget = false
            )
            isArmedFast = stateMachine.armed
            if (outcome == VerifyOutcome.VERIFIED_CLOSED) {
                statsStore.recordVerifiedSkip(sinceLaunchMs)
                SkipLog.log("VERIFIED pkg=$clickedPkg (closed, latency=${sinceLaunchMs}ms)")
            }
            return
        }

        val stillPresent = isTargetStillVisibleFast(root, matchedText, matchedVia)
        val outcome = stateMachine.onVerifyCheck(
            sessionId = sessionId,
            attemptId = attemptId,
            clickedPkg = clickedPkg,
            currentForegroundPkg = currentPkg,
            stillHasSkipTarget = stillPresent
        )
        isArmedFast = stateMachine.armed
        when (outcome) {
            VerifyOutcome.VERIFIED_CLOSED -> {
                statsStore.recordVerifiedSkip(sinceLaunchMs)
                SkipLog.log("VERIFIED pkg=$clickedPkg (closed, latency=${sinceLaunchMs}ms)")
            }
            VerifyOutcome.STILL_PRESENT -> {
                SkipLog.log("RETRY_ESCALATE pkg=$clickedPkg (still present, next attempt uses GESTURE)")
                val retryDelay = (SkipStateMachine.DEFAULT_COOLDOWN_MS - VERIFY_DELAY_MS + 30L).coerceAtLeast(60L)
                worker?.postDelayed({
                    pendingContentScan.set(true)
                    scheduleDrain()
                }, retryDelay)
            }
            VerifyOutcome.IGNORED_STALE -> Unit
        }
    }

    private fun isTargetStillVisibleFast(
        root: AccessibilityNodeInfo,
        matchedText: String,
        matchedVia: String
    ): Boolean {
        if (matchedVia == "text" && matchedText.isNotBlank()) {
            val found = try {
                root.findAccessibilityNodeInfosByText(matchedText)
            } catch (_: Exception) {
                null
            }
            if (found != null) {
                var visible = false
                for (n in found) {
                    if (n.isVisibleToUser) visible = true
                    safeRecycle(n)
                }
                safeRecycle(root)
                return visible
            }
        } else if (matchedText.contains(":id/")) {
            val found = try {
                root.findAccessibilityNodeInfosByViewId(matchedText)
            } catch (_: Exception) {
                null
            }
            if (found != null) {
                var visible = false
                for (n in found) {
                    if (n.isVisibleToUser) visible = true
                    safeRecycle(n)
                }
                safeRecycle(root)
                return visible
            }
        }

        val (screenW, screenH) = getScreenSize()
        return NodeExtractor.extract(root, stateMachine.activeActivity, screenW, screenH, maxNodes = 120).use { tree ->
            SkipMatcher.findBestMatch(tree.snapshot, ruleIndex) != null
        }
    }

    private fun collectCandidateRoots(): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>(3)
        val active = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        }
        val activeWindowId = active?.windowId ?: -1
        if (active != null) {
            result.add(active)
        }
        val winList = try {
            windows
        } catch (_: Exception) {
            null
        }
        if (winList != null) {
            for (win in winList) {
                if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                if (win.id == activeWindowId) continue
                val r = try {
                    win.root
                } catch (_: Exception) {
                    null
                }
                if (r != null) {
                    result.add(r)
                }
            }
        }
        return result
    }

    private fun getScreenSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return 1080 to 2400
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width().coerceAtLeast(1) to b.height().coerceAtLeast(1)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels.coerceAtLeast(1) to dm.heightPixels.coerceAtLeast(1)
        }
    }

    private fun startForegroundKeepAlive() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FlashSkip 后台守护",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持开屏跳过服务在后台不被系统清理"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)

            val openIntent = Intent(this, MainActivity::class.java)
            val pi = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_flash)
                .setContentTitle("FlashSkip 闪跳运行中")
                .setContentText("正在守护开屏广告自动跳过（点击打开面板）")
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(pi)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (t: Throwable) {
            SkipLog.log("KEEPALIVE_NOTIF_SKIP: ${t.message}")
        }
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        screenReceiverRegistered = true
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        screenReceiverRegistered = false
    }

    private fun safeRecycle(node: AccessibilityNodeInfo) {
        try {
            @Suppress("DEPRECATION")
            node.recycle()
        } catch (_: Exception) {
        }
    }

    override fun onInterrupt() {}

    private fun stopWorker() {
        worker?.removeCallbacksAndMessages(null)
        workerThread?.quitSafely()
        worker = null
        workerThread = null
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        isArmedFast = false
        unregisterScreenReceiver()
        stopWorker()
        SkipLog.log("SERVICE unbind")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        isArmedFast = false
        unregisterScreenReceiver()
        stopWorker()
        super.onDestroy()
    }
}
