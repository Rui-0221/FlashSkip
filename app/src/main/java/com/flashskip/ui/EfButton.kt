package com.flashskip.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.animation.PathInterpolator
import androidx.appcompat.widget.AppCompatButton
import com.flashskip.R

/**
 * Tri-theme action button supporting:
 * - Consumer rounded pill/soft-rect buttons (Classic style).
 * - Endfield 45-degree bottom-right cut-corner buttons with mechanical left-to-right wipe
 *   (PathInterpolator(0.7, 0, 0.2, 1), 100ms press / 280ms release) and Saira 700/112% typography.
 * - Meow chibi-cat candy-lip pill buttons with springy press-down feel, top gloss, and subtle paw accent.
 * Respects system reduced-motion settings and renders a high-contrast 2dp focus outline.
 */
class EfButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.buttonStyle
) : AppCompatButton(context, attrs, defStyleAttr) {

    enum class Kind(val attrVal: Int) {
        NORMAL(0),
        GO(1),
        DANGER(2);

        companion object {
            fun fromAttr(v: Int): Kind = entries.firstOrNull { it.attrVal == v } ?: NORMAL
        }
    }

    private var kind: Kind = Kind.NORMAL
    private var small: Boolean = false
    private var customRadiusPx: Float = -1f

    private var wipeFraction: Float = 0f
    private var wipeAnimator: ValueAnimator? = null
    private var wasPressedState = false

    private val rectF = RectF()
    private val clipPath = Path()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val wipePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    companion object {
        private val SMOOTH_INTERPOLATOR = PathInterpolator(0.4f, 0f, 0.2f, 1f)
        private val MECH_INTERPOLATOR = PathInterpolator(0.7f, 0f, 0.2f, 1f)
    }

    init {
        val density = resources.displayMetrics.density
        if (attrs != null) {
            val ta = context.obtainStyledAttributes(attrs, R.styleable.EfButton)
            kind = Kind.fromAttr(ta.getInt(R.styleable.EfButton_efKind, 0))
            small = ta.getBoolean(R.styleable.EfButton_efSmall, false)
            if (ta.hasValue(R.styleable.EfButton_efCutSize)) {
                customRadiusPx = ta.getDimension(R.styleable.EfButton_efCutSize, -1f)
            }
            ta.recycle()
        }

        background = null
        stateListAnimator = null
        elevation = 0f
        isAllCaps = false
        isFocusable = true
        isClickable = true
        gravity = Gravity.CENTER
        minWidth = (48f * density).toInt()
        minHeight = (if (small) 44f * density else 48f * density).toInt()
        minimumHeight = minHeight

        val padH = ((if (small) 15f else 20f) * density).toInt()
        val padV = ((if (small) 8f else 11f) * density).toInt()
        setPaddingRelative(
            maxOf(paddingStart, padH),
            maxOf(paddingTop, padV),
            maxOf(paddingEnd, padH),
            maxOf(paddingBottom, padV)
        )

        refreshThemeAppearance()
    }

    fun setEfKind(newKind: Kind) {
        if (kind == newKind) return
        kind = newKind
        syncTextColor()
        invalidate()
    }

    fun refreshThemeAppearance() {
        val endfield = FlashTheme.isEndfield(context)
        typeface = if (endfield) EfTypeface.button(context) else Typeface.DEFAULT_BOLD
        fontFeatureSettings = "tnum"
        focusPaint.strokeWidth = resources.getDimension(R.dimen.ef_focus_stroke)
        syncTextColor()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isEnabled && event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (kind == Kind.GO || kind == Kind.DANGER) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
        return super.onTouchEvent(event)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        val pressedNow = isPressed && isEnabled
        if (pressedNow != wasPressedState) {
            wasPressedState = pressedNow
            val endfield = FlashTheme.isEndfield(context)
            val downMs = if (endfield) 100L else 90L
            val upMs = if (endfield) 280L else 180L
            animatePress(if (pressedNow) 1f else 0f, if (pressedNow) downMs else upMs, endfield)
        }
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) {
            wipeAnimator?.cancel()
            wipeFraction = 0f
            wasPressedState = false
        }
        syncTextColor()
        invalidate()
    }

    private fun animatePress(target: Float, durationMs: Long, endfield: Boolean) {
        wipeAnimator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            wipeFraction = target
            syncTextColor()
            invalidate()
            return
        }
        wipeAnimator = ValueAnimator.ofFloat(wipeFraction, target).apply {
            duration = durationMs
            interpolator = if (endfield) MECH_INTERPOLATOR else SMOOTH_INTERPOLATOR
            addUpdateListener { anim ->
                wipeFraction = anim.animatedValue as Float
                syncTextColor()
                invalidate()
            }
            start()
        }
    }

    private fun syncTextColor() {
        val p = FlashTheme.palette(context)
        val color = when {
            !isEnabled -> p.ink2
            kind == Kind.GO -> p.onSignal
            kind == Kind.DANGER && wipeFraction >= 0.45f -> p.dangerFg
            kind == Kind.DANGER -> p.danger
            wipeFraction >= 0.45f -> p.onSignal
            else -> p.ink
        }
        setTextColor(color)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            super.onDraw(canvas)
            return
        }

        val endfield = FlashTheme.isEndfield(context)
        val meow = FlashTheme.isMeow(context)
        val p = FlashTheme.palette(context)
        val density = resources.displayMetrics.density

        val restColor = when (kind) {
            Kind.GO -> p.signal
            Kind.NORMAL, Kind.DANGER -> p.chip
        }
        val pressColor = when (kind) {
            Kind.GO -> p.signalWipeOnGo
            Kind.NORMAL -> p.signal
            Kind.DANGER -> p.dangerSolid
        }

        if (endfield) {
            val cutPx = if (customRadiusPx >= 0f) {
                customRadiusPx
            } else {
                (if (small) 7f else 8f) * density
            }
            clipPath.set(
                EfCutDrawable.buildCutPath(
                    width = w,
                    height = h,
                    cutBottomRightPx = cutPx,
                    useCutCorners = true
                )
            )
            val save = canvas.save()
            canvas.clipPath(clipPath)
            bgPaint.color = restColor
            bgPaint.alpha = if (isEnabled) 255 else 150
            canvas.drawRect(0f, 0f, w, h, bgPaint)

            if (wipeFraction > 0f) {
                wipePaint.color = pressColor
                wipePaint.alpha = 255
                canvas.drawRect(0f, 0f, w * wipeFraction.coerceIn(0f, 1f), h, wipePaint)
            }
            canvas.restoreToCount(save)

            if (isFocused) {
                focusPaint.color = p.trace
                val half = focusPaint.strokeWidth / 2f
                val focusPath = EfCutDrawable.buildCutPath(
                    width = w,
                    height = h,
                    cutBottomRightPx = cutPx,
                    insetPx = half,
                    useCutCorners = true
                )
                canvas.drawPath(focusPath, focusPaint)
            }
        } else if (meow) {
            val lipH = 3f * density
            val pressShift = (2f * density) * wipeFraction.coerceIn(0f, 1f)
            val faceTop = pressShift
            val faceBottom = (h - lipH + pressShift * 0.5f).coerceAtLeast(faceTop + 8f)
            val radius = (if (small) 16f else 20f) * density
            val r = radius.coerceAtMost(minOf(w, faceBottom - faceTop) * 0.5f)

            // Candy 3D bottom lip
            val lipColor = when {
                !isEnabled -> p.rule
                kind == Kind.GO -> p.signalWipeOnGo
                kind == Kind.DANGER -> p.danger
                else -> p.rule
            }
            rectF.set(0f, faceTop + lipH - pressShift * 0.5f, w, h)
            bgPaint.color = lipColor
            bgPaint.alpha = if (isEnabled) 255 else 135
            canvas.drawRoundRect(rectF, r, r, bgPaint)

            // Main candy button face
            rectF.set(0f, faceTop, w, faceBottom)
            bgPaint.color = restColor
            bgPaint.alpha = if (isEnabled) 255 else 150
            canvas.drawRoundRect(rectF, r, r, bgPaint)

            if (wipeFraction > 0f) {
                wipePaint.color = pressColor
                wipePaint.alpha = (255 * wipeFraction).toInt().coerceIn(0, 255)
                canvas.drawRoundRect(rectF, r, r, wipePaint)
            }

            // Soft top highlight pill
            if (isEnabled) {
                wipePaint.color = Color.WHITE
                wipePaint.alpha = if (p.isDark) 24 else 68
                val hiPad = maxOf(r * 0.65f, 10f * density)
                if (w - hiPad * 2 > 8f * density) {
                    canvas.drawRoundRect(
                        RectF(hiPad, faceTop + 2.5f * density, w - hiPad, faceTop + 5f * density),
                        2f * density,
                        2f * density,
                        wipePaint
                    )
                }
            }

            // Subtle decorative mini paw on primary GO button (left edge, never colliding with centered text)
            if (kind == Kind.GO && w > 110f * density) {
                wipePaint.color = p.onSignal
                wipePaint.alpha = 46
                MeowBackdropView.drawMiniPaw(
                    canvas = canvas,
                    cx = 20f * density,
                    cy = (faceTop + faceBottom) * 0.5f,
                    radius = 7.5f * density,
                    angleDeg = -14f,
                    paint = wipePaint
                )
            }

            if (isFocused) {
                focusPaint.color = p.trace
                val half = focusPaint.strokeWidth / 2f
                rectF.set(half, faceTop + half, w - half, faceBottom - half)
                canvas.drawRoundRect(rectF, (r - half).coerceAtLeast(0f), (r - half).coerceAtLeast(0f), focusPaint)
            }
        } else {
            val cornerSize = if (customRadiusPx >= 0f) {
                customRadiusPx
            } else {
                resources.getDimension(if (small) R.dimen.ef_cut_small else R.dimen.ef_cut_button)
            }
            val radius = cornerSize.coerceAtMost(minOf(w, h) * 0.5f)
            rectF.set(0f, 0f, w, h)
            bgPaint.color = restColor
            bgPaint.alpha = if (isEnabled) 255 else 135
            canvas.drawRoundRect(rectF, radius, radius, bgPaint)

            if (wipeFraction > 0f) {
                wipePaint.color = pressColor
                wipePaint.alpha = (255 * wipeFraction).toInt().coerceIn(0, 255)
                canvas.drawRoundRect(rectF, radius, radius, wipePaint)
            }

            if (isFocused) {
                focusPaint.color = p.trace
                val half = focusPaint.strokeWidth / 2f
                rectF.set(half, half, w - half, h - half)
                canvas.drawRoundRect(
                    rectF,
                    (radius - half).coerceAtLeast(0f),
                    (radius - half).coerceAtLeast(0f),
                    focusPaint
                )
            }
        }

        super.onDraw(canvas)
    }
}
