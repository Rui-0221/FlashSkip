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
    private val allowBoundedMovement: Boolean = false,
    private val nativeAnchor: NodeSnapshot? = null,
    val followsVisibleAd: Boolean = false
) {
    private val packageName = snapshot.packageName
    private val windowId = snapshot.windowId
    val freeform = snapshot.freeform
    val returnTimeoutMs = if (freeform == null) RETURN_VERIFY_TIMEOUT_MS else 1800L
    val verifyTimeoutMs = if (freeform == null) 360L else 1500L
    private val target = snapshot.nodes[decision.targetNodeIndex]
    private val matchedVia = decision.matchedVia
    private val requiresStartupProof = decision.provenStartupAd
    private val structuredStartupClose = decision.nativeOnly && requiresStartupProof
    private val targetLabel = stableSkipLabel(target.effectiveText)
    private val layoutFrame = snapshot.nodes.firstOrNull()?.bounds?.takeIf {
        nativeAnchor != null || allowBoundedMovement && provesSplashCountdown(snapshot, target, it)
    }
    val waitsForStableLayout: Boolean get() = layoutFrame != null
    val retriesNativeClick: Boolean get() = nativeAnchor != null || structuredStartupClose
    private val originalMarkers = snapshot.nodes.filter {
        it.index != target.index && it.isVisibleToUser &&
            (isAdMarker(it) || it.effectiveText.startsWith("摇一摇"))
    }

    fun matchesWindow(pkg: String, id: Int?): Boolean =
        windowId != null && pkg == packageName && id == windowId

    fun matches(node: NodeSnapshot, viewport: FreeformViewport? = freeform): Boolean {
        if (!isIdentifiedTarget(node)) return false
        if (freeform != null && !freeform.sameTask(viewport)) {
            return followsVisibleAd && viewport != null && freeform.captionId == viewport.captionId &&
                requiresStartupProof && viewport.screenTarget(node.bounds) != null &&
                (target.bounds == node.bounds || isNearby(target.bounds, node.bounds))
        }
        val original = clickBounds(target.bounds, freeform) ?: return false
        val candidate = clickBounds(node.bounds, viewport) ?: return false
        return original == candidate || matchesAnonymousClose(node) && isNearby(original, candidate) ||
            allowBoundedMovement && target.viewId.isNotBlank() &&
            (isNearby(original, candidate) || layoutFrame?.let { frame ->
                isUpperCornerControl(node.bounds, frame) &&
                    abs(node.bounds.right - target.bounds.right) <= frame.width * .08f
            } == true)
    }

    fun allowsRetry(snapshot: WindowSnapshot, node: NodeSnapshot, decision: MatchDecision? = null): Boolean {
        if (!matchesWindow(snapshot.packageName, snapshot.windowId)) return false
        if (followsVisibleAd) {
            if (!snapshot.complete || decision?.matchedVia != matchedVia ||
                requiresStartupProof && !decision.provenStartupAd) return false
            return matches(node, snapshot.freeform) && snapshot.nodes.count(::isIdentifiedTarget) == 1
        }
        if (!sameSurface(snapshot)) return false
        if (nativeAnchor != null) {
            val anchor = decision?.let { findNativeAnchor(snapshot, it) } ?: return false
            return snapshot.nodes.firstOrNull()?.bounds == layoutFrame &&
                anchor.sameControlIdentity(nativeAnchor) && node.sameControlIdentity(target) &&
                isNearby(target.bounds, node.bounds) && isNearby(nativeAnchor.bounds, anchor.bounds)
        }
        if (!matches(node, snapshot.freeform)) return false
        if (layoutFrame != null) {
            return snapshot.nodes.firstOrNull()?.bounds == layoutFrame &&
                provesSplashCountdown(snapshot, node, layoutFrame) &&
                snapshot.nodes.count { it.isVisibleToUser && it.viewId == target.viewId } == 1
        }
        return (target.bounds == node.bounds && freeform?.nodeRoot == snapshot.freeform?.nodeRoot || snapshot.complete) &&
            snapshot.nodes.count {
                if (allowBoundedMovement) isIdentifiedTarget(it) else matches(it, snapshot.freeform)
            } == 1
    }

    /** A native parent may expand in place as its countdown becomes visible. */
    fun confirmsNativePillRelayout(anchor: NodeSnapshot, children: List<NodeSnapshot>): Boolean {
        val original = nativeAnchor ?: return false
        val frame = layoutFrame ?: return false
        if (!anchor.isVisibleToUser || !anchor.isEnabled || !anchor.isClickable ||
            !anchor.sameControlIdentity(original) || !isUpperCornerControl(anchor.bounds, frame) ||
            anchor.bounds.top != original.bounds.top || anchor.bounds.bottom != original.bounds.bottom ||
            anchor.bounds.right != original.bounds.right || !isNearby(original.bounds, anchor.bounds)) return false
        if (children.size != 2 || children.any {
                !it.isVisibleToUser || !it.isEnabled || it.isClickable ||
                    it.className != "android.widget.TextView" || !anchor.bounds.contains(it.bounds)
            }) return false
        val label = children.singleOrNull { it.sameControlIdentity(target) && isNearby(target.bounds, it.bounds) }
            ?: return false
        return children.any { it !== label && PILL_COUNTDOWN.matches(it.effectiveText) && !it.bounds.overlaps(label.bounds) }
    }

    private fun clickBounds(bounds: RectBounds, viewport: FreeformViewport?): RectBounds? =
        if (viewport == null) bounds else viewport.screenTarget(bounds)

    private fun isIdentifiedTarget(node: NodeSnapshot): Boolean =
        node.isVisibleToUser && node.isEnabled && target.className.isNotBlank() &&
            (matchesAnonymousClose(node) || sameControl(node) &&
                (target.viewId.isNotBlank() || targetLabel != null) &&
                if (targetLabel != null) stableSkipLabel(node.effectiveText) == targetLabel
                else target.effectiveText == node.effectiveText)

    private fun matchesAnonymousClose(node: NodeSnapshot): Boolean = followsVisibleAd &&
        (matchedVia == "self_drawn_skip" || structuredStartupClose) &&
        node.className == target.className && node.isClickable &&
        node.viewId.isBlank() && node.effectiveText.isBlank() &&
        (node.bounds == target.bounds || !structuredStartupClose && requiresStartupProof && isNearby(target.bounds, node.bounds))

    private fun isNearby(original: RectBounds, bounds: RectBounds): Boolean =
        bounds.width.toDouble() in (original.width * 0.8)..(original.width * 1.25) &&
            bounds.height.toDouble() in (original.height * 0.8)..(original.height * 1.25) &&
            abs(bounds.centerX - original.centerX) <= minOf(bounds.width, original.width) * 0.75 &&
            abs(bounds.centerY - original.centerY) <= minOf(bounds.height, original.height) * 0.75

    fun observe(snapshot: WindowSnapshot): TargetObservation {
        val root = snapshot.nodes.firstOrNull()
        if (snapshot.packageName != packageName || snapshot.windowId != windowId ||
            root == null || !root.isVisibleToUser || root.bounds.isEmpty
        ) {
            return TargetObservation.UNOBSERVED
        }
        if (!sameSurface(snapshot)) {
            return if (followsVisibleAd && requiresStartupProof && freeform != null &&
                snapshot.freeform?.captionId == freeform.captionId && snapshot.nodes.any(::isIdentifiedTarget)) {
                TargetObservation.TARGET_PRESENT
            } else TargetObservation.UNOBSERVED
        }
        return observeNodes(snapshot)
    }

    fun observeReturn(snapshot: WindowSnapshot): TargetObservation {
        if (snapshot.packageName != packageName || snapshot.windowId == windowId || !sameSurface(snapshot) ||
            (!snapshot.isFullScreen && snapshot.freeform == null) ||
            snapshot.nodes.firstOrNull()?.isVisibleToUser != true
        ) return TargetObservation.UNOBSERVED
        if (hasVisibleAdSignal(snapshot)) return TargetObservation.AD_PRESENT
        return if (snapshot.complete) TargetObservation.ABSENT else TargetObservation.UNOBSERVED
    }

    private fun sameSurface(snapshot: WindowSnapshot) =
        if (freeform == null) snapshot.freeform == null else freeform.sameTask(snapshot.freeform)

    private fun observeNodes(snapshot: WindowSnapshot): TargetObservation {
        var adStillVisible = false
        for (node in snapshot.nodes) {
            if (!node.isVisibleToUser) continue
            if (sameControl(node) || node.isEnabled && matchesAnonymousClose(node)) return TargetObservation.TARGET_PRESENT
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

        fun forNativeReacquisition(snapshot: WindowSnapshot, decision: MatchDecision): TargetEvidence? =
            findNativeAnchor(snapshot, decision)?.let { TargetEvidence(snapshot, decision, nativeAnchor = it) }

        fun forStructuredNativeRetry(snapshot: WindowSnapshot, decision: MatchDecision): TargetEvidence? {
            if (!decision.nativeOnly || !decision.provenStartupAd) return null
            val evidence = TargetEvidence(snapshot, decision, followsVisibleAd = true)
            return evidence.takeIf { it.allowsRetry(snapshot, snapshot.nodes[decision.targetNodeIndex], decision) }
        }

        private fun findNativeAnchor(snapshot: WindowSnapshot, decision: MatchDecision): NodeSnapshot? {
            val viewport = snapshot.freeform
            if (!decision.canClickNode || !snapshot.complete || snapshot.windowId == null ||
                !(snapshot.isFullScreen || viewport != null) || snapshot.behindTopWindow ||
                decision.bannerNodeIndices.isNotEmpty()) return null
            val target = snapshot.nodes[decision.targetNodeIndex]
            val anchor = snapshot.nodes[decision.clickNodeIndex]
            val frame = snapshot.nodes.first().bounds
            if (target.parentIndex != anchor.index || !SkipMatcher.isGenericSkipText(target.effectiveText) ||
                !target.isVisibleToUser || !target.isEnabled || !anchor.isVisibleToUser || !anchor.isEnabled ||
                !anchor.isClickable || anchor.viewId.isBlank() || !anchor.bounds.contains(target.bounds) ||
                !isUpperCornerControl(anchor.bounds, frame)) return null
            val anchorOnScreen = if (viewport == null) anchor.bounds else viewport.screenTarget(anchor.bounds) ?: return null
            if (snapshot.obstructions.any { it.overlaps(anchorOnScreen) }) return null
            if (snapshot.nodes.count { it.isVisibleToUser && it.viewId == anchor.viewId } != 1 ||
                snapshot.nodes.count { it.isVisibleToUser && it.parentIndex == anchor.index &&
                    SkipMatcher.isGenericSkipText(it.effectiveText) } != 1) return null
            val independentMarker = viewport == null && snapshot.nodes.any { it.isVisibleToUser &&
                it.index != target.index && it.index != anchor.index && isAdMarker(it) }
            if (!independentMarker &&
                !provesNativePillCreative(snapshot.nodes, frame, anchor, target)) return null
            return anchor
        }

        private fun provesNativePillCreative(
            nodes: List<NodeSnapshot>, frame: RectBounds, anchor: NodeSnapshot, target: NodeSnapshot
        ): Boolean {
            if (anchor.shortId != "ptgskiplayout" || anchor.className != "android.widget.LinearLayout" ||
                target.className != "android.widget.TextView" || target.isClickable) return false
            val wrapper = nodes.getOrNull(anchor.parentIndex) ?: return false
            val scene = nodes.getOrNull(wrapper.parentIndex) ?: return false
            if (!wrapper.isVisibleToUser || wrapper.isClickable || wrapper.shortId != "ptgsplashskipfl" ||
                !wrapper.bounds.contains(anchor.bounds) || !scene.isVisibleToUser || !scene.bounds.contains(wrapper.bounds) ||
                scene.bounds.width < frame.width * .85f || scene.bounds.height < frame.height * .70f) return false
            val countdown = nodes.filter { it.parentIndex == anchor.index && it.index != target.index }.singleOrNull()
                ?: return false
            if (!countdown.isEnabled || countdown.isClickable || countdown.shortId != "ptgsplashsec" ||
                countdown.className != "android.widget.TextView" || countdown.isVisibleToUser &&
                (!anchor.bounds.contains(countdown.bounds) || !PILL_COUNTDOWN.matches(countdown.effectiveText))) return false
            return nodes.any { creative ->
                if (!creative.isVisibleToUser || creative.shortId != "ptgsplashiv" ||
                    creative.className != "android.widget.ImageView" ||
                    creative.bounds.width < scene.bounds.width * .85f || creative.bounds.height < scene.bounds.height * .85f ||
                    !scene.bounds.contains(creative.bounds)) return@any false
                var parent = creative.parentIndex
                while (parent > scene.index && parent in nodes.indices) {
                    val ancestor = nodes[parent]
                    if (ancestor.parentIndex >= parent) return@any false
                    parent = ancestor.parentIndex
                }
                parent == scene.index
            }
        }

        fun hasVisibleAdSignal(snapshot: WindowSnapshot): Boolean = snapshot.nodes.any {
            it.isVisibleToUser && (isAdMarker(it) || it.effectiveText.startsWith("摇一摇") ||
                SkipMatcher.isGenericSkipText(it.effectiveText))
        }

        private val SPLASH_CONTROL_ID = Regex("(^|_)splash_(?:skip|jump|close)(_|$)")
        private val SPLASH_JUMP_ID = Regex("(^|_)splash_jump(_|$)")
        private val PILL_COUNTDOWN = Regex("""^(?:\d{1,2}|\(\d{1,2}\))$""")

        internal fun isSplashControlArea(shortId: String): Boolean =
            shortId.endsWith("_area") && SPLASH_CONTROL_ID.containsMatchIn(shortId)

        internal fun isSplashCountdown(node: NodeSnapshot): Boolean =
            SPLASH_CONTROL_ID.containsMatchIn(node.shortId) && SkipMatcher.isGenericSkipText(node.effectiveText) &&
                node.effectiveText.any { it in '0'..'9' }

        internal fun isSplashJumpCountdown(node: NodeSnapshot): Boolean =
            SPLASH_JUMP_ID.containsMatchIn(node.shortId) && isSplashCountdown(node)

        internal fun provesSplashCountdown(snapshot: WindowSnapshot, node: NodeSnapshot, frame: RectBounds): Boolean {
            if (!snapshot.complete || !(snapshot.isFullScreen || snapshot.freeform != null) ||
                !node.isVisibleToUser || !node.isEnabled ||
                !isSplashCountdown(node) ||
                !isUpperCornerControl(node.bounds, frame)) return false
            return snapshot.nodes.any { it.index != node.index && it.isVisibleToUser && isAdMarker(it) }
        }

        internal fun provesFloatingSdkSplashClose(snapshot: WindowSnapshot, node: NodeSnapshot, frame: RectBounds): Boolean {
            if (snapshot.freeform == null || snapshot.behindTopWindow || !snapshot.complete ||
                !node.isVisibleToUser || !node.isEnabled || node.isClickable || node.effectiveText.isNotEmpty() ||
                node.shortId != "ms_skipview_container" || node.className != "android.widget.RelativeLayout" ||
                !isUpperCornerControl(node.bounds, frame)) return false
            val creative = snapshot.nodes.getOrNull(node.parentIndex) ?: return false
            if (!creative.isVisibleToUser || !creative.bounds.contains(node.bounds) ||
                creative.bounds.width < frame.width * .85f || creative.bounds.height < frame.height * .70f) return false
            val close = snapshot.nodes.singleOrNull { it.parentIndex == node.index } ?: return false
            if (!close.isVisibleToUser || !close.isEnabled || close.className != "android.view.View" ||
                close.shortId != "ms_skipview" || close.bounds != node.bounds || close.effectiveText.isNotEmpty()) return false
            val badge = snapshot.nodes.singleOrNull {
                it.parentIndex == creative.index && it.isVisibleToUser && it.shortId == "ms_ad_tag_layout" &&
                    creative.bounds.contains(it.bounds) && it.bounds.top >= creative.bounds.top + creative.bounds.height * .60f
            } ?: return false
            return snapshot.nodes.any {
                it.isVisibleToUser && it.shortId == "ms_img_meishu_ad_tag" && it.className == "android.widget.ImageView" &&
                    badge.bounds.contains(it.bounds) && snapshot.nodes.getOrNull(it.parentIndex)?.let { parent ->
                        parent.index == badge.index || parent.parentIndex == badge.index
                    } == true
            }
        }

        private fun isUpperCornerControl(bounds: RectBounds, frame: RectBounds): Boolean =
            frame.contains(bounds) && bounds.right >= frame.left + frame.width * .85f &&
                bounds.top >= frame.top && bounds.bottom <= frame.top + frame.height * .26f &&
                bounds.width <= frame.width * .25f && bounds.height <= frame.height * .10f

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
 
