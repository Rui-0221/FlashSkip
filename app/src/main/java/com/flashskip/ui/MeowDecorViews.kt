package com.flashskip.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.flashskip.R
import kotlin.math.PI
import kotlin.math.sin

/**
 * Tab-aware sea-salt & chibi American Shorthair backdrop (zero pink, non-obtrusive watermark alpha):
 * - Tab 0 (Home): Giant tilted (-16 deg) front-facing Shorthair cat head in the lower half covering
 *   roughly half the phone screen at soft watermark alpha, accented with upper-half clouds, paws & sparkles.
 * - Tab 1 (Keep-Alive): Patrol paw-print walking trail across the page + rolling hill & sitting cat silhouette.
 * - Tab 2 (More/Settings): Cozy toybox sketchbook pattern (yarn balls, fish bones, chubby fish, mini cat heads).
 */
class MeowBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var activeTab = 0
    private var bgHeadDrawable: Drawable? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val hillPath = Path()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setActiveTab(tabIndex: Int) {
        val clamped = tabIndex.coerceIn(0, 2)
        if (activeTab == clamped) return
        activeTab = clamped
        invalidate()
    }

    private fun headDrawable(): Drawable? {
        if (bgHeadDrawable == null) {
            bgHeadDrawable = ContextCompat.getDrawable(context, R.drawable.cat_bg_head)?.mutate()
        }
        return bgHeadDrawable
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val p = FlashTheme.palette(context)
        val d = resources.displayMetrics.density

        val blueColor = Color.argb(if (p.isDark) 30 else 36, Color.red(p.signal), Color.green(p.signal), Color.blue(p.signal))
        val goldColor = Color.argb(if (p.isDark) 34 else 44, Color.red(p.triB), Color.green(p.triB), Color.blue(p.triB))
        val mintColor = Color.argb(if (p.isDark) 28 else 36, Color.red(p.triC), Color.green(p.triC), Color.blue(p.triC))
        val slateColor = Color.argb(if (p.isDark) 28 else 26, Color.red(p.ink2), Color.green(p.ink2), Color.blue(p.ink2))
        val hillColor = Color.argb(if (p.isDark) 24 else 28, Color.red(p.signal), Color.green(p.signal), Color.blue(p.signal))

        when (activeTab) {
            0 -> drawHomeGiantHeadBackdrop(canvas, w, h, d, p, blueColor, goldColor, slateColor)
            1 -> drawKeepAlivePatrolBackdrop(canvas, w, h, d, blueColor, goldColor, slateColor, hillColor)
            else -> drawMoreToyboxBackdrop(canvas, w, h, d, blueColor, goldColor, mintColor, slateColor)
        }
    }

    private fun drawHomeGiantHeadBackdrop(
        canvas: Canvas,
        w: Float,
        h: Float,
        d: Float,
        p: FlashTheme.Palette,
        blueColor: Int,
        goldColor: Int,
        slateColor: Int
    ) {
        // Soft top-right & upper-left sea-salt cloud puffs
        fillPaint.color = Color.argb(if (p.isDark) 18 else 24, Color.red(p.signal), Color.green(p.signal), Color.blue(p.signal))
        canvas.drawCircle(w * 0.88f, h * 0.14f, 44f * d, fillPaint)
        canvas.drawCircle(w * 0.96f, h * 0.17f, 32f * d, fillPaint)
        canvas.drawCircle(w * 0.10f, h * 0.28f, 38f * d, fillPaint)
        canvas.drawCircle(w * 0.18f, h * 0.32f, 26f * d, fillPaint)

        // Subtle paw prints & sparkles balanced in the upper half above the giant lower cat head
        fillPaint.color = blueColor
        drawMiniPaw(canvas, w * 0.14f, h * 0.19f, 11f * d, -22f, fillPaint)
        drawMiniPaw(canvas, w * 0.22f, h * 0.24f, 9f * d, -10f, fillPaint)
        drawMiniPaw(canvas, w * 0.84f, h * 0.32f, 10.5f * d, 18f, fillPaint)

        fillPaint.color = goldColor
        drawSparkle(canvas, w * 0.82f, h * 0.23f, 7f * d, fillPaint)
        drawSparkle(canvas, w * 0.15f, h * 0.36f, 6.5f * d, fillPaint)

        strokePaint.color = slateColor
        strokePaint.strokeWidth = 1.4f * d
        drawMiniFish(canvas, w * 0.86f, h * 0.39f, 10f * d, -15f, strokePaint)

        // Giant tilted (-16 deg) American Shorthair cat head positioned in the lower half, covering ~half the screen
        val head = headDrawable() ?: return
        val iw = head.intrinsicWidth.coerceAtLeast(1).toFloat()
        val ih = head.intrinsicHeight.coerceAtLeast(1).toFloat()
        val aspect = iw / ih
        // Oversized tilted cat head covering roughly the lower half of a portrait phone screen,
        // while remaining gracefully bounded on landscape/tablet screens.
        val desiredH = h * 0.48f
        val desiredW = (desiredH * aspect).coerceAtLeast(w * 1.28f)
        val targetH = (desiredW / aspect).coerceAtMost(h * 0.68f)
        val targetW = targetH * aspect
        val cx = w * 0.54f
        val cy = h * 0.72f

        val save = canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(-16f)
        head.setBounds(
            (-targetW * 0.5f).toInt(),
            (-targetH * 0.5f).toInt(),
            (targetW * 0.5f).toInt(),
            (targetH * 0.5f).toInt()
        )
        // Gentle watermark opacity so the giant lower-half cat head is clearly visible yet never obstructs buttons/text
        head.alpha = if (p.isDark) 86 else 108
        head.draw(canvas)
        canvas.restoreToCount(save)
    }

    private fun drawKeepAlivePatrolBackdrop(
        canvas: Canvas,
        w: Float,
        h: Float,
        d: Float,
        blueColor: Int,
        goldColor: Int,
        slateColor: Int,
        hillColor: Int
    ) {
        // Rolling bottom hill + sitting guardian cat silhouette
        hillPath.reset()
        hillPath.moveTo(0f, h)
        hillPath.lineTo(0f, h * 0.87f)
        hillPath.cubicTo(w * 0.28f, h * 0.82f, w * 0.54f, h * 0.91f, w * 0.78f, h * 0.84f)
        hillPath.cubicTo(w * 0.88f, h * 0.81f, w * 0.95f, h * 0.82f, w, h * 0.84f)
        hillPath.lineTo(w, h)
        hillPath.close()
        fillPaint.color = hillColor
        canvas.drawPath(hillPath, fillPaint)
        drawSittingCatSilhouette(canvas, w * 0.85f, h * 0.825f, 20f * d, slateColor)

        // Diagonal patrol paw trail
        val trailSteps = 10
        fillPaint.color = blueColor
        for (i in 0 until trailSteps) {
            val t = i.toFloat() / (trailSteps - 1)
            val tx = w * (0.10f + t * 0.78f)
            val ty = h * (0.16f + t * 0.58f) + (if (i % 2 == 0) -11f else 11f) * d
            val angle = 34f + (if (i % 2 == 0) -12f else 12f)
            drawMiniPaw(canvas, tx, ty, 9.2f * d, angle, fillPaint)
        }

        fillPaint.color = goldColor
        drawSparkle(canvas, w * 0.80f, h * 0.22f, 7f * d, fillPaint)
        drawSparkle(canvas, w * 0.22f, h * 0.68f, 6f * d, fillPaint)
        drawMiniCatHeadIcon(canvas, w * 0.18f, h * 0.24f, 11f * d, -14f, slateColor.let {
            fillPaint.color = it
            fillPaint
        })
    }

    private fun drawMoreToyboxBackdrop(
        canvas: Canvas,
        w: Float,
        h: Float,
        d: Float,
        blueColor: Int,
        goldColor: Int,
        mintColor: Int,
        slateColor: Int
    ) {
        val stepX = 76f * d
        val stepY = 78f * d
        val cols = (w / stepX).toInt() + 2
        val rows = (h / stepY).toInt() + 2

        for (r in 0 until rows) {
            val offsetX = if (r % 2 == 1) stepX * 0.5f else 0f
            for (c in 0 until cols) {
                val cx = c * stepX + offsetX + 18f * d
                val cy = r * stepY + 28f * d
                val kind = (r * 5 + c) % 6
                val tilt = ((r + c) % 3 - 1) * 16f
                when (kind) {
                    0 -> {
                        fillPaint.color = blueColor
                        drawMiniPaw(canvas, cx, cy, 9.5f * d, tilt, fillPaint)
                    }
                    1 -> {
                        strokePaint.color = slateColor
                        strokePaint.strokeWidth = 1.35f * d
                        drawYarnBall(canvas, cx, cy, 8.2f * d, tilt, strokePaint)
                    }
                    2 -> {
                        strokePaint.color = mintColor
                        strokePaint.strokeWidth = 1.4f * d
                        drawFishBone(canvas, cx, cy, 9.5f * d, tilt, strokePaint)
                    }
                    3 -> {
                        fillPaint.color = goldColor
                        drawSparkle(canvas, cx, cy, 5.8f * d, fillPaint)
                        canvas.drawCircle(cx + 10f * d, cy - 6f * d, 1.6f * d, fillPaint)
                    }
                    4 -> {
                        fillPaint.color = slateColor
                        drawMiniCatHeadIcon(canvas, cx, cy, 8.5f * d, tilt * 0.5f, fillPaint)
                    }
                    else -> {
                        strokePaint.color = blueColor
                        strokePaint.strokeWidth = 1.35f * d
                        drawMiniFish(canvas, cx, cy, 8.5f * d, tilt, strokePaint)
                    }
                }
            }
        }
    }

    private fun drawSittingCatSilhouette(canvas: Canvas, cx: Float, baseY: Float, size: Float, color: Int) {
        fillPaint.color = color
        val body = RectF(cx - size * 0.48f, baseY - size * 0.95f, cx + size * 0.48f, baseY + size * 0.1f)
        canvas.drawOval(body, fillPaint)
        canvas.drawCircle(cx - size * 0.05f, baseY - size * 1.12f, size * 0.38f, fillPaint)
        val ears = Path().apply {
            moveTo(cx - size * 0.38f, baseY - size * 1.28f)
            lineTo(cx - size * 0.36f, baseY - size * 1.68f)
            lineTo(cx - size * 0.12f, baseY - size * 1.44f)
            close()
            moveTo(cx + size * 0.28f, baseY - size * 1.28f)
            lineTo(cx + size * 0.26f, baseY - size * 1.68f)
            lineTo(cx + size * 0.02f, baseY - size * 1.44f)
            close()
        }
        canvas.drawPath(ears, fillPaint)
        strokePaint.color = color
        strokePaint.strokeWidth = size * 0.16f
        val tail = Path().apply {
            moveTo(cx + size * 0.34f, baseY - size * 0.15f)
            cubicTo(cx + size * 0.92f, baseY - size * 0.1f, cx + size * 0.95f, baseY - size * 0.78f, cx + size * 0.65f, baseY - size * 0.85f)
        }
        canvas.drawPath(tail, strokePaint)
    }

    companion object {
        fun drawMiniPaw(canvas: Canvas, cx: Float, cy: Float, radius: Float, angleDeg: Float, paint: Paint) {
            val save = canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(angleDeg)
            val padRect = RectF(-radius * 0.56f, -radius * 0.12f, radius * 0.56f, radius * 0.68f)
            canvas.drawOval(padRect, paint)
            val toeRx = radius * 0.19f
            val toeRy = radius * 0.25f
            val toeAngles = floatArrayOf(-38f, -13f, 13f, 38f)
            val toeX = floatArrayOf(-0.62f, -0.22f, 0.22f, 0.62f)
            val toeY = floatArrayOf(-0.34f, -0.62f, -0.62f, -0.34f)
            for (i in 0..3) {
                val s = canvas.save()
                canvas.translate(toeX[i] * radius, toeY[i] * radius)
                canvas.rotate(toeAngles[i])
                canvas.drawOval(-toeRx, -toeRy, toeRx, toeRy, paint)
                canvas.restoreToCount(s)
            }
            canvas.restoreToCount(save)
        }

        fun drawMiniCatHeadIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, angleDeg: Float, paint: Paint) {
            val save = canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(angleDeg)
            val p = Path().apply {
                moveTo(-r * 0.78f, -r * 0.1f)
                lineTo(-r * 0.84f, -r * 0.95f)
                lineTo(-r * 0.24f, -r * 0.55f)
                quadTo(0f, -r * 0.68f, r * 0.24f, -r * 0.55f)
                lineTo(r * 0.84f, -r * 0.95f)
                lineTo(r * 0.78f, -r * 0.1f)
                cubicTo(r * 0.95f, r * 0.55f, r * 0.48f, r * 0.78f, 0f, r * 0.78f)
                cubicTo(-r * 0.48f, r * 0.78f, -r * 0.95f, r * 0.55f, -r * 0.78f, -r * 0.1f)
                close()
            }
            canvas.drawPath(p, paint)
            canvas.restoreToCount(save)
        }

        private fun drawYarnBall(canvas: Canvas, cx: Float, cy: Float, r: Float, angleDeg: Float, paint: Paint) {
            val save = canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(angleDeg)
            canvas.drawCircle(0f, 0f, r, paint)
            val arc1 = Path().apply {
                moveTo(-r * 0.75f, -r * 0.32f)
                quadTo(0f, r * 0.12f, r * 0.75f, -r * 0.32f)
                moveTo(-r * 0.72f, r * 0.34f)
                quadTo(0f, -r * 0.08f, r * 0.72f, r * 0.34f)
                moveTo(r * 0.85f, r * 0.45f)
                cubicTo(r * 1.4f, r * 0.55f, r * 1.15f, r * 1.1f, r * 1.65f, r * 0.95f)
            }
            canvas.drawPath(arc1, paint)
            canvas.restoreToCount(save)
        }

        private fun drawFishBone(canvas: Canvas, cx: Float, cy: Float, size: Float, angleDeg: Float, paint: Paint) {
            val save = canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(angleDeg)
            canvas.drawLine(-size * 0.7f, 0f, size * 0.7f, 0f, paint)
            canvas.drawLine(-size * 0.15f, -size * 0.34f, -size * 0.15f, size * 0.34f, paint)
            canvas.drawLine(size * 0.22f, -size * 0.28f, size * 0.22f, size * 0.28f, paint)
            val head = Path().apply {
                moveTo(-size * 0.7f, 0f)
                lineTo(-size * 0.42f, -size * 0.36f)
                lineTo(-size * 0.42f, size * 0.36f)
                close()
                moveTo(size * 0.65f, 0f)
                lineTo(size * 0.95f, -size * 0.3f)
                moveTo(size * 0.65f, 0f)
                lineTo(size * 0.95f, size * 0.3f)
            }
            canvas.drawPath(head, paint)
            canvas.restoreToCount(save)
        }

        fun drawMiniFish(canvas: Canvas, cx: Float, cy: Float, size: Float, angleDeg: Float, paint: Paint) {
            val save = canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(angleDeg)
            val body = RectF(-size * 0.7f, -size * 0.36f, size * 0.45f, size * 0.36f)
            canvas.drawOval(body, paint)
            val tail = Path().apply {
                moveTo(size * 0.45f, 0f)
                lineTo(size * 0.85f, -size * 0.32f)
                lineTo(size * 0.85f, size * 0.32f)
                close()
            }
            canvas.drawPath(tail, paint)
            canvas.restoreToCount(save)
        }

        fun drawSparkle(canvas: Canvas, cx: Float, cy: Float, r: Float, paint: Paint) {
            val path = Path().apply {
                moveTo(cx, cy - r)
                quadTo(cx, cy, cx + r, cy)
                quadTo(cx, cy, cx, cy + r)
                quadTo(cx, cy, cx - r, cy)
                quadTo(cx, cy, cx, cy - r)
                close()
            }
            canvas.drawPath(path, paint)
        }
    }
}

