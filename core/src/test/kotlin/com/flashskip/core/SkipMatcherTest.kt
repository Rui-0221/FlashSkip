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
    fun `business search suggestion is excluded even when the page also has an ad badge`() {
        val window = makeWindow(activity = "com.example.MainActivity", nodes = listOf(
            node(0, bounds = fullScreen, depth = 0, clickable = false),
            node(1, 0, text = "广告", bounds = RectBounds(15, 810, 80, 845)),
            node(2, 0, bounds = RectBounds(0, 100, 1080, 500), className = "android.widget.GridView"),
            node(3, 2, text = "跳过广告", bounds = topRight)
        ))
        assertNull(SkipMatcher.findBestMatch(window, ruleIndex))
    }

    @Test
    fun `plain skip on a preference page never qualifies as a splash control`() {
        val window = makeWindow(nodes = listOf(
            node(0, bounds = fullScreen, depth = 0, clickable = false),
            node(1, 0, text = "跳过", viewId = "id/tv_skip"),
            node(2, 0, text = "选择感兴趣的内容", bounds = centerScreen, clickable = false)
        ))
        assertNull(SkipMatcher.findBestMatch(window, ruleIndex))
    }

    @Test
    fun `shake call to action alone does not authorize an anonymous countdown label`() {
        val window = makeWindow(activity = "com.example.MainActivity", nodes = listOf(
            node(0, bounds = fullScreen, depth = 0, clickable = false),
            node(1, 0, text = "4 | 跳过"),
            node(2, 0, text = "摇一摇有惊喜", bounds = centerScreen, clickable = false)
        ))
        assertNull(SkipMatcher.findBestMatch(window, ruleIndex))
    }

    @Test
    fun `displaced native countdown beside a promotion is excluded while an independent close can win`() {
        val trapBounds = RectBounds(860, 370, 1040, 448)
        val trap = node(4, 1, text = "5 | 跳过", viewId = "id/demo_splash_jump_tv", bounds = trapBounds)
        val promoBounds = RectBounds(120, 1500, 950, 2030)
        val window = makeWindow(nodes = listOf(
            node(0, bounds = fullScreen, depth = 0, clickable = false),
            node(1, 0, bounds = fullScreen, clickable = false),
            node(2, 1, bounds = RectBounds(22, 145, 1058, 330), clickable = false),
            node(3, 2, text = "刚刚", bounds = RectBounds(920, 160, 1030, 210), clickable = false),
            trap,
            node(5, 1, viewId = "id/demo_splash_interaction_view", bounds = promoBounds, clickable = false),
            node(6, 5, viewId = "id/demo_shake_click_region", text = "点击按钮了解更多", bounds = promoBounds),
            node(7, 1, text = "广告", bounds = RectBounds(18, 2050, 88, 2100), clickable = false)
        ))
        assertNull(SkipMatcher.findBestMatch(window, ruleIndex))
        val afterBanner = window.copy(nodes = window.nodes.map {
            if (it.index in 2..3) it.copy(isVisibleToUser = false, text = "") else it
        })
        assertNull(SkipMatcher.findBestMatch(afterBanner, ruleIndex))
        val close = node(8, 1, desc = "Interstitial close button", bounds = RectBounds(12, 45, 78, 111),
            className = "android.widget.ImageButton")
        assertEquals(close.index, SkipMatcher.findBestMatch(window.copy(nodes = window.nodes + close), ruleIndex)?.targetNodeIndex)
        val genuine = window.copy(nodes = window.nodes.map {
            if (it.index == 6) it.copy(viewId = "", text = "") else it
        })
        assertEquals(trap.index, SkipMatcher.findBestMatch(genuine, ruleIndex)?.targetNodeIndex)
    }

    @Test
    fun `anonymous header close requires an adjacent interactive ad label on the creative`() {
        val badge = node(2, 1, text = "互动广告", bounds = RectBounds(700, 120, 840, 210), clickable = false)
        val close = node(3, 1, bounds = topRight, className = "android.view.View")
        val window = makeWindow(activity = "com.example.MainActivity", nodes = listOf(
            node(0, bounds = fullScreen, depth = 0, clickable = false),
            node(1, 0, bounds = fullScreen, clickable = false),
            badge, close, node(4, 1, text = "广告", bounds = RectBounds(20, 2090, 70, 2130), clickable = false)
        ))
        val decision = SkipMatcher.findBestMatch(window, ruleIndex)
        assertEquals(close.index, decision?.targetNodeIndex)
        assertTrue(decision!!.nativeOnly)
        assertNull(SkipMatcher.findBestMatch(window.copy(obstructions = listOf(topRight)), ruleIndex))
        assertNull(SkipMatcher.findBestMatch(window.copy(nodes = window.nodes.map {
            if (it == badge) it.copy(bounds = RectBounds(700, 280, 840, 370))
            else if (it.index == 4) it.copy(text = "业务") else it
        }), ruleIndex))
    }

    @Test
    fun `named interstitial close resolves to the small native button within the ad card`() {
        val card = RectBounds(140, 700, 940, 1900)
        val closeBounds = RectBounds(850, 725, 915, 790)
        val area = node(2, 1, viewId = "id/ad_close_area", bounds = closeBounds, clickable = false)
        val close = node(3, 2, desc = "关闭", viewId = "id/ad_close", bounds = closeBounds,
            className = "android.widget.ImageView")
        val window = makeWindow(activity = "com.example.MainActivity", nodes = listOf(
            node(0, bounds = fullScreen, depth = 0, clickable = false),
            node(1, 0, viewId = "id/ad_container", bounds = card, clickable = false),
            area, close,
            node(4, 1, viewId = "id/ad_log", bounds = RectBounds(165, 735, 250, 772), clickable = false),
            node(5, 1, viewId = "id/ad_icon", bounds = RectBounds(180, 1480, 260, 1560), clickable = false)
        ))
        val decision = SkipMatcher.findBestMatch(window, ruleIndex)
        assertEquals(close.index, decision?.clickNodeIndex)
        assertTrue(decision!!.nativeOnly)
        assertNull(SkipMatcher.findBestMatch(window.copy(nodes = window.nodes.map {
            if (it.index == 4) it.copy(viewId = "id/panel_logo") else it
        }), ruleIndex))
        assertNull(SkipMatcher.findBestMatch(window.copy(complete = false), ruleIndex))
    }

    @Test
    fun `web ad close uses the native leaf on a stable layout even if the creative says shake`() {
        val card = RectBounds(140, 700, 940, 1900)
        val closeBounds = RectBounds(850, 725, 915, 790)
        val window = makeWindow(activity = "com.example.MainActivity", nodes = listOf(
            node(0, bounds = fullScreen, depth = 0, clickable = false),
            node(1, 0, bounds = card, clickable = false),
            node(2, 1, desc = "webview-close:abcdef:0:1", bounds = closeBounds, clickable = false),
            node(3, 2, bounds = closeBounds, className = "android.view.View"),
            node(4, 1, text = "广告", bounds = RectBounds(165, 735, 250, 772), clickable = false),
            node(5, 1, text = "摇一摇", bounds = centerScreen, clickable = false)
        )).copy(windowId = 3)
        val machine = SkipStateMachine(ruleIndex)
        machine.onWindowStateChanged(window.packageName, window.activityName, true, 1000L, windowId = 3,
            confirmedFullscreenActivity = true, confirmedForegroundEntry = true)
        assertEquals(EvaluationResult.AwaitStableLayout, machine.evaluateSnapshot(window, 1150L, true, false, 8000L))
        val action = machine.evaluateSnapshot(window, 1240L, true, false, 8000L) as EvaluationResult.ExecuteClick
        assertEquals(3, action.command.decision.clickNodeIndex)
        assertEquals(ClickMethod.NODE_ACTION, action.command.preferredMethod)
        assertFalse(action.command.allowImmediateGestureFallback)
    }

    @Test
    fun `sdk interstitial close requires the registered card and its own ad badge`() {
        val card = RectBounds(0, 750, 1080, 1780)
        val closeBounds = RectBounds(965, 750, 1045, 830)
        val window = makeWindow(activity = "com.example.MainActivity", nodes = listOf(
            node(0, bounds = fullScreen, depth = 0, clickable = false),
            node(1, 0, viewId = "id/ksad_interstitial_native", bounds = card, clickable = false),
            node(2, 1, viewId = "id/ksad_interstitial_auto_close", bounds = closeBounds, clickable = false),
            node(3, 2, viewId = "id/ksad_auto_close_btn", bounds = closeBounds, className = "android.widget.ImageView"),
            node(4, 1, viewId = "id/ksad_logo_text", text = "广告", bounds = RectBounds(30, 1500, 75, 1530))
        ))
        assertEquals(3, SkipMatcher.findBestMatch(window, ruleIndex)?.clickNodeIndex)
        assertNull(SkipMatcher.findBestMatch(window.copy(nodes = window.nodes.map {
            if (it.index == 4) it.copy(parentIndex = 0) else it
        }), ruleIndex))
    }

    @Test
    fun `web close id requires feedback and an ad label in the same card`() {
        val card = RectBounds(110, 410, 970, 2080)
        val window = makeWindow(activity = "com.example.MainActivity", nodes = listOf(
            node(0, bounds = fullScreen, depth = 0, clickable = false),
            node(1, 0, bounds = fullScreen, className = "android.webkit.WebView", clickable = false),
            node(2, 1, bounds = card, viewId = "ad_area", clickable = false),
            node(3, 2, bounds = RectBounds(885, 430, 940, 485), viewId = "close_btn", text = "×"),
            node(4, 2, bounds = RectBounds(135, 430, 220, 485), text = "反馈"),
            node(5, 2, bounds = RectBounds(860, 1990, 925, 2035), text = "广告", clickable = false)
        ))
        assertEquals(3, SkipMatcher.findBestMatch(window, ruleIndex)?.clickNodeIndex)
        assertNull(SkipMatcher.findBestMatch(window.copy(nodes = window.nodes.map {
            if (it.index == 5) it.copy(parentIndex = 1) else it
        }), ruleIndex))
    }

    @Test
    fun `portrait sdk end card exposes a top corner close separate from the creative`() {
        val window = makeWindow(activity = "com.example.PortraitADActivity", nodes = listOf(
            node(0, bounds = fullScreen, depth = 0, clickable = false),
            node(1, 0, bounds = fullScreen, clickable = false),
            node(2, 1, bounds = fullScreen, className = "android.webkit.WebView", clickable = false),
            node(3, 1, className = "android.widget.ImageView"),
            node(4, 2, bounds = fullScreen, className = "android.webkit.WebView", clickable = false,
                text = "优量汇-插屏视频endcard")
        ))
        assertEquals(3, SkipMatcher.findBestMatch(window, ruleIndex)?.clickNodeIndex)
        assertNull(SkipMatcher.findBestMatch(window.copy(activityName = "com.example.MainActivity"), ruleIndex))
    }

    @Test
    fun `reward creative closes only through an unambiguous image opposite ad feedback`() {
        val close = node(2, 1, bounds = RectBounds(940, 650, 1010, 720), className = "android.widget.ImageView")
        val window = makeWindow(activity = "com.example.MobRewardVideoActivity", nodes = listOf(
            node(0, bounds = fullScreen, depth = 0, clickable = false),
            node(1, 0, bounds = fullScreen, clickable = false), close,
            node(3, 1, bounds = RectBounds(130, 755, 250, 810), text = "反馈")
        ))
        assertEquals(close.index, SkipMatcher.findBestMatch(window, ruleIndex)?.clickNodeIndex)
        assertNull(SkipMatcher.findBestMatch(window.copy(nodes = window.nodes.dropLast(1)), ruleIndex))
        assertNull(SkipMatcher.findBestMatch(window.copy(nodes = window.nodes +
            close.copy(index = 4, bounds = RectBounds(875, 650, 945, 720))), ruleIndex))
    }

    @Test
    fun `custom skip description requires the promotional image to share the creative without overlap`() {
        val image = node(2, 1, desc = "上滑或点击查看详情", bounds = RectBounds(180, 0, 835, 2400),
            className = "android.widget.ImageView")
        val window = makeWindow(activity = "com.example.MainActivity", nodes = listOf(
            node(0, bounds = fullScreen, depth = 0, clickable = false),
            node(1, 0, bounds = fullScreen, clickable = false), image,
            node(3, 1, desc = "跳过", className = "android.view.View")
        ))
        assertEquals(3, SkipMatcher.findBestMatch(window, ruleIndex)?.targetNodeIndex)
        assertNull(SkipMatcher.findBestMatch(window.copy(nodes = window.nodes.map {
            if (it == image) it.copy(bounds = fullScreen) else it
        }), ruleIndex))
    }

    @Test
    fun `split countdown below a banner needs a separate ad marker`() {
        val root = node(0, bounds = fullScreen, depth = 0, clickable = false)
        val marker = node(1, 0, text = "摇一摇查看详情", bounds = centerScreen)
        val pill = node(2, 0, bounds = RectBounds(720, 680, 865, 750))
        val timer = node(3, 2, text = "4", bounds = RectBounds(720, 680, 745, 750))
        val separator = node(4, 2, text = "|", bounds = RectBounds(745, 680, 770, 750))
        val skip = node(5, 2, text = "跳过", bounds = RectBounds(770, 680, 865, 750), clickable = false)
        val window = makeWindow(nodes = listOf(root, marker, pill, timer, separator, skip)).copy(
            windowId = 7, behindTopWindow = true, obstructions = listOf(RectBounds(0, 0, 1080, 420))
        )
        val match = SkipMatcher.findBestMatch(window, ruleIndex)
        assertEquals(skip.index, match?.targetNodeIndex)
        assertTrue(match!!.forceGestureOnly)
        assertNull(SkipMatcher.findBestMatch(window.copy(nodes = window.nodes.map {
            if (it == marker) marker.copy(text = "普通业务") else it
        }), ruleIndex))
        assertNull(SkipMatcher.findBestMatch(window.copy(nodes = window.nodes.map {
            if (it == separator) separator.copy(text = "授权") else it
        }), ruleIndex))
        assertNull(SkipMatcher.findBestMatch(window.copy(complete = false), ruleIndex))
    }

    @Test
    fun `banner coverage is checked on physical bounds after freeform scaling`() {
        val frame = RectBounds(200, 400, 740, 1600)
        val viewport = FreeformViewport(frame, RectBounds(200, 400, 1280, 2800), 4,
            RectBounds(200, 400, 740, 460), emptyList())
        val marker = node(1, 0, desc = "广告", bounds = centerScreen)
        val skip = node(2, 0, text = "4 | 跳过", bounds = RectBounds(745, 680, 885, 750))
        val window = makeWindow(nodes = listOf(node(0, bounds = fullScreen, depth = 0), marker, skip)).copy(
            freeform = viewport, windowId = 7, behindTopWindow = true,
            obstructions = listOf(RectBounds(210, 420, 730, 650))
        )
        assertEquals(skip.index, SkipMatcher.findBestMatch(window, ruleIndex)?.targetNodeIndex)
        assertNull(SkipMatcher.findBestMatch(window.copy(obstructions = listOf(RectBounds(570, 690, 580, 775))), ruleIndex))
        assertNull(SkipMatcher.findBestMatch(window.copy(nodes = listOf(window.nodes[0], marker,
            skip.copy(bounds = RectBounds(450, 680, 590, 750)))), ruleIndex))
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
    fun `generic SDK controls use a safe node click except for a split countdown`() {
        for (id in listOf("layout_skip", "splash_skip")) {
            val root = node(0, bounds = fullScreen, clickable = false, depth = 0)
            val control = node(1, parentIndex = 0, viewId = "com.example.app:id/$id")
            val plain = SkipMatcher.findBestMatch(makeWindow(nodes = listOf(root, control)), ruleIndex)!!
            assertFalse(plain.forceGestureOnly)
            assertEquals(control.index, plain.clickNodeIndex)

            val countdown = control.copy(text = "5 | 跳过")
            val split = SkipMatcher.findBestMatch(makeWindow(nodes = listOf(root, countdown)), ruleIndex)!!
            assertTrue(split.forceGestureOnly)
        }
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
    fun `rejects center text that looks edge aligned behind a clipped window transition`() {
        val win = makeWindow(
            isFullScreen = false,
            nodes = listOf(
                node(0, bounds = RectBounds(480, 0, 1080, 2400), clickable = false, depth = 0),
                node(1, parentIndex = 0, text = "跳过", bounds = RectBounds(933, 1630, 1073, 1740))
            )
        )
        assertNull(SkipMatcher.findBestMatch(win, ruleIndex))
    }

    @Test
    fun `rejects a screen center button even when a shortened root makes it look like a lower edge`() {
        val win = makeWindow(nodes = listOf(
            node(0, bounds = RectBounds(0, 0, 1080, 2110), clickable = false, depth = 0),
            node(1, parentIndex = 0, text = "跳过", bounds = RectBounds(410, 1590, 670, 1700))
        ))
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
