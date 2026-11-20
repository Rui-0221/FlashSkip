package com.flashskip

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.ImageViewCompat
import com.flashskip.core.PackagePolicy
import com.flashskip.core.SkipStateMachine
import com.flashskip.data.SkipLog
import com.flashskip.data.StatsStore
import com.flashskip.keepalive.KeepAliveStatus
import com.flashskip.keepalive.PermissionHelper
import com.flashskip.keepalive.SettingsDestination
import com.flashskip.ui.ConsoleFormatter
import com.flashskip.ui.EfButton
import com.flashskip.ui.EfCutDrawable
import com.flashskip.ui.EfDialogs
import com.flashskip.ui.EfHatchView
import com.flashskip.ui.EfSegmentBarView
import com.flashskip.ui.EfTypeface
import com.flashskip.ui.FlashTheme

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statsStore: StatsStore

    private lateinit var rootContainer: FrameLayout
    private lateinit var ivContourTopRight: ImageView
    private lateinit var ivContourBottomLeft: ImageView
    private lateinit var mainScroll: ScrollView
    private lateinit var commandBar: View
    private lateinit var ivBrandSeal: View
    private lateinit var vEndfieldMark: View
    private lateinit var tvCommandBrand: TextView
    private lateinit var tvCommandVer: TextView
    private lateinit var tvCmdStatus: TextView
    private lateinit var btnThemeQuick: TextView
    private lateinit var headerHairline: View
    private lateinit var triStripContainer: View
    private lateinit var triStripA: View
    private lateinit var triStripB: View
    private lateinit var triStripC: View
    private lateinit var tabNavBar: View
    private lateinit var navDivider: View

    // Endfield Header / Eyebrow Views
    private lateinit var efHomeDisplayHeader: View
    private lateinit var efHomeEyebrowTop: TextView
    private lateinit var efHomeWordmark: TextView
    private lateinit var efHomeSubheadline: TextView
    private lateinit var efEyebrowHero: TextView
    private lateinit var efEyebrowControls: TextView
    private lateinit var efEyebrowHomeKa: TextView
    private lateinit var efEyebrowStats: TextView
    private lateinit var efEyebrowKaPage: TextView
    private lateinit var efEyebrowKaGuide: TextView
    private lateinit var efEyebrowMorePage: TextView
    private lateinit var efEyebrowExclusions: TextView
    private lateinit var efEyebrowStyle: TextView
    private lateinit var efEyebrowPrivacy: TextView
    private lateinit var efEyebrowAdvanced: TextView

    // Home Tab Views
    private lateinit var serviceStatusPanel: View
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvKeepAliveBadge: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvHeroDesc: TextView
    private lateinit var noticeActionBanner: LinearLayout
    private lateinit var vNoticeAccent: View
    private lateinit var tvNoticeFlag: TextView
    private lateinit var tvNextStepHint: TextView
    private lateinit var btnEnable: EfButton
    private lateinit var btnHomeSecondary: EfButton

    private lateinit var cardHomeControls: View
    private lateinit var ctrlDivider: View
    private lateinit var swMaster: SwitchCompat
    private lateinit var tvMasterSummary: TextView
    private lateinit var swSkipToast: SwitchCompat
    private lateinit var tvSkipToastDesc: TextView

    private lateinit var homeKeepAliveCard: View
    private lateinit var tvHomeKeepAliveTitle: TextView
    private lateinit var tvReadinessText: TextView
    private lateinit var segReadiness: EfSegmentBarView
    private lateinit var tvHomeKeepAliveSummary: TextView
    private lateinit var tvHomeCheckA11y: TextView
    private lateinit var tvHomeCheckNotif: TextView
    private lateinit var tvHomeCheckBattery: TextView
    private lateinit var tvHomeCheckBg: TextView
    private lateinit var btnGoKeepAlive: EfButton

    private lateinit var telemetryPanel: View
    private lateinit var tvStatsCardTitle: TextView
    private lateinit var tvStatsCardPrivate: TextView
    private lateinit var emptyStatsBox: View
    private lateinit var hatchEmptyStats: EfHatchView
    private lateinit var tvEmptyStatsText: TextView
    private lateinit var tvStatLegacyEmpty: TextView
    private lateinit var homeStatsTiles: View
    private lateinit var statTodayTile: View
    private lateinit var statTotalTile: View
    private lateinit var tvStatToday: TextView
    private lateinit var tvStatTodayLabel: TextView
    private lateinit var tvStatTotal: TextView
    private lateinit var tvStatTotalLabel: TextView
    private lateinit var btnViewDetailedStats: TextView

    // Keep-Alive Tab Views
    private lateinit var tvTitleKeepAlive: TextView
    private lateinit var tvDescKeepAlive: TextView
    private lateinit var cardKaChecklist: View
    private lateinit var kaDivider1: View
    private lateinit var kaDivider2: View
    private lateinit var kaDivider3: View
    private lateinit var tvKaItem1Title: TextView
    private lateinit var tvKaItem2Title: TextView
    private lateinit var tvKaItem3Title: TextView
    private lateinit var tvKaItem4Title: TextView
    private lateinit var tvKaA11yBadge: TextView
    private lateinit var tvKaA11yDesc: TextView
    private lateinit var btnKaAccessibility: EfButton
    private lateinit var btnRestrictedSettings: EfButton
    private lateinit var tvKaNotifBadge: TextView
    private lateinit var tvKaNotifDesc: TextView
    private lateinit var btnKaNotification: EfButton
    private lateinit var tvKaBatteryBadge: TextView
    private lateinit var tvKaBatteryDesc: TextView
    private lateinit var btnBatteryOpt: EfButton
    private lateinit var tvKaBgBadge: TextView
    private lateinit var tvKaBgDesc: TextView
    private lateinit var btnOemBackground: EfButton
    private lateinit var kaJumpFeedbackBanner: LinearLayout
    private lateinit var vKaFeedbackBar: View
    private lateinit var tvKaFeedbackFlag: TextView
    private lateinit var tvKaJumpFeedback: TextView
    private lateinit var btnKaAppDetails: EfButton
    private lateinit var tvKeepAliveBrandTitle: TextView
    private lateinit var tvKeepAliveGuideDesc: TextView
    private lateinit var keepAliveStepsContainer: LinearLayout
    private lateinit var keepAliveGuideCard: View
    private lateinit var tvKeepAliveInfo: TextView
    private lateinit var btnBrowseOtherBrands: TextView

    // More / Settings & Lower-Tier Tools Tab Views
    private lateinit var tvTitleMore: TextView
    private lateinit var sectionExclusions: View
    private lateinit var tvExclusionsCardTitle: TextView
    private lateinit var tvBuiltInExclusions: TextView
    private lateinit var exclDivider: View
    private lateinit var tvCustomExclusionsHeader: TextView
    private lateinit var emptyExclusionsBox: View
    private lateinit var hatchEmptyExclusions: EfHatchView
    private lateinit var tvEmptyExclusionsText: TextView
    private lateinit var customExclusionsList: LinearLayout
    private lateinit var cardAppearance: View
    private lateinit var tvAppearanceTitle: TextView
    private lateinit var tvAppearanceDesc: TextView
    private lateinit var tvThemeStyleLabel: TextView
    private lateinit var segStyle: LinearLayout
    private lateinit var segStyleClassic: TextView
    private lateinit var segStyleEndfield: TextView
    private lateinit var tvStyleActiveDesc: TextView
    private lateinit var tvThemeModeLabel: TextView
    private lateinit var segTheme: LinearLayout
    private lateinit var segThemeSystem: TextView
    private lateinit var segThemeLight: TextView
    private lateinit var segThemeDark: TextView
    private lateinit var cardPrivacySafety: View
    private lateinit var tvPrivacyTitle: TextView
    private lateinit var tvSafetyGuide: TextView

    private lateinit var advancedToolsCard: View
    private lateinit var btnToggleAdvancedTools: View
    private lateinit var tvAdvancedTitle: TextView
    private lateinit var tvAdvancedSub: TextView
    private lateinit var tvAdvancedToolsArrow: TextView
    private lateinit var advancedToolsContainer: View
    private lateinit var sectionTestLab: View
    private lateinit var btnToggleTestLabList: TextView
    private lateinit var testLabListContainer: LinearLayout
    private lateinit var tvWindow: TextView
    private lateinit var segWindowBar: EfSegmentBarView
    private lateinit var sbWindow: SeekBar
    private lateinit var btnWindowMinus: EfButton
    private lateinit var btnWindowPlus: EfButton
    private lateinit var tvTelemetryHeaderText: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvStatSampleCount: TextView
    private lateinit var telemetryDataGroup: View
    private lateinit var tvStatP50: TextView
    private lateinit var tvStatP95: TextView
    private lateinit var tvStatStillPresent: TextView
    private lateinit var tvStatNavigated: TextView
    private lateinit var tvStatUnobserved: TextView
    private lateinit var tvStatLegacy: TextView
    private lateinit var swObserve: SwitchCompat
    private lateinit var swDiag: SwitchCompat
    private lateinit var logContainerBox: View
    private lateinit var sectionLogs: View
    private lateinit var tvLog: TextView
    private lateinit var tvLogMeta: TextView
    private lateinit var emptyLogBox: View
    private lateinit var hatchEmptyLog: EfHatchView
    private lateinit var tvEmptyLogText: TextView

    private data class TabSlot(
        val container: View,
        val icon: ImageView,
        val enLabel: TextView,
        val zhLabel: TextView
    )

    private lateinit var tabPanels: List<View>
    private lateinit var tabViews: List<TabSlot>

    private var activeTab = 0
    private var advancedToolsExpanded = false
    private var testLabListExpanded = false
    private var syncingSwitches = false
    private var notifPromptedOnce = false
    private var hasTriedOemJump = false
    private var lastOemDestination: SettingsDestination? = null
    private var currentPrimaryAction = ConsoleFormatter.HomePrimaryAction.OPEN_ACCESSIBILITY
    private var lastRenderedLogVersion = -1
    private var lastRenderedExclusions: Set<String> = emptySet()

    companion object {
        private const val KEY_ACTIVE_TAB = "active_tab"
        private const val KEY_ADV_EXPANDED = "adv_expanded"
        private const val KEY_LAB_EXPANDED = "lab_expanded"
        private const val EXTRA_LAUNCH_SCENARIO = "launch_scenario"
        private const val EXTRA_OPEN_TAB = "open_tab"
        private const val EXTRA_THEME_STYLE = "theme_style"
        private const val REQ_POST_NOTIFICATIONS = 101
    }

    private val refresh = object : Runnable {
        override fun run() {
            refreshUi(forceLog = false)
            handler.postDelayed(this, 2000)
        }
    }

    private val reconnectCheck = Runnable {
        refreshUi(forceLog = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        statsStore = StatsStore(this)
        AppCompatDelegate.setDefaultNightMode(statsStore.uiThemeMode)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        activeTab = savedInstanceState?.getInt(KEY_ACTIVE_TAB, 0)?.coerceIn(0, 2) ?: 0
        advancedToolsExpanded = savedInstanceState?.getBoolean(KEY_ADV_EXPANDED, false) ?: false
        testLabListExpanded = savedInstanceState?.getBoolean(KEY_LAB_EXPANDED, false) ?: false

        bindViews()
        applyEdgeToEdgeInsets()
        applyTypographyTokens()
        applyVisualTheme()
        setupControls()
        buildKeepAliveGuideRows()
        buildTestLabRows()
        syncExpandedPanels()
        selectTab(activeTab, scrollToTop = false)

        handleIntentExtras(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_ACTIVE_TAB, activeTab)
        outState.putBoolean(KEY_ADV_EXPANDED, advancedToolsExpanded)
        outState.putBoolean(KEY_LAB_EXPANDED, testLabListExpanded)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentExtras(intent)
    }

    private fun handleIntentExtras(intent: Intent?) {
        val styleArg = intent?.getStringExtra(EXTRA_THEME_STYLE)
        if (!styleArg.isNullOrBlank()) {
            intent.removeExtra(EXTRA_THEME_STYLE)
            val requested = FlashTheme.ThemeStyle.fromKey(styleArg)
            if (statsStore.uiThemeStyle != requested.key) {
                statsStore.uiThemeStyle = requested.key
                recreate()
                return
            }
        }

        val tabArg = intent?.getStringExtra(EXTRA_OPEN_TAB)
        if (!tabArg.isNullOrBlank()) {
            intent.removeExtra(EXTRA_OPEN_TAB)
            when (tabArg.lowercase()) {
                "home", "0" -> selectTab(0, scrollToTop = true)
                "keepalive", "guide", "1" -> selectTab(1, scrollToTop = true)
                "more", "about" -> selectTab(2, scrollToTop = true)
                "appearance", "theme" -> {
                    selectTab(2, scrollToTop = false)
                    scrollToSection(cardAppearance)
                }
                "policy" -> {
                    selectTab(2, scrollToTop = false)
                    scrollToSection(sectionExclusions)
                }
                "testlab", "tests", "2" -> {
                    advancedToolsExpanded = true
                    testLabListExpanded = true
                    syncExpandedPanels()
                    selectTab(2, scrollToTop = false)
                    scrollToSection(sectionTestLab)
                }
                "logs", "3" -> {
                    advancedToolsExpanded = true
                    syncExpandedPanels()
                    selectTab(2, scrollToTop = false)
                    scrollToSection(sectionLogs)
                }
            }
        }

        val scenario = intent?.getStringExtra(EXTRA_LAUNCH_SCENARIO) ?: return
        val requestId = TestSplashActivity.requestIdFrom(intent)
        intent.removeExtra(EXTRA_LAUNCH_SCENARIO)
        intent.removeExtra(TestSplashActivity.EXTRA_REQUEST_ID)
        requestId?.let { SkipLog.log("LAB_REQUEST request=$it stage=received atMs=${SystemClock.uptimeMillis()}") }
        handler.postDelayed({ startTestScenario(scenario, requestId) }, 150)
    }

    private fun scrollToSection(target: View) {
        mainScroll.post {
            val contentRoot = mainScroll.getChildAt(0) as? ViewGroup
            val targetY = if (contentRoot != null) {
                runCatching {
                    val rect = android.graphics.Rect(0, 0, target.width, target.height)
                    contentRoot.offsetDescendantRectToMyCoords(target, rect)
                    rect.top
                }.getOrElse { target.top }
            } else {
                target.top
            }
            mainScroll.smoothScrollTo(0, targetY.coerceAtLeast(0))
        }
    }

    private fun bindViews() {
        rootContainer = findViewById(R.id.rootContainer)
        ivContourTopRight = findViewById(R.id.ivContourTopRight)
        ivContourBottomLeft = findViewById(R.id.ivContourBottomLeft)
        mainScroll = findViewById(R.id.mainScroll)
        commandBar = findViewById(R.id.commandBar)
        ivBrandSeal = findViewById(R.id.ivBrandSeal)
        vEndfieldMark = findViewById(R.id.vEndfieldMark)
        tvCommandBrand = findViewById(R.id.tvCommandBrand)
        tvCommandVer = findViewById(R.id.tvCommandVer)
        tvCmdStatus = findViewById(R.id.tvCmdStatus)
        btnThemeQuick = findViewById(R.id.btnThemeQuick)
        headerHairline = findViewById(R.id.headerHairline)
        triStripContainer = findViewById(R.id.triStripContainer)
        triStripA = findViewById(R.id.triStripA)
        triStripB = findViewById(R.id.triStripB)
        triStripC = findViewById(R.id.triStripC)
        tabNavBar = findViewById(R.id.tabNavBar)
        navDivider = findViewById(R.id.navDivider)

        efHomeDisplayHeader = findViewById(R.id.efHomeDisplayHeader)
        efHomeEyebrowTop = findViewById(R.id.efHomeEyebrowTop)
        efHomeWordmark = findViewById(R.id.efHomeWordmark)
        efHomeSubheadline = findViewById(R.id.efHomeSubheadline)
        efEyebrowHero = findViewById(R.id.efEyebrowHero)
        efEyebrowControls = findViewById(R.id.efEyebrowControls)
        efEyebrowHomeKa = findViewById(R.id.efEyebrowHomeKa)
        efEyebrowStats = findViewById(R.id.efEyebrowStats)
        efEyebrowKaPage = findViewById(R.id.efEyebrowKaPage)
        efEyebrowKaGuide = findViewById(R.id.efEyebrowKaGuide)
        efEyebrowMorePage = findViewById(R.id.efEyebrowMorePage)
        efEyebrowExclusions = findViewById(R.id.efEyebrowExclusions)
        efEyebrowStyle = findViewById(R.id.efEyebrowStyle)
        efEyebrowPrivacy = findViewById(R.id.efEyebrowPrivacy)
        efEyebrowAdvanced = findViewById(R.id.efEyebrowAdvanced)

        serviceStatusPanel = findViewById(R.id.serviceStatusPanel)
        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        tvKeepAliveBadge = findViewById(R.id.tvKeepAliveBadge)
        tvStatus = findViewById(R.id.tvStatus)
        tvHeroDesc = findViewById(R.id.tvHeroDesc)
        noticeActionBanner = findViewById(R.id.noticeActionBanner)
        vNoticeAccent = findViewById(R.id.vNoticeAccent)
        tvNoticeFlag = findViewById(R.id.tvNoticeFlag)
        tvNextStepHint = findViewById(R.id.tvNextStepHint)
        btnEnable = findViewById(R.id.btnEnable)
        btnHomeSecondary = findViewById(R.id.btnHomeSecondary)

        cardHomeControls = findViewById(R.id.cardHomeControls)
        ctrlDivider = findViewById(R.id.ctrlDivider)
        swMaster = findViewById(R.id.swMaster)
        tvMasterSummary = findViewById(R.id.tvMasterSummary)
        swSkipToast = findViewById(R.id.swSkipToast)
        tvSkipToastDesc = findViewById(R.id.tvSkipToastDesc)

        homeKeepAliveCard = findViewById(R.id.homeKeepAliveCard)
        tvHomeKeepAliveTitle = findViewById(R.id.tvHomeKeepAliveTitle)
        tvReadinessText = findViewById(R.id.tvReadinessText)
        segReadiness = findViewById(R.id.segReadiness)
        tvHomeKeepAliveSummary = findViewById(R.id.tvHomeKeepAliveSummary)
        tvHomeCheckA11y = findViewById(R.id.tvHomeCheckA11y)
        tvHomeCheckNotif = findViewById(R.id.tvHomeCheckNotif)
        tvHomeCheckBattery = findViewById(R.id.tvHomeCheckBattery)
        tvHomeCheckBg = findViewById(R.id.tvHomeCheckBg)
        btnGoKeepAlive = findViewById(R.id.btnGoKeepAlive)

        telemetryPanel = findViewById(R.id.telemetryPanel)
        tvStatsCardTitle = findViewById(R.id.tvStatsCardTitle)
        tvStatsCardPrivate = findViewById(R.id.tvStatsCardPrivate)
        emptyStatsBox = findViewById(R.id.emptyStatsBox)
        hatchEmptyStats = findViewById(R.id.hatchEmptyStats)
        tvEmptyStatsText = findViewById(R.id.tvEmptyStatsText)
        tvStatLegacyEmpty = findViewById(R.id.tvStatLegacyEmpty)
        homeStatsTiles = findViewById(R.id.homeStatsTiles)
        statTodayTile = findViewById(R.id.statTodayTile)
        statTotalTile = findViewById(R.id.statTotalTile)
        tvStatToday = findViewById(R.id.tvStatToday)
        tvStatTodayLabel = findViewById(R.id.tvStatTodayLabel)
        tvStatTotal = findViewById(R.id.tvStatTotal)
        tvStatTotalLabel = findViewById(R.id.tvStatTotalLabel)
        btnViewDetailedStats = findViewById(R.id.btnViewDetailedStats)

        tvTitleKeepAlive = findViewById(R.id.tvTitleKeepAlive)
        tvDescKeepAlive = findViewById(R.id.tvDescKeepAlive)
        cardKaChecklist = findViewById(R.id.cardKaChecklist)
        kaDivider1 = findViewById(R.id.kaDivider1)
        kaDivider2 = findViewById(R.id.kaDivider2)
        kaDivider3 = findViewById(R.id.kaDivider3)
        tvKaItem1Title = findViewById(R.id.tvKaItem1Title)
        tvKaItem2Title = findViewById(R.id.tvKaItem2Title)
        tvKaItem3Title = findViewById(R.id.tvKaItem3Title)
        tvKaItem4Title = findViewById(R.id.tvKaItem4Title)
        tvKaA11yBadge = findViewById(R.id.tvKaA11yBadge)
        tvKaA11yDesc = findViewById(R.id.tvKaA11yDesc)
        btnKaAccessibility = findViewById(R.id.btnKaAccessibility)
        btnRestrictedSettings = findViewById(R.id.btnRestrictedSettings)
        tvKaNotifBadge = findViewById(R.id.tvKaNotifBadge)
        tvKaNotifDesc = findViewById(R.id.tvKaNotifDesc)
        btnKaNotification = findViewById(R.id.btnKaNotification)
        tvKaBatteryBadge = findViewById(R.id.tvKaBatteryBadge)
        tvKaBatteryDesc = findViewById(R.id.tvKaBatteryDesc)
        btnBatteryOpt = findViewById(R.id.btnBatteryOpt)
        tvKaBgBadge = findViewById(R.id.tvKaBgBadge)
        tvKaBgDesc = findViewById(R.id.tvKaBgDesc)
        btnOemBackground = findViewById(R.id.btnOemBackground)
        kaJumpFeedbackBanner = findViewById(R.id.kaJumpFeedbackBanner)
        vKaFeedbackBar = findViewById(R.id.vKaFeedbackBar)
        tvKaFeedbackFlag = findViewById(R.id.tvKaFeedbackFlag)
        tvKaJumpFeedback = findViewById(R.id.tvKaJumpFeedback)
        btnKaAppDetails = findViewById(R.id.btnKaAppDetails)
        tvKeepAliveBrandTitle = findViewById(R.id.tvKeepAliveBrandTitle)
        tvKeepAliveGuideDesc = findViewById(R.id.tvKeepAliveGuideDesc)
        keepAliveStepsContainer = findViewById(R.id.keepAliveStepsContainer)
        keepAliveGuideCard = findViewById(R.id.keepAliveGuideCard)
        tvKeepAliveInfo = findViewById(R.id.tvKeepAliveInfo)
        btnBrowseOtherBrands = findViewById(R.id.btnBrowseOtherBrands)

        tvTitleMore = findViewById(R.id.tvTitleMore)
        sectionExclusions = findViewById(R.id.sectionExclusions)
        tvExclusionsCardTitle = findViewById(R.id.tvExclusionsCardTitle)
        tvBuiltInExclusions = findViewById(R.id.tvBuiltInExclusions)
        exclDivider = findViewById(R.id.exclDivider)
        tvCustomExclusionsHeader = findViewById(R.id.tvCustomExclusionsHeader)
        emptyExclusionsBox = findViewById(R.id.emptyExclusionsBox)
        hatchEmptyExclusions = findViewById(R.id.hatchEmptyExclusions)
        tvEmptyExclusionsText = findViewById(R.id.tvEmptyExclusionsText)
        customExclusionsList = findViewById(R.id.customExclusionsList)
        cardAppearance = findViewById(R.id.cardAppearance)
        tvAppearanceTitle = findViewById(R.id.tvAppearanceTitle)
        tvAppearanceDesc = findViewById(R.id.tvAppearanceDesc)
        tvThemeStyleLabel = findViewById(R.id.tvThemeStyleLabel)
        segStyle = findViewById(R.id.segStyle)
        segStyleClassic = findViewById(R.id.segStyleClassic)
        segStyleEndfield = findViewById(R.id.segStyleEndfield)
        tvStyleActiveDesc = findViewById(R.id.tvStyleActiveDesc)
        tvThemeModeLabel = findViewById(R.id.tvThemeModeLabel)
        segTheme = findViewById(R.id.segTheme)
        segThemeSystem = findViewById(R.id.segThemeSystem)
        segThemeLight = findViewById(R.id.segThemeLight)
        segThemeDark = findViewById(R.id.segThemeDark)
        cardPrivacySafety = findViewById(R.id.cardPrivacySafety)
        tvPrivacyTitle = findViewById(R.id.tvPrivacyTitle)
        tvSafetyGuide = findViewById(R.id.tvSafetyGuide)

        advancedToolsCard = findViewById(R.id.advancedToolsCard)
        btnToggleAdvancedTools = findViewById(R.id.btnToggleAdvancedTools)
        tvAdvancedTitle = findViewById(R.id.tvAdvancedTitle)
        tvAdvancedSub = findViewById(R.id.tvAdvancedSub)
        tvAdvancedToolsArrow = findViewById(R.id.tvAdvancedToolsArrow)
        advancedToolsContainer = findViewById(R.id.advancedToolsContainer)
        sectionTestLab = findViewById(R.id.sectionTestLab)
        btnToggleTestLabList = findViewById(R.id.btnToggleTestLabList)
        testLabListContainer = findViewById(R.id.testLabListContainer)
        tvWindow = findViewById(R.id.tvWindow)
        segWindowBar = findViewById(R.id.segWindowBar)
        sbWindow = findViewById(R.id.sbWindow)
        btnWindowMinus = findViewById(R.id.btnWindowMinus)
        btnWindowPlus = findViewById(R.id.btnWindowPlus)
        tvTelemetryHeaderText = findViewById(R.id.tvTelemetryHeaderText)
        tvStats = findViewById(R.id.tvStats)
        tvStatSampleCount = findViewById(R.id.tvStatSampleCount)
        telemetryDataGroup = findViewById(R.id.telemetryDataGroup)
        tvStatP50 = findViewById(R.id.tvStatP50)
        tvStatP95 = findViewById(R.id.tvStatP95)
        tvStatStillPresent = findViewById(R.id.tvStatStillPresent)
        tvStatNavigated = findViewById(R.id.tvStatNavigated)
        tvStatUnobserved = findViewById(R.id.tvStatUnobserved)
        tvStatLegacy = findViewById(R.id.tvStatLegacy)
        swObserve = findViewById(R.id.swObserve)
        swDiag = findViewById(R.id.swDiag)
        logContainerBox = findViewById(R.id.logContainerBox)
        sectionLogs = findViewById(R.id.sectionLogs)
        tvLog = findViewById(R.id.tvLog)
        tvLogMeta = findViewById(R.id.tvLogMeta)
        emptyLogBox = findViewById(R.id.emptyLogBox)
        hatchEmptyLog = findViewById(R.id.hatchEmptyLog)
        tvEmptyLogText = findViewById(R.id.tvEmptyLogText)

        tabPanels = listOf(
            findViewById(R.id.panelTabHome),
            findViewById(R.id.panelTabKeepAlive),
            findViewById(R.id.panelTabMore)
        )
        tabViews = listOf(
            TabSlot(findViewById(R.id.tabHome), findViewById(R.id.tabHomeIcon), findViewById(R.id.tabHomeEn), findViewById(R.id.tabHomeZh)),
            TabSlot(findViewById(R.id.tabKeepAlive), findViewById(R.id.tabKeepAliveIcon), findViewById(R.id.tabKeepAliveEn), findViewById(R.id.tabKeepAliveZh)),
            TabSlot(findViewById(R.id.tabMore), findViewById(R.id.tabMoreIcon), findViewById(R.id.tabMoreEn), findViewById(R.id.tabMoreZh))
        )
    }

    private fun applyEdgeToEdgeInsets() {
        val cmdBaseTop = commandBar.paddingTop
        val navBaseBottom = tabNavBar.paddingBottom
        val isWideRail = resources.configuration.smallestScreenWidthDp >= 600 ||
            resources.configuration.screenWidthDp >= 600

        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            commandBar.setPadding(
                commandBar.paddingLeft,
                cmdBaseTop + bars.top,
                commandBar.paddingRight,
                commandBar.paddingBottom
            )
            if (!isWideRail) {
                tabNavBar.setPadding(
                    tabNavBar.paddingLeft,
                    tabNavBar.paddingTop,
                    tabNavBar.paddingRight,
                    navBaseBottom + bars.bottom
                )
            } else {
                mainScroll.setPadding(
                    mainScroll.paddingLeft,
                    mainScroll.paddingTop,
                    mainScroll.paddingRight,
                    bars.bottom
                )
            }
            insets
        }

        val endfield = FlashTheme.isEndfield(this)
        val palette = FlashTheme.palette(this)
        window.statusBarColor = palette.commandBar
        window.navigationBarColor = palette.panel
        WindowInsetsControllerCompat(window, window.decorView).apply {
            // Endfield command bar is always #161616 dark, so status bar icons remain light even in Light paper mode
            isAppearanceLightStatusBars = if (endfield) false else !palette.isDark
            isAppearanceLightNavigationBars = !palette.isDark
        }
    }

    private fun applyTypographyTokens() {
        val endfield = FlashTheme.isEndfield(this)
        if (endfield) {
            EfTypeface.applyFigure(tvStatToday)
            EfTypeface.applyFigure(tvStatTotal)
            EfTypeface.applyField(tvReadinessText, bold = true)
            EfTypeface.applyWordmark(efHomeWordmark)
            EfTypeface.applyEyebrow(efHomeEyebrowTop)
            listOf(
                efEyebrowHero, efEyebrowControls, efEyebrowHomeKa, efEyebrowStats,
                efEyebrowKaPage, efEyebrowKaGuide, efEyebrowMorePage, efEyebrowExclusions,
                efEyebrowStyle, efEyebrowPrivacy, efEyebrowAdvanced
            ).forEach { EfTypeface.applyEyebrow(it) }
            listOf(tvStatSampleCount, tvStatP50, tvStatP95, tvStatStillPresent, tvStatNavigated, tvStatUnobserved, tvLogMeta)
                .forEach { EfTypeface.applyField(it) }
        } else {
            EfTypeface.applyFigure(tvStatToday)
            EfTypeface.applyFigure(tvStatTotal)
            listOf(
                tvStatSampleCount, tvStatP50, tvStatP95, tvStatStillPresent,
                tvStatNavigated, tvStatUnobserved, tvLogMeta
            ).forEach { it.fontFeatureSettings = "tnum" }
        }
    }

    private fun applyVisualTheme() {
        val endfield = FlashTheme.isEndfield(this)
        val p = FlashTheme.palette(this)
        val density = resources.displayMetrics.density
        val hairlinePx = (1f * density).toInt().coerceAtLeast(1)

        rootContainer.setBackgroundColor(p.paper)

        // Endfield open contours backdrop (9% ink alpha in opposite corners)
        val contourTint = Color.argb((255 * 0.09f).toInt(), Color.red(p.ink), Color.green(p.ink), Color.blue(p.ink))
        listOf(ivContourTopRight, ivContourBottomLeft).forEach { iv ->
            iv.visibility = if (endfield) View.VISIBLE else View.GONE
            ImageViewCompat.setImageTintList(iv, ColorStateList.valueOf(contourTint))
        }

        // Top Command Bar / Header
        commandBar.setBackgroundColor(p.commandBar)
        ivBrandSeal.visibility = if (endfield) View.GONE else View.VISIBLE
        vEndfieldMark.visibility = if (endfield) View.VISIBLE else View.GONE
        vEndfieldMark.setBackgroundColor(p.signal)
        tvCommandBrand.text = if (endfield) "FLASHSKIP" else getString(R.string.app_display_name)
        tvCommandBrand.setTextColor(p.commandBarInk)
        if (endfield) {
            EfTypeface.applyWordmark(tvCommandBrand)
            tvCommandBrand.letterSpacing = 0.06f
            tvCommandVer.text = "LOCAL │ OFFLINE"
            EfTypeface.applyField(tvCommandVer)
            tvCommandVer.isAllCaps = true
        } else {
            tvCommandBrand.typeface = Typeface.DEFAULT_BOLD
            tvCommandBrand.letterSpacing = 0f
            tvCommandVer.text = "不联网 · 本地运行"
            tvCommandVer.typeface = Typeface.DEFAULT
            tvCommandVer.isAllCaps = false
        }
        tvCommandVer.setTextColor(p.commandBarMuted)

        headerHairline.visibility = if (endfield) View.GONE else View.VISIBLE
        headerHairline.setBackgroundColor(p.rule)
        triStripContainer.visibility = if (endfield) View.VISIBLE else View.GONE
        triStripA.setBackgroundColor(p.triA)
        triStripB.setBackgroundColor(p.triB)
        triStripC.setBackgroundColor(p.triC)

        val focusStroke = resources.getDimensionPixelSize(R.dimen.ef_focus_stroke).coerceAtLeast(2)
        btnThemeQuick.setTextColor(p.commandBarInk)
        btnThemeQuick.background = EfCutDrawable.buildInsetFocusSelector(
            fillColor = p.commandBarChip,
            focusStrokeColor = if (endfield) p.signal else p.trace,
            strokeWidthPx = focusStroke,
            insetPx = 0,
            cornerRadiusPx = if (endfield) 0f else 999f * density,
            cutBottomRightPx = if (endfield) 7f * density else 0f
        )
        if (endfield) EfTypeface.applyField(btnThemeQuick, bold = true)

        // Endfield hero & // eyebrows
        efHomeDisplayHeader.visibility = if (endfield) View.VISIBLE else View.GONE
        efHomeEyebrowTop.setTextColor(p.ink2)
        efHomeWordmark.setTextColor(p.ink)
        efHomeSubheadline.setTextColor(p.ink)

        val eyebrows = listOf(
            efEyebrowHero, efEyebrowControls, efEyebrowHomeKa, efEyebrowStats,
            efEyebrowKaPage, efEyebrowKaGuide, efEyebrowMorePage, efEyebrowExclusions,
            efEyebrowStyle, efEyebrowPrivacy, efEyebrowAdvanced
        )
        eyebrows.forEach { tv ->
            tv.visibility = if (endfield) View.VISIBLE else View.GONE
            tv.setTextColor(p.ink2)
        }

        // Dashboard Cards / Panels (Endfield: 0dp sharp top-tab + 26dp bottom-right cut, 1dp rule)
        val mainPanels = listOf(
            serviceStatusPanel, cardHomeControls, homeKeepAliveCard, telemetryPanel,
            cardKaChecklist, keepAliveGuideCard, sectionExclusions, cardAppearance,
            cardPrivacySafety, advancedToolsCard
        )
        mainPanels.forEach { panel ->
            if (endfield) {
                panel.background = EfCutDrawable.buildEndfieldPanel(
                    fillColor = p.panel,
                    ruleColor = p.rule,
                    tabColor = p.ink,
                    strokeWidthPx = hairlinePx.toFloat(),
                    cutBottomRightPx = 26f * density,
                    tabWidthPx = (56f * density).toInt(),
                    tabHeightPx = (5f * density).toInt()
                )
            } else {
                panel.setBackgroundResource(R.drawable.ef_panel_bg)
            }
        }
        // Keep top-edge padding clear of the 5dp dark tab & bottom-right padding clear of 26dp cut
        if (endfield) {
            val pad18 = (18f * density).toInt()
            val padTop22 = (22f * density).toInt()
            val padBottom24 = (24f * density).toInt()
            listOf(
                serviceStatusPanel, homeKeepAliveCard, telemetryPanel, cardKaChecklist,
                keepAliveGuideCard, sectionExclusions, cardAppearance, cardPrivacySafety
            ).forEach { it.setPadding(pad18, padTop22, pad18, padBottom24) }
        }

        // Primary & Secondary text colors across layouts
        listOf(
            tvStatus, tvHomeKeepAliveTitle, tvReadinessText, tvStatsCardTitle,
            tvStatToday, tvStatTotal, tvTitleKeepAlive, tvKaItem1Title, tvKaItem2Title,
            tvKaItem3Title, tvKaItem4Title, tvKeepAliveBrandTitle, tvTitleMore,
            tvExclusionsCardTitle, tvCustomExclusionsHeader, tvAppearanceTitle,
            tvThemeStyleLabel, tvThemeModeLabel, tvPrivacyTitle, tvAdvancedTitle,
            tvWindow, tvTelemetryHeaderText, tvStatP50, tvStatP95, tvStatStillPresent,
            tvStatUnobserved, tvLog
        ).forEach { it.setTextColor(p.ink) }

        listOf(
            tvHeroDesc, tvMasterSummary, tvSkipToastDesc, tvHomeKeepAliveSummary,
            tvStatsCardPrivate, tvStatTodayLabel, tvStatTotalLabel, tvDescKeepAlive,
            tvKaA11yDesc, tvKaNotifDesc, tvKaBatteryDesc, tvKaBgDesc, tvKeepAliveGuideDesc,
            tvKeepAliveInfo, tvBuiltInExclusions, tvAppearanceDesc, tvStyleActiveDesc,
            tvSafetyGuide, tvAdvancedSub, tvStatSampleCount, tvStats, tvLogMeta
        ).forEach { it.setTextColor(p.ink2) }

        // Hairline rules
        listOf(ctrlDivider, kaDivider1, kaDivider2, kaDivider3, exclDivider)
            .forEach { it.setBackgroundColor(p.rule) }

        // Notice & feedback banners (Endfield = 28dp yellow ! square block, Classic = 4dp vertical bar)
        applyBannerTheme(noticeActionBanner, vNoticeAccent, tvNoticeFlag, tvNextStepHint, p, endfield, density, hairlinePx)
        applyBannerTheme(kaJumpFeedbackBanner, vKaFeedbackBar, tvKaFeedbackFlag, tvKaJumpFeedback, p, endfield, density, hairlinePx)

        // Subtle inner tiles (0dp in Endfield, rounded in Classic)
        val innerTiles = listOf(statTodayTile, statTotalTile, telemetryDataGroup, logContainerBox)
        innerTiles.forEach { v ->
            if (endfield) {
                v.background = EfCutDrawable.buildSquareBox(p.panelSubtle, p.rule, hairlinePx)
            } else {
                v.setBackgroundResource(R.drawable.fs_tile_bg)
            }
        }

        // Empty state 45-degree hatch boxes
        applyEmptyHatchTheme(emptyStatsBox, hatchEmptyStats, tvEmptyStatsText, p, endfield, density)
        applyEmptyHatchTheme(emptyExclusionsBox, hatchEmptyExclusions, tvEmptyExclusionsText, p, endfield, density)
        applyEmptyHatchTheme(emptyLogBox, hatchEmptyLog, tvEmptyLogText, p, endfield, density)

        // Contrast-safe action links (NEVER yellow text on light paper!)
        val linkColor = p.linkColor
        listOf(btnViewDetailedStats, btnBrowseOtherBrands, tvAdvancedToolsArrow, btnToggleTestLabList).forEach { link ->
            (link as TextView).setTextColor(linkColor)
            if (endfield && !p.isDark) {
                link.paintFlags = link.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            } else {
                link.paintFlags = link.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
            }
        }

        // Switches & SeekBar
        listOf(swMaster, swSkipToast, swObserve, swDiag).forEach { sw ->
            sw.setTextColor(p.ink)
            if (endfield) {
                sw.trackDrawable = FlashTheme.buildEndfieldSwitchTrack(this, p)
                sw.thumbDrawable = FlashTheme.buildEndfieldSwitchThumb(this, p)
            } else {
                sw.setTrackResource(R.drawable.ef_switch_track)
                sw.setThumbResource(R.drawable.ef_switch_thumb)
            }
        }
        if (endfield) {
            sbWindow.progressDrawable = FlashTheme.buildEndfieldSeekBarProgress(this, p)
            sbWindow.thumb = FlashTheme.buildEndfieldSeekBarThumb(this, p)
        }

        // Refresh all EfButtons to adopt cut corners / rounded corners & theme colors
        refreshEfButtonsInTree(rootContainer)
    }

    private fun applyBannerTheme(
        banner: LinearLayout,
        classicBar: View,
        endfieldFlag: TextView,
        textView: TextView,
        p: FlashTheme.Palette,
        endfield: Boolean,
        density: Float,
        hairlinePx: Int
    ) {
        if (endfield) {
            classicBar.visibility = View.GONE
            endfieldFlag.visibility = View.VISIBLE
            endfieldFlag.setBackgroundColor(p.signal)
            endfieldFlag.setTextColor(p.onSignal)
            EfTypeface.applyField(endfieldFlag, bold = true)
            banner.background = EfCutDrawable.buildSquareBox(p.panel, p.rule, hairlinePx)
            banner.setPadding(0, 0, (12f * density).toInt(), 0)
            val pTextV = (10f * density).toInt()
            textView.setPadding(0, pTextV, 0, pTextV)
        } else {
            classicBar.visibility = View.VISIBLE
            classicBar.setBackgroundColor(p.signal)
            endfieldFlag.visibility = View.GONE
            banner.setBackgroundResource(R.drawable.fs_tile_bg)
            val p12 = (12f * density).toInt()
            banner.setPadding(p12, p12, p12, p12)
            textView.setPadding(0, 0, 0, 0)
        }
        textView.setTextColor(p.ink)
    }

    private fun applyEmptyHatchTheme(
        container: View,
        hatchView: EfHatchView,
        labelView: TextView,
        p: FlashTheme.Palette,
        endfield: Boolean,
        density: Float
    ) {
        if (endfield) {
            container.background = null
            hatchView.visibility = View.VISIBLE
            hatchView.invalidate()
            labelView.setBackgroundColor(p.paper)
            labelView.setTextColor(p.ink)
            val pH = (10f * density).toInt()
            val pV = (6f * density).toInt()
            labelView.setPadding(pH, pV, pH, pV)
        } else {
            hatchView.visibility = View.GONE
            container.setBackgroundResource(R.drawable.fs_tile_bg)
            labelView.setBackgroundColor(Color.TRANSPARENT)
            labelView.setTextColor(p.ink2)
            labelView.setPadding(0, 0, 0, 0)
        }
    }

    private fun refreshEfButtonsInTree(view: View) {
        if (view is EfButton) {
            view.refreshThemeAppearance()
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                refreshEfButtonsInTree(view.getChildAt(i))
            }
        }
    }

    private fun setupControls() {
        syncingSwitches = true
        swMaster.isChecked = statsStore.masterEnabled
        swSkipToast.isChecked = statsStore.skipToastEnabled
        swObserve.isChecked = statsStore.observeOnly
        swDiag.isChecked = statsStore.diagNodes
        syncingSwitches = false

        swMaster.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            statsStore.masterEnabled = checked
            SkipLog.log("SETTING master_enabled=$checked")
            SkipAdService.refreshForegroundProtection()
            refreshUi(forceLog = false)
        }
        swSkipToast.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            statsStore.skipToastEnabled = checked
            SkipLog.log("SETTING skip_toast=$checked")
            showToast(if (checked) "已开启跳过成功轻提示" else "已关闭跳过成功轻提示")
        }
        swObserve.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            statsStore.observeOnly = checked
            SkipLog.log("SETTING observe_only=$checked")
            refreshUi(forceLog = false)
        }
        swDiag.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            statsStore.diagNodes = checked
            SkipLog.log("SETTING diag_nodes=$checked")
        }

        val minSec = (SkipStateMachine.MIN_WINDOW_MS / 1000L).toInt()
        val maxSec = (SkipStateMachine.MAX_WINDOW_MS / 1000L).toInt()
        val initialSec = ConsoleFormatter.windowSeconds(statsStore.windowMs)
        sbWindow.max = maxSec - minSec
        sbWindow.progress = initialSec - minSec
        renderWindowUi(initialSec)

        sbWindow.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                renderWindowUi(progress + minSec)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val sec = (seekBar?.progress ?: (6 - minSec)) + minSec
                persistWindowSeconds(sec)
            }
        })

        btnWindowMinus.setOnClickListener {
            val next = (ConsoleFormatter.windowSeconds(statsStore.windowMs) - 1).coerceIn(minSec, maxSec)
            sbWindow.progress = next - minSec
            persistWindowSeconds(next)
        }
        btnWindowPlus.setOnClickListener {
            val next = (ConsoleFormatter.windowSeconds(statsStore.windowMs) + 1).coerceIn(minSec, maxSec)
            sbWindow.progress = next - minSec
            persistWindowSeconds(next)
        }

        tvCmdStatus.setOnClickListener {
            if (activeTab != 0) selectTab(0, scrollToTop = true)
        }

        btnEnable.setOnClickListener {
            when (currentPrimaryAction) {
                ConsoleFormatter.HomePrimaryAction.OPEN_ACCESSIBILITY -> openAccessibilityWithFeedback()
                ConsoleFormatter.HomePrimaryAction.RESUME_MASTER -> {
                    statsStore.masterEnabled = true
                    SkipLog.log("SETTING master_enabled=true")
                    SkipAdService.refreshForegroundProtection()
                    refreshUi(forceLog = false)
                    showToast("已恢复自动跳过广告")
                }
                ConsoleFormatter.HomePrimaryAction.OPEN_KEEPALIVE -> selectTab(1, scrollToTop = true)
                ConsoleFormatter.HomePrimaryAction.DISABLE_OBSERVE -> {
                    statsStore.observeOnly = false
                    SkipLog.log("SETTING observe_only=false")
                    refreshUi(forceLog = false)
                    showToast("已关闭演练模式，恢复正常自动跳过")
                }
                ConsoleFormatter.HomePrimaryAction.RUN_QUICK_TEST -> {
                    startTestScenario(TestSplashActivity.SCENARIO_STANDARD)
                }
            }
        }

        btnHomeSecondary.setOnClickListener {
            val status = PermissionHelper.readStatus(this)
            when {
                status.state == com.flashskip.keepalive.KeepAliveState.NEEDS_ACCESSIBILITY -> openAppDetailsWithFeedback()
                status.state == com.flashskip.keepalive.KeepAliveState.RUNNING &&
                    status.batteryExempt && !status.backgroundRestricted && status.notificationsVisible &&
                    !statsStore.observeOnly -> selectTab(1, scrollToTop = true)
                status.state == com.flashskip.keepalive.KeepAliveState.RUNNING &&
                    !statsStore.observeOnly -> startTestScenario(TestSplashActivity.SCENARIO_STANDARD)
                else -> selectTab(1, scrollToTop = true)
            }
        }

        btnGoKeepAlive.setOnClickListener {
            selectTab(1, scrollToTop = true)
        }

        btnViewDetailedStats.setOnClickListener {
            advancedToolsExpanded = true
            syncExpandedPanels()
            selectTab(2, scrollToTop = false)
            scrollToSection(advancedToolsContainer)
        }

        // Keep-Alive Tab Buttons
        btnKaAccessibility.setOnClickListener { openAccessibilityWithFeedback() }
        btnRestrictedSettings.setOnClickListener { openAppDetailsWithFeedback() }
        btnKaAppDetails.setOnClickListener { openAppDetailsWithFeedback() }

        btnKaNotification.setOnClickListener {
            handleNotificationSetupClick()
        }

        btnBatteryOpt.setOnClickListener {
            if (!PermissionHelper.openBatteryOptimizationSettings(this)) {
                showToast("未能打开电池设置，请在手机设置中搜索“电池优化”")
            }
        }

        btnOemBackground.setOnClickListener {
            val dest = PermissionHelper.openOemBackgroundSettings(this)
            hasTriedOemJump = true
            lastOemDestination = dest
            renderOemJumpFeedback()
            if (dest == null) {
                showToast("无法自动跳转，请按下方第 1–4 步手动设置")
                scrollToSection(keepAliveGuideCard)
            }
        }

        btnBrowseOtherBrands.setOnClickListener {
            showOtherBrandsPicker()
        }

        // More / Tools Tab Buttons
        btnToggleAdvancedTools.setOnClickListener {
            advancedToolsExpanded = !advancedToolsExpanded
            syncExpandedPanels()
        }
        btnToggleTestLabList.setOnClickListener {
            testLabListExpanded = !testLabListExpanded
            syncExpandedPanels()
        }
        findViewById<View>(R.id.btnTest).setOnClickListener {
            startTestScenario(TestSplashActivity.SCENARIO_STANDARD)
        }
        findViewById<View>(R.id.btnTestLab).setOnClickListener {
            showTestLabPicker()
        }
        findViewById<View>(R.id.btnExclusions).setOnClickListener {
            showExclusionsDialog()
        }
        findViewById<View>(R.id.btnAddExclusionInline).setOnClickListener {
            promptAddExclusion()
        }
        findViewById<View>(R.id.btnRefresh).setOnClickListener {
            refreshUi(forceLog = true)
            showToast("运行记录与状态已刷新")
        }
        findViewById<View>(R.id.btnCopyLog).setOnClickListener {
            copyLogsToClipboard()
        }
        findViewById<View>(R.id.btnShareLog).setOnClickListener {
            shareLogs()
        }
        findViewById<View>(R.id.btnClearLog).setOnClickListener {
            EfDialogs.showConfirmDialog(
                activity = this,
                eyebrow = "",
                title = "清空全部运行记录？",
                message = "将删除应用内保留的运行记录和本地日志文件，清空后无法恢复。",
                confirmText = "清空",
                confirmDanger = true
            ) {
                SkipLog.clear()
                refreshUi(forceLog = true)
                showToast("运行记录已清空")
            }
        }

        tabViews.forEachIndexed { index, slot ->
            slot.container.setOnClickListener { selectTab(index, scrollToTop = true) }
        }

        btnThemeQuick.setOnClickListener {
            val next = ConsoleFormatter.ThemeOption.nextCycle(statsStore.uiThemeMode)
            applyThemeOption(next)
        }
        segStyleClassic.setOnClickListener { applyThemeStyle(FlashTheme.ThemeStyle.CLASSIC) }
        segStyleEndfield.setOnClickListener { applyThemeStyle(FlashTheme.ThemeStyle.ENDFIELD) }
        segThemeSystem.setOnClickListener { applyThemeOption(ConsoleFormatter.ThemeOption.SYSTEM) }
        segThemeLight.setOnClickListener { applyThemeOption(ConsoleFormatter.ThemeOption.LIGHT) }
        segThemeDark.setOnClickListener { applyThemeOption(ConsoleFormatter.ThemeOption.DARK) }
        renderStyleSegmented()
        renderThemeSegmented()

        tvBuiltInExclusions.text =
            "为保护账号与资金安全，已自动避开系统桌面、输入法以及微信、支付宝、云闪付、主流银行等 ${PackagePolicy.SENSITIVE_FINANCE_PACKAGES.size} 款金融支付应用，永远不会在这些页面点击。"
    }

    private fun openAccessibilityWithFeedback() {
        if (!PermissionHelper.openAccessibilitySettings(this)) {
            SkipLog.log("UI cannot open accessibility settings")
            showToast("未能直接打开无障碍页面，请前往手机「设置 → 辅助功能 / 无障碍」中开启 FlashSkip")
        }
    }

    private fun openAppDetailsWithFeedback() {
        if (!PermissionHelper.openAppDetailsSettings(this)) {
            showToast("未能打开应用信息页，请到手机设置的应用列表里找到 FlashSkip")
        }
    }

    private fun handleNotificationSetupClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            if (!notifPromptedOnce || ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                )
            ) {
                notifPromptedOnce = true
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_POST_NOTIFICATIONS
                )
                return
            }
        }
        if (!PermissionHelper.openNotificationSettings(this)) {
            showToast("未能直接打开通知设置，请在手机设置里允许 FlashSkip 发送通知")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_POST_NOTIFICATIONS) {
            SkipAdService.refreshForegroundProtection()
            refreshUi(forceLog = false)
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            showToast(
                if (granted) "已开启守护通知，后台运行会更稳定"
                else "未获得通知权限，您也可以点按钮前往系统设置里开启"
            )
        }
    }

    private fun syncExpandedPanels() {
        advancedToolsContainer.visibility = if (advancedToolsExpanded) View.VISIBLE else View.GONE
        tvAdvancedToolsArrow.text = if (advancedToolsExpanded) "收起" else "展开"
        testLabListContainer.visibility = if (testLabListExpanded) View.VISIBLE else View.GONE
        btnToggleTestLabList.text = if (testLabListExpanded) "收起 20 项测试列表 ▴" else "展开全部 20 项测试列表 ▾"
    }

    private fun buildKeepAliveGuideRows() {
        val brandDisplay = ConsoleFormatter.brandDisplayName(Build.MANUFACTURER, Build.BRAND)
        tvKeepAliveBrandTitle.text = "$brandDisplay · 手把手操作步骤"

        val baseSteps = PermissionHelper.backgroundGuide().steps
        val fullSteps = ConsoleFormatter.buildBrandStepsWithRecentLock(baseSteps)

        keepAliveStepsContainer.removeAllViews()
        val density = resources.displayMetrics.density
        val endfield = FlashTheme.isEndfield(this)
        val p = FlashTheme.palette(this)
        val hairlinePx = (1f * density).toInt().coerceAtLeast(1)

        fullSteps.forEachIndexed { idx, step ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                if (endfield) {
                    background = EfCutDrawable.buildSquareBox(p.panelSubtle, p.rule, hairlinePx)
                } else {
                    setBackgroundResource(R.drawable.fs_tile_bg)
                }
                val p12 = (12f * density).toInt()
                setPadding(p12, p12, p12, p12)
            }
            val numBadge = TextView(this).apply {
                text = if (endfield) "${idx + 1}".padStart(2, '0') else "${idx + 1}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (endfield) 12f else 12.5f)
                setTextColor(p.tagFg)
                gravity = Gravity.CENTER
                if (endfield) {
                    EfTypeface.applyField(this, bold = true)
                    background = EfCutDrawable.build(
                        fillColor = p.tagBg,
                        cutBottomRightPx = 7f * density,
                        useCutCorners = true
                    )
                    setPadding((7f * density).toInt(), 0, (10f * density).toInt(), 0)
                } else {
                    setTypeface(typeface, Typeface.BOLD)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(p.tagBg)
                    }
                }
            }
            val badgeH = (24f * density).toInt()
            val badgeLp = LinearLayout.LayoutParams(
                if (endfield) ViewGroup.LayoutParams.WRAP_CONTENT else badgeH,
                badgeH
            ).apply {
                marginEnd = (10f * density).toInt()
                topMargin = (2f * density).toInt()
            }
            row.addView(numBadge, badgeLp)

            val textTv = TextView(this).apply {
                text = step
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(p.ink)
                setLineSpacing(0f, 1.45f)
            }
            row.addView(textTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            if (idx > 0) lp.topMargin = (8f * density).toInt()
            keepAliveStepsContainer.addView(row, lp)
        }
    }

    private fun showOtherBrandsPicker() {
        val catalog = ConsoleFormatter.allBrandGuides()
        val items = catalog.mapIndexed { idx, spec -> "${idx + 1}" to spec.brandName }
        EfDialogs.showActionPickerDialog(
            activity = this,
            eyebrow = "",
            title = "选择手机品牌查看操作教程",
            items = items
        ) { which ->
            val picked = catalog[which]
            EfDialogs.showInfoStepsDialog(
                activity = this,
                title = picked.brandName,
                subtitle = "请按以下真实菜单名称依次检查设置：",
                steps = picked.steps
            )
        }
    }

    private fun renderOemJumpFeedback() {
        if (!hasTriedOemJump) {
            kaJumpFeedbackBanner.visibility = View.GONE
            return
        }
        kaJumpFeedbackBanner.visibility = View.VISIBLE
        tvKaJumpFeedback.text = ConsoleFormatter.oemJumpFeedbackText(lastOemDestination)
    }

    private fun renderWindowUi(sec: Int) {
        tvWindow.text = getString(R.string.window_value_fmt, getString(R.string.window_label), sec)
        segWindowBar.setSegments(ConsoleFormatter.windowSegments24(sec), total = 24, useSignal = false)
        val minSec = (SkipStateMachine.MIN_WINDOW_MS / 1000L).toInt()
        val maxSec = (SkipStateMachine.MAX_WINDOW_MS / 1000L).toInt()
        btnWindowMinus.isEnabled = sec > minSec
        btnWindowPlus.isEnabled = sec < maxSec
    }

    private fun persistWindowSeconds(sec: Int) {
        statsStore.windowMs = sec * 1000L
        SkipLog.log("SETTING window_ms=${sec * 1000L}")
        renderWindowUi(sec)
    }

    private fun applyThemeStyle(style: FlashTheme.ThemeStyle) {
        if (statsStore.uiThemeStyle == style.key) return
        statsStore.uiThemeStyle = style.key
        recreate()
    }

    private fun applyThemeOption(option: ConsoleFormatter.ThemeOption) {
        if (statsStore.uiThemeMode == option.nightMode) return
        statsStore.uiThemeMode = option.nightMode
        renderThemeSegmented()
        AppCompatDelegate.setDefaultNightMode(option.nightMode)
    }

    private fun renderStyleSegmented() {
        val current = FlashTheme.currentStyle(this)
        val descOption = ConsoleFormatter.ThemeStyleOption.fromKey(current.key)
        tvStyleActiveDesc.text = "当前为${descOption.label}风格：${descOption.description}"
        renderSegmentedGroup(
            container = segStyle,
            items = listOf(
                segStyleClassic to FlashTheme.ThemeStyle.CLASSIC,
                segStyleEndfield to FlashTheme.ThemeStyle.ENDFIELD
            ),
            active = current
        )
    }

    private fun renderThemeSegmented() {
        val current = ConsoleFormatter.ThemeOption.fromNightMode(statsStore.uiThemeMode)
        val endfield = FlashTheme.isEndfield(this)
        btnThemeQuick.text = if (endfield) {
            when (current) {
                ConsoleFormatter.ThemeOption.SYSTEM -> "AUTO"
                ConsoleFormatter.ThemeOption.LIGHT -> "LIGHT"
                ConsoleFormatter.ThemeOption.DARK -> "DARK"
            }
        } else {
            current.headerLabel
        }
        btnThemeQuick.contentDescription = "切换界面明暗配色，当前：${current.label}"
        renderSegmentedGroup(
            container = segTheme,
            items = listOf(
                segThemeSystem to ConsoleFormatter.ThemeOption.SYSTEM,
                segThemeLight to ConsoleFormatter.ThemeOption.LIGHT,
                segThemeDark to ConsoleFormatter.ThemeOption.DARK
            ),
            active = current
        )
    }

    private fun <T> renderSegmentedGroup(
        container: LinearLayout,
        items: List<Pair<TextView, T>>,
        active: T
    ) {
        val endfield = FlashTheme.isEndfield(this)
        val p = FlashTheme.palette(this)
        val density = resources.displayMetrics.density
        val focusStrokePx = resources.getDimensionPixelSize(R.dimen.ef_focus_stroke).coerceAtLeast(2)
        val hairlinePx = (1f * density).toInt().coerceAtLeast(1)

        if (endfield) {
            container.background = EfCutDrawable.buildSquareBox(p.panel, p.ink, hairlinePx)
            container.setPadding(0, 0, 0, 0)
        } else {
            container.setBackgroundResource(R.drawable.fs_tile_bg)
            val pad = (4f * density).toInt()
            container.setPadding(pad, pad, pad, pad)
        }

        items.forEach { (view, opt) ->
            val selected = (opt == active)
            view.isSelected = selected
            ViewCompat.setStateDescription(view, if (selected) "已选中" else null)
            if (endfield) {
                // Endfield segmented rule: active segment uses solid ink inverted to paper (never yellow)
                if (selected) {
                    view.background = EfCutDrawable.buildInsetFocusSelector(
                        fillColor = p.ink,
                        focusStrokeColor = p.trace,
                        strokeWidthPx = focusStrokePx,
                        insetPx = (2f * density).toInt().coerceAtLeast(2),
                        cornerRadiusPx = 0f
                    )
                    view.setTextColor(p.paper)
                    view.typeface = Typeface.DEFAULT_BOLD
                } else {
                    view.background = EfCutDrawable.buildInsetFocusSelector(
                        fillColor = Color.TRANSPARENT,
                        focusStrokeColor = p.trace,
                        strokeWidthPx = focusStrokePx,
                        insetPx = 0,
                        cornerRadiusPx = 0f
                    )
                    view.setTextColor(p.ink)
                    view.typeface = Typeface.DEFAULT
                }
            } else {
                if (selected) {
                    view.background = EfCutDrawable.buildInsetFocusSelector(
                        fillColor = p.signal,
                        focusStrokeColor = p.panel,
                        strokeWidthPx = focusStrokePx,
                        insetPx = (2f * density).toInt().coerceAtLeast(2),
                        cornerRadiusPx = 10f * density
                    )
                    view.setTextColor(p.onSignal)
                    view.typeface = Typeface.DEFAULT_BOLD
                } else {
                    view.setBackgroundResource(R.drawable.ef_focus_row_bg)
                    view.setTextColor(p.ink2)
                    view.typeface = Typeface.DEFAULT
                }
            }
        }
    }

    private fun selectTab(index: Int, scrollToTop: Boolean) {
        activeTab = index.coerceIn(0, tabPanels.lastIndex)
        tabPanels.forEachIndexed { idx, panel ->
            panel.visibility = if (idx == activeTab) View.VISIBLE else View.GONE
        }

        val endfield = FlashTheme.isEndfield(this)
        val p = FlashTheme.palette(this)
        val density = resources.displayMetrics.density
        val focusStroke = resources.getDimensionPixelSize(R.dimen.ef_focus_stroke).coerceAtLeast(2)

        tabNavBar.setBackgroundColor(p.panel)
        navDivider.setBackgroundColor(p.rule)

        tabViews.forEachIndexed { idx, slot ->
            val selected = (idx == activeTab)
            slot.container.isSelected = selected
            ViewCompat.setStateDescription(slot.container, if (selected) "当前页面" else null)

            if (endfield) {
                slot.icon.visibility = View.GONE
                slot.enLabel.visibility = View.VISIBLE
                EfTypeface.applyEyebrow(slot.enLabel)
                if (selected) {
                    // Endfield bottom/rail nav rule: current tab is full #FFFA00 block + 7dp bottom-right cut + ink text
                    slot.container.background = EfCutDrawable.buildInsetFocusSelector(
                        fillColor = p.signal,
                        focusStrokeColor = p.ink,
                        strokeWidthPx = focusStroke,
                        insetPx = (2f * density).toInt().coerceAtLeast(2),
                        cutBottomRightPx = 7f * density
                    )
                    slot.enLabel.setTextColor(p.onSignal)
                    slot.zhLabel.setTextColor(p.onSignal)
                    slot.zhLabel.typeface = Typeface.DEFAULT_BOLD
                } else {
                    slot.container.background = EfCutDrawable.buildInsetFocusSelector(
                        fillColor = Color.TRANSPARENT,
                        focusStrokeColor = p.trace,
                        strokeWidthPx = focusStroke,
                        insetPx = 0,
                        cornerRadiusPx = 0f
                    )
                    slot.enLabel.setTextColor(p.ink2)
                    slot.zhLabel.setTextColor(p.ink)
                    slot.zhLabel.typeface = Typeface.DEFAULT
                }
            } else {
                slot.icon.visibility = View.VISIBLE
                slot.enLabel.visibility = View.GONE
                if (selected) {
                    slot.container.background = EfCutDrawable.buildInsetFocusSelector(
                        fillColor = p.tagBg,
                        focusStrokeColor = p.trace,
                        strokeWidthPx = focusStroke,
                        insetPx = 0,
                        cornerRadiusPx = 12f * density
                    )
                    ImageViewCompat.setImageTintList(slot.icon, ColorStateList.valueOf(p.signal))
                    slot.zhLabel.setTextColor(p.signal)
                    slot.zhLabel.typeface = Typeface.DEFAULT_BOLD
                } else {
                    slot.container.setBackgroundResource(R.drawable.ef_focus_row_bg)
                    ImageViewCompat.setImageTintList(slot.icon, ColorStateList.valueOf(p.ink2))
                    slot.zhLabel.setTextColor(p.ink2)
                    slot.zhLabel.typeface = Typeface.DEFAULT
                }
            }
        }

        if (scrollToTop) {
            mainScroll.scrollTo(0, 0)
            mainScroll.post { mainScroll.scrollTo(0, 0) }
        }
    }

    private fun buildTestLabRows() {
        testLabListContainer.removeAllViews()
        val density = resources.displayMetrics.density
        val endfield = FlashTheme.isEndfield(this)
        val p = FlashTheme.palette(this)
        val hairlinePx = (1f * density).toInt().coerceAtLeast(1)

        ConsoleFormatter.TestGroup.entries.forEachIndexed { groupIndex, group ->
            val groupScenarios = ConsoleFormatter.TEST_LAB_SCENARIOS.filter { it.group == group }
            val headerZh = TextView(this).apply {
                text = if (endfield) {
                    "// ${group.code} │ ${group.title}（${groupScenarios.size}）"
                } else {
                    "${group.title}（${groupScenarios.size}）"
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (endfield) 12.5f else 14f)
                if (endfield) {
                    EfTypeface.applyEyebrow(this)
                } else {
                    setTypeface(typeface, Typeface.BOLD)
                }
                setTextColor(if (endfield) p.ink2 else p.ink)
                setPadding(
                    0,
                    if (groupIndex == 0) (4f * density).toInt() else (16f * density).toInt(),
                    0,
                    (8f * density).toInt()
                )
            }
            testLabListContainer.addView(headerZh)

            val groupBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                if (endfield) {
                    background = EfCutDrawable.buildSquareBox(p.panelSubtle, p.rule, hairlinePx)
                } else {
                    setBackgroundResource(R.drawable.fs_tile_bg)
                }
            }

            groupScenarios.forEachIndexed { idx, spec ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = (52f * density).toInt()
                    isFocusable = true
                    isClickable = true
                    if (endfield) {
                        background = EfCutDrawable.buildInsetFocusSelector(
                            fillColor = Color.TRANSPARENT,
                            focusStrokeColor = p.trace,
                            strokeWidthPx = (2f * density).toInt().coerceAtLeast(2),
                            insetPx = 0
                        )
                    } else {
                        setBackgroundResource(R.drawable.ef_focus_row_bg)
                    }
                    setPadding(
                        (12f * density).toInt(),
                        (10f * density).toInt(),
                        (12f * density).toInt(),
                        (10f * density).toInt()
                    )
                    setOnClickListener { startTestScenario(spec.key) }
                }

                val tagView = TextView(this).apply {
                    text = spec.indexLabel
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setTextColor(p.tagFg)
                    if (endfield) {
                        EfTypeface.applyField(this, bold = true)
                        background = EfCutDrawable.build(
                            fillColor = p.tagBg,
                            cutBottomRightPx = 7f * density,
                            useCutCorners = true
                        )
                        setPadding((7f * density).toInt(), (2f * density).toInt(), (11f * density).toInt(), (2f * density).toInt())
                    } else {
                        setTypeface(typeface, Typeface.BOLD)
                        background = EfCutDrawable.build(
                            fillColor = p.tagBg,
                            cutBottomRightPx = 8f * density,
                            useCutCorners = false
                        )
                        setPadding((8f * density).toInt(), (3f * density).toInt(), (8f * density).toInt(), (3f * density).toInt())
                    }
                }
                row.addView(tagView, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (10f * density).toInt() })

                val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val titleTv = TextView(this).apply {
                    text = spec.title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(p.ink)
                }
                val detailTv = TextView(this).apply {
                    text = spec.detail
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setTextColor(p.ink2)
                }
                texts.addView(titleTv)
                texts.addView(detailTv)
                row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                row.setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            v.alpha = 0.78f
                            if (endfield) v.translationX = 6f * density
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.alpha = 1f
                            v.translationX = 0f
                        }
                    }
                    false
                }

                groupBox.addView(row)
                if (idx < groupScenarios.lastIndex) {
                    val div = View(this).apply { setBackgroundColor(p.rule) }
                    groupBox.addView(div, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        hairlinePx
                    ))
                }
            }
            testLabListContainer.addView(groupBox)
        }
    }

    override fun onResume() {
        super.onResume()
        SkipAdService.refreshForegroundProtection()
        handler.post(refresh)
        if (!SkipAdService.connected) {
            handler.removeCallbacks(reconnectCheck)
            handler.postDelayed(reconnectCheck, 500)
        }
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(reconnectCheck)
        super.onPause()
    }

    private fun applyStatusPillStyle(view: TextView, tone: ConsoleFormatter.StatusTone) {
        val endfield = FlashTheme.isEndfield(this)
        val p = FlashTheme.palette(this)
        if (!endfield) {
            val (bgRes, fgRes) = when (tone) {
                ConsoleFormatter.StatusTone.OK -> R.drawable.ef_state_active_bg to R.color.fs_status_ok_fg
                ConsoleFormatter.StatusTone.WARN -> R.drawable.ef_state_dashed_bg to R.color.fs_status_warn_fg
                ConsoleFormatter.StatusTone.PAUSED -> R.drawable.fs_pill_paused_bg to R.color.fs_status_paused_fg
                ConsoleFormatter.StatusTone.ALERT -> R.drawable.ef_state_alert_bg to R.color.fs_status_alert_fg
            }
            view.setBackgroundResource(bgRes)
            view.setTextColor(ContextCompat.getColor(this, fgRes))
            return
        }

        val d = resources.displayMetrics.density
        val strokePx = (1f * d).toInt().coerceAtLeast(1)
        when (tone) {
            ConsoleFormatter.StatusTone.OK -> {
                // Solid box = live
                view.background = EfCutDrawable.buildSquareBox(p.statusOkBg, p.statusOkStroke, strokePx)
                view.setTextColor(p.statusOkFg)
            }
            ConsoleFormatter.StatusTone.WARN -> {
                // Dashed box = attention/connecting
                view.background = EfCutDrawable.buildSquareBox(p.statusWarnBg, p.statusWarnStroke, strokePx, dashed = true, dashWidthPx = 4f * d, dashGapPx = 3f * d)
                view.setTextColor(p.statusWarnFg)
            }
            ConsoleFormatter.StatusTone.PAUSED -> {
                // Quiet dashed chip box = paused
                view.background = EfCutDrawable.buildSquareBox(p.statusPausedBg, p.statusPausedStroke, strokePx, dashed = true, dashWidthPx = 4f * d, dashGapPx = 3f * d)
                view.setTextColor(p.statusPausedFg)
            }
            ConsoleFormatter.StatusTone.ALERT -> {
                // Yellow cut stamp = needs look (never red)
                view.background = EfCutDrawable.build(p.statusAlertBg, cutBottomRightPx = 7f * d, useCutCorners = true)
                view.setTextColor(p.statusAlertFg)
            }
        }
    }

    private fun refreshUi(forceLog: Boolean) {
        val status = PermissionHelper.readStatus(this)
        val observeOnly = statsStore.observeOnly

        // Sync switches with persisted preferences without re-invoking user listeners
        syncingSwitches = true
        try {
            if (swMaster.isChecked != status.masterEnabled) swMaster.isChecked = status.masterEnabled
            if (swObserve.isChecked != observeOnly) swObserve.isChecked = observeOnly
            val skipToastOn = statsStore.skipToastEnabled
            if (swSkipToast.isChecked != skipToastOn) swSkipToast.isChecked = skipToastOn
            val diagOn = statsStore.diagNodes
            if (swDiag.isChecked != diagOn) swDiag.isChecked = diagOn
        } finally {
            syncingSwitches = false
        }

        // Top Header Status Pill
        val headerStatus = ConsoleFormatter.headerStatus(status, observeOnly)
        tvCmdStatus.text = headerStatus.label
        applyStatusPillStyle(tvCmdStatus, headerStatus.tone)

        // Home Hero Card
        val hero = ConsoleFormatter.formatHomeHero(status, observeOnly)
        tvStatusBadge.text = hero.badgeText
        applyStatusPillStyle(tvStatusBadge, hero.badgeTone)

        tvKeepAliveBadge.text = hero.keepAliveChipText
        if (hero.keepAliveHealthy) {
            applyStatusPillStyle(tvKeepAliveBadge, ConsoleFormatter.StatusTone.OK)
        } else {
            val pTheme = FlashTheme.palette(this)
            if (FlashTheme.isEndfield(this)) {
                val strokePx = (1f * resources.displayMetrics.density).toInt().coerceAtLeast(1)
                tvKeepAliveBadge.background = EfCutDrawable.buildSquareBox(pTheme.chip, pTheme.rule, strokePx)
            } else {
                tvKeepAliveBadge.setBackgroundResource(R.drawable.fs_tile_bg)
            }
            tvKeepAliveBadge.setTextColor(pTheme.ink2)
        }

        tvStatus.text = hero.headline
        tvHeroDesc.text = hero.description

        if (hero.bannerText != null) {
            noticeActionBanner.visibility = View.VISIBLE
            tvNextStepHint.text = hero.bannerText
        } else {
            noticeActionBanner.visibility = View.GONE
        }

        currentPrimaryAction = hero.primaryAction
        btnEnable.text = hero.primaryCtaLabel
        btnEnable.setEfKind(
            if (hero.primaryAction == ConsoleFormatter.HomePrimaryAction.RUN_QUICK_TEST) {
                EfButton.Kind.NORMAL
            } else {
                EfButton.Kind.GO
            }
        )

        if (hero.secondaryCtaLabel != null) {
            btnHomeSecondary.visibility = View.VISIBLE
            btnHomeSecondary.text = hero.secondaryCtaLabel
        } else {
            btnHomeSecondary.visibility = View.GONE
        }

        tvMasterSummary.text = if (status.masterEnabled) {
            "当前已开启：打开其他应用时会自动跳过开屏广告，不用时可随时关闭暂停。"
        } else {
            "当前已暂停：不会主动点击任何开屏页面，打开上方开关即可随时恢复。"
        }

        // Home Keep-Alive Overview Card
        val readiness4 = ConsoleFormatter.computeKeepAliveReadiness4(status)
        tvReadinessText.text = readiness4.fractionText
        segReadiness.setSegments(readiness4.filledSegments24, total = 24, useSignal = true)

        renderHomeCheckChip(
            tvHomeCheckA11y,
            when {
                status.accessibilityConnected -> "无障碍：已连接" to ConsoleFormatter.StatusTone.OK
                status.accessibilityEnabled -> "无障碍：待重连" to ConsoleFormatter.StatusTone.WARN
                else -> "无障碍：未开启" to ConsoleFormatter.StatusTone.ALERT
            }
        )
        renderHomeCheckChip(
            tvHomeCheckNotif,
            if (status.notificationsVisible) {
                "守护通知：已开启" to ConsoleFormatter.StatusTone.OK
            } else {
                "守护通知：未开启" to ConsoleFormatter.StatusTone.WARN
            }
        )
        renderHomeCheckChip(
            tvHomeCheckBattery,
            if (status.batteryExempt) {
                "电量限制：已无限制" to ConsoleFormatter.StatusTone.OK
            } else {
                "电量限制：未豁免" to ConsoleFormatter.StatusTone.WARN
            }
        )
        renderHomeCheckChip(
            tvHomeCheckBg,
            if (!status.backgroundRestricted) {
                "系统后台：未受限" to ConsoleFormatter.StatusTone.OK
            } else {
                "系统后台：被限制中" to ConsoleFormatter.StatusTone.ALERT
            }
        )

        tvHomeKeepAliveSummary.text = when {
            readiness4.readyCount == 4 -> "4 项核心保护全部就绪。再在手机“最近任务”里把闪跳卡片加锁，全天运行会更稳。"
            status.backgroundRestricted -> "系统当前限制了后台运行，锁屏后很容易失效，请点下方按钮去放行。"
            else -> "还有 ${4 - readiness4.readyCount} 项待完善。完成后，息屏放几小时或一整天也能稳稳工作。"
        }
        btnGoKeepAlive.setEfKind(
            if (readiness4.readyCount < 4 && status.accessibilityConnected) EfButton.Kind.GO else EfButton.Kind.NORMAL
        )

        // Keep-Alive Tab Detailed Checklist
        renderKeepAliveTab(status)

        renderStats()
        renderCustomExclusions()

        val currentVersion = SkipLog.version.get()
        if ((forceLog || currentVersion != lastRenderedLogVersion) && !tvLog.hasSelection()) {
            lastRenderedLogVersion = currentVersion
            val log = SkipLog.tail(80)
            if (log.isEmpty()) {
                emptyLogBox.visibility = View.VISIBLE
                tvLog.visibility = View.GONE
                tvLog.text = ""
                tvLogMeta.text = "0 行"
            } else {
                emptyLogBox.visibility = View.GONE
                tvLog.visibility = View.VISIBLE
                tvLog.text = log
                val lineCount = log.count { it == '\n' } + 1
                tvLogMeta.text = "最近 $lineCount 行"
            }
        } else if (tvLog.hasSelection()) {
            tvLogMeta.text = "选中暂停中"
        }
    }

    private fun renderHomeCheckChip(view: TextView, state: Pair<String, ConsoleFormatter.StatusTone>) {
        view.text = state.first
        applyStatusPillStyle(view, state.second)
    }

    private fun renderKeepAliveTab(status: KeepAliveStatus) {
        when {
            status.accessibilityConnected -> {
                tvKaA11yBadge.text = "工作中"
                applyStatusPillStyle(tvKaA11yBadge, ConsoleFormatter.StatusTone.OK)
                btnKaAccessibility.setEfKind(EfButton.Kind.NORMAL)
            }
            status.accessibilityEnabled -> {
                tvKaA11yBadge.text = "连接断开"
                applyStatusPillStyle(tvKaA11yBadge, ConsoleFormatter.StatusTone.WARN)
                btnKaAccessibility.setEfKind(EfButton.Kind.GO)
            }
            else -> {
                tvKaA11yBadge.text = "未开启"
                applyStatusPillStyle(tvKaA11yBadge, ConsoleFormatter.StatusTone.ALERT)
                btnKaAccessibility.setEfKind(EfButton.Kind.GO)
            }
        }

        if (status.notificationsVisible) {
            tvKaNotifBadge.text = "已开启"
            applyStatusPillStyle(tvKaNotifBadge, ConsoleFormatter.StatusTone.OK)
            btnKaNotification.text = "通知设置（已开启）"
        } else {
            tvKaNotifBadge.text = "建议开启"
            applyStatusPillStyle(tvKaNotifBadge, ConsoleFormatter.StatusTone.WARN)
            val runtimeMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            btnKaNotification.text = if (runtimeMissing && !notifPromptedOnce) {
                "允许状态栏守护通知"
            } else {
                "去系统设置开启通知"
            }
        }

        if (status.batteryExempt) {
            tvKaBatteryBadge.text = "已设为无限制"
            applyStatusPillStyle(tvKaBatteryBadge, ConsoleFormatter.StatusTone.OK)
            btnBatteryOpt.text = "电池优化已豁免（点击可核对）"
        } else {
            tvKaBatteryBadge.text = "受省电限制"
            applyStatusPillStyle(tvKaBatteryBadge, ConsoleFormatter.StatusTone.WARN)
            btnBatteryOpt.text = getString(R.string.btn_battery_opt)
        }

        if (status.backgroundRestricted) {
            tvKaBgBadge.text = "已被系统限制"
            applyStatusPillStyle(tvKaBgBadge, ConsoleFormatter.StatusTone.ALERT)
        } else {
            tvKaBgBadge.text = "未发现系统拦截"
            applyStatusPillStyle(tvKaBgBadge, ConsoleFormatter.StatusTone.OK)
        }

        tvKeepAliveInfo.text = PermissionHelper.oemKeepAliveAdvice()
        renderOemJumpFeedback()
    }

    private fun renderStats() {
        val summary = statsStore.getSummary()
        val p = ConsoleFormatter.formatStats(summary)
        tvStats.text = p.summaryLine
        tvStatSampleCount.text = p.sampleCountText

        val hasSkippedCounts = summary.todaySkipped > 0 || summary.totalSkipped > 0
        if (!hasSkippedCounts) {
            emptyStatsBox.visibility = View.VISIBLE
            homeStatsTiles.visibility = View.GONE
            if (p.legacyNotice != null) {
                tvStatLegacyEmpty.visibility = View.VISIBLE
                tvStatLegacyEmpty.text = p.legacyNotice
            } else {
                tvStatLegacyEmpty.visibility = View.GONE
            }
        } else {
            emptyStatsBox.visibility = View.GONE
            homeStatsTiles.visibility = View.VISIBLE
            tvStatToday.text = p.todayText
            tvStatTotal.text = p.totalText
        }

        if (p.isEmpty) {
            telemetryDataGroup.visibility = View.GONE
            return
        }

        telemetryDataGroup.visibility = View.VISIBLE
        tvStatP50.text = p.p50Text
        tvStatP95.text = p.p95Text
        tvStatStillPresent.text = p.stillPresentText
        tvStatNavigated.text = p.navigatedText
        tvStatUnobserved.text = p.unobservedText

        val pTheme = FlashTheme.palette(this)
        if (summary.navigatedAttempts > 0) {
            applyStatusPillStyle(tvStatNavigated, ConsoleFormatter.StatusTone.WARN)
        } else {
            tvStatNavigated.setBackgroundColor(0)
            tvStatNavigated.setTextColor(pTheme.ink)
        }

        if (p.legacyNotice != null) {
            tvStatLegacy.visibility = View.VISIBLE
            tvStatLegacy.text = p.legacyNotice
        } else {
            tvStatLegacy.visibility = View.GONE
        }
    }

    private fun renderCustomExclusions() {
        val custom = statsStore.getUserDisabledPackages()
        if (custom == lastRenderedExclusions && customExclusionsList.childCount > 0 == custom.isNotEmpty()) {
            return
        }
        lastRenderedExclusions = custom
        val sorted = custom.sorted()
        tvCustomExclusionsHeader.text = "我添加的不跳过应用（${sorted.size}）"

        if (sorted.isEmpty()) {
            emptyExclusionsBox.visibility = View.VISIBLE
            customExclusionsList.visibility = View.GONE
            customExclusionsList.removeAllViews()
            return
        }

        emptyExclusionsBox.visibility = View.GONE
        customExclusionsList.visibility = View.VISIBLE
        customExclusionsList.removeAllViews()

        val density = resources.displayMetrics.density
        val p = FlashTheme.palette(this)

        sorted.forEachIndexed { idx, pkg ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (50f * density).toInt()
                setPadding(0, (6f * density).toInt(), 0, (6f * density).toInt())
            }
            val pkgTv = TextView(this).apply {
                text = pkg
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(p.ink)
            }
            val removeBtn = EfButton(this).apply {
                text = "移除"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setEfKind(EfButton.Kind.DANGER)
                contentDescription = "恢复自动跳过 $pkg"
                setOnClickListener {
                    EfDialogs.showConfirmDialog(
                        activity = this@MainActivity,
                        eyebrow = "",
                        title = "移除免打扰应用？",
                        message = "移除后，下次打开 $pkg 时将重新自动跳过它的开屏广告。",
                        confirmText = "移除",
                        confirmDanger = true
                    ) {
                        statsStore.setPackageDisabled(pkg, false)
                        SkipLog.log("SETTING excluded-=$pkg")
                        renderCustomExclusions()
                        showToast("已恢复自动跳过：$pkg")
                    }
                }
            }
            row.addView(pkgTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(removeBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (8f * density).toInt() })
            customExclusionsList.addView(row)

            if (idx < sorted.lastIndex) {
                val div = View(this).apply { setBackgroundColor(p.rule) }
                customExclusionsList.addView(div, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (1f * density).toInt().coerceAtLeast(1)
                ))
            }
        }
    }

    private fun startTestScenario(scenario: String, requestId: String? = null) {
        val launch = Intent(this, TestSplashActivity::class.java)
            .putExtra(TestSplashActivity.EXTRA_SCENARIO, scenario)
        if (requestId != null) {
            launch.putExtra(TestSplashActivity.EXTRA_REQUEST_ID, requestId)
            SkipLog.log("LAB_REQUEST request=$requestId stage=launch_call atMs=${SystemClock.uptimeMillis()}")
        }
        startActivity(launch)
    }

    private fun showTestLabPicker() {
        val scenarios = ConsoleFormatter.TEST_LAB_SCENARIOS
        val items = scenarios.map { spec ->
            spec.indexLabel to "[${spec.group.title}] ${spec.title}"
        }
        EfDialogs.showActionPickerDialog(
            activity = this,
            eyebrow = "",
            title = "选择要体验或测试的模拟开屏场景",
            items = items
        ) { which ->
            startTestScenario(scenarios[which].key)
        }
    }

    private fun showExclusionsDialog() {
        val custom = statsStore.getUserDisabledPackages().sorted()
        val options = buildList {
            add("添加" to "添加不跳过广告的应用包名")
            if (custom.isNotEmpty()) {
                add("移除" to "从免打扰名单移除（当前 ${custom.size} 个）")
            }
        }
        EfDialogs.showActionPickerDialog(
            activity = this,
            eyebrow = "",
            title = "免打扰名单管理（已内置 ${PackagePolicy.SENSITIVE_FINANCE_PACKAGES.size} 项金融保护）",
            items = options
        ) { which ->
            when (which) {
                0 -> promptAddExclusion()
                1 -> promptRemoveExclusion(custom)
            }
        }
    }

    private fun promptAddExclusion() {
        EfDialogs.showAddExclusionDialog(this) { pkg ->
            statsStore.setPackageDisabled(pkg, true)
            SkipLog.log("SETTING excluded+=$pkg")
            renderCustomExclusions()
            showToast("已加入免打扰名单：$pkg")
        }
    }

    private fun promptRemoveExclusion(custom: List<String>) {
        if (custom.isEmpty()) {
            showToast("当前还没有手动添加的应用")
            return
        }
        val items = custom.mapIndexed { i, pkg -> "${i + 1}" to pkg }
        EfDialogs.showActionPickerDialog(
            activity = this,
            eyebrow = "",
            title = "选择要移除的应用",
            items = items
        ) { which ->
            val pkg = custom[which]
            EfDialogs.showConfirmDialog(
                activity = this,
                eyebrow = "",
                title = "移除免打扰应用？",
                message = "移除后，下次打开 $pkg 时将重新自动跳过它的开屏广告。",
                confirmText = "移除",
                confirmDanger = true
            ) {
                statsStore.setPackageDisabled(pkg, false)
                SkipLog.log("SETTING excluded-=$pkg")
                renderCustomExclusions()
                showToast("已恢复自动跳过：$pkg")
            }
        }
    }

    private fun copyLogsToClipboard() {
        val content = SkipLog.exportAll()
        if (content.isBlank()) {
            showToast("暂无可复制的运行记录")
            return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("FlashSkip Log", content))
        showToast("已复制全部运行记录")
    }

    private fun shareLogs() {
        val content = SkipLog.exportAll()
        if (content.isBlank()) {
            showToast("暂无可导出的运行记录")
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "FlashSkip 运行记录")
            putExtra(Intent.EXTRA_TEXT, content)
        }
        startActivity(Intent.createChooser(intent, "分享或导出运行记录"))
    }

    private fun showToast(message: String) {
        val isWideRail = resources.configuration.smallestScreenWidthDp >= 600 ||
            resources.configuration.screenWidthDp >= 600
        val offset = if (!isWideRail && tabNavBar.height > 0) tabNavBar.height else 0
        EfDialogs.showDarkToast(rootContainer, message, bottomOffsetPx = offset)
    }
}