/**
 * Interactive American Shorthair cat mascot view driven by the user's reference art poses:
 * - Sleeping curled cat when any core/keep-alive setting is incomplete
 * - Randomly chosen awake cat pose (walk / belly roll / sit / stretch-yawn) when fully active
 * Supports gentle breathing animation, soft sea-salt cushion aura, and tap-to-pet sparkle burst.
 */
class MeowCatMascotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentPose: ConsoleFormatter.MeowCatPose = ConsoleFormatter.MeowCatPose.AWAKE_SIT
    private var isPetted = false
    private var petProgress = 0f
    private var idlePhase = 0f

    private var idleAnimator: ValueAnimator? = null
    private var petAnimator: ValueAnimator? = null
    private val clearPetRunnable = Runnable { resetPetAnimation() }
    private val drawableCache = mutableMapOf<ConsoleFormatter.MeowCatPose, Drawable?>()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shadowRect = RectF()

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "美短猫咪守护伙伴，轻点可以摸摸头"
    }

    private fun resetPetAnimation() {
        removeCallbacks(clearPetRunnable)
        val anim = petAnimator
        petAnimator = null
        anim?.cancel()
        if (isPetted || petProgress != 0f) {
            isPetted = false
            petProgress = 0f
            invalidate()
        }
    }

    fun setPose(newPose: ConsoleFormatter.MeowCatPose) {
        if (currentPose == newPose) return
        currentPose = newPose
        resetPetAnimation()
        syncIdleAnimator()
        invalidate()
    }

    fun setMood(newMood: ConsoleFormatter.CatMood) {
        val fallback = when (newMood) {
            ConsoleFormatter.CatMood.HAPPY_GUARD -> ConsoleFormatter.MeowCatPose.AWAKE_SIT
            else -> ConsoleFormatter.MeowCatPose.SLEEPING
        }
        setPose(fallback)
    }

    fun triggerPetLove() {
        removeCallbacks(clearPetRunnable)
        val prevAnim = petAnimator
        petAnimator = null
        prevAnim?.cancel()

        isPetted = true
        if (!ValueAnimator.areAnimatorsEnabled()) {
            petProgress = 0.35f
            invalidate()
            postDelayed(clearPetRunnable, 1200L)
            return
        }
        petProgress = 0f
        petAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200L
            addUpdateListener { anim ->
                petProgress = anim.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (petAnimator === animation) {
                        petAnimator = null
                        isPetted = false
                        petProgress = 0f
                        invalidate()
                    }
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (petAnimator === animation) {
                        petAnimator = null
                        isPetted = false
                        petProgress = 0f
                        invalidate()
                    }
                }
            })
            start()
        }
    }

    private fun drawableForPose(pose: ConsoleFormatter.MeowCatPose): Drawable? =
        drawableCache.getOrPut(pose) {
            val resId = when (pose) {
                ConsoleFormatter.MeowCatPose.SLEEPING -> R.drawable.cat_pose_sleep
                ConsoleFormatter.MeowCatPose.AWAKE_WALK -> R.drawable.cat_pose_walk
                ConsoleFormatter.MeowCatPose.AWAKE_ROLL -> R.drawable.cat_pose_roll
                ConsoleFormatter.MeowCatPose.AWAKE_SIT -> R.drawable.cat_pose_sit
                ConsoleFormatter.MeowCatPose.AWAKE_STRETCH -> R.drawable.cat_pose_stretch
            }
            ContextCompat.getDrawable(context, resId)?.mutate()
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncIdleAnimator()
    }

    override fun onDetachedFromWindow() {
        idleAnimator?.cancel()
        idleAnimator = null
        resetPetAnimation()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            syncIdleAnimator()
        } else {
            idleAnimator?.cancel()
            idleAnimator = null
            resetPetAnimation()
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isShown) {
            syncIdleAnimator()
        } else {
            idleAnimator?.cancel()
            idleAnimator = null
            resetPetAnimation()
        }
    }

    private fun syncIdleAnimator() {
        val shouldRun = isAttachedToWindow &&
            isShown &&
            windowVisibility == VISIBLE &&
            ValueAnimator.areAnimatorsEnabled()
        if (!shouldRun) {
            idleAnimator?.cancel()
            idleAnimator = null
            return
        }
        if (idleAnimator?.isRunning == true) return
        idleAnimator = ValueAnimator.ofFloat(0f, (2.0 * PI).toFloat()).apply {
            duration = if (currentPose == ConsoleFormatter.MeowCatPose.SLEEPING) 3200L else 2500L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                idlePhase = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val d = resources.displayMetrics.density
        val defaultW = (116f * d).toInt()
        val defaultH = (104f * d).toInt()
        setMeasuredDimension(resolveSize(defaultW, widthMeasureSpec), resolveSize(defaultH, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val p = FlashTheme.palette(context)
        val d = resources.displayMetrics.density
        val isAwake = currentPose != ConsoleFormatter.MeowCatPose.SLEEPING

        // 1. Soft sea-salt aura cushion behind the cat (ensures crisp silhouette & Zzz contrast in dark mode)
        val auraColor = if (p.isDark) {
            Color.argb(66, Color.red(p.signal), Color.green(p.signal), Color.blue(p.signal))
        } else {
            Color.argb(115, Color.red(p.chip), Color.green(p.chip), Color.blue(p.chip))
        }
        fillPaint.color = auraColor
        canvas.drawCircle(w * 0.5f, h * 0.48f, minOf(w, h) * 0.43f, fillPaint)

        // Subtle ground shadow ellipse under paws/body
        fillPaint.color = Color.argb(if (p.isDark) 52 else 36, Color.red(p.ink2), Color.green(p.ink2), Color.blue(p.ink2))
        shadowRect.set(w * 0.14f, h * 0.80f, w * 0.86f, h * 0.93f)
        canvas.drawOval(shadowRect, fillPaint)

        // 2. Render reference cat pose with gentle breathing & tap-to-pet bounce
        val catDrawable = drawableForPose(currentPose)
        if (catDrawable != null) {
            val pad = 4f * d
            val availW = (w - pad * 2f).coerceAtLeast(8f)
            val availH = (h - pad * 2f).coerceAtLeast(8f)
            val iw = catDrawable.intrinsicWidth.coerceAtLeast(1).toFloat()
            val ih = catDrawable.intrinsicHeight.coerceAtLeast(1).toFloat()
            val ratio = minOf(availW / iw, availH / ih)
            val drawW = iw * ratio
            val drawH = ih * ratio

            val breathY = if (isAwake) sin(idlePhase) * 1.4f * d else sin(idlePhase) * 0.9f * d
            val petBounce = if (isPetted) sin(petProgress * PI.toFloat() * 3f) * (1f - petProgress) * 0.08f else 0f

            val save = canvas.save()
            canvas.translate(w * 0.5f, h * 0.50f + breathY)
            if (petBounce != 0f) {
                canvas.scale(1f + petBounce, 1f - petBounce * 0.7f)
            }
            catDrawable.setBounds(
                (-drawW * 0.5f).toInt(),
                (-drawH * 0.5f).toInt(),
                (drawW * 0.5f).toInt(),
                (drawH * 0.5f).toInt()
            )
            catDrawable.draw(canvas)
            canvas.restoreToCount(save)
        }

        // 3. Transient tap-to-pet sparkles near top/head area (none drawn at rest so tail stays unobstructed)
        if (isPetted) {
            val rise = petProgress * 16f * d
            val fade = sin(petProgress * PI.toFloat()).coerceIn(0f, 1f)
            val alpha = (fade * 255).toInt().coerceIn(0, 255)
            fillPaint.color = Color.argb(alpha, 255, 212, 82)
            MeowBackdropView.drawSparkle(canvas, w * 0.14f, h * 0.22f - rise, 6.0f * d, fillPaint)
            MeowBackdropView.drawSparkle(canvas, w * 0.46f, h * 0.12f - rise * 0.9f, 5.2f * d, fillPaint)
            fillPaint.color = Color.argb(alpha, Color.red(p.signal), Color.green(p.signal), Color.blue(p.signal))
            MeowBackdropView.drawSparkle(canvas, w * 0.26f, h * 0.10f - rise * 1.1f, 5.6f * d, fillPaint)
        }
    }
}

/**
 * Cozy sea-salt marshmallow rounded card drawable for Meow theme (zero pink):
 * - ~72% translucent fill so the giant tilted Shorthair head backdrop stays clearly visible behind cards
 * - 22dp rounded rectangle with 3dp candy drop-lip shadow along the bottom
 * - 1.5dp soft border stroke
 * - Bottom-right paw + mini fish watermark, top-right subtle sparkle accents
 * - Optional peeking silver-tabby cat ears on top-left corner ([showCatEars])
 */
class MeowPanelDrawable(
    private val fillColor: Int,
    private val strokeColor: Int,
    private val lipColor: Int,
    private val pawColor: Int,
    private val cornerRadiusPx: Float,
    private val strokeWidthPx: Float,
    private val density: Float,
    private val showCatEars: Boolean = false,
    private val isDark: Boolean = false
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fishStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val earPath = Path()

    override fun draw(canvas: Canvas) {
        val b: Rect = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return

        val save = canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())

        val lipOffset = (3f * density).coerceAtMost(h * 0.15f)
        val earHeight = if (showCatEars) 14f * density else 0f
        val bodyTop = earHeight
        val bodyBottom = (h - lipOffset).coerceAtLeast(bodyTop + 8f)
        val r = cornerRadiusPx.coerceAtMost(minOf(w, bodyBottom - bodyTop) * 0.45f)

        // Optional peeking silver-tabby cat ears along top-left
        if (showCatEars && w > 96f * density) {
            drawPeekingEar(canvas, 30f * density, bodyTop + 2f * density, 18f * density, earHeight - 1.5f * density)
            drawPeekingEar(canvas, 56f * density, bodyTop + 2f * density, 18f * density, earHeight - 1.5f * density)
        }

        // Bottom candy drop-lip
        fillPaint.color = Color.argb(
            if (isDark) 210 else 212,
            Color.red(lipColor),
            Color.green(lipColor),
            Color.blue(lipColor)
        )
        rect.set(0f, bodyTop + lipOffset, w, h)
        canvas.drawRoundRect(rect, r, r, fillPaint)

        // Main card face (~72% translucent frosted glass so giant lower-half tilted cat head clearly shows through)
        fillPaint.color = Color.argb(
            if (isDark) 186 else 182,
            Color.red(fillColor),
            Color.green(fillColor),
            Color.blue(fillColor)
        )
        rect.set(0f, bodyTop, w, bodyBottom)
        canvas.drawRoundRect(rect, r, r, fillPaint)

        // Subtle paw + mini fish watermark in bottom-right & top-right sparkle
        val clipSave = canvas.save()
        val clipPath = Path().apply { addRoundRect(rect, r, r, Path.Direction.CW) }
        canvas.clipPath(clipPath)
        pawPaint.color = Color.argb(24, Color.red(pawColor), Color.green(pawColor), Color.blue(pawColor))
        MeowBackdropView.drawMiniPaw(canvas, w - 22f * density, bodyBottom - 16f * density, 13.5f * density, -18f, pawPaint)
        fishStroke.color = Color.argb(24, Color.red(pawColor), Color.green(pawColor), Color.blue(pawColor))
        fishStroke.strokeWidth = 1.2f * density
        MeowBackdropView.drawMiniFish(canvas, w - 42f * density, bodyBottom - 14f * density, 7f * density, 10f, fishStroke)
        MeowBackdropView.drawSparkle(canvas, w - 16f * density, bodyTop + 14f * density, 4f * density, pawPaint)
        canvas.restoreToCount(clipSave)

        // Soft border
        val half = strokeWidthPx * 0.5f
        strokePaint.color = strokeColor
        strokePaint.strokeWidth = strokeWidthPx
        rect.set(half, bodyTop + half, w - half, bodyBottom - half)
        canvas.drawRoundRect(rect, (r - half).coerceAtLeast(0f), (r - half).coerceAtLeast(0f), strokePaint)

        canvas.restoreToCount(save)
    }

    private fun drawPeekingEar(canvas: Canvas, cx: Float, baseY: Float, baseW: Float, height: Float) {
        val halfW = baseW * 0.5f
        val apexY = (baseY - height).coerceAtLeast(1.5f * density)
        val tipW = 2.6f * density
        val tipDrop = 2.2f * density
        earPath.reset()
        earPath.moveTo(cx - halfW, baseY)
        earPath.lineTo(cx - tipW, apexY + tipDrop)
        earPath.quadTo(cx, apexY - 0.8f * density, cx + tipW, apexY + tipDrop)
        earPath.lineTo(cx + halfW, baseY)
        earPath.close()

        fillPaint.color = 0xFFB8C2CC.toInt()
        canvas.drawPath(earPath, fillPaint)
        strokePaint.color = strokeColor
        strokePaint.strokeWidth = strokeWidthPx
        canvas.drawPath(earPath, strokePaint)

        val innerApexY = apexY + 4.2f * density
        earPath.reset()
        earPath.moveTo(cx - halfW * 0.46f, baseY - 1.5f * density)
        earPath.lineTo(cx - tipW * 0.5f, innerApexY + tipDrop * 0.5f)
        earPath.quadTo(cx, innerApexY, cx + tipW * 0.5f, innerApexY + tipDrop * 0.5f)
        earPath.lineTo(cx + halfW * 0.46f, baseY - 1.5f * density)
        earPath.close()
        pawPaint.color = 0xFFE5EAF0.toInt()
        canvas.drawPath(earPath, pawPaint)
    }

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

/**
 * Playful cat-whisker & paw-print horizontal divider (`--- 🐾 ---`) for Meow mode cards.
 */
class MeowDividerDrawable(
    private val lineColor: Int,
    private val pawColor: Int,
    private val density: Float
) : Drawable() {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val pawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return

        val save = canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        val cy = h * 0.5f
        val cx = w * 0.5f
        val gap = 14f * density

        linePaint.color = lineColor
        linePaint.strokeWidth = 1.2f * density
        linePaint.pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)

        if (cx - gap > 4f * density) {
            canvas.drawLine(0f, cy, cx - gap, cy, linePaint)
            canvas.drawLine(cx + gap, cy, w, cy, linePaint)
        }
        pawPaint.color = pawColor
        MeowBackdropView.drawMiniPaw(canvas, cx, cy, 4.4f * density, 0f, pawPaint)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        linePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        linePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Soft dashed rounded empty-state container with a tiny peeking shorthair cat head in bottom-right.
 */
class MeowEmptyCardDrawable(
    private val fillColor: Int,
    private val strokeColor: Int,
    private val accentColor: Int,
    private val density: Float
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val catPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return

        val save = canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        val r = 16f * density
        val strokeW = 1.4f * density
        val half = strokeW * 0.5f
        rect.set(half, half, w - half, h - half)

        fillPaint.color = fillColor
        canvas.drawRoundRect(rect, r, r, fillPaint)

        val clipSave = canvas.save()
        val clipPath = Path().apply { addRoundRect(rect, r, r, Path.Direction.CW) }
        canvas.clipPath(clipPath)
        catPaint.color = Color.argb(38, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        MeowBackdropView.drawMiniCatHeadIcon(canvas, w - 24f * density, h - 8f * density, 16f * density, -10f, catPaint)
        canvas.restoreToCount(clipSave)

        strokePaint.color = strokeColor
        strokePaint.strokeWidth = strokeW
        strokePaint.pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
        canvas.drawRoundRect(rect, r, r, strokePaint)

        canvas.restoreToCount(save)
    }

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

/**
 * Circular Switch/SeekBar thumb with a centered miniature cat paw print.
 */
class MeowPawSwitchThumbDrawable(
    private val fillColor: Int,
    private val pawColor: Int,
    private val strokeColor: Int,
    private val sizePx: Int,
    private val density: Float
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun getIntrinsicWidth(): Int = sizePx
    override fun getIntrinsicHeight(): Int = sizePx

    override fun draw(canvas: Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val r = (minOf(b.width(), b.height()) * 0.5f - 1.5f * density).coerceAtLeast(4f)

        fillPaint.color = fillColor
        canvas.drawCircle(cx, cy, r, fillPaint)

        strokePaint.color = strokeColor
        strokePaint.strokeWidth = (1.2f * density).coerceAtLeast(1f)
        canvas.drawCircle(cx, cy, r, strokePaint)

        pawPaint.color = pawColor
        MeowBackdropView.drawMiniPaw(canvas, cx, cy + 0.5f * density, r * 0.56f, 0f, pawPaint)
    }

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
