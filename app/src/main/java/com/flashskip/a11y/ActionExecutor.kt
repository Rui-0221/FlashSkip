package com.flashskip.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.flashskip.core.ActionCommand
import com.flashskip.core.ClickMethod
import com.flashskip.core.FreeformViewport
import com.flashskip.core.NodeSnapshot
import com.flashskip.core.RectBounds
import com.flashskip.core.TargetEvidence
import com.flashskip.core.containsCenter
import com.flashskip.core.sameControlIdentity

/**
 * Executes skip clicks via node action or clamped screen-coordinate gesture,
 * keeping gesture coordinates strictly inside the matched skip button bounds.
 */
object ActionExecutor {

    enum class Rejection {
        TARGET_UNAVAILABLE, TARGET_WINDOW_CHANGED, TARGET_IDENTITY_CHANGED, TARGET_INACTIVE,
        TARGET_MOVED, TARGET_OCCLUDED, RETRY_TARGET_CHANGED, TARGET_OUTSIDE_SCREEN, FOREGROUND_CHANGED, PLATFORM_REJECTED
    }

    data class ExecutionResult(
        val dispatched: Boolean,
        val actualMethod: ClickMethod,
        val rejectedBy: Rejection? = null,
        val inputStartedAtMs: Long? = null,
        val inputReturnedAtMs: Long? = null
    )

    fun reacquisitionEvidence(
        result: ExecutionResult,
        tree: ExtractedTree,
        command: ActionCommand
    ): TargetEvidence? {
        if (result.dispatched) return null
        val snapshot = tree.snapshot
        val reason = result.rejectedBy
        val retry = command.retryTarget?.takeIf { it.followsVisibleAd }
        if (retry != null && (reason == Rejection.TARGET_UNAVAILABLE || reason == Rejection.TARGET_MOVED ||
                reason == Rejection.TARGET_OCCLUDED || reason == Rejection.PLATFORM_REJECTED ||
                reason == Rejection.FOREGROUND_CHANGED && snapshot.freeform != null)) return retry
        if (result.actualMethod == ClickMethod.NODE_ACTION &&
            (reason == Rejection.TARGET_UNAVAILABLE || reason == Rejection.PLATFORM_REJECTED)
        ) {
            TargetEvidence.forStructuredNativeRetry(snapshot, command.decision)?.let { return it }
        }
        if (result.inputStartedAtMs != null || command.attemptNumber != 1) return null
        if (result.actualMethod == ClickMethod.NODE_ACTION && command.preferredMethod == ClickMethod.NODE_ACTION &&
            (result.rejectedBy == Rejection.TARGET_UNAVAILABLE || result.rejectedBy == Rejection.TARGET_IDENTITY_CHANGED)
        ) return TargetEvidence.forNativeReacquisition(snapshot, command.decision)

        val floatingForegroundRecheck = snapshot.freeform != null && result.rejectedBy == Rejection.FOREGROUND_CHANGED
        val relayout = result.rejectedBy == Rejection.TARGET_MOVED || result.rejectedBy == Rejection.TARGET_OCCLUDED
        if (command.preferredMethod != ClickMethod.GESTURE ||
            (result.rejectedBy != Rejection.TARGET_UNAVAILABLE && !relayout && !floatingForegroundRecheck)
        ) return null
        val evidence = TargetEvidence(snapshot, command.decision,
            allowBoundedMovement = relayout || floatingForegroundRecheck,
            followsVisibleAd = floatingForegroundRecheck && command.decision.provenStartupAd)
        return evidence.takeIf {
            (result.rejectedBy != Rejection.TARGET_OCCLUDED || it.waitsForStableLayout) &&
                it.matches(snapshot.nodes[command.decision.targetNodeIndex])
        }
    }

    fun execute(
        service: AccessibilityService,
        tree: ExtractedTree,
        command: ActionCommand,
        screenWidth: Int,
        screenHeight: Int,
        callbackHandler: Handler,
        canDispatch: (RectBounds?) -> Boolean,
        onGestureOutcome: (sessionId: Long, attemptId: Long, completed: Boolean) -> Unit
    ): ExecutionResult = executeWithTap(tree, command, canDispatch) { bounds ->
        dispatchClampedTap(
            service, bounds, screenWidth, screenHeight, callbackHandler,
            horizontalFraction = if (command.retryTarget?.followsVisibleAd != true) .5f
                else when (command.attemptNumber % 3) { 2 -> .62f; 0 -> .38f; else -> .5f },
            onCompleted = { onGestureOutcome(command.sessionId, command.attemptId, true) },
            onCancelled = { onGestureOutcome(command.sessionId, command.attemptId, false) }
        )
    }

