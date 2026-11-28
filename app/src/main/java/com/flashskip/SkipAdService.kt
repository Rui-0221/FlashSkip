package com.flashskip

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.flashskip.a11y.ActionExecutor
import com.flashskip.a11y.SkipTrace
import com.flashskip.a11y.WindowTrace
import com.flashskip.a11y.hasVisibleNode
import com.flashskip.a11y.isFocusedApp
import com.flashskip.a11y.ApplicationWindows
import com.flashskip.a11y.ContentWindowRoot
import com.flashskip.a11y.isFreshGestureRetry
import com.flashskip.a11y.ExtractedTree
import com.flashskip.core.RectBounds
import com.flashskip.a11y.mayChangeForeground
import com.flashskip.a11y.changesWindowGeometry
import com.flashskip.a11y.readFocusedUnmapped
import com.flashskip.a11y.showsReturnTo
import com.flashskip.a11y.withFocusedAppWindow
import com.flashskip.a11y.NodeExtractor
import com.flashskip.core.EvaluationResult
import com.flashskip.core.ForegroundEvents
import com.flashskip.core.ForegroundWindow
import com.flashskip.core.PackagePolicy
import com.flashskip.core.RuleIndex
import com.flashskip.core.SkipMatcher
import com.flashskip.core.SkipStateMachine
import com.flashskip.core.SnapshotJson
import com.flashskip.core.ActionCommand
import com.flashskip.core.ClickMethod
import com.flashskip.core.ClosureCheck
import com.flashskip.core.TargetEvidence
import com.flashskip.core.TargetObservation
import com.flashskip.core.VerifyOutcome
import com.flashskip.data.SkipLog
import com.flashskip.data.StatsStore
import com.flashskip.ui.SkipSuccessFeedback
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * FlashSkip Accessibility Service (thin Android adapter over pure-Kotlin [SkipStateMachine] & [SkipMatcher]).
 *
 * Engineering invariants:
 * - FIFO bounded queue for `TYPE_WINDOW_STATE_CHANGED`, coalesced latest-event flag for content scans;
 * - Zero off-window CPU/IPC burn;
 * - Screen-off disarm + user-unlock hot-start re-arm via `BroadcastReceiver`;
 * - `TYPE_WINDOWS_CHANGED` active-package sync (catches OEM gesture home transitions);
 * - Bounded verification in the original foreground window;
 * - Low-importance foreground notification while the connected service is enabled.
 */
class SkipAdService : AccessibilityService() {

    companion object {
        @Volatile
        private var activeService: SkipAdService? = null
        val connected: Boolean get() = activeService != null
        val foregroundActive: Boolean get() = activeService?.foregroundTypeActive() == true

        // Called on the main thread when the app becomes visible after a restricted background bind.
        fun refreshForegroundProtection() {
            activeService?.updateForegroundProtection()
        }

        private const val FIRST_PROBE_MS = 24L
        private const val PROBE_INTERVAL_MS = 24L
        private const val TARGET_RETRY_MS = 125L
        private const val RELAYOUT_PROBE_MS = 32L
        private const val GESTURE_CALLBACK_TIMEOUT_MS = 750L
        private const val FRESH_VERIFY_BUDGET_MS = 120L
        private const val MAX_VERIFY_NODES = 400
        private const val WINDOW_STATE_MAX_AGE_MS = 400L
        private val MATCH_TREE_PREFETCH = AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST or
            AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE
        private const val STACKED_WINDOW_STATE_MAX_AGE_MS = 1500L
        private const val STARTUP_RECOVERY_MS = 1800L
        private val STARTUP_PROBE_OFFSETS_MS = longArrayOf(0, 60, 160, 340, 650, 950, 1250, 1580)
        private const val DIAG_THROTTLE_MS = 2500L
        private const val EVIDENCE_TAG = "FlashSkipEvidence"
        internal const val NOTIFICATION_CHANNEL_ID = "flashskip_keepalive"
        private const val NOTIF_ID = 1001
        private const val ACTION_PAUSE = "com.flashskip.action.PAUSE_AUTO_SKIP"
    }

    private data class WindowStatePayload(
        val pkg: String,
        val cls: String,
        val isFullScreen: Boolean,
        val timestampMs: Long,
        val windowId: Int
    )

    private data class ScanEvent(
        val pkg: String,
        val windowId: Int,
        val timestampMs: Long,
        val receivedAtMs: Long,
        val admittedDuringStartup: Boolean = false,
        val unarmedContentCheck: Boolean = false,
        val offeredSourceRoot: Boolean = false
    )
    private data class PendingRetry(
        val sessionId: Long,
        val origin: TargetEvidence,
        val event: InputOrigin?
    )

    private data class InputOrigin(val eventAtMs: Long, val receivedAtMs: Long, val windowId: Int, val type: String)

    private data class ObservedAttempt(
        val command: ActionCommand,
        val windowId: Int,
        val evidence: TargetEvidence,
        val method: ClickMethod,
        val detectedAtMs: Long,
        val actionAtMs: Long,
        val inputStartedAtMs: Long?,
        val inputReturnedAtMs: Long?,
        val event: InputOrigin?,
        val matchUs: Long,
        val labTrace: TestSplashActivity.RunTrace? = null,
        val returnWindow: ForegroundWindow? = null,
        val crossWindowClosure: ClosureCheck = evidence.crossWindowClosureCheck(inputStartedAtMs ?: actionAtMs),
        val closure: ClosureCheck = evidence.closureCheck(inputStartedAtMs ?: actionAtMs),
        var lastProbeMs: Long = Long.MIN_VALUE / 2,
        var gestureCompletedAtMs: Long? = null
    ) {
        val eventAtMs: Long? get() = event?.eventAtMs
    }

    private val pendingWindowStates = ConcurrentLinkedQueue<WindowStatePayload>()
    private val windowStates = LinkedHashMap<ForegroundWindow, WindowStatePayload>()
    private val transientWindows = LinkedHashSet<ForegroundWindow>()
    private val pendingForegroundSignal = AtomicBoolean()
    private val pendingWindowCheck = AtomicBoolean(false)
    private val pendingContentScan = AtomicBoolean(false)
    private val scanEvent = AtomicReference<ScanEvent?>()
    private val geometryEvent = AtomicReference<InputOrigin?>()
    private val drainScheduled = AtomicBoolean(false)

    @Volatile
    private var isArmedFast: Boolean = false
    @Volatile
    private var screenSuspended = false
    @Volatile
    private var startupUntilMs = 0L
    private var startupEventNotes = 0
    @Volatile
    private var workEpoch = 0L
    private var workerEpoch = 0L

    private var workerThread: HandlerThread? = null
    @Volatile
    private var worker: Handler? = null
    private var screenReceiverRegistered = false
    private var stopObservingMaster: (() -> Unit)? = null
    private var foregroundRequested = false

