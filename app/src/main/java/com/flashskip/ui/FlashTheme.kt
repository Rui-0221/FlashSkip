package com.flashskip.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import com.flashskip.data.StatsStore

/**
 * Dual-style visual theme registry:
 * - [ThemeStyle.CLASSIC]: Default consumer-friendly rounded Swiss Light/Dark palette.
 * - [ThemeStyle.ENDFIELD]: Industrial-manual Endfield UI theme derived strictly from
 *   endfield-ui design tokens (#F4F4F2/#191919 paper, #FFFA00 signal accent, 0dp cards,
 *   45-degree cut-corner interactive elements, Saira wide labels, 1px hairlines).
 */
object FlashTheme {

    enum class ThemeStyle(val key: String, val label: String, val shortLabel: String) {
        CLASSIC("classic", "日常简约", "日常简约"),
        ENDFIELD("endfield", "终末地", "终末地");

        companion object {
            fun fromKey(raw: String?): ThemeStyle =
                entries.firstOrNull { it.key.equals(raw, ignoreCase = true) } ?: CLASSIC
        }
    }

    data class Palette(
        val style: ThemeStyle,
        val isDark: Boolean,
        val paper: Int,
        val panel: Int,
        val panelSubtle: Int,
        val ink: Int,
        val ink2: Int,
        val rule: Int,
        val chip: Int,
        val signal: Int,
        val onSignal: Int,
        val signalWipeOnGo: Int,
        val trace: Int,
        val tagBg: Int,
        val tagFg: Int,
        val danger: Int,
        val dangerSolid: Int,
        val dangerFg: Int,
        val scrim: Int,
        val commandBar: Int,
        val commandBarInk: Int,
        val commandBarMuted: Int,
        val commandBarChip: Int,
        val commandBarRule: Int,
        val linkColor: Int,
        val statusOkBg: Int,
        val statusOkFg: Int,
        val statusOkStroke: Int?,
        val statusWarnBg: Int,
        val statusWarnFg: Int,
        val statusWarnStroke: Int?,
        val statusPausedBg: Int,
        val statusPausedFg: Int,
        val statusPausedStroke: Int?,
        val statusAlertBg: Int,
        val statusAlertFg: Int,
        val statusAlertStroke: Int?,
        val toastBg: Int,
        val toastFg: Int,
        val triA: Int,
        val triB: Int,
        val triC: Int
    )

    private val CLASSIC_LIGHT = Palette(
        style = ThemeStyle.CLASSIC,
        isDark = false,
        paper = 0xFFF4F6F9.toInt(),
        panel = 0xFFFFFFFF.toInt(),
        panelSubtle = 0xFFF8FAFC.toInt(),
        ink = 0xFF111827.toInt(),
        ink2 = 0xFF475569.toInt(),
        rule = 0xFFE2E8F0.toInt(),
        chip = 0xFFE8EEF5.toInt(),
        signal = 0xFF1D4ED8.toInt(),
        onSignal = 0xFFFFFFFF.toInt(),
        signalWipeOnGo = 0xFF1E40AF.toInt(),
        trace = 0xFF1D4ED8.toInt(),
        tagBg = 0xFFEEF2FF.toInt(),
        tagFg = 0xFF1E40AF.toInt(),
        danger = 0xFFB91C1C.toInt(),
        dangerSolid = 0xFFDC2626.toInt(),
        dangerFg = 0xFFFFFFFF.toInt(),
        scrim = 0x80111827.toInt(),
        commandBar = 0xFFFFFFFF.toInt(),
        commandBarInk = 0xFF111827.toInt(),
        commandBarMuted = 0xFF475569.toInt(),
        commandBarChip = 0xFFE8EEF5.toInt(),
        commandBarRule = 0xFFE8EEF5.toInt(),
        linkColor = 0xFF1D4ED8.toInt(),
        statusOkBg = 0xFFE6F6EF.toInt(),
        statusOkFg = 0xFF046C4E.toInt(),
        statusOkStroke = null,
        statusWarnBg = 0xFFFEF3C7.toInt(),
        statusWarnFg = 0xFF92400E.toInt(),
        statusWarnStroke = null,
        statusPausedBg = 0xFFE2E8F0.toInt(),
        statusPausedFg = 0xFF334155.toInt(),
        statusPausedStroke = null,
        statusAlertBg = 0xFFFEE2E2.toInt(),
        statusAlertFg = 0xFF991B1B.toInt(),
        statusAlertStroke = null,
        toastBg = 0xFF1E293B.toInt(),
        toastFg = 0xFFF8FAFC.toInt(),
        triA = 0xFF1D4ED8.toInt(),
        triB = 0xFF1D4ED8.toInt(),
        triC = 0xFF1D4ED8.toInt()
    )