    private fun executeWithTap(
        tree: ExtractedTree,
        command: ActionCommand,
        canDispatch: (RectBounds?) -> Boolean,
        tap: (RectBounds) -> ExecutionResult
    ): ExecutionResult {
        val decision = command.decision

        if (command.preferredMethod == ClickMethod.NODE_ACTION) {
            val clickNode = tree.nodeAt(decision.clickNodeIndex)
            val viewport = tree.snapshot.freeform
            var clickBounds = tree.snapshot.nodes[decision.clickNodeIndex].bounds
            val nativeCountdown = if (!command.allowImmediateGestureFallback &&
                decision.targetNodeIndex == decision.clickNodeIndex
            ) TargetEvidence(tree.snapshot, decision, allowBoundedMovement = true).takeIf { it.waitsForStableLayout }
            else null
            if (viewport != null || tree.snapshot.behindTopWindow || tree.snapshot.obstructions.isNotEmpty() ||
                !command.allowImmediateGestureFallback) {
                var layoutMoved = false
                var liveAnchor: NodeSnapshot? = null
                for (index in setOf(decision.targetNodeIndex, decision.clickNodeIndex)) {
                    val live = tree.nodeAt(index)
                    if (live == null || !runCatching { live.refresh() }.getOrDefault(false)) {
                        return ExecutionResult(false, ClickMethod.NODE_ACTION, Rejection.TARGET_UNAVAILABLE)
                    }
                    val original = tree.snapshot.nodes[index]
                    val current = tree.currentTarget(index, live, Rect().also(live::getBoundsInScreen))
                    if (live.windowId != tree.windowId || live.packageName?.toString() != command.packageName ||
                        !live.isVisibleToUser || !live.isEnabled || !current.sameControlIdentity(original) ||
                        index == decision.clickNodeIndex && !live.isClickable ||
                        viewport != null && viewport.screenTarget(current.bounds) == null) {
                        return ExecutionResult(false, ClickMethod.NODE_ACTION, Rejection.TARGET_IDENTITY_CHANGED)
                    }
                    if (current.bounds != original.bounds && nativeCountdown?.matches(current, viewport) != true) {
                        layoutMoved = true
                    }
                    if (index == decision.clickNodeIndex) {
                        clickBounds = current.bounds
                        liveAnchor = current
                    }
                }
                if (layoutMoved) {
                    val proof = command.retryTarget?.takeIf(TargetEvidence::retriesNativeClick)
                        ?: TargetEvidence.forNativeReacquisition(tree.snapshot, decision)
                    val anchor = liveAnchor
                    if (proof == null || anchor == null || clickNode == null ||
                        !confirmsLiveNativePill(proof, anchor, clickNode, command.packageName, viewport)) {
                        return ExecutionResult(false, ClickMethod.NODE_ACTION, Rejection.TARGET_IDENTITY_CHANGED)
                    }
                }
            }
            if (clickNode != null && (clickNode.windowId != tree.windowId ||
                    clickNode.packageName?.toString() != command.packageName)
            ) {
                return ExecutionResult(false, ClickMethod.NODE_ACTION, Rejection.TARGET_WINDOW_CHANGED)
            }
            if (clickNode != null) {
                val bannerTarget = if (nativeCountdown != null) clickBounds else decision.targetBounds
                if (!bannerClear(tree, command, bannerTarget)) {
                    return ExecutionResult(false, ClickMethod.NODE_ACTION, Rejection.TARGET_OCCLUDED)
                }
                if (!canDispatch(clickBounds)) {
                    return ExecutionResult(false, ClickMethod.NODE_ACTION, Rejection.FOREGROUND_CHANGED)
                }
                val startedAt = SystemClock.uptimeMillis()
                val accepted = try {
                    clickNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } catch (_: Exception) {
                    false
                }
                val returnedAt = SystemClock.uptimeMillis()
                if (accepted || !command.allowImmediateGestureFallback) {
                    return ExecutionResult(
                        accepted, ClickMethod.NODE_ACTION,
                        if (accepted) null else Rejection.PLATFORM_REJECTED,
                        startedAt, returnedAt
                    )
                }
            }
            if (clickNode == null && !command.allowImmediateGestureFallback) {
                return ExecutionResult(false, ClickMethod.NODE_ACTION, Rejection.TARGET_UNAVAILABLE)
            }
        }

        val target = tree.nodeAt(decision.targetNodeIndex)
            ?: return ExecutionResult(false, ClickMethod.GESTURE, Rejection.TARGET_UNAVAILABLE)
        val refreshed = try {
            target.refresh()
        } catch (_: Exception) {
            false
        }
        if (!refreshed) {
            return ExecutionResult(false, ClickMethod.GESTURE, Rejection.TARGET_UNAVAILABLE)
        }
        val bounds = Rect().also(target::getBoundsInScreen)
        val currentTarget = tree.currentTarget(decision.targetNodeIndex, target, bounds)
        val originalTarget = tree.snapshot.nodes[decision.targetNodeIndex]
        val retry = command.retryTarget
        val rejection = when {
            target.windowId != tree.windowId || target.packageName?.toString() != command.packageName ->
                Rejection.TARGET_WINDOW_CHANGED
            !currentTarget.sameControlIdentity(originalTarget) -> Rejection.TARGET_IDENTITY_CHANGED
            !currentTarget.isVisibleToUser || !currentTarget.isEnabled -> Rejection.TARGET_INACTIVE
            originalTarget.viewId.isBlank() && originalTarget.effectiveText.isBlank() &&
                (!target.isClickable || currentTarget.bounds != originalTarget.bounds && retry?.followsVisibleAd != true) ->
                Rejection.TARGET_IDENTITY_CHANGED
            !currentTarget.bounds.containsCenter(decision.targetBounds) ->
                Rejection.TARGET_MOVED
            retry != null && !retry.matches(currentTarget, tree.snapshot.freeform) -> Rejection.RETRY_TARGET_CHANGED
            else -> null
        }
        if (rejection != null) return ExecutionResult(false, ClickMethod.GESTURE, rejection)
        if (!bannerClear(tree, command)) return ExecutionResult(false, ClickMethod.GESTURE, Rejection.TARGET_OCCLUDED)
        if (!canDispatch(null)) {
            return ExecutionResult(false, ClickMethod.GESTURE, Rejection.FOREGROUND_CHANGED)
        }
        val viewport = tree.snapshot.freeform
        val intended = decision.targetBounds
        val liveArea = currentTarget.bounds
        val bounded = RectBounds(maxOf(intended.left, liveArea.left), maxOf(intended.top, liveArea.top),
            minOf(intended.right, liveArea.right), minOf(intended.bottom, liveArea.bottom))
        val physical = if (viewport == null) bounded else viewport.screenTarget(bounded)
            ?: return ExecutionResult(false, ClickMethod.GESTURE, Rejection.TARGET_OUTSIDE_SCREEN)
        return tap(physical)
    }

