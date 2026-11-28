package com.flashskip

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.flashskip.data.SkipLog
import java.util.concurrent.atomic.AtomicInteger

/**
 * Multi-scenario splash test lab supporting both positive skip verification
 * and negative anti-misclick controls.
 */
class TestSplashActivity : AppCompatActivity() {

    data class RunTrace(val requestId: String, val instanceId: Int) {
        val fields: String get() = "request=$requestId instance=$instanceId"
    }

    companion object {
        const val EXTRA_REQUEST_ID = "launch_request"
        private val requestIdPattern = Regex("[A-Za-z0-9_-]{1,32}")
        private val nextInstance = AtomicInteger()

        @Volatile
        var resumedTrace: RunTrace? = null
            private set

        fun requestIdFrom(intent: Intent): String? =
            intent.getStringExtra(EXTRA_REQUEST_ID)?.takeIf(requestIdPattern::matches)

        const val EXTRA_SCENARIO = "scenario"
        const val SCENARIO_STANDARD = "standard"
        const val SCENARIO_NON_CLICKABLE = "non_clickable"
        const val SCENARIO_FULLSCREEN_PARENT = "fullscreen_parent"
        const val SCENARIO_FAKE_SUCCESS = "fake_success"
        const val SCENARIO_DESC_ONLY = "desc_only"
        const val SCENARIO_NEG_GUIDE = "neg_guide"
        const val SCENARIO_NEG_REDPACKET = "neg_redpacket"
        const val SCENARIO_NEG_CENTER = "neg_center"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var splashRoot: FrameLayout
    private lateinit var tvAdTitle: TextView
    private lateinit var btnSkip: Button
    private lateinit var tvFakeSkip: TextView
    private lateinit var btnCenterSkip: Button
    private lateinit var btnBackManual: Button

    private var countdown = 3
    private var shownAt = 0L
    private var scenario = SCENARIO_STANDARD
    private var labWebView: WebView? = null
    private var extraTarget: View? = null
    private var closed = false
    private var trace: RunTrace? = null
    private var warmResumeCount = 0
    private var hotDialog: Dialog? = null
    private var topBanner: PopupWindow? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_splash)

        splashRoot = findViewById(R.id.splashRoot)
        tvAdTitle = findViewById(R.id.tvAdTitle)
        btnSkip = findViewById(R.id.btnSkip)
        tvFakeSkip = findViewById(R.id.tvFakeSkip)
        btnCenterSkip = findViewById(R.id.btnCenterSkip)
        btnBackManual = findViewById(R.id.btnBackManual)

        scenario = intent.getStringExtra(EXTRA_SCENARIO) ?: SCENARIO_STANDARD
        trace = requestIdFrom(intent)?.let { RunTrace(it, nextInstance.incrementAndGet()) }
        logLifecycle("created")
        btnBackManual.setOnClickListener { finish() }
        tvAdTitle.contentDescription = "广告"

