package com.flashskip.data

import android.content.Context
import android.content.SharedPreferences
import com.flashskip.core.SkipStateMachine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists user preferences, per-app exclusion overrides, and local skip metrics
 * (total count, daily count, P50/P95 latency). Zero network usage.
 */
class StatsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val PREFS_NAME = "flashskip_prefs"
        private const val KEY_MASTER = "master_enabled"
        private const val KEY_OBSERVE = "observe_only"
        private const val KEY_WINDOW_MS = "window_ms"
        private const val KEY_DIAG = "diag_nodes"
        private const val KEY_TOTAL_SKIPPED = "stats_total_skipped"
        private const val KEY_TODAY_DATE = "stats_today_date"
        private const val KEY_TODAY_SKIPPED = "stats_today_skipped"
        private const val KEY_LATENCIES = "stats_latencies"
        private const val KEY_DISABLED_PKGS = "user_disabled_packages"
        private const val MAX_LATENCY_SAMPLES = 100
    }

    var masterEnabled: Boolean
        get() = prefs.getBoolean(KEY_MASTER, true)
        set(value) = prefs.edit().putBoolean(KEY_MASTER, value).apply()

    var observeOnly: Boolean
        get() = prefs.getBoolean(KEY_OBSERVE, false)
        set(value) = prefs.edit().putBoolean(KEY_OBSERVE, value).apply()

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

    fun getUserDisabledPackages(): Set<String> =
        prefs.getStringSet(KEY_DISABLED_PKGS, emptySet())?.toSet() ?: emptySet()

    fun setPackageDisabled(pkg: String, disabled: Boolean) {
        val current = getUserDisabledPackages().toMutableSet()
        if (disabled) current.add(pkg) else current.remove(pkg)
        prefs.edit().putStringSet(KEY_DISABLED_PKGS, current).apply()
    }

    @Synchronized
    fun recordVerifiedSkip(latencyMs: Long) {
        val today = todayString()
        val savedDate = prefs.getString(KEY_TODAY_DATE, "")
        val prevToday = if (savedDate == today) prefs.getInt(KEY_TODAY_SKIPPED, 0) else 0
        val total = prefs.getInt(KEY_TOTAL_SKIPPED, 0) + 1

        val samples = loadLatenciesLocked().toMutableList()
        samples.add(latencyMs.coerceAtLeast(0L))
        val trimmed = if (samples.size > MAX_LATENCY_SAMPLES) {
            samples.takeLast(MAX_LATENCY_SAMPLES)
        } else {
            samples
        }

        prefs.edit()
            .putInt(KEY_TOTAL_SKIPPED, total)
            .putString(KEY_TODAY_DATE, today)
            .putInt(KEY_TODAY_SKIPPED, prevToday + 1)
            .putString(KEY_LATENCIES, trimmed.joinToString(","))
            .apply()
    }

    @Synchronized
    fun getSummary(): StatsSummary {
        val today = todayString()
        val savedDate = prefs.getString(KEY_TODAY_DATE, "")
        val todayCount = if (savedDate == today) prefs.getInt(KEY_TODAY_SKIPPED, 0) else 0
        val total = prefs.getInt(KEY_TOTAL_SKIPPED, 0)
        val sorted = loadLatenciesLocked().sorted()
        val p50 = percentile(sorted, 0.50)
        val p95 = percentile(sorted, 0.95)
        return StatsSummary(
            totalSkipped = total,
            todaySkipped = todayCount,
            p50Ms = p50,
            p95Ms = p95,
            sampleCount = sorted.size
        )
    }

    private fun loadLatenciesLocked(): List<Long> {
        val raw = prefs.getString(KEY_LATENCIES, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(",").mapNotNull { it.toLongOrNull() }
    }

    private fun percentile(sorted: List<Long>, p: Double): Long {
        if (sorted.isEmpty()) return -1L
        val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }

    private fun todayString(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}

data class StatsSummary(
    val totalSkipped: Int,
    val todaySkipped: Int,
    val p50Ms: Long,
    val p95Ms: Long,
    val sampleCount: Int
)
