package com.flashskip.experimental

import com.flashskip.core.ForegroundWindow
import com.flashskip.core.SkipStateMachine

internal class SkipActionGate {
    private data class Dispatch(val owner: Any, val window: ForegroundWindow, val atMs: Long)

    private val windows = LinkedHashMap<ForegroundWindow, Dispatch>()
    private var inFlight: Dispatch? = null
    private var lastFinished: Dispatch? = null

    @Synchronized
    fun begin(owner: Any, window: ForegroundWindow, nowMs: Long, selected: () -> Boolean): Boolean {
        if (!selected() || inFlight != null) return false
        val last = lastFinished
        if (last != null && last.owner !== owner && nowMs - last.atMs < HANDOVER_MS) return false
        windows.values.removeAll { nowMs - it.atMs > SkipStateMachine.MAX_WINDOW_MS }
        if (windows[window]?.let { it.owner !== owner } == true) return false
        val dispatch = Dispatch(owner, window, nowMs)
        windows[window] = dispatch
        if (windows.size > MAX_TRACKED_WINDOWS) windows.remove(windows.keys.first())
        inFlight = dispatch
        return true
    }

    @Synchronized
    fun finish(owner: Any, nowMs: Long) {
        val dispatch = inFlight?.takeIf { it.owner === owner } ?: return
        val finished = dispatch.copy(atMs = nowMs)
        windows[dispatch.window] = finished
        lastFinished = finished
        inFlight = null
    }

    private companion object {
        // The older service may have a gesture callback pending after dispatch returns.
        const val HANDOVER_MS = 800L
        const val MAX_TRACKED_WINDOWS = 32
    }
}
