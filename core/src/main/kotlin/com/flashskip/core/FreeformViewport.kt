package com.flashskip.core

import kotlin.math.abs
import kotlin.math.roundToInt

/** Node coordinates and compositor coordinates can differ on scaled floating tasks. */
data class FreeformViewport(
    val frame: RectBounds,
    val nodeRoot: RectBounds,
    val captionId: Int,
    val caption: RectBounds,
    val obstructions: List<RectBounds>,
    val display: RectBounds? = null
) {
    fun local(rect: RectBounds) = RectBounds(
        rect.left - nodeRoot.left, rect.top - nodeRoot.top,
        rect.right - nodeRoot.left, rect.bottom - nodeRoot.top
    )

    fun screenTarget(local: RectBounds): RectBounds? {
        if (local.isEmpty || local.left < 0 || local.top < 0 ||
            local.right > nodeRoot.width || local.bottom > nodeRoot.height) return null
        fun x(v: Int) = frame.left + (v * frame.width.toDouble() / nodeRoot.width).roundToInt()
        fun y(v: Int) = frame.top + (v * frame.height.toDouble() / nodeRoot.height).roundToInt()
        val target = RectBounds(x(local.left), y(local.top), x(local.right), y(local.bottom))
        val clipped = display?.let {
            RectBounds(maxOf(target.left, it.left), maxOf(target.top, it.top),
                minOf(target.right, it.right), minOf(target.bottom, it.bottom))
        } ?: target
        val visible = if (caption.overlaps(clipped)) {
            if (caption.bottom - clipped.top > clipped.height * .20f) return null
            clipped.copy(top = caption.bottom)
        } else clipped
        if (visible.width < 12 || visible.height < 12 || visible.area * 2 < target.area ||
            obstructions.any { it.overlaps(visible) }) return null
        return visible
    }

    fun sameTask(other: FreeformViewport?) = other != null && captionId == other.captionId && frame == other.frame

    companion object {
        fun aligned(frame: RectBounds, root: RectBounds, width: Int, height: Int): Boolean {
            if (frame.isEmpty || root.isEmpty || frame.top < 0 || frame.bottom > height ||
                abs(frame.left - root.left) > 2 || abs(frame.top - root.top) > 2) return false
            val visible = frame.clippedToScreen(width, height) ?: return false
            // A parked task may retain the same horizontal overhang after being resized.
            // Visibility of the actual input rectangle is checked separately by screenTarget.
            if (visible.width < frame.width * .85f) return false
            val sx = frame.width.toDouble() / root.width
            val sy = frame.height.toDouble() / root.height
            return sx in 0.25..1.02 && sy in 0.25..1.02 &&
                abs(root.height * sx - frame.height) <= 3.0
        }

        fun isCaption(frame: RectBounds, bar: RectBounds): Boolean =
            !bar.isEmpty && abs(bar.left - frame.left) <= 2 && abs(bar.right - frame.right) <= 2 &&
                abs(bar.top - frame.top) <= 2 && bar.height in 2..(frame.height / 8)
    }
}

fun RectBounds.containsCenter(target: RectBounds) = target.centerX >= left && target.centerX < right &&
    target.centerY >= top && target.centerY < bottom
