package com.flashskip.core

import java.util.concurrent.atomic.AtomicLong

data class ForegroundWindow(val packageName: String, val id: Int) {
    val valid: Boolean get() = packageName.isNotBlank() && id >= 0
}

/** Reconciles wake-up hints with the application root observed by the Android worker. */
class ForegroundEvents {
    private data class WindowSignal(val windowId: Int?, val startedAtMs: Long, var nextProbe: Int = 0)

    @Volatile
    var verifiedWindow: ForegroundWindow? = null
        private set

    private var windowSignal: WindowSignal? = null
    private var lastSignalAtMs = Long.MIN_VALUE / 2
    private var lastSignalWindowId: Int? = null
    private val nextUnarmedCheckAtMs = AtomicLong(Long.MIN_VALUE / 2)

    fun onWindowSignal(windowId: Int?, nowMs: Long) {
        val validId = windowId?.takeIf { it >= 0 }
        val signal = windowSignal
        if (signal != null && nowMs - signal.startedAtMs <= PROBE_DEADLINE_MS &&
            (signal.windowId == validId || validId == null)
        ) return
        if (validId == lastSignalWindowId && nowMs - lastSignalAtMs <= PROBE_DEADLINE_MS) return
        windowSignal = WindowSignal(validId, nowMs)
        lastSignalAtMs = nowMs
        lastSignalWindowId = validId
    }

    fun observeRoot(window: ForegroundWindow?, nowMs: Long): Long? {
        val credible = window?.takeIf { it.valid }
        if (credible != null) verifiedWindow = credible
        val signal = windowSignal ?: return null
        if (nowMs - signal.startedAtMs >= PROBE_DEADLINE_MS ||
            credible != null && (signal.windowId == null || credible.id == signal.windowId)
        ) {
            windowSignal = null
            return null
        }
        while (signal.nextProbe < PROBE_OFFSETS_MS.size) {
            val at = signal.startedAtMs + PROBE_OFFSETS_MS[signal.nextProbe]
            if (at > nowMs) return at
            signal.nextProbe++
        }
        windowSignal = null
        return null
    }

    fun reserveUnarmedContent(window: ForegroundWindow, nowMs: Long): Boolean {
        if (!window.valid || window == verifiedWindow) return false
        val next = nextUnarmedCheckAtMs.get()
        return nowMs >= next && nextUnarmedCheckAtMs.compareAndSet(next, nowMs + CONTENT_CHECK_INTERVAL_MS)
    }

    fun acceptsStateHint(eventWindow: ForegroundWindow, root: ForegroundWindow?): Boolean =
        eventWindow.valid && eventWindow == root

    fun cancelWindowProbes() {
        windowSignal = null
    }

    companion object {
        private const val PROBE_DEADLINE_MS = 160L
        private const val CONTENT_CHECK_INTERVAL_MS = 200L
        private val PROBE_OFFSETS_MS = longArrayOf(32, 80, 140)
    }
}