        when (scenario) {
            SCENARIO_STANDARD -> setupStandard()
            SCENARIO_NON_CLICKABLE -> setupNonClickable()
            SCENARIO_FULLSCREEN_PARENT -> setupFullscreenParentTrap()
            SCENARIO_FAKE_SUCCESS -> setupFakeSuccessButton()
            SCENARIO_DESC_ONLY -> setupDescOnly()
            SCENARIO_NEG_GUIDE -> setupNegativeButton("对照测试：新手引导页\n（“跳过引导”绝不能被点击）", "跳过引导")
            SCENARIO_NEG_REDPACKET -> setupNegativeButton("对照测试：诱导红包\n（“点击跳过领红包”绝不能被点击）", "点击跳过领红包")
            SCENARIO_NEG_CENTER -> setupNegativeCenter()
            "id_only" -> setupIdOnly()
            "webview" -> setupWebView()
            "custom_view" -> setupCustomView()
            "structured_interactive" -> setupStructuredClose(interactive = true)
            "structured_interstitial", "structured_web_close", "structured_ks_interstitial", "neg_ad_card_decoy" ->
                setupStructuredClose(interactive = false)
            "neg_plain_step" -> {
                tvAdTitle.contentDescription = null
                setupNegativeButton("选择感兴趣的内容", "跳过")
            }
            "countdown" -> setupAdButton("倒计时广告", "3 | 跳过")
            "fullscreen_overlay" -> setupFullScreenOverlay()
            "shake" -> setupAdButton("摇一摇查看详情", "跳过")
            "stacked_banner", "stacked_countdown", "stacked_covered", "stacked_business" -> setupStackedCountdown()
            "recorded_splash_relayout" -> setupRecordedSplashRelayout()
            "recorded_skip_area_relayout" -> setupRecordedSplashRelayout(skipArea = true)
            "recorded_promo_countdown" -> setupRecordedSplashRelayout(promotionalCountdown = true)
            "warm_resume" -> {
                tvAdTitle.text = "回到前台后显示模拟开屏广告"
                tvAdTitle.contentDescription = null
                btnSkip.text = "跳过"
                btnSkip.setOnClickListener { closeSuccess(scenario) }
            }
            "hot_window_close", "hot_window_redirect", "hot_window_unknown" -> {
                tvAdTitle.text = "独立窗口回前台靶场"
                tvAdTitle.contentDescription = null
                btnBackManual.visibility = View.VISIBLE
            }
            "redirect" -> setupAdButton("导流对照", "跳过") {
                logTest("TEST_NAV[$scenario] destination=MainActivity")
                finish()
            }
            "hide_target" -> setupAdButton("暂时隐藏按钮，广告仍显示", "跳过") {
                btnSkip.visibility = View.GONE
                logTest("TEST_REMAINS[$scenario] ad marker still visible")
            }
            "marker_replaced" -> setupAdButton("关闭入口撤下后更换广告标记", "跳过") {
                btnSkip.visibility = View.GONE
                tvAdTitle.contentDescription = "推广"
                logTest("TEST_REMAINS[$scenario] ad marker replaced")
            }
            "marker_late" -> {
                tvAdTitle.contentDescription = null
                setupAdButton("关闭入口撤下后才显示标记", "跳过") {
                    btnSkip.visibility = View.GONE
                    handler.postDelayed({
                        tvAdTitle.contentDescription = "推广"
                        logTest("TEST_REMAINS[$scenario] new ad marker visible")
                    }, 90)
                }
            }
            "retry_replaced" -> setupReplacementTarget()
            "shake_first" -> {
                tvAdTitle.text = "摇一摇先触发，跳过入口尚未显示"
                handler.postDelayed({
                    logTest("TEST_NAV[$scenario] no accessible skip before navigation")
                    finish()
                }, 250)
            }
            "render_only" -> {
                tvAdTitle.text = "纯渲染广告：没有可访问的关闭控件"
                btnBackManual.visibility = View.VISIBLE
                val paintedClose = createPaintedClose(renderOnly = true)
                paintedClose.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                paintedClose.setOnTouchListener { view, event ->
                    val density = resources.displayMetrics.density
                    val inPaintedArea = event.x > view.width - 72 * density && event.y < 72 * density
                    if (inPaintedArea && event.action == MotionEvent.ACTION_UP) {
                        logTest("TEST_FAIL[$scenario] blind tap on painted control")
                    }
                    inPaintedArea
                }
                paintedClose.visibility = View.GONE
                splashRoot.addView(paintedClose, 0, FrameLayout.LayoutParams(-1, -1))
                showTarget(paintedClose)
            }
            "neg_feed" -> {
                tvAdTitle.text = "信息流：广告标记可见，关闭控件位于页面中央"
                setupNegativeCenter()
            }
            "neg_payment" -> setupNegativeButton("支付页面对照", "跳过付款")
            else -> setupStandard()
        }
    }

    private fun setupStandard() {
        tvAdTitle.text = "模拟开屏广告（标准可点）"
        btnSkip.setOnClickListener { closeSuccess("standard") }
        handler.postDelayed({
            btnSkip.visibility = View.VISIBLE
            shownAt = SystemClock.uptimeMillis()
            logTest("TEST_LAB[$scenario] skip button shown")
            tick(btnSkip)
        }, 800)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupNonClickable() {
        tvAdTitle.text = "模拟开屏广告（不可点击自绘控件，需手势点击）"
        tvFakeSkip.isClickable = false
        tvFakeSkip.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> true
                MotionEvent.ACTION_UP -> {
                    closeSuccess("non_clickable_gesture")
                    true
                }
                else -> true
            }
        }
        handler.postDelayed({
            tvFakeSkip.visibility = View.VISIBLE
            shownAt = SystemClock.uptimeMillis()
            logTest("TEST_LAB[$scenario] non-clickable skip shown")
            tick(tvFakeSkip)
        }, 500)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFullscreenParentTrap() {
        tvAdTitle.text = "模拟开屏陷阱（全屏容器可点击，跳过不可点击）\n若点到全屏容器则测试失败"
        splashRoot.isClickable = true
        splashRoot.setOnClickListener {
            logTest("TEST_FAIL[$scenario] MISCLICKED FULLSCREEN AD LANDING CONTAINER!")
            Toast.makeText(this, "失败：误点了全屏广告容器！", Toast.LENGTH_LONG).show()
            finish()
        }
        tvFakeSkip.isClickable = false
        tvFakeSkip.text = "跳过"
        tvFakeSkip.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                closeSuccess("fullscreen_parent_safe_gesture")
                true
            } else {
                true
            }
        }
        handler.postDelayed({
            tvFakeSkip.visibility = View.VISIBLE
            shownAt = SystemClock.uptimeMillis()
            logTest("TEST_LAB[$scenario] fullscreen trap + small skip shown")
        }, 500)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFakeSuccessButton() {
        tvAdTitle.text = "模拟开屏（ACTION_CLICK 假成功，需自动升级手势）"
        btnSkip.text = "跳过"
        // Ignore Accessibility ACTION_CLICK while returning true!
        btnSkip.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) {
                    logTest("TEST_LAB[$scenario] fake-success ACTION_CLICK ignored!")
                    return true
                }
                return super.performAccessibilityAction(host, action, args)
            }
        }
        btnSkip.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                closeSuccess("fake_success_escalated_gesture")
                true
            } else {
                false
            }
        }
        handler.postDelayed({
            btnSkip.visibility = View.VISIBLE
            shownAt = SystemClock.uptimeMillis()
            logTest("TEST_LAB[$scenario] fake-success skip shown")
        }, 500)
    }

    private fun setupDescOnly() {
        tvAdTitle.text = "模拟开屏（空 text + contentDescription='跳过广告'）"
        btnSkip.text = ""
        btnSkip.contentDescription = "跳过广告"
        btnSkip.setOnClickListener { closeSuccess("desc_only") }
        handler.postDelayed({
            btnSkip.visibility = View.VISIBLE
            shownAt = SystemClock.uptimeMillis()
            logTest("TEST_LAB[$scenario] desc-only skip shown")
        }, 500)
    }

    private fun setupNegativeButton(title: String, buttonText: String) {
        tvAdTitle.text = title
        btnSkip.text = buttonText
        btnBackManual.visibility = View.VISIBLE
        btnSkip.setOnClickListener {
            logTest("TEST_FAIL[$scenario] MISCLICKED '$buttonText'!")
            Toast.makeText(this, "失败：发生误触 '$buttonText'！", Toast.LENGTH_LONG).show()
            finish()
        }
        handler.postDelayed({
            btnSkip.visibility = View.VISIBLE
            logTest("TEST_LAB[$scenario] negative button '$buttonText' shown (expect 0 click)")
        }, 300)
    }

    private fun setupNegativeCenter() {
        if (scenario == SCENARIO_NEG_CENTER) {
            tvAdTitle.text = "对照测试：屏幕正中普通按钮\n（屏幕中心“跳过”绝不能被误触）"
        }
        btnBackManual.visibility = View.VISIBLE
        btnCenterSkip.setOnClickListener {
            logTest("TEST_FAIL[$scenario] MISCLICKED center button!")
            Toast.makeText(this, "失败：误触屏幕正中按钮！", Toast.LENGTH_LONG).show()
            finish()
        }
        handler.postDelayed({
            btnCenterSkip.visibility = View.VISIBLE
            logTest("TEST_LAB[$scenario] center button shown (expect 0 click)")
        }, 300)
    }

    private fun closeSuccess(tag: String) {
        if (closed) return
        closed = true
        topBanner?.dismiss()
        handler.removeCallbacksAndMessages(null)
        btnSkip.visibility = View.GONE
        tvFakeSkip.visibility = View.GONE
        tvAdTitle.visibility = View.GONE
        extraTarget?.visibility = View.GONE
        splashRoot.isClickable = false
        splashRoot.setBackgroundColor(Color.DKGRAY)
        val closedAt = SystemClock.uptimeMillis()
        val visibleFor = if (shownAt > 0) closedAt - shownAt else -1
        logTest("TEST_PASS[$tag] closed shownAtMs=$shownAt closedAtMs=$closedAt buttonVisibleFor=${visibleFor}ms")
        handler.postDelayed({ finish() }, 480)
    }

    private fun setupAdButton(title: String, label: String, delayMs: Long = 500L, action: () -> Unit = { closeSuccess(scenario) }) {
        tvAdTitle.text = title
        btnSkip.text = label
        btnSkip.setOnClickListener { action() }
        showTarget(btnSkip, delayMs)
    }

    private fun setupStackedCountdown() {
        val business = scenario == "stacked_business"
        val covered = scenario == "stacked_covered"
        val compactBanner = scenario == "stacked_banner"
        tvAdTitle.text = if (business) "普通业务页面" else "摇一摇查看详情"
        if (business) tvAdTitle.contentDescription = null
        btnBackManual.visibility = View.VISIBLE
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (density * value).toInt()
        val pill = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = ColorDrawable(Color.DKGRAY)
            for (part in listOf("4", " | ", "跳过")) {
                addView(TextView(context).apply {
                    text = part
                    textSize = 16f
                    setTextColor(Color.WHITE)
                })
            }
            setOnClickListener {
                if (business || covered) logTest("TEST_FAIL[$scenario] touched guarded control")
                else closeSuccess(scenario)
            }
            visibility = View.GONE
        }
        splashRoot.post {
            val bannerHeight = (splashRoot.height * if (compactBanner) .08f else .18f).toInt()
            if (isFinishing || bannerHeight <= 0) return@post
            val topFraction = when {
                covered -> .08f
                compactBanner -> .14f
                else -> .28f
            }
            val top = (splashRoot.height * topFraction).toInt()
            val right = if (compactBanner) dp(12) else (splashRoot.width * .26f).toInt() - dp(42)
            splashRoot.addView(pill, FrameLayout.LayoutParams(dp(84), dp(34), Gravity.TOP or Gravity.RIGHT).apply {
                topMargin = top
                rightMargin = right.coerceAtLeast(0)
            })
            val banner = TextView(this).apply {
                text = "通知预览　优惠速抢　刚刚"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(55, 58, 66))
                setOnClickListener { logTest("TEST_FAIL[$scenario] touched banner") }
            }
            val inset = if (compactBanner) (splashRoot.width * .03f).toInt() else 0
            val yOffset = if (compactBanner) (splashRoot.height * .02f).toInt() else 0
            topBanner = PopupWindow(banner, splashRoot.width - inset * 2, bannerHeight, true).apply {
                isOutsideTouchable = true
                if (Build.VERSION.SDK_INT >= 29) setTouchModal(false)
                showAtLocation(splashRoot, Gravity.TOP or Gravity.LEFT, inset, yOffset)
            }
            extraTarget = pill
            showTarget(pill, delayMs = 1_250L)
        }
    }

    private fun setupRecordedSplashRelayout(skipArea: Boolean = false, promotionalCountdown: Boolean = false) {
        tvAdTitle.text = "同窗横幅与倒计时回放"
        splashRoot.post {
            if (isFinishing || splashRoot.width == 0 || splashRoot.height == 0) return@post
            // Use the ratios of the traced 1200 × 2664 window so the lab can run in a floating task.
            fun region(left: Int, top: Int, right: Int, bottom: Int): FrameLayout.LayoutParams {
                val w = splashRoot.width
                val h = splashRoot.height
                return FrameLayout.LayoutParams((right - left) * w / 1200, (bottom - top) * h / 2664).apply {
                    leftMargin = left * w / 1200
                    topMargin = top * h / 2664
                }
            }
            val creative = FrameLayout(this)
            splashRoot.addView(creative, FrameLayout.LayoutParams(-1, -1))
            val banner = LinearLayout(this).apply {
                setBackgroundColor(Color.LTGRAY)
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                addView(TextView(context).apply { text = "刚刚" })
                setOnClickListener { logTest("TEST_FAIL[$scenario] touched banner") }
                visibility = View.INVISIBLE
            }
            creative.addView(banner, region(33, 153, 1167, 366))
            val callToAction = TextView(this).apply {
                text = "点击按钮了解更多"
                gravity = Gravity.CENTER
                setOnClickListener { logTest("TEST_FAIL[$scenario] touched ad call to action") }
            }
            if (promotionalCountdown) {
                val interaction = FrameLayout(this).apply { id = R.id.lab_splash_interaction_view }
                creative.addView(interaction, region(0, 1607, 1200, 2133))
                callToAction.id = R.id.lab_shake_click_region
                interaction.addView(callToAction, region(141, 348, 1058, 526))
                btnBackManual.visibility = View.VISIBLE
            } else {
                creative.addView(callToAction, region(141, 1955, 1058, 2133))
            }
            val badge = if (skipArea) region(1040, 2313, 1161, 2352) else region(1075, 2283, 1153, 2336)
            creative.addView(TextView(this).apply { text = "广告" }, badge)
            val skip = TextView(this).apply {
                id = if (skipArea) R.id.lab_splash_skip else R.id.lab_splash_jump
                text = if (skipArea) "5s | 跳过" else "5 | 跳过"
                gravity = Gravity.CENTER
                setSingleLine()
                setBackgroundColor(Color.DKGRAY)
                setTextColor(Color.WHITE)
                visibility = View.INVISIBLE
                if (!skipArea) {
                    setOnClickListener {
                        if (promotionalCountdown) {
                            logTest("TEST_FAIL[$scenario] clicked the promotional countdown")
                        } else {
                            creative.visibility = View.GONE
                            closeSuccess(scenario)
                        }
                    }
                    setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            logTest("TEST_FAIL[$scenario] used a coordinate tap for a same-window banner")
                        }
                        true
                    }
                }
            }
            val area = if (skipArea) FrameLayout(this).apply {
                id = R.id.lab_splash_skip_area
                creative.addView(this, region(890, 0, 1200, 183))
                addView(skip, region(39, 52, 271, 131))
                isClickable = true
                setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        if (skip.visibility == View.VISIBLE && event.x >= skip.left && event.x < skip.right &&
                            event.y >= skip.top && event.y < skip.bottom
                        ) {
                            creative.visibility = View.GONE
                            closeSuccess(scenario)
                        } else {
                            logTest("TEST_FAIL[$scenario] touched empty skip area")
                        }
                    }
                    true
                }
            } else null
            if (area == null) {
                val placement = if (promotionalCountdown) region(949, 399, 1168, 481) else region(1101, 157, 1167, 239)
                creative.addView(skip, placement)
            }
            extraTarget = skip
            showTarget(skip, 750)
            handler.postDelayed({
                if (area != null) {
                    area.layoutParams = region(890, 121, 1200, 304)
                    skip.text = "4s | 跳过"
                } else {
                    skip.layoutParams = region(949, 399, 1168, 481)
                    skip.text = "4 | 跳过"
                    banner.visibility = View.VISIBLE
                }
            }, 790)
            if (promotionalCountdown) {
                handler.postDelayed({
                    banner.visibility = View.GONE
                    skip.text = "2 | 跳过"
                }, 1800)
            }
        }
    }

    private fun setupReplacementTarget() {
        tvAdTitle.text = "原按钮换成同位置、同文案的另一个控件"
        btnSkip.text = "跳过"
        btnSkip.setOnClickListener { logTest("TEST_FAIL[$scenario] touched replacement") }
        btnSkip.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK &&
                    host.id == R.id.btnSkip
                ) {
                    host.id = R.id.replacement_skip
                    host.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                    logTest("TEST_REMAINS[$scenario] original resource ID replaced")
                    return true
                }
                return super.performAccessibilityAction(host, action, args)
            }
        }
        showTarget(btnSkip)
    }

    private fun setupIdOnly() {
        tvAdTitle.text = "通过原生资源 ID 识别"
        btnSkip.id = R.id.tt_splash_skip
        setupAdButton(tvAdTitle.text.toString(), "")
    }

    private fun setupWebView() {
        tvAdTitle.text = "WebView 虚拟关闭控件"
        val web = WebView(this)
        labWebView = web
        addExtraTarget(web, 120, 64)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.toString() == "https://flashskip.invalid/close") closeSuccess(scenario)
                return true
            }
        }
        web.loadDataWithBaseURL(null, """
            <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
            <body style="margin:0"><a href="https://flashskip.invalid/close"
              style="display:block;padding:12px;background:#242e4b;color:white;font-size:16px">跳过广告</a></body>
        """.trimIndent(), "text/html", "UTF-8", null)
        showTarget(web)
    }

    private fun setupFullScreenOverlay() {
        val home = TextView(this).apply {
            text = "模拟首页"
            gravity = Gravity.CENTER
            textSize = 22f
        }
        splashRoot.addView(home, 0, FrameLayout.LayoutParams(-1, -1))
        tvAdTitle.visibility = View.GONE
        splashRoot.setBackgroundColor(Color.DKGRAY)
        btnSkip.text = "关闭广告"
        btnSkip.setOnClickListener {
            home.visibility = View.VISIBLE
            closeSuccess(scenario)
        }
        handler.postDelayed({
            home.visibility = View.GONE
            splashRoot.setBackgroundColor(Color.rgb(35, 49, 79))
            tvAdTitle.text = "首页中的全屏广告"
            tvAdTitle.visibility = View.VISIBLE
            shownAt = SystemClock.uptimeMillis()
            btnSkip.visibility = View.VISIBLE
            logTest("TEST_LAB[$scenario] target shown shownAtMs=$shownAt")
        }, 1200)
    }

    private fun setupCustomView() {
        tvAdTitle.text = "自绘可访问关闭控件"
        val view = createPaintedClose()
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        view.setOnClickListener { closeSuccess(scenario) }
        addExtraTarget(view, 54, 54)
        showTarget(view)
    }

    private fun setupStructuredClose(interactive: Boolean) {
        val genuine = scenario != "neg_ad_card_decoy"
        val web = scenario == "structured_web_close"
        val sdkCard = scenario == "structured_ks_interstitial"
        tvAdTitle.text = if (interactive) "互动创意靶场" else "弹窗关闭靶场"
        tvAdTitle.contentDescription = null
        btnBackManual.visibility = if (genuine) View.GONE else View.VISIBLE

        splashRoot.post {
            val width = splashRoot.width
            val height = splashRoot.height
            val cardWidth = if (interactive) width else (width * .84f).toInt()
            val cardHeight = if (interactive) height else (height * .58f).toInt()
            val closeSize = minOf((cardWidth * .10f).toInt(), (cardHeight * .09f).toInt())
            val card = FrameLayout(this).apply {
                if (sdkCard) id = R.id.ksad_interstitial_native
                else if (!interactive && !web) id = R.id.ad_container
                setBackgroundColor(Color.rgb(46, 65, 101))
                setOnClickListener { logTest("TEST_FAIL[$scenario] creative body clicked") }
            }
            fun addControl(control: View, w: Int, h: Int, gravity: Int, margin: Int) {
                card.addView(control, FrameLayout.LayoutParams(w, h, gravity).apply {
                    topMargin = closeSize / 2
                    marginEnd = margin
                })
            }
            val corner = Gravity.TOP or Gravity.END
            val close = when {
                interactive || web -> createPaintedClose()
                sdkCard -> ImageView(this).apply {
                    id = R.id.ksad_auto_close_btn
                    setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                }
                else -> ImageButton(this).apply {
                    id = R.id.ad_close
                    contentDescription = "关闭"
                    setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                }
            }
            close.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            close.setOnClickListener {
                if (genuine) closeSuccess(scenario)
                else logTest("TEST_FAIL[$scenario] ordinary dialog close clicked")
            }
            if (interactive) {
                val badge = TextView(this).apply {
                    text = "互动广告"
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                }
                addControl(badge, 2 * closeSize, closeSize, corner, closeSize * 7 / 4)
                addControl(close, closeSize, closeSize, corner, closeSize / 2)
                card.addView(TextView(this).apply {
                    text = "广告"
                    setTextColor(Color.WHITE)
                },
                    FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
            } else {
                val area = FrameLayout(this).apply {
                    when {
                        web -> contentDescription = "webview-close:abcdef:0:1"
                        sdkCard -> id = R.id.ksad_interstitial_auto_close
                        else -> id = R.id.ad_close_area
                    }
                }
                area.addView(close, FrameLayout.LayoutParams(-1, -1))
                addControl(area, closeSize, closeSize, corner, closeSize / 2)
                addControl(TextView(this).apply {
                    if (sdkCard) id = R.id.ksad_logo_text
                    else if (genuine && !web) id = R.id.ad_log
                    text = when {
                        web || sdkCard -> "广告"
                        genuine -> "广告标识"
                        else -> "业务弹窗"
                    }
                    setTextColor(Color.WHITE)
                }, 3 * closeSize, closeSize, Gravity.TOP or Gravity.START, 0)
                card.addView(View(this).apply {
                    id = R.id.ad_icon
                    contentDescription = "图标"
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                }, FrameLayout.LayoutParams(closeSize, closeSize, Gravity.CENTER))
            }
            card.visibility = View.GONE
            extraTarget = card
            splashRoot.addView(card, 0, FrameLayout.LayoutParams(cardWidth, cardHeight, Gravity.CENTER))
            showTarget(card)
        }
    }

    private fun createPaintedClose(renderOnly: Boolean = false): View {
        return object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                strokeWidth = 5f
            }

            override fun onDraw(canvas: Canvas) {
                val size = if (renderOnly) 54f * resources.displayMetrics.density else width.toFloat()
                val left = if (renderOnly) width - size - 16f * resources.displayMetrics.density else 0f
                val offset = if (renderOnly) 16f * resources.displayMetrics.density else 0f
                val start = left + size * 0.34f
                val end = left + size * 0.66f
                val top = offset + size * 0.34f
                val bottom = offset + size * 0.66f
                canvas.drawLine(start, top, end, bottom, paint)
                canvas.drawLine(start, bottom, end, top, paint)
            }
        }.apply { if (!renderOnly) setBackgroundResource(R.drawable.btn_skip_bg) }
    }

    private fun addExtraTarget(target: View, widthDp: Int, heightDp: Int) {
        val density = resources.displayMetrics.density
        val params = FrameLayout.LayoutParams(
            (widthDp * density).toInt(), (heightDp * density).toInt(), Gravity.TOP or Gravity.END
        ).apply {
            topMargin = (16 * density).toInt()
            marginEnd = topMargin
        }
        target.visibility = View.GONE
        extraTarget = target
        splashRoot.addView(target, params)
    }

    private fun showTarget(view: View, delayMs: Long = 500L) {
        handler.postDelayed({
            shownAt = SystemClock.uptimeMillis()
            view.visibility = View.VISIBLE
            logTest("TEST_LAB[$scenario] target shown shownAtMs=$shownAt")
        }, delayMs)
    }

    private fun tick(view: TextView) {
        if (countdown > 0) {
            view.text = "跳过 ${countdown}s"
            countdown--
            handler.postDelayed({ tick(view) }, 1000)
        } else {
            view.text = "跳过"
        }
    }

    override fun onResume() {
        super.onResume()
        resumedTrace = trace
        logLifecycle("resumed")
        if (scenario == "warm_resume" && ++warmResumeCount == 2) {
            tvAdTitle.contentDescription = "广告"
            showTarget(btnSkip, delayMs = 900)
        }
        if (scenario.startsWith("hot_window_") && ++warmResumeCount == 2) {
            handler.postDelayed({ showHotDialog() }, 220)
        }
    }

    private fun showHotDialog(destination: Boolean = false) {
        if (isFinishing || isDestroyed) return
        val dialog = Dialog(this, android.R.style.Theme_Material_NoActionBar)
        dialog.setContentView(R.layout.activity_test_splash)
        val title = dialog.findViewById<TextView>(R.id.tvAdTitle)
        title.text = if (destination) "应用内落地页" else "模拟全屏广告"
        title.contentDescription = if (destination) null else "广告"
        val skip = dialog.findViewById<Button>(R.id.btnSkip)
        skip.text = "跳过"
        skip.setOnClickListener {
            logTest("TEST_TAP[$scenario] close control received")
            dialog.dismiss()
            if (scenario == "hot_window_redirect") showHotDialog(destination = true)
        }
        if (!destination && scenario == "hot_window_unknown") {
            // Exercise an unmapped type using the test Activity's own window token.
            dialog.window?.setType(WindowManager.LayoutParams.LAST_APPLICATION_WINDOW)
        }
        hotDialog = dialog
        dialog.show()
        dialog.window?.setLayout(-1, -1)
        if (destination) {
            logTest("TEST_DEST[$scenario] another same-app window")
            return
        }
        logTest("TEST_WINDOW[$scenario] top window attached")
        handler.postDelayed({
            if (!dialog.isShowing) return@postDelayed
            skip.visibility = View.VISIBLE
            skip.post {
                val origin = IntArray(2)
                skip.getLocationOnScreen(origin)
                logTest("TEST_LAB[$scenario] shown tapX=${origin[0] + skip.width / 2} tapY=${origin[1] + skip.height / 2}")
            }
        }, 1800)
    }

    override fun onPause() {
        if (resumedTrace == trace) resumedTrace = null
        logLifecycle("paused")
        super.onPause()
    }

    private fun logTest(message: String) {
        val run = trace
        SkipLog.log(if (run == null) message else "$message ${run.fields} atMs=${SystemClock.uptimeMillis()}")
    }

    private fun logLifecycle(stage: String) {
        if (trace != null) logTest("LAB_INSTANCE stage=$stage")
    }

    override fun onDestroy() {
        topBanner?.dismiss()
        logLifecycle("destroyed")
        hotDialog?.dismiss()
        handler.removeCallbacksAndMessages(null)
        labWebView?.let {
            splashRoot.removeView(it)
            it.destroy()
        }
        super.onDestroy()
    }
}
