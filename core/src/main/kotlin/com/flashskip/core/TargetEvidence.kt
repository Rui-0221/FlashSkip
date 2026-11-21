package com.flashskip.core

import kotlin.math.abs

enum class TargetObservation {
    TARGET_PRESENT,
    AD_PRESENT,
    ABSENT,
    UNOBSERVED
}

/** A refreshed native node can update its countdown while keeping the same identity. */
fun NodeSnapshot.sameControlIdentity(other: NodeSnapshot): Boolean =
    className.isNotBlank() && className == other.className && viewId == other.viewId &&
        (stableSkipLabel(effectiveText) ?: effectiveText) ==
        (stableSkipLabel(other.effectiveText) ?: other.effectiveText)

private val COUNTDOWN = Regex("""\d{1,2}\s*[s秒]?""")
private val SEPARATORS = Regex("""[|｜·•/\s()（）]+""")

private fun stableSkipLabel(label: String): String? {
    if (!SkipMatcher.isGenericSkipText(label)) return null
    return label.lowercase().replace(COUNTDOWN, "").replace(SEPARATORS, "")
}

/** Evidence captured before dispatch. Observation and permission to retry have different thresholds. */
class TargetEvidence(
    snapshot: WindowSnapshot,
    decision: MatchDecision,
    private val allowBoundedMovement: Boolean = false
) {
    private val packageName = snapshot.packageName
    private val windowId = snapshot.windowId
    private val target = snapshot.nodes[decision.targetNodeIndex]
    private val targetLabel = stableSkipLabel(target.effectiveText)
    private val originalMarkers = snapshot.nodes.filter {
        it.index != target.index && it.isVisibleToUser &&
            (isAdMarker(it) || it.effectiveText.startsWith("摇一摇"))
    }

    fun matchesWindow(pkg: String, id: Int?): Boolean =
        windowId != null && pkg == packageName && id == windowId

    fun matches(node: NodeSnapshot): Boolean =
        isIdentifiedTarget(node) && (target.bounds == node.bounds ||
            allowBoundedMovement && target.viewId.isNotBlank() && isNearby(node.bounds))

    fun allowsRetry(snapshot: WindowSnapshot, node: NodeSnapshot): Boolean =
        matches(node) && (target.bounds == node.bounds || snapshot.complete) &&
            snapshot.nodes.count { if (allowBoundedMovement) isIdentifiedTarget(it) else matches(it) } == 1

    private fun isIdentifiedTarget(node: NodeSnapshot): Boolean =
        node.isVisibleToUser && node.isEnabled && target.className.isNotBlank() &&
            sameControl(node) &&
            (target.viewId.isNotBlank() || targetLabel != null) &&
            if (targetLabel != null) stableSkipLabel(node.effectiveText) == targetLabel
            else target.effectiveText == node.effectiveText

    private fun isNearby(bounds: RectBounds): Boolean =
        bounds.width.toDouble() in (target.bounds.width * 0.8)..(target.bounds.width * 1.25) &&
            bounds.height.toDouble() in (target.bounds.height * 0.8)..(target.bounds.height * 1.25) &&
            abs(bounds.centerX - target.bounds.centerX) <= minOf(bounds.width, target.bounds.width) * 0.75 &&
            abs(bounds.centerY - target.bounds.centerY) <= minOf(bounds.height, target.bounds.height) * 0.75

    fun observe(snapshot: WindowSnapshot): TargetObservation {
        val root = snapshot.nodes.firstOrNull()
        if (snapshot.packageName != packageName || snapshot.windowId != windowId ||
            root == null || !root.isVisibleToUser || root.bounds.isEmpty
        ) {
            return TargetObservation.UNOBSERVED
        }

        return observeNodes(snapshot)
    }

    fun observeReturn(snapshot: WindowSnapshot): TargetObservation {
        if (snapshot.packageName != packageName || snapshot.windowId == windowId || !snapshot.isFullScreen ||
            snapshot.nodes.firstOrNull()?.isVisibleToUser != true
        ) return TargetObservation.UNOBSERVED
        if (hasVisibleAdSignal(snapshot)) return TargetObservation.AD_PRESENT
        return if (snapshot.complete) TargetObservation.ABSENT else TargetObservation.UNOBSERVED
    }

    private fun observeNodes(snapshot: WindowSnapshot): TargetObservation {
        var adStillVisible = false
        for (node in snapshot.nodes) {
            if (!node.isVisibleToUser) continue
            if (sameControl(node)) return TargetObservation.TARGET_PRESENT
            if (isAdMarker(node) || originalMarkers.any { isSameMarker(it, node) } ||
                node.bounds == target.bounds && targetLabel != null && stableSkipLabel(node.effectiveText) == targetLabel
            ) {
                adStillVisible = true
            }
        }

        return when {
            adStillVisible -> TargetObservation.AD_PRESENT
            snapshot.complete -> TargetObservation.ABSENT
            else -> TargetObservation.UNOBSERVED
        }
    }

    fun closureCheck(actionAtMs: Long): ClosureCheck =
        ClosureCheck(actionAtMs + if (originalMarkers.isEmpty()) NO_MARKER_GRACE_MS else MARKER_GRACE_MS)

    fun crossWindowClosureCheck(actionAtMs: Long): ClosureCheck =
        ClosureCheck(actionAtMs + NO_MARKER_GRACE_MS, stabilityMs = 96L)

    private fun sameControl(node: NodeSnapshot): Boolean {
        if (target.className != node.className) return false
        if (target.viewId.isNotBlank() || node.viewId.isNotBlank()) {
            return target.viewId == node.viewId
        }
        if (target.bounds != node.bounds) return false
        return if (targetLabel != null) stableSkipLabel(node.effectiveText) == targetLabel
        else target.effectiveText == node.effectiveText
    }

    private fun isSameMarker(original: NodeSnapshot, current: NodeSnapshot): Boolean =
        original.className == current.className &&
            if (original.viewId.isNotBlank()) original.viewId == current.viewId
            else original.bounds == current.bounds && original.effectiveText == current.effectiveText

    companion object {
        const val RETURN_VERIFY_TIMEOUT_MS = 750L

        fun hasVisibleAdSignal(snapshot: WindowSnapshot): Boolean = snapshot.nodes.any {
            it.isVisibleToUser && (isAdMarker(it) || it.effectiveText.startsWith("摇一摇") ||
                SkipMatcher.isGenericSkipText(it.effectiveText))
        }

        private fun isAdMarker(node: NodeSnapshot): Boolean =
            node.text.trim().lowercase() in AD_LABELS ||
                node.contentDescription.trim().lowercase() in AD_LABELS ||
                node.contentDescription == "NativeVideoAdView" ||
                AD_MARKER_ID.containsMatchIn(node.shortId)
        private const val MARKER_GRACE_MS = 72L
        private const val NO_MARKER_GRACE_MS = 120L
        private val AD_LABELS = setOf("广告", "推广", "ad", "sponsored")
        private val AD_MARKER_ID = Regex("(^|[_-])ad[_-](tag|logo)([_-]|$)")
    }
}

/** Requires two complete absences separated in time, with grace for accessibility marker updates. */
class ClosureCheck internal constructor(
    private val earliestConfirmationMs: Long,
    private val stabilityMs: Long = 24L
) {
    private var firstAbsentAtMs: Long? = null

    fun record(observation: TargetObservation, nowMs: Long): Boolean {
        if (observation != TargetObservation.ABSENT) {
            firstAbsentAtMs = null
            return false
        }
        val first = firstAbsentAtMs ?: nowMs.also { firstAbsentAtMs = it }
        return nowMs >= earliestConfirmationMs && nowMs - first >= stabilityMs
    }
}
 
