package com.flashskip.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipMatcherTest {

    private val screenW = 1080
    private val screenH = 2400
    private val topRight = RectBounds(860, 120, 1040, 210)
    private val fullScreen = RectBounds(0, 0, screenW, screenH)
    private val centerScreen = RectBounds(360, 1100, 720, 1220)
    private val ruleIndex = RuleIndex.defaultBuiltin()

    private fun makeWindow(
        pkg: String = "com.example.app",
        activity: String = "com.example.app.SplashActivity",
        isFullScreen: Boolean = true,
        nodes: List<NodeSnapshot>
    ) = WindowSnapshot(pkg, activity, screenW, screenH, isFullScreen, nodes)

    private fun node(
        index: Int,
        parentIndex: Int = -1,
        text: String = "",
        desc: String = "",
        viewId: String = "",
        bounds: RectBounds = topRight,
        visible: Boolean = true,
        clickable: Boolean = true,
        enabled: Boolean = true,
        depth: Int = 1,
        className: String = "android.widget.TextView"
    ) = NodeSnapshot(
        index = index,
        parentIndex = parentIndex,
        className = className,
        viewId = viewId,
        text = text,
        contentDescription = desc,
        bounds = bounds,
        isVisibleToUser = visible,
        isClickable = clickable,
        isEnabled = enabled,
        depth = depth
    )

    @Test
    fun `matches valid skip text variants`() {
        val validTexts = listOf(
            "跳过", "跳过广告", "点击跳过", "跳过>", "跳过 3s", "跳过3S", "跳过 5秒", "跳过3",
            "跳过 | 3s", "跳过(3s)", "3s 跳过", "3 跳过", "3s|跳过",
            "Skip", "Skip Ad", "Skip 3s", "3s Skip", "关闭广告"
        )
        for (txt in validTexts) {
            val win = makeWindow(nodes = listOf(node(0, text = txt)))
            val decision = SkipMatcher.findBestMatch(win, ruleIndex)
            assertNotNull("Expected match for '$txt'", decision)
            assertEquals(txt, decision!!.matchedText)
        }
    }

    @Test
    fun `blocks blacklisted and non-ad skip texts`() {
        val invalidTexts = listOf(
            "跳过引导", "跳过此步", "跳过登录", "跳过片头", "跳过片尾",
            "跳过验证", "跳过更新", "跳过此题", "点击跳过领红包",
            "跳过并领取", "跳过立即下载", "Skip intro", "Skip step", "Skip login"
        )
        for (txt in invalidTexts) {
            val win = makeWindow(nodes = listOf(node(0, text = txt)))
            val decision = SkipMatcher.findBestMatch(win, ruleIndex)
            assertNull("Must NOT match '$txt'", decision)
        }
    }

    @Test
    fun `empty string text does not mask contentDescription`() {
        val win = makeWindow(
            nodes = listOf(
                node(0, text = "", desc = "跳过 3s", clickable = false)
            )
        )
        val decision = SkipMatcher.findBestMatch(win, ruleIndex)
        assertNotNull(decision)
        assertEquals("desc", decision!!.matchedVia)
        assertEquals("跳过 3s", decision.matchedText)
        assertTrue(decision.forceGestureOnly)
    }

    @Test
    fun `fullscreen clickable ad container must NOT be climbed or clicked`() {
        // Node 0: full-screen clickable ad landing container
        // Node 1: small non-clickable "跳过" TextView inside it
        val win = makeWindow(
            nodes = listOf(
                node(
                    index = 0,
                    parentIndex = -1,
                    bounds = fullScreen,
                    clickable = true,
                    depth = 0,
                    className = "android.widget.FrameLayout"
                ),
                node(
                    index = 1,
                    parentIndex = 0,
                    text = "跳过 3s",
                    bounds = topRight,
                    clickable = false,
                    depth = 1
                )
            )
        )
        val decision = SkipMatcher.findBestMatch(win, ruleIndex)
        assertNotNull(decision)
        assertEquals(1, decision!!.targetNodeIndex)
        // Must NOT climb to node 0!
        assertEquals(1, decision.clickNodeIndex)
        assertTrue(decision.forceGestureOnly)
        assertEquals(topRight, decision.targetBounds)
    }

    @Test
    fun `small clickable parent container IS safely climbed`() {
        val wrapperBounds = RectBounds(850, 110, 1050, 220)
        val win = makeWindow(
            nodes = listOf(
                node(index = 0, parentIndex = -1, bounds = fullScreen, clickable = false, depth = 0),
                node(index = 1, parentIndex = 0, bounds = wrapperBounds, clickable = true, depth = 1),
                node(index = 2, parentIndex = 1, text = "跳过", bounds = topRight, clickable = false, depth = 2)
            )
        )
        val decision = SkipMatcher.findBestMatch(win, ruleIndex)
        assertNotNull(decision)
        assertEquals(2, decision!!.targetNodeIndex)
        assertEquals(1, decision.clickNodeIndex)
        assertFalse(decision.forceGestureOnly)
    }

    @Test
    fun `rejects oversized skip node even if text matches`() {
        val hugeBanner = RectBounds(0, 0, screenW, 600)
        val win = makeWindow(nodes = listOf(node(0, text = "跳过", bounds = hugeBanner)))
        assertNull(SkipMatcher.findBestMatch(win, ruleIndex))
    }

    @Test
    fun `rejects generic match sitting in center content area`() {
        val win = makeWindow(nodes = listOf(node(0, text = "跳过", bounds = centerScreen)))
        assertNull(SkipMatcher.findBestMatch(win, ruleIndex))
    }

    @Test
    fun `rejects center button even during mid-slide window transition animation`() {
        // Window is mid-slide (+600px in X): screen-space looks edge-aligned, window-relative is center!
        val shiftedRoot = RectBounds(600, 121, 1800, 2664)
        val shiftedCenterBtn = RectBounds(1056, 1773, 1344, 1930)
        val win = WindowSnapshot(
            packageName = "com.example.app",
            activityName = "com.example.app.MainActivity",
            screenWidth = 1200,
            screenHeight = 2664,
            isFullScreen = true,
            nodes = listOf(
                node(0, bounds = shiftedRoot, clickable = false, depth = 0, className = "android.widget.FrameLayout"),
                node(1, parentIndex = 0, text = "跳过", bounds = shiftedCenterBtn, clickable = true, depth = 1)
            )
        )
        assertNull(SkipMatcher.findBestMatch(win, ruleIndex))
    }

    @Test
    fun `rejects id-only match without visible ad context and allows with ad context`() {
        // Without ad context (MainActivity + invisible countdown) -> must reject
        val noContextWin = makeWindow(
            activity = "com.example.app.MainActivity",
            nodes = listOf(
                node(0, bounds = fullScreen, clickable = false, depth = 0),
                node(1, parentIndex = 0, viewId = "com.example.app:id/btn_skip", bounds = topRight),
                node(2, parentIndex = 0, text = "60s后重发", bounds = centerScreen, visible = true),
                node(3, parentIndex = 0, text = "广告", bounds = RectBounds(20, 20, 80, 50), visible = false)
            )
        )
        assertNull(SkipMatcher.findBestMatch(noContextWin, ruleIndex))

        // With visible ad tag -> allows
        val adContextWin = makeWindow(
            activity = "com.example.app.MainActivity",
            nodes = listOf(
                node(0, bounds = fullScreen, clickable = false, depth = 0),
                node(1, parentIndex = 0, viewId = "com.example.app:id/btn_skip", bounds = topRight),
                node(2, parentIndex = 0, text = "广告", bounds = RectBounds(20, 2200, 100, 2250), visible = true)
            )
        )
        val hit = SkipMatcher.findBestMatch(adContextWin, ruleIndex)
        assertNotNull(hit)
        assertEquals("id", hit!!.matchedVia)
    }

    @Test
    fun `close symbol X only matches under explicit ad context`() {
        val normalWin = makeWindow(
            activity = "com.example.app.MainActivity",
            nodes = listOf(
                node(0, bounds = fullScreen, clickable = false, depth = 0),
                node(1, parentIndex = 0, text = "×", bounds = topRight)
            )
        )
        assertNull(SkipMatcher.findBestMatch(normalWin, ruleIndex))

        val adWin = makeWindow(
            activity = "com.example.app.SplashActivity",
            nodes = listOf(
                node(0, bounds = fullScreen, clickable = false, depth = 0),
                node(1, parentIndex = 0, text = "×", bounds = topRight),
                node(2, parentIndex = 0, text = "互动广告", bounds = RectBounds(30, 2200, 200, 2260))
            )
        )
        val hit = SkipMatcher.findBestMatch(adWin, ruleIndex)
        assertNotNull(hit)
        assertEquals("ad_close", hit!!.matchedVia)
    }

    @Test
    fun `matches self-drawn twist-ad skip view without clicking ad badge or loading logo`() {
        // 1. Empty loading splash must NOT match
        val loadingWin = WindowSnapshot(
            packageName = "com.example.campus",
            activityName = "com.example.campus.splash.SplashActivity",
            screenWidth = 1200,
            screenHeight = 2664,
            isFullScreen = true,
            nodes = listOf(
                node(0, bounds = RectBounds(0, 0, 1200, 2664), clickable = false, depth = 0, className = "android.widget.FrameLayout"),
                node(1, parentIndex = 0, viewId = "com.example.campus:id/flAd", bounds = RectBounds(0, 0, 1200, 2369), clickable = false, depth = 6, className = "android.widget.FrameLayout"),
                node(2, parentIndex = 0, viewId = "com.example.campus:id/iv_info", bounds = RectBounds(371, 906, 830, 1365), clickable = false, depth = 6, className = "android.widget.ImageView")
            )
        )
        assertNull(SkipMatcher.findBestMatch(loadingWin, ruleIndex))

        // 2. Real captured twist-ad layout: Node 2 is self-drawn circular skip [52,2025,183,2156],
        // Node 4 is "广告" TextView [66,2192,171,2238], Node 5 is "扭一扭或点击了解更多"
        val realTwistAdWin = WindowSnapshot(
            packageName = "com.example.campus",
            activityName = "com.example.campus.splash.SplashActivity",
            screenWidth = 1200,
            screenHeight = 2664,
            isFullScreen = true,
            nodes = listOf(
                node(0, bounds = RectBounds(0, 0, 1200, 2664), clickable = false, depth = 0, className = "android.widget.FrameLayout"),
                node(1, parentIndex = 0, bounds = RectBounds(0, 0, 1200, 2369), clickable = false, depth = 7, className = "android.widget.FrameLayout"),
                node(2, parentIndex = 1, bounds = RectBounds(52, 2025, 183, 2156), clickable = true, depth = 8, className = "android.view.View"),
                node(3, parentIndex = 1, bounds = RectBounds(0, 0, 1200, 2369), clickable = false, depth = 8, className = "android.widget.FrameLayout"),
                node(4, parentIndex = 3, bounds = RectBounds(66, 2192, 171, 2238), clickable = true, depth = 9, className = "android.widget.TextView"),
                node(5, parentIndex = 3, text = "扭一扭或点击了解更多", desc = "f4208a", bounds = RectBounds(438, 1944, 858, 1994), clickable = false, depth = 21, className = "android.widget.TextView")
            )
        )
        val decision = SkipMatcher.findBestMatch(realTwistAdWin, ruleIndex)
        assertNotNull(decision)
        assertEquals(2, decision!!.targetNodeIndex)
        assertEquals("self_drawn_skip", decision.matchedVia)
        assertTrue(decision.isShakeAd)
        assertTrue(decision.forceGestureOnly)
    }

    @Test
    fun `rejects id containing skip_guide or skip_login even on splash`() {
        val win = makeWindow(
            nodes = listOf(
                node(0, bounds = fullScreen, clickable = false, depth = 0),
                node(1, parentIndex = 0, viewId = "com.example.app:id/btn_skip_guide", bounds = topRight)
            )
        )
        assertNull(SkipMatcher.findBestMatch(win, ruleIndex))
    }

    @Test
    fun `detects shake ad in single pass and prefers top-right real skip over lower-score candidate`() {
        val bottomLeft = RectBounds(30, 2100, 200, 2200)
        val win = makeWindow(
            nodes = listOf(
                node(0, bounds = fullScreen, clickable = false, depth = 0),
                node(1, parentIndex = 0, text = "摇一摇了解更多", bounds = centerScreen, clickable = false),
                node(2, parentIndex = 0, viewId = "com.example.app:id/skip_btn", bounds = bottomLeft),
                node(3, parentIndex = 0, text = "跳过 4s", bounds = topRight)
            )
        )
        val hit = SkipMatcher.findBestMatch(win, ruleIndex)
        assertNotNull(hit)
        assertTrue(hit!!.isShakeAd)
        assertEquals(3, hit.targetNodeIndex)
    }

    @Test
    fun `banking and system packages are excluded by PackagePolicy`() {
        assertTrue(PackagePolicy.isExcludedPackage("com.eg.android.AlipayGphone"))
        assertTrue(PackagePolicy.isExcludedPackage("com.tencent.mm"))
        assertTrue(PackagePolicy.isExcludedPackage("com.icbc"))
        assertTrue(PackagePolicy.isExcludedPackage("com.miui.home"))
        assertTrue(PackagePolicy.isExcludedPackage("com.oplus.camera"))
        assertFalse(PackagePolicy.isExcludedPackage("com.sina.weibo"))
    }
}