    private val CLASSIC_DARK = Palette(
        style = ThemeStyle.CLASSIC,
        isDark = true,
        paper = 0xFF0E1319.toInt(),
        panel = 0xFF171F29.toInt(),
        panelSubtle = 0xFF1E2835.toInt(),
        ink = 0xFFF1F5F9.toInt(),
        ink2 = 0xFF9FB0C4.toInt(),
        rule = 0xFF2A3544.toInt(),
        chip = 0xFF232E3C.toInt(),
        signal = 0xFF2563EB.toInt(),
        onSignal = 0xFFFFFFFF.toInt(),
        signalWipeOnGo = 0xFF1D4ED8.toInt(),
        trace = 0xFF60A5FA.toInt(),
        tagBg = 0xFF1E3A5F.toInt(),
        tagFg = 0xFFBFDBFE.toInt(),
        danger = 0xFFFCA5A5.toInt(),
        dangerSolid = 0xFFDC2626.toInt(),
        dangerFg = 0xFFFFFFFF.toInt(),
        scrim = 0xA6000000.toInt(),
        commandBar = 0xFF171F29.toInt(),
        commandBarInk = 0xFFF1F5F9.toInt(),
        commandBarMuted = 0xFF9FB0C4.toInt(),
        commandBarChip = 0xFF232E3C.toInt(),
        commandBarRule = 0xFF232E3C.toInt(),
        linkColor = 0xFF60A5FA.toInt(),
        statusOkBg = 0xFF0E3226.toInt(),
        statusOkFg = 0xFF6EE7B7.toInt(),
        statusOkStroke = null,
        statusWarnBg = 0xFF33240B.toInt(),
        statusWarnFg = 0xFFFCD34D.toInt(),
        statusWarnStroke = null,
        statusPausedBg = 0xFF24303F.toInt(),
        statusPausedFg = 0xFFCBD5E1.toInt(),
        statusPausedStroke = null,
        statusAlertBg = 0xFF3B1619.toInt(),
        statusAlertFg = 0xFFFCA5A5.toInt(),
        statusAlertStroke = null,
        toastBg = 0xFFF1F5F9.toInt(),
        toastFg = 0xFF0F172A.toInt(),
        triA = 0xFF2563EB.toInt(),
        triB = 0xFF2563EB.toInt(),
        triC = 0xFF2563EB.toInt()
    )

