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
        const val DEFAULT_COOLDOWN_MS = 450L
        const val DEFAULT_MAX_ATTEMPTS = 4
        const val VERIFY_WINDOW_MS = 2000L
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
    private var pendingVerifySessionId: Long = -1L
    private var pendingVerifyAttemptId: Long = -1L
    private var pendingVerifyPkg: String = ""
    private var pendingVerifyMethod: ClickMethod? = null
    private var previousNodeActionHadNoEffect: Boolean = false
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
        selfTestActivity: String? = null
    ): VerifyOutcome {
        if (pkg.isBlank()) return VerifyOutcome.IGNORED_STALE

        if (PackagePolicy.isTransientOverlay(pkg, className, isFullScreen)) {
            return VerifyOutcome.IGNORED_STALE
        }

        // Check if current transition completes a pending verification on the same package
        var verifyOutcome = VerifyOutcome.IGNORED_STALE
        if (pendingVerifyAttemptId >= 0 &&
            pkg == pendingVerifyPkg &&
            nowMs - lastClickAtMs < VERIFY_WINDOW_MS &&
            className != activeActivity
        ) {
            verifyOutcome = VerifyOutcome.VERIFIED_CLOSED
            clearPendingVerify()
            armed = false
        }

        val isSelfPackage = selfTestPackage != null && pkg == selfTestPackage
        val isSelfTestAllowed = isSelfPackage && className == selfTestActivity

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

        val isNewPackage = pkg != activePackage
        val looksLikeActivity = isFullScreen ||
            isSelfTestAllowed ||
            className.endsWith("Activity", ignoreCase = true) ||
            className.contains(".splash.", ignoreCase = true)
        val isNewFullScreenActivity = !isNewPackage &&
            looksLikeActivity &&
            className.isNotEmpty() &&
            className != activeActivity

        if (isNewPackage || isNewFullScreenActivity) {
            // If we just verified closure via this exact Activity transition, stay disarmed
            // unless we switched to a brand-new package.
            val shouldArm = isNewPackage || verifyOutcome != VerifyOutcome.VERIFIED_CLOSED
            activePackage = pkg
            activeActivity = className
            if (shouldArm) {
                startNewSession(nowMs)
            } else {
                armed = false
            }
        } else if (className.isNotEmpty()) {
            activeActivity = className
        }

        return verifyOutcome
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

        val isSelfPackage = selfTestPackage != null && pkg == selfTestPackage
        if (isSelfPackage) {
            if (activeActivity != selfTestActivity && snapshot.activityName != selfTestActivity) {
                return EvaluationResult.NoOp
            }
        } else if (PackagePolicy.isExcludedPackage(pkg, userDisabledPackages, userAllowedPackages)) {
            armed = false
            return EvaluationResult.NoOp
        }

        // If root package disagrees with activePackage on a non-transient window (e.g. cold-start mismatch),
        // align activePackage to the authoritative root package.
        if (pkg != activePackage) {
            activePackage = pkg
            startNewSession(nowMs)
        }

        val clampedWindowMs = windowMs.coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
        val actLower = activeActivity.ifEmpty { snapshot.activityName }.lowercase()
        val isSplashActivity = actLower.contains("splash") ||
            actLower.contains("advert") ||
            actLower.contains("launchad")
        val effectiveWindowMs = if (isSplashActivity) {
            maxOf(clampedWindowMs, SPLASH_ACTIVITY_MIN_WINDOW_MS)
        } else {
            clampedWindowMs
        }
        val sinceLaunch = nowMs - launchAtMs
        if (sinceLaunch > effectiveWindowMs) {
            armed = false
            return if (lastOutOfWindowSessionLogged != sessionId) {
                lastOutOfWindowSessionLogged = sessionId
                EvaluationResult.OutOfWindow(sinceLaunch)
            } else {
                EvaluationResult.NoOp
            }
        }

        if (!observeOnly) {
            if (attempts >= maxAttempts) {
                armed = false
                return EvaluationResult.NoOp
            }
            if (lastClickAtMs > 0L && nowMs - lastClickAtMs < cooldownMs) {
                return EvaluationResult.NoOp
            }
        } else {
            if (lastObserveLogAtMs > 0L && nowMs - lastObserveLogAtMs < cooldownMs) {
                return EvaluationResult.NoOp
            }
        }

        val decision = SkipMatcher.findBestMatch(snapshot, ruleIndex) ?: return EvaluationResult.NoOp

        if (observeOnly) {
            lastObserveLogAtMs = nowMs
            return EvaluationResult.ObserveDetected(pkg, decision, sinceLaunch)
        }

        val preferredMethod = when {
            decision.forceGestureOnly -> ClickMethod.GESTURE
            decision.isShakeAd -> ClickMethod.GESTURE // Bypass ACTION_CLICK fake-success delay on shake ads
            previousNodeActionHadNoEffect -> ClickMethod.GESTURE // Escalate after fake-success ACTION_CLICK
            else -> ClickMethod.NODE_ACTION
        }

        attempts++
        attemptSeq++
        lastClickAtMs = nowMs
        pendingVerifySessionId = sessionId
        pendingVerifyAttemptId = attemptSeq
        pendingVerifyPkg = pkg
        pendingVerifyMethod = preferredMethod

        return EvaluationResult.ExecuteClick(
            ActionCommand(
                sessionId = sessionId,
                attemptId = attemptSeq,
                packageName = pkg,
                decision = decision,
                preferredMethod = preferredMethod,
                allowImmediateGestureFallback = (preferredMethod == ClickMethod.NODE_ACTION),
                sinceLaunchMs = sinceLaunch,
                attemptNumber = attempts
            )
        )
    }

    /**
     * Records the actual method dispatched (in case NODE_ACTION returned false and fell back immediately).
     */
    fun onActionDispatched(sessionId: Long, attemptId: Long, actualMethod: ClickMethod, ok: Boolean) {
        if (sessionId != this.sessionId || attemptId != pendingVerifyAttemptId) return
        pendingVerifyMethod = actualMethod
        if (!ok) {
            clearPendingVerify()
        }
    }

    /**
     * Evaluates delayed verification. Protects against stale cross-app contamination:
     * only updates state if [sessionId] matches, and if the foreground package changed away
     * from [clickedPkg], closes verification without disarming the new foreground app.
     */
    fun onVerifyCheck(
        sessionId: Long,
        attemptId: Long,
        clickedPkg: String,
        currentForegroundPkg: String?,
        stillHasSkipTarget: Boolean
    ): VerifyOutcome {
        if (attemptId != pendingVerifyAttemptId) {
            return VerifyOutcome.IGNORED_STALE
        }
        val sameSession = (sessionId == this.sessionId)
        val method = pendingVerifyMethod
        clearPendingVerify()

        // User or ad closed the splash and moved to another app/home screen
        if (currentForegroundPkg != null && currentForegroundPkg != clickedPkg) {
            if (sameSession) armed = false
            return VerifyOutcome.VERIFIED_CLOSED
        }

        if (!sameSession) {
            return VerifyOutcome.IGNORED_STALE
        }

        if (!stillHasSkipTarget) {
            armed = false
            previousNodeActionHadNoEffect = false
            return VerifyOutcome.VERIFIED_CLOSED
        }

        // Still present! If we used NODE_ACTION (which returned true as a fake-success),
        // escalate next attempt directly to GESTURE.
        if (method == ClickMethod.NODE_ACTION) {
            previousNodeActionHadNoEffect = true
        }
        return VerifyOutcome.STILL_PRESENT
    }

    /**
     * Disarms immediately when the screen turns off so stale timestamps never carry over across sleep.
     */
    fun onScreenOff() {
        armed = false
        clearPendingVerify()
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
        startNewSession(nowMs)
    }

    private fun startNewSession(nowMs: Long) {
        sessionId++
        launchAtMs = nowMs
        lastClickAtMs = 0L
        attempts = 0
        armed = true
        previousNodeActionHadNoEffect = false
        clearPendingVerify()
    }

    private fun disarmToPackage(pkg: String, className: String) {
        sessionId++
        activePackage = pkg
        activeActivity = className
        armed = false
        attempts = 0
        previousNodeActionHadNoEffect = false
        clearPendingVerify()
    }

    private fun clearPendingVerify() {
        pendingVerifySessionId = -1L
        pendingVerifyAttemptId = -1L
        pendingVerifyPkg = ""
        pendingVerifyMethod = null
    }
}
