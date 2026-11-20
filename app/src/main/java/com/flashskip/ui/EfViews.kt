package com.flashskip.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import com.flashskip.R

/**
 * Draws Endfield-spec 45-degree ink hatch lines (1.5dp stroke, 9.2dp horizontal step, 22% ink alpha)
 * behind empty states or title bands when Endfield theme is active.
 */
class EfHatchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var bordered = false
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (attrs != null) {
            val ta = context.obtainStyledAttributes(attrs, R.styleable.EfHatchView)
            bordered = ta.getBoolean(R.styleable.EfHatchView_efHatchBordered, false)
            ta.recycle()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val palette = FlashTheme.palette(context)
        val d = resources.displayMetrics.density
        linePaint.strokeWidth = 1.5f * d
        linePaint.color = Color.argb(
            (255 * 0.22f).toInt(),
            Color.red(palette.ink),
            Color.green(palette.ink),
            Color.blue(palette.ink)
        )

        val step = 9.2f * d
        canvas.save()
        canvas.clipRect(0f, 0f, w, h)
        var x = -h
        while (x < w) {
            canvas.drawLine(x, h, x + h, 0f, linePaint)
            x += step
        }
        canvas.restore()

        if (bordered) {
            borderPaint.strokeWidth = 1f * d
            borderPaint.color = palette.rule
            val half = borderPaint.strokeWidth / 2f
            canvas.drawRect(half, half, w - half, h - half, borderPaint)
        }
    }
}

/**
 * Segmented progress bar backed by proportional readiness/window data:
 * - Classic theme: soft rounded pill segments.
 * - Endfield theme: sharp 0dp mechanical square blocks (`#FFFA00` / `trace` active, `chip` inactive).
 */
class EfSegmentBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var totalSegments = 24
    private var filledSegments = 0
    private var useSignalFill = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()

    fun setSegments(filled: Int, total: Int = 24, useSignal: Boolean = true) {
        totalSegments = total.coerceAtLeast(1)
        filledSegments = filled.coerceIn(0, totalSegments)
        useSignalFill = useSignal
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultDp = if (FlashTheme.isEndfield(context)) 10f else 8f
        val defaultH = (defaultDp * resources.displayMetrics.density).toInt()
        val h = resolveSize(defaultH, heightMeasureSpec)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val endfield = FlashTheme.isEndfield(context)
        val p = FlashTheme.palette(context)
        val d = resources.displayMetrics.density
        val gap = (if (endfield) 2f else 3f) * d
        val totalGap = gap * (totalSegments - 1)
        val segW = ((w - totalGap) / totalSegments).coerceAtLeast(1f)
        val radius = if (endfield) 0f else minOf(h, segW) * 0.5f

        val activeColor = if (useSignalFill) p.signal else p.trace
        val inactiveColor = p.chip

        for (i in 0 until totalSegments) {
            val left = i * (segW + gap)
            paint.color = if (i < filledSegments) activeColor else inactiveColor
            rect.set(left, 0f, left + segW, h)
            if (radius > 0f) {
                canvas.drawRoundRect(rect, radius, radius, paint)
            } else {
                canvas.drawRect(rect, paint)
            }
        }
    }
}

/**
 * Responsive horizontal wrapping container so action buttons and status pills never overflow
 * or truncate across 360dp-430dp widths and large system font scales.
 */
class EfWrapLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private var hGap: Int = (8f * resources.displayMetrics.density).toInt()
    private var vGap: Int = (8f * resources.displayMetrics.density).toInt()

    init {
        if (attrs != null) {
            val ta = context.obtainStyledAttributes(attrs, R.styleable.EfWrapLayout)
            hGap = ta.getDimensionPixelSize(R.styleable.EfWrapLayout_efHorizontalGap, hGap)
            vGap = ta.getDimensionPixelSize(R.styleable.EfWrapLayout_efVerticalGap, vGap)
            ta.recycle()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availW = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val maxRowW = if (widthMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else availW

        var rowW = 0
        var rowH = 0
        var totalH = 0
        var maxUsedW = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue

            val childWSpec = if (maxRowW == Int.MAX_VALUE) {
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            } else {
                MeasureSpec.makeMeasureSpec(maxRowW, MeasureSpec.AT_MOST)
            }
            val childHSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            child.measure(childWSpec, childHSpec)

            val cw = child.measuredWidth
            val ch = child.measuredHeight

            if (rowW > 0 && rowW + hGap + cw > maxRowW) {
                maxUsedW = maxOf(maxUsedW, rowW)
                totalH += rowH + vGap
                rowW = cw
                rowH = ch
            } else {
                rowW = if (rowW == 0) cw else rowW + hGap + cw
                rowH = maxOf(rowH, ch)
            }
        }

        if (rowH > 0) {
            maxUsedW = maxOf(maxUsedW, rowW)
            totalH += rowH
        }

        val desiredW = maxUsedW + paddingLeft + paddingRight
        val desiredH = totalH + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(desiredW, widthMeasureSpec), resolveSize(desiredH, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxRowW = (r - l - paddingLeft - paddingRight).coerceAtLeast(0)
        var x = paddingLeft
        var y = paddingTop
        var rowH = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val cw = child.measuredWidth.coerceAtMost(maxRowW)
            val ch = child.measuredHeight

            if (x > paddingLeft && x + cw > paddingLeft + maxRowW) {
                x = paddingLeft
                y += rowH + vGap
                rowH = 0
            }
            child.layout(x, y, x + cw, y + ch)
            x += cw + hGap
            rowH = maxOf(rowH, ch)
        }
    }
}

/**
 * Responsive app header bar holding brand group, live status pill/stamp, and theme switcher.
 * Stays single-row when space allows and cleanly wraps onto 2-3 rows under large fonts
 * on compact widths so no label crowds or clips.
 */
class EfCommandBarLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val brandGap = (10f * density).toInt()
    private val statusGap = (8f * density).toInt()
    private val rowGap = (8f * density).toInt()

    private fun tripleChildren(): Triple<View?, View?, View?> {
        val brand = findViewById<View>(R.id.brandGroup) ?: getChildAt(0)
        val status = findViewById<View>(R.id.tvCmdStatus) ?: getChildAt(1)
        val theme = findViewById<View>(R.id.btnThemeQuick) ?: getChildAt(2)
        return Triple(brand, status, theme)
    }

    private fun visibleWidth(v: View?): Int =
        if (v != null && v.visibility != GONE) v.measuredWidth else 0

    private fun visibleHeight(v: View?): Int =
        if (v != null && v.visibility != GONE) v.measuredHeight else 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val availW = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        val childWSpec = if (widthMode == MeasureSpec.UNSPECIFIED) {
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        } else {
            MeasureSpec.makeMeasureSpec(availW, MeasureSpec.AT_MOST)
        }
        val childHSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

        val (brand, status, theme) = tripleChildren()
        listOfNotNull(brand, status, theme).forEach { child ->
            if (child.visibility != GONE) {
                child.measure(childWSpec, childHSpec)
            }
        }

        val placement = ConsoleFormatter.computeHeaderPlacement(
            availW = if (widthMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE / 4 else availW,
            brandW = visibleWidth(brand),
            brandH = visibleHeight(brand),
            statusW = visibleWidth(status),
            statusH = visibleHeight(status),
            themeW = visibleWidth(theme),
            themeH = visibleHeight(theme),
            brandGap = brandGap,
            statusGap = statusGap,
            rowGap = rowGap
        )

        val desiredW = maxOf(
            suggestedMinimumWidth,
            availW + paddingLeft + paddingRight
        )
        val desiredH = maxOf(
            suggestedMinimumHeight,
            placement.contentHeight + paddingTop + paddingBottom
        )
        setMeasuredDimension(resolveSize(desiredW, widthMeasureSpec), resolveSize(desiredH, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val availW = (r - l - paddingLeft - paddingRight).coerceAtLeast(0)
        val (brand, status, theme) = tripleChildren()
        val placement = ConsoleFormatter.computeHeaderPlacement(
            availW = availW,
            brandW = visibleWidth(brand),
            brandH = visibleHeight(brand),
            statusW = visibleWidth(status),
            statusH = visibleHeight(status),
            themeW = visibleWidth(theme),
            themeH = visibleHeight(theme),
            brandGap = brandGap,
            statusGap = statusGap,
            rowGap = rowGap
        )

        layoutVisible(brand, paddingLeft + placement.brandX, paddingTop + placement.brandY)
        layoutVisible(status, paddingLeft + placement.statusX, paddingTop + placement.statusY)
        layoutVisible(theme, paddingLeft + placement.themeX, paddingTop + placement.themeY)
    }

    private fun layoutVisible(child: View?, left: Int, top: Int) {
        if (child == null || child.visibility == GONE) return
        child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
    }
}
