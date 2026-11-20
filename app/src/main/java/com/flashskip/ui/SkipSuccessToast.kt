package com.flashskip.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.flashskip.R
import com.flashskip.data.StatsStore
import java.lang.ref.WeakReference

/**
 * Pure JVM-testable debounce gate so consecutive verified ad closes coalesce into
 * at most one toast within [cooldownMs].
 */
class SkipToastGate(private val cooldownMs: Long = DEFAULT_COOLDOWN_MS) {

    companion object {
        const val DEFAULT_COOLDOWN_MS = 1800L
    }

    private var lastShownAtMs: Long = Long.MIN_VALUE / 2

    @Synchronized
    fun shouldShow(nowUptimeMs: Long): Boolean {
        if (nowUptimeMs - lastShownAtMs < cooldownMs) return false
        lastShownAtMs = nowUptimeMs
        return true
    }

    @Synchronized
    fun reset() {
        lastShownAtMs = Long.MIN_VALUE / 2
    }
}

/**
 * Lightweight, silent, non-focusable, non-touchable system Toast shown strictly after
 * an ad close is verified ([com.flashskip.core.VerifyOutcome.VERIFIED_CLOSED]).
 * Requires zero overlay permissions and never blocks clicks or scrolling in foreground apps.
 */
object SkipSuccessToast {

    private const val FAST_DISMISS_MS = 1350L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val gate = SkipToastGate()
    private var activeToast: WeakReference<Toast>? = null
    private var dismissRunnable: Runnable? = null

    fun onVerifiedClosed(context: Context) {
        val appContext = context.applicationContext
        if (!StatsStore(appContext).skipToastEnabled) return
        val now = SystemClock.uptimeMillis()
        if (!gate.shouldShow(now)) return

        val showTask = Runnable { showInternal(appContext) }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showTask.run()
        } else {
            mainHandler.post(showTask)
        }
    }

    private fun showInternal(appContext: Context) {
        try {
            dismissRunnable?.let { mainHandler.removeCallbacks(it) }
            activeToast?.get()?.cancel()

            val toast = Toast.makeText(
                appContext,
                appContext.getString(R.string.skip_success_toast),
                Toast.LENGTH_SHORT
            )
            activeToast = WeakReference(toast)
            toast.show()

            val cancelTask = Runnable {
                try {
                    if (activeToast?.get() === toast) {
                        toast.cancel()
                        activeToast = null
                    }
                } catch (_: Exception) {
                }
            }
            dismissRunnable = cancelTask
            mainHandler.postDelayed(cancelTask, FAST_DISMISS_MS)
        } catch (_: Exception) {
        }
    }
}
