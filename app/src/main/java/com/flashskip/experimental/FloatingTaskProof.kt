package com.flashskip.experimental

import android.os.SystemClock
import com.flashskip.a11y.SkipTrace
import com.flashskip.core.ForegroundWindow
import com.flashskip.core.FreeformViewport
import com.flashskip.core.RectBounds

/** UiAutomation may expose a caption window without letting shell read its SystemUI tree. */
internal class FloatingTaskProof {
    private data class Request(val window: ForegroundWindow, val caption: Int, val frame: RectBounds, val unknown: Boolean)
    private var lastRequest: Request? = null
    private var lastRead = 0L
    private var lastResult = false

    fun verify(window: ForegroundWindow, viewport: FreeformViewport, resumedUnknown: Boolean): Boolean {
        val request = Request(window, viewport.captionId, viewport.frame, resumedUnknown)
        val now = SystemClock.uptimeMillis()
        if (lastRequest == request && now - lastRead < CACHE_MS) return lastResult
        lastRequest = request
        lastRead = now
        lastResult = runCatching {
            val command = ProcessBuilder("/system/bin/dumpsys", "-t", "1", "window", "-a")
                .redirectErrorStream(true).start()
            try {
                command.inputStream.bufferedReader().useLines { lines ->
                    hasFocusedFloatingTask(lines, window.packageName, viewport, resumedUnknown)
                }
            } finally {
                command.destroy()
            }
        }.onFailure { error ->
            SkipTrace.log("shizuku") { "phase=wm_proof result=${error.javaClass.simpleName}" }
        }.getOrDefault(false)
        return lastResult
    }

    fun clear() {
        lastRequest = null
    }

    private companion object {
        const val CACHE_MS = 1200L
    }
}

internal fun hasFocusedFloatingTask(
    lines: Sequence<String>, pkg: String, viewport: FreeformViewport, resumedUnknown: Boolean
): Boolean {
    val focusPattern = Regex("""mCurrentFocus=(Window\{[^\r\n]+\})""")
    val windowPattern = Regex("""Window #\d+ (Window\{[^\r\n]+\}):""")
    val typePattern = Regex("""\bty=(BASE_APPLICATION|99)(?:\s|\})""")
    val framePattern = Regex("""\bframe=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""")
    var focused: String? = null
    var inFocused = false
    var freeform = false
    var frame: RectBounds? = null
    var allowedType = false
    var ownedByPackage = false
    for (raw in lines.take(18000)) {
        val line = raw.trim()
        focusPattern.matchEntire(line)?.let {
            if (focused != null) return false
            focused = it.groupValues[1]
        }
        val entry = windowPattern.matchEntire(line)
        if (entry != null) {
            if (inFocused) break
            inFocused = entry.groupValues[1] == focused
            continue
        }
        if (!inFocused) continue
        if (line.startsWith("mOwnerUid=")) {
            ownedByPackage = " package=$pkg " in line
        }
        if (line.startsWith("mAttrs=")) {
            val type = typePattern.find(line)?.groupValues?.get(1)
            allowedType = type == "BASE_APPLICATION" || resumedUnknown && type == "99"
        }
        if (line.startsWith("mFullConfiguration=")) freeform = " mWindowingMode=freeform " in line
        if (line.startsWith("Frames:")) {
            val m = framePattern.find(line) ?: return false
            frame = RectBounds(m.groupValues[1].toInt(), m.groupValues[2].toInt(),
                m.groupValues[3].toInt(), m.groupValues[4].toInt())
        }
    }
    val focusedOwner = inFocused && ownedByPackage && focused?.contains(" $pkg/") == true
    val aligned = frame != null && FreeformViewport.aligned(viewport.frame, frame, Int.MAX_VALUE, Int.MAX_VALUE)
    return focusedOwner && allowedType && freeform && aligned
}
