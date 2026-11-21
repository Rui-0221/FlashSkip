package com.flashskip.experimental

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.flashskip.a11y.ActionExecutor
import com.flashskip.a11y.ExtractedTree
import com.flashskip.a11y.NodeExtractor
import com.flashskip.a11y.SkipTrace
import com.flashskip.a11y.WindowTrace
import com.flashskip.a11y.hasVisibleNode
import com.flashskip.a11y.hasOrdinaryWindow
import com.flashskip.a11y.readFocusedUnmapped
import com.flashskip.a11y.showsReturnTo
import com.flashskip.a11y.isFocusedApp
import com.flashskip.a11y.isFocusedFullscreenApp
import com.flashskip.a11y.mayChangeForeground
import com.flashskip.a11y.withFocusedAppWindow
import com.flashskip.core.ActionCommand
import com.flashskip.core.ClickMethod
import com.flashskip.core.EvaluationResult
import com.flashskip.core.ForegroundEvents
import com.flashskip.core.ForegroundWindow
import com.flashskip.core.PackagePolicy
import com.flashskip.core.RectBounds
import com.flashskip.core.SkipStateMachine
import com.flashskip.core.TargetEvidence
import com.flashskip.core.TargetObservation
import com.flashskip.core.VerifyOutcome
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/** Runs only in the shell process started by the user-authorized Shizuku service. */
@Suppress("DEPRECATION")
class UiAutomationRunner(private val context: Context) : ISkipRunner.Stub() {
    private val callbacks = HandlerThread("FlashSkipAutomationCallbacks").apply { start() }
    private val worker = Handler(HandlerThread("FlashSkipAutomation").apply { start() }.looper)
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    private var automation: UiAutomation? = null
    private var host: ISkipHost? = null
    private val hostDied = IBinder.DeathRecipient(::destroy)
    private var machine = SkipStateMachine()
    private var foreground = ForegroundEvents()
    private var activityHint: ActivityHint? = null
    private var probeEventAt = 0L
    private val windowProbe = Runnable { inspectForeground(probeEventAt) }
    private var launch: ForegroundWindow? = null
    private var master = false
    private var observe = false
    private var windowMs = SkipStateMachine.DEFAULT_WINDOW_MS
    private var disabled = emptySet<String>()
    private var pending: Verification? = null
    private var lastScan = 0L
    private var tracedScanSession = -1L
    private var wasLocked = false
    private val windowTrace = WindowTrace("shizuku")

    private data class ActivityHint(val window: ForegroundWindow, val activity: String, val at: Long)

    private data class Verification(
        val command: ActionCommand,
        val window: ForegroundWindow,
        val evidence: TargetEvidence,
        val dispatchedAt: Long,
        val method: ClickMethod,
        val returnWindow: ForegroundWindow?
    ) {
        val crossWindowClosure = evidence.crossWindowClosureCheck(dispatchedAt)
        val closure = evidence.closureCheck(dispatchedAt)
        var lastObservation = TargetObservation.UNOBSERVED
    }

