package com.flashskip.a11y

import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.flashskip.core.ForegroundWindow

/** Keeps one root identity and the latest compatible event while the service is armed. */
@Suppress("DEPRECATION")
internal class ContentWindowRoot {
    data class SourceRoot(val node: AccessibilityNodeInfo, val ancestorSteps: Int)

    private val lock = Any()
    private var expected: AccessibilityNodeInfo? = null
    private var latestEvent: AccessibilityEvent? = null

    fun offer(event: AccessibilityEvent, window: ForegroundWindow): Boolean = synchronized(lock) {
        val root = expected ?: return@synchronized false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || window.id != root.windowId ||
            window.packageName != root.packageName?.toString()) return@synchronized false
        val subtreeChanged = event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE != 0
        if (!subtreeChanged && event.className?.toString() != root.className?.toString()) return@synchronized false
        val previous = latestEvent
        latestEvent = AccessibilityEvent.obtain(event)
        previous?.recycle()
        true
    }

    /** Called on the Worker. The caller still validates the current focused window. */
    fun acquire(window: ForegroundWindow, eventTime: Long, prefetch: Int): SourceRoot? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val (root, event) = synchronized(lock) {
            val stored = latestEvent ?: return null
            if (stored.eventTime > eventTime) return null
            latestEvent = null
            expected to stored
        }
        try {
            if (root == null || event.eventTime != eventTime || event.windowId != window.id ||
                root.windowId != window.id || root.packageName?.toString() != window.packageName) return null
            val ancestors = AccessibilityNodeInfo.FLAG_PREFETCH_ANCESTORS or
                AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE
            var node = runCatching { event.getSource(prefetch or ancestors) }.getOrNull() ?: return null
            for (steps in 0..MAX_ANCESTOR_STEPS) {
                val sameWindow = node.windowId == window.id && node.packageName?.toString() == window.packageName
                if (sameWindow && node == root && node.isVisibleToUser) return SourceRoot(node, steps)
                val parent = try {
                    if (sameWindow && steps < MAX_ANCESTOR_STEPS) node.getParent(ancestors) else null
                } catch (_: Exception) {
                    null
                } finally {
                    node.recycle()
                }
                node = parent ?: return null
            }
            return null
        } finally {
            event.recycle()
        }
    }

    fun remember(root: AccessibilityNodeInfo) = synchronized(lock) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && expected != root) {
            val identity = AccessibilityNodeInfo.obtain(root)
            clearLocked()
            expected = identity
        }
    }

    fun clear() = synchronized(lock) { clearLocked() }

    private fun clearLocked() {
        latestEvent?.recycle()
        expected?.recycle()
        latestEvent = null
        expected = null
    }

    private companion object {
        const val MAX_ANCESTOR_STEPS = 12
    }
}