    private val ENDFIELD_LIGHT = Palette(
        style = ThemeStyle.ENDFIELD,
        isDark = false,
        paper = 0xFFF4F4F2.toInt(),
        panel = 0xFFFFFFFF.toInt(),
        panelSubtle = 0xFFF4F4F2.toInt(),
        ink = 0xFF161616.toInt(),
        ink2 = 0xFF5C5C5C.toInt(),
        rule = 0xFFD9D9D6.toInt(),
        chip = 0xFFE4E4E0.toInt(),
        signal = 0xFFFFFA00.toInt(),
        onSignal = 0xFF161616.toInt(),
        signalWipeOnGo = 0xFFE2DD00.toInt(),
        trace = 0xFF161616.toInt(),
        tagBg = 0xFF161616.toInt(),
        tagFg = 0xFFFFFFFF.toInt(),
        danger = 0xFFB3261E.toInt(),
        dangerSolid = 0xFFB3261E.toInt(),
        dangerFg = 0xFFFFFFFF.toInt(),
        scrim = 0x80161616.toInt(),
        commandBar = 0xFF161616.toInt(),
        commandBarInk = 0xFFEDEDED.toInt(),
        commandBarMuted = 0xFFA3A3A3.toInt(),
        commandBarChip = 0xFF2E2E2E.toInt(),
        commandBarRule = 0xFF353535.toInt(),
        linkColor = 0xFF161616.toInt(), // Never yellow on light paper (1.1:1 contrast)
        statusOkBg = 0xFFFFFFFF.toInt(),
        statusOkFg = 0xFF161616.toInt(),
        statusOkStroke = 0xFF161616.toInt(),
        statusWarnBg = 0xFFFFFFFF.toInt(),
        statusWarnFg = 0xFF161616.toInt(),
        statusWarnStroke = 0xFF5C5C5C.toInt(),
        statusPausedBg = 0xFFE4E4E0.toInt(),
        statusPausedFg = 0xFF5C5C5C.toInt(),
        statusPausedStroke = 0xFF5C5C5C.toInt(),
        statusAlertBg = 0xFFFFFA00.toInt(), // Endfield rule: no red for non-delete alerts
        statusAlertFg = 0xFF161616.toInt(),
        statusAlertStroke = null,
        toastBg = 0xFF161616.toInt(),
        toastFg = 0xFFEDEDED.toInt(),
        triA = 0xFFFF00F0.toInt(),
        triB = 0xFFFFFA00.toInt(),
        triC = 0xFF00FFA2.toInt()
    )

    private val ENDFIELD_DARK = Palette(
        style = ThemeStyle.ENDFIELD,
        isDark = true,
        paper = 0xFF191919.toInt(),
        panel = 0xFF222222.toInt(),
        panelSubtle = 0xFF292929.toInt(),
        ink = 0xFFEDEDED.toInt(),
        ink2 = 0xFFA3A3A3.toInt(),
        rule = 0xFF353535.toInt(),
        chip = 0xFF2E2E2E.toInt(),
        signal = 0xFFFFFA00.toInt(),
        onSignal = 0xFF161616.toInt(),
        signalWipeOnGo = 0xFFE2DD00.toInt(),
        trace = 0xFFFFFA00.toInt(), // Yellow works cleanly as line/trace on #191919 (15.8:1)
        tagBg = 0xFF3A3A3A.toInt(),
        tagFg = 0xFFEDEDED.toInt(),
        danger = 0xFFFF8A7A.toInt(),
        dangerSolid = 0xFFB3261E.toInt(),
        dangerFg = 0xFFFFFFFF.toInt(),
        scrim = 0xA0000000.toInt(),
        commandBar = 0xFF161616.toInt(),
        commandBarInk = 0xFFEDEDED.toInt(),
        commandBarMuted = 0xFFA3A3A3.toInt(),
        commandBarChip = 0xFF2E2E2E.toInt(),
        commandBarRule = 0xFF353535.toInt(),
        linkColor = 0xFFFFFA00.toInt(),
        statusOkBg = 0xFF222222.toInt(),
        statusOkFg = 0xFFEDEDED.toInt(),
        statusOkStroke = 0xFFEDEDED.toInt(),
        statusWarnBg = 0xFF222222.toInt(),
        statusWarnFg = 0xFFEDEDED.toInt(),
        statusWarnStroke = 0xFFA3A3A3.toInt(),
        statusPausedBg = 0xFF2E2E2E.toInt(),
        statusPausedFg = 0xFFA3A3A3.toInt(),
        statusPausedStroke = 0xFFA3A3A3.toInt(),
        statusAlertBg = 0xFFFFFA00.toInt(),
        statusAlertFg = 0xFF161616.toInt(),
        statusAlertStroke = null,
        toastBg = 0xFF161616.toInt(),
        toastFg = 0xFFEDEDED.toInt(),
        triA = 0xFFFF00F0.toInt(),
        triB = 0xFFFFFA00.toInt(),
        triC = 0xFF00FFA2.toInt()
    )

