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
        TARGET_MOVED, RETRY_TARGET_CHANGED, TARGET_OUTSIDE_SCREEN, FOREGROUND_CHANGED, PLATFORM_REJECTED
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
        val snapshot = tree.snapshot
        val floatingForegroundRecheck = snapshot.freeform != null && result.rejectedBy == Rejection.FOREGROUND_CHANGED
        if (result.dispatched || result.inputStartedAtMs != null || command.preferredMethod != ClickMethod.GESTURE ||
            command.attemptNumber != 1 ||
            (result.rejectedBy != Rejection.TARGET_UNAVAILABLE && result.rejectedBy != Rejection.TARGET_MOVED &&
                !floatingForegroundRecheck)
        ) return null
        val evidence = TargetEvidence(snapshot, command.decision,
            allowBoundedMovement = result.rejectedBy == Rejection.TARGET_MOVED || floatingForegroundRecheck)
        return evidence.takeIf { it.matches(snapshot.nodes[command.decision.targetNodeIndex]) }
    }

    fun execute(
        service: AccessibilityService,
        tree: ExtractedTree,
        command: ActionCommand,
        screenWidth: Int,
        screenHeight: Int,
        callbackHandler: Handler,
        canDispatch: () -> Boolean,
        onGestureOutcome: (sessionId: Long, attemptId: Long, completed: Boolean) -> Unit
    ): ExecutionResult = executeWithTap(tree, command, canDispatch) { bounds ->
        dispatchClampedTap(
            service, bounds, screenWidth, screenHeight, callbackHandler,
            onCompleted = { onGestureOutcome(command.sessionId, command.attemptId, true) },
            onCancelled = { onGestureOutcome(command.sessionId, command.attemptId, false) }
        )
    }

    fun executeWithTap(
        tree: ExtractedTree,
        command: ActionCommand,
        canDispatch: () -> Boolean,
        tap: (RectBounds) -> ExecutionResult
    ): ExecutionResult {
        val decision = command.decision

        if (command.preferredMethod == ClickMethod.NODE_ACTION) {
            val clickNode = tree.nodeAt(decision.clickNodeIndex)
            val viewport = tree.snapshot.freeform
            if (viewport != null) {
                for (index in setOf(decision.targetNodeIndex, decision.clickNodeIndex)) {
                    val live = tree.nodeAt(index)
                    if (live == null || !runCatching { live.refresh() }.getOrDefault(false)) {
                        return ExecutionResult(false, ClickMethod.NODE_ACTION, Rejection.TARGET_UNAVAILABLE)
                    }
                    val original = tree.snapshot.nodes[index]
                    val current = tree.currentTarget(index, live, Rect().also(live::getBoundsInScreen))
                    if (live.windowId != tree.windowId || live.packageName?.toString() != command.packageName ||
                        !live.isVisibleToUser || !live.isEnabled || !current.sameControlIdentity(original) ||
                        current.bounds != original.bounds || viewport.screenTarget(current.bounds) == null) {
                        return ExecutionResult(false, ClickMethod.NODE_ACTION, Rejection.TARGET_IDENTITY_CHANGED)
                    }
                }
            }
            if (clickNode != null && (clickNode.windowId != tree.windowId ||
                    clickNode.packageName?.toString() != command.packageName)
            ) {
                return ExecutionResult(false, ClickMethod.NODE_ACTION, Rejection.TARGET_WINDOW_CHANGED)
            }
            if (!canDispatch()) {
                return ExecutionResult(false, ClickMethod.NODE_ACTION, Rejection.FOREGROUND_CHANGED)
            }
            if (clickNode != null) {
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
                currentTarget.bounds != originalTarget.bounds -> Rejection.TARGET_IDENTITY_CHANGED
            !currentTarget.bounds.containsCenter(decision.targetBounds) ->
                Rejection.TARGET_MOVED
            retry != null && !retry.matches(currentTarget, tree.snapshot.freeform) -> Rejection.RETRY_TARGET_CHANGED
            else -> null
        }
        if (rejection != null) return ExecutionResult(false, ClickMethod.GESTURE, rejection)
        if (!canDispatch()) {
            return ExecutionResult(false, ClickMethod.GESTURE, Rejection.FOREGROUND_CHANGED)
        }
        val viewport = tree.snapshot.freeform
        val physical = if (viewport == null) decision.targetBounds else viewport.screenTarget(decision.targetBounds)
            ?: return ExecutionResult(false, ClickMethod.GESTURE, Rejection.TARGET_OUTSIDE_SCREEN)
        return tap(physical)
    }

    private fun dispatchClampedTap(
        service: AccessibilityService,
        bounds: com.flashskip.core.RectBounds,
        screenWidth: Int,
        screenHeight: Int,
        callbackHandler: Handler,
        onCompleted: () -> Unit,
        onCancelled: () -> Unit
    ): ExecutionResult {
        val clipped = bounds.clippedToScreen(screenWidth, screenHeight)
            ?: return ExecutionResult(false, ClickMethod.GESTURE, Rejection.TARGET_OUTSIDE_SCREEN)
        val maxX = (screenWidth - 1).coerceAtLeast(1).toFloat()
        val maxY = (screenHeight - 1).coerceAtLeast(1).toFloat()
        val x = clipped.centerX.coerceIn(1f, maxX)
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
