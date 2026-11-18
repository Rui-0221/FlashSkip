package com.flashskip

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.flashskip.core.PackagePolicy
import com.flashskip.core.SkipStateMachine
import com.flashskip.data.SkipLog
import com.flashskip.data.StatsStore
import com.flashskip.keepalive.PermissionHelper
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statsStore: StatsStore

    private lateinit var tvStatus: TextView
    private lateinit var tvKeepAliveInfo: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvLog: TextView
    private lateinit var swMaster: MaterialSwitch
    private lateinit var swObserve: MaterialSwitch
    private lateinit var tvWindow: TextView
    private lateinit var sbWindow: SeekBar
    private lateinit var swDiag: MaterialSwitch

    private var lastRenderedLogVersion = -1

    private val refresh = object : Runnable {
        override fun run() {
            refreshUi(forceLog = false)
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statsStore = StatsStore(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvKeepAliveInfo = findViewById(R.id.tvKeepAliveInfo)
        tvStats = findViewById(R.id.tvStats)
        tvLog = findViewById(R.id.tvLog)
        swMaster = findViewById(R.id.swMaster)
        swObserve = findViewById(R.id.swObserve)
        tvWindow = findViewById(R.id.tvWindow)
        sbWindow = findViewById(R.id.sbWindow)
        swDiag = findViewById(R.id.swDiag)

        swMaster.isChecked = statsStore.masterEnabled
        swObserve.isChecked = statsStore.observeOnly
        swDiag.isChecked = statsStore.diagNodes

        swMaster.setOnCheckedChangeListener { _, checked ->
            statsStore.masterEnabled = checked
            SkipLog.log("SETTING master_enabled=$checked")
        }
        swObserve.setOnCheckedChangeListener { _, checked ->
            statsStore.observeOnly = checked
            SkipLog.log("SETTING observe_only=$checked")
        }
        swDiag.setOnCheckedChangeListener { _, checked ->
            statsStore.diagNodes = checked
            SkipLog.log("SETTING diag_nodes=$checked")
        }

        // Slider 0..12 maps to 3..15 seconds; default 6s
        val minSec = (SkipStateMachine.MIN_WINDOW_MS / 1000L).toInt()
        val maxSec = (SkipStateMachine.MAX_WINDOW_MS / 1000L).toInt()
        val windowSec = (statsStore.windowMs / 1000L).toInt().coerceIn(minSec, maxSec)
        sbWindow.max = maxSec - minSec
        sbWindow.progress = windowSec - minSec
        tvWindow.text = getString(R.string.window_value_fmt, getString(R.string.window_label), windowSec)

        sbWindow.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val sec = progress + minSec
                tvWindow.text = getString(R.string.window_value_fmt, getString(R.string.window_label), sec)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val sec = (seekBar?.progress ?: (6 - minSec)) + minSec
                statsStore.windowMs = sec * 1000L
                SkipLog.log("SETTING window_ms=${sec * 1000L}")
            }
        })

        findViewById<Button>(R.id.btnEnable).setOnClickListener {
            ensureNotificationPermission()
            if (!PermissionHelper.openAccessibilitySettings(this)) {
                SkipLog.log("UI cannot open accessibility settings")
            }
        }
        findViewById<Button>(R.id.btnRestrictedSettings).setOnClickListener {
            PermissionHelper.openAppDetailsSettings(this)
        }
        findViewById<Button>(R.id.btnBatteryOpt).setOnClickListener {
            ensureNotificationPermission()
            PermissionHelper.openBatteryOptimizationSettings(this)
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener {
            startTestScenario(TestSplashActivity.SCENARIO_STANDARD)
        }
        findViewById<Button>(R.id.btnTestLab).setOnClickListener {
            showTestLabPicker()
        }
        findViewById<Button>(R.id.btnExclusions).setOnClickListener {
            showExclusionsDialog()
        }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            refreshUi(forceLog = true)
        }
        findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            copyLogsToClipboard()
        }
        findViewById<Button>(R.id.btnShareLog).setOnClickListener {
            shareLogs()
        }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            SkipLog.clear()
            refreshUi(forceLog = true)
        }

        handleTestLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTestLaunchIntent(intent)
    }

    private fun handleTestLaunchIntent(intent: Intent?) {
        val scenario = intent?.getStringExtra("launch_scenario") ?: return
        intent.removeExtra("launch_scenario")
        handler.postDelayed({ startTestScenario(scenario) }, 150)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun refreshUi(forceLog: Boolean) {
        val enabledInSettings = PermissionHelper.isAccessibilityEnabledInSettings(this)
        tvStatus.text = when {
            SkipAdService.connected -> getString(R.string.status_running)
            enabledInSettings -> getString(R.string.status_connecting)
            else -> getString(R.string.status_disabled)
        }

        val batteryExempt = PermissionHelper.isIgnoringBatteryOptimizations(this)
        val batteryLabel = if (batteryExempt) "✅ 电池优化已豁免" else "⚠️ 未豁免电池优化（后台易被杀）"
        tvKeepAliveInfo.text = "$batteryLabel\n${PermissionHelper.oemKeepAliveAdvice()}"

        val s = statsStore.getSummary()
        tvStats.text = if (s.totalSkipped == 0) {
            getString(R.string.stats_empty)
        } else {
            val latencyPart = if (s.sampleCount > 0) "P50 ${s.p50Ms}ms · P95 ${s.p95Ms}ms" else "延迟统计中"
            "今日跳过 ${s.todaySkipped} 次 · 累计 ${s.totalSkipped} 次 · $latencyPart"
        }

        // Never interrupt user's active text selection when refreshing logs
        val currentVersion = SkipLog.version.get()
        if ((forceLog || currentVersion != lastRenderedLogVersion) && !tvLog.hasSelection()) {
            lastRenderedLogVersion = currentVersion
            val log = SkipLog.tail(80)
            tvLog.text = if (log.isEmpty()) getString(R.string.log_empty) else log
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }
    }

    private fun startTestScenario(scenario: String) {
        startActivity(
            Intent(this, TestSplashActivity::class.java).putExtra(
                TestSplashActivity.EXTRA_SCENARIO,
                scenario
            )
        )
    }

    private fun showTestLabPicker() {
        val labels = arrayOf(
            "1. [正向] 标准开屏 (800ms Button)",
            "2. [正向] 不可点击控件 (强制手势)",
            "3. [正向] 全屏可点击父容器陷阱 (防误点广告主体)",
            "4. [正向] ACTION_CLICK 假成功 (第2次自动升级手势)",
            "5. [正向] 空 text + contentDescription='跳过广告'",
            "6. [负向] '跳过引导' 对照页 (必须 0 点击)",
            "7. [负向] '点击跳过领红包' 对照页 (必须 0 点击)",
            "8. [负向] 屏幕正中'跳过' 对照页 (必须 0 点击)"
        )
        val keys = arrayOf(
            TestSplashActivity.SCENARIO_STANDARD,
            TestSplashActivity.SCENARIO_NON_CLICKABLE,
            TestSplashActivity.SCENARIO_FULLSCREEN_PARENT,
            TestSplashActivity.SCENARIO_FAKE_SUCCESS,
            TestSplashActivity.SCENARIO_DESC_ONLY,
            TestSplashActivity.SCENARIO_NEG_GUIDE,
            TestSplashActivity.SCENARIO_NEG_REDPACKET,
            TestSplashActivity.SCENARIO_NEG_CENTER
        )
        AlertDialog.Builder(this)
            .setTitle("选择靶场验证场景")
            .setItems(labels) { _, which -> startTestScenario(keys[which]) }
            .show()
    }

    private fun showExclusionsDialog() {
        val custom = statsStore.getUserDisabledPackages().sorted()
        val msg = buildString {
            append("【默认内置保护（不可误触）】\n")
            append("• 系统与桌面组件（Android/Honor/Huawei/MIUI/ColorOS/vivo 等）\n")
            append("• 主流输入法\n")
            append("• 金融与支付类应用（共 ${PackagePolicy.SENSITIVE_FINANCE_PACKAGES.size} 个：微信/支付宝/云闪付/主要银行）\n\n")
            append("【用户自定义忽略列表（${custom.size} 个）】\n")
            if (custom.isEmpty()) {
                append("（暂无，可点击下方按钮添加包名）")
            } else {
                custom.forEach { append("• ").append(it).append('\n') }
            }
        }
        AlertDialog.Builder(this)
            .setTitle("安全排除名单")
            .setMessage(msg)
            .setPositiveButton("添加包名") { _, _ -> promptAddExclusion() }
            .setNeutralButton("移除包名") { _, _ -> promptRemoveExclusion(custom) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun promptAddExclusion() {
        val input = EditText(this).apply { hint = "例如 com.example.app" }
        AlertDialog.Builder(this)
            .setTitle("添加忽略应用包名")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val pkg = input.text.toString().trim()
                if (pkg.isNotEmpty()) {
                    statsStore.setPackageDisabled(pkg, true)
                    SkipLog.log("SETTING excluded+=$pkg")
                    Toast.makeText(this, "已加入忽略名单：$pkg", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun promptRemoveExclusion(custom: List<String>) {
        if (custom.isEmpty()) {
            Toast.makeText(this, "当前没有自定义忽略包名", Toast.LENGTH_SHORT).show()
            return
        }
        val items = custom.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择要移除的包名")
            .setItems(items) { _, which ->
                val pkg = items[which]
                statsStore.setPackageDisabled(pkg, false)
                SkipLog.log("SETTING excluded-=$pkg")
                Toast.makeText(this, "已移除：$pkg", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun copyLogsToClipboard() {
        val content = SkipLog.exportAll()
        if (content.isBlank()) {
            Toast.makeText(this, "暂无可复制日志", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("FlashSkip Log", content))
        Toast.makeText(this, "已复制全部日志", Toast.LENGTH_SHORT).show()
    }

    private fun shareLogs() {
        val content = SkipLog.exportAll()
        if (content.isBlank()) {
            Toast.makeText(this, "暂无可导出日志", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "FlashSkip 运行日志")
            putExtra(Intent.EXTRA_TEXT, content)
        }
        startActivity(Intent.createChooser(intent, "导出 FlashSkip 日志"))
    }
}
