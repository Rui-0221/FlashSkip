package com.flashskip.core

/**
 * Pure-Kotlin skip-target matcher with multi-layer anti-misclick safeguards:
 * 1. Exact/word-boundary text & desc regexes (no raw `contains("跳过")`);
 * 2. Semantic blacklist on node text, small parent/children, and resource id;
 * 3. Geometry & area bounds constraints + ancestor size lock (blocks full-screen ad container traps);
 * 4. Position scoring (edge/corner preference, blocks mid-screen content false positives);
 * 5. Single-pass shake-ad detection and strict visible ad-context verification for id-only rules.
 */
object SkipMatcher {

    private const val MAX_TEXT_LEN = 14

    private val SKIP_CN_REGEX = Regex(
        """^(点击)?跳过(广告)?(\s*[|｜·•/\(（]?\s*\d{1,2}\s*[sS秒]?[\)）]?|\s*[>›×✕])?$"""
    )
    private val SKIP_NUM_FIRST_CN_REGEX = Regex(
        """^\d{1,2}\s*[sS秒]?\s*[|｜·•/]?\s*(点击)?跳过(广告)?$"""
    )
    private val SKIP_EN_REGEX = Regex(
        """^(skip(\s*(ad|ads|\d{1,2}\s*s?))?|\d{1,2}\s*s?\s*[|｜·•/]?\s*skip|关闭广告(\s*\d{1,2}\s*[sS秒]?)?)$""",
        RegexOption.IGNORE_CASE
    )
    private val AD_CLOSE_SYMBOL_REGEX = Regex("""^(关闭|[×✕╳xX])$""")
    private val STANDALONE_COUNTDOWN_REGEX = Regex("""^\d{1,2}\s*[sS秒]$""")

    private val SEMANTIC_BLACKLIST = listOf(
        "引导", "教程", "此步", "步骤", "登录", "注册", "绑定", "验证",
        "片头", "片尾", "更新", "此题", "问卷", "领取", "下载", "购买",
        "开通", "立即", "红包", "福利", "抽奖", "安装", "详情", "查看",
        "开启", "允许", "同意", "授权", "会员", "充值",
        "支付", "付款", "转账", "收款",
        "intro", "tutorial", "step", "login", "verify", "episode", "guide", "payment", "checkout"
    )

    private val ID_BLACKLIST = listOf(
        "guide", "login", "step", "intro", "verify", "tutorial", "signin", "signup", "payment", "checkout"
    )

    private val SHAKE_TEXTS = listOf("摇一摇", "扭一扭", "摇动手机", "摇动", "晃动", "前倾", "转一转")

    /**
     * Evaluates [window] in a single pass over [WindowSnapshot.nodes] and returns
     * the highest-scoring safe [MatchDecision], or null if nothing safe matches.
     */
    fun findBestMatch(window: WindowSnapshot, ruleIndex: RuleIndex): MatchDecision? {
        val viewport = window.freeform
        if (viewport != null) {
            if (window.windowId == null || !FreeformViewport.aligned(viewport.frame, viewport.nodeRoot,
                    window.screenWidth, window.screenHeight) || !FreeformViewport.isCaption(viewport.frame, viewport.caption) ||
                window.nodes.firstOrNull()?.bounds != viewport.local(viewport.nodeRoot)) return null
            return match(window.copy(screenWidth = viewport.nodeRoot.width, screenHeight = viewport.nodeRoot.height), ruleIndex)
        }
        return match(window, ruleIndex)
    }

