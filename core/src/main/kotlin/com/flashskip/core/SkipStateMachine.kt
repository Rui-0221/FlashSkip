package com.flashskip.core

/**
 * Deterministic, pure-Kotlin state machine governing launch windows, cooldowns,
 * attempt caps, fake-success escalation to gesture, and cross-app safe verification.
 */
class SkipStateMachine(
    private val ruleIndex: RuleIndex = RuleIndex.defaultBuiltin(),
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS
) {
    companion object {
        const val DEFAULT_WINDOW_MS = 6000L
        const val SPLASH_ACTIVITY_MIN_WINDOW_MS = 10000L
        const val MIN_WINDOW_MS = 3000L
        const val MAX_WINDOW_MS = 15000L
        const val DEFAULT_COOLDOWN_MS = 180L
        const val DEFAULT_MAX_ATTEMPTS = 4
    }

    var activePackage: String = ""
        private set
    var activeActivity: String = ""
        private set
    var armed: Boolean = false
        private set
    var launchAtMs: Long = 0L
        private set
    var lastClickAtMs: Long = 0L
        private set
    var attempts: Int = 0
        private set
    var sessionId: Long = 0L
        private set

    private var attemptSeq: Long = 0L
    private data class PendingAttempt(val command: ActionCommand, var method: ClickMethod)

    private var pendingAttempt: PendingAttempt? = null
    private var gestureRetryTarget: TargetEvidence? = null
    private var launchWindowId: Int? = null
    private var resumeSession = false
    private var navigationDestination: ForegroundWindow? = null
    private var lastObserveLogAtMs: Long = 0L
    private var lastOutOfWindowSessionLogged: Long = -1L

    /**
     * Handles a window-state transition.
     * - Transient overlays (IME, Dialog, PopupWindow, SystemUI) do NOT reset launch window/attempts.
     * - Switching into an excluded package (banking/system) immediately disarms the machine.
     * - Switching to a new app or a genuine full-screen Activity arms a new session.
     */
    fun onWindowStateChanged(
        pkg: String,
        className: String,
        isFullScreen: Boolean,
        nowMs: Long,
        userDisabledPackages: Set<String> = emptySet(),
        userAllowedPackages: Set<String> = emptySet(),
        selfTestPackage: String? = null,
        selfTestActivity: String? = null,
        windowId: Int? = null,
        confirmedFullscreenActivity: Boolean = false,
        confirmedForegroundEntry: Boolean = false
    ): VerifyOutcome {
        if (pkg.isBlank()) return VerifyOutcome.IGNORED_STALE

        if (PackagePolicy.isTransientOverlay(pkg, className, isFullScreen)) {
            return VerifyOutcome.IGNORED_STALE
        }
        if (navigationDestination != null && windowId != null) {
            if (!confirmedForegroundEntry && navigationDestination == ForegroundWindow(pkg, windowId)) {
                return VerifyOutcome.IGNORED_STALE
            }
            navigationDestination = null
        }

        val isNewPackage = pkg != activePackage
        val activityChanged = className.isNotEmpty() && className != activeActivity &&
            (className.endsWith("Activity", ignoreCase = true) ||
                className.startsWith("$pkg.") && isFullScreen)
        val sameActivityRelaunched = confirmedFullscreenActivity && className.isNotBlank() &&
            className == activeActivity && launchWindowId != null && windowId != null && windowId != launchWindowId
        val newActivity = activityChanged || sameActivityRelaunched
        val newSession = isNewPackage || newActivity || confirmedForegroundEntry
        val isSelfPackage = selfTestPackage != null && pkg == selfTestPackage
        val isSelfTestAllowed = isSelfPackage && className == selfTestActivity
        if (isSelfPackage && !isSelfTestAllowed && !isNewPackage && !activityChanged) {
            // Widget-class events in the test window must not disarm the test Activity.
            return VerifyOutcome.IGNORED_STALE
        }
        val verifyOutcome = if (pendingAttempt != null && newSession) {
            VerifyOutcome.NAVIGATED
        } else {
            VerifyOutcome.IGNORED_STALE
        }

        if (isSelfPackage && !isSelfTestAllowed) {
            disarmToPackage(pkg, className)
            return verifyOutcome
        }

        if (!isSelfTestAllowed &&
            PackagePolicy.isExcludedPackage(pkg, userDisabledPackages, userAllowedPackages)
        ) {
            disarmToPackage(pkg, className)
            return verifyOutcome
        }

        if (verifyOutcome == VerifyOutcome.NAVIGATED ||
            gestureRetryTarget != null && newSession
        ) {
            disarmToPackage(pkg, className)
        } else if (newSession) {
            activePackage = pkg
            activeActivity = className
            startNewSession(nowMs, windowId, confirmedForegroundEntry)
        }

        return verifyOutcome
    }

    /** Move the initial scan to a proved same-package window without renewing the deadline. */
    fun moveWithinResume(
        window: ForegroundWindow,
        nowMs: Long,
        windowMs: Long,
        confirmedEntry: Boolean = false
    ): Boolean {
        if ((!resumeSession && !confirmedEntry) || !window.valid || window.packageName != activePackage ||
            (!confirmedEntry && launchWindowId == window.id) || attempts != 0 || !isWithinWindow(nowMs, windowMs)
        ) return false
        resumeSession = true
        launchWindowId = window.id
        return true
    }

    /**
     * Evaluates a captured [WindowSnapshot] against current window/cooldown/attempt guards.
     */
    fun evaluateSnapshot(
        snapshot: WindowSnapshot,
        nowMs: Long,
        masterEnabled: Boolean,
        observeOnly: Boolean,
        windowMs: Long,
        userDisabledPackages: Set<String> = emptySet(),
        userAllowedPackages: Set<String> = emptySet(),
        selfTestPackage: String? = null,
        selfTestActivity: String? = null
    ): EvaluationResult {
        if (!masterEnabled || !armed) return EvaluationResult.NoOp

        val pkg = snapshot.packageName
        if (pkg.isBlank()) return EvaluationResult.NoOp
        val retryTarget = gestureRetryTarget
        if (retryTarget != null && (observeOnly || !retryTarget.matchesWindow(pkg, snapshot.windowId))) {
            cancelGestureRetry()
            return EvaluationResult.NoOp
        }

        val isSelfPackage = selfTestPackage != null && pkg == selfTestPackage
        if (isSelfPackage) {
            if (activeActivity != selfTestActivity && snapshot.activityName != selfTestActivity) {
                return EvaluationResult.NoOp
            }
        } else if (PackagePolicy.isExcludedPackage(pkg, userDisabledPackages, userAllowedPackages)) {
            armed = false
            return EvaluationResult.NoOp
        }

        if (pkg != activePackage || resumeSession && launchWindowId != null && snapshot.windowId != launchWindowId) {
            return EvaluationResult.NoOp
        }

        val sinceLaunch = nowMs - launchAtMs
        if (!isWithinWindow(nowMs, windowMs, activeActivity.ifEmpty { snapshot.activityName })) {
            armed = false
            return if (lastOutOfWindowSessionLogged != sessionId) {
                lastOutOfWindowSessionLogged = sessionId
                EvaluationResult.OutOfWindow(sinceLaunch)
            } else {
                EvaluationResult.NoOp
            }
        }

        if (!observeOnly) {
            if (pendingAttempt != null) return EvaluationResult.NoOp
            if (attempts >= maxAttempts) {
                armed = false
                return EvaluationResult.NoOp
            }
            if (gestureRetryTarget == null && attempts > 0 && nowMs - lastClickAtMs < cooldownMs) {
                return EvaluationResult.NoOp
            }
        } else {
            if (lastObserveLogAtMs > 0L && nowMs - lastObserveLogAtMs < cooldownMs) {
                return EvaluationResult.NoOp
            }
        }

        val decision = SkipMatcher.findBestMatch(snapshot, ruleIndex)
        if (retryTarget != null && (decision == null ||
                !retryTarget.allowsRetry(snapshot, snapshot.nodes[decision.targetNodeIndex]))
        ) {
            cancelGestureRetry()
            return EvaluationResult.NoOp
        }
        decision ?: return EvaluationResult.NoOp

        if (observeOnly) {
            lastObserveLogAtMs = nowMs
            return EvaluationResult.ObserveDetected(pkg, decision, sinceLaunch)
        }

        val preferredMethod = when {
            decision.forceGestureOnly -> ClickMethod.GESTURE
            decision.isShakeAd -> ClickMethod.GESTURE // Bypass ACTION_CLICK fake-success delay on shake ads
            retryTarget != null -> ClickMethod.GESTURE
            else -> ClickMethod.NODE_ACTION
        }

        attempts++
        attemptSeq++
        lastClickAtMs = nowMs
        gestureRetryTarget = null
        val command = ActionCommand(
                sessionId = sessionId,
                attemptId = attemptSeq,
                packageName = pkg,
                decision = decision,
                preferredMethod = preferredMethod,
                allowImmediateGestureFallback = (preferredMethod == ClickMethod.NODE_ACTION),
                sinceLaunchMs = sinceLaunch,
                attemptNumber = attempts,
                retryTarget = retryTarget
        )
        pendingAttempt = PendingAttempt(command, preferredMethod)
        return EvaluationResult.ExecuteClick(command)
    }

    fun isWithinWindow(nowMs: Long, windowMs: Long, activityName: String = activeActivity): Boolean {
        if (!armed) return false
        val configuredMs = windowMs.coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
        val activity = activityName.lowercase()
        val isSplash = activity.contains("splash") || activity.contains("advert") || activity.contains("launchad")
        val durationMs = if (isSplash) maxOf(configuredMs, SPLASH_ACTIVITY_MIN_WINDOW_MS) else configuredMs
        return nowMs - launchAtMs <= durationMs
    }

    /**
     * Records the actual method dispatched (in case NODE_ACTION returned false and fell back immediately).
     */
    fun onActionDispatched(
        sessionId: Long,
        attemptId: Long,
        actualMethod: ClickMethod,
        ok: Boolean,
        refreshTargetBeforeInput: TargetEvidence? = null
    ) {
        val pending = pendingAttemptFor(sessionId, attemptId) ?: return
        pending.method = actualMethod
        if (!ok) {
            pendingAttempt = null
            gestureRetryTarget = refreshTargetBeforeInput.takeIf {
                actualMethod == ClickMethod.GESTURE && pending.command.preferredMethod == ClickMethod.GESTURE &&
                    pending.command.attemptNumber == 1
            }
            armed = gestureRetryTarget != null
        }
    }

    fun finishVerification(
        sessionId: Long,
        attemptId: Long,
        outcome: VerifyOutcome,
        retryTarget: TargetEvidence? = null
    ): VerifyOutcome {
        val pending = pendingAttemptFor(sessionId, attemptId) ?: return VerifyOutcome.IGNORED_STALE
        if (outcome == VerifyOutcome.IGNORED_STALE) return outcome
        pendingAttempt = null
        gestureRetryTarget = retryTarget.takeIf {
            outcome == VerifyOutcome.STILL_PRESENT && pending.method == ClickMethod.NODE_ACTION
        }
        armed = gestureRetryTarget != null
        return outcome
    }

    private fun pendingAttemptFor(sessionId: Long, attemptId: Long): PendingAttempt? =
        pendingAttempt?.takeIf { it.command.sessionId == sessionId && it.command.attemptId == attemptId }

    fun cancelGestureRetry() {
        if (gestureRetryTarget == null) return
        gestureRetryTarget = null
        armed = false
    }

    fun onNavigationObserved(destination: ForegroundWindow) {
        if (!destination.valid) return
        disarmToPackage(destination.packageName, "")
        navigationDestination = destination
    }

    /**
     * Disarms immediately when the screen turns off so stale timestamps never carry over across sleep.
     */
    fun onScreenOff() {
        armed = false
        pendingAttempt = null
        gestureRetryTarget = null
    }

    /**
     * Re-arms when the user unlocks the phone so hot-start / resume splash ads
     * in the foreground app get a fresh launch window (respecting exclusion policy).
     */
    fun onUserPresent(
        foregroundPkg: String,
        nowMs: Long,
        userDisabledPackages: Set<String> = emptySet(),
        userAllowedPackages: Set<String> = emptySet(),
        selfTestPackage: String? = null
    ) {
        if (foregroundPkg.isBlank()) return
        if (selfTestPackage != null && foregroundPkg == selfTestPackage) {
            armed = false
            return
        }
        if (PackagePolicy.isExcludedPackage(foregroundPkg, userDisabledPackages, userAllowedPackages)) {
            disarmToPackage(foregroundPkg, "")
            return
        }
        activePackage = foregroundPkg
        startNewSession(nowMs, resumed = true)
    }

    private fun startNewSession(nowMs: Long, windowId: Int? = null, resumed: Boolean = false) {
        sessionId++
        resumeSession = resumed
        launchWindowId = windowId
        navigationDestination = null
        launchAtMs = nowMs
        lastClickAtMs = 0L
        attempts = 0
        armed = true
        pendingAttempt = null
        gestureRetryTarget = null
        lastObserveLogAtMs = 0L
    }

    private fun disarmToPackage(pkg: String, className: String) {
        sessionId++
        resumeSession = false
        launchWindowId = null
        activePackage = pkg
        activeActivity = className
        armed = false
        attempts = 0
        pendingAttempt = null
        gestureRetryTarget = null
    }
}