    override fun configure(
        client: ISkipHost,
        masterEnabled: Boolean,
        observeOnly: Boolean,
        windowMs: Long,
        disabledPackages: Array<String>
    ) {
        worker.post {
            if (host != null && host?.asBinder() != client.asBinder()) return@post
            master = masterEnabled
            observe = observeOnly
            this.windowMs = windowMs
            disabled = disabledPackages.toSet()
            if (host != null) return@post
            host = client
            val connected = runCatching {
                client.asBinder().linkToDeath(hostDied, 0)
                val session = connectAutomation()
                automation = session
                session.serviceInfo = session.serviceInfo.apply {
                    eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                }
                session.setOnAccessibilityEventListener { event ->
                    windowTrace.event(event, worker) { at, offset ->
                        if (master && observe && screenReady() && automation === session) {
                            displaySize()?.let { size ->
                                windowTrace.windows(at, offset, size.x, size.y, { session.rootInActiveWindow }, { session.windows })
                            }
                        }
                    }
                    val eventType = event.eventType
                    val pkg = event.packageName?.toString().orEmpty()
                    val id = event.windowId
                    val activity = event.className?.toString().orEmpty()
                    val full = event.isFullScreen
                    val at = event.eventTime
                    val foregroundChange = eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
                        event.mayChangeForeground()
                    event.recycle()
                    when {
                        eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> worker.post {
                            val signaled = ForegroundWindow(pkg, id)
                            val now = SystemClock.uptimeMillis()
                            if (!signaled.valid || now - at > MAX_EVENT_AGE_MS) return@post
                            if (!screenReady()) {
                                wasLocked = true
                                reset()
                                return@post
                            }
                            if (wasLocked) {
                                foreground.onUnlocked(now)
                                wasLocked = false
                            }
                            if (full && !PackagePolicy.isTransientOverlay(pkg, "", false) &&
                                !PackagePolicy.isExcludedPackage(pkg, disabled)
                            ) {
                                foreground.onResumeWindowHint(signaled, at)
                            }
                            if (PackagePolicy.isTransientOverlay(pkg, activity, full)) return@post
                            if (full && activity.startsWith("$pkg.")) activityHint = ActivityHint(signaled, activity, at)
                            foreground.onWindowSignal(id, now)
                            inspectForeground(at)
                        }
                        foregroundChange && pendingForeground.compareAndSet(false, true) -> worker.post {
                            pendingForeground.set(false)
                            val now = SystemClock.uptimeMillis()
                            if (now - at > MAX_EVENT_AGE_MS) return@post
                            foreground.onForegroundSignal(now)
                            inspectForeground(at)
                        }
                        eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                            pendingContent.compareAndSet(false, true) -> worker.post {
                            pendingContent.set(false)
                            inspectContent(ForegroundWindow(pkg, id), at)
                        }
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "UiAutomation connection failed", error)
                disconnectAutomation()
            }.isSuccess
            runCatching { client.onReady(connected) }
        }
    }

    private val pendingContent = AtomicBoolean()
    private val pendingForeground = AtomicBoolean()

    private fun connectAutomation(): UiAutomation {
        val connectionType = Class.forName("android.app.IUiAutomationConnection")
        val connection = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance()
        val instance = UiAutomation::class.java.getConstructor(Looper::class.java, connectionType)
            .newInstance(callbacks.looper, connection)
        UiAutomation::class.java.getMethod("connect", Int::class.javaPrimitiveType)
            .invoke(instance, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        return instance
    }

    private fun inspectContent(window: ForegroundWindow, at: Long) {
        runCatching {
            if (!master || !window.valid || pending != null) return
            val now = SystemClock.uptimeMillis()
            if (now - at > MAX_EVENT_AGE_MS) return
            if (launch == window && machine.isWithinWindow(now, windowMs) && now - lastScan >= PROBE_MS) {
                scan(window, at)
            } else if (!PackagePolicy.isExcludedPackage(window.packageName) &&
                foreground.reserveUnarmedContent(window, now)
            ) {
                inspectForeground(at, expectedWindow = window)
            }
        }.onFailure { reset() }
    }

    private fun inspectForeground(at: Long, expectedWindow: ForegroundWindow? = null) {
        runCatching {
            if (!master || pending != null) return
            if (!screenReady()) {
                wasLocked = true
                reset()
                return
            }
            val now = SystemClock.uptimeMillis()
            val size = displaySize() ?: return
            val root = activeRoot(preferFocused = true)
            var labRoot = false
            val unmapped = foreground.resumeWindow(now)
            val window = try {
                root?.let { node ->
                    identity(node).takeIf { candidate ->
                        candidate.valid && (expectedWindow == null || expectedWindow == candidate) &&
                            !PackagePolicy.isTransientOverlay(candidate.packageName, "", false) &&
                            node.isFocusedFullscreenApp(size.x, size.y, unmapped, ::confirmsFocus)
                    }?.also { candidate ->
                        if (candidate != foreground.verifiedWindow) {
                            labRoot = candidate.packageName == APP && node.hasVisibleNode("$APP:id/splashRoot")
                            if (!machine.armed && !PackagePolicy.isExcludedPackage(candidate.packageName, disabled) &&
                                node.hasOrdinaryWindow()
                            ) {
                                val copy = AccessibilityNodeInfo.obtain(node)
                                NodeExtractor.extract(copy, "", size.x, size.y, maxNodes = MAX_VERIFY_NODES).use { tree ->
                                    if (tree.snapshot.complete && tree.snapshot.isFullScreen &&
                                        !TargetEvidence.hasVisibleAdSignal(tree.snapshot)
                                    ) {
                                        foreground.rememberReturnWindow(candidate)
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                root?.recycle()
            }
            SkipTrace.log("shizuku") {
                "phase=root result=${if (window == null) "unavailable" else "available"} " +
                    "sameWindow=${window != null && window == foreground.verifiedWindow} armed=${machine.armed}"
            }
            updateWindowProbe(window, at)
            if (window == null) return
            val entered = foreground.recordFocusedApplication(window)
            if (entered) SkipTrace.log("shizuku") { "phase=foreground result=package_transition" }
            val hint = activityHint?.takeIf { it.window == window && now - it.at <= MAX_EVENT_AGE_MS }
            if (hint != null) activityHint = null
            val previousSession = machine.sessionId
            val moved = machine.moveWithinResume(window, now, windowMs, entered)
            if (moved) {
                launch = window
                SkipTrace.log("shizuku") { "phase=window_switch session=${machine.sessionId} kind=resume windowId=${window.id}" }
            } else if (hint != null || entered || window.packageName != machine.activePackage) {
                val activity = hint?.activity ?: when {
                    labRoot -> LAB
                    window.packageName == machine.activePackage -> machine.activeActivity
                    else -> ""
                }
                machine.onWindowStateChanged(
                    window.packageName, activity, true, if (entered) now else hint?.at ?: now,
                    userDisabledPackages = disabled, selfTestPackage = APP, selfTestActivity = LAB,
                    windowId = window.id, confirmedFullscreenActivity = hint != null,
                    confirmedForegroundEntry = entered
                )
                if (!machine.armed || machine.sessionId != previousSession) launch = window.takeIf { machine.armed }
            }
            val newSession = previousSession != machine.sessionId
            if (newSession) SkipTrace.log("shizuku") {
                "phase=window session=${machine.sessionId} armed=${machine.armed}"
            }
            if (window != launch || !machine.isWithinWindow(now, windowMs)) return
            if (newSession || moved || now - lastScan >= PROBE_MS) scan(window, at)
            if (newSession && pending == null && machine.isWithinWindow(SystemClock.uptimeMillis(), windowMs)) {
                val seenSession = machine.sessionId
                worker.postDelayed({
                    if (seenSession == machine.sessionId && pending == null && launch == window) {
                        runCatching { scan(window, at) }
                    }
                }, FIRST_TREE_RETRY_MS)
            }
        }.onFailure { reset() }
    }

    private fun updateWindowProbe(window: ForegroundWindow?, eventAt: Long) {
        worker.removeCallbacks(windowProbe)
        probeEventAt = eventAt
        foreground.observeRoot(window, SystemClock.uptimeMillis())?.let { worker.postAtTime(windowProbe, it) }
    }

    private fun scan(window: ForegroundWindow, eventAt: Long) {
        val now = SystemClock.uptimeMillis()
        if (!master || !machine.isWithinWindow(now, windowMs) || !screenReady() || window.packageName in disabled) return
        val size = displaySize() ?: return
        val root = activeRoot() ?: return
        if (identity(root) != window) {
            root.recycle()
            return
        }
        lastScan = now
        NodeExtractor.extract(root, machine.activeActivity, size.x, size.y).use { tree ->
            val snapshot = tree.snapshot
            if (snapshot.complete && snapshot.isFullScreen && root.hasOrdinaryWindow() &&
                !TargetEvidence.hasVisibleAdSignal(snapshot)
            ) {
                foreground.rememberReturnWindow(window)
            }
            if (window.packageName == APP &&
                snapshot.nodes.none { it.isVisibleToUser && it.viewId == "$APP:id/splashRoot" }) return
            val matchAt = SystemClock.uptimeMillis()
            val result = machine.evaluateSnapshot(
                snapshot, matchAt, master, observe, windowMs, userDisabledPackages = disabled,
                selfTestPackage = APP, selfTestActivity = LAB
            )
            if (tracedScanSession != machine.sessionId || result != EvaluationResult.NoOp) {
                tracedScanSession = machine.sessionId
                SkipTrace.log("shizuku") {
                    "phase=match session=${machine.sessionId} result=${result.javaClass.simpleName} " +
                        "complete=${snapshot.complete} nodes=${snapshot.nodes.size} elapsedMs=${matchAt - machine.launchAtMs}"
                }
            }
            when (result) {
                is EvaluationResult.ExecuteClick -> {
                    val evidence = TargetEvidence(snapshot, result.command.decision)
                    runAction(tree, result.command, evidence, window, size, eventAt)
                }
                is EvaluationResult.ObserveDetected -> Log.d(TAG, "Observation matched a skip control")
                else -> Unit
            }
        }
    }

    private fun runAction(
        tree: ExtractedTree,
        command: ActionCommand,
        evidence: TargetEvidence,
        window: ForegroundWindow,
        size: Point,
        eventAt: Long
    ) {
        val client = host ?: return
        var hasLease = false
        val result = try {
            ActionExecutor.executeWithTap(tree, command, canDispatch = {
                screenReady() && sameForeground(window) &&
                    machine.isWithinWindow(SystemClock.uptimeMillis(), windowMs) &&
                    (hasLease || client.beginAction(window.packageName, tree.windowId).also { hasLease = it })
            }) { bounds -> injectTap(bounds, size) }
        } finally {
            if (hasLease) runCatching { client.endAction() }
        }
        SkipTrace.log("shizuku") {
            "phase=input session=${command.sessionId} attempt=${command.attemptId} " +
                "accepted=${result.dispatched} reason=${result.rejectedBy ?: "-"}"
        }
        val refreshTarget = ActionExecutor.reacquisitionEvidence(result, tree, command)
        machine.onActionDispatched(command.sessionId, command.attemptId, result.actualMethod, result.dispatched, refreshTarget)
        runCatching {
            client.onInput(command.sessionId, command.attemptId, result.dispatched, result.rejectedBy?.name.orEmpty())
        }
        if (!result.dispatched) {
            if (hasLease) runCatching { client.onResult(VerifyOutcome.UNOBSERVED.name, 0L) }
            if (refreshTarget != null && machine.armed) {
                worker.post {
                    if (machine.sessionId == command.sessionId && pending == null && launch == window) {
                        runCatching { scan(window, eventAt) }
                    }
                }
            }
            return
        }
        val attempt = Verification(command, window, evidence, SystemClock.uptimeMillis(), result.actualMethod,
            foreground.returnWindowFor(window))
        pending = attempt
        worker.postDelayed({ verify(attempt, eventAt) }, PROBE_MS)
    }

    private fun verify(attempt: Verification, eventAt: Long) {
        if (pending !== attempt) return
        try {
            if (!screenReady()) {
                finish(attempt, VerifyOutcome.UNOBSERVED, eventAt)
                reset()
                return
            }
            val now = SystemClock.uptimeMillis()
            val size = displaySize()
            if (size != null) {
                if (android.os.Build.VERSION.SDK_INT >= 33) automation?.clearCache()
                activeRoot()?.let { root ->
                    val current = identity(root)
                    if (current == attempt.returnWindow) {
                        verifyReturn(attempt, root, current, size, eventAt, now)
                        return
                    }
                    attempt.crossWindowClosure.record(TargetObservation.UNOBSERVED, now)
                    if (current != attempt.window || current.packageName.isBlank()) {
                        if (current.valid && !PackagePolicy.isTransientOverlay(current.packageName, "", false) &&
                            root.isFocusedFullscreenApp(size.x, size.y, confirmFocus = ::confirmsFocus)
                        ) {
                            foreground.recordFocusedApplication(current)
                            foreground.observeRoot(current, now)
                        }
                        root.recycle()
                        if (!current.valid) {
                            attempt.lastObservation = TargetObservation.UNOBSERVED
                        } else {
                            finish(attempt, VerifyOutcome.NAVIGATED, eventAt)
                            machine.onNavigationObserved(current)
                            return
                        }
                    } else {
                        val refreshBy = if (android.os.Build.VERSION.SDK_INT < 33) now + LEGACY_REFRESH_MS else null
                        NodeExtractor.extract(root, machine.activeActivity, size.x, size.y,
                            maxNodes = MAX_VERIFY_NODES, refreshDeadlineMs = refreshBy).use { tree ->
                            val state = attempt.evidence.observe(tree.snapshot)
                            attempt.lastObservation = state
                            if (attempt.closure.record(state, now)) {
                                finish(attempt, VerifyOutcome.VERIFIED_CLOSED, eventAt)
                                return
                            }
                            if (state == TargetObservation.TARGET_PRESENT && attempt.method == ClickMethod.NODE_ACTION) {
                                finish(attempt, VerifyOutcome.STILL_PRESENT, eventAt, attempt.evidence)
                                scan(attempt.window, eventAt)
                                return
                            }
                        }
                    }
                } ?: run { attempt.lastObservation = TargetObservation.UNOBSERVED }
            }
            if (now - attempt.dispatchedAt >= VERIFY_MS) {
                val outcome = when (attempt.lastObservation) {
                    TargetObservation.TARGET_PRESENT, TargetObservation.AD_PRESENT -> VerifyOutcome.STILL_PRESENT
                    else -> VerifyOutcome.UNOBSERVED
                }
                finish(attempt, outcome, eventAt)
            } else {
                worker.postDelayed({ verify(attempt, eventAt) }, PROBE_MS)
            }
        } catch (_: Exception) {
            finish(attempt, VerifyOutcome.UNOBSERVED, eventAt)
        }
    }

    private fun verifyReturn(
        attempt: Verification,
        root: AccessibilityNodeInfo,
        current: ForegroundWindow,
        size: Point,
        eventAt: Long,
        now: Long
    ) {
        val returned = runCatching { automation?.windows?.showsReturnTo(attempt.window.id, current.id) == true }
            .getOrDefault(false)
        val state = if (returned) {
            val refreshBy = if (Build.VERSION.SDK_INT < 33) now + LEGACY_REFRESH_MS else null
            NodeExtractor.extract(root, "", size.x, size.y, maxNodes = MAX_VERIFY_NODES,
                refreshDeadlineMs = refreshBy).use { attempt.evidence.observeReturn(it.snapshot) }
        } else {
            root.recycle()
            TargetObservation.UNOBSERVED
        }
        val observedAt = SystemClock.uptimeMillis()
        if (attempt.crossWindowClosure.record(state, observedAt)) {
            finish(attempt, VerifyOutcome.VERIFIED_CLOSED, eventAt)
            foreground.recordFocusedApplication(current)
            machine.onNavigationObserved(current)
        } else if (observedAt - attempt.dispatchedAt >= TargetEvidence.RETURN_VERIFY_TIMEOUT_MS) {
            val result = if (state == TargetObservation.AD_PRESENT || state == TargetObservation.TARGET_PRESENT) {
                VerifyOutcome.STILL_PRESENT
            } else VerifyOutcome.NAVIGATED
            finish(attempt, result, eventAt)
            machine.onNavigationObserved(current)
        } else {
            worker.postDelayed({ verify(attempt, eventAt) }, PROBE_MS)
        }
    }

    private fun finish(attempt: Verification, outcome: VerifyOutcome, eventAt: Long, retry: TargetEvidence? = null) {
        if (pending !== attempt) return
        pending = null
        machine.finishVerification(attempt.command.sessionId, attempt.command.attemptId, outcome, retry)
        SkipTrace.log("shizuku") {
            "phase=verify session=${attempt.command.sessionId} attempt=${attempt.command.attemptId} " +
                "result=$outcome elapsedMs=${SystemClock.uptimeMillis() - attempt.dispatchedAt}"
        }
        runCatching { host?.onResult(outcome.name, (SystemClock.uptimeMillis() - eventAt).coerceAtLeast(0)) }
    }

    private fun injectTap(bounds: RectBounds, size: Point): ActionExecutor.ExecutionResult {
        val session = automation ?: return ActionExecutor.ExecutionResult(false, ClickMethod.GESTURE)
        val clipped = bounds.clippedToScreen(size.x, size.y)
            ?: return ActionExecutor.ExecutionResult(false, ClickMethod.GESTURE, ActionExecutor.Rejection.TARGET_OUTSIDE_SCREEN)
        val start = SystemClock.uptimeMillis()
        val x = clipped.centerX
        val y = clipped.centerY
        fun send(action: Int): Boolean {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            return try {
                session.injectInputEvent(event, true)
            } finally {
                event.recycle()
            }
        }
        val success = runCatching {
            if (!send(MotionEvent.ACTION_DOWN)) false else {
                var completed = false
                try {
                    completed = send(MotionEvent.ACTION_UP)
                    completed
                } finally {
                    if (!completed) runCatching { send(MotionEvent.ACTION_CANCEL) }
                }
            }
        }.getOrDefault(false)
        return ActionExecutor.ExecutionResult(success, ClickMethod.GESTURE,
            if (success) null else ActionExecutor.Rejection.PLATFORM_REJECTED, start, SystemClock.uptimeMillis())
    }

    private fun screenReady() = power?.isInteractive == true && keyguard?.isKeyguardLocked == false

    private fun displaySize(): Point? {
        val display = displays?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        return Point().also(display::getRealSize).takeIf { it.x > 1 && it.y > 1 }
    }

    private fun activeRoot(preferFocused: Boolean = false): AccessibilityNodeInfo? {
        val session = automation ?: return null
        val unmapped = foreground.resumeWindow(SystemClock.uptimeMillis())
        val root = session.rootInActiveWindow
        if (root != null) {
            val owner = runCatching { root.window }.getOrNull()
            val type = owner?.type
            val usable = try {
                root.windowId >= 0 && type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    (!preferFocused || owner?.isFocusedApp() == true)
            } finally {
                owner?.recycle()
            }
            if (usable) return root
            root.recycle()
            if (type != null && type != -1 && type != AccessibilityWindowInfo.TYPE_APPLICATION) return null
            if (type == -1) return unmapped?.let { session.windows.readFocusedUnmapped(it) }
        }
        val recovered = session.windows.withFocusedAppWindow { window ->
            val candidate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) window.getRoot(0) else window.root
            if (candidate == null || candidate.windowId == window.id) candidate else {
                candidate.recycle()
                null
            }
        }
        return recovered ?: unmapped?.let { session.windows.readFocusedUnmapped(it) }
    }

    private fun identity(root: AccessibilityNodeInfo) =
        ForegroundWindow(root.packageName?.toString().orEmpty(), root.windowId)

    private fun confirmsFocus(windowId: Int): Boolean =
        automation?.windows?.withFocusedAppWindow { it.id == windowId } == true

    private fun sameForeground(window: ForegroundWindow): Boolean {
        val root = activeRoot(preferFocused = true) ?: return false
        return try { identity(root) == window } finally { root.recycle() }
    }

    private fun reset() {
        machine = SkipStateMachine()
        foreground = ForegroundEvents()
        activityHint = null
        worker.removeCallbacks(windowProbe)
        launch = null
        pending = null
    }

    private fun disconnectAutomation() {
        val session = automation ?: return
        automation = null
        runCatching { session.setOnAccessibilityEventListener(null) }
        runCatching { UiAutomation::class.java.getMethod("disconnect").invoke(session) }
    }

    override fun destroy() {
        worker.post {
            disconnectAutomation()
            exitProcess(0)
        }
    }

    private companion object {
        const val TAG = "FlashSkipAutomation"
        const val APP = "com.flashskip"
        const val LAB = "$APP.TestSplashActivity"
        const val PROBE_MS = 28L
        const val FIRST_TREE_RETRY_MS = 85L
        const val VERIFY_MS = 360L
        const val LEGACY_REFRESH_MS = 90L
        const val MAX_EVENT_AGE_MS = 400L
        const val MAX_VERIFY_NODES = 400
    }
}
