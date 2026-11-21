package com.flashskip.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel

/**
 * Dual-theme shape builders supporting both soft rounded consumer cards and
 * Endfield 45-degree chamfered cut-corner / 0dp sharp hairline surfaces.
 */
object EfCutDrawable {

    fun shapeModel(
        cutBottomRightPx: Float = 0f,
        cutTopLeftPx: Float = 0f,
        useCutCorners: Boolean = false
    ): ShapeAppearanceModel {
        val family = if (useCutCorners) CornerFamily.CUT else CornerFamily.ROUNDED
        val builder = ShapeAppearanceModel.builder()
        if (useCutCorners) {
            builder.setAllCorners(CornerFamily.CUT, 0f)
            if (cutBottomRightPx > 0f) {
                builder.setBottomRightCorner(CornerFamily.CUT, cutBottomRightPx)
            }
            if (cutTopLeftPx > 0f) {
                builder.setTopLeftCorner(CornerFamily.CUT, cutTopLeftPx)
            }
        } else {
            builder.setAllCorners(family, cutBottomRightPx)
            if (cutTopLeftPx > 0f) {
                builder.setTopLeftCorner(family, cutTopLeftPx)
            }
        }
        return builder.build()
    }

    fun build(
        fillColor: Int,
        strokeColor: Int? = null,
        strokeWidthPx: Float = 0f,
        cutBottomRightPx: Float = 0f,
        cutTopLeftPx: Float = 0f,
        useCutCorners: Boolean = false
    ): MaterialShapeDrawable {
        return MaterialShapeDrawable(shapeModel(cutBottomRightPx, cutTopLeftPx, useCutCorners)).apply {
            this.fillColor = ColorStateList.valueOf(fillColor)
            if (strokeColor != null && strokeWidthPx > 0f) {
                setStroke(strokeWidthPx, strokeColor)
            }
        }
    }

    /**
     * Builds an Endfield dashboard panel (`panel` fill, 1dp `rule` border, bottom-right cut,
     * plus a 56dp x 5dp dark ink tab anchored at top-left, yellow tactical chamfer wedge,
     * and L-bracket framing corners).
     */
    fun buildEndfieldPanel(
        fillColor: Int,
        ruleColor: Int,
        tabColor: Int,
        strokeWidthPx: Float,
        cutBottomRightPx: Float,
        tabWidthPx: Int,
        tabHeightPx: Int,
        signalColor: Int = 0xFFFFFA00.toInt(),
        isDark: Boolean = false,
        density: Float = 3f
    ): Drawable = EndfieldPanelDrawable(
        fillColor = fillColor,
        ruleColor = ruleColor,
        tabColor = tabColor,
        signalColor = signalColor,
        isDark = isDark,
        strokeWidthPx = strokeWidthPx,
        cutBottomRightPx = cutBottomRightPx,
        tabWidthPx = tabWidthPx,
        tabHeightPx = tabHeightPx,
        density = density
    )

    /**
     * Builds a crisp 0dp box (with optional dashed stroke for background/connecting states).
     */
    fun buildSquareBox(
        fillColor: Int,
        strokeColor: Int? = null,
        strokeWidthPx: Int = 0,
        dashed: Boolean = false,
        dashWidthPx: Float = 0f,
        dashGapPx: Float = 0f
    ): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 0f
        setColor(fillColor)
        if (strokeColor != null && strokeWidthPx > 0) {
            if (dashed && dashWidthPx > 0f && dashGapPx > 0f) {
                setStroke(strokeWidthPx, strokeColor, dashWidthPx, dashGapPx)
            } else {
                setStroke(strokeWidthPx, strokeColor)
            }
        }
    }

    fun buildCutPath(
        width: Float,
        height: Float,
        cutBottomRightPx: Float = 0f,
        cutTopLeftPx: Float = 0f,
        insetPx: Float = 0f,
        useCutCorners: Boolean = false
    ): Path {
        val left = insetPx
        val top = insetPx
        val right = (width - insetPx).coerceAtLeast(left)
        val bottom = (height - insetPx).coerceAtLeast(top)
        if (!useCutCorners) {
            val radius = maxOf(cutBottomRightPx, cutTopLeftPx)
                .coerceIn(0f, minOf((right - left) * 0.5f, (bottom - top) * 0.5f))
            return Path().apply {
                addRoundRect(left, top, right, bottom, radius, radius, Path.Direction.CW)
            }
        }
        val maxCut = minOf(right - left, bottom - top) * 0.45f
        val tl = cutTopLeftPx.coerceIn(0f, maxCut)
        val br = cutBottomRightPx.coerceIn(0f, maxCut)
        return Path().apply {
            moveTo(left + tl, top)
            lineTo(right, top)
            lineTo(right, bottom - br)
            if (br > 0f) lineTo(right - br, bottom)
            lineTo(left, bottom)
            lineTo(left, top + tl)
            if (tl > 0f) lineTo(left + tl, top)
            close()
        }
    }

    /**
     * Preserves a solid selected fill while rendering a crisp inset high-contrast stroke when focused.
     */
    fun buildInsetFocusSelector(
        fillColor: Int,
        focusStrokeColor: Int,
        strokeWidthPx: Int,
        insetPx: Int,
        cornerRadiusPx: Float = 0f,
        cutBottomRightPx: Float = 0f
    ): StateListDrawable {
        if (cutBottomRightPx > 0f) {
            val idle = build(
                fillColor = fillColor,
                cutBottomRightPx = cutBottomRightPx,
                useCutCorners = true
            )
            val focusBase = build(
                fillColor = fillColor,
                strokeColor = focusStrokeColor,
                strokeWidthPx = strokeWidthPx.toFloat(),
                cutBottomRightPx = cutBottomRightPx,
                useCutCorners = true
            )
            return StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focusBase)
                addState(intArrayOf(), idle)
            }
        }
        val idle = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            setColor(fillColor)
        }
        val focusBase = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            setColor(fillColor)
        }
        val focusRing = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = (cornerRadiusPx - insetPx).coerceAtLeast(0f)
            setColor(Color.TRANSPARENT)
            setStroke(strokeWidthPx, focusStrokeColor)
        }
        val focused = LayerDrawable(arrayOf(focusBase, focusRing)).apply {
            setLayerInset(1, insetPx, insetPx, insetPx, insetPx)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), idle)
        }
    }
}