    private fun match(window: WindowSnapshot, ruleIndex: RuleIndex): MatchDecision? {
        if (window.nodes.isEmpty() || window.screenWidth <= 0 || window.screenHeight <= 0) {
            return null
        }

        val rules = ruleIndex.rulesFor(window.packageName)
        var isShakeAd = false
        var hasVisibleAdTag = false
        var hasVisibleCreativeSignal = false
        var hasFullScreenRoot = window.isFullScreen
        val actLower = window.activityName.lowercase()
        if (actLower.contains("splash") || actLower.contains("advert") ||
            actLower.contains("adactivity") || actLower.contains("launchad") ||
            actLower.contains("splashad")
        ) {
            hasVisibleAdTag = true
        }

        // Pre-index children by parent for local semantic check without extra tree scans
        val childrenMap = HashMap<Int, MutableList<NodeSnapshot>>()
        for (node in window.nodes) {
            if (node.parentIndex >= 0) {
                childrenMap.getOrPut(node.parentIndex) { mutableListOf() }.add(node)
            }
            if (node.depth == 0 &&
                node.bounds.width >= window.screenWidth * 0.85f &&
                node.bounds.height >= window.screenHeight * 0.85f
            ) {
                hasFullScreenRoot = true
            }
            if (node.isVisibleToUser) {
                val eff = node.effectiveText
                val sid = node.shortId
                val desc = node.contentDescription
                if (sid.contains("shake") || sid.contains("twist") ||
                    SHAKE_TEXTS.any { eff.contains(it) }
                ) {
                    isShakeAd = true
                    hasVisibleAdTag = true
                    hasVisibleCreativeSignal = true
                }
                if (desc == "NativeVideoAdView" || desc == "virtualNode" || desc == "广告" || desc == "推广") {
                    hasVisibleCreativeSignal = true
                    hasVisibleAdTag = true
                }
                if ((eff.length <= 16 && eff.contains("广告") && !eff.contains("设置")) ||
                    sid.contains("ad_tag") || sid.contains("ad_logo") ||
                    sid.contains("splash") || STANDALONE_COUNTDOWN_REGEX.matches(eff)
                ) {
                    hasVisibleAdTag = true
                    hasVisibleCreativeSignal = true
                }
            }
        }

        val hasAdContext = hasFullScreenRoot && hasVisibleAdTag
        val rootBounds = window.nodes.firstOrNull()?.bounds?.takeIf {
            it.width >= window.screenWidth * 0.85f && it.height >= window.screenHeight * 0.85f
        }
        // Accessibility can clip a sliding window to the display edge. Its content is not edge-aligned.
        if (!hasFullScreenRoot) return null
        if (rootBounds != null) {
            val visibleRoot = rootBounds.clippedToScreen(window.screenWidth, window.screenHeight) ?: return null
            if (visibleRoot.width < window.screenWidth * 0.90f ||
                visibleRoot.height < window.screenHeight * 0.85f
            ) return null
        }
        var best: MatchDecision? = null

        for (node in window.nodes) {
            if (!node.isVisibleToUser || !node.isEnabled) continue
            if (window.freeform != null) {
                // A fixed top margin can move a centered button into the bottom band as a task shrinks.
                val x = node.bounds.centerX / window.screenWidth
                if (x in 0.30f..0.70f || window.freeform.screenTarget(node.bounds) == null) continue
            }
            val clipped = node.bounds.clippedToScreen(window.screenWidth, window.screenHeight) ?: continue
            if (!isButtonSized(clipped, window.screenWidth, window.screenHeight)) continue
            if (!isInEdgeOrCornerZone(node.bounds, window.screenWidth, window.screenHeight, rootBounds)) continue

            val eff = node.effectiveText
            val sid = node.shortId

            if (hasBlacklistedSemantics(node, window, childrenMap)) continue

            // 1. Check custom AppRules first
            var matchedVia: String? = null
            var baseScore = 0
            var forceGestureFromRule = false
            var matchedLabel = eff.ifEmpty { node.viewId }

            for (rule in rules) {
                if (rule.activityName != null && window.activityName != rule.activityName) continue
                val hit = when {
                    rule.textExact != null -> eff == rule.textExact
                    rule.descExact != null -> node.contentDescription.trim() == rule.descExact
                    rule.textRegex != null -> Regex(rule.textRegex, RegexOption.IGNORE_CASE).matches(eff)
                    rule.viewIdContains != null -> hasAdContext && sid.contains(rule.viewIdContains.lowercase())
                    else -> false
                }
                if (hit) {
                    matchedVia = "rule:${rule.id}"
                    baseScore = 100
                    forceGestureFromRule = rule.preferGesture
                    break
                }
            }

            // 2. Generic L1 text/desc match
            if (matchedVia == null && eff.isNotEmpty() && isGenericSkipText(eff)) {
                matchedVia = if (node.text.isNotBlank()) "text" else "desc"
                baseScore = if (eff == "跳过" || eff.equals("skip", ignoreCase = true) || eff == "跳过广告") 80 else 70
                matchedLabel = eff
            }

            // 3. Generic L1 id match or ad-context-only close symbol ("关闭" / "×")
            if (matchedVia == null && hasAdContext && AD_CLOSE_SYMBOL_REGEX.matches(eff)) {
                matchedVia = "ad_close"
                baseScore = 50
                matchedLabel = eff
            }

            if (matchedVia == null && sid.contains("skip") && !ID_BLACKLIST.any { sid.contains(it) }) {
                if (!hasAdContext) continue
                matchedVia = "id"
                baseScore = 40
                matchedLabel = node.viewId.ifEmpty { "id:skip" }
            }

            // 4. L3 self-drawn leaf android.view.View overlay skip (no text/desc/id)
            if (matchedVia == null && hasFullScreenRoot && hasVisibleCreativeSignal &&
                isSelfDrawnOverlaySkipCandidate(node, clipped, window, childrenMap, rootBounds)
            ) {
                matchedVia = "self_drawn_skip"
                baseScore = 30
                forceGestureFromRule = true
                matchedLabel = "view:self_drawn_skip"
            }

            if (matchedVia == null) continue

            // Ancestor size lock: only climb to clickable parents that are ALSO button-sized
            val (clickIdx, forceGestureFromOversizedParent) = resolveSafeClickTarget(
                node,
                window.nodes,
                window.screenWidth,
                window.screenHeight
            )
            val clickNode = window.nodes[clickIdx]
            // Split countdown pills can ignore ACTION_CLICK even when marked clickable.
            val splitCountdown = (eff.contains('|') || eff.contains('｜')) && isGenericSkipText(eff)
            val forceGesture = forceGestureFromRule || splitCountdown || forceGestureFromOversizedParent ||
                !clickNode.isClickable || !clickNode.isEnabled

            val positionBonus = computePositionBonus(node.bounds, window.screenWidth, window.screenHeight, rootBounds)
            val clickableBonus = if (!forceGesture && clickNode.isClickable) 5 else 0
            val shakeBonus = if (isShakeAd) 10 else 0
            val totalScore = baseScore + positionBonus + clickableBonus + shakeBonus

            if (best == null || totalScore > best.score) {
                best = MatchDecision(
                    targetNodeIndex = node.index,
                    clickNodeIndex = clickIdx,
                    matchedText = matchedLabel,
                    matchedVia = matchedVia,
                    isShakeAd = isShakeAd,
                    forceGestureOnly = forceGesture,
                    targetBounds = clipped,
                    score = totalScore
                )
            }
        }

        return best
    }

