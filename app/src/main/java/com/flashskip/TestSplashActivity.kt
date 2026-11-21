package com.flashskip

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
            "countdown" -> setupAdButton("倒计时广告", "3 | 跳过")
            "fullscreen_overlay" -> setupFullScreenOverlay()
            "shake" -> setupAdButton("摇一摇查看详情", "跳过")
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
