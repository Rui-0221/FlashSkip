package com.flashskip.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.view.accessibility.AccessibilityNodeInfo
import com.flashskip.core.ActionCommand
import com.flashskip.core.ClickMethod
import com.flashskip.data.SkipLog

/**
 * Executes skip clicks via node action or clamped screen-coordinate gesture,
 * keeping gesture coordinates strictly inside the matched skip button bounds.
 */
object ActionExecutor {

    data class ExecutionResult(
        val dispatched: Boolean,
        val actualMethod: ClickMethod
    )

    fun execute(
        service: AccessibilityService,
        tree: ExtractedTree,
        command: ActionCommand,
        screenWidth: Int,
        screenHeight: Int,
        callbackHandler: Handler,
        canDispatch: () -> Boolean,
        onGestureOutcome: (sessionId: Long, attemptId: Long, completed: Boolean) -> Unit
    ): ExecutionResult {
        val decision = command.decision

        if (command.preferredMethod == ClickMethod.NODE_ACTION) {
            val clickNode = tree.nodeAt(decision.clickNodeIndex)
            val nodeOk = clickNode != null && canDispatch() &&
                try {
                    clickNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } catch (_: Exception) {
                    false
                }
            if (nodeOk) {
                return ExecutionResult(dispatched = true, actualMethod = ClickMethod.NODE_ACTION)
            }
            if (!command.allowImmediateGestureFallback) {
                return ExecutionResult(dispatched = false, actualMethod = ClickMethod.NODE_ACTION)
            }
        }

        val target = tree.nodeAt(decision.targetNodeIndex)
        val targetStillAtMatch = if (target == null) false else try {
            if (!target.refresh() || !target.isVisibleToUser || !target.isEnabled) {
                false
            } else {
                val bounds = Rect().also(target::getBoundsInScreen)
                val retry = command.retryTarget
                bounds.contains(decision.targetBounds.centerX.toInt(), decision.targetBounds.centerY.toInt()) &&
                    (retry == null || (retry.matchesWindow(target.packageName?.toString().orEmpty(), target.windowId) &&
                        retry.matches(tree.currentTarget(decision.targetNodeIndex, target, bounds))))
            }
        } catch (_: Exception) {
            false
        }
        if (!targetStillAtMatch || !canDispatch()) {
            return ExecutionResult(dispatched = false, actualMethod = ClickMethod.GESTURE)
        }
        val gestureOk = dispatchClampedTap(
            service = service,
            bounds = decision.targetBounds,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            callbackHandler = callbackHandler,
            onCompleted = { onGestureOutcome(command.sessionId, command.attemptId, true) },
            onCancelled = { onGestureOutcome(command.sessionId, command.attemptId, false) }
        )
        return ExecutionResult(dispatched = gestureOk, actualMethod = ClickMethod.GESTURE)
    }

    private fun dispatchClampedTap(
        service: AccessibilityService,
        bounds: com.flashskip.core.RectBounds,
        screenWidth: Int,
        screenHeight: Int,
        callbackHandler: Handler,
        onCompleted: () -> Unit,
        onCancelled: () -> Unit
    ): Boolean {
        val clipped = bounds.clippedToScreen(screenWidth, screenHeight) ?: return false
        val maxX = (screenWidth - 1).coerceAtLeast(1).toFloat()
        val maxY = (screenHeight - 1).coerceAtLeast(1).toFloat()
        val x = clipped.centerX.coerceIn(1f, maxX)
        val y = clipped.centerY.coerceIn(1f, maxY)

        return try {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 35L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val cb = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    SkipLog.log("GESTURE_OK at=${x.toInt()},${y.toInt()}")
                    onCompleted()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    SkipLog.log("GESTURE_CANCELLED at=${x.toInt()},${y.toInt()}")
                    onCancelled()
                }
            }
            service.dispatchGesture(gesture, cb, callbackHandler)
        } catch (e: Exception) {
            SkipLog.log("GESTURE_ERROR ${e.message}")
            false
        }
    }
}
