package com.flashskip.a11y

import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.flashskip.core.RuleIndex
import com.flashskip.core.SkipMatcher
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** On-demand experiment. The window reads here never enter the production matching pipeline. */
@Suppress("DEPRECATION")
internal class WindowTrace(private val engine: String) {
    private val packages = ConcurrentHashMap<String, Int>()
    private val nextPackage = AtomicInteger()
    private val ruleIndex by lazy { RuleIndex.defaultBuiltin() }
    private var nextProbeAt = 0L

    fun event(event: AccessibilityEvent, worker: Handler, probe: (Long, Long) -> Unit) {
        if (!enabled()) return
        val type = event.eventType
        val at = event.eventTime
        val changes = if (Build.VERSION.SDK_INT >= 28 && type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            event.windowChanges
        } else 0
        write("event t=$at eventType=${AccessibilityEvent.eventTypeToString(type)} windowId=${event.windowId} " +
            "changes=$changes contentChanges=${event.contentChangeTypes} pkg=${alias(event.packageName)} full=${event.isFullScreen}")
        val now = SystemClock.uptimeMillis()
        if (now < nextProbeAt || type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            !(type == AccessibilityEvent.TYPE_WINDOWS_CHANGED && event.mayChangeForeground())
        ) return
        nextProbeAt = now + OFFSETS.last() + 300L
        OFFSETS.forEach { offset ->
            worker.postAtTime({ if (enabled()) probe(at, offset) }, now + offset)
        }
    }

    fun windows(triggerAt: Long, offset: Long, width: Int, height: Int,
                active: () -> AccessibilityNodeInfo?, all: () -> List<AccessibilityWindowInfo>) {
        val readAt = SystemClock.uptimeMillis()
        val activeRoot = runCatching(active).getOrNull()
        try {
            write("probe t=$readAt trigger=$triggerAt offset=$offset activeId=${activeRoot?.windowId ?: -1} " +
                "activePkg=${alias(activeRoot?.packageName)}")
        } finally {
            activeRoot?.recycle()
        }
        val windows = runCatching(all).getOrDefault(emptyList())
        try {
            for (window in windows) {
                val before = SystemClock.uptimeMillis()
                val root = runCatching {
                    if (Build.VERSION.SDK_INT >= 33) window.getRoot(0) else window.root
                }.getOrNull()
                val id = root?.windowId ?: -1
                val rawBounds = root?.let { android.graphics.Rect().also(it::getBoundsInScreen).bounds() }
                val pkg = alias(root?.packageName)
                val candidate = runCatching {
                    root?.let {
                        NodeExtractor.extract(it, "", width, height, maxNodes = 350).use { tree ->
                            val snapshot = tree.snapshot
                            val match = SkipMatcher.findBestMatch(snapshot, ruleIndex)
                            val hints = snapshot.nodes.count { node ->
                                node.isVisibleToUser && SkipMatcher.isGenericSkipText(node.effectiveText)
                            }
                            "nodes=${snapshot.nodes.size} complete=${snapshot.complete} full=${snapshot.isFullScreen} " +
                                "visibleSkip=$hints match=${match != null}"
                        }
                    } ?: "nodes=0 complete=false full=false visibleSkip=0 match=false"
                }.getOrDefault("readError=true")
                val display = if (Build.VERSION.SDK_INT >= 30) window.displayId else 0
                write("window t=$before trigger=$triggerAt offset=$offset windowId=${window.id} " +
                    "windowType=${window.type} layer=${window.layer} display=$display active=${window.isActive} " +
                    "focused=${window.isFocused} frame=${window.screenRect()} rootBounds=$rawBounds " +
                    "rootId=$id rootPkg=$pkg readMs=${SystemClock.uptimeMillis() - before} $candidate")
            }
        } finally {
            windows.forEach { it.recycle() }
        }
    }

    private fun alias(pkg: CharSequence?): Int =
        if (pkg.isNullOrBlank()) 0 else packages.computeIfAbsent(pkg.toString()) { nextPackage.incrementAndGet() }

    private fun enabled() = Log.isLoggable(TAG, Log.DEBUG)

    private fun write(fields: String) = Log.d(TAG, "engine=$engine $fields")

    companion object {
        private const val TAG = "FlashSkipWindows"
        private val OFFSETS = longArrayOf(0, 120, 400, 850, 1600, 2400)
    }
}