    private lateinit var statsStore: StatsStore
    private lateinit var stateMachine: SkipStateMachine
    @Volatile
    private var foregroundEvents = ForegroundEvents()
    private val ruleIndex = RuleIndex.defaultBuiltin()
    private var lastDiagDumpAt = 0L
    private var lastRootLookupNoteAt = 0L
    private var tracedScanSession = -1L
    private var tracedNotificationWindow: ForegroundWindow? = null
    private var tracedNotificationSession = -1L
    private var tracedNotificationCandidate: ForegroundWindow? = null
    private var tracedNotificationCandidateSession = -1L
    private val appWindows by lazy { ApplicationWindows(this) { windows } }
    private val contentWindowRoot = ContentWindowRoot()
    private val windowTrace = WindowTrace("accessibility")
    private var pendingAttempt: ObservedAttempt? = null
    private var pendingRetry: PendingRetry? = null
    private val startupProbe = Runnable {
        if (canScan() && inStartupRecovery()) {
            pendingWindowCheck.set(true)
            scheduleDrain()
        }
    }
    private val windowProbe = Runnable {
        if (canScan()) {
            pendingWindowCheck.set(true)
            scheduleDrain()
        }
    }
    private val relayoutProbe = Runnable {
        if (canScan() && stateMachine.armed) {
            pendingContentScan.set(true)
            scheduleDrain()
        }
    }
    private val verificationRunnable = Runnable {
        try {
            verifyPending()
        } catch (_: Exception) {
            pendingAttempt?.let { completeAttempt(it, VerifyOutcome.UNOBSERVED, "tree_error") }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenSuspended = true
                    resetPendingWork("screen_off")
                }
                Intent.ACTION_SCREEN_ON -> resumeIfUnlocked()
                Intent.ACTION_USER_PRESENT -> resumeAfterScreenOff()
                ACTION_PAUSE -> {
                    statsStore.masterEnabled = false
                    SkipLog.log("SETTING master_enabled=false source=notification")
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
        if (activeService === this) {
            updateForegroundProtection()
            return
        }
        if (worker == null) {
            statsStore = StatsStore(this)
            stateMachine = SkipStateMachine(ruleIndex = ruleIndex)
            val thread = HandlerThread("SkipAdWorker", Process.THREAD_PRIORITY_FOREGROUND).apply { start() }
            workerThread = thread
            worker = Handler(thread.looper)
        }
        screenSuspended = !isInteractiveAndUnlocked()
        activeService = this
        registerScreenReceiver()
        stopObservingMaster = statsStore.observeMasterEnabled { enabled ->
            if (activeService === this) {
                if (!enabled) resetPendingWork("paused")
                updateForegroundProtection()
            }
        }
        updateForegroundProtection()

        SkipLog.log(
            "SERVICE connected v0.7 (window=${statsStore.windowMs}ms, cooldown=${SkipStateMachine.DEFAULT_COOLDOWN_MS}ms, maxAttempts=${SkipStateMachine.DEFAULT_MAX_ATTEMPTS})"
        )
        val handler = worker ?: return
        handler.post {
            if (canScan()) {
                startupEventNotes = 0
                startupUntilMs = SystemClock.uptimeMillis() + STARTUP_RECOVERY_MS
                STARTUP_PROBE_OFFSETS_MS.forEach { handler.postDelayed(startupProbe, it) }
            }
        }
    }

    private fun canScan(): Boolean =
        activeService === this && !screenSuspended && statsStore.masterEnabled

    private fun isInteractiveAndUnlocked(): Boolean =
        getSystemService(PowerManager::class.java)?.isInteractive == true &&
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true

    private fun resumeIfUnlocked() {
        if (screenSuspended && isInteractiveAndUnlocked()) resumeAfterScreenOff()
    }

    private fun resumeAfterScreenOff() {
        if (!screenSuspended) return
        screenSuspended = false
        val handler = worker ?: return
        handler.post {
            if (!canScan()) return@post
            foregroundEvents.onUnlocked(SystemClock.uptimeMillis())
            val root = activeApplicationRoot(rootOnly = true)
            val fgPkg = root?.packageName?.toString().orEmpty()
            if (root != null) safeRecycle(root)
            stateMachine.onUserPresent(
                foregroundPkg = fgPkg,
                nowMs = SystemClock.uptimeMillis(),
                userDisabledPackages = statsStore.getUserDisabledPackages(),
                selfTestPackage = packageName
            )
            isArmedFast = stateMachine.armed
            if (isArmedFast) {
                pendingContentScan.set(true)
                scheduleDrain(handler)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val w = worker ?: return
        if (activeService !== this || !statsStore.masterEnabled) return
        val receivedAt = SystemClock.uptimeMillis()
        // Some ROMs omit a wake/unlock broadcast. A window transition provides an event-driven fallback.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            resumeIfUnlocked()
        }
        if (!canScan()) return
        windowTrace.event(event, w) { at, offset ->
            if (canScan() && statsStore.observeOnly) {
                val (width, height) = getScreenSize()
                windowTrace.windows(at, offset, width, height, { rootInActiveWindow }, { windows })
            }
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString().orEmpty()
                val cls = event.className?.toString().orEmpty()
                val full = event.isFullScreen
                if (pendingWindowStates.size < 32) {
                    pendingWindowStates.add(
                        WindowStatePayload(pkg, cls, full, event.eventTime, event.windowId)
                    )
                }
                scanEvent.set(ScanEvent(pkg, event.windowId, event.eventTime, receivedAt))
                pendingContentScan.set(true)
                scheduleDrain(w)
            }

            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (event.mayChangeForeground()) {
                    pendingForegroundSignal.set(true)
                    pendingWindowCheck.set(true)
                } else if (!isArmedFast || !event.changesWindowGeometry()) {
                    return
                }
                if (isArmedFast) {
                    if (event.changesWindowGeometry() && event.windowId >= 0) {
                        geometryEvent.set(InputOrigin(event.eventTime, receivedAt, event.windowId, "geometry"))
                    }
                    pendingContentScan.set(true)
                }
                scheduleDrain(w)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val identity = ForegroundWindow(event.packageName?.toString().orEmpty(), event.windowId)
                val admittedDuringStartup = !isArmedFast && inStartupRecovery()
                val unarmedCheck = !isArmedFast && !admittedDuringStartup &&
                    !PackagePolicy.isExcludedPackage(identity.packageName) &&
                    foregroundEvents.reserveUnarmedContent(identity, SystemClock.uptimeMillis())
                if (!isArmedFast && !admittedDuringStartup && !unarmedCheck) return
                val offeredSourceRoot = isArmedFast && contentWindowRoot.offer(event, identity)
                scanEvent.set(ScanEvent(
                    identity.packageName, identity.id, event.eventTime, receivedAt,
                    admittedDuringStartup, unarmedCheck, offeredSourceRoot
                ))
                pendingContentScan.set(true)
                scheduleDrain(w)
            }
        }
    }

    private fun scheduleDrain(targetWorker: Handler? = worker) {
        val w = targetWorker ?: return
        if (canScan() && drainScheduled.compareAndSet(false, true) && !w.post(drainRunnable)) {
            drainScheduled.set(false)
        }
    }

    private fun inStartupRecovery(): Boolean =
        startupUntilMs > SystemClock.uptimeMillis()

    private fun stopStartupRecovery() {
        startupUntilMs = 0L
        worker?.removeCallbacks(startupProbe)
    }


