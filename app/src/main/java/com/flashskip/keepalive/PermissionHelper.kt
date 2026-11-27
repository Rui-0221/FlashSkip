package com.flashskip.keepalive

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.flashskip.SkipAdService
import com.flashskip.data.StatsStore

/**
 * Exact permission, battery-optimization, and OEM keep-alive helpers.
 */
object PermissionHelper {

    fun readStatus(context: Context): KeepAliveStatus {
        val store = StatsStore(context)
        val notifPermGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager?.getNotificationChannel(SkipAdService.NOTIFICATION_CHANNEL_ID)
        val channelReady = channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE
        val notifVisible = notifPermGranted && manager?.let {
            it.areNotificationsEnabled() &&
                (channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE)
        } == true

        return KeepAliveStatus(
            masterEnabled = store.masterEnabled,
            accessibilityEnabled = isAccessibilityEnabledInSettings(context),
            accessibilityConnected = SkipAdService.connected,
            foregroundActive = SkipAdService.foregroundActive,
            batteryExempt = isIgnoringBatteryOptimizations(context),
            backgroundRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true,
            notificationsVisible = notifVisible,
            notificationPermissionGranted = notifPermGranted,
            notificationChannelReady = channelReady,
            oemKeepAliveConfirmed = store.oemKeepAliveConfirmed
        )
    }

    /**
     * Accurately checks whether [SkipAdService] is enabled in system settings by parsing
     * colon-separated [ComponentName] entries rather than doing substring matching.
     */
    fun isAccessibilityEnabledInSettings(context: Context): Boolean {
        val expected = ComponentName(context, SkipAdService::class.java)
        val enabled = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (_: Exception) {
            null
        } ?: return false

        return enabled.split(':')
            .asSequence()
            .mapNotNull { ComponentName.unflattenFromString(it.trim()) }
            .any { it == expected }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return try {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) {
            false
        }
    }

    fun openAccessibilitySettings(context: Context): Boolean =
        open(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    /**
     * Opens App Info so Android 13+ users can enable "Allow restricted settings" from the top-right menu.
     */
    fun openAppDetailsSettings(context: Context): Boolean =
        open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)))

    fun openBatteryOptimizationSettings(context: Context): Boolean {
        if (!isIgnoringBatteryOptimizations(context) && open(
                context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context))
            )) {
            return true
        }
        return open(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) ||
            openAppDetailsSettings(context) || open(context, Intent(Settings.ACTION_SETTINGS))
    }

    fun openOemBackgroundSettings(context: Context): SettingsDestination? {
        val pages = currentOem().pages
        for (page in pages) {
            val intent = Intent(page.action).apply {
                setPackage(page.packageName)
                page.activity?.let { activity ->
                    component = ComponentName.createRelative(page.packageName, activity)
                }
                page.activityType?.let { putExtra("activity_type", it) }
            }
            if (open(context, intent)) return SettingsDestination.OEM_BACKGROUND
        }
        if (openAppDetailsSettings(context)) return SettingsDestination.APP_DETAILS
        if (open(context, Intent(Settings.ACTION_SETTINGS))) return SettingsDestination.SYSTEM_SETTINGS
        return null
    }

    fun openNotificationSettings(context: Context): Boolean =
        open(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }) || openAppDetailsSettings(context)

    fun backgroundGuide(): KeepAliveGuide = currentOem().guide

    fun oemKeepAliveAdvice(): String = backgroundGuide().steps.joinToString("\n")

    private fun currentOem() = OemBackgroundSettings.forDevice(Build.MANUFACTURER, Build.BRAND)

    private fun packageUri(context: Context): Uri = Uri.fromParts("package", context.packageName, null)

    private fun open(context: Context, intent: Intent): Boolean {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Direct start also works when Android package visibility hides the target from resolveActivity.
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
