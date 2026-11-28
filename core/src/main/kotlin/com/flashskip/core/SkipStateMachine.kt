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
        const val DEFAULT_COOLDOWN_MS = 125L
        const val DEFAULT_MAX_ATTEMPTS = 4
        private const val RELAYOUT_WAIT_MS = 540L
        private const val RELAYOUT_STABILITY_MS = 58L
        private const val PLACEHOLDER_OBSERVATION_MS = 1_600L
        private const val LATE_STARTUP_GRACE_MS = 4_000L
        private const val FLOATING_STARTUP_GRACE_MS = 6_000L
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
    private var pendingRetryTarget: TargetEvidence? = null
    private data class RetryLayout(val target: RectBounds, val click: RectBounds, val seenAt: Long)
    private var retryLayout: RetryLayout? = null
    private data class InitialLayout(val windowId: Int?, val target: NodeSnapshot, val viewport: FreeformViewport?,
                                     val startedAt: Long, val sampledAt: Long)
    private var initialLayout: InitialLayout? = null
    private var launchWindowId: Int? = null
    private var lateStartupEligible = false
    private var placeholderWindowId: Int? = null
    private var floatingPlaceholder = false
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
        confirmedForegroundEntry: Boolean = false,
        confirmedFloatingWindow: Boolean = false
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
        val newFloatingLaunch = confirmedFloatingWindow && windowId != null && windowId != launchWindowId
        val newSession = isNewPackage || newActivity || confirmedForegroundEntry || newFloatingLaunch
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
            pendingRetryTarget != null && newSession
        ) {
            disarmToPackage(pkg, className)
        } else if (newSession) {
            activePackage = pkg
            activeActivity = className
            val sameStartingWindow = lateStartupEligible && windowId != null && windowId == launchWindowId &&
                nowMs - launchAtMs <= PLACEHOLDER_OBSERVATION_MS
            startNewSession(nowMs, windowId, confirmedForegroundEntry,
                allowLateStartup = !isSelfTestAllowed &&
                    (isNewPackage || confirmedForegroundEntry || sameStartingWindow || newFloatingLaunch))
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
        if (launchWindowId != window.id) {
            placeholderWindowId = null
            floatingPlaceholder = false
        }
        launchWindowId = window.id
        return true
    }

    fun isLaunchWindow(window: ForegroundWindow): Boolean =
        window.packageName == activePackage && window.id == launchWindowId

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
        val retryTarget = pendingRetryTarget
        if (retryTarget != null && (observeOnly || !retryTarget.matchesWindow(pkg, snapshot.windowId))) {
            cancelRetry()
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

        if (pkg != activePackage || snapshot.freeform != null && snapshot.windowId != launchWindowId ||
            resumeSession && launchWindowId != null && snapshot.windowId != launchWindowId) {
            return EvaluationResult.NoOp
        }

        val sinceLaunch = nowMs - launchAtMs
        val activity = activeActivity.ifEmpty { snapshot.activityName }
        val regularDuration = regularDuration(windowMs, activity)
        if (!isWithinWindow(nowMs, windowMs, activity)) return expire(sinceLaunch)
        val placeholder = lateStartupEligible && snapshot.isLaunchPlaceholder()
        if (placeholder && sinceLaunch <= PLACEHOLDER_OBSERVATION_MS && snapshot.windowId == launchWindowId) {
            placeholderWindowId = snapshot.windowId
        }
        if (placeholderWindowId != null && snapshot.freeform != null) floatingPlaceholder = true
        val afterRegularWindow = sinceLaunch > regularDuration

        if (!observeOnly) {
            if (pendingAttempt != null) return EvaluationResult.NoOp
            if (attempts >= maxAttempts && retryTarget?.followsVisibleAd != true) {
                armed = false
                return EvaluationResult.NoOp
            }
            if (attempts > 0 && nowMs - lastClickAtMs < cooldownMs && (retryTarget == null || retryTarget.followsVisibleAd)) {
                return if (retryTarget == null) EvaluationResult.NoOp else EvaluationResult.AwaitStableLayout
            }
        } else {
            if (lastObserveLogAtMs > 0L && nowMs - lastObserveLogAtMs < cooldownMs) {
                return EvaluationResult.NoOp
            }
        }

        val decision = SkipMatcher.findBestMatch(snapshot, ruleIndex)
        val targetForStartup = decision?.let { snapshot.nodes[it.targetNodeIndex] }
        val floatingSdkClose = lateStartupEligible && placeholderWindowId != null && targetForStartup != null &&
            snapshot.nodes.firstOrNull()?.bounds?.let { frame ->
                TargetEvidence.provesFloatingSdkSplashClose(snapshot, targetForStartup, frame)
            } == true
        val provenStartupAd = lateStartupEligible && (decision?.provenStartupAd == true ||
            targetForStartup != null && snapshot.nodes.firstOrNull()?.bounds?.let { frame ->
                TargetEvidence.provesSplashCountdown(snapshot, targetForStartup, frame)
            } == true) || floatingSdkClose
        if (lateStartupEligible && snapshot.complete && !placeholder && !provenStartupAd) {
            val pendingAd = placeholderWindowId != null && snapshot.nodes.firstOrNull()?.bounds?.let { frame ->
                snapshot.nodes.any { TargetEvidence.provesSplashCountdown(snapshot, it, frame) }
            } == true
            if (!pendingAd) {
                lateStartupEligible = false
                placeholderWindowId = null
            }
        }
        if (afterRegularWindow) {
            if (snapshot.windowId == null || placeholderWindowId != snapshot.windowId) return expire(sinceLaunch)
            if (!provenStartupAd) return EvaluationResult.NoOp
        }
        if (retryTarget != null) {
            val proven = decision?.takeIf {
                retryTarget.allowsRetry(snapshot, snapshot.nodes[it.targetNodeIndex], it)
            }
            if (retryTarget.followsVisibleAd && proven == null) {
                if (nowMs - lastClickAtMs < RELAYOUT_WAIT_MS) return EvaluationResult.AwaitStableLayout
                cancelRetry()
                return EvaluationResult.NoOp
            }
            if (retryTarget.waitsForStableLayout) {
                if (nowMs - lastClickAtMs >= RELAYOUT_WAIT_MS) {
                    cancelRetry()
                    return EvaluationResult.NoOp
                }
                val candidate = proven?.let {
                    RetryLayout(it.targetBounds, snapshot.nodes[it.clickNodeIndex].bounds, nowMs)
                }
                val previous = retryLayout
                if (candidate == null || previous == null || previous.target != candidate.target ||
                    previous.click != candidate.click) {
                    retryLayout = candidate
                    return EvaluationResult.NoOp
                }
                if (nowMs - previous.seenAt < RELAYOUT_STABILITY_MS) return EvaluationResult.NoOp
            } else if (proven == null) {
                cancelRetry()
                return EvaluationResult.NoOp
            }
        }
        if (decision == null) {
            initialLayout = null
            return EvaluationResult.NoOp
        }

        if (observeOnly) {
            lastObserveLogAtMs = nowMs
            return EvaluationResult.ObserveDetected(pkg, decision, sinceLaunch)
        }
        val target = snapshot.nodes[decision.targetNodeIndex]
        val parent = snapshot.nodes.getOrNull(target.parentIndex)
        val nativeCountdown = !decision.nativeOnly && (decision.bannerNodeIndices.isNotEmpty() ||
            TargetEvidence.isSplashJumpCountdown(target))
        val nativeCloseOnly = decision.nativeOnly || nativeCountdown
        if (nativeCountdown && (!target.isClickable || decision.clickNodeIndex != target.index ||
                snapshot.nodes.firstOrNull()?.bounds?.let { TargetEvidence.provesSplashCountdown(snapshot, target, it) } != true)) {
            return EvaluationResult.NoOp
        }
        val nestedCountdown = decision.forceGestureOnly && TargetEvidence.isSplashCountdown(target) &&
            parent?.shortId == "${target.shortId}_area" && TargetEvidence.isSplashControlArea(parent.shortId)
        if (attempts == 0 && (nativeCloseOnly || nestedCountdown || afterRegularWindow && floatingSdkClose)) {
            val previous = initialLayout?.takeIf {
                it.windowId == snapshot.windowId && it.target.sameControlIdentity(target)
            }
            val startedAt = previous?.startedAt ?: nowMs
            if (nowMs - startedAt >= RELAYOUT_WAIT_MS) return EvaluationResult.NoOp
            if (previous == null || previous.target.bounds != target.bounds || previous.viewport != snapshot.freeform) {
                initialLayout = InitialLayout(snapshot.windowId, target, snapshot.freeform, startedAt, nowMs)
                return EvaluationResult.AwaitStableLayout
            }
            if (nowMs - previous.sampledAt < RELAYOUT_STABILITY_MS) return EvaluationResult.AwaitStableLayout
        }
        initialLayout = null

        val preferredMethod = when {
            nativeCloseOnly -> ClickMethod.NODE_ACTION
            retryTarget?.retriesNativeClick == true -> ClickMethod.NODE_ACTION
            retryTarget?.followsVisibleAd == true && attempts == 1 && decision.provenStartupAd &&
                decision.canClickNode && decision.matchedVia == "rule:sdk-layout-skip" -> ClickMethod.NODE_ACTION
            decision.forceGestureOnly -> ClickMethod.GESTURE
            decision.isShakeAd -> ClickMethod.GESTURE // Bypass ACTION_CLICK fake-success delay on shake ads
            retryTarget != null -> ClickMethod.GESTURE
            else -> ClickMethod.NODE_ACTION
        }

        attempts++
        attemptSeq++
        lastClickAtMs = nowMs
        pendingRetryTarget = null
        retryLayout = null
        val command = ActionCommand(
                sessionId = sessionId,
                attemptId = attemptSeq,
                packageName = pkg,
                decision = decision,
                preferredMethod = preferredMethod,
                allowImmediateGestureFallback = preferredMethod == ClickMethod.NODE_ACTION && !nativeCloseOnly &&
                    retryTarget?.retriesNativeClick != true,
                sinceLaunchMs = sinceLaunch,
                attemptNumber = attempts,
                retryTarget = retryTarget
        )
        pendingAttempt = PendingAttempt(command, preferredMethod)
        return EvaluationResult.ExecuteClick(command)
    }

    private fun WindowSnapshot.isLaunchPlaceholder(): Boolean =
        complete && (isFullScreen || freeform != null) && nodes.size in 1..16 &&
            nodes.none { it.isVisibleToUser && (it.isClickable || it.effectiveText.isNotEmpty()) }

    private fun expire(sinceLaunch: Long): EvaluationResult {
        armed = false
        if (lastOutOfWindowSessionLogged == sessionId) return EvaluationResult.NoOp
        lastOutOfWindowSessionLogged = sessionId
        return EvaluationResult.OutOfWindow(sinceLaunch)
    }

    fun isWithinWindow(nowMs: Long, windowMs: Long, activityName: String = activeActivity): Boolean {
        if (!armed) return false
        val regular = regularDuration(windowMs, activityName)
        val duration = if (placeholderWindowId == null) regular else {
            val standardDeadline = regular + LATE_STARTUP_GRACE_MS
            val floatingDeadline = if (floatingPlaceholder) minOf(regular + FLOATING_STARTUP_GRACE_MS, 12_000L) else regular
            minOf(maxOf(standardDeadline, floatingDeadline), MAX_WINDOW_MS)
        }
        return nowMs - launchAtMs <= duration
    }

    private fun regularDuration(windowMs: Long, activityName: String): Long {
        val configuredMs = windowMs.coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
        val activity = activityName.lowercase()
        val isSplash = activity.contains("splash") || activity.contains("advert") || activity.contains("launchad")
        return if (isSplash) maxOf(configuredMs, SPLASH_ACTIVITY_MIN_WINDOW_MS) else configuredMs
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
            pendingRetryTarget = refreshTargetBeforeInput?.takeIf {
                actualMethod == pending.command.preferredMethod &&
                    (actualMethod == ClickMethod.GESTURE || it.retriesNativeClick) &&
                    (it.followsVisibleAd || pending.command.attemptNumber == 1 && attempts < maxAttempts)
            }
            armed = pendingRetryTarget != null
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
        pendingRetryTarget = retryTarget.takeIf {
            outcome == VerifyOutcome.STILL_PRESENT && (it?.followsVisibleAd == true || attempts < maxAttempts &&
                pending.method == ClickMethod.NODE_ACTION && pending.command.allowImmediateGestureFallback)
        }
        armed = pendingRetryTarget != null
        return outcome
    }

    private fun pendingAttemptFor(sessionId: Long, attemptId: Long): PendingAttempt? =
        pendingAttempt?.takeIf { it.command.sessionId == sessionId && it.command.attemptId == attemptId }

    fun cancelRetry() {
        if (pendingRetryTarget == null) return
        pendingRetryTarget = null
        retryLayout = null
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
        pendingRetryTarget = null
        retryLayout = null
        initialLayout = null
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

    private fun startNewSession(
        nowMs: Long, windowId: Int? = null, resumed: Boolean = false, allowLateStartup: Boolean = false
    ) {
        sessionId++
        resumeSession = resumed
        launchWindowId = windowId
        lateStartupEligible = allowLateStartup
        placeholderWindowId = null
        floatingPlaceholder = false
        navigationDestination = null
        launchAtMs = nowMs
        lastClickAtMs = 0L
        attempts = 0
        armed = true
        pendingAttempt = null
        pendingRetryTarget = null
        retryLayout = null
        lastObserveLogAtMs = 0L
        initialLayout = null
    }

    private fun disarmToPackage(pkg: String, className: String) {
        sessionId++
        resumeSession = false
        launchWindowId = null
        lateStartupEligible = false
        placeholderWindowId = null
        floatingPlaceholder = false
        activePackage = pkg
        activeActivity = className
        armed = false
        attempts = 0
        pendingAttempt = null
        pendingRetryTarget = null
        retryLayout = null
        initialLayout = null
    }
}
