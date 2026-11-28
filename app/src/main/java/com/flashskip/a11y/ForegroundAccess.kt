package com.flashskip.a11y

import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.flashskip.core.ForegroundWindow

internal fun AccessibilityEvent.mayChangeForeground(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
    val changes = AccessibilityEvent.WINDOWS_CHANGE_ACTIVE or AccessibilityEvent.WINDOWS_CHANGE_FOCUSED or
        AccessibilityEvent.WINDOWS_CHANGE_ADDED or AccessibilityEvent.WINDOWS_CHANGE_REMOVED
    return windowChanges and changes != 0
}

internal fun AccessibilityEvent.changesWindowGeometry(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
    windowChanges and (AccessibilityEvent.WINDOWS_CHANGE_BOUNDS or AccessibilityEvent.WINDOWS_CHANGE_LAYER) != 0

internal fun AccessibilityWindowInfo.isFocusedApp(): Boolean =
    isFocused && type == AccessibilityWindowInfo.TYPE_APPLICATION && id >= 0 &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || displayId == Display.DEFAULT_DISPLAY)

internal fun AccessibilityWindowInfo.isFocusedUnmapped(expectedId: Int?): Boolean =
    expectedId != null && id == expectedId && type == -1 && isActive && isFocused &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || displayId == Display.DEFAULT_DISPLAY)

@Suppress("DEPRECATION")
internal fun List<AccessibilityWindowInfo>.readFocusedUnmapped(expected: ForegroundWindow): AccessibilityNodeInfo? {
    return try {
        val focused = singleOrNull { it.isFocused } ?: return null
        if (!focused.isFocusedUnmapped(expected.id) || any { it.id != focused.id && it.isActive }) return null
        val root = (if (Build.VERSION.SDK_INT >= 33) focused.getRoot(0) else focused.root) ?: return null
        if (root.windowId == expected.id && root.packageName?.toString() == expected.packageName) {
            root
        } else {
            root.recycle()
            null
        }
    } finally {
        forEach { it.recycle() }
    }
}

@Suppress("DEPRECATION")
internal fun List<AccessibilityWindowInfo>.showsReturnTo(originId: Int, destinationId: Int, captionId: Int? = null): Boolean {
    return try {
        withFocusedAppWindow { focused ->
            focused.id == destinationId && none { window ->
                window.id == originId || window.id != captionId && window.layer > focused.layer &&
                    (window.type == AccessibilityWindowInfo.TYPE_APPLICATION || window.type == -1)
            }
        } == true
    } catch (_: IllegalStateException) {
        false
    }
}

internal fun AccessibilityNodeInfo.hasVisibleNode(viewId: String): Boolean {
    val nodes = runCatching { findAccessibilityNodeInfosByViewId(viewId) }.getOrNull() ?: return false
    return try {
        nodes.any { it.isVisibleToUser && it.windowId == windowId }
    } finally {
        @Suppress("DEPRECATION")
        nodes.forEach { it.recycle() }
    }
}

internal fun <T> List<AccessibilityWindowInfo>.withFocusedAppWindow(read: (AccessibilityWindowInfo) -> T): T? {
    return try {
        val focused = singleOrNull { it.isFocused } ?: return null
        if (!focused.isFocusedApp() || any { it.id != focused.id && it.isActive }) return null
        read(focused)
    } finally {
        @Suppress("DEPRECATION")
        forEach { it.recycle() }
    }
}