    @Suppress("DEPRECATION")
    private fun confirmsLiveNativePill(proof: TargetEvidence, anchor: NodeSnapshot, parent: AccessibilityNodeInfo,
                                      packageName: String, viewport: FreeformViewport?): Boolean {
        if (parent.childCount != 2) return false
        val children = ArrayList<NodeSnapshot>(2)
        for (i in 0 until parent.childCount) {
            val child = runCatching { parent.getChild(i) }.getOrNull() ?: return false
            try {
                if (!runCatching { child.refresh() }.getOrDefault(false) || child.windowId != parent.windowId ||
                    child.packageName?.toString() != packageName) return false
                val physical = Rect().also(child::getBoundsInScreen).bounds()
                children.add(NodeSnapshot(i, anchor.index, child.className?.toString().orEmpty(),
                    child.viewIdResourceName.orEmpty(), child.text?.toString().orEmpty(),
                    child.contentDescription?.toString().orEmpty(), viewport?.local(physical) ?: physical,
                    child.isVisibleToUser, child.isClickable, child.isEnabled, anchor.depth + 1))
            } finally {
                child.recycle()
            }
        }
        return proof.confirmsNativePillRelayout(anchor, children)
    }

    private fun bannerClear(tree: ExtractedTree, command: ActionCommand, target: RectBounds = command.decision.targetBounds): Boolean {
        for (index in command.decision.bannerNodeIndices) {
            val banner = tree.nodeAt(index) ?: return false
            if (!runCatching { banner.refresh() }.getOrDefault(false) || banner.windowId != tree.windowId ||
                banner.packageName?.toString() != command.packageName) return false
            if (!banner.isVisibleToUser) continue
            val bounds = Rect().also(banner::getBoundsInScreen).bounds()
            val local = tree.snapshot.freeform?.local(bounds) ?: bounds
            if (if (command.decision.nativeOnly) local.overlaps(target) else local.containsCenter(target)) return false
        }
        return true
    }

    private fun dispatchClampedTap(
        service: AccessibilityService,
        bounds: com.flashskip.core.RectBounds,
        screenWidth: Int,
        screenHeight: Int,
        callbackHandler: Handler,
        horizontalFraction: Float,
        onCompleted: () -> Unit,
        onCancelled: () -> Unit
    ): ExecutionResult {
        val clipped = bounds.clippedToScreen(screenWidth, screenHeight)
            ?: return ExecutionResult(false, ClickMethod.GESTURE, Rejection.TARGET_OUTSIDE_SCREEN)
        val maxX = (screenWidth - 1).coerceAtLeast(1).toFloat()
        val maxY = (screenHeight - 1).coerceAtLeast(1).toFloat()
        val x = (clipped.left + clipped.width * horizontalFraction).coerceIn(1f, maxX)
        val y = clipped.centerY.coerceIn(1f, maxY)

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 35L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = onCompleted()

            override fun onCancelled(gestureDescription: GestureDescription?) = onCancelled()
        }
        val startedAt = SystemClock.uptimeMillis()
        val accepted = try {
            service.dispatchGesture(gesture, cb, callbackHandler)
        } catch (_: Exception) {
            false
        }
        return ExecutionResult(
            accepted, ClickMethod.GESTURE,
            if (accepted) null else Rejection.PLATFORM_REJECTED,
            startedAt, SystemClock.uptimeMillis()
        )
    }
}
