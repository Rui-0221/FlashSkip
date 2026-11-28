package com.flashskip.a11y

import com.flashskip.core.FreeformViewport
import com.flashskip.core.RectBounds

private const val GESTURE_RETRY_SNAPSHOT_MAX_AGE_MS = 96L

internal fun isFreshGestureRetry(treeAtMs: Long, checkAtMs: Long, foregroundChanged: Boolean): Boolean =
    !foregroundChanged && checkAtMs - treeAtMs in 0L..GESTURE_RETRY_SNAPSHOT_MAX_AGE_MS

/** One default-display window from the same accessibility window-list read. */
internal data class WindowLayer(
    val id: Int, val layer: Int, val bounds: RectBounds, val application: Boolean,
    val unmapped: Boolean, val focused: Boolean, val active: Boolean, val titled: Boolean, val pip: Boolean
)

internal fun List<WindowLayer>.captionFor(
    id: Int, frame: RectBounds, resumedUnknown: Boolean = false, bannerId: Int? = null
): WindowLayer? {
    val owner = singleOrNull { it.id == id } ?: return null
    if (!(owner.application || resumedUnknown && owner.unmapped) || owner.pip || owner.bounds != frame ||
        bannerId == null && (!owner.focused || !owner.active) ||
        any { it.id != id && it.id != bannerId && (it.focused || it.active) }) return null
    val caption = singleOrNull {
        it.id != bannerId && it.layer > owner.layer && it.application && !it.titled && !it.active && !it.focused &&
            FreeformViewport.isCaption(frame, it.bounds)
    } ?: return null
    return caption.takeUnless { any { other ->
        other.id != caption.id && other.layer > owner.layer && (other.application || other.unmapped) &&
            other.bounds.overlaps(frame) && !other.isTopStripOf(frame)
    } }
}

/** A competing input window may be bypassed only when it is a shallow strip above the same task. */
internal fun List<WindowLayer>.competingBanner(owner: WindowLayer): WindowLayer? {
    if (!owner.application || owner.pip || owner.bounds.isEmpty) return null
    val focused = singleOrNull { it.focused } ?: return null
    val competitors = filter { it.id != owner.id && (it.active || it.focused) }
    val banner = competitors.singleOrNull() ?: return null
    if (focused.id != owner.id && focused.id != banner.id) return null
    if (!(banner.application || banner.unmapped) || banner.pip || banner.layer <= owner.layer ||
        !banner.isTopStripOf(owner.bounds)) return null
    if (any { it.id != banner.id && it.layer > owner.layer && (it.application || it.unmapped) &&
            it.bounds.overlaps(owner.bounds) && !it.isTopStripOf(owner.bounds) }) return null
    return banner
}

private fun WindowLayer.isTopStripOf(frame: RectBounds): Boolean =
    bounds.overlaps(frame) && bounds.height <= frame.height * .20f &&
        bounds.top <= frame.top + frame.height * .12f &&
        bounds.bottom <= frame.top + frame.height * .25f

internal fun List<WindowLayer>.higherObstructions(owner: WindowLayer): List<RectBounds> =
    filter { it.id != owner.id && it.layer >= owner.layer && it.bounds.overlaps(owner.bounds) }
        .map { it.bounds }

internal fun List<WindowLayer>.allowsFocusedFullscreenInput(id: Int, frame: RectBounds, target: RectBounds): Boolean {
    val owner = singleOrNull { it.id == id && it.application && it.focused && !it.pip && it.bounds == frame }
        ?: return false
    return frame.contains(target) && none { it.id != id && (it.focused || it.active ||
        it.layer >= owner.layer && it.bounds.overlaps(target)) }
}

internal fun List<WindowLayer>.allowsFocusedUnscaledInput(id: Int, viewport: FreeformViewport,
                                                         target: RectBounds): Boolean {
    // With identical frames, refreshed node bounds map directly to physical pixels.
    if (viewport.nodeRoot != viewport.frame || viewport.display == null) return false
    val physical = viewport.screenTarget(target) ?: return false
    val caption = captionFor(id, viewport.frame) ?: return false
    if (caption.id != viewport.captionId || caption.bounds != viewport.caption) return false
    val owner = single { it.id == id }
    return owner.bounds.contains(physical) && none {
        it.id != owner.id && it.id != caption.id && it.layer >= owner.layer && it.bounds.overlaps(physical)
    }
}
