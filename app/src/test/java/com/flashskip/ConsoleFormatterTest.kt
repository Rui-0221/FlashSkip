package com.flashskip

import androidx.appcompat.app.AppCompatDelegate
import com.flashskip.data.StatsSummary
import com.flashskip.keepalive.KeepAliveStatus
import com.flashskip.keepalive.SettingsDestination
import com.flashskip.ui.ConsoleFormatter
import com.flashskip.ui.FlashTheme
import com.flashskip.ui.SkipToastGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleFormatterTest {

    @Test
    fun `empty stats renders empty state and preserves legacy migration notice`() {
        val pureEmpty = StatsSummary(
            totalSkipped = 0,
            todaySkipped = 0,
            p50Ms = -1L,
            p95Ms = -1L,
            sampleCount = 0,
            legacyTotalSkipped = 0,
            stillPresentAttempts = 0,
            navigatedAttempts = 0,
            unobservedAttempts = 0
        )
        val p0 = ConsoleFormatter.formatStats(pureEmpty)
        assertTrue(p0.isEmpty)
        assertNull(p0.legacyNotice)

        val migrated = pureEmpty.copy(legacyTotalSkipped = 12)
        val p1 = ConsoleFormatter.formatStats(migrated)
        assertTrue(p1.isEmpty)
        assertTrue(p1.legacyNotice?.contains("12") == true)
        assertTrue(p1.summaryLine.contains("12"))
    }

    @Test
    fun `zero ms latency is valid while negative latency shows sample shortage`() {
        val s = StatsSummary(
            totalSkipped = 6,
            todaySkipped = 2,
            p50Ms = 0L,
            p95Ms = -1L,
            sampleCount = 6,
            legacyTotalSkipped = 0,
            stillPresentAttempts = 1,
            navigatedAttempts = 2,
            unobservedAttempts = 3
        )
        val p = ConsoleFormatter.formatStats(s)
        assertFalse(p.isEmpty)
        assertEquals("2", p.todayText)
        assertEquals("6", p.totalText)
        assertEquals("0 ms", p.p50Text)
        assertEquals("样本不足 (6/20)", p.p95Text)
        assertEquals("6 条记录", p.sampleCountText)
        assertEquals("1 次", p.stillPresentText)
        assertEquals("2 次", p.navigatedText)
        assertEquals("3 次", p.unobservedText)
        assertTrue(p.summaryLine.contains("中位速度 0ms"))
        assertTrue(p.summaryLine.contains("95%耗时 样本不足"))
    }

    @Test
    fun `attempt-only activity exits empty state without computing miss rates`() {
        val s = StatsSummary(
            totalSkipped = 0,
            todaySkipped = 0,
            p50Ms = -1L,
            p95Ms = -1L,
            sampleCount = 0,
            legacyTotalSkipped = 0,
            stillPresentAttempts = 0,
            navigatedAttempts = 2,
            unobservedAttempts = 1
        )
        val p = ConsoleFormatter.formatStats(s)
        assertFalse(p.isEmpty)
        assertEquals("2 次", p.navigatedText)
        assertEquals("1 次", p.unobservedText)
    }

    @Test
    fun `readiness, window segments, and command status map accurately`() {
        assertEquals(0, ConsoleFormatter.computeReadiness(false, false, false).filledSegments24)
        assertEquals(16, ConsoleFormatter.computeReadiness(true, true, false).filledSegments24)
        assertEquals(24, ConsoleFormatter.computeReadiness(true, true, true).filledSegments24)

        assertEquals(3, ConsoleFormatter.windowSeconds(1000L))
        assertEquals(15, ConsoleFormatter.windowSeconds(30000L))
        assertEquals(24, ConsoleFormatter.windowSegments24(15))

        assertEquals("OFF │ 06S", ConsoleFormatter.commandStatusText(false, false, true, false, 6))
        assertEquals("WAIT │ 06S", ConsoleFormatter.commandStatusText(false, true, true, false, 6))
        assertEquals("PAUSE │ 06S", ConsoleFormatter.commandStatusText(true, true, false, false, 6))
        assertEquals("OBS │ 06S", ConsoleFormatter.commandStatusText(true, true, true, true, 6))
        assertEquals("RUN │ 10S", ConsoleFormatter.commandStatusText(true, true, true, false, 10))
    }

    @Test
    fun `test lab catalog covers all 20 unique scenarios and theme cycles predictably`() {
        assertEquals(20, ConsoleFormatter.TEST_LAB_SCENARIOS.size)
        val keys = ConsoleFormatter.TEST_LAB_SCENARIOS.map { it.key }.toSet()
        assertEquals(20, keys.size)
        assertEquals("No.01", ConsoleFormatter.TEST_LAB_SCENARIOS.first().indexLabel)
        assertEquals("No.20", ConsoleFormatter.TEST_LAB_SCENARIOS.last().indexLabel)

        assertEquals(
            ConsoleFormatter.ThemeOption.LIGHT,
            ConsoleFormatter.ThemeOption.nextCycle(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        )
        assertEquals(
            ConsoleFormatter.ThemeOption.DARK,
            ConsoleFormatter.ThemeOption.nextCycle(AppCompatDelegate.MODE_NIGHT_NO)
        )
        assertEquals(
            ConsoleFormatter.ThemeOption.SYSTEM,
            ConsoleFormatter.ThemeOption.nextCycle(AppCompatDelegate.MODE_NIGHT_YES)
        )
    }

    @Test
    fun `command header keeps single row when wide and wraps cleanly when compact enlarged`() {
        val compactNormal = ConsoleFormatter.computeHeaderPlacement(
            availW = 338,
            brandW = 140, brandH = 24,
            statusW = 68, statusH = 20,
            themeW = 86, themeH = 48,
            brandGap = 12, statusGap = 8, rowGap = 6
        )
        assertEquals(1, compactNormal.rows)
        assertTrue(compactNormal.brandX + 140 + 12 <= compactNormal.statusX)
        assertEquals(338 - 86, compactNormal.themeX)

        val wideEnlarged = ConsoleFormatter.computeHeaderPlacement(
            availW = 572,
            brandW = 205, brandH = 34,
            statusW = 102, statusH = 28,
            themeW = 118, themeH = 54,
            brandGap = 12, statusGap = 8, rowGap = 6
        )
        assertEquals(1, wideEnlarged.rows)
        assertTrue(wideEnlarged.brandX + 205 + 12 <= wideEnlarged.statusX)

        val compactEnlarged = ConsoleFormatter.computeHeaderPlacement(
            availW = 338,
            brandW = 205, brandH = 34,
            statusW = 102, statusH = 28,
            themeW = 118, themeH = 54,
            brandGap = 12, statusGap = 8, rowGap = 6
        )
        assertEquals(2, compactEnlarged.rows)
        assertEquals(0, compactEnlarged.brandY)
        assertEquals(0, compactEnlarged.statusX)
        assertEquals(338 - 118, compactEnlarged.themeX)
        assertTrue(compactEnlarged.statusY >= 34 + 6)
        assertTrue(compactEnlarged.themeY >= 34 + 6)
        assertTrue(compactEnlarged.statusX + 102 + 8 <= compactEnlarged.themeX)
        assertEquals(34 + 6 + 54, compactEnlarged.contentHeight)

        val ultraNarrow = ConsoleFormatter.computeHeaderPlacement(
            availW = 160,
            brandW = 150, brandH = 34,
            statusW = 110, statusH = 28,
            themeW = 110, themeH = 54,
            brandGap = 12, statusGap = 8, rowGap = 6
        )
        assertEquals(3, ultraNarrow.rows)
        assertEquals(34 + 6 + 28 + 6 + 54, ultraNarrow.contentHeight)
    }

    @Test
    fun `consumer hero and header distinguish authorization, connection, restrictions, pause, and observe mode`() {
        val healthy = KeepAliveStatus(
            masterEnabled = true,
            accessibilityEnabled = true,
            accessibilityConnected = true,
            foregroundActive = true,
            batteryExempt = true,
            backgroundRestricted = false,
            notificationsVisible = true,
            notificationPermissionGranted = true,
            notificationChannelReady = true,
            oemKeepAliveConfirmed = true
        )

        val needsA11y = ConsoleFormatter.formatHomeHero(healthy.copy(accessibilityEnabled = false), false)
        assertEquals(ConsoleFormatter.HomePrimaryAction.OPEN_ACCESSIBILITY, needsA11y.primaryAction)
        assertEquals(ConsoleFormatter.StatusTone.ALERT, needsA11y.badgeTone)
        assertTrue(needsA11y.showRestrictedHelp)
        assertEquals("待开启", ConsoleFormatter.headerStatus(healthy.copy(accessibilityEnabled = false), false).label)

        val connecting = ConsoleFormatter.formatHomeHero(healthy.copy(accessibilityConnected = false), false)
        assertEquals(ConsoleFormatter.HomePrimaryAction.OPEN_ACCESSIBILITY, connecting.primaryAction)
        assertEquals(ConsoleFormatter.StatusTone.WARN, connecting.badgeTone)
        assertEquals("待重连", ConsoleFormatter.headerStatus(healthy.copy(accessibilityConnected = false), false).label)

        val paused = ConsoleFormatter.formatHomeHero(healthy.copy(masterEnabled = false), false)
        assertEquals(ConsoleFormatter.HomePrimaryAction.RESUME_MASTER, paused.primaryAction)
        assertEquals(ConsoleFormatter.StatusTone.PAUSED, paused.badgeTone)
        assertEquals("已暂停", ConsoleFormatter.headerStatus(healthy.copy(masterEnabled = false), false).label)

        val restricted = ConsoleFormatter.formatHomeHero(healthy.copy(batteryExempt = false), false)
        assertEquals(ConsoleFormatter.HomePrimaryAction.OPEN_KEEPALIVE, restricted.primaryAction)
        assertEquals(ConsoleFormatter.StatusTone.WARN, restricted.badgeTone)
        assertFalse(restricted.keepAliveHealthy)

        val observe = ConsoleFormatter.formatHomeHero(healthy, observeOnly = true)
        assertEquals(ConsoleFormatter.HomePrimaryAction.DISABLE_OBSERVE, observe.primaryAction)
        assertEquals("演练中", ConsoleFormatter.headerStatus(healthy, observeOnly = true).label)

        val ready = ConsoleFormatter.formatHomeHero(healthy, false)
        assertEquals(ConsoleFormatter.HomePrimaryAction.RUN_QUICK_TEST, ready.primaryAction)
        assertEquals(ConsoleFormatter.StatusTone.OK, ready.badgeTone)
        assertTrue(ready.keepAliveHealthy)
        assertEquals("保护中", ConsoleFormatter.headerStatus(healthy, false).label)
    }

    @Test
    fun `keep-alive readiness, brand guide fallback text, and toast cooldown gate behave predictably`() {
        val status = KeepAliveStatus(
            masterEnabled = true,
            accessibilityEnabled = true,
            accessibilityConnected = true,
            foregroundActive = true,
            batteryExempt = false,
            backgroundRestricted = false,
            notificationsVisible = true,
            notificationPermissionGranted = true,
            notificationChannelReady = true,
            oemKeepAliveConfirmed = true
        )
        val r4 = ConsoleFormatter.computeKeepAliveReadiness4(status)
        assertEquals(3, r4.readyCount)
        assertEquals(18, r4.filledSegments24)

        assertEquals("荣耀手机（MagicOS）", ConsoleFormatter.brandDisplayName("HONOR", "HONOR"))
        assertTrue(ConsoleFormatter.oemJumpFeedbackText(null).contains("手动"))
        assertTrue(ConsoleFormatter.oemJumpFeedbackText(SettingsDestination.APP_DETAILS).contains("应用详情"))
        assertTrue(ConsoleFormatter.buildBrandStepsWithRecentLock(listOf("步骤一")).any { "最近任务" in it })
        assertEquals(6, ConsoleFormatter.allBrandGuides().size)

        val gate = SkipToastGate(cooldownMs = 1800L)
        assertTrue(gate.shouldShow(10_000L))
        assertFalse(gate.shouldShow(10_500L))
        assertFalse(gate.shouldShow(11_799L))
        assertTrue(gate.shouldShow(11_800L))
    }

    @Test
    fun `endfield theme palette enforces tokens, light contrast safety, and dual theme separation`() {
        assertEquals(FlashTheme.ThemeStyle.CLASSIC, FlashTheme.ThemeStyle.fromKey(null))
        assertEquals(FlashTheme.ThemeStyle.CLASSIC, FlashTheme.ThemeStyle.fromKey("unknown"))
        assertEquals(FlashTheme.ThemeStyle.ENDFIELD, FlashTheme.ThemeStyle.fromKey("endfield"))
        assertEquals(ConsoleFormatter.ThemeStyleOption.ENDFIELD, ConsoleFormatter.ThemeStyleOption.fromKey("ENDFIELD"))

        val efLight = FlashTheme.paletteFor(FlashTheme.ThemeStyle.ENDFIELD, dark = false)
        val efDark = FlashTheme.paletteFor(FlashTheme.ThemeStyle.ENDFIELD, dark = true)
        val clLight = FlashTheme.paletteFor(FlashTheme.ThemeStyle.CLASSIC, dark = false)

        // Endfield fixed token invariants
        assertEquals(0xFFF4F4F2.toInt(), efLight.paper)
        assertEquals(0xFF191919.toInt(), efDark.paper)
        assertEquals(0xFFFFFA00.toInt(), efLight.signal)
        assertEquals(0xFFFFFA00.toInt(), efDark.signal)
        assertEquals(0xFF161616.toInt(), efLight.commandBar)
        assertEquals(0xFF161616.toInt(), efDark.commandBar)

        // Light Endfield never uses acid yellow for ink lines or text links
        assertEquals(efLight.ink, efLight.trace)
        assertEquals(efLight.ink, efLight.linkColor)
        assertNotEquals(efLight.signal, efLight.linkColor)

        // Dark Endfield promotes trace & link to high-contrast acid yellow against dark paper
        assertEquals(efDark.signal, efDark.trace)
        assertEquals(efDark.signal, efDark.linkColor)

        // Alert badge in Endfield uses signal yellow + ink (never danger red)
        assertEquals(efLight.signal, efLight.statusAlertBg)
        assertEquals(efLight.onSignal, efLight.statusAlertFg)
        assertNotEquals(clLight.signal, efLight.signal)
    }

    @Test
    fun `meow theme palette, cat moods, and endfield HUD telemetry map accurately`() {
        assertEquals(FlashTheme.ThemeStyle.MEOW, FlashTheme.ThemeStyle.fromKey("meow"))
        assertEquals(ConsoleFormatter.ThemeStyleOption.MEOW, ConsoleFormatter.ThemeStyleOption.fromKey("MEOW"))

        val meowLight = FlashTheme.paletteFor(FlashTheme.ThemeStyle.MEOW, dark = false)
        val meowDark = FlashTheme.paletteFor(FlashTheme.ThemeStyle.MEOW, dark = true)
        val clLight = FlashTheme.paletteFor(FlashTheme.ThemeStyle.CLASSIC, dark = false)

        assertEquals(FlashTheme.ThemeStyle.MEOW, meowLight.style)
        assertTrue(meowDark.isDark)
        assertFalse(meowLight.isDark)
        assertNotEquals(meowLight.signal, meowLight.onSignal)
        assertNotEquals(meowDark.signal, meowDark.onSignal)
        assertNotEquals(clLight.paper, meowLight.paper)
        assertNotEquals(meowLight.paper, meowDark.paper)

        val healthy = KeepAliveStatus(
            masterEnabled = true,
            accessibilityEnabled = true,
            accessibilityConnected = true,
            foregroundActive = true,
            batteryExempt = true,
            backgroundRestricted = false,
            notificationsVisible = true,
            notificationPermissionGranted = true,
            notificationChannelReady = true,
            oemKeepAliveConfirmed = true
        )

        val catNeedPerm = ConsoleFormatter.formatMeowCat(healthy.copy(accessibilityEnabled = false), false, 0)
        assertEquals(ConsoleFormatter.CatMood.NEED_PERMISSION, catNeedPerm.mood)
        assertEquals(ConsoleFormatter.MeowCatPose.SLEEPING, catNeedPerm.pose)
        assertFalse(catNeedPerm.isAwake)
        assertTrue(catNeedPerm.speechText.contains("无障碍"))

        val catConnecting = ConsoleFormatter.formatMeowCat(healthy.copy(accessibilityConnected = false), false, 0)
        assertEquals(ConsoleFormatter.CatMood.ATTENTIVE_WARN, catConnecting.mood)
        assertEquals(ConsoleFormatter.MeowCatPose.SLEEPING, catConnecting.pose)
        assertFalse(catConnecting.isAwake)

        val catPaused = ConsoleFormatter.formatMeowCat(healthy.copy(masterEnabled = false), false, 0)
        assertEquals(ConsoleFormatter.CatMood.SLEEPING_PAUSED, catPaused.mood)
        assertEquals(ConsoleFormatter.MeowCatPose.SLEEPING, catPaused.pose)
        assertFalse(catPaused.isAwake)

        val catObserve = ConsoleFormatter.formatMeowCat(healthy, observeOnly = true, todaySkipped = 3)
        assertEquals(ConsoleFormatter.CatMood.ATTENTIVE_WARN, catObserve.mood)
        assertEquals(ConsoleFormatter.MeowCatPose.SLEEPING, catObserve.pose)
        assertFalse(catObserve.isAwake)

        val catNoBattery = ConsoleFormatter.formatMeowCat(healthy.copy(batteryExempt = false), false, 0)
        assertEquals(ConsoleFormatter.MeowCatPose.SLEEPING, catNoBattery.pose)
        assertFalse(catNoBattery.isAwake)

        val catBgRestricted = ConsoleFormatter.formatMeowCat(healthy.copy(backgroundRestricted = true), false, 0)
        assertEquals(ConsoleFormatter.MeowCatPose.SLEEPING, catBgRestricted.pose)
        assertFalse(catBgRestricted.isAwake)

        val catNoNotif = ConsoleFormatter.formatMeowCat(healthy.copy(notificationsVisible = false), false, 0)
        assertEquals(ConsoleFormatter.MeowCatPose.SLEEPING, catNoNotif.pose)
        assertFalse(catNoNotif.isAwake)

        val unconfirmedOem = healthy.copy(oemKeepAliveConfirmed = false)
        val catUnconfirmedOem = ConsoleFormatter.formatMeowCat(unconfirmedOem, false, 0)
        assertEquals(ConsoleFormatter.MeowCatPose.SLEEPING, catUnconfirmedOem.pose)
        assertFalse(catUnconfirmedOem.isAwake)
        assertFalse(ConsoleFormatter.isCatAwakeReady(unconfirmedOem, observeOnly = false))
        val hudUnconfirmedOem = ConsoleFormatter.formatEndfieldHud(unconfirmedOem, observeOnly = false)
        assertEquals("GUARD", hudUnconfirmedOem.statusCode)
        assertFalse(hudUnconfirmedOem.syncPercent == 100)

        val missingFgNotif = healthy.copy(foregroundActive = false)
        assertFalse(ConsoleFormatter.isCatAwakeReady(missingFgNotif, observeOnly = false))
        assertEquals("GUARD", ConsoleFormatter.formatEndfieldHud(missingFgNotif, observeOnly = false).statusCode)

        assertTrue(ConsoleFormatter.isCatAwakeReady(healthy, observeOnly = false))
        assertFalse(ConsoleFormatter.isCatAwakeReady(healthy.copy(batteryExempt = false), observeOnly = false))
        assertFalse(ConsoleFormatter.isCatAwakeReady(healthy, observeOnly = true))

        val pickedAll = (0 until 4).map { ConsoleFormatter.pickRandomAwakePose(null, it) }.toSet()
        assertEquals(4, pickedAll.size)
        assertFalse(pickedAll.contains(ConsoleFormatter.MeowCatPose.SLEEPING))

        for (prev in ConsoleFormatter.AWAKE_CAT_POSES) {
            for (r in 0..5) {
                val next = ConsoleFormatter.pickRandomAwakePose(prev, r)
                assertNotEquals(prev, next)
                assertTrue(next in ConsoleFormatter.AWAKE_CAT_POSES)
            }
        }

        val catHappyZero = ConsoleFormatter.formatMeowCat(
            healthy,
            observeOnly = false,
            todaySkipped = 0,
            preferredAwakePose = ConsoleFormatter.MeowCatPose.AWAKE_WALK
        )
        assertEquals(ConsoleFormatter.CatMood.HAPPY_GUARD, catHappyZero.mood)
        assertEquals(ConsoleFormatter.MeowCatPose.AWAKE_WALK, catHappyZero.pose)
        assertTrue(catHappyZero.isAwake)
        assertTrue(catHappyZero.speechText.contains("就位"))

        val catHappyWithCount = ConsoleFormatter.formatMeowCat(
            healthy,
            observeOnly = false,
            todaySkipped = 7,
            preferredAwakePose = ConsoleFormatter.MeowCatPose.AWAKE_ROLL
        )
        assertEquals(ConsoleFormatter.CatMood.HAPPY_GUARD, catHappyWithCount.mood)
        assertEquals(ConsoleFormatter.MeowCatPose.AWAKE_ROLL, catHappyWithCount.pose)
        assertTrue(catHappyWithCount.isAwake)
        assertTrue(catHappyWithCount.speechText.contains("7"))

        val hudOnline = ConsoleFormatter.formatEndfieldHud(healthy, observeOnly = false)
        assertEquals("ONLINE", hudOnline.statusCode)
        assertEquals(100, hudOnline.syncPercent)
        assertTrue(hudOnline.isActive)
        assertFalse(hudOnline.isAlert)

        val hudGuard = ConsoleFormatter.formatEndfieldHud(healthy.copy(batteryExempt = false), observeOnly = false)
        assertEquals("GUARD", hudGuard.statusCode)
        assertEquals(75, hudGuard.syncPercent)

        val hudObserve = ConsoleFormatter.formatEndfieldHud(healthy, observeOnly = true)
        assertEquals("OBSERVE", hudObserve.statusCode)

        val hudPaused = ConsoleFormatter.formatEndfieldHud(healthy.copy(masterEnabled = false), observeOnly = false)
        assertEquals("STANDBY", hudPaused.statusCode)
        assertFalse(hudPaused.isActive)

        val hudReconnect = ConsoleFormatter.formatEndfieldHud(healthy.copy(accessibilityConnected = false), observeOnly = false)
        assertEquals("RECONNECT", hudReconnect.statusCode)
        assertTrue(hudReconnect.isAlert)

        val hudOffline = ConsoleFormatter.formatEndfieldHud(healthy.copy(accessibilityEnabled = false), observeOnly = false)
        assertEquals("OFFLINE", hudOffline.statusCode)
        assertEquals(0, hudOffline.syncPercent)
        assertTrue(hudOffline.isAlert)
    }

    @Test
    fun `config audit exposes hidden failures and adapts copy across all three UI themes`() {
        val healthy = KeepAliveStatus(
            masterEnabled = true,
            accessibilityEnabled = true,
            accessibilityConnected = true,
            foregroundActive = true,
            batteryExempt = true,
            backgroundRestricted = false,
            notificationsVisible = true,
            notificationPermissionGranted = true,
            notificationChannelReady = true,
            oemKeepAliveConfirmed = true
        )

        val fullPass = ConsoleFormatter.buildConfigAudit(
            healthy,
            observeOnly = false,
            brandName = "荣耀手机（MagicOS）",
            style = ConsoleFormatter.ThemeStyleOption.ENDFIELD
        )
        assertEquals(5, fullPass.totalCount)
        assertEquals(5, fullPass.passedCount)
        assertTrue(fullPass.canRunLiveTest)
        assertEquals("01", fullPass.items.first().indexLabel)
        assertEquals("05", fullPass.items.last().indexLabel)
        assertTrue(fullPass.items.all { it.status == ConsoleFormatter.AuditItemStatus.PASS })

        val disguised = healthy.copy(
            foregroundActive = false,
            oemKeepAliveConfirmed = false
        )
        val auditDisguised = ConsoleFormatter.buildConfigAudit(
            disguised,
            observeOnly = false,
            brandName = "荣耀手机（MagicOS）",
            style = ConsoleFormatter.ThemeStyleOption.CLASSIC
        )
        assertEquals(3, auditDisguised.passedCount)
        assertEquals("1", auditDisguised.items.first().indexLabel)
        assertEquals(ConsoleFormatter.AuditItemStatus.WARN, auditDisguised.items[1].status)
        assertEquals("前台未拉起", auditDisguised.items[1].statusLabel)
        assertEquals(ConsoleFormatter.AuditItemStatus.WARN, auditDisguised.items[4].status)
        assertEquals(ConsoleFormatter.AuditAction.TOGGLE_OEM_CONFIRM, auditDisguised.items[4].secondaryAction)

        val disabled = healthy.copy(accessibilityEnabled = false, batteryExempt = false, backgroundRestricted = true)
        val auditDisabled = ConsoleFormatter.buildConfigAudit(
            disabled,
            observeOnly = false,
            brandName = "荣耀手机（MagicOS）",
            style = ConsoleFormatter.ThemeStyleOption.MEOW
        )
        assertFalse(auditDisabled.canRunLiveTest)
        assertEquals(ConsoleFormatter.AuditItemStatus.FAIL, auditDisabled.items[0].status)
        assertEquals(ConsoleFormatter.AuditAction.OPEN_RESTRICTED, auditDisabled.items[0].secondaryAction)
        assertEquals(ConsoleFormatter.AuditItemStatus.FAIL, auditDisabled.items[3].status)

        val classicCopy = ConsoleFormatter.themeActionCopy(ConsoleFormatter.ThemeStyleOption.CLASSIC)
        val endfieldCopy = ConsoleFormatter.themeActionCopy(ConsoleFormatter.ThemeStyleOption.ENDFIELD)
        val meowCopy = ConsoleFormatter.themeActionCopy(ConsoleFormatter.ThemeStyleOption.MEOW)
        assertNotEquals(classicCopy.homeCheckLabel, endfieldCopy.homeCheckLabel)
        assertNotEquals(endfieldCopy.homeCheckLabel, meowCopy.homeCheckLabel)
        assertTrue(endfieldCopy.homeCheckLabel.contains("DIAG"))
        assertTrue(meowCopy.homeCheckLabel.contains("猫猫"))
    }
}