    private fun processPendingOnWorker() {
        val epoch = workerEpoch
        if (epoch != workEpoch || !canScan()) return
        val workerAt = SystemClock.uptimeMillis()
        val shouldCheckWindows = pendingWindowCheck.getAndSet(false)
        val shouldScan = pendingContentScan.getAndSet(false)
        val foregroundSignal = pendingForegroundSignal.getAndSet(false)
        val source = scanEvent.get()
        val geometry = geometryEvent.getAndSet(null)
        val disabledPkgs = statsStore.getUserDisabledPackages()
        val recovering = inStartupRecovery()
        val labActivity = TestSplashActivity::class.java.name
        val now = SystemClock.uptimeMillis()
        val hadState = collectWindowStates(now, labActivity)

        isArmedFast = stateMachine.armed
        if (pendingAttempt != null) {
            cancelWindowProbes()
            verificationRunnable.run()
            return
        }
        if (!shouldScan && !shouldCheckWindows) return

        if (foregroundSignal) {
            contentWindowRoot.clear()
            foregroundEvents.onForegroundSignal(now)
        }
        val checkingForeground = shouldCheckWindows || hadState || source?.unarmedContentCheck == true
        val unmapped = expectedUnmapped(now)
        val matchingScan = shouldScan && stateMachine.armed && !checkingForeground && !recovering
        val sourceRoot = source?.takeIf { matchingScan && it.offeredSourceRoot && it.pkg == stateMachine.activePackage }?.let {
            contentWindowRoot.acquire(ForegroundWindow(it.pkg, it.windowId), it.timestampMs, MATCH_TREE_PREFETCH)
        }
        val root = sourceRoot?.node ?: activeApplicationRoot(
            rootOnly = checkingForeground || recovering, prefetchForMatch = matchingScan
        )
        val rootAt = SystemClock.uptimeMillis()
        if (root == null) {
            contentWindowRoot.clear()
            if (shouldCheckWindows || hadState) SkipTrace.log("accessibility") { "phase=root result=unavailable" }
            updateWindowProbe(null)
            if (recovering && shouldCheckWindows) SkipLog.log("STARTUP_PROBE phase=root result=unavailable")
            cancelPendingRetry("foreground_unavailable")
            return
        }
        val pkg = root.packageName?.toString().orEmpty()
        val identity = ForegroundWindow(pkg, root.windowId)
        if (shouldCheckWindows || hadState) SkipTrace.log("accessibility") {
            "phase=root result=available samePackage=${pkg == stateMachine.activePackage} " +
                "sameWindow=${identity == foregroundEvents.verifiedWindow} armed=${stateMachine.armed}"
        }
        if (identity in transientWindows) {
            safeRecycle(root)
            updateWindowProbe(null)
            cancelPendingRetry("transient_window")
            return
        }
        if (!identity.valid) {
            safeRecycle(root)
            updateWindowProbe(null)
            cancelPendingRetry("foreground_identity_missing")
            return
        }
        if (!shouldCheckWindows && !hadState && source?.unarmedContentCheck == true &&
            !foregroundEvents.acceptsStateHint(ForegroundWindow(source.pkg, source.windowId), identity)
        ) {
            safeRecycle(root)
            return
        }
        val retry = pendingRetry
        if (retry != null && (retry.sessionId != stateMachine.sessionId ||
                !retry.origin.matchesWindow(pkg, root.windowId))
        ) {
            safeRecycle(root)
            cancelPendingRetry("foreground_changed")
            return
        }
        val (screenW, screenH) = getScreenSize()
        val surface = appWindows.inspect(root, screenW, screenH, unmapped)
        val surfaceAt = SystemClock.uptimeMillis()
        if (surface == null) {
            contentWindowRoot.clear()
            if (checkingForeground) SkipTrace.log("accessibility") { "phase=surface result=unverified windowId=${identity.id}" }
            safeRecycle(root)
            updateWindowProbe(null)
            cancelPendingRetry("unverified_app_window")
            return
        }
        val hint = windowStates.remove(identity)
        val hintAge = hint?.let { now - it.timestampMs } ?: Long.MAX_VALUE
        val verifiedActivity = if (hint != null || surface.freeform != null) appWindows.verifiedActivity(identity) else null
        val floatingLaunch = hint != null && surface.freeform != null && !surface.behindTopWindow &&
            !stateMachine.isLaunchWindow(identity) && hintAge in 0..ForegroundEvents.DELAYED_ACTIVITY_HINT_MAX_AGE_MS &&
            surface.ordinaryApplication && verifiedActivity == hint.cls
        val delayedActivity = hint != null && hintAge > WINDOW_STATE_MAX_AGE_MS && !surface.behindTopWindow &&
            surface.ordinaryApplication && verifiedActivity == hint.cls &&
            (floatingLaunch || surface.freeform == null &&
                foregroundEvents.acceptsDelayedActivityHint(ForegroundWindow(hint.pkg, hint.windowId), identity, hintAge) &&
                isCredibleFullScreenActivity(root, hint, labActivity))
        val state = hint?.takeIf {
            hintAge in 0..WINDOW_STATE_MAX_AGE_MS ||
                surface.behindTopWindow && hintAge in 0..STACKED_WINDOW_STATE_MAX_AGE_MS || delayedActivity
        }
        if (delayedActivity) SkipTrace.log("accessibility") {
            "phase=window_hint result=delayed_activity windowId=${identity.id} ageMs=$hintAge"
        }
        val focusedApp = (checkingForeground || pkg != stateMachine.activePackage) &&
            !PackagePolicy.isTransientOverlay(pkg, state?.cls.orEmpty(), state?.isFullScreen ?: true)
        val enteredForeground = focusedApp &&
            foregroundEvents.recordFocusedApplication(identity)
        if (focusedApp && identity != foregroundEvents.verifiedWindow && !stateMachine.armed &&
            !PackagePolicy.isExcludedPackage(pkg, disabledPkgs) && surface.ordinaryApplication) {
            @Suppress("DEPRECATION")
            val copy = AccessibilityNodeInfo.obtain(root)
            NodeExtractor.extract(copy, "", screenW, screenH, maxNodes = MAX_VERIFY_NODES, surface = surface).use {
                if (it.snapshot.complete && !TargetEvidence.hasVisibleAdSignal(it.snapshot)) {
                    foregroundEvents.rememberReturnWindow(identity)
                }
            }
        }
        val newFloatingWindow = surface.freeform != null && identity != foregroundEvents.verifiedWindow
        val recoveredLab = pkg == packageName && (newFloatingWindow || surface.behindTopWindow ||
            state == null && (recovering || enteredForeground)) &&
            root.hasVisibleNode("$packageName:id/splashRoot")
        updateWindowProbe(identity.takeIf { !checkingForeground || focusedApp })
        if (enteredForeground) SkipTrace.log("accessibility") { "phase=foreground result=package_transition" }
        val moved = focusedApp && !floatingLaunch &&
            stateMachine.moveWithinResume(identity, SystemClock.uptimeMillis(), statsStore.windowMs, enteredForeground)
        if (moved) SkipTrace.log("accessibility") { "phase=window_switch session=${stateMachine.sessionId} kind=resume windowId=${identity.id}" }
        if (!moved && (state != null || recoveredLab || enteredForeground || pkg != stateMachine.activePackage &&
            (!checkingForeground || focusedApp))) {
            val previousSession = stateMachine.sessionId
            val observedAt = SystemClock.uptimeMillis()
            val hintedAt = source?.takeIf {
                it.pkg == pkg && it.windowId == root.windowId &&
                    it.timestampMs in (observedAt - STARTUP_RECOVERY_MS)..observedAt
            }?.timestampMs ?: state?.timestampMs?.takeIf { surface.behindTopWindow }
            val className = when {
                recoveredLab -> labActivity
                state != null -> state.cls
                enteredForeground && pkg == stateMachine.activePackage -> stateMachine.activeActivity
                surface.freeform != null -> verifiedActivity.orEmpty()
                else -> ""
            }
            stateMachine.onWindowStateChanged(
                pkg, className, state?.isFullScreen ?: true,
                if (enteredForeground && !delayedActivity) observedAt else state?.timestampMs ?: hintedAt ?: observedAt,
                userDisabledPackages = disabledPkgs,
                selfTestPackage = packageName,
                selfTestActivity = labActivity,
                windowId = identity.id,
                confirmedFullscreenActivity = recoveredLab || state != null &&
                    (surface.freeform != null || isCredibleFullScreenActivity(root, state, labActivity)),
                confirmedForegroundEntry = enteredForeground,
                confirmedFloatingWindow = floatingLaunch
            )
            if (previousSession != stateMachine.sessionId) SkipTrace.log("accessibility") {
                "phase=window session=${stateMachine.sessionId} armed=${stateMachine.armed}"
            }
        }
        if (recoveredLab) {
            val fromContent = source?.admittedDuringStartup == true && source.windowId == root.windowId
            SkipLog.log("STARTUP_PROBE phase=root result=recovered_lab_without_activity_state windowId=${root.windowId} contentWhileUnarmed=$fromContent")
        } else if (recovering && shouldCheckWindows && !stateMachine.armed && pkg == packageName &&
            startupEventNotes < 6
        ) {
            startupEventNotes++
            SkipLog.log("STARTUP_PROBE phase=root result=own_panel_or_lab_not_yet_visible windowId=${root.windowId}")
        }
        isArmedFast = stateMachine.armed
        if (!isArmedFast) {
            contentWindowRoot.clear()
            safeRecycle(root)
            return
        }
        if (surface.ordinaryApplication && !surface.behindTopWindow) contentWindowRoot.remember(root)
        else contentWindowRoot.clear()
        val extractAt = SystemClock.uptimeMillis()
        NodeExtractor.extract(root, stateMachine.activeActivity, screenW, screenH, surface = surface).use { tree ->
            val treeAt = SystemClock.uptimeMillis()
            val snapshot = tree.snapshot
            if ((identity != tracedNotificationCandidate || tracedNotificationCandidateSession != stateMachine.sessionId) &&
                evidenceLoggingEnabled() && snapshot.complete &&
                snapshot.nodes.any { it.isVisibleToUser && it.shortId == "wm_splash_jump_tv" } &&
                snapshot.nodes.any { it.shortId == "ll_notification" }) {
                tracedNotificationCandidate = identity
                tracedNotificationCandidateSession = stateMachine.sessionId
                SkipTrace.log("accessibility") {
                    "phase=creative candidate=notification_banner windowId=${identity.id}"
                }
            }
            if ((identity != tracedNotificationWindow || tracedNotificationSession != stateMachine.sessionId) &&
                evidenceLoggingEnabled() && snapshot.complete &&
                snapshot.nodes.any { it.isVisibleToUser && it.shortId == "wm_splash_jump_tv" } &&
                snapshot.nodes.any { it.isVisibleToUser && it.shortId == "wm_shake_click_region" }) {
                tracedNotificationWindow = identity
                tracedNotificationSession = stateMachine.sessionId
                SkipTrace.log("accessibility") {
                    val dismissAction = AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id
                    val dismissNodes = snapshot.nodes.filter { node -> node.isVisibleToUser &&
                        tree.nodeAt(node.index)?.actionList?.any { it.id == dismissAction } == true }
                        .joinToString(",") { it.index.toString() }
                    "phase=creative type=notification_banner windowId=${identity.id} dismissNodes=${dismissNodes.ifEmpty { "-" }}"
                }
            }
            snapshot.freeform?.let { foregroundEvents.trackFloatingLaunch(stateMachine.sessionId, identity, it.captionId, SystemClock.uptimeMillis()) }
            if (snapshot.complete && (snapshot.isFullScreen || snapshot.freeform != null) && surface.ordinaryApplication &&
                !TargetEvidence.hasVisibleAdSignal(snapshot)
            ) {
                foregroundEvents.rememberReturnWindow(identity)
            }
            if (pkg == packageName && snapshot.nodes.none { it.viewId == "$packageName:id/splashRoot" && it.isVisibleToUser }) {
                cancelPendingRetry("lab_root_missing")
                return@use
            }
            val now = SystemClock.uptimeMillis()
            if (statsStore.diagNodes && now - lastDiagDumpAt > DIAG_THROTTLE_MS &&
                !PackagePolicy.isExcludedPackage(snapshot.packageName, disabledPkgs)
            ) {
                lastDiagDumpAt = now
                val file = SkipLog.writeSnapshotFile(snapshot.packageName, SnapshotJson.toJson(snapshot))
                SkipLog.log("SNAPSHOT nodes=${snapshot.nodes.size} saved=${file != null}")
            }

            val matchStartedAt = SystemClock.uptimeMillis()
            val matchStartCpu = SystemClock.currentThreadTimeMillis()
            val matchStart = SystemClock.elapsedRealtimeNanos()
            val evaluation = stateMachine.evaluateSnapshot(
                snapshot, now, statsStore.masterEnabled, statsStore.observeOnly, statsStore.windowMs,
                userDisabledPackages = disabledPkgs,
                selfTestPackage = packageName,
                selfTestActivity = labActivity
            )
            val matchUs = (SystemClock.elapsedRealtimeNanos() - matchStart) / 1000L
            val matchCpuMs = SystemClock.currentThreadTimeMillis() - matchStartCpu
            if (!stateMachine.armed) {
                isArmedFast = false
                contentWindowRoot.clear()
            }
            if (tracedScanSession != stateMachine.sessionId || evaluation != EvaluationResult.NoOp) {
                tracedScanSession = stateMachine.sessionId
                SkipTrace.log("accessibility") {
                    "phase=match session=${stateMachine.sessionId} result=${evaluation.javaClass.simpleName} " +
                        "complete=${snapshot.complete} nodes=${snapshot.nodes.size} elapsedMs=${now - stateMachine.launchAtMs}"
                }
            }
            when (evaluation) {
                EvaluationResult.AwaitStableLayout -> scheduleRelayoutProbe()
                EvaluationResult.NoOp -> if (retry != null) {
                    if (retry.origin.waitsForStableLayout && stateMachine.armed) scheduleRelayoutProbe()
                    else cancelPendingRetry("target_unproven")
                } else Unit
                is EvaluationResult.OutOfWindow -> {
                    cancelPendingRetry("window_expired")
                    SkipLog.log("WINDOW_EXPIRED after=${evaluation.sinceLaunchMs}ms")
                }
                is EvaluationResult.ObserveDetected -> {
                    stopStartupRecovery()
                    val decision = evaluation.decision
                    SkipLog.log("DETECT via=${decision.matchedVia} shake=${decision.isShakeAd} matchUs=$matchUs observe=true")
                }
                is EvaluationResult.ExecuteClick -> {
                    val command = evaluation.command
                    stopStartupRecovery()
                    cancelWindowProbes()
                    val detectedAt = SystemClock.uptimeMillis()
                    val labTrace = if (pkg == packageName) TestSplashActivity.resumedTrace else null
                    val evidence = TargetEvidence(snapshot, command.decision,
                        allowBoundedMovement = true, followsVisibleAd = true)
                    if (evidenceLoggingEnabled()) {
                        windowTrace.recordActionSnapshot(command.sessionId, command.attemptId, detectedAt, snapshot)
                    }
                    val originEvent = retry?.event ?: source?.takeIf {
                        it.windowId == tree.windowId && it.pkg == pkg &&
                            it.timestampMs in stateMachine.launchAtMs..it.receivedAtMs && it.receivedAtMs <= workerAt
                    }?.let { InputOrigin(it.timestampMs, it.receivedAtMs, it.windowId, "scan") } ?: geometry?.takeIf {
                        it.windowId == tree.windowId && it.eventAtMs in stateMachine.launchAtMs..it.receivedAtMs &&
                            it.receivedAtMs <= workerAt
                    }
                    pendingRetry = null
                    worker?.removeCallbacks(relayoutProbe)
                    val w = worker ?: return@use
                    val actionAt = SystemClock.uptimeMillis()
                    var foregroundCheckMs = 0L
                    var checkedByLayers = false
                    var retrySnapshotInvalidated = false
                    val result = ActionExecutor.execute(
                        service = this,
                        tree = tree,
                        command = command,
                        screenWidth = screenW,
                        screenHeight = screenH,
                        callbackHandler = w,
                        canDispatch = { clickBounds ->
                            epoch == workEpoch && canScan() && !statsStore.observeOnly &&
                                (command.retryTarget?.matchesWindow(pkg, tree.windowId) != false) &&
                                run {
                                    val started = SystemClock.uptimeMillis()
                                    val gestureRetry = clickBounds == null && retry?.origin?.followsVisibleAd == true
                                    if (gestureRetry && !isFreshGestureRetry(treeAt, started, pendingForegroundSignal.get())) {
                                        retrySnapshotInvalidated = true
                                        return@run false
                                    }
                                    val target = command.decision.targetBounds
                                    checkedByLayers = clickBounds == null && appWindows.canDispatchFocusedGesture(
                                        surface, tree.windowId, target, screenW, screenH
                                    )
                                    val allowed = checkedByLayers || isSameForeground(tree, target, clickBounds, screenW, screenH)
                                    val checkedAt = SystemClock.uptimeMillis()
                                    foregroundCheckMs += checkedAt - started
                                    retrySnapshotInvalidated = allowed && gestureRetry &&
                                        !isFreshGestureRetry(treeAt, checkedAt, pendingForegroundSignal.get())
                                    allowed && !retrySnapshotInvalidated
                                } &&
                                epoch == workEpoch && canScan() && !statsStore.observeOnly &&
                                stateMachine.isWithinWindow(SystemClock.uptimeMillis(), statsStore.windowMs)
                        },
                        onGestureOutcome = { sid, aid, completed ->
                            val pending = pendingAttempt
                            if (epoch == workEpoch && pending != null &&
                                pending.command.sessionId == sid && pending.command.attemptId == aid
                            ) {
                                val callbackAt = SystemClock.uptimeMillis()
                                val gestureResult = if (completed) "GESTURE_OK" else "GESTURE_CANCELLED"
                                SkipLog.log("$gestureResult session=$sid attempt=$aid callbackAtMs=$callbackAt")
                                if (completed) {
                                    pending.gestureCompletedAtMs = callbackAt
                                    verificationRunnable.run()
                                } else {
                                    pending.gestureCompletedAtMs = callbackAt
                                    scheduleVerification(TARGET_RETRY_MS)
                                }
                            }
                        }
                    )
                    val refreshTarget = if (retrySnapshotInvalidated) {
                        contentWindowRoot.clear()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            runCatching { clearCache() }
                        }
                        retry?.origin
                    } else ActionExecutor.reacquisitionEvidence(result, tree, command)
                    stateMachine.onActionDispatched(
                        command.sessionId, command.attemptId, result.actualMethod, result.dispatched, refreshTarget
                    )
                    SkipTrace.log("accessibility") {
                        "phase=input session=${command.sessionId} attempt=${command.attemptId} " +
                            "accepted=${result.dispatched} reason=${result.rejectedBy ?: "-"}"
                    }
                    val pending = ObservedAttempt(
                        command, tree.windowId, evidence, result.actualMethod, detectedAt, actionAt,
                        result.inputStartedAtMs, result.inputReturnedAtMs,
                        originEvent, matchUs, labTrace,
                        snapshot.freeform?.let { foregroundEvents.floatingDestination(command.sessionId, identity, it.captionId) }
                            ?: foregroundEvents.returnWindowFor(identity, result.inputStartedAtMs ?: actionAt)
                    )
                    pendingAttempt = pending
                    val decision = command.decision
                    SkipLog.log(
                        "TARGET session=${command.sessionId} attempt=${command.attemptId} via=${decision.matchedVia} " +
                            "shake=${decision.isShakeAd} method=${result.actualMethod} dispatch=${result.dispatched} " +
                            "eventAtMs=${pending.eventAtMs ?: "-"} detectAtMs=$detectedAt actAtMs=$actionAt matchUs=$matchUs " +
                            "matchCpuMs=$matchCpuMs " +
                            "receivedAtMs=${originEvent?.receivedAtMs ?: "-"} originType=${originEvent?.type ?: "-"} " +
                            "workerAtMs=$workerAt rootAtMs=$rootAt eventSourceRoot=${sourceRoot != null} " +
                            "sourceAncestorSteps=${sourceRoot?.ancestorSteps ?: "-"} offeredSourceRoot=${source?.offeredSourceRoot == true} " +
                            "surfaceAtMs=$surfaceAt extractAtMs=$extractAt treeAtMs=$treeAt matchStartedAtMs=$matchStartedAt " +
                            "foregroundCheckMs=$foregroundCheckMs " +
                            "foregroundLayersOnly=$checkedByLayers retrySnapshotInvalidated=$retrySnapshotInvalidated " +
                            "inputAtMs=${result.inputStartedAtMs ?: "-"} inputReturnAtMs=${result.inputReturnedAtMs ?: "-"} " +
                            "dispatchRejection=${result.rejectedBy ?: "-"}" +
                            (labTrace?.let { " ${it.fields} windowId=${tree.windowId}" } ?: "")
                    )
                    if (result.dispatched) {
                        scheduleVerification(
                            if (result.actualMethod == ClickMethod.GESTURE) GESTURE_CALLBACK_TIMEOUT_MS else FIRST_PROBE_MS
                        )
                    } else {
                        completeAttempt(pending, VerifyOutcome.UNOBSERVED, "dispatch_rejected", refreshTarget)
                    }
                }
            }
        }
        isArmedFast = stateMachine.armed
    }


    private fun collectWindowStates(now: Long, labActivity: String): Boolean {
        windowStates.entries.removeAll { now - it.value.timestampMs > ForegroundEvents.DELAYED_ACTIVITY_HINT_MAX_AGE_MS }
        var seen = false
        while (true) {
            val state = pendingWindowStates.poll() ?: break
            val identity = ForegroundWindow(state.pkg, state.windowId)
            val age = now - state.timestampMs
            if (!identity.valid || age !in 0..ForegroundEvents.DELAYED_ACTIVITY_HINT_MAX_AGE_MS) continue
            if (age > WINDOW_STATE_MAX_AGE_MS && (
                    !state.cls.startsWith("${state.pkg}.") || !state.cls.endsWith("Activity") ||
                    PackagePolicy.isExcludedPackage(state.pkg) || PackagePolicy.isTransientOverlay(state.pkg, state.cls, true))) continue
            appWindows.state(identity, state.cls)
            if (age <= WINDOW_STATE_MAX_AGE_MS && state.isFullScreen && !PackagePolicy.isTransientOverlay(state.pkg, "", false) &&
                !PackagePolicy.isExcludedPackage(state.pkg)
            ) {
                foregroundEvents.onResumeWindowHint(identity, state.timestampMs)
            }
            if (PackagePolicy.isTransientOverlay(state.pkg, state.cls, state.isFullScreen)) {
                if (identity != foregroundEvents.verifiedWindow && identity !in transientWindows) {
                    if (transientWindows.size >= 16) transientWindows.remove(transientWindows.first())
                    transientWindows.add(identity)
                    windowStates.remove(identity)
                }
                continue
            }
            seen = true
            foregroundEvents.onWindowSignal(identity.id, now)
            if (!(state.cls == labActivity || state.cls.endsWith("Activity", ignoreCase = true) ||
                    state.isFullScreen && state.cls.startsWith("${state.pkg}."))
            ) continue
            transientWindows.remove(identity)
            val previous = windowStates[identity]
            if (previous != null && previous.timestampMs > state.timestampMs) continue
            if (windowStates.size >= 16 && previous == null) windowStates.remove(windowStates.keys.first())
            windowStates[identity] = state
        }
        return seen
    }

    private fun isCredibleFullScreenActivity(
        root: AccessibilityNodeInfo,
        event: WindowStatePayload,
        labActivity: String
    ): Boolean {
        if (event.cls == labActivity && event.pkg == packageName) {
            return root.hasVisibleNode("$packageName:id/splashRoot")
        }
        if (!event.isFullScreen || !event.cls.endsWith("Activity") || !event.cls.startsWith("${event.pkg}.")) {
            return false
        }
        val bounds = Rect().also(root::getBoundsInScreen)
        val (w, h) = getScreenSize()
        return bounds.width() >= w * 0.85f && bounds.height() >= h * 0.85f
    }

    private fun updateWindowProbe(root: ForegroundWindow?) {
        val handler = worker ?: return
        handler.removeCallbacks(windowProbe)
        foregroundEvents.observeRoot(root, SystemClock.uptimeMillis())?.let { handler.postAtTime(windowProbe, it) }
    }

    private fun cancelWindowProbes() {
        foregroundEvents.cancelWindowProbes()
        worker?.removeCallbacks(windowProbe)
    }

    private fun verifyPending() {
        if (workerEpoch != workEpoch || !canScan()) return
        val attempt = pendingAttempt ?: return
        val now = SystemClock.uptimeMillis()
        val inputReturnedAt = attempt.inputReturnedAtMs ?: attempt.actionAtMs
        val gestureCompletedAt = attempt.gestureCompletedAtMs
        if (attempt.method == ClickMethod.GESTURE && gestureCompletedAt == null) {
            val remaining = GESTURE_CALLBACK_TIMEOUT_MS - (now - inputReturnedAt)
            if (remaining > 0) {
                scheduleVerification(remaining)
                return
            }
            SkipTrace.log("accessibility") { "phase=gesture_callback result=missing attempt=${attempt.command.attemptId}" }
        }
        if (now - attempt.lastProbeMs < PROBE_INTERVAL_MS) return
        attempt.lastProbeMs = now
        val observingSince = gestureCompletedAt ?: inputReturnedAt
        val age = now - observingSince
        val root = activeApplicationRoot()
        if (workerEpoch != workEpoch || !canScan()) {
            if (root != null) safeRecycle(root)
            return
        }
        if (root == null) {
            attempt.closure.record(TargetObservation.UNOBSERVED, now)
            if (age >= attempt.evidence.verifyTimeoutMs) completeAttempt(attempt, VerifyOutcome.UNOBSERVED, "no_active_tree")
            else scheduleVerification(PROBE_INTERVAL_MS)
            return
        }
        val currentPkg = root.packageName?.toString().orEmpty()
        val destination = ForegroundWindow(currentPkg, root.windowId)
        if (!destination.valid || destination in transientWindows ||
            PackagePolicy.isTransientOverlay(currentPkg, "", false)
        ) {
            safeRecycle(root)
            attempt.closure.record(TargetObservation.UNOBSERVED, now)
            if (age >= attempt.evidence.verifyTimeoutMs) completeAttempt(attempt, VerifyOutcome.UNOBSERVED, "unavailable_window")
            else scheduleVerification(PROBE_INTERVAL_MS)
            return
        }
        if (destination == attempt.returnWindow) {
            val observation = if (runCatching { windows.showsReturnTo(attempt.windowId, destination.id, attempt.evidence.freeform?.captionId) }.getOrDefault(false)) {
                val (width, height) = getScreenSize()
                val cleared = Build.VERSION.SDK_INT >= 33 && runCatching { clearCachedSubtree(root) }.getOrDefault(false)
                if (cleared && !runCatching { root.refresh() }.getOrDefault(false)) {
                    safeRecycle(root)
                    TargetObservation.UNOBSERVED
                } else {
                    NodeExtractor.extract(root, "", width, height, maxNodes = MAX_VERIFY_NODES,
                        surface = appWindows.inspect(root, width, height, expectedUnmapped(now)),
                        refreshDeadlineMs = if (cleared) null else now + FRESH_VERIFY_BUDGET_MS
                    ).use { attempt.evidence.observeReturn(it.snapshot) }
                }
            } else {
                safeRecycle(root)
                TargetObservation.UNOBSERVED
            }
            if (workerEpoch != workEpoch || !canScan()) return
            val observedAt = SystemClock.uptimeMillis()
            if (attempt.crossWindowClosure.record(observation, observedAt)) {
                completeAttempt(attempt, VerifyOutcome.VERIFIED_CLOSED, "returned_to_previous_window")
                foregroundEvents.recordFocusedApplication(destination)
                stateMachine.onNavigationObserved(destination)
            } else if (observedAt - observingSince >= attempt.evidence.returnTimeoutMs) {
                val outcome = if (observation == TargetObservation.AD_PRESENT || observation == TargetObservation.TARGET_PRESENT) {
                    VerifyOutcome.STILL_PRESENT
                } else VerifyOutcome.NAVIGATED
                completeAttempt(attempt, outcome, "return_window_unconfirmed")
                stateMachine.onNavigationObserved(destination)
            } else {
                scheduleVerification(PROBE_INTERVAL_MS)
            }
            return
        }
        attempt.crossWindowClosure.record(TargetObservation.UNOBSERVED, now)
        if (currentPkg != attempt.command.packageName || root.windowId != attempt.windowId) {
            val (w, h) = getScreenSize()
            if (appWindows.inspect(root, w, h, expectedUnmapped(now)) != null) {
                foregroundEvents.recordFocusedApplication(destination)
            }
            safeRecycle(root)
            val reason = if (currentPkg == attempt.command.packageName) {
                "same_package_window_changed"
            } else {
                "foreground_package_changed"
            }
            completeAttempt(attempt, VerifyOutcome.NAVIGATED, reason)
            foregroundEvents.observeRoot(destination, now)
            stateMachine.onNavigationObserved(destination)
            return
        }

        val (screenW, screenH) = getScreenSize()
        var observation = NodeExtractor.extract(
            root, stateMachine.activeActivity, screenW, screenH, maxNodes = MAX_VERIFY_NODES,
            surface = appWindows.inspect(root, screenW, screenH, expectedUnmapped(now))
        ).use { attempt.evidence.observe(it.snapshot) }
        if (workerEpoch != workEpoch || !canScan()) return
        var observedAt = SystemClock.uptimeMillis()
        if (attempt.closure.record(observation, observedAt)) {
            observation = observeFreshClosure(attempt, screenW, screenH)
            if (workerEpoch != workEpoch || !canScan()) return
            observedAt = SystemClock.uptimeMillis()
            if (observation == TargetObservation.ABSENT) {
                completeAttempt(attempt, VerifyOutcome.VERIFIED_CLOSED, "same_window_target_and_markers_absent")
                return
            }
            attempt.closure.record(observation, observedAt)
        }

        val retryReady = observation == TargetObservation.TARGET_PRESENT &&
            observedAt - observingSince >= TARGET_RETRY_MS
        if (retryReady) {
            completeAttempt(attempt, VerifyOutcome.STILL_PRESENT, "target_visible", attempt.evidence)
            return
        }
        if (observedAt - observingSince >= attempt.evidence.verifyTimeoutMs) {
            when (observation) {
                TargetObservation.TARGET_PRESENT -> completeAttempt(attempt, VerifyOutcome.STILL_PRESENT,
                    "target_visible", attempt.evidence)
                TargetObservation.AD_PRESENT -> completeAttempt(attempt, VerifyOutcome.STILL_PRESENT, "ad_evidence_visible")
                else -> completeAttempt(attempt, VerifyOutcome.UNOBSERVED, "absence_unconfirmed")
            }
        } else {
            scheduleVerification(PROBE_INTERVAL_MS)
        }
    }

    private fun observeFreshClosure(attempt: ObservedAttempt, screenW: Int, screenH: Int): TargetObservation {
        val root = activeApplicationRoot() ?: return TargetObservation.UNOBSERVED
        if (!attempt.evidence.matchesWindow(root.packageName?.toString().orEmpty(), root.windowId)) {
            safeRecycle(root)
            return TargetObservation.UNOBSERVED
        }
        val cleared = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { clearCachedSubtree(root) }.getOrDefault(false)
        if (cleared && !runCatching { root.refresh() }.getOrDefault(false)) {
            safeRecycle(root)
            return TargetObservation.UNOBSERVED
        }
        // A cached child can omit a newly visible ad badge. Verify absence against current nodes.
        return NodeExtractor.extract(
            root, stateMachine.activeActivity, screenW, screenH, maxNodes = MAX_VERIFY_NODES,
            surface = appWindows.inspect(root, screenW, screenH, expectedUnmapped(SystemClock.uptimeMillis())),
            refreshDeadlineMs = if (cleared) null else SystemClock.uptimeMillis() + FRESH_VERIFY_BUDGET_MS
        ).use { attempt.evidence.observe(it.snapshot) }
    }

    private fun scheduleVerification(delayMs: Long) {
        val w = worker ?: return
        w.removeCallbacks(verificationRunnable)
        w.postDelayed(verificationRunnable, delayMs)
    }

    private fun completeAttempt(
        attempt: ObservedAttempt,
        outcome: VerifyOutcome,
        reason: String,
        retryTarget: TargetEvidence? = null
    ) {
        if (pendingAttempt !== attempt) return
        pendingAttempt = null
        worker?.removeCallbacks(verificationRunnable)
        val retryEvidence = retryTarget?.let {
            attempt.command.retryTarget?.takeIf(TargetEvidence::followsVisibleAd) ?: it
        }
        stateMachine.finishVerification(attempt.command.sessionId, attempt.command.attemptId, outcome, retryEvidence)
        val at = SystemClock.uptimeMillis()
        val latencyMs = if (outcome == VerifyOutcome.VERIFIED_CLOSED) attempt.eventAtMs?.let { at - it } else null
        statsStore.recordOutcome(outcome, latencyMs)
        SkipTrace.log("accessibility") {
            "phase=verify session=${attempt.command.sessionId} attempt=${attempt.command.attemptId} " +
                "result=$outcome reason=$reason elapsedMs=${at - attempt.actionAtMs}"
        }
        SkipLog.log(
            "OUTCOME result=$outcome evidence=$reason session=${attempt.command.sessionId} attempt=${attempt.command.attemptId} " +
                "method=${attempt.method} matchUs=${attempt.matchUs} detectToActMs=${attempt.actionAtMs - attempt.detectedAtMs} " +
                "actToOutcomeMs=${at - attempt.actionAtMs} eventToConfirmedMs=${latencyMs ?: "-"} " +
                "eventToInputMs=${attempt.eventAtMs?.let { event -> attempt.inputStartedAtMs?.minus(event) } ?: "-"} " +
                "inputToOutcomeMs=${attempt.inputStartedAtMs?.let { at - it } ?: "-"}" +
                (attempt.labTrace?.let { " ${it.fields} windowId=${attempt.windowId}" } ?: "")
        )
        if (outcome == VerifyOutcome.VERIFIED_CLOSED) SkipSuccessFeedback.onVerifiedClosed(this)
        isArmedFast = stateMachine.armed
        if (!isArmedFast) contentWindowRoot.clear()
        if (retryEvidence != null && isArmedFast) {
            pendingRetry = PendingRetry(attempt.command.sessionId, retryEvidence, attempt.event)
            if (retryEvidence.waitsForStableLayout && !retryEvidence.followsVisibleAd) scheduleRelayoutProbe() else {
                pendingContentScan.set(true)
                scheduleDrain()
            }
        }
    }

    private fun scheduleRelayoutProbe() {
        val handler = worker ?: return
        handler.removeCallbacks(relayoutProbe)
        handler.postDelayed(relayoutProbe, RELAYOUT_PROBE_MS)
    }

    private fun cancelPendingRetry(reason: String) {
        val retry = pendingRetry ?: return
        pendingRetry = null
        worker?.removeCallbacks(relayoutProbe)
        stateMachine.cancelRetry()
        isArmedFast = stateMachine.armed
        if (!isArmedFast) contentWindowRoot.clear()
        SkipLog.log("RETRY_CANCEL session=${retry.sessionId} reason=$reason")
    }

    private fun expectedUnmapped(now: Long): ForegroundWindow? =
        foregroundEvents.resumeWindow(now) ?: if (pendingAttempt != null || stateMachine.isWithinWindow(now, statsStore.windowMs))
            foregroundEvents.floatingOrigin(stateMachine.sessionId) else null

    private fun activeApplicationRoot(
        rootOnly: Boolean = inStartupRecovery(),
        prefetchForMatch: Boolean = false
    ): AccessibilityNodeInfo? {
        val start = SystemClock.uptimeMillis()
        val unmapped = expectedUnmapped(start)
        val root = try {
            when {
                prefetchForMatch && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                    getRootInActiveWindow(MATCH_TREE_PREFETCH)
                rootOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getRootInActiveWindow(0)
                else -> rootInActiveWindow
            }
        } catch (_: Exception) {
            null
        }
        val defaultLookupMs = SystemClock.uptimeMillis() - start
        if (root != null) {
            val owner = try {
                root.window
            } catch (_: Exception) {
                null
            }
            val type = owner?.type
            val usable = try {
                root.windowId >= 0 && type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    owner?.isFocusedApp() == true
            } finally {
                @Suppress("DEPRECATION")
                owner?.recycle()
            }
            if (type == AccessibilityWindowInfo.TYPE_APPLICATION || type == -1) {
                val bounds = Rect().also(root::getBoundsInScreen)
                if (bounds.height() <= getScreenSize().second * .25f) {
                    readBehindTopWindow(start)?.let { recovered ->
                        safeRecycle(root)
                        return recovered
                    }
                }
            }
            if (usable) return root
            safeRecycle(root)
            if (type != null && type != -1 && type != AccessibilityWindowInfo.TYPE_APPLICATION) return null
            if (type == -1) return unmapped?.let { runCatching { windows.readFocusedUnmapped(it) }.getOrNull() }
        } else {
            readBehindTopWindow(start)?.let { return it }
        }

        // Recover missing active-root data only from the single active, focused app window.
        val recovered = withFocusedAppWindow(null) { window ->
            val candidate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) window.getRoot(0) else window.root
            if (candidate != null && (candidate.windowId != window.id ||
                    withFocusedAppWindow(window.id) { true } != true)
            ) {
                safeRecycle(candidate)
                null
            } else {
                candidate
            }
        }
        if (recovered == null && unmapped != null) {
            runCatching { windows.readFocusedUnmapped(unmapped) }.getOrNull()?.let { return it }
        }
        val at = SystemClock.uptimeMillis()
        if (at - lastRootLookupNoteAt >= DIAG_THROTTLE_MS) {
            lastRootLookupNoteAt = at
            val reason = if (root == null) "root_unavailable" else "unverified_active_root"
            SkipLog.log("A11Y_ROOT fallback=$reason found=${recovered != null} defaultMs=$defaultLookupMs totalMs=${at - start}")
        }
        return recovered
    }

    private fun readBehindTopWindow(now: Long): AccessibilityNodeInfo? {
        val expected = windowStates.values.asSequence()
            .filter { now - it.timestampMs in 0..STACKED_WINDOW_STATE_MAX_AGE_MS }
            .map { ForegroundWindow(it.pkg, it.windowId) }
            .filter { !PackagePolicy.isExcludedPackage(it.packageName) && appWindows.verifiedActivity(it) != null }
            .toCollection(LinkedHashSet())
        if (pendingAttempt != null || stateMachine.armed && stateMachine.isWithinWindow(now, statsStore.windowMs)) {
            foregroundEvents.verifiedWindow?.let(expected::add)
        }
        return appWindows.readBehindTopWindow(expected)
    }

    private fun <T> withFocusedAppWindow(expectedId: Int?, read: (AccessibilityWindowInfo) -> T): T? {
        return try {
            windows.withFocusedAppWindow { focused ->
                if (expectedId == null || focused.id == expectedId) read(focused) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isSameForeground(tree: ExtractedTree, target: RectBounds, clickBounds: RectBounds?, w: Int, h: Int): Boolean {
        val root = activeApplicationRoot(rootOnly = true) ?: return false
        return try {
            appWindows.canDispatch(root, w, h, expectedUnmapped(SystemClock.uptimeMillis()), tree, target, clickBounds)
        } finally {
            safeRecycle(root)
        }
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

    private fun foregroundTypeActive(): Boolean = foregroundRequested &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0)

    private fun updateForegroundProtection() {
        if (!statsStore.masterEnabled) {
            stopForegroundProtection()
            return
        }
        if (foregroundTypeActive()) return
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "自动跳过广告",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示 FlashSkip 的运行状态，方便随时暂停"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)

            val openIntent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val pauseIntent = PendingIntent.getBroadcast(
                this,
                0,
                Intent(ACTION_PAUSE).setPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_flash)
                .setContentTitle("FlashSkip 正在运行")
                .setContentText("打开其他应用时，会尝试跳过开屏广告。")
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(pi)
                .addAction(0, "暂停自动跳过", pauseIntent)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            foregroundRequested = true
            SkipLog.log("FOREGROUND active=${foregroundTypeActive()}")
        } catch (e: RuntimeException) {
            SkipLog.log("FOREGROUND unavailable=${e.javaClass.simpleName}")
        }
    }

    private fun stopForegroundProtection() {
        if (foregroundRequested) {
            foregroundRequested = false
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(ACTION_PAUSE)
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

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        val actionSnapshots = args.contains("--action-evidence")
        if (!actionSnapshots && !args.contains("--window-evidence")) {
            super.dump(fd, writer, args)
            return
        }
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 ||
            activeService !== this || !canScan() || !isInteractiveAndUnlocked()) return
        // ADB evidence capture must not enable rolling snapshots in the app's files.
        val evidenceEnabled = evidenceLoggingEnabled()
        if (actionSnapshots) {
            if (evidenceEnabled) windowTrace.dumpActionSnapshots(writer)
            return
        }
        if (!statsStore.diagNodes && !evidenceEnabled) return
        val (width, height) = getScreenSize()
        windowTrace.dumpEvidence(writer, width, height, windows)
    }

    private fun evidenceLoggingEnabled() =
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && Log.isLoggable(EVIDENCE_TAG, Log.DEBUG)

    private fun resetPendingWork(reason: String) {
        val epoch = ++workEpoch
        isArmedFast = false
        val handler = worker ?: return
        handler.post {
            stopStartupRecovery()
            cancelWindowProbes()
            handler.removeCallbacks(drainRunnable)
            handler.removeCallbacks(verificationRunnable)
            handler.removeCallbacks(relayoutProbe)
            drainScheduled.set(false)
            pendingWindowCheck.set(false)
            pendingForegroundSignal.set(false)
            pendingContentScan.set(false)
            scanEvent.set(null)
            geometryEvent.set(null)
            contentWindowRoot.clear()
            pendingWindowStates.clear()
            appWindows.clear()
            windowStates.clear()
            transientWindows.clear()
            foregroundEvents = ForegroundEvents()
            pendingRetry = null
            pendingAttempt?.let { completeAttempt(it, VerifyOutcome.UNOBSERVED, reason) }
            stateMachine.onScreenOff()
            isArmedFast = false
            workerEpoch = epoch
        }
    }

    private fun deactivate() {
        if (activeService !== this) return
        activeService = null
        screenSuspended = true
        stopObservingMaster?.invoke()
        stopObservingMaster = null
        unregisterScreenReceiver()
        stopForegroundProtection()
        resetPendingWork("disconnected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        deactivate()
        SkipLog.log("SERVICE unbind")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        deactivate()
        workerThread?.quitSafely()
        worker = null
        workerThread = null
        super.onDestroy()
    }
}
