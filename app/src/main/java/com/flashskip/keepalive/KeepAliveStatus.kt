package com.flashskip.keepalive

enum class KeepAliveState { PAUSED, NEEDS_ACCESSIBILITY, CONNECTING, RUNNING }

data class KeepAliveStatus(
    val masterEnabled: Boolean,
    val accessibilityEnabled: Boolean,
    val accessibilityConnected: Boolean,
    val foregroundActive: Boolean,
    val batteryExempt: Boolean,
    val backgroundRestricted: Boolean,
    val notificationsVisible: Boolean
) {
    val state: KeepAliveState
        get() = when {
            !masterEnabled -> KeepAliveState.PAUSED
            !accessibilityEnabled -> KeepAliveState.NEEDS_ACCESSIBILITY
            !accessibilityConnected -> KeepAliveState.CONNECTING
            else -> KeepAliveState.RUNNING
        }
}

enum class SettingsDestination {
    OEM_BACKGROUND, APP_DETAILS, SYSTEM_SETTINGS
}
