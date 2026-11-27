package com.flashskip.keepalive

enum class KeepAliveState { PAUSED, NEEDS_ACCESSIBILITY, CONNECTING, RUNNING }

data class KeepAliveStatus(
    val masterEnabled: Boolean,
    val accessibilityEnabled: Boolean,
    val accessibilityConnected: Boolean,
    val foregroundActive: Boolean,
    val batteryExempt: Boolean,
    val backgroundRestricted: Boolean,
    val notificationsVisible: Boolean,
    val notificationPermissionGranted: Boolean = notificationsVisible,
    val notificationChannelReady: Boolean = notificationsVisible,
    val oemKeepAliveConfirmed: Boolean = false
) {
    val state: KeepAliveState
        get() = when {
            !masterEnabled -> KeepAliveState.PAUSED
            !accessibilityEnabled -> KeepAliveState.NEEDS_ACCESSIBILITY
            !accessibilityConnected -> KeepAliveState.CONNECTING
            else -> KeepAliveState.RUNNING
        }

    /**
     * Notification readiness must reflect runtime permission, system toggle, channel state,
     * and (once active) whether the ongoing foreground notification actually started.
     */
    val notificationsReady: Boolean
        get() = notificationPermissionGranted &&
            notificationsVisible &&
            (!masterEnabled || !accessibilityConnected || (notificationChannelReady && foregroundActive))

    /**
     * Native background restriction alone cannot verify OEM autostart / background switches.
     * Both native non-restriction and explicit user OEM confirmation are required for a true green state.
     */
    val backgroundReady: Boolean
        get() = !backgroundRestricted && oemKeepAliveConfirmed

    val isKeepAliveHealthy: Boolean
        get() = batteryExempt && notificationsReady && backgroundReady
}

enum class SettingsDestination {
    OEM_BACKGROUND, APP_DETAILS, SYSTEM_SETTINGS
}
