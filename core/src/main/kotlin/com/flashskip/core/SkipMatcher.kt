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
    private val COUNTDOWN_PART = Regex("""^(?:\d{1,2}\s*[sS秒]?|\(\s*\d{1,2}\s*[sS秒]?\s*\)|（\s*\d{1,2}\s*[sS秒]?\s*）)\s*[|｜·•/]?$""")

    private val SEMANTIC_BLACKLIST = listOf(
        "引导", "教程", "此步", "步骤", "登录", "注册", "绑定", "验证",
        "片头", "片尾", "更新", "此题", "问卷", "领取", "下载", "购买",
        "开通", "立即", "红包", "福利", "抽奖", "安装", "详情", "查看",
        "开启", "允许", "同意", "授权", "会员", "充值", "去广告",
        "支付", "付款", "转账", "收款",
        "intro", "tutorial", "step", "login", "verify", "episode", "guide", "payment", "checkout"
    )

    private val ID_BLACKLIST = listOf(
        "guide", "login", "step", "intro", "verify", "tutorial", "signin", "signup", "payment", "checkout"
    )

    private val SHAKE_TEXTS = listOf("摇一摇", "扭一扭", "摇动手机", "摇动", "晃动", "前倾", "转一转")
    private val BUSINESS_STEP = Regex(
        "^(?:next|下一步|搜索历史|历史搜索|选择.{0,12}(?:兴趣|偏好|行业|感兴趣).{0,12}|vip去广告|.{0,10}(?:登录|注册)后.{0,10})[。！!：:]?$",
        RegexOption.IGNORE_CASE
    )
    private val CREATIVE_LABEL = Regex("^(?:广告|推广|广告素材|互动广告|广告\\s*[|｜]\\s*已.{1,12}预加载)$")
    private val CREATIVE_CTA = Regex("^(?:点击跳转详情(?:页面?|或者第三方应用)?|上滑或点击查看详情)[。>›]?$")
    private val COUNTDOWN_BUTTON = Regex("跳过[,，]\\s*\\d{1,2}")
    private val GENERIC_RULE_IDS = setOf("ad_skip", "splash_skip", "layout_skip")
    private val WEB_AD_CLOSE = Regex("webview-close:[0-9a-f]{6,16}:\\d{1,3}:\\d{1,3}")

    /**
     * Returns the highest-scoring safe [MatchDecision] in [window], if one can be established.
     */
    fun findBestMatch(window: WindowSnapshot, ruleIndex: RuleIndex): MatchDecision? {
        val viewport = window.freeform
        if (viewport != null) {
            if (window.windowId == null || !FreeformViewport.aligned(viewport.frame, viewport.nodeRoot,
                    window.screenWidth, window.screenHeight) || !FreeformViewport.isCaption(viewport.frame, viewport.caption) ||
                window.nodes.firstOrNull()?.bounds != viewport.local(viewport.nodeRoot)) return null
            val display = RectBounds(0, 0, window.screenWidth, window.screenHeight)
            return match(window.copy(screenWidth = viewport.nodeRoot.width, screenHeight = viewport.nodeRoot.height,
                freeform = viewport.copy(display = display)), ruleIndex)
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
        var hasIndependentAdMarker = false
        var hasCreativeLabel = false
        var hasBusinessStep = false
        var hasFullScreenRoot = window.isFullScreen
        val actLower = window.activityName.lowercase()
        val activity = actLower.substringAfterLast('.')
        val launchActivity = activity.endsWith("splashactivity") || activity.endsWith("splashadactivity") ||
            activity.endsWith("splashscreenactivity") || activity in setOf("launcher", "launcheractivity", "launchactivity")
        if (actLower.contains("splash") || actLower.contains("advert") ||
            actLower.contains("adactivity") || actLower.contains("launchad") ||
            actLower.contains("splashad")
        ) {
            hasVisibleAdTag = true
        }

        // Pre-index children by parent for local semantic check without extra tree scans
        val childrenMap = HashMap<Int, MutableList<NodeSnapshot>>()
        val creativeCtas = mutableListOf<NodeSnapshot>()
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
                if (CREATIVE_LABEL.matches(eff)) hasCreativeLabel = true
                if (CREATIVE_CTA.matches(eff)) creativeCtas.add(node)
                if (BUSINESS_STEP.matches(eff) || node.className.endsWith(".EditText") ||
                    node.className.endsWith(".Switch")) hasBusinessStep = true
                if (eff == "广告" || eff == "推广" || desc == "NativeVideoAdView" ||
                    sid.endsWith("ad_tag") || sid.endsWith("ad_logo") || SHAKE_TEXTS.any { eff.startsWith(it) }) {
                    hasIndependentAdMarker = true
                }
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
        if (window.behindTopWindow && (!window.complete || !hasIndependentAdMarker)) return null
        if (rootBounds != null) {
            val visibleRoot = rootBounds.clippedToScreen(window.screenWidth, window.screenHeight) ?: return null
            if (visibleRoot.width < window.screenWidth * 0.90f ||
                visibleRoot.height < window.screenHeight * 0.85f
            ) return null
        }
        var best: MatchDecision? = null
        val nativeCloses = mutableListOf<MatchDecision>()
        val surface = rootBounds ?: RectBounds(0, 0, window.screenWidth, window.screenHeight)
        val startupControl = launchActivity && !hasBusinessStep

        for (node in window.nodes) {
            if (!node.isVisibleToUser || !node.isEnabled) continue
            if (window.freeform != null) {
                // A fixed top margin can move a centered button into the bottom band as a task shrinks.
                val x = node.bounds.centerX / window.screenWidth
                if (x in 0.30f..0.70f || window.freeform.screenTarget(node.bounds) == null) continue
            }
            val clipped = node.bounds.clippedToScreen(window.screenWidth, window.screenHeight) ?: continue
            if (!isButtonSized(clipped, window.screenWidth, window.screenHeight)) continue
            if (isObstructed(clipped, window)) continue

            val eff = node.effectiveText
            val sid = node.shortId
            if (hasBlacklistedSemantics(node, window, childrenMap)) continue
            val interactiveVideoClose = node.isClickable && node.viewId.isEmpty() && eff.isEmpty() &&
                isInteractiveVideoClose(node, window, childrenMap, surface)
            if (node.isClickable && (interactiveVideoClose || isNativeAdClose(node, window, childrenMap, surface))) {
                val banners = findSiblingBanners(node, window, childrenMap, structured = true)
                if (window.complete && clipped == node.bounds && banners.none { it.isVisibleToUser && it.bounds.overlaps(clipped) }) {
                    nativeCloses.add(MatchDecision(node.index, node.index, eff.ifEmpty { "ad_close" },
                        "structured_native_close", isShakeAd, false, clipped, 125, banners.map { it.index },
                        canClickNode = true, nativeOnly = true, provenStartupAd = interactiveVideoClose))
                }
                continue
            }
            val splitPill = sid == "layout_skip" && provesSplitSplashPill(node, window, childrenMap, surface)
            val splitChildren = splitPill || hasCountdownSiblings(node, window, childrenMap)
            if (eff.isEmpty() && !splitChildren && TargetEvidence.isSplashControlArea(sid)) continue
            val countdown = splitChildren || isGenericSkipText(eff) && eff.any { it in '0'..'9' }
            if (!isInEdgeOrCornerZone(node.bounds, window.screenWidth, window.screenHeight, rootBounds) &&
                !(hasIndependentAdMarker && countdown && node.bounds == clipped &&
                    isDisplacedUpperControl(clipped, window, rootBounds))) continue

            val sideControl = isSideControl(clipped, window, rootBounds) || splitChildren &&
                isDisplacedUpperControl(clipped, window, rootBounds)
            val contentControl = isInScrollingContent(node, window.nodes)
            val explicitAdAction = eff.equals("skip ad", true) || eff.equals("skip ads", true) ||
                eff.startsWith("跳过广告") || eff.startsWith("关闭广告")
            val splashCountdown = TargetEvidence.provesSplashCountdown(window, node, surface) ||
                sid == "splash_skip" && countdown && isSplashImageSibling(node, window, childrenMap, surface)
            val tiedCreativeControl = creativeCtas.isNotEmpty() && (countdown || node.className == "android.view.View" &&
                node.text.isBlank() && node.isClickable) &&
                isOnCreative(node, window.nodes, surface, creativeCtas)
            val splitButton = splitChildren && enclosingCountdownButton(node, window.nodes)
            val nearOuterSide = clipped.centerX / window.screenWidth !in 0.20f..0.80f
            val textContext = sideControl && !contentControl && !hasBusinessStep &&
                (hasCreativeLabel && (nearOuterSide || splitChildren) ||
                    startupControl || splashCountdown || explicitAdAction || tiedCreativeControl || splitButton || splitPill ||
                    splitChildren && hasIndependentAdMarker)

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
                    rule.viewIdContains != null -> (hasAdContext || splitPill) && sid.contains(rule.viewIdContains.lowercase()) &&
                        (rule.viewIdContains !in GENERIC_RULE_IDS || textContext)
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
            if (matchedVia == null && textContext && isGenericSkipText(eff)) {
                matchedVia = if (node.text.isNotBlank()) "text" else "desc"
                baseScore = if (eff == "跳过" || eff.equals("skip", ignoreCase = true) || eff == "跳过广告") 80 else 70
                matchedLabel = eff
            }

            // 3. Generic L1 id match or ad-context-only close symbol ("关闭" / "×")
            if (matchedVia == null && sideControl && startupControl && !contentControl &&
                (hasCreativeLabel || hasIndependentAdMarker) && AD_CLOSE_SYMBOL_REGEX.matches(eff)) {
                matchedVia = "ad_close"
                baseScore = 50
                matchedLabel = eff
            }

            if (matchedVia == null && sid.contains("skip") && !ID_BLACKLIST.any { sid.contains(it) }) {
                if (!textContext || !hasAdContext || !hasCreativeLabel && !countdown) continue
                matchedVia = "id"
                baseScore = 40
                matchedLabel = node.viewId.ifEmpty { "id:skip" }
            }

            // 4. L3 self-drawn leaf android.view.View overlay skip (no text/desc/id)
            if (matchedVia == null && window.complete && hasVisibleCreativeSignal &&
                (hasIndependentAdMarker || !hasCreativeLabel) &&
                isSelfDrawnOverlaySkipCandidate(node, clipped, window, childrenMap, rootBounds)
            ) {
                matchedVia = "self_drawn_skip"
                baseScore = 30
                forceGestureFromRule = true
                matchedLabel = "view:self_drawn_skip"
            }

            if (matchedVia == null) continue
            val banners = findSiblingBanners(node, window, childrenMap)
            if (banners.any { it.isVisibleToUser && it.bounds.containsCenter(clipped) }) continue
            if (isPromotionalCountdown(node, window, childrenMap, banners.isNotEmpty())) continue

            // Ancestor size lock: only climb to clickable parents that are ALSO button-sized
            val (clickIdx, forceGestureFromOversizedParent) = resolveSafeClickTarget(
                node,
                window.nodes,
                window.screenWidth,
                window.screenHeight
            )
            val clickNode = window.nodes[clickIdx]
            // Split countdown pills can ignore ACTION_CLICK even when marked clickable.
            val splitCountdown = splitChildren || (eff.contains('|') || eff.contains('｜')) && isGenericSkipText(eff)
            val canClickNode = !forceGestureFromOversizedParent && clickNode.isClickable && clickNode.isEnabled &&
                !hasBlacklistedSemantics(clickNode, window, childrenMap) && !isObstructed(clickNode.bounds, window)
            val forceGesture = forceGestureFromRule || splitCountdown || !canClickNode

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
                    score = totalScore,
                    bannerNodeIndices = banners.map { it.index },
                    canClickNode = canClickNode && !forceGestureFromRule,
                    provenStartupAd = splitPill || matchedVia == "self_drawn_skip" && window.freeform != null &&
                        !hasBusinessStep && provesFloatingVideoClose(node, window, surface)
                )
            }
        }

        return if (nativeCloses.isEmpty()) best else nativeCloses.singleOrNull()
    }

    private fun isObstructed(bounds: RectBounds, window: WindowSnapshot): Boolean {
        val viewport = window.freeform
        val physical = if (viewport == null) bounds else viewport.screenTarget(bounds) ?: return true
        return window.obstructions.any { it.overlaps(physical) }
    }

    private fun isSideControl(bounds: RectBounds, window: WindowSnapshot, root: RectBounds?): Boolean {
        fun nearSide(frame: RectBounds): Boolean {
            val x = (bounds.centerX - frame.left) / frame.width
            return frame.contains(bounds) && (x <= .25f || x >= .75f)
        }
        return nearSide(RectBounds(0, 0, window.screenWidth, window.screenHeight)) && (root == null || nearSide(root))
    }

    private fun isInScrollingContent(node: NodeSnapshot, nodes: List<NodeSnapshot>): Boolean {
        var index = node.parentIndex
        while (index in 0 until node.index) {
            val parent = nodes.getOrNull(index) ?: break
            if (parent.className.endsWith("RecyclerView") || parent.className.endsWith("GridView") ||
                parent.className.endsWith("ListView") || parent.className.endsWith("ScrollView")) return true
            index = parent.parentIndex
        }
        return false
    }

    private fun enclosingCountdownButton(node: NodeSnapshot, nodes: List<NodeSnapshot>): Boolean {
        val parent = nodes.getOrNull(node.parentIndex) ?: return false
        val button = nodes.getOrNull(parent.parentIndex) ?: return false
        return button.className == "android.widget.Button" && button.isClickable && button.isEnabled &&
            button.bounds == parent.bounds && COUNTDOWN_BUTTON.matches(button.effectiveText)
    }

    private fun isSplashImageSibling(
        node: NodeSnapshot, window: WindowSnapshot, children: Map<Int, List<NodeSnapshot>>, surface: RectBounds
    ): Boolean {
        val parent = window.nodes.getOrNull(node.parentIndex) ?: return false
        return parent.shortId == "splash_container" && parent.bounds.contains(node.bounds) &&
            isTopRight(node.bounds, surface) && children[parent.index].orEmpty().any {
                it.isVisibleToUser && it.shortId == "splash_image" && it.className == "android.widget.ImageView" &&
                    it.bounds.width >= surface.width * .85f && it.bounds.height >= surface.height * .70f
            }
    }

    private fun isTopRight(bounds: RectBounds, surface: RectBounds, maxWidth: Float = .22f): Boolean =
        bounds.centerX > surface.centerX && inTopCorner(bounds, surface, maxWidth)

    private fun isOnCreative(
        node: NodeSnapshot, nodes: List<NodeSnapshot>, surface: RectBounds, callsToAction: List<NodeSnapshot>
    ): Boolean = ancestorWithin(node, nodes, 4) { container ->
        container.parentIndex >= 0 && container.bounds.contains(node.bounds) &&
            container.bounds.width >= surface.width * .80f && container.bounds.height >= surface.height * .70f &&
            callsToAction.any { action ->
                isInside(action, container, nodes) &&
                    (action.bounds.top >= surface.top + surface.height * .55f ||
                        action.isClickable && action.className == "android.widget.ImageView" &&
                        action.bounds.width >= surface.width * .50f && action.bounds.height >= surface.height * .70f &&
                        !action.bounds.overlaps(node.bounds))
            }
    } != null

    private fun isNativeAdClose(
        node: NodeSnapshot, window: WindowSnapshot, children: Map<Int, List<NodeSnapshot>>, surface: RectBounds
    ): Boolean {
        val parent = window.nodes.getOrNull(node.parentIndex) ?: return false
        if (!parent.isVisibleToUser || !parent.bounds.contains(node.bounds)) return false
        val desc = node.contentDescription.trim()
        if (node.className == "android.widget.ImageButton" && desc.equals("Interstitial close button", true)) {
            return inTopCorner(node.bounds, surface, .22f)
        }

        if (node.shortId == "close_btn" && parent.shortId == "ad_area" &&
            (node.className == "android.widget.TextView" || node.className == "android.widget.Button") &&
            (node.effectiveText.isEmpty() || AD_CLOSE_SYMBOL_REGEX.matches(node.effectiveText))) {
            val siblings = children[parent.index].orEmpty()
            return isAdCard(parent.bounds, surface, maxHeight = .90f) &&
                isTopRight(node.bounds, parent.bounds) &&
                ancestorWithin(parent, window.nodes, 6) { it.className == "android.webkit.WebView" } != null &&
                siblings.any { it.isVisibleToUser && it.effectiveText == "广告" && parent.bounds.contains(it.bounds) } &&
                siblings.any { it.isVisibleToUser && it.effectiveText == "反馈" && it.bounds.centerX < parent.bounds.centerX &&
                    kotlin.math.abs(it.bounds.centerY - node.bounds.centerY) <= node.bounds.height }
        }

        if (node.shortId == "ad_close" && desc == "关闭" && parent.shortId == "ad_close_area") {
            val card = ancestorWithin(node, window.nodes, 5) { it.shortId == "ad_container" } ?: return false
            return isAdCard(card.bounds, surface) && inTopCorner(node.bounds, card.bounds) &&
                children[parent.index].orEmpty().singleOrNull()?.index == node.index &&
                listOf("ad_log", "ad_icon").all { id ->
                    window.nodes.any { it.shortId == id && isInside(it, card, window.nodes) }
                }
        }

        if (node.shortId == "ksad_auto_close_btn" && node.className == "android.widget.ImageView") {
            val closeArea = ancestorWithin(node, window.nodes, 3) { it.shortId == "ksad_interstitial_auto_close" }
                ?: return false
            val card = window.nodes.getOrNull(closeArea.parentIndex) ?: return false
            return card.shortId == "ksad_interstitial_native" && closeArea.bounds.contains(node.bounds) &&
                isAdCard(card.bounds, surface) && inTopCorner(node.bounds, card.bounds) &&
                window.nodes.any { it.shortId == "ksad_logo_text" && it.effectiveText == "广告" &&
                    isInside(it, card, window.nodes) }
        }

        if (node.className == "android.view.View" && node.bounds == parent.bounds &&
            WEB_AD_CLOSE.matches(parent.contentDescription.trim())) {
            val card = ancestorWithin(parent, window.nodes, 12) { isAdCard(it.bounds, surface) } ?: return false
            return inTopCorner(node.bounds, card.bounds) && children[parent.index].orEmpty().singleOrNull()?.index == node.index &&
                window.nodes.any { it.effectiveText == "广告" && isInside(it, card, window.nodes) }
        }

        val anonymousImage = node.className == "android.widget.ImageView" && node.viewId.isEmpty() &&
            node.effectiveText.isEmpty() && children[node.index].isNullOrEmpty()
        val activity = window.activityName.substringAfterLast('.')
        if (anonymousImage && activity == "PortraitADActivity") {
            val web = children[parent.index].orEmpty().singleOrNull {
                it.isVisibleToUser && it.className == "android.webkit.WebView" &&
                    it.bounds.width >= surface.width * .85f && it.bounds.height >= surface.height * .70f
            } ?: return false
            return isTopRight(node.bounds, surface) && window.nodes.any {
                it.className == "android.webkit.WebView" && it.effectiveText == "优量汇-插屏视频endcard" &&
                    ancestorWithin(it, window.nodes, 2) { ancestor -> ancestor.index == web.index } != null && it.isVisibleToUser
            }
        }

        if (anonymousImage && activity == "MobRewardVideoActivity") {
            val bounds = node.bounds
            val right = (bounds.centerX - surface.left) / surface.width
            val aspect = bounds.width.toFloat() / bounds.height
            return right >= .80f && bounds.top <= surface.top + surface.height * .38f &&
                bounds.width <= surface.width * .10f && aspect in .85f..1.18f &&
                parent.bounds.width >= surface.width * .85f && parent.bounds.height >= surface.height * .70f &&
                window.nodes.singleOrNull { feedback ->
                    feedback.isClickable && feedback.effectiveText == "反馈" &&
                        feedback.bounds.centerX <= surface.left + surface.width * .30f &&
                        feedback.bounds.centerY - bounds.centerY in -bounds.height.toFloat()..(2f * bounds.height) &&
                        isInside(feedback, parent, window.nodes)
                } != null
        }

        if (node.effectiveText.isNotEmpty() || node.viewId.isNotEmpty() ||
            node.className != "android.view.View" && node.className != "android.widget.LinearLayout" ||
            !isTopRight(node.bounds, surface) ||
            parent.bounds.width < surface.width * .85f || parent.bounds.height < surface.height * .70f) return false
        val siblings = children[parent.index].orEmpty()
        val badge = siblings.singleOrNull { it.isVisibleToUser && !it.isClickable && it.effectiveText == "互动广告" }
            ?: return false
        val gap = node.bounds.left - badge.bounds.right
        if (gap < 0 || gap > badge.bounds.height ||
            kotlin.math.abs(node.bounds.centerY - badge.bounds.centerY) > minOf(node.bounds.height, badge.bounds.height) / 4 ||
            children[node.index].orEmpty().any { it.isClickable || it.effectiveText.isNotEmpty() }) return false
        return window.nodes.any { it.effectiveText == "广告" && isInside(it, parent, window.nodes) } ||
            siblings.any { it.isVisibleToUser && it.isClickable && it.className == "android.widget.ImageView" &&
                it.bounds == parent.bounds } &&
            window.nodes.any { it.className == "android.view.SurfaceView" && it.bounds == parent.bounds &&
                isInside(it, parent, window.nodes) }
    }

    private fun isInteractiveVideoClose(
        node: NodeSnapshot, window: WindowSnapshot, children: Map<Int, List<NodeSnapshot>>, surface: RectBounds
    ): Boolean {
        if (!window.complete || node.className != "android.view.View" || !children[node.index].isNullOrEmpty() ||
            !isTopRight(node.bounds, surface, .22f)) return false
        val scene = window.nodes.getOrNull(node.parentIndex) ?: return false
        if (!scene.isVisibleToUser || scene.isClickable || !scene.bounds.contains(node.bounds) ||
            scene.bounds.width < surface.width * .85f || scene.bounds.height < surface.height * .70f) return false
        val badge = children[scene.index].orEmpty().singleOrNull {
            it.isVisibleToUser && !it.isClickable && it.effectiveText == "互动广告" &&
                it.bounds.right <= node.bounds.left && node.bounds.left - it.bounds.right <= it.bounds.height &&
                kotlin.math.abs(it.bounds.centerY - node.bounds.centerY) <= node.bounds.height / 4f
        } ?: return false
        if (!scene.bounds.contains(badge.bounds)) return false
        val sceneNodes = window.nodes.filter { isInside(it, scene, window.nodes) }
        return sceneNodes.any { it.className == "android.view.SurfaceView" &&
            it.bounds.height >= scene.bounds.height * .90f && it.bounds.width >= scene.bounds.width * .65f } &&
            sceneNodes.any { it.effectiveText == "广告" && it.bounds.top > scene.bounds.centerY } &&
            sceneNodes.any { it.shortId == "sig_ad_privacy_ad_logo" && it.bounds.top > scene.bounds.centerY } &&
            sceneNodes.any { it.isClickable && it.bounds.top >= node.bounds.bottom &&
                it.bounds.width >= scene.bounds.width * .65f && it.bounds.height >= scene.bounds.height * .55f }
    }

    private fun isAdCard(bounds: RectBounds, surface: RectBounds, maxHeight: Float = .85f): Boolean =
        surface.contains(bounds) && bounds.width >= surface.width * .55f && bounds.height >= surface.height * .35f &&
            bounds.height <= surface.height * maxHeight && bounds.top >= surface.top + surface.height * .07f

    private fun inTopCorner(bounds: RectBounds, frame: RectBounds, maxWidth: Float = .16f): Boolean =
        frame.contains(bounds) && bounds.width <= frame.width * maxWidth && bounds.height <= frame.height * .14f &&
            bounds.top <= frame.top + frame.height * .15f &&
            (bounds.left <= frame.left + frame.width * .15f || bounds.right >= frame.right - frame.width * .15f)

    private fun isInside(node: NodeSnapshot, container: NodeSnapshot, nodes: List<NodeSnapshot>): Boolean =
        node.isVisibleToUser && container.bounds.contains(node.bounds) &&
            ancestorWithin(node, nodes, node.depth) { it.index == container.index } != null

    private fun ancestorWithin(
        node: NodeSnapshot, nodes: List<NodeSnapshot>, levels: Int, matches: (NodeSnapshot) -> Boolean
    ): NodeSnapshot? {
        var current = node
        repeat(levels) {
            if (current.parentIndex !in 0 until current.index) return null
            current = nodes.getOrNull(current.parentIndex) ?: return null
            if (matches(current)) return current
        }
        return null
    }

    private fun findSiblingBanners(
        node: NodeSnapshot, window: WindowSnapshot, children: Map<Int, List<NodeSnapshot>>, structured: Boolean = false
    ): List<NodeSnapshot> {
        if (!structured && !TargetEvidence.isSplashCountdown(node)) return emptyList()
        return children[node.parentIndex].orEmpty().filter { sibling ->
            sibling.index != node.index &&
                (!sibling.isVisibleToUser || sibling.bounds.width >= window.screenWidth * .50f &&
                    sibling.bounds.top <= window.screenHeight * .20f && sibling.bounds.height <= window.screenHeight * .20f) &&
                children[sibling.index].orEmpty().any {
                    it.effectiveText == "刚刚" || it.effectiveText.equals("just now", ignoreCase = true)
                }
        }
    }

    /** A lone countdown can share its scene with an SDK's explicit ad-click region. */
    private fun isPromotionalCountdown(
        node: NodeSnapshot, window: WindowSnapshot, children: Map<Int, List<NodeSnapshot>>, hasBanner: Boolean
    ): Boolean {
        if (!node.isClickable || node.className != "android.widget.TextView" ||
            !TargetEvidence.isSplashJumpCountdown(node) || !children[node.index].isNullOrEmpty()) return false
        val parent = window.nodes.getOrNull(node.parentIndex) ?: return false
        if (!parent.bounds.contains(node.bounds) || parent.bounds.width < window.screenWidth * .85f ||
            parent.bounds.height < window.screenHeight * .70f) return false
        // The observed banner disappears while the countdown stays in its displaced position.
        if (!hasBanner && node.bounds.top < parent.bounds.top + parent.bounds.height * .14f) return false
        val prefix = node.shortId.substringBefore("splash_jump")
        val interaction = children[parent.index].orEmpty().singleOrNull {
            it.isVisibleToUser && it.shortId == "${prefix}splash_interaction_view" &&
                it.bounds.top >= parent.bounds.top + parent.bounds.height * .5f
        } ?: return false
        return window.nodes.any {
            it.isClickable && it.shortId == "${prefix}shake_click_region" && isInside(it, interaction, window.nodes)
        }
    }

    private fun hasCountdownSiblings(
        node: NodeSnapshot, window: WindowSnapshot, children: Map<Int, List<NodeSnapshot>>
    ): Boolean {
        if (node.effectiveText != "跳过" && !node.effectiveText.equals("skip", ignoreCase = true)) return false
        val parent = window.nodes.getOrNull(node.parentIndex) ?: return false
        if (!isButtonSized(parent.bounds, window.screenWidth, window.screenHeight)) return false
        val siblings = children[parent.index].orEmpty().filter { it.index != node.index && it.isVisibleToUser }
        return siblings.size in 1..2 && siblings.all { parent.bounds.contains(it.bounds) } &&
            parent.bounds.contains(node.bounds) && siblings.count { COUNTDOWN_PART.matches(it.effectiveText) } == 1 &&
            siblings.all { COUNTDOWN_PART.matches(it.effectiveText) || it.effectiveText in listOf("|", "｜", "·", "•", "/") }
    }

    private fun provesSplitSplashPill(
        node: NodeSnapshot, window: WindowSnapshot, children: Map<Int, List<NodeSnapshot>>, surface: RectBounds
    ): Boolean {
        if (!window.complete || !node.isClickable || node.className != "android.widget.LinearLayout" ||
            !isTopRight(node.bounds, surface, .24f)) return false
        val controls = children[node.index].orEmpty().filter { it.isVisibleToUser }
        if (controls.size != 2 || controls.any { it.isClickable || !it.isEnabled || !node.bounds.contains(it.bounds) ||
                it.className != "android.widget.TextView" }) return false
        val verb = controls.singleOrNull { it.effectiveText == "跳过" || it.effectiveText.equals("skip", true) }
            ?: return false
        if (controls.none { it.shortId == "tv_close_time" && COUNTDOWN_PART.matches(it.effectiveText) &&
                !it.bounds.overlaps(verb.bounds) }) return false
        val creative = window.nodes.getOrNull(node.parentIndex) ?: return false
        if (!creative.isVisibleToUser || !creative.bounds.contains(node.bounds) ||
            creative.bounds.width < surface.width * .85f || creative.bounds.height < surface.height * .70f) return false
        val siblings = children[creative.index].orEmpty()
        return siblings.any { it.isVisibleToUser && !it.isClickable && it.shortId == "iv_ad_icon" &&
            it.className == "android.widget.ImageView" && creative.bounds.contains(it.bounds) &&
            it.bounds.top > creative.bounds.centerY } && window.nodes.any {
            CREATIVE_CTA.matches(it.effectiveText) && isInside(it, creative, window.nodes) &&
                it.bounds.top > creative.bounds.centerY
        }
    }

    private fun provesFloatingVideoClose(node: NodeSnapshot, window: WindowSnapshot, surface: RectBounds): Boolean {
        val creative = window.nodes.getOrNull(node.parentIndex) ?: return false
        return !window.behindTopWindow && creative.bounds.contains(node.bounds) &&
            node.bounds.centerX <= surface.left + surface.width * .20f && node.bounds.top >= surface.centerY &&
            window.nodes.any { it.contentDescription == "NativeVideoAdView" &&
                isInside(it, creative, window.nodes) && it.bounds.width >= creative.bounds.width * .95f &&
                it.bounds.height >= creative.bounds.height * .95f }
    }

    private fun isDisplacedUpperControl(bounds: RectBounds, window: WindowSnapshot, root: RectBounds?): Boolean {
        fun inUpperSide(frame: RectBounds): Boolean {
            val x = (bounds.centerX - frame.left) / frame.width
            val y = (bounds.centerY - frame.top) / frame.height
            return frame.contains(bounds) && (x <= .30f || x >= .70f) && y <= .38f &&
                bounds.width <= frame.width * .24f && bounds.height <= frame.height * .10f
        }
        return inUpperSide(RectBounds(0, 0, window.screenWidth, window.screenHeight)) &&
            (root == null || inUpperSide(root))
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

        // A small container may combine the target and a separate label into one action.
        if (node.parentIndex in window.nodes.indices) {
            val parent = window.nodes[node.parentIndex]
            val parentClipped = parent.bounds.clippedToScreen(window.screenWidth, window.screenHeight)
            if (parentClipped != null && isButtonSized(parent.bounds, window.screenWidth, window.screenHeight)) {
                val pLower = parent.effectiveText.lowercase()
                if (pLower.isNotEmpty() && SEMANTIC_BLACKLIST.any { pLower.contains(it) }) return true
                if (childrenMap[parent.index].orEmpty().any { sibling ->
                        sibling.isVisibleToUser && SEMANTIC_BLACKLIST.any { sibling.effectiveText.lowercase().contains(it) }
                    }) return true
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
            if (pClipped == null || !isButtonSized(parent.bounds, screenWidth, screenHeight) ||
                !parent.bounds.contains(node.bounds)) {
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
