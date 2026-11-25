package com.flashskip.a11y

import com.flashskip.core.FreeformViewport
import com.flashskip.core.RectBounds

/** One default-display window from the same accessibility window-list read. */
internal data class WindowLayer(
    val id: Int, val layer: Int, val bounds: RectBounds, val application: Boolean,
    val unmapped: Boolean, val focused: Boolean, val active: Boolean, val titled: Boolean, val pip: Boolean
)

internal fun List<WindowLayer>.captionFor(id: Int, frame: RectBounds, resumedUnknown: Boolean = false): WindowLayer? {
    val owner = singleOrNull { it.focused } ?: return null
    if (owner.id != id || !(owner.application || resumedUnknown && owner.unmapped) || !owner.active || owner.pip ||
        owner.bounds != frame || any { it.id != id && it.active }) return null
    val caption = singleOrNull {
        it.layer == owner.layer + 1 && it.application && !it.titled && !it.active && !it.focused &&
            FreeformViewport.isCaption(frame, it.bounds)
    } ?: return null
    return caption.takeUnless { any { other ->
        other.id != caption.id && other.layer > owner.layer && (other.application || other.unmapped) &&
            other.bounds.overlaps(frame)
    } }
}

internal fun RectBounds.overlaps(other: RectBounds) =
    left < other.right && other.left < right && top < other.bottom && other.top < bottom
