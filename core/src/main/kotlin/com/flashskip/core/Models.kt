package com.flashskip.core

/**
 * Screen-space rectangle representing a node or window bounds in pixels.
 */
data class RectBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Long get() = width.toLong() * height.toLong()
    val isEmpty: Boolean get() = width <= 0 || height <= 0
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    /**
     * Returns the visible portion clipped inside `[0, screenWidth] x [0, screenHeight]`,
     * or null if completely off-screen or degenerate.
     */
    fun clippedToScreen(screenWidth: Int, screenHeight: Int): RectBounds? {
        if (screenWidth <= 0 || screenHeight <= 0) return null
        val l = left.coerceIn(0, screenWidth)
        val t = top.coerceIn(0, screenHeight)
        val r = right.coerceIn(0, screenWidth)
        val b = bottom.coerceIn(0, screenHeight)
        return if (r > l && b > t) RectBounds(l, t, r, b) else null
    }
}

/**
 * Immutable, Android-free snapshot of a single AccessibilityNodeInfo.
 */
data class NodeSnapshot(
    val index: Int,
    val parentIndex: Int,
    val className: String,
    val viewId: String,
    val text: String,
    val contentDescription: String,
    val bounds: RectBounds,
    val isVisibleToUser: Boolean,
    val isClickable: Boolean,
    val isEnabled: Boolean,
    val depth: Int
) {
    /**
     * Picks non-blank text first, falling back to non-blank contentDescription.
     * Prevents empty `""` text from masking a valid contentDescription.
     */
    val effectiveText: String
        get() = text.trim().ifEmpty { contentDescription.trim() }

    /**
     * Local resource id suffix after `/`, lowercased.
     */
    val shortId: String
        get() = viewId.substringAfterLast('/').lowercase()
}

/**
 * Immutable snapshot of an interactive window tree.
 */
data class WindowSnapshot(
    val packageName: String,
    val activityName: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val isFullScreen: Boolean,
    val nodes: List<NodeSnapshot>
)

/**
 * Custom per-app or global matching rule.
 */
data class AppRule(
    val id: String,
    val packageName: String,
    val activityName: String? = null,
    val textExact: String? = null,
    val textRegex: String? = null,
    val viewIdContains: String? = null,
    val descExact: String? = null,
    val preferGesture: Boolean = false
)

enum class ClickMethod {
    NODE_ACTION,
    GESTURE
}

/**
 * Result of matching a skip target in a [WindowSnapshot].
 */
data class MatchDecision(
    val targetNodeIndex: Int,
    val clickNodeIndex: Int,
    val matchedText: String,
    val matchedVia: String,
    val isShakeAd: Boolean,
    val forceGestureOnly: Boolean,
    val targetBounds: RectBounds,
    val score: Int
)

/**
 * Command emitted by [SkipStateMachine] when a skip click should be performed.
 */
data class ActionCommand(
    val sessionId: Long,
    val attemptId: Long,
    val packageName: String,
    val decision: MatchDecision,
    val preferredMethod: ClickMethod,
    val allowImmediateGestureFallback: Boolean,
    val sinceLaunchMs: Long,
    val attemptNumber: Int
)

sealed interface EvaluationResult {
    data object NoOp : EvaluationResult
    data class OutOfWindow(val sinceLaunchMs: Long) : EvaluationResult
    data class ObserveDetected(
        val packageName: String,
        val decision: MatchDecision,
        val sinceLaunchMs: Long
    ) : EvaluationResult
    data class ExecuteClick(val command: ActionCommand) : EvaluationResult
}

enum class VerifyOutcome {
    IGNORED_STALE,
    VERIFIED_CLOSED,
    STILL_PRESENT
}
