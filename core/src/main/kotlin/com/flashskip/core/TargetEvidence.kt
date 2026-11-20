package com.flashskip.core

enum class TargetObservation {
    TARGET_PRESENT,
    AD_PRESENT,
    ABSENT,
    UNOBSERVED
}

/** Evidence captured before dispatch. Observation and permission to retry have different thresholds. */
class TargetEvidence(snapshot: WindowSnapshot, decision: MatchDecision) {
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
        node.isVisibleToUser && node.isEnabled && target.className.isNotBlank() &&
            target.bounds == node.bounds && sameControl(node) &&
            (target.viewId.isNotBlank() || targetLabel != null) &&
            if (targetLabel != null) stableSkipLabel(node.effectiveText) == targetLabel
            else target.effectiveText == node.effectiveText

    fun observe(snapshot: WindowSnapshot): TargetObservation {
        val root = snapshot.nodes.firstOrNull()
        if (snapshot.packageName != packageName || snapshot.windowId != windowId ||
            root == null || !root.isVisibleToUser || root.bounds.isEmpty
        ) {
            return TargetObservation.UNOBSERVED
        }

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

    private fun isAdMarker(node: NodeSnapshot): Boolean =
        node.text.trim().lowercase() in AD_LABELS ||
            node.contentDescription.trim().lowercase() in AD_LABELS ||
            node.contentDescription == "NativeVideoAdView" ||
            AD_MARKER_ID.containsMatchIn(node.shortId)

    private fun stableSkipLabel(label: String): String? {
        if (!SkipMatcher.isGenericSkipText(label)) return null
        return label.lowercase().replace(COUNTDOWN, "").replace(SEPARATORS, "")
    }

    companion object {
        private const val MARKER_GRACE_MS = 72L
        private const val NO_MARKER_GRACE_MS = 120L
        private val AD_LABELS = setOf("广告", "推广", "ad", "sponsored")
        private val AD_MARKER_ID = Regex("(^|[_-])ad[_-](tag|logo)([_-]|$)")
        private val COUNTDOWN = Regex("""\d{1,2}\s*[s秒]?""")
        private val SEPARATORS = Regex("""[|｜·•/\s()（）]+""")
    }
}

/** Requires two complete absences separated in time, with grace for accessibility marker updates. */
class ClosureCheck internal constructor(private val earliestConfirmationMs: Long) {
    private var firstAbsentAtMs: Long? = null

    fun record(observation: TargetObservation, nowMs: Long): Boolean {
        if (observation != TargetObservation.ABSENT) {
            firstAbsentAtMs = null
            return false
        }
        val first = firstAbsentAtMs ?: nowMs.also { firstAbsentAtMs = it }
        return nowMs >= earliestConfirmationMs && nowMs - first >= 24L
    }
}
 