    fun isGenericSkipText(raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty() || text.length > MAX_TEXT_LEN) return false
        val lower = text.lowercase()
        if (SEMANTIC_BLACKLIST.any { lower.contains(it) }) return false
        return SKIP_CN_REGEX.matches(text) ||
            SKIP_NUM_FIRST_CN_REGEX.matches(text) ||
            SKIP_EN_REGEX.matches(text)
    }

    /**
     * Checks that a node (and its small container or children) does not carry blacklisted words.
     */
    private fun hasBlacklistedSemantics(
        node: NodeSnapshot,
        window: WindowSnapshot,
        childrenMap: Map<Int, List<NodeSnapshot>>
    ): Boolean {
        val lowerText = node.effectiveText.lowercase()
        val sid = node.shortId
        if (SEMANTIC_BLACKLIST.any { lowerText.contains(it) }) return true
        if (ID_BLACKLIST.any { sid.contains(it) }) return true

        // Check direct children of this node (e.g. a clickable container holding ["跳过", "引导"])
        val children = childrenMap[node.index].orEmpty()
        for (c in children) {
            val cLower = c.effectiveText.lowercase()
            if (cLower.isNotEmpty() && SEMANTIC_BLACKLIST.any { cLower.contains(it) }) return true
        }

        // Check immediate parent ONLY if parent is button-sized (avoid rejecting because of full-screen siblings)
        if (node.parentIndex in window.nodes.indices) {
            val parent = window.nodes[node.parentIndex]
            val parentClipped = parent.bounds.clippedToScreen(window.screenWidth, window.screenHeight)
            if (parentClipped != null && isButtonSized(parentClipped, window.screenWidth, window.screenHeight)) {
                val pLower = parent.effectiveText.lowercase()
                if (pLower.isNotEmpty() && SEMANTIC_BLACKLIST.any { pLower.contains(it) }) return true
            }
        }
        return false
    }

    /**
     * Detects self-drawn custom `android.view.View` skip buttons (zero text/desc/id)
     * sitting directly inside a full-screen overlay wrapper on a confirmed ad creative.
     */
    private fun isSelfDrawnOverlaySkipCandidate(
        node: NodeSnapshot,
        clipped: RectBounds,
        window: WindowSnapshot,
        childrenMap: Map<Int, List<NodeSnapshot>>,
        rootBounds: RectBounds?
    ): Boolean {
        if (node.className != "android.view.View") return false
        if (!node.isClickable || !node.isEnabled) return false
        if (node.effectiveText.isNotEmpty() || node.viewId.isNotEmpty()) return false
        if (!childrenMap[node.index].isNullOrEmpty()) return false

        val minSide = minOf(clipped.width, clipped.height)
        val maxSide = maxOf(clipped.width, clipped.height)
        if (minSide < 48 || clipped.width > 260 || clipped.height > 200) return false
        if (maxSide.toFloat() / minSide.toFloat() > 2.6f) return false

        if (!isInEdgeOrCornerZone(node.bounds, window.screenWidth, window.screenHeight, rootBounds)) {
            return false
        }

        // Must be a direct child of a large full-screen ad wrapper, not inside an inner ad card
        val parent = window.nodes.getOrNull(node.parentIndex) ?: return false
        return parent.bounds.width >= window.screenWidth * 0.85f &&
            parent.bounds.height >= window.screenHeight * 0.70f
    }

    fun isButtonSized(bounds: RectBounds, screenWidth: Int, screenHeight: Int): Boolean {
        if (bounds.isEmpty || screenWidth <= 0 || screenHeight <= 0) return false
        if (bounds.width < 12 || bounds.height < 12) return false
        val maxW = (screenWidth * 0.48f).toInt()
        val maxH = (screenHeight * 0.16f).toInt()
        val maxArea = (screenWidth.toLong() * screenHeight.toLong() * 8L) / 100L
        return bounds.width <= maxW && bounds.height <= maxH && bounds.area <= maxArea
    }

    /**
     * Rejects hits sitting squarely in the center content band of the window.
     * Normalizes against [rootBounds] when available so mid-animation window slide-in
     * offsets (e.g. +600px in X) cannot make a center button look edge-aligned.
     */
    fun isInEdgeOrCornerZone(
        bounds: RectBounds,
        screenWidth: Int,
        screenHeight: Int,
        rootBounds: RectBounds? = null
    ): Boolean {
        val screenX = bounds.centerX / screenWidth.coerceAtLeast(1)
        val screenY = bounds.centerY / screenHeight.coerceAtLeast(1)
        if (isCentral(screenX, screenY)) return false
        if (rootBounds == null) return true
        val rootX = (bounds.centerX - rootBounds.left) / rootBounds.width.coerceAtLeast(1)
        val rootY = (bounds.centerY - rootBounds.top) / rootBounds.height.coerceAtLeast(1)
        return !isCentral(rootX, rootY)
    }

    private fun isCentral(x: Float, y: Float): Boolean = x in 0.20f..0.80f && y in 0.25f..0.75f

    private fun computePositionBonus(
        bounds: RectBounds,
        screenWidth: Int,
        screenHeight: Int,
        rootBounds: RectBounds? = null
    ): Int {
        val refLeft = rootBounds?.left ?: 0
        val refTop = rootBounds?.top ?: 0
        val refW = (rootBounds?.width ?: screenWidth).coerceAtLeast(1)
        val refH = (rootBounds?.height ?: screenHeight).coerceAtLeast(1)
        val rx = (bounds.centerX - refLeft) / refW.toFloat()
        val ry = (bounds.centerY - refTop) / refH.toFloat()
        return when {
            rx >= 0.60f && ry <= 0.25f -> 20 // Classic top-right splash skip corner
            ry <= 0.25f || ry >= 0.78f -> 10 // Top or bottom bar
            else -> 0
        }
    }

    /**
     * Climbs up to 3 parent levels to find a clickable container ONLY if every climbed
     * ancestor still satisfies [isButtonSized]. If an ancestor is oversized (such as a
     * full-screen ad container), climbing stops immediately and forces gesture clicking
     * on the small skip node itself.
     */
    private fun resolveSafeClickTarget(
        node: NodeSnapshot,
        allNodes: List<NodeSnapshot>,
        screenWidth: Int,
        screenHeight: Int
    ): Pair<Int, Boolean> {
        if (node.isClickable && node.isEnabled) {
            return node.index to false
        }
        var curIndex = node.parentIndex
        var up = 1
        while (curIndex in allNodes.indices && up <= 3) {
            val parent = allNodes[curIndex]
            val pClipped = parent.bounds.clippedToScreen(screenWidth, screenHeight)
            if (pClipped == null || !isButtonSized(pClipped, screenWidth, screenHeight)) {
                // Ancestor is oversized (likely full-screen ad container); NEVER click it!
                return node.index to true
            }
            if (parent.isVisibleToUser && parent.isClickable && parent.isEnabled) {
                return parent.index to false
            }
            curIndex = parent.parentIndex
            up++
        }
        return node.index to true
    }
}
