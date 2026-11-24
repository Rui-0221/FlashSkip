package com.flashskip.core

import java.util.concurrent.atomic.AtomicLong

data class ForegroundWindow(val packageName: String, val id: Int) {
    val valid: Boolean get() = packageName.isNotBlank() && id >= 0
}

/** Reconciles wake-up hints with the application root observed by the Android worker. */
class ForegroundEvents {
    private data class WindowSignal(
        val windowId: Int?,
        val startedAtMs: Long,
        val previousWindow: ForegroundWindow? = null,
        val foregroundChange: Boolean = false,
        var nextProbe: Int = 0
    )

    private data class ResumeSignal(
        var expected: ForegroundWindow,
        val startedAt: Long,
        var nextProbe: Int = 0
    )

    @Volatile
    var verifiedWindow: ForegroundWindow? = null
        private set

    private var windowSignal: WindowSignal? = null
    private var focusedApplication: ForegroundWindow? = null
    private var resumeSignal: ResumeSignal? = null
    private var unlockedAt = Long.MIN_VALUE / 2
    private val previousApplicationWindows = LinkedHashMap<String, ForegroundWindow>()
    private var lastSignalAtMs = Long.MIN_VALUE / 2
    private var lastSignalWindowId: Int? = null
    private val nextUnarmedCheckAtMs = AtomicLong(Long.MIN_VALUE / 2)

    fun onWindowSignal(windowId: Int?, nowMs: Long) {
        val validId = windowId?.takeIf { it >= 0 }
        val signal = windowSignal
        if (signal?.foregroundChange == true && nowMs - signal.startedAtMs < FOREGROUND_PROBE_DEADLINE_MS) return
        if (signal != null && nowMs - signal.startedAtMs <= PROBE_DEADLINE_MS &&
            (signal.windowId == validId || validId == null)
        ) return
        if (validId == lastSignalWindowId && nowMs - lastSignalAtMs <= PROBE_DEADLINE_MS) return
        windowSignal = WindowSignal(validId, nowMs)
        lastSignalAtMs = nowMs
        lastSignalWindowId = validId
    }

    /** A window-list event may report the window losing focus before the next root exists. */
    fun onForegroundSignal(nowMs: Long) {
        val signal = windowSignal
        if (signal?.foregroundChange == true && nowMs - signal.startedAtMs < FOREGROUND_PROBE_DEADLINE_MS) return
        windowSignal = WindowSignal(null, nowMs, verifiedWindow, foregroundChange = true)
    }

    fun onUnlocked(nowMs: Long) {
        unlockedAt = nowMs
    }

    /** A state event is an identity hint. Android adapters must still prove the live focus and root. */
    fun onResumeWindowHint(window: ForegroundWindow, nowMs: Long) {
        if (!window.valid) return
        val current = resumeSignal
        if (current != null && nowMs - current.startedAt < RESUME_PROBE_MS &&
            current.expected.packageName == window.packageName
        ) {
            current.expected = window
            return
        }
        val previous = focusedApplication
        val entered = previous != null && previous.packageName != window.packageName
        if (!entered && nowMs - unlockedAt >= RESUME_PROBE_MS) return
        unlockedAt = Long.MIN_VALUE / 2
        resumeSignal = ResumeSignal(window, nowMs)
    }

    fun resumeWindow(nowMs: Long): ForegroundWindow? =
        resumeSignal?.takeIf { nowMs - it.startedAt < RESUME_PROBE_MS }?.expected

    /** A cached window only supports closure for the window tracked in the current resume. */
    fun returnWindowFor(origin: ForegroundWindow, actionAtMs: Long): ForegroundWindow? {
        if (resumeWindow(actionAtMs) != origin) return null
        return previousApplicationWindows[origin.packageName]?.takeIf { it != origin }
    }

    /** The adapter has observed a complete, full-screen window with no visible ad signal. */
    fun rememberReturnWindow(window: ForegroundWindow) {
        if (!window.valid || previousApplicationWindows[window.packageName] == window) return
        previousApplicationWindows.remove(window.packageName)
        previousApplicationWindows[window.packageName] = window
        if (previousApplicationWindows.size > 8) {
            previousApplicationWindows.remove(previousApplicationWindows.keys.first())
        }
    }

    /** Called only for verified, full-screen application windows, including the launcher. */
    fun recordFocusedApplication(window: ForegroundWindow): Boolean {
        if (!window.valid) return false
        val previous = focusedApplication
        focusedApplication = window
        return previous != null && previous.packageName != window.packageName
    }

    fun observeRoot(window: ForegroundWindow?, nowMs: Long): Long? {
        val credible = window?.takeIf { it.valid }
        if (credible != null) verifiedWindow = credible
        val regular = nextWindowProbe(credible, nowMs)
        val resume = nextResumeProbe(nowMs)
        return when {
            regular == null -> resume
            resume == null -> regular
            else -> minOf(regular, resume)
        }
    }

    private fun nextResumeProbe(nowMs: Long): Long? {
        val signal = resumeSignal ?: return null
        if (nowMs - signal.startedAt >= RESUME_PROBE_MS) return null
        while (signal.nextProbe < RESUME_PROBE_OFFSETS_MS.size) {
            val due = signal.startedAt + RESUME_PROBE_OFFSETS_MS[signal.nextProbe]
            if (due > nowMs) return due
            signal.nextProbe++
        }
        return null
    }

    private fun nextWindowProbe(credible: ForegroundWindow?, nowMs: Long): Long? {
        val signal = windowSignal ?: return null
        val deadline = if (signal.foregroundChange) FOREGROUND_PROBE_DEADLINE_MS else PROBE_DEADLINE_MS
        val arrived = credible != null && if (signal.foregroundChange) {
            credible != signal.previousWindow
        } else {
            signal.windowId == null || credible.id == signal.windowId
        }
        if (nowMs - signal.startedAtMs >= deadline || arrived) {
            windowSignal = null
            return null
        }
        val offsets = if (signal.foregroundChange) FOREGROUND_PROBE_OFFSETS_MS else PROBE_OFFSETS_MS
        while (signal.nextProbe < offsets.size) {
            val at = signal.startedAtMs + offsets[signal.nextProbe]
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
        resumeSignal?.nextProbe = RESUME_PROBE_OFFSETS_MS.size
    }

    companion object {
        private const val PROBE_DEADLINE_MS = 160L
        private const val FOREGROUND_PROBE_DEADLINE_MS = 600L
        private const val CONTENT_CHECK_INTERVAL_MS = 200L
        private const val RESUME_PROBE_MS = 3800L
        private val RESUME_PROBE_OFFSETS_MS = longArrayOf(140, 450, 900, 1650, 2450, 3400)
        private val PROBE_OFFSETS_MS = longArrayOf(32, 80, 140)
        private val FOREGROUND_PROBE_OFFSETS_MS = longArrayOf(32, 80, 140, 260, 450)
    }
}
