package com.flashskip.ui

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches API 26+ variable Saira typefaces (wide Latin labels and tabular figures).
 * Chinese glyphs automatically fall back to the platform CJK sans-serif font.
 */
object EfTypeface {

    private val cache = ConcurrentHashMap<String, Typeface>()

    fun saira(context: Context, weight: Int, width: Int): Typeface {
        val key = "$weight:$width"
        return cache.getOrPut(key) {
            try {
                Typeface.Builder(context.assets, "fonts/saira.ttf")
                    .setFontVariationSettings("'wght' $weight, 'wdth' $width")
                    .build()
            } catch (_: Exception) {
                if (weight >= 600) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }
    }

    fun eyebrow(context: Context): Typeface = saira(context, weight = 600, width = 125)

    fun wordmark(context: Context): Typeface = saira(context, weight = 800, width = 125)

    fun field(context: Context): Typeface = saira(context, weight = 400, width = 112)

    fun fieldBold(context: Context): Typeface = saira(context, weight = 700, width = 112)

    fun button(context: Context): Typeface = saira(context, weight = 700, width = 112)

    fun figure(context: Context): Typeface = saira(context, weight = 800, width = 112)

    fun applyEyebrow(tv: TextView) {
        tv.typeface = eyebrow(tv.context)
        tv.letterSpacing = 0.14f
        tv.isAllCaps = true
    }

    fun applyWordmark(tv: TextView) {
        tv.typeface = wordmark(tv.context)
        tv.letterSpacing = -0.01f
    }

    fun applyField(tv: TextView, bold: Boolean = false) {
        tv.typeface = if (bold) fieldBold(tv.context) else field(tv.context)
        tv.fontFeatureSettings = "tnum"
    }

    fun applyFigure(tv: TextView) {
        tv.typeface = figure(tv.context)
        tv.fontFeatureSettings = "tnum"
    }
}
