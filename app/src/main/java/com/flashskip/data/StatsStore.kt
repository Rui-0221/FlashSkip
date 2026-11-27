package com.flashskip.data

import android.content.Context
import android.content.SharedPreferences
import com.flashskip.core.SkipStateMachine
import com.flashskip.core.VerifyOutcome
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/**
 * Persists user preferences, per-app exclusion overrides, and local skip metrics
 * (total count, daily count, P50/P95 latency). Zero network usage.
 */
class StatsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val KEY_EXPERIMENTAL_SKIP = "experimental_skip_enabled"
        const val PREFS_NAME = "flashskip_prefs"
        private const val KEY_MASTER = "master_enabled"
        private const val KEY_OBSERVE = "observe_only"
        private const val KEY_WINDOW_MS = "window_ms"
        private const val KEY_DIAG = "diag_nodes"
        private const val KEY_SKIP_TOAST = "skip_toast_enabled"
        private const val KEY_LEGACY_TOTAL = "stats_total_skipped"
        private const val KEY_TOTAL_SKIPPED = "stats_v2_confirmed"
        private const val KEY_TODAY_DATE = "stats_v2_today_date"
        private const val KEY_TODAY_SKIPPED = "stats_v2_today_confirmed"
        private const val KEY_LATENCIES = "stats_v2_event_to_confirm_ms"
        private const val KEY_STILL_PRESENT = "stats_v2_still_present"
        private const val KEY_NAVIGATED = "stats_v2_navigated"
        private const val KEY_UNOBSERVED = "stats_v2_unobserved"
        private const val KEY_DISABLED_PKGS = "user_disabled_packages"
        private const val KEY_UI_THEME_MODE = "ui_theme_mode"
        private const val KEY_UI_THEME_STYLE = "ui_theme_style"
        private const val KEY_OEM_KEEPALIVE_CONFIRMED = "oem_keepalive_confirmed"
        private const val MAX_LATENCY_SAMPLES = 100
    }

    var masterEnabled: Boolean
        get() = prefs.getBoolean(KEY_MASTER, true)
        set(value) = prefs.edit().putBoolean(KEY_MASTER, value).apply()

    fun observeMasterEnabled(onChanged: (Boolean) -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_MASTER || key == null) onChanged(masterEnabled)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    var observeOnly: Boolean
        get() = prefs.getBoolean(KEY_OBSERVE, false)
        set(value) = prefs.edit().putBoolean(KEY_OBSERVE, value).apply()

    var experimentalSkipEnabled: Boolean
        get() = prefs.getBoolean(KEY_EXPERIMENTAL_SKIP, false)
        set(value) = prefs.edit().putBoolean(KEY_EXPERIMENTAL_SKIP, value).apply()

    fun observeSkipSettings(onChanged: () -> Unit) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                KEY_MASTER, KEY_OBSERVE, KEY_WINDOW_MS, KEY_DISABLED_PKGS, KEY_EXPERIMENTAL_SKIP -> onChanged()
                else -> Unit
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        skipSettingsListener = listener
    }

    private var skipSettingsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    var skipToastEnabled: Boolean
        get() = prefs.getBoolean(KEY_SKIP_TOAST, true)
        set(value) = prefs.edit().putBoolean(KEY_SKIP_TOAST, value).apply()

    var windowMs: Long
        get() {
            val raw = prefs.getLong(KEY_WINDOW_MS, SkipStateMachine.DEFAULT_WINDOW_MS)
            return raw.coerceIn(SkipStateMachine.MIN_WINDOW_MS, SkipStateMachine.MAX_WINDOW_MS)
        }
        set(value) {
            val clamped = value.coerceIn(SkipStateMachine.MIN_WINDOW_MS, SkipStateMachine.MAX_WINDOW_MS)
            prefs.edit().putLong(KEY_WINDOW_MS, clamped).apply()
        }

    var diagNodes: Boolean
        get() = prefs.getBoolean(KEY_DIAG, false)
        set(value) = prefs.edit().putBoolean(KEY_DIAG, value).apply()

    var uiThemeMode: Int
        get() = prefs.getInt(KEY_UI_THEME_MODE, -1)
        set(value) = prefs.edit().putInt(KEY_UI_THEME_MODE, value).apply()

    var uiThemeStyle: String
        get() = prefs.getString(KEY_UI_THEME_STYLE, "classic") ?: "classic"
        set(value) = prefs.edit().putString(KEY_UI_THEME_STYLE, value).apply()

    var oemKeepAliveConfirmed: Boolean
        get() = prefs.getBoolean(KEY_OEM_KEEPALIVE_CONFIRMED, false)
        set(value) = prefs.edit().putBoolean(KEY_OEM_KEEPALIVE_CONFIRMED, value).apply()

    fun getUserDisabledPackages(): Set<String> =
        prefs.getStringSet(KEY_DISABLED_PKGS, emptySet())?.toSet() ?: emptySet()

    fun setPackageDisabled(pkg: String, disabled: Boolean) {
        val current = getUserDisabledPackages().toMutableSet()
        if (disabled) current.add(pkg) else current.remove(pkg)
        prefs.edit().putStringSet(KEY_DISABLED_PKGS, current).apply()
    }

    @Synchronized
    fun recordOutcome(outcome: VerifyOutcome, latencyMs: Long?) {
        val outcomeKey = when (outcome) {
            VerifyOutcome.VERIFIED_CLOSED -> KEY_TOTAL_SKIPPED
            VerifyOutcome.STILL_PRESENT -> KEY_STILL_PRESENT
            VerifyOutcome.NAVIGATED -> KEY_NAVIGATED
            VerifyOutcome.UNOBSERVED -> KEY_UNOBSERVED
            VerifyOutcome.IGNORED_STALE -> return
        }
        val editor = prefs.edit().putInt(outcomeKey, prefs.getInt(outcomeKey, 0) + 1)
        if (outcome != VerifyOutcome.VERIFIED_CLOSED) {
            editor.apply()
            return
        }
        val today = todayString()
        val savedDate = prefs.getString(KEY_TODAY_DATE, "")
        val prevToday = if (savedDate == today) prefs.getInt(KEY_TODAY_SKIPPED, 0) else 0
        if (latencyMs != null && latencyMs >= 0) {
            val samples = (loadLatenciesLocked() + latencyMs).takeLast(MAX_LATENCY_SAMPLES)
            editor.putString(KEY_LATENCIES, samples.joinToString(","))
        }
        editor
            .putString(KEY_TODAY_DATE, today)
            .putInt(KEY_TODAY_SKIPPED, prevToday + 1)
            .apply()
    }

    @Synchronized
    fun getSummary(): StatsSummary {
        val today = todayString()
        val savedDate = prefs.getString(KEY_TODAY_DATE, "")
        val todayCount = if (savedDate == today) prefs.getInt(KEY_TODAY_SKIPPED, 0) else 0
        val total = prefs.getInt(KEY_TOTAL_SKIPPED, 0)
        val sorted = loadLatenciesLocked().sorted()
        val p50 = percentile(sorted, 0.50, minSamples = 5)
        val p95 = percentile(sorted, 0.95, minSamples = 20)
        return StatsSummary(
            totalSkipped = total,
            todaySkipped = todayCount,
            p50Ms = p50,
            p95Ms = p95,
            sampleCount = sorted.size,
            legacyTotalSkipped = prefs.getInt(KEY_LEGACY_TOTAL, 0),
            stillPresentAttempts = prefs.getInt(KEY_STILL_PRESENT, 0),
            navigatedAttempts = prefs.getInt(KEY_NAVIGATED, 0),
            unobservedAttempts = prefs.getInt(KEY_UNOBSERVED, 0)
        )
    }

    private fun loadLatenciesLocked(): List<Long> {
        val raw = prefs.getString(KEY_LATENCIES, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(",").mapNotNull { it.toLongOrNull() }
    }

    private fun percentile(sorted: List<Long>, p: Double, minSamples: Int): Long {
        if (sorted.size < minSamples) return -1L
        return sorted[ceil(sorted.size * p).toInt() - 1]
    }

    private fun todayString(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}

data class StatsSummary(
    val totalSkipped: Int,
    val todaySkipped: Int,
    val p50Ms: Long,
    val p95Ms: Long,
    val sampleCount: Int,
    val legacyTotalSkipped: Int,
    val stillPresentAttempts: Int,
    val navigatedAttempts: Int,
    val unobservedAttempts: Int
)
