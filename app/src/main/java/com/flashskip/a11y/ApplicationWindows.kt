package com.flashskip.a11y

import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.TypedValue
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.flashskip.core.ForegroundWindow
import com.flashskip.core.FreeformViewport
import com.flashskip.core.PackagePolicy
import com.flashskip.core.RectBounds
import com.flashskip.core.containsCenter

internal data class AppSurface(val freeform: FreeformViewport? = null)

/** Coordinates from live windows are never inferred from the dimensions of an ad control. */
@Suppress("DEPRECATION")
internal class ApplicationWindows(
    private val context: Context,
    private val verifyHiddenCaption: ((ForegroundWindow, FreeformViewport, Boolean) -> Boolean)? = null,
    private val listWindows: () -> List<AccessibilityWindowInfo>
) {
    private val activities = LinkedHashMap<ForegroundWindow, String>()
    private val declared = LinkedHashMap<ComponentName, Boolean>()

    fun state(window: ForegroundWindow, cls: String) {
        if (!window.valid) return
        if (PackagePolicy.isTransientOverlay(window.packageName, cls, false)) {
            activities.remove(window)
        } else if (cls.contains('.') && !cls.startsWith("android.") && !cls.startsWith("androidx.")) {
            if (activities.size >= 24 && window !in activities) activities.remove(activities.keys.first())
            activities[window] = cls
        }
    }

    fun clear() = activities.clear()

    fun verifiedActivity(window: ForegroundWindow): String? =
        activities[window]?.takeIf { isActivity(window) }

    fun canDispatch(root: AccessibilityNodeInfo, width: Int, height: Int, unmapped: ForegroundWindow?,
                    tree: ExtractedTree, target: RectBounds): Boolean {
        if (root.windowId != tree.windowId || root.packageName?.toString() != tree.snapshot.packageName) return false
        val live = inspect(root, width, height, unmapped) ?: return false
        val original = tree.snapshot.freeform
        if (original != null) {
            val viewport = live.freeform ?: return false
            if (!original.sameTask(viewport) || original.nodeRoot != viewport.nodeRoot) return false
            return viewport.screenTarget(target) != null
        }
        if (live.freeform != null) return false
        val windows = listWindows()
        return try {
            val owner = windows.singleOrNull { it.isFocused } ?: return false
            owner.id == root.windowId && windows.none { it.id != owner.id && (it.isActive ||
                it.layer > owner.layer && it.screenRect().containsCenter(target)) }
        } finally {
            windows.forEach { it.recycle() }
        }
    }

    private fun isActivity(window: ForegroundWindow): Boolean {
        val component = ComponentName(window.packageName, activities[window] ?: return false)
        return declared[component] ?: run {
            val valid = runCatching {
                val pm = context.packageManager
                val activity = pm.getActivityInfo(component, 0)
                val theme = pm.getResourcesForApplication(activity.applicationInfo).newTheme()
                theme.applyStyle(activity.themeResource, true)
                val value = TypedValue()
                !theme.resolveAttribute(android.R.attr.windowIsFloating, value, true) || value.data == 0
            }.getOrDefault(false)
            if (declared.size >= 32) declared.remove(declared.keys.first())
            declared[component] = valid
            valid
        }
    }

    fun inspect(root: AccessibilityNodeInfo, width: Int, height: Int, unmapped: ForegroundWindow?): AppSurface? {
        return try {
            readSurface(root, width, height, unmapped)
        } catch (_: IllegalStateException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun readSurface(root: AccessibilityNodeInfo, width: Int, height: Int, unmapped: ForegroundWindow?): AppSurface? {
        if (!root.isVisibleToUser) return null
        val id = ForegroundWindow(root.packageName?.toString().orEmpty(), root.windowId)
        if (!id.valid) return null
        val owner = root.window ?: return null
        var resumedUnknown: Boolean
        val frame = try {
            resumedUnknown = id == unmapped && owner.isFocusedUnmapped(id.id)
            if (owner.id != id.id || !owner.isFocusedApp() &&
                !resumedUnknown) return null
            owner.screenRect()
        } finally {
            owner.recycle()
        }
        val raw = Rect().also(root::getBoundsInScreen).bounds()
        val inset = frame.left > 2 && frame.right < width - 2 && frame.top > 2 && frame.bottom < height - 2
        if (!inset && isLarge(frame, width, height)) {
            return if (isLarge(raw, width, height)) AppSurface() else null
        }
        val aligned = FreeformViewport.aligned(frame, raw, width, height)
        val activity = isActivity(id)
        SkipTrace.log("window") { "phase=freeform windowId=${id.id} frame=$frame root=$raw aligned=$aligned activity=$activity" }
        if (!aligned || !(activity || resumedUnknown)) return null
        val windows = listWindows()
        try {
            val visible = windows.filter { Build.VERSION.SDK_INT < 30 || it.displayId == 0 }
            val layers = visible.map { WindowLayer(it.id, it.layer, it.screenRect(),
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION, it.type == -1, it.isFocused,
                it.isActive, !it.title.isNullOrEmpty(), it.isInPictureInPictureMode) }
            val bar = layers.captionFor(id.id, frame, resumedUnknown)
            if (bar == null) {
                SkipTrace.log("window") { "phase=caption windowId=${id.id} result=unverified_layers" }
                return null
            }
            val caption = visible.first { it.id == bar.id }
            val captionRoot = if (Build.VERSION.SDK_INT >= 33) caption.getRoot(0) else caption.root
            val obstructions = layers.filter { it.layer >= bar.layer && it.id != bar.id && it.bounds.overlaps(frame) }
            val viewport = FreeformViewport(frame, raw, bar.id, bar.bounds, obstructions.map { it.bounds })
            if (captionRoot == null) {
                val verified = verifyHiddenCaption?.invoke(id, viewport, resumedUnknown) == true
                SkipTrace.log("window") { "phase=caption windowId=${id.id} wmVerified=$verified" }
                return if (verified) AppSurface(viewport) else null
            }
            val systemCaption = try {
                captionRoot.windowId == bar.id && captionRoot.packageName?.toString() in SYSTEM_DECOR_PACKAGES
            } finally {
                captionRoot.recycle()
            }
            SkipTrace.log("window") { "phase=caption windowId=${id.id} system=$systemCaption" }
            if (!systemCaption) return null
            return AppSurface(viewport)
        } finally {
            windows.forEach { it.recycle() }
        }
    }

    private fun isLarge(bounds: RectBounds, w: Int, h: Int): Boolean {
        val visible = bounds.clippedToScreen(w, h) ?: return false
        return visible.width >= w * .85f && visible.height >= h * .85f
    }

    private companion object {
        val SYSTEM_DECOR_PACKAGES = setOf("android", "com.android.systemui")
    }
}

internal fun Rect.bounds() = RectBounds(left, top, right, bottom)
internal fun AccessibilityWindowInfo.screenRect() = Rect().also(::getBoundsInScreen).bounds()
