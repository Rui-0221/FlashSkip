package com.flashskip.a11y

import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.TypedValue
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.flashskip.core.ForegroundWindow
import com.flashskip.core.FreeformViewport
import com.flashskip.core.PackagePolicy
import com.flashskip.core.RectBounds

internal data class AppSurface(
    val freeform: FreeformViewport? = null,
    val frame: RectBounds? = null,
    val obstructions: List<RectBounds> = emptyList(),
    val behindTopWindow: Boolean = false,
    val ordinaryApplication: Boolean = false
)

/** Coordinates from live windows are never inferred from the dimensions of an ad control. */
@Suppress("DEPRECATION")
internal class ApplicationWindows(
    private val context: Context,
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

    /** The executor already refreshed the gesture node and checked its window and owner. */
    fun canDispatchFocusedGesture(surface: AppSurface, windowId: Int, target: RectBounds, width: Int, height: Int): Boolean {
        val frame = surface.frame ?: return false
        if (surface.behindTopWindow) return false
        val viewport = surface.freeform
        if (viewport != null && viewport.nodeRoot != viewport.frame ||
            viewport == null && target.clippedToScreen(width, height) != target) return false
        return runCatching {
            withLayers { _, layers ->
                if (viewport == null) layers.allowsFocusedFullscreenInput(windowId, frame, target)
                else layers.allowsFocusedUnscaledInput(windowId, viewport, target)
            }
        }.getOrDefault(false)
    }

    fun canDispatch(root: AccessibilityNodeInfo, width: Int, height: Int, unmapped: ForegroundWindow?,
                    tree: ExtractedTree, target: RectBounds, clickBounds: RectBounds?): Boolean {
        if (root.windowId != tree.windowId || root.packageName?.toString() != tree.snapshot.packageName) return false
        val original = tree.snapshot.freeform
        val live = inspect(root, width, height, unmapped, verifiedCaption = original) ?: return false
        val viewport = live.freeform
        if (original != null && (!original.sameTask(viewport) || original.nodeRoot != viewport?.nodeRoot) ||
            original == null && viewport != null ||
            live.behindTopWindow && !tree.snapshot.behindTopWindow) return false
        val frame = live.frame ?: return false
        fun clear(bounds: RectBounds): Boolean {
            val physical = if (viewport == null) bounds else viewport.screenTarget(bounds) ?: return false
            return physical.clippedToScreen(width, height) == physical && frame.contains(physical) &&
                live.obstructions.none { it.overlaps(physical) }
        }
        return clear(target) && (clickBounds == null || clear(clickBounds))
    }

    /** Recover a known application or the verified parent of a same-package strip. */
    fun readBehindTopWindow(expected: Collection<ForegroundWindow>): AccessibilityNodeInfo? {
        return runCatching {
            withLayers { visible, layers ->
                val focused = visible.singleOrNull { it.isFocused } ?: return@withLayers null
                val parentId = focused.parent?.let {
                    try { it.id } finally { it.recycle() }
                }
                val candidates = layers.mapNotNull { owner ->
                    val hint = expected.singleOrNull { it.id == owner.id }
                    if (hint == null && owner.id != parentId) return@mapNotNull null
                    val banner = layers.competingBanner(owner) ?: return@mapNotNull null
                    if (hint == null && banner.id != focused.id) return@mapNotNull null
                    val pkg = visible.bannerPackage(banner.id) ?: return@mapNotNull null
                    if (hint != null && hint.packageName != pkg || PackagePolicy.isExcludedPackage(pkg)) return@mapNotNull null
                    ForegroundWindow(pkg, owner.id)
                }
                val identity = candidates.singleOrNull() ?: return@withLayers null
                val root = visible.first { it.id == identity.id }.readRoot() ?: return@withLayers null
                if (root.windowId == identity.id && root.packageName?.toString() == identity.packageName) {
                    SkipTrace.log("window") { "phase=stacked_root windowId=${identity.id} result=same_package_banner" }
                    root
                } else {
                    root.recycle()
                    null
                }
            }
        }.getOrNull()
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

    fun inspect(root: AccessibilityNodeInfo, width: Int, height: Int, unmapped: ForegroundWindow?,
                verifiedCaption: FreeformViewport? = null): AppSurface? {
        return try {
            readSurface(root, width, height, unmapped, verifiedCaption)
        } catch (_: IllegalStateException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun readSurface(root: AccessibilityNodeInfo, width: Int, height: Int, unmapped: ForegroundWindow?,
                            verifiedCaption: FreeformViewport?): AppSurface? {
        if (!root.isVisibleToUser) return null
        val id = ForegroundWindow(root.packageName?.toString().orEmpty(), root.windowId)
        if (!id.valid) return null
        val owner = root.window ?: return null
        val resumedUnknown: Boolean
        val frame = try {
            resumedUnknown = id == unmapped && owner.isFocusedUnmapped(id.id)
            if (owner.id != id.id || Build.VERSION.SDK_INT >= 30 && owner.displayId != Display.DEFAULT_DISPLAY ||
                owner.type != AccessibilityWindowInfo.TYPE_APPLICATION && !resumedUnknown) return null
            owner.screenRect()
        } finally {
            owner.recycle()
        }
        val raw = Rect().also(root::getBoundsInScreen).bounds()
        val inset = frame.left > 2 && frame.right < width - 2 && frame.top > 2 && frame.bottom < height - 2
        val fullscreen = !inset && frame.left >= -2 && frame.right <= width + 2 && isLarge(frame, width, height)
        if (fullscreen && !isLarge(raw, width, height)) return null
        if (!fullscreen && (!FreeformViewport.aligned(frame, raw, width, height) ||
                !(isActivity(id) || resumedUnknown))) return null
        return withLayers { visible, layers ->
            val layer = layers.singleOrNull { it.id == id.id && it.bounds == frame && !it.pip } ?: return@withLayers null
            val normalFocus = layer.focused && layers.none { it.id != id.id && (it.focused || it.active) }
            val banner = if (normalFocus) null else layers.competingBanner(layer)
                ?.takeIf { visible.hasBannerPackage(it.id, id.packageName) } ?: return@withLayers null
            val obstructions = layers.higherObstructions(layer)
            if (banner != null) SkipTrace.log("window") {
                "phase=stacked_surface windowId=${id.id} bannerId=${banner.id} frame=$frame root=$raw"
            }
            if (fullscreen) return@withLayers AppSurface(
                frame = frame, obstructions = obstructions, behindTopWindow = banner != null,
                ordinaryApplication = layer.application
            )
            val bar = layers.captionFor(id.id, frame, resumedUnknown, banner?.id)
            if (bar == null) {
                SkipTrace.log("window") { "phase=caption windowId=${id.id} result=unverified_layers" }
                return@withLayers null
            }
            val sameCaption = verifiedCaption != null && verifiedCaption.captionId == bar.id && verifiedCaption.caption == bar.bounds &&
                verifiedCaption.frame == frame && verifiedCaption.nodeRoot == raw
            val systemCaption = if (sameCaption) true else {
                val caption = visible.first { it.id == bar.id }
                val captionRoot = caption.readRoot() ?: return@withLayers null
                try {
                    captionRoot.windowId == bar.id && captionRoot.packageName?.toString() in SYSTEM_DECOR_PACKAGES
                } finally {
                    captionRoot.recycle()
                }
            }
            SkipTrace.log("window") { "phase=caption windowId=${id.id} system=$systemCaption reused=$sameCaption" }
            if (!systemCaption) return@withLayers null
            AppSurface(FreeformViewport(frame, raw, bar.id, bar.bounds, emptyList(), RectBounds(0, 0, width, height)),
                frame, layers.filterNot { it.id == bar.id }.higherObstructions(layer), behindTopWindow = banner != null,
                ordinaryApplication = layer.application)
        }
    }

    private fun <T> withLayers(read: (List<AccessibilityWindowInfo>, List<WindowLayer>) -> T): T {
        val windows = listWindows()
        return try {
            val visible = windows.filter { Build.VERSION.SDK_INT < 30 || it.displayId == Display.DEFAULT_DISPLAY }
            val layers = visible.map { WindowLayer(it.id, it.layer, it.screenRect(),
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION, it.type == -1, it.isFocused,
                it.isActive, !it.title.isNullOrEmpty(), it.isInPictureInPictureMode) }
            read(visible, layers)
        } finally {
            windows.forEach { it.recycle() }
        }
    }

    private fun List<AccessibilityWindowInfo>.hasBannerPackage(id: Int, pkg: String): Boolean {
        return bannerPackage(id) == pkg
    }

    private fun List<AccessibilityWindowInfo>.bannerPackage(id: Int): String? {
        val root = firstOrNull { it.id == id }?.readRoot() ?: return null
        return try {
            root.packageName?.toString()?.takeIf { root.windowId == id && it.isNotBlank() }
        } finally {
            root.recycle()
        }
    }

    private fun AccessibilityWindowInfo.readRoot() =
        if (Build.VERSION.SDK_INT >= 33) getRoot(0) else root

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
