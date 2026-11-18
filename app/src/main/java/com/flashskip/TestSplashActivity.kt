package com.flashskip

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.flashskip.data.SkipLog

/**
 * Multi-scenario splash test lab supporting both positive skip verification
 * and negative anti-misclick controls.
 */
class TestSplashActivity : AppCompatActivity() {

    companion object {
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
        btnBackManual.setOnClickListener { finish() }

        when (scenario) {
            SCENARIO_STANDARD -> setupStandard()
            SCENARIO_NON_CLICKABLE -> setupNonClickable()
            SCENARIO_FULLSCREEN_PARENT -> setupFullscreenParentTrap()
            SCENARIO_FAKE_SUCCESS -> setupFakeSuccessButton()
            SCENARIO_DESC_ONLY -> setupDescOnly()
            SCENARIO_NEG_GUIDE -> setupNegativeButton("对照测试：新手引导页\n（“跳过引导”绝不能被点击）", "跳过引导")
            SCENARIO_NEG_REDPACKET -> setupNegativeButton("对照测试：诱导红包\n（“点击跳过领红包”绝不能被点击）", "点击跳过领红包")
            SCENARIO_NEG_CENTER -> setupNegativeCenter()
            else -> setupStandard()
        }
    }

    private fun setupStandard() {
        tvAdTitle.text = "模拟开屏广告（标准可点）"
        btnSkip.setOnClickListener { closeSuccess("standard") }
        handler.postDelayed({
            btnSkip.visibility = View.VISIBLE
            shownAt = SystemClock.elapsedRealtime()
            SkipLog.log("TEST_LAB[$scenario] skip button shown")
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
            shownAt = SystemClock.elapsedRealtime()
            SkipLog.log("TEST_LAB[$scenario] non-clickable skip shown")
            tick(tvFakeSkip)
        }, 500)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFullscreenParentTrap() {
        tvAdTitle.text = "模拟开屏陷阱（全屏容器可点击，跳过不可点击）\n若点到全屏容器则测试失败"
        splashRoot.isClickable = true
        splashRoot.setOnClickListener {
            SkipLog.log("TEST_FAIL[$scenario] MISCLICKED FULLSCREEN AD LANDING CONTAINER!")
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
            shownAt = SystemClock.elapsedRealtime()
            SkipLog.log("TEST_LAB[$scenario] fullscreen trap + small skip shown")
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
                    SkipLog.log("TEST_LAB[$scenario] fake-success ACTION_CLICK ignored!")
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
            shownAt = SystemClock.elapsedRealtime()
            SkipLog.log("TEST_LAB[$scenario] fake-success skip shown")
        }, 500)
    }

    private fun setupDescOnly() {
        tvAdTitle.text = "模拟开屏（空 text + contentDescription='跳过广告'）"
        btnSkip.text = ""
        btnSkip.contentDescription = "跳过广告"
        btnSkip.setOnClickListener { closeSuccess("desc_only") }
        handler.postDelayed({
            btnSkip.visibility = View.VISIBLE
            shownAt = SystemClock.elapsedRealtime()
            SkipLog.log("TEST_LAB[$scenario] desc-only skip shown")
        }, 500)
    }

    private fun setupNegativeButton(title: String, buttonText: String) {
        tvAdTitle.text = title
        btnSkip.text = buttonText
        btnBackManual.visibility = View.VISIBLE
        btnSkip.setOnClickListener {
            SkipLog.log("TEST_FAIL[$scenario] MISCLICKED '$buttonText'!")
            Toast.makeText(this, "失败：发生误触 '$buttonText'！", Toast.LENGTH_LONG).show()
            finish()
        }
        handler.postDelayed({
            btnSkip.visibility = View.VISIBLE
            SkipLog.log("TEST_LAB[$scenario] negative button '$buttonText' shown (expect 0 click)")
        }, 300)
    }

    private fun setupNegativeCenter() {
        tvAdTitle.text = "对照测试：屏幕正中普通按钮\n（屏幕中心“跳过”绝不能被误触）"
        btnBackManual.visibility = View.VISIBLE
        btnCenterSkip.setOnClickListener {
            SkipLog.log("TEST_FAIL[$scenario] MISCLICKED center button!")
            Toast.makeText(this, "失败：误触屏幕正中按钮！", Toast.LENGTH_LONG).show()
            finish()
        }
        handler.postDelayed({
            btnCenterSkip.visibility = View.VISIBLE
            SkipLog.log("TEST_LAB[$scenario] center button shown (expect 0 click)")
        }, 300)
    }

    private fun closeSuccess(tag: String) {
        val visibleFor = if (shownAt > 0) SystemClock.elapsedRealtime() - shownAt else -1
        SkipLog.log("TEST_PASS[$tag] closed, buttonVisibleFor=${visibleFor}ms")
        Toast.makeText(this, "测试通过：已关闭 ($tag)", Toast.LENGTH_SHORT).show()
        finish()
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

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
