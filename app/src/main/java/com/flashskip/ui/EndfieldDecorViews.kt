package com.flashskip.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full-screen procedural Endfield topographic contour & tactical survey backdrop.
 * Renders crisp multi-ring organic elevation contours, subtle survey dot-matrix grid,
 * coordinate crosshairs, and side elevation ruler ticks at carefully balanced alpha
 * so foreground cards and text stay effortlessly legible.
 */
class EndfieldBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val majorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val minorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val techPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        typeface = EfTypeface.fieldBold(context)
    }

    private var cachedW = -1
    private var cachedH = -1
    private val majorPaths = mutableListOf<Path>()
    private val minorPaths = mutableListOf<Path>()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun ensureContourPaths(w: Int, h: Int) {
        if (w == cachedW && h == cachedH && (majorPaths.isNotEmpty() || minorPaths.isNotEmpty())) return
        cachedW = w
        cachedH = h
        majorPaths.clear()
        minorPaths.clear()
        if (w <= 0 || h <= 0) return

        val wf = w.toFloat()
        val hf = h.toFloat()

        // 1. Top-right mountain peak contour cluster (9 concentric organic rings)
        buildOrganicContourCluster(
            cx = wf * 0.90f,
            cy = hf * 0.16f,
            baseRadius = minOf(wf, hf) * 0.13f,
            ringStep = minOf(wf, hf) * 0.048f,
            rings = 9,
            sx = 1.25f,
            sy = 0.88f,
            phase = 0.4f,
            seed = 1.0f
        )

        // 2. Mid-left ridge contour cluster (8 concentric organic rings)
        buildOrganicContourCluster(
            cx = wf * 0.06f,
            cy = hf * 0.50f,
            baseRadius = minOf(wf, hf) * 0.11f,
            ringStep = minOf(wf, hf) * 0.046f,
            rings = 8,
            sx = 0.92f,
            sy = 1.32f,
            phase = 1.7f,
            seed = 2.3f
        )

        // 3. Bottom-right valley saddle contour cluster (8 concentric organic rings)
        buildOrganicContourCluster(
            cx = wf * 0.78f,
            cy = hf * 0.88f,
            baseRadius = minOf(wf, hf) * 0.12f,
            ringStep = minOf(wf, hf) * 0.047f,
            rings = 8,
            sx = 1.35f,
            sy = 0.84f,
            phase = 2.9f,
            seed = 3.7f
        )
    }

    private fun buildOrganicContourCluster(
        cx: Float,
        cy: Float,
        baseRadius: Float,
        ringStep: Float,
        rings: Int,
        sx: Float,
        sy: Float,
        phase: Float,
        seed: Float
    ) {
        val steps = 56
        for (ring in 0 until rings) {
            val rBase = baseRadius + ring * ringStep
            val path = Path()
            val ptsX = FloatArray(steps)
            val ptsY = FloatArray(steps)
            for (i in 0 until steps) {
                val theta = (i.toFloat() / steps) * (2.0 * PI).toFloat()
                val mod = 1f +
                    0.16f * sin(2f * theta + phase + ring * 0.06f) +
                    0.09f * cos(3f * theta - seed + ring * 0.04f) +
                    0.05f * sin(5f * theta + phase * 1.4f)
                val r = rBase * mod
                ptsX[i] = cx + cos(theta) * r * sx
                ptsY[i] = cy + sin(theta) * r * sy
            }

            // Smooth closed quadratic spline passing through midpoints
            val startMidX = (ptsX[steps - 1] + ptsX[0]) * 0.5f
            val startMidY = (ptsY[steps - 1] + ptsY[0]) * 0.5f
            path.moveTo(startMidX, startMidY)
            for (i in 0 until steps) {
                val next = (i + 1) % steps
                val midX = (ptsX[i] + ptsX[next]) * 0.5f
                val midY = (ptsY[i] + ptsY[next]) * 0.5f
                path.quadTo(ptsX[i], ptsY[i], midX, midY)
            }
            path.close()

            if (ring % 3 == 0) {
                majorPaths.add(path)
            } else {
                minorPaths.add(path)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        ensureContourPaths(w, h)

        val p = FlashTheme.palette(context)
        val d = resources.displayMetrics.density
        val inkR = Color.red(p.ink)
        val inkG = Color.green(p.ink)
        val inkB = Color.blue(p.ink)

        val majorAlpha = if (p.isDark) 42 else 48
        val minorAlpha = if (p.isDark) 24 else 28

        majorPaint.strokeWidth = 1.45f * d
        majorPaint.color = Color.argb(majorAlpha, inkR, inkG, inkB)
        minorPaint.strokeWidth = 0.9f * d
        minorPaint.color = Color.argb(minorAlpha, inkR, inkG, inkB)

        for (path in minorPaths) {
            canvas.drawPath(path, minorPaint)
        }
        for (path in majorPaths) {
            canvas.drawPath(path, majorPaint)
        }

        // Faint survey dot-matrix grid in corners
        dotPaint.color = Color.argb(if (p.isDark) 26 else 22, inkR, inkG, inkB)
        val gridStep = 18f * d
        val dotR = 1.0f * d
        for (row in 1..6) {
            for (col in 1..6) {
                val gx = w - col * gridStep
                val gy = row * gridStep + 58f * d
                if (gx > 0 && gy < h) {
                    canvas.drawCircle(gx, gy, dotR, dotPaint)
                }
            }
        }

        // Tactical survey crosshairs (+) and elevation labels
        techPaint.strokeWidth = 1.1f * d
        techPaint.color = Color.argb(if (p.isDark) 44 else 52, inkR, inkG, inkB)
        labelPaint.textSize = 9.5f * d
        labelPaint.color = Color.argb(if (p.isDark) 58 else 64, inkR, inkG, inkB)

        drawCrosshairWithTag(canvas, w * 0.80f, h * 0.14f, 6f * d, "+240M", d)
        drawCrosshairWithTag(canvas, w * 0.16f, h * 0.48f, 6f * d, "SEC-04", d)
        drawCrosshairWithTag(canvas, w * 0.72f, h * 0.85f, 6f * d, "FS//08", d)

        // Right-edge vertical tactical ruler ticks (01 / 02 / 03)
        val rulerX = w - 8f * d
        val rulerStart = h * 0.24f
        val rulerEnd = h * 0.72f
        val tickPaint = Paint(techPaint).apply {
            color = Color.argb(if (p.isDark) 32 else 38, inkR, inkG, inkB)
            strokeWidth = 1f * d
        }
        canvas.drawLine(rulerX, rulerStart, rulerX, rulerEnd, tickPaint)
        val segs = 12
        for (i in 0..segs) {
            val ty = rulerStart + (rulerEnd - rulerStart) * (i.toFloat() / segs)
            val tickLen = if (i % 4 == 0) 7f * d else 3.5f * d
            canvas.drawLine(rulerX - tickLen, ty, rulerX, ty, tickPaint)
            if (i % 4 == 0 && i < segs) {
                val idxText = "0${(i / 4) + 1}"
                canvas.drawText(idxText, rulerX - 22f * d, ty + 3f * d, labelPaint)
            }
        }
    }

    private fun drawCrosshairWithTag(canvas: Canvas, x: Float, y: Float, arm: Float, tag: String, d: Float) {
        canvas.drawLine(x - arm, y, x + arm, y, techPaint)
        canvas.drawLine(x, y - arm, x, y + arm, techPaint)
        canvas.drawText(tag, x + arm + 4f * d, y + 3f * d, labelPaint)
    }
}

/**
 * Compact 40dp-42dp Endfield tactical telemetry HUD strip inside the home display header.
 * Features:
 * - Left: 45-degree black/acid-yellow industrial hazard chevron stripe
 * - Center: Live animated signal pulse waveform inside corner brackets [ ]
 * - Right: Industrial barcode ticks + [STATUS] SYNC XX% readout
 */
class EfTechHudStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var statusCode = "OFFLINE"
    private var syncPercent = 0
    private var subLabel = "A11Y REQUIRED"
    private var isAlert = true
    private var isActive = false

    private var wavePhase = 0f
    private var waveAnimator: ValueAnimator? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        typeface = EfTypeface.fieldBold(context)
        fontFeatureSettings = "tnum"
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        typeface = EfTypeface.field(context)
        fontFeatureSettings = "tnum"
    }

    private val wavePath = Path()
    private val stripeClip = Path()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun updateTelemetry(telemetry: ConsoleFormatter.EndfieldHudTelemetry) {
        val changed = statusCode != telemetry.statusCode ||
            syncPercent != telemetry.syncPercent ||
            subLabel != telemetry.subLabel ||
            isAlert != telemetry.isAlert ||
            isActive != telemetry.isActive
        statusCode = telemetry.statusCode
        syncPercent = telemetry.syncPercent
        subLabel = telemetry.subLabel
        isAlert = telemetry.isAlert
        isActive = telemetry.isActive
        if (changed) {
            syncAnimatorState()
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncAnimatorState()
    }

    override fun onDetachedFromWindow() {
        stopAnimator()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) syncAnimatorState() else stopAnimator()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isShown) syncAnimatorState() else stopAnimator()
    }

    private fun syncAnimatorState() {
        val shouldRun = isAttachedToWindow &&
            isShown &&
            windowVisibility == VISIBLE &&
            (isActive || isAlert) &&
            ValueAnimator.areAnimatorsEnabled()
        if (!shouldRun) {
            stopAnimator()
            return
        }
        if (waveAnimator?.isRunning == true) return
        waveAnimator = ValueAnimator.ofFloat(0f, (2.0 * PI).toFloat()).apply {
            duration = if (isAlert) 1200L else 1900L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                wavePhase = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimator() {
        waveAnimator?.cancel()
        waveAnimator = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val d = resources.displayMetrics.density
        val desiredH = (42f * d).toInt()
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(desiredH, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val p = FlashTheme.palette(context)
        val d = resources.displayMetrics.density

        // Container box
        bgPaint.color = p.panel
        bgPaint.alpha = 236
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        borderPaint.strokeWidth = 1f * d
        borderPaint.color = p.rule
        val half = borderPaint.strokeWidth * 0.5f
        canvas.drawRect(half, half, w - half, h - half, borderPaint)

        // 1. Left 45-degree hazard stripe block
        val stripeBlockW = (44f * d).coerceAtMost(w * 0.16f)
        stripeClip.reset()
        stripeClip.addRect(0f, 0f, stripeBlockW, h, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(stripeClip)
        stripePaint.color = 0xFF161616.toInt()
        canvas.drawRect(0f, 0f, stripeBlockW, h, stripePaint)

        stripePaint.color = p.signal
        val period = 10f * d
        val barW = 4.6f * d
        var sx = -h
        while (sx < stripeBlockW + h) {
            val poly = Path().apply {
                moveTo(sx, h)
                lineTo(sx + barW, h)
                lineTo(sx + barW + h, 0f)
                lineTo(sx + h, 0f)
                close()
            }
            canvas.drawPath(poly, stripePaint)
            sx += period
        }
        canvas.restoreToCount(save)

        // Divider right of hazard block
        canvas.drawLine(stripeBlockW, 0f, stripeBlockW, h, borderPaint)

        // 2. Right readout block (calculate width first)
        val syncText = "SYNC $syncPercent%"
        val codeText = "[$statusCode]"
        textPaint.textSize = 11.5f * d
        subTextPaint.textSize = 9.5f * d
        val codeW = textPaint.measureText(codeText)
        val syncW = subTextPaint.measureText(syncText)
        val barcodeW = 24f * d
        val rightPad = 10f * d
        val textColW = maxOf(codeW, syncW)
        val rightBlockW = (rightPad * 2 + textColW + barcodeW + 8f * d).coerceAtMost(w * 0.52f)
        val rightStart = w - rightBlockW

        // Divider left of right readout
        canvas.drawLine(rightStart, 0f, rightStart, h, borderPaint)

        // Barcode vertical micro-ticks
        val bcX0 = rightStart + 8f * d
        val bcTop = 9f * d
        val bcBot = h - 9f * d
        val barWeights = floatArrayOf(1.6f, 0.8f, 2.1f, 0.9f, 1.3f, 2.2f, 0.9f, 1.5f)
        stripePaint.color = p.ink
        stripePaint.alpha = 185
        var bx = bcX0
        for (bw in barWeights) {
            val lw = bw * d
            if (bx + lw <= bcX0 + barcodeW) {
                canvas.drawRect(bx, bcTop, bx + lw, bcBot, stripePaint)
            }
            bx += lw + 1.5f * d
        }

        // Status readout text
        val tx = (w - rightPad - textColW).coerceAtLeast(bcX0 + barcodeW + 4f * d)
        textPaint.color = when {
            isAlert && !p.isDark -> p.ink
            isAlert -> p.signal
            isActive && p.isDark -> p.signal
            else -> p.ink
        }
        if (isAlert && !p.isDark) {
            // Crisp signal pill behind alert code on light mode
            stripePaint.color = p.signal
            stripePaint.alpha = 255
            canvas.drawRect(tx - 3f * d, 5f * d, tx + codeW + 3f * d, 19f * d, stripePaint)
            textPaint.color = p.onSignal
        }
        canvas.drawText(codeText, tx, 16f * d, textPaint)

        subTextPaint.color = p.ink2
        canvas.drawText(syncText, tx, h - 8f * d, subTextPaint)

        // 3. Center telemetry waveform inside brackets [ ]
        val waveLeft = stripeBlockW + 10f * d
        val waveRight = rightStart - 10f * d
        if (waveRight - waveLeft > 24f * d) {
            // Subtle center baseline & framing brackets
            val midY = h * 0.5f
            val bracketPaint = Paint(borderPaint).apply {
                strokeWidth = 1.2f * d
                color = Color.argb(120, Color.red(p.ink2), Color.green(p.ink2), Color.blue(p.ink2))
            }
            val bLen = 4f * d
            // Left [
            canvas.drawLine(waveLeft, 7f * d, waveLeft, h - 7f * d, bracketPaint)
            canvas.drawLine(waveLeft, 7f * d, waveLeft + bLen, 7f * d, bracketPaint)
            canvas.drawLine(waveLeft, h - 7f * d, waveLeft + bLen, h - 7f * d, bracketPaint)
            // Right ]
            canvas.drawLine(waveRight, 7f * d, waveRight, h - 7f * d, bracketPaint)
            canvas.drawLine(waveRight - bLen, 7f * d, waveRight, 7f * d, bracketPaint)
            canvas.drawLine(waveRight - bLen, h - 7f * d, waveRight, h - 7f * d, bracketPaint)

            val innerL = waveLeft + 5f * d
            val innerR = waveRight - 5f * d
            val span = (innerR - innerL).coerceAtLeast(1f)

            if (!isActive && !isAlert) {
                dashedPaint.strokeWidth = 1.4f * d
                dashedPaint.color = p.ink2
                dashedPaint.pathEffect = DashPathEffect(floatArrayOf(4f * d, 3f * d), 0f)
                canvas.drawLine(innerL, midY, innerR, midY, dashedPaint)
            } else {
                wavePaint.strokeWidth = 1.7f * d
                wavePaint.color = if (p.isDark) p.signal else p.ink
                wavePath.reset()
                val samples = 44
                val amp = (h * 0.27f)
                for (i in 0..samples) {
                    val t = i.toFloat() / samples
                    val x = innerL + span * t
                    val y = if (isAlert) {
                        val pulse = sin(t * 10f + wavePhase)
                        val sq = if (pulse > 0.35f) -0.85f else if (pulse < -0.35f) 0.7f else 0f
                        midY + sq * amp * 0.75f
                    } else {
                        val carrier = sin(t * 14f - wavePhase)
                        val envelope = 0.5f + 0.5f * sin(t * 5f + wavePhase * 0.5f)
                        val spike = if (((t * 3f - wavePhase / (2f * PI.toFloat())) % 1f + 1f) % 1f < 0.14f) 1.25f else 0.55f
                        midY - carrier * envelope * spike * amp * 0.72f
                    }
                    if (i == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
                }
                canvas.drawPath(wavePath, wavePaint)
            }
        }
    }
}

/**
 * Upgraded Endfield tactical panel drawable:
 * - Translucent 93% panel fill so topographic contours softly bleed near panel edges
 * - 26dp bottom-right 45-degree chamfered corner + 1dp hairline border
 * - Top-left 56x5dp ink index tab + subtle top micro-notches
 * - Bottom-right chamfer yellow tactical wedge triangle
 * - Top-right and bottom-left L-bracket framing ticks
 */
class EndfieldPanelDrawable(
    private val fillColor: Int,
    private val ruleColor: Int,
    private val tabColor: Int,
    private val signalColor: Int,
    private val isDark: Boolean,
    private val strokeWidthPx: Float,
    private val cutBottomRightPx: Float,
    private val tabWidthPx: Int,
    private val tabHeightPx: Int,
    private val density: Float
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val tabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val wedgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
    }
    private val bodyPath = Path()
    private val wedgePath = Path()

    override fun draw(canvas: Canvas) {
        val b: Rect = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return

        val save = canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())

        val cut = cutBottomRightPx.coerceIn(0f, minOf(w, h) * 0.45f)
        val half = strokeWidthPx * 0.5f

        // Panel polygon
        bodyPath.reset()
        bodyPath.moveTo(half, half)
        bodyPath.lineTo(w - half, half)
        bodyPath.lineTo(w - half, h - cut - half)
        if (cut > 0f) bodyPath.lineTo(w - cut - half, h - half)
        bodyPath.lineTo(half, h - half)
        bodyPath.close()

        // 93% opacity so underlying Endfield contours remain subtly perceptible without hurting contrast
        fillPaint.color = Color.argb(
            238,
            Color.red(fillColor),
            Color.green(fillColor),
            Color.blue(fillColor)
        )
        canvas.drawPath(bodyPath, fillPaint)

        // Crisp 1dp hairline border
        strokePaint.color = ruleColor
        strokePaint.strokeWidth = strokeWidthPx
        canvas.drawPath(bodyPath, strokePaint)

        // Top-left dark ink tab (56dp x 5dp)
        tabPaint.color = tabColor
        val tw = tabWidthPx.toFloat().coerceAtMost(w * 0.5f)
        val th = tabHeightPx.toFloat()
        canvas.drawRect(0f, 0f, tw, th, tabPaint)

        // Micro tick notches right of the top-left tab
        val notchStart = tw + 6f * density
        val notchH = (th * 0.6f).coerceAtLeast(2f * dSafe())
        for (i in 0 until 3) {
            val nx = notchStart + i * 5f * density
            if (nx + 2.5f * density < w - 16f * density) {
                canvas.drawRect(nx, 0f, nx + 2.2f * density, notchH, tabPaint)
            }
        }

        // Top-right & bottom-left tactical framing corner L-brackets
        bracketPaint.color = tabColor
        bracketPaint.alpha = if (isDark) 170 else 140
        bracketPaint.strokeWidth = (1.6f * density).coerceAtLeast(1.5f)
        val bArm = 9f * density
        val inset = 4f * density
        // Top-right ┐
        canvas.drawLine(w - inset - bArm, inset, w - inset, inset, bracketPaint)
        canvas.drawLine(w - inset, inset, w - inset, inset + bArm, bracketPaint)
        // Bottom-left └
        canvas.drawLine(inset, h - inset - bArm, inset, h - inset, bracketPaint)
        canvas.drawLine(inset, h - inset, inset + bArm, h - inset, bracketPaint)

        // Bottom-right signal yellow chamfer triangle wedge in the cut corner
        if (cut >= 10f * density) {
            val wSize = (cut * 0.44f).coerceAtLeast(7f * density)
            val gap = 2.2f * density
            wedgePath.reset()
            wedgePath.moveTo(w - gap, h - gap)
            wedgePath.lineTo(w - gap - wSize, h - gap)
            wedgePath.lineTo(w - gap, h - gap - wSize)
            wedgePath.close()

            wedgePaint.color = signalColor
            canvas.drawPath(wedgePath, wedgePaint)

            if (!isDark) {
                strokePaint.color = tabColor
                strokePaint.strokeWidth = (0.9f * density).coerceAtLeast(1f)
                canvas.drawPath(wedgePath, strokePaint)
            }
        }

        canvas.restoreToCount(save)
    }

    private fun dSafe(): Float = if (density > 0f) density else 1f

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
