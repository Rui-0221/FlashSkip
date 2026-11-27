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
        ENDFIELD("endfield", "终末地", "工业手册风格 · 等高线与电光黄高亮"),
        MEOW("meow", "软萌猫咪", "美短加白猫咪主题 · 海盐奶蓝与互动猫猫");

        companion object {
            fun fromKey(raw: String?): ThemeStyleOption =
                entries.firstOrNull { it.key.equals(raw, ignoreCase = true) } ?: CLASSIC
        }
    }

    enum class CatMood {
        HAPPY_GUARD,
        NEED_PERMISSION,
        ATTENTIVE_WARN,
        SLEEPING_PAUSED
    }

    enum class MeowCatPose(val poseBadge: String) {
        SLEEPING("zZ 呼噜打盹中"),
        AWAKE_WALK("★ 巡逻散步喵"),
        AWAKE_ROLL("★ 露肚卖萌喵"),
        AWAKE_SIT("★ 端坐盯梢喵"),
        AWAKE_STRETCH("★ 伸腰元气喵")
    }

    val AWAKE_CAT_POSES: List<MeowCatPose> = listOf(
        MeowCatPose.AWAKE_WALK,
        MeowCatPose.AWAKE_ROLL,
        MeowCatPose.AWAKE_SIT,
        MeowCatPose.AWAKE_STRETCH
    )

    data class MeowCatPresentation(
        val mood: CatMood,
        val pose: MeowCatPose,
        val isAwake: Boolean,
        val titleTag: String,
        val speechText: String
    )

    data class EndfieldHudTelemetry(
        val statusCode: String,
        val syncPercent: Int,
        val subLabel: String,
        val isAlert: Boolean,
        val isActive: Boolean
    )

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
            !status.isKeepAliveHealthy -> HeaderStatus("建议保活", StatusTone.WARN)
            else -> HeaderStatus("保护中", StatusTone.OK)
        }
    }

    fun formatHomeHero(status: KeepAliveStatus, observeOnly: Boolean): HomeHeroPresentation {
        val keepAliveHealthy = status.isKeepAliveHealthy
        val foregroundMissing = status.accessibilityConnected &&
            status.masterEnabled &&
            status.notificationsVisible &&
            (!status.notificationChannelReady || !status.foregroundActive)
        val keepAliveChip = when {
            status.backgroundRestricted -> "系统限制后台运行"
            !status.batteryExempt -> "未关闭省电限制"
            !status.notificationsReady -> if (foregroundMissing) "守护通知未运行" else "建议允许状态通知"
            !status.oemKeepAliveConfirmed -> "厂商自启待核对"
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
                        !status.notificationsReady ->
                            if (foregroundMissing) {
                                "当前可以跳过广告，但前台守护通知未实际拉起，后台更容易被系统回收，请点「一键配置体检」排查。"
                            } else {
                                "当前可以跳过广告，建议允许状态栏通知以减少后台被清理，并显示“已跳过广告”轻提示。"
                            }
                        else ->
                            "当前可以跳过广告，但尚未核对手机厂商「应用启动管理 / 自启动」设置，锁屏或清理后台后容易失效。"
                    }
                    HomeHeroPresentation(
                        badgeText = "保护中 · 建议设置保活",
                        badgeTone = StatusTone.WARN,
                        keepAliveChipText = keepAliveChip,
                        keepAliveHealthy = false,
                        headline = "正在为您跳过广告，建议完成后台保活",
                        description = reason,
                        bannerText = "完成「后台保活」设置并核对自启后，无需频繁重开，1 小时、半天甚至整天都能稳稳工作。",
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
            (if (status.notificationsReady) 1 else 0) +
            (if (status.batteryExempt) 1 else 0) +
            (if (status.backgroundReady) 1 else 0)
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

    fun isCatAwakeReady(status: KeepAliveStatus, observeOnly: Boolean): Boolean =
        status.state == KeepAliveState.RUNNING &&
            !observeOnly &&
            status.isKeepAliveHealthy

    fun pickRandomAwakePose(previous: MeowCatPose?, randomVal: Int): MeowCatPose {
        val candidates = if (previous != null && previous in AWAKE_CAT_POSES) {
            AWAKE_CAT_POSES.filter { it != previous }
        } else {
            AWAKE_CAT_POSES
        }
        val idx = Math.floorMod(randomVal, candidates.size)
        return candidates[idx]
    }

    fun formatMeowCat(
        status: KeepAliveStatus,
        observeOnly: Boolean,
        todaySkipped: Int,
        preferredAwakePose: MeowCatPose = MeowCatPose.AWAKE_SIT
    ): MeowCatPresentation {
        if (isCatAwakeReady(status, observeOnly)) {
            val awakePose = if (preferredAwakePose in AWAKE_CAT_POSES) {
                preferredAwakePose
            } else {
                MeowCatPose.AWAKE_SIT
            }
            val speech = if (todaySkipped > 0) {
                "喵！全部守护已就绪，今天已帮你拍飞 ${todaySkipped} 个开屏广告啦，摸摸我呀~"
            } else {
                when (awakePose) {
                    MeowCatPose.AWAKE_WALK -> "喵~ 全部设置已就位，美短小猫正在全速巡逻，开屏广告一冒头就拍掉！"
                    MeowCatPose.AWAKE_ROLL -> "喵呜~ 所有守护都已就位！小猫安心露肚皮打滚，广告交给我来拦~"
                    MeowCatPose.AWAKE_STRETCH -> "哈欠——伸个大懒腰！全部设置已就位，精神满满守护每一次启动~"
                    else -> "喵~ 美短小猫端坐就位，全部守护已开启，打开其他应用自动帮你点掉广告！"
                }
            }
            return MeowCatPresentation(
                mood = CatMood.HAPPY_GUARD,
                pose = awakePose,
                isAwake = true,
                titleTag = awakePose.poseBadge,
                speechText = speech
            )
        }

        return when (status.state) {
            KeepAliveState.NEEDS_ACCESSIBILITY -> MeowCatPresentation(
                mood = CatMood.NEED_PERMISSION,
                pose = MeowCatPose.SLEEPING,
                isAwake = false,
                titleTag = "zZ 等开无障碍喵",
                speechText = "呼噜…还没开启无障碍权限，小猫先眯一会儿，点下方开启就能叫醒我哦~"
            )

            KeepAliveState.CONNECTING -> MeowCatPresentation(
                mood = CatMood.ATTENTIVE_WARN,
                pose = MeowCatPose.SLEEPING,
                isAwake = false,
                titleTag = "zZ 连接掉线打盹",
                speechText = "呼噜…小猫刚才和系统失联困住啦，去无障碍里重新开关一次就能醒来~"
            )

            KeepAliveState.PAUSED -> MeowCatPresentation(
                mood = CatMood.SLEEPING_PAUSED,
                pose = MeowCatPose.SLEEPING,
                isAwake = false,
                titleTag = "zZ 午睡打盹中",
                speechText = "呼噜噜…自动跳过已暂停，小猫先蜷起来睡一觉，需要时随时唤醒我~"
            )

            KeepAliveState.RUNNING -> when {
                observeOnly -> MeowCatPresentation(
                    mood = CatMood.ATTENTIVE_WARN,
                    pose = MeowCatPose.SLEEPING,
                    isAwake = false,
                    titleTag = "zZ 演练小憩中",
                    speechText = "呼噜…当前是“仅演练不点击”，关闭演练并备齐设置，小猫就立刻醒来干活~"
                )

                status.backgroundRestricted -> MeowCatPresentation(
                    mood = CatMood.ATTENTIVE_WARN,
                    pose = MeowCatPose.SLEEPING,
                    isAwake = false,
                    titleTag = "zZ 后台受限困倦",
                    speechText = "呼噜…系统限制了后台活动，去「后台保活」放行全部设置，小猫就能元气醒来~"
                )

                !status.batteryExempt -> MeowCatPresentation(
                    mood = CatMood.ATTENTIVE_WARN,
                    pose = MeowCatPose.SLEEPING,
                    isAwake = false,
                    titleTag = "zZ 省电打盹中",
                    speechText = "呼噜…还没设为电量无限制，补齐保活最后几步就能把小猫彻底叫醒哦~"
                )

                !status.notificationsReady -> MeowCatPresentation(
                    mood = CatMood.ATTENTIVE_WARN,
                    pose = MeowCatPose.SLEEPING,
                    isAwake = false,
                    titleTag = "zZ 等开守护通知",
                    speechText = "呼噜…还差状态栏守护通知或前台未拉起，点「配置体检」排查一下就能叫醒小猫~"
                )

                else -> MeowCatPresentation(
                    mood = CatMood.ATTENTIVE_WARN,
                    pose = MeowCatPose.SLEEPING,
                    isAwake = false,
                    titleTag = "zZ 等核对自启喵",
                    speechText = "呼噜…还没核对手机自启动管理，点「配置体检」或去「后台保活」确认一下，小猫马上清醒守护你~"
                )
            }
        }
    }

    fun formatEndfieldHud(
        status: KeepAliveStatus,
        observeOnly: Boolean
    ): EndfieldHudTelemetry {
        val keepAliveHealthy = status.isKeepAliveHealthy
        return when (status.state) {
            KeepAliveState.NEEDS_ACCESSIBILITY -> EndfieldHudTelemetry(
                statusCode = "OFFLINE",
                syncPercent = 0,
                subLabel = "A11Y REQUIRED",
                isAlert = true,
                isActive = false
            )

            KeepAliveState.CONNECTING -> EndfieldHudTelemetry(
                statusCode = "RECONNECT",
                syncPercent = 25,
                subLabel = "LINK LOST",
                isAlert = true,
                isActive = false
            )

            KeepAliveState.PAUSED -> EndfieldHudTelemetry(
                statusCode = "STANDBY",
                syncPercent = 50,
                subLabel = "PAUSED",
                isAlert = false,
                isActive = false
            )

            KeepAliveState.RUNNING -> when {
                observeOnly -> EndfieldHudTelemetry(
                    statusCode = "OBSERVE",
                    syncPercent = 75,
                    subLabel = "DRY-RUN",
                    isAlert = false,
                    isActive = true
                )

                !keepAliveHealthy -> EndfieldHudTelemetry(
                    statusCode = "GUARD",
                    syncPercent = 75,
                    subLabel = "KEEP-ALIVE",
                    isAlert = false,
                    isActive = true
                )

                else -> EndfieldHudTelemetry(
                    statusCode = "ONLINE",
                    syncPercent = 100,
                    subLabel = "LINK ACTIVE",
                    isAlert = false,
                    isActive = true
                )
            }
        }
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

    enum class AuditItemStatus { PASS, WARN, FAIL }

    enum class AuditAction {
        OPEN_ACCESSIBILITY,
        OPEN_RESTRICTED,
        RESUME_MASTER,
        DISABLE_OBSERVE,
        OPEN_NOTIFICATION,
        OPEN_BATTERY,
        OPEN_APP_DETAILS,
        OPEN_OEM_BACKGROUND,
        TOGGLE_OEM_CONFIRM,
        RUN_LIVE_TEST
    }

    data class ConfigAuditItem(
        val indexLabel: String,
        val title: String,
        val statusLabel: String,
        val detail: String,
        val status: AuditItemStatus,
        val primaryActionLabel: String?,
        val primaryAction: AuditAction?,
        val secondaryActionLabel: String? = null,
        val secondaryAction: AuditAction? = null
    )

    data class ConfigAuditReport(
        val passedCount: Int,
        val totalCount: Int,
        val headline: String,
        val summary: String,
        val canRunLiveTest: Boolean,
        val items: List<ConfigAuditItem>
    )

    data class ThemeActionCopy(
        val homeCheckLabel: String,
        val kaCheckLabel: String,
        val oemConfirmLabel: String,
        val oemResetLabel: String,
        val dialogEyebrow: String,
        val dialogTitle: String,
        val liveTestLabel: String,
        val closeLabel: String
    )

    fun themeActionCopy(style: ThemeStyleOption): ThemeActionCopy = when (style) {
        ThemeStyleOption.CLASSIC -> ThemeActionCopy(
            homeCheckLabel = "一键配置体检",
            kaCheckLabel = "一键深度体检（核验真实状态）",
            oemConfirmLabel = "我已在系统页开启自启（标记已核对）",
            oemResetLabel = "已核对厂商自启（点此重新核对）",
            dialogEyebrow = "配置体检",
            dialogTitle = "全项配置深度体检",
            liveTestLabel = "实测能否正常跳过",
            closeLabel = "关闭"
        )
        ThemeStyleOption.ENDFIELD -> ThemeActionCopy(
            homeCheckLabel = "DIAG // 一键配置自检",
            kaCheckLabel = "RUN // 执行全项配置诊断",
            oemConfirmLabel = "CONFIRM // 标记厂商自启已放行",
            oemResetLabel = "VERIFIED // 重置厂商自启标记",
            dialogEyebrow = "SYSTEM AUDIT",
            dialogTitle = "配置状态深度诊断",
            liveTestLabel = "EXEC // 实测跳过闭环",
            closeLabel = "CLOSE"
        )
        ThemeStyleOption.MEOW -> ThemeActionCopy(
            homeCheckLabel = "🐾 帮猫猫做配置体检",
            kaCheckLabel = "🐾 一键深度体检全项守护",
            oemConfirmLabel = "🐾 我已帮猫猫开好自启啦",
            oemResetLabel = "🐾 已核对自启（点此重检）",
            dialogEyebrow = "猫猫守护体检",
            dialogTitle = "喵~ 全项守护体检报告",
            liveTestLabel = "🐾 实测一下猫猫跳过",
            closeLabel = "好哒"
        )
    }

    fun buildConfigAudit(
        status: KeepAliveStatus,
        observeOnly: Boolean,
        brandName: String,
        style: ThemeStyleOption = ThemeStyleOption.CLASSIC
    ): ConfigAuditReport {
        fun idx(n: Int): String = if (style == ThemeStyleOption.ENDFIELD) n.toString().padStart(2, '0') else n.toString()

        val item1 = when {
            !status.accessibilityEnabled -> ConfigAuditItem(
                indexLabel = idx(1),
                title = "无障碍服务与真实连接",
                statusLabel = "未开启",
                detail = "系统无障碍未勾选 FlashSkip。若开关为灰色点不动，请先进入应用详情右上角菜单「允许受限设置」。",
                status = AuditItemStatus.FAIL,
                primaryActionLabel = "去开启无障碍",
                primaryAction = AuditAction.OPEN_ACCESSIBILITY,
                secondaryActionLabel = "解除受限设置",
                secondaryAction = AuditAction.OPEN_RESTRICTED
            )
            !status.accessibilityConnected -> ConfigAuditItem(
                indexLabel = idx(1),
                title = "无障碍服务与真实连接",
                statusLabel = "连接断开",
                detail = "系统开关已勾选，但后台连接实际已中断（常见于系统清理或重启）。请前往无障碍重新开关一次。",
                status = AuditItemStatus.WARN,
                primaryActionLabel = "去重新开关",
                primaryAction = AuditAction.OPEN_ACCESSIBILITY
            )
            !status.masterEnabled -> ConfigAuditItem(
                indexLabel = idx(1),
                title = "无障碍服务与真实连接",
                statusLabel = "总开关已暂停",
                detail = "无障碍已连接，但您当前手动暂停了应用内总开关，恢复后即可正常跳过。",
                status = AuditItemStatus.WARN,
                primaryActionLabel = "立即恢复跳过",
                primaryAction = AuditAction.RESUME_MASTER
            )
            observeOnly -> ConfigAuditItem(
                indexLabel = idx(1),
                title = "无障碍服务与真实连接",
                statusLabel = "演练模式中",
                detail = "当前仅演练不实际点击，普通日常使用请关闭演练模式。",
                status = AuditItemStatus.WARN,
                primaryActionLabel = "关闭演练模式",
                primaryAction = AuditAction.DISABLE_OBSERVE
            )
            else -> ConfigAuditItem(
                indexLabel = idx(1),
                title = "无障碍服务与真实连接",
                statusLabel = "已真实连接",
                detail = "无障碍服务已真实绑定且总开关开启，具备实时识别与跳过能力。",
                status = AuditItemStatus.PASS,
                primaryActionLabel = null,
                primaryAction = null
            )
        }

        val item2 = when {
            !status.notificationPermissionGranted || !status.notificationsVisible -> ConfigAuditItem(
                indexLabel = idx(2),
                title = "前台守护通知存活",
                statusLabel = "未允许通知",
                detail = "缺少通知运行时权限或系统通知已被关闭，后台守护容易被系统回收，也无法看到跳过轻提示。",
                status = AuditItemStatus.WARN,
                primaryActionLabel = "去允许通知",
                primaryAction = AuditAction.OPEN_NOTIFICATION
            )
            status.accessibilityConnected && status.masterEnabled &&
                (!status.notificationChannelReady || !status.foregroundActive) -> ConfigAuditItem(
                    indexLabel = idx(2),
                    title = "前台守护通知存活",
                    statusLabel = "前台未拉起",
                    detail = "虽然总通知权限已允许，但守护通知渠道被禁用或前台守护未实际运行，请检查通知设置。",
                    status = AuditItemStatus.WARN,
                    primaryActionLabel = "检查通知渠道",
                    primaryAction = AuditAction.OPEN_NOTIFICATION
                )
            else -> ConfigAuditItem(
                indexLabel = idx(2),
                title = "前台守护通知存活",
                statusLabel = if (status.foregroundActive) "守护运行中" else "权限已允许",
                detail = if (status.foregroundActive) {
                    "通知权限、守护渠道与前台常驻服务均已真正在系统内运行。"
                } else {
                    "通知权限已允许；待无障碍服务连接后会自动拉起静默守护通知。"
                },
                status = AuditItemStatus.PASS,
                primaryActionLabel = null,
                primaryAction = null
            )
        }

        val item3 = if (!status.batteryExempt) {
            ConfigAuditItem(
                indexLabel = idx(3),
                title = "系统电池优化白名单",
                statusLabel = "受省电限制",
                detail = "尚未加入系统电池优化白名单，息屏放置一段时间后容易被系统省电策略清理。",
                status = AuditItemStatus.WARN,
                primaryActionLabel = "设为无限制",
                primaryAction = AuditAction.OPEN_BATTERY
            )
        } else {
            ConfigAuditItem(
                indexLabel = idx(3),
                title = "系统电池优化白名单",
                statusLabel = "已豁免省电",
                detail = "系统标准电池优化已放行，可显著减少息屏休眠清理。",
                status = AuditItemStatus.PASS,
                primaryActionLabel = null,
                primaryAction = null
            )
        }

        val item4 = if (status.backgroundRestricted) {
            ConfigAuditItem(
                indexLabel = idx(4),
                title = "系统原生后台限制",
                statusLabel = "已被系统限制",
                detail = "Android 原生后台电池限制已拦截本应用，请在应用详情的「电池」页解除限制。",
                status = AuditItemStatus.FAIL,
                primaryActionLabel = "去应用信息放行",
                primaryAction = AuditAction.OPEN_APP_DETAILS
            )
        } else {
            ConfigAuditItem(
                indexLabel = idx(4),
                title = "系统原生后台限制",
                statusLabel = "原生未拦截",
                detail = "原生 Android 未禁止后台运行；国产手机管家自启动开关不向应用开放读取，需核对下方第 5 项。",
                status = AuditItemStatus.PASS,
                primaryActionLabel = null,
                primaryAction = null
            )
        }

        val item5 = if (!status.oemKeepAliveConfirmed) {
            ConfigAuditItem(
                indexLabel = idx(5),
                title = "厂商自启与加锁（$brandName）",
                statusLabel = "待人工核对",
                detail = "Android 不允许应用读取系统管家的自启动/关联启动开关。请先点按钮前往确认已全部打开，再点「我已开启」。",
                status = AuditItemStatus.WARN,
                primaryActionLabel = "去厂商后台页",
                primaryAction = AuditAction.OPEN_OEM_BACKGROUND,
                secondaryActionLabel = "我已开启",
                secondaryAction = AuditAction.TOGGLE_OEM_CONFIRM
            )
        } else {
            ConfigAuditItem(
                indexLabel = idx(5),
                title = "厂商自启与加锁（$brandName）",
                statusLabel = "已人工核对",
                detail = "您已确认放行本机自启动与后台活动；建议同时在「最近任务」里给闪跳卡片加上小锁。",
                status = AuditItemStatus.PASS,
                primaryActionLabel = "再次查看设置",
                primaryAction = AuditAction.OPEN_OEM_BACKGROUND,
                secondaryActionLabel = "重置为待核对",
                secondaryAction = AuditAction.TOGGLE_OEM_CONFIRM
            )
        }

        val items = listOf(item1, item2, item3, item4, item5)
        val passed = items.count { it.status == AuditItemStatus.PASS }
        val total = items.size
        val canRunLiveTest = status.state == KeepAliveState.RUNNING && !observeOnly
        val headline = when {
            passed == total && canRunLiveTest -> "全部配置已核验就绪"
            status.state == KeepAliveState.NEEDS_ACCESSIBILITY -> "核心无障碍尚未开启"
            status.state == KeepAliveState.CONNECTING -> "无障碍连接已断开"
            else -> "仍有 ${total - passed} 项待完善或核对"
        }
        val summary = when {
            passed == total && canRunLiveTest ->
                "所有可检测权限与本地守护均已生效，点击下方可实测一次模拟广告跳过闭环。"
            canRunLiveTest ->
                "基础跳过功能已可用，但仍有 ${total - passed} 项保活设置待补齐，补全后息屏久放更稳。"
            else ->
                "请先按上方标黄或标红项完成开启与连接，之后即可点击下方按钮实测跳过效果。"
        }

        return ConfigAuditReport(
            passedCount = passed,
            totalCount = total,
            headline = headline,
            summary = summary,
            canRunLiveTest = canRunLiveTest,
            items = items
        )
    }
}