    fun isDarkActive(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun currentStyle(context: Context): ThemeStyle =
        ThemeStyle.fromKey(StatsStore(context).uiThemeStyle)

    fun isEndfield(context: Context): Boolean = currentStyle(context) == ThemeStyle.ENDFIELD

    fun palette(context: Context): Palette = paletteFor(currentStyle(context), isDarkActive(context))

    fun paletteFor(style: ThemeStyle, dark: Boolean): Palette = when (style) {
        ThemeStyle.CLASSIC -> if (dark) CLASSIC_DARK else CLASSIC_LIGHT
        ThemeStyle.ENDFIELD -> if (dark) ENDFIELD_DARK else ENDFIELD_LIGHT
    }

    /** Builds square mechanical switch track for Endfield mode. */
    fun buildEndfieldSwitchTrack(context: Context, p: Palette): Drawable {
        val d = context.resources.displayMetrics.density
        val w = (50f * d).toInt()
        val h = (26f * d).toInt()
        val pad = (3f * d).toInt()

        val on = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setColor(p.signal)
            setSize(w, h)
            setPadding(pad, pad, pad, pad)
        }
        val off = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setColor(p.chip)
            setStroke((1f * d).toInt().coerceAtLeast(1), p.rule)
            setSize(w, h)
            setPadding(pad, pad, pad, pad)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), on)
            addState(intArrayOf(), off)
        }
    }

    /** Builds square switch knob (dark ink when ON, panel white/dark when OFF). */
    fun buildEndfieldSwitchThumb(context: Context, p: Palette): Drawable {
        val d = context.resources.displayMetrics.density
        val size = (20f * d).toInt()

        fun knob(fill: Int, strokeColor: Int?): Drawable {
            val rect = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 0f
                setColor(fill)
                if (strokeColor != null) {
                    setStroke((1f * d).toInt().coerceAtLeast(1), strokeColor)
                }
                setSize(size, size)
            }
            return LayerDrawable(arrayOf(rect)).apply {
                val inset = (3f * d).toInt()
                setLayerInset(0, inset, inset, inset, inset)
            }
        }

        val onThumb = knob(p.onSignal, null)
        val offThumb = knob(p.panel, if (p.isDark) null else p.rule)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), onThumb)
            addState(intArrayOf(), offThumb)
        }
    }

    /** Builds square-thumb Endfield SeekBar track & knob. */
    fun buildEndfieldSeekBarThumb(context: Context, p: Palette): Drawable {
        val d = context.resources.displayMetrics.density
        val size = (18f * d).toInt()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setColor(p.signal)
            setStroke((2f * d).toInt().coerceAtLeast(2), p.onSignal)
            setSize(size, size)
        }
    }

    fun buildEndfieldSeekBarProgress(context: Context, p: Palette): Drawable {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setColor(p.chip)
        }
        val fg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setColor(p.trace)
        }
        val clip = android.graphics.drawable.ClipDrawable(fg, android.view.Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
        return LayerDrawable(arrayOf(bg, clip)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    /** Crisp 0dp input box for Endfield mode, focused = 2dp bottom trace line look. */
    fun buildEndfieldInputBg(context: Context, p: Palette): Drawable {
        val d = context.resources.displayMetrics.density
        val stroke = (1f * d).toInt().coerceAtLeast(1)
        val focusStroke = (2f * d).toInt().coerceAtLeast(2)

        val idle = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setColor(p.panel)
            setStroke(stroke, p.rule)
        }
        val base = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setColor(p.panel)
        }
        val bottomTrace = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setColor(Color.TRANSPARENT)
            setStroke(focusStroke, p.trace)
        }
        val focused = LayerDrawable(arrayOf(base, bottomTrace)).apply {
            setLayerInset(1, -focusStroke * 2, -focusStroke * 2, -focusStroke * 2, 0)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), idle)
        }
    }
}
