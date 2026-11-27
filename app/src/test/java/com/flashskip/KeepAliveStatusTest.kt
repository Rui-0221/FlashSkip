package com.flashskip

import com.flashskip.keepalive.KeepAliveState
import com.flashskip.keepalive.KeepAliveStatus
import com.flashskip.keepalive.OemBackgroundSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAliveStatusTest {
    private val active = KeepAliveStatus(
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

    @Test
    fun explicitPauseTakesPriorityOverSystemStatus() {
        assertEquals(KeepAliveState.PAUSED, active.copy(masterEnabled = false).state)
        assertEquals(KeepAliveState.NEEDS_ACCESSIBILITY, active.copy(accessibilityEnabled = false).state)
        assertEquals(KeepAliveState.CONNECTING, active.copy(accessibilityConnected = false).state)
    }

    @Test
    fun notificationsReadyAndBackgroundReadyRejectFakeGreens() {
        assertTrue(active.notificationsReady)
        assertTrue(active.backgroundReady)
        assertTrue(active.isKeepAliveHealthy)

        // Connected + enabled, but runtime notification permission or channel or foreground service missing
        assertFalse(active.copy(notificationPermissionGranted = false).notificationsReady)
        assertFalse(active.copy(notificationChannelReady = false).notificationsReady)
        assertFalse(active.copy(foregroundActive = false).notificationsReady)
        assertFalse(active.copy(foregroundActive = false).isKeepAliveHealthy)

        // When accessibility is not connected yet, notification permission alone is allowed
        assertTrue(active.copy(accessibilityConnected = false, foregroundActive = false).notificationsReady)

        // Native backgroundRestricted == false alone cannot pass backgroundReady without OEM confirmation
        assertFalse(active.copy(oemKeepAliveConfirmed = false).backgroundReady)
        assertFalse(active.copy(oemKeepAliveConfirmed = false).isKeepAliveHealthy)
    }

    @Test
    fun lackOfBackgroundProtectionDoesNotHideAWorkingConnection() {
        val limited = active.copy(
            foregroundActive = false,
            batteryExempt = false,
            backgroundRestricted = true,
            notificationsVisible = false
        )
        assertEquals(KeepAliveState.RUNNING, limited.state)
        assertFalse(limited.foregroundActive)
        assertTrue(limited.backgroundRestricted)
    }

    @Test
    fun honorPrefersNewSystemManagerAndKeepsItsManualGuide() {
        val honor = OemBackgroundSettings.forDevice("HUAWEI", "HONOR")
        assertEquals(OemBackgroundSettings.HONOR, honor)
        assertEquals("com.hihonor.systemmanager", honor.pages.first().packageName)
        assertEquals("hihonor.intent.action.HSM_STARTUPAPP_MANAGER", honor.pages.first().action)
        assertTrue(honor.guide.steps.any { "允许后台活动" in it })
        assertEquals(OemBackgroundSettings.HUAWEI, OemBackgroundSettings.forDevice("HUAWEI", "HUAWEI"))
    }

    @Test
    fun brandAliasesPickMatchingPagesAndUnknownDevicesUseSystemFallback() {
        assertEquals(OemBackgroundSettings.XIAOMI, OemBackgroundSettings.forDevice("Xiaomi", "POCO"))
        assertEquals(OemBackgroundSettings.COLOR_OS, OemBackgroundSettings.forDevice("OPPO", "OnePlus"))
        assertEquals(OemBackgroundSettings.VIVO, OemBackgroundSettings.forDevice("vivo", "iQOO"))
        val samsung = OemBackgroundSettings.forDevice("samsung electronics", "Samsung")
        assertEquals(2, samsung.pages.single().activityType)
        val unknown = OemBackgroundSettings.forDevice("Example", "NotVivo")
        assertTrue(unknown.pages.isEmpty())
        assertTrue(unknown.guide.steps.any { "电池优化" in it })
    }
}
