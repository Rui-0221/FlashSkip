package com.flashskip.ui

import androidx.appcompat.app.AppCompatDelegate
import com.flashskip.TestSplashActivity
import com.flashskip.core.SkipStateMachine
import com.flashskip.data.StatsSummary
import com.flashskip.keepalive.KeepAliveState
import com.flashskip.keepalive.KeepAliveStatus
import com.flashskip.keepalive.SettingsDestination
import java.util.Locale

/**
 * Pure presentation formatters, consumer-friendly status mapping, and test-range catalog
 * for FlashSkip. Keeps all state/text/layout calculations deterministic and JVM-testable.
 */
object ConsoleFormatter {

    enum class ThemeOption(val nightMode: Int, val label: String, val headerLabel: String) {
        SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, "跟随系统", "主题·自动"),
        LIGHT(AppCompatDelegate.MODE_NIGHT_NO, "浅色", "主题·浅色"),
        DARK(AppCompatDelegate.MODE_NIGHT_YES, "深色", "主题·深色");

        companion object {
            fun fromNightMode(mode: Int): ThemeOption =
                entries.firstOrNull { it.nightMode == mode } ?: SYSTEM

            fun nextCycle(mode: Int): ThemeOption = when (fromNightMode(mode)) {
                SYSTEM -> LIGHT
                LIGHT -> DARK
                DARK -> SYSTEM
            }
        }
    }

    enum class ThemeStyleOption(val key: String, val label: String, val description: String) {
        CLASSIC("classic", "日常简约", "圆润卡片与清晰日常风格"),
        ENDFIELD("endfield", "终末地", "工业手册风格 · 切角与电光黄高亮");

        companion object {
            fun fromKey(raw: String?): ThemeStyleOption =
                entries.firstOrNull { it.key.equals(raw, ignoreCase = true) } ?: CLASSIC
        }
    }

    enum class StatusTone { OK, WARN, PAUSED, ALERT }

    enum class HomePrimaryAction {
        OPEN_ACCESSIBILITY,
        RESUME_MASTER,
        OPEN_KEEPALIVE,
        DISABLE_OBSERVE,
        RUN_QUICK_TEST
    }

    data class HeaderStatus(
        val label: String,
        val tone: StatusTone
    )

    data class HomeHeroPresentation(
        val badgeText: String,
        val badgeTone: StatusTone,
        val keepAliveChipText: String,
        val keepAliveHealthy: Boolean,
        val headline: String,
        val description: String,
        val bannerText: String?,
        val primaryCtaLabel: String,
        val primaryAction: HomePrimaryAction,
        val secondaryCtaLabel: String?,
        val showRestrictedHelp: Boolean
    )

    data class BrandGuideSpec(
        val brandName: String,
        val steps: List<String>
    )

    enum class TestGroup(val code: String, val title: String) {
        POSITIVE("POSITIVE", "常见可自动跳过"),
        NEGATIVE("NEGATIVE", "防误触安全对照"),
        BOUNDARY("BOUNDARY", "特殊边界对照")
    }

    data class TestScenarioSpec(
        val indexLabel: String,
        val key: String,
        val group: TestGroup,
        val title: String,
        val detail: String
    )

    data class StatsPresentation(
        val isEmpty: Boolean,
        val todayText: String,
        val totalText: String,
        val p50Text: String,
        val p95Text: String,
        val sampleCountText: String,
        val stillPresentText: String,
        val navigatedText: String,
        val unobservedText: String,
        val legacyNotice: String?,
        val summaryLine: String
    )

    data class ReadinessState(
        val readyCount: Int,
        val totalCount: Int,
        val filledSegments24: Int,
        val fractionText: String
    )

    data class HeaderPlacement(
        val rows: Int,
        val brandX: Int,
        val brandY: Int,
        val statusX: Int,
        val statusY: Int,
        val themeX: Int,
        val themeY: Int,
        val contentHeight: Int
    )

    val TEST_LAB_SCENARIOS: List<TestScenarioSpec> = listOf(
        TestScenarioSpec("No.01", TestSplashActivity.SCENARIO_STANDARD, TestGroup.POSITIVE, "普通“跳过”按钮", "延迟出现标准按钮，验证快速点击关闭"),
        TestScenarioSpec("No.02", TestSplashActivity.SCENARIO_NON_CLICKABLE, TestGroup.POSITIVE, "自绘不可直接点击按钮", "模拟自定义文字控件，通过安全手势点击"),
        TestScenarioSpec("No.03", TestSplashActivity.SCENARIO_FULLSCREEN_PARENT, TestGroup.POSITIVE, "全屏广告区域防误点", "避开全屏广告跳转区，只准确点右上角小按钮"),
        TestScenarioSpec("No.04", TestSplashActivity.SCENARIO_FAKE_SUCCESS, TestGroup.POSITIVE, "忽略无障碍点击的广告", "普通点击被忽略时自动改用屏幕手势跳过"),
        TestScenarioSpec("No.05", TestSplashActivity.SCENARIO_DESC_ONLY, TestGroup.POSITIVE, "仅带无障碍描述的按钮", "没有可见文字，仅靠描述识别“跳过广告”"),
        TestScenarioSpec("No.06", "id_only", TestGroup.POSITIVE, "无文字通用跳过控件", "靠常见广告组件标识识别并安全跳过"),
        TestScenarioSpec("No.07", "webview", TestGroup.POSITIVE, "网页内嵌跳过按钮", "网页视图中的“跳过广告”链接关闭"),
        TestScenarioSpec("No.08", "custom_view", TestGroup.POSITIVE, "纯图形叉号关闭按钮", "右上角自绘关闭图标（×）识别与关闭"),
        TestScenarioSpec("No.09", "countdown", TestGroup.POSITIVE, "带倒计时的跳过按钮", "识别“3 | 跳过”组合提示并自动关闭"),
        TestScenarioSpec("No.10", "fullscreen_overlay", TestGroup.POSITIVE, "延迟弹出的全屏广告层", "进入首页后延迟弹出的全屏广告跳过"),
        TestScenarioSpec("No.11", "shake", TestGroup.POSITIVE, "摇一摇广告的跳过入口", "避开摇一摇提示，只点可访问的跳过按钮"),
        TestScenarioSpec("No.12", TestSplashActivity.SCENARIO_NEG_GUIDE, TestGroup.NEGATIVE, "新手“跳过引导”按钮", "保护正常引导流程，确保绝不误点"),
        TestScenarioSpec("No.13", TestSplashActivity.SCENARIO_NEG_REDPACKET, TestGroup.NEGATIVE, "诱导“跳过领红包”按钮", "识别并拦截诱导广告词，必须保持 0 点击"),
        TestScenarioSpec("No.14", TestSplashActivity.SCENARIO_NEG_CENTER, TestGroup.NEGATIVE, "页面正中间普通“跳过”", "位置安全锁拦截居中正常按钮，绝不误点"),
        TestScenarioSpec("No.15", "neg_feed", TestGroup.NEGATIVE, "信息流中央含广告字样按钮", "位于内容区中心而非角落的按钮，保持 0 点击"),
        TestScenarioSpec("No.16", "neg_payment", TestGroup.NEGATIVE, "支付页面“跳过付款”", "高危支付字样拦截，确保金融界面安全"),
        TestScenarioSpec("No.17", "redirect", TestGroup.BOUNDARY, "点击后跳转到其他页面", "验证如实记录为发生跳转，不虚报成功关闭"),
        TestScenarioSpec("No.18", "hide_target", TestGroup.BOUNDARY, "按钮消失但广告仍在", "仅按钮隐藏而广告画面未关时，不记为成功"),
        TestScenarioSpec("No.19", "shake_first", TestGroup.BOUNDARY, "跳过未出现前已自动跳转", "验证无关闭按钮时不强行乱点"),
        TestScenarioSpec("No.20", "render_only", TestGroup.BOUNDARY, "纯画面绘制无法识别的叉号", "无任何系统节点时禁止盲目乱点屏幕")
    )

    fun headerStatus(status: KeepAliveStatus, observeOnly: Boolean): HeaderStatus = when (status.state) {
        KeepAliveState.NEEDS_ACCESSIBILITY -> HeaderStatus("待开启", StatusTone.ALERT)
        KeepAliveState.CONNECTING -> HeaderStatus("待重连", StatusTone.WARN)
        KeepAliveState.PAUSED -> HeaderStatus("已暂停", StatusTone.PAUSED)
        KeepAliveState.RUNNING -> when {
            observeOnly -> HeaderStatus("演练中", StatusTone.WARN)
            !status.batteryExempt || status.backgroundRestricted -> HeaderStatus("建议保活", StatusTone.WARN)
            else -> HeaderStatus("保护中", StatusTone.OK)
        }
    }

    fun formatHomeHero(status: KeepAliveStatus, observeOnly: Boolean): HomeHeroPresentation {
        val keepAliveHealthy = status.batteryExempt && !status.backgroundRestricted && status.notificationsVisible
        val keepAliveChip = when {
            status.backgroundRestricted -> "系统限制后台运行"
            !status.batteryExempt -> "未关闭省电限制"
            !status.notificationsVisible -> "建议允许状态通知"
            else -> "后台常驻已就绪"
        }

        return when (status.state) {
            KeepAliveState.NEEDS_ACCESSIBILITY -> HomeHeroPresentation(
                badgeText = "还差一步：开启权限",
                badgeTone = StatusTone.ALERT,
                keepAliveChipText = keepAliveChip,
                keepAliveHealthy = keepAliveHealthy,
                headline = "开启“无障碍服务”后，就能自动跳过广告",
                description = "开启后，每次打开其他软件遇到开屏广告都会自动帮您点掉“跳过”。纯本地运行，绝不联网或读取个人隐私。",
                bannerText = "如果系统设置里 FlashSkip 开关是灰色的点不动，请先点下方「解除系统限制」，在右上角菜单点“允许受限设置”后再回来打开。",
                primaryCtaLabel = "去开启无障碍服务",
                primaryAction = HomePrimaryAction.OPEN_ACCESSIBILITY,
                secondaryCtaLabel = "点不了开关？点此解除受限设置",
                showRestrictedHelp = true
            )

            KeepAliveState.CONNECTING -> HomeHeroPresentation(
                badgeText = "后台连接中断",
                badgeTone = StatusTone.WARN,
                keepAliveChipText = keepAliveChip,
                keepAliveHealthy = keepAliveHealthy,
                headline = "权限已开启，但后台服务被系统暂停了",
                description = "这通常是因为系统清理或休眠导致连接暂时断开。点击下方按钮进入「无障碍」，把 FlashSkip 先关闭再重新开启即可恢复。",
                bannerText = "想避免以后再次断开，恢复开关后请去「后台保活」页完成电量无限制和自启动设置。",
                primaryCtaLabel = "去无障碍重新开关一次",
                primaryAction = HomePrimaryAction.OPEN_ACCESSIBILITY,
                secondaryCtaLabel = "查看后台保活设置指南",
                showRestrictedHelp = false
            )

            KeepAliveState.PAUSED -> HomeHeroPresentation(
                badgeText = "已手动暂停",
                badgeTone = StatusTone.PAUSED,
                keepAliveChipText = keepAliveChip,
                keepAliveHealthy = keepAliveHealthy,
                headline = "您已暂停自动跳过开屏广告",
                description = "系统权限依然保留着，打开下方的「自动跳过开屏广告」开关（或点下方按钮）就能立刻恢复使用。",
                bannerText = null,
                primaryCtaLabel = "立即恢复自动跳过",
                primaryAction = HomePrimaryAction.RESUME_MASTER,
                secondaryCtaLabel = if (!keepAliveHealthy) "完善后台保活设置" else null,
                showRestrictedHelp = false
            )

            KeepAliveState.RUNNING -> when {
                observeOnly -> HomeHeroPresentation(
                    badgeText = "演练模式中",
                    badgeTone = StatusTone.WARN,
                    keepAliveChipText = keepAliveChip,
                    keepAliveHealthy = keepAliveHealthy,
                    headline = "当前处于“仅演练不点击”模式，不会真的跳过广告",
                    description = "您开启了高级工具里的演练模式，现在只会记录发现按钮，不会实际点击。普通使用请关闭演练模式。",
                    bannerText = "提示：点击下方按钮即可一键恢复正常自动跳过。",
                    primaryCtaLabel = "关闭演练，恢复自动跳过",
                    primaryAction = HomePrimaryAction.DISABLE_OBSERVE,
                    secondaryCtaLabel = "去完成后台保活设置",
                    showRestrictedHelp = false
                )

                !keepAliveHealthy -> {
                    val reason = when {
                        status.backgroundRestricted ->
                            "当前可以跳过广告，但系统限制了后台活动，息屏或过几小时后很可能自动停止工作。"
                        !status.batteryExempt ->
                            "当前可以跳过广告，但还未关闭电池省电限制，手机息屏一会后服务容易被系统清理。"
                        else ->
                            "当前可以跳过广告，建议允许状态栏通知以减少后台被清理，并显示“已跳过广告”轻提示。"
                    }
                    HomeHeroPresentation(
                        badgeText = "保护中 · 建议设置保活",
                        badgeTone = StatusTone.WARN,
                        keepAliveChipText = keepAliveChip,
                        keepAliveHealthy = false,
                        headline = "正在为您跳过广告，建议完成后台保活",
                        description = reason,
                        bannerText = "完成「后台保活」设置后，无需频繁重开，1 小时、半天甚至整天都能稳稳工作。",
                        primaryCtaLabel = "去确保后台长期有效",
                        primaryAction = HomePrimaryAction.OPEN_KEEPALIVE,
                        secondaryCtaLabel = "先体验一次模拟跳过",
                        showRestrictedHelp = false
                    )
                }

                else -> HomeHeroPresentation(
                    badgeText = "正在保护中",
                    badgeTone = StatusTone.OK,
                    keepAliveChipText = "后台常驻已就绪",
                    keepAliveHealthy = true,
                    headline = "一切就绪，正在为您自动跳过开屏广告",
                    description = "打开其他应用时会自动帮您点掉开屏广告；已开启长效保护，锁屏放置也不易失效。",
                    bannerText = null,
                    primaryCtaLabel = "体验一次模拟跳过效果",
                    primaryAction = HomePrimaryAction.RUN_QUICK_TEST,
                    secondaryCtaLabel = null,
                    showRestrictedHelp = false
                )
            }
        }
    }

    fun computeKeepAliveReadiness4(status: KeepAliveStatus): ReadinessState {
        val ready = (if (status.accessibilityConnected) 1 else 0) +
            (if (status.notificationsVisible) 1 else 0) +
            (if (status.batteryExempt) 1 else 0) +
            (if (!status.backgroundRestricted) 1 else 0)
        return ReadinessState(
            readyCount = ready,
            totalCount = 4,
            filledSegments24 = (ready * 24) / 4,
            fractionText = "$ready / 4 项已就绪"
        )
    }

    fun oemJumpFeedbackText(destination: SettingsDestination?): String = when (destination) {
        SettingsDestination.OEM_BACKGROUND ->
            "已尝试打开本机的后台/应用启动管理页。请在列表里找到 FlashSkip，关闭「自动管理」，把自启动、关联启动和后台活动全部打开。"
        SettingsDestination.APP_DETAILS ->
            "当前手机系统限制了直达启动管理页，已为您打开「FlashSkip 应用详情」。请点击其中的「电池 / 耗电管理」设为无限制，并按下方步骤去系统管家放行。"
        SettingsDestination.SYSTEM_SETTINGS ->
            "已为您打开系统「设置」。请直接在设置顶部搜索框里搜索下方步骤中提到的菜单名称（如“应用启动管理”或“自启动”）。"
        null ->
            "当前系统无法由应用自动跳转该设置页。请手动打开手机「设置」，照着下方第 1 步到第 4 步逐一设置即可。"
    }

    fun brandDisplayName(manufacturer: String, brand: String): String {
        val words = "$brand $manufacturer".lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+"))
        return when {
            words.any { it == "honor" || it == "hihonor" } -> "荣耀手机（MagicOS）"
            words.any { it == "huawei" } -> "华为手机（HarmonyOS / EMUI）"
            words.any { it in setOf("xiaomi", "redmi", "poco", "blackshark") } -> "小米 / 红米手机（澎湃OS / MIUI）"
            words.any { it in setOf("oppo", "oneplus", "realme") } -> "OPPO / 一加 / 真我手机（ColorOS）"
            words.any { it in setOf("vivo", "iqoo") } -> "vivo / iQOO 手机（OriginOS）"
            words.any { it == "samsung" } -> "三星手机（One UI）"
            words.any { it == "meizu" } -> "魅族手机（Flyme）"
            else -> "通用安卓手机"
        }
    }

    fun buildBrandStepsWithRecentLock(baseSteps: List<String>): List<String> {
        val lockStep = "从屏幕底部上滑悬停进入「最近任务（多任务）」界面，找到 FlashSkip 卡片并向下轻拉（或点卡片菜单）加上小锁 🔒，防止一键清理时被关闭。"
        return if (baseSteps.any { "最近任务" in it || "加锁" in it || "锁定" in it }) {
            baseSteps
        } else {
            baseSteps + lockStep
        }
    }

    fun allBrandGuides(): List<BrandGuideSpec> = listOf(
        BrandGuideSpec(
            "荣耀 / 华为手机（MagicOS / HarmonyOS）",
            buildBrandStepsWithRecentLock(listOf(
                "打开手机「设置」，搜索并进入「应用启动管理」。",
                "找到 FlashSkip，关闭旁边的「自动管理」。",
                "在弹出的手动管理窗口里同时打开「允许自启动」「允许关联启动」「允许后台活动」，点「确定」。"
            ))
        ),
        BrandGuideSpec(
            "小米 / 红米手机（澎湃OS / MIUI）",
            buildBrandStepsWithRecentLock(listOf(
                "打开手机「设置」，搜索并进入「自启动」或「后台自启动」，打开 FlashSkip 开关。",
                "打开「设置 → 应用设置 → 应用管理 → FlashSkip → 省电策略」，选择「无限制」。",
                "若在“应用详情”中看到「暂停使用闲置应用 / 闲置时删除权限」，请将其关闭。"
            ))
        ),
        BrandGuideSpec(
            "OPPO / 一加 / 真我手机（ColorOS）",
            buildBrandStepsWithRecentLock(listOf(
                "打开手机「设置 → 应用 → 自启动」，找到并允许 FlashSkip 自启动。",
                "进入「设置 → 应用 → 应用管理 → FlashSkip → 耗电管理」，打开「允许完全后台行为 / 允许后台运行」。",
                "若无障碍开关呈灰色，先到应用详情右上角点「允许受限设置」。"
            ))
        ),
        BrandGuideSpec(
            "vivo / iQOO 手机（OriginOS）",
            buildBrandStepsWithRecentLock(listOf(
                "打开手机「设置」，搜索「自启动」，打开 FlashSkip 的允许自启动开关。",
                "在设置里搜索「后台高耗电」，找到 FlashSkip 并设为「允许后台高耗电」。"
            ))
        ),
        BrandGuideSpec(
            "三星手机（One UI）",
            buildBrandStepsWithRecentLock(listOf(
                "打开手机「设置 → 电池 → 后台使用限制」。",
                "进入「从不自动休眠的应用程序」，点击右上角“+”号添加 FlashSkip。"
            ))
        ),
        BrandGuideSpec(
            "其他 / 通用安卓手机",
            buildBrandStepsWithRecentLock(listOf(
                "打开手机「设置 → 应用 → FlashSkip → 电池」，选择「无限制」或「不优化」。",
                "若系统设置里无障碍开关为灰色，点应用详情右上角菜单「允许受限设置」。"
            ))
        )
    )

    fun formatStats(summary: StatsSummary): StatsPresentation {
        val hasAnyActivity = summary.totalSkipped > 0 ||
            summary.stillPresentAttempts > 0 ||
            summary.navigatedAttempts > 0 ||
            summary.unobservedAttempts > 0

        val legacyNote = if (summary.legacyTotalSkipped > 0) {
            "早期版本历史记录：${summary.legacyTotalSkipped} 次（独立保留，未并入本页计数）"
        } else {
            null
        }

        val p50 = formatLatencyMs(summary.p50Ms, minRequired = 5, currentSamples = summary.sampleCount)
        val p95 = formatLatencyMs(summary.p95Ms, minRequired = 20, currentSamples = summary.sampleCount)

        val summaryLine = if (!hasAnyActivity) {
            if (legacyNote != null) "暂无最近跳过记录 │ $legacyNote" else "暂无最近跳过记录（所有数据只保存在本机）"
        } else {
            buildString {
                append("今日跳过 ${summary.todaySkipped} 次")
                append(" │ 累计 ${summary.totalSkipped} 次")
                append(" │ 中位速度 ${formatShortLatency(summary.p50Ms)}")
                append(" │ 95%耗时 ${formatShortLatency(summary.p95Ms)}")
                append(" │ ${summary.sampleCount} 条样本")
            }
        }

        return StatsPresentation(
            isEmpty = !hasAnyActivity,
            todayText = summary.todaySkipped.toString(),
            totalText = summary.totalSkipped.toString(),
            p50Text = p50,
            p95Text = p95,
            sampleCountText = "${summary.sampleCount} 条记录",
            stillPresentText = "${summary.stillPresentAttempts} 次",
            navigatedText = "${summary.navigatedAttempts} 次",
            unobservedText = "${summary.unobservedAttempts} 次",
            legacyNotice = legacyNote,
            summaryLine = summaryLine
        )
    }

    fun formatLatencyMs(valueMs: Long, minRequired: Int, currentSamples: Int): String = when {
        valueMs >= 0L -> "$valueMs ms"
        else -> "样本不足 ($currentSamples/$minRequired)"
    }

    private fun formatShortLatency(valueMs: Long): String =
        if (valueMs >= 0L) "${valueMs}ms" else "样本不足"

    fun computeReadiness(
        serviceConnected: Boolean,
        masterEnabled: Boolean,
        batteryExempt: Boolean
    ): ReadinessState {
        val ready = (if (serviceConnected) 1 else 0) +
            (if (masterEnabled) 1 else 0) +
            (if (batteryExempt) 1 else 0)
        return ReadinessState(
            readyCount = ready,
            totalCount = 3,
            filledSegments24 = (ready * 24) / 3,
            fractionText = "$ready / 3"
        )
    }

    fun windowSeconds(windowMs: Long): Int =
        (windowMs.coerceIn(SkipStateMachine.MIN_WINDOW_MS, SkipStateMachine.MAX_WINDOW_MS) / 1000L).toInt()

    fun windowSegments24(windowSec: Int): Int {
        val clamped = windowSec.coerceIn(
            (SkipStateMachine.MIN_WINDOW_MS / 1000L).toInt(),
            (SkipStateMachine.MAX_WINDOW_MS / 1000L).toInt()
        )
        return (clamped * 24) / (SkipStateMachine.MAX_WINDOW_MS / 1000L).toInt()
    }

    fun commandStatusText(
        serviceConnected: Boolean,
        enabledInSettings: Boolean,
        masterEnabled: Boolean,
        observeOnly: Boolean,
        windowSec: Int
    ): String {
        val state = when {
            !enabledInSettings -> "OFF"
            !serviceConnected -> "WAIT"
            !masterEnabled -> "PAUSE"
            observeOnly -> "OBS"
            else -> "RUN"
        }
        return "$state │ %02dS".format(windowSec)
    }

    /**
     * Plans responsive coordinates for the top header (`brand`, `status`, `themeQuick`)
     * so enlarged fonts on compact widths wrap naturally instead of clipping or crowding.
     */
    fun computeHeaderPlacement(
        availW: Int,
        brandW: Int,
        brandH: Int,
        statusW: Int,
        statusH: Int,
        themeW: Int,
        themeH: Int,
        brandGap: Int,
        statusGap: Int,
        rowGap: Int
    ): HeaderPlacement {
        val singleRowFits = brandW + brandGap + statusW + statusGap + themeW <= availW
        if (singleRowFits) {
            val rowH = maxOf(brandH, statusH, themeH)
            val themeX = (availW - themeW).coerceAtLeast(0)
            val statusX = (themeX - statusGap - statusW).coerceAtLeast(brandW + brandGap)
            return HeaderPlacement(
                rows = 1,
                brandX = 0,
                brandY = (rowH - brandH) / 2,
                statusX = statusX,
                statusY = (rowH - statusH) / 2,
                themeX = themeX,
                themeY = (rowH - themeH) / 2,
                contentHeight = rowH
            )
        }

        val row2Top = brandH + rowGap
        val row2Fits = statusW + statusGap + themeW <= availW
        if (row2Fits) {
            val row2H = maxOf(statusH, themeH)
            return HeaderPlacement(
                rows = 2,
                brandX = 0,
                brandY = 0,
                statusX = 0,
                statusY = row2Top + (row2H - statusH) / 2,
                themeX = (availW - themeW).coerceAtLeast(0),
                themeY = row2Top + (row2H - themeH) / 2,
                contentHeight = row2Top + row2H
            )
        }

        val row3Top = row2Top + statusH + rowGap
        return HeaderPlacement(
            rows = 3,
            brandX = 0,
            brandY = 0,
            statusX = 0,
            statusY = row2Top,
            themeX = (availW - themeW).coerceAtLeast(0),
            themeY = row3Top,
            contentHeight = row3Top + themeH
        )
    }
}
