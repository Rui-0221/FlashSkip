package com.flashskip.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.flashskip.R

/**
 * Dual-theme modal dialogs and floating bottom confirmation banner:
 * - Classic theme: rounded 20dp surface, hairline stroke, soft toast bar.
 * - Endfield theme: 26dp bottom-right cut panel with top-left 56x5dp ink tab,
 *   Saira 125% // eyebrow headers, cut index tags, and command-bar yellow-stripe toast.
 */
object EfDialogs {

    private val SMOOTH = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    private val MECH = PathInterpolator(0.7f, 0f, 0.2f, 1f)
    private val SNAP = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    private val toastHandler = Handler(Looper.getMainLooper())
    private var activeToast: View? = null

    fun showConfirmDialog(
        activity: Activity,
        eyebrow: String,
        title: String,
        message: String,
        confirmText: String,
        confirmDanger: Boolean = false,
        onConfirm: () -> Unit
    ): AlertDialog {
        val density = activity.resources.displayMetrics.density
        val endfield = FlashTheme.isEndfield(activity)
        val meow = FlashTheme.isMeow(activity)
        val p = FlashTheme.palette(activity)
        val pad = (20f * density).toInt()
        val extraCutClearance = if (endfield) (8f * density).toInt() else 0
        val topPad = when {
            endfield -> (22f * density).toInt()
            meow -> (28f * density).toInt()
            else -> pad
        }

        val root = buildDialogFrame(activity)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, topPad, pad, pad + extraCutClearance)
        }

        val resolvedEyebrow = eyebrow.ifBlank { if (confirmDanger) "CONFIRM DELETE" else "CONFIRM" }
        if (endfield) {
            content.addView(makeEndfieldEyebrow(activity, "// $resolvedEyebrow", p))
        } else if (eyebrow.isNotBlank() && eyebrow.none { it in 'A'..'Z' && eyebrow.all { ch -> ch.isUpperCase() || ch == ' ' || ch == '/' } }) {
            content.addView(makeSubtitleTag(activity, eyebrow, p))
        }
        content.addView(makeTitle(activity, title, p))

        val body = TextView(activity).apply {
            text = message
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(p.ink2)
            setLineSpacing(0f, 1.5f)
            setPadding(0, (10f * density).toInt(), 0, (18f * density).toInt())
        }
        content.addView(body)

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            if (endfield) setPadding(0, 0, (6f * density).toInt(), 0)
        }

        lateinit var dialog: AlertDialog
        val btnCancel = EfButton(activity).apply {
            text = "取消"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setOnClickListener { dialog.dismiss() }
        }
        val btnConfirm = EfButton(activity).apply {
            text = confirmText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setEfKind(if (confirmDanger) EfButton.Kind.DANGER else EfButton.Kind.GO)
            setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
        }
        val gap = (10f * density).toInt()
        actions.addView(btnCancel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = gap })
        actions.addView(btnConfirm, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        content.addView(actions)
        root.addView(content)

        dialog = AlertDialog.Builder(activity)
            .setView(root)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        return dialog
    }

    fun showAddExclusionDialog(
        activity: Activity,
        onAdd: (String) -> Unit
    ): AlertDialog {
        val density = activity.resources.displayMetrics.density
        val endfield = FlashTheme.isEndfield(activity)
        val meow = FlashTheme.isMeow(activity)
        val p = FlashTheme.palette(activity)
        val pad = (20f * density).toInt()
        val extraCutClearance = if (endfield) (8f * density).toInt() else 0
        val topPad = when {
            endfield -> (22f * density).toInt()
            meow -> (28f * density).toInt()
            else -> pad
        }

        val root = buildDialogFrame(activity)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, topPad, pad, pad + extraCutClearance)
        }

        if (endfield) {
            content.addView(makeEndfieldEyebrow(activity, "// EXCLUSION", p))
        }
        content.addView(makeTitle(activity, "添加不想跳过的应用", p))

        val label = TextView(activity).apply {
            text = "请输入该应用的包名（例如 com.example.app）"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(p.ink2)
            setPadding(0, (8f * density).toInt(), 0, (8f * density).toInt())
        }
        content.addView(label)

        val input = EditText(activity).apply {
            if (endfield) {
                background = FlashTheme.buildEndfieldInputBg(activity, p)
                EfTypeface.applyField(this)
            } else if (meow) {
                background = FlashTheme.buildMeowInputBg(activity, p)
            } else {
                setBackgroundResource(R.drawable.ef_input_bg)
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            setTextColor(p.ink)
            setHintTextColor(p.ink2)
            hint = "如 com.example.app"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            minHeight = (48f * density).toInt()
            val pH = (12f * density).toInt()
            val pV = (10f * density).toInt()
            setPadding(pH, pV, pH, pV)
        }
        content.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val helper = TextView(activity).apply {
            text = "不知道包名？长按桌面该软件图标点「应用信息」，拉到最底部即可看到“包名”并复制。添加后闪跳将不再自动跳过它。"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(0f, 1.45f)
            setTextColor(p.ink2)
            setPadding(0, (8f * density).toInt(), 0, (16f * density).toInt())
        }
        content.addView(helper)

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            if (endfield) setPadding(0, 0, (6f * density).toInt(), 0)
        }
        lateinit var dialog: AlertDialog
        val btnCancel = EfButton(activity).apply {
            text = "取消"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setOnClickListener { dialog.dismiss() }
        }
        val btnSave = EfButton(activity).apply {
            text = "加入名单"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setEfKind(EfButton.Kind.GO)
            setOnClickListener {
                val pkg = input.text.toString().trim()
                if (pkg.isEmpty()) {
                    helper.text = "! 请先输入完整的应用包名（例如 com.example.app）再点保存。"
                    if (endfield) {
                        helper.setBackgroundColor(p.signal)
                        helper.setTextColor(p.onSignal)
                        val pErr = (8f * density).toInt()
                        helper.setPadding(pErr, pErr, pErr, pErr)
                    } else {
                        helper.setTextColor(p.danger)
                    }
                } else {
                    dialog.dismiss()
                    onAdd(pkg)
                }
            }
        }
        actions.addView(btnCancel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = (10f * density).toInt() })
        actions.addView(btnSave)
        content.addView(actions)
        root.addView(content)

        dialog = AlertDialog.Builder(activity).setView(root).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        return dialog
    }

    fun showActionPickerDialog(
        activity: Activity,
        eyebrow: String,
        title: String,
        items: List<Pair<String, String>>,
        onPick: (Int) -> Unit
    ): AlertDialog {
        val density = activity.resources.displayMetrics.density
        val endfield = FlashTheme.isEndfield(activity)
        val meow = FlashTheme.isMeow(activity)
        val p = FlashTheme.palette(activity)
        val pad = (20f * density).toInt()
        val extraCutClearance = if (endfield) (8f * density).toInt() else 0
        val topPad = when {
            endfield -> (22f * density).toInt()
            meow -> (28f * density).toInt()
            else -> pad
        }

        val root = buildDialogFrame(activity)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, topPad, pad, pad + extraCutClearance)
        }
        val resolvedEyebrow = eyebrow.ifBlank { "SELECT" }
        if (endfield) {
            content.addView(makeEndfieldEyebrow(activity, "// $resolvedEyebrow", p))
        } else if (eyebrow.isNotBlank() && eyebrow.none { it in 'A'..'Z' && eyebrow.all { ch -> ch.isUpperCase() || ch == ' ' || ch == '/' || ch.isDigit() } }) {
            content.addView(makeSubtitleTag(activity, eyebrow, p))
        }
        content.addView(makeTitle(activity, title, p))

        val scroll = ScrollView(activity)
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (10f * density).toInt(), 0, (10f * density).toInt())
        }

        lateinit var dialog: AlertDialog
        items.forEachIndexed { index, (tag, label) ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (48f * density).toInt()
                isFocusable = true
                isClickable = true
                if (endfield) {
                    background = EfCutDrawable.buildInsetFocusSelector(
                        fillColor = Color.TRANSPARENT,
                        focusStrokeColor = p.trace,
                        strokeWidthPx = (2f * density).toInt().coerceAtLeast(2),
                        insetPx = 0,
                        cornerRadiusPx = 0f
                    )
                } else {
                    setBackgroundResource(R.drawable.ef_focus_row_bg)
                }
                setPadding((8f * density).toInt(), (10f * density).toInt(), (8f * density).toInt(), (10f * density).toInt())
                setOnClickListener {
                    dialog.dismiss()
                    onPick(index)
                }
            }

            if (tag.isNotEmpty()) {
                val displayTag = if (endfield && tag.all { it.isDigit() } && tag.length == 1) {
                    tag.padStart(2, '0')
                } else {
                    tag
                }
                val tagView = TextView(activity).apply {
                    text = displayTag
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setTextColor(p.tagFg)
                    if (endfield) {
                        EfTypeface.applyField(this, bold = true)
                        background = EfCutDrawable.build(
                            fillColor = p.tagBg,
                            cutBottomRightPx = 7f * density,
                            useCutCorners = true
                        )
                        setPadding((7f * density).toInt(), (2f * density).toInt(), (11f * density).toInt(), (2f * density).toInt())
                    } else {
                        background = EfCutDrawable.build(
                            fillColor = p.tagBg,
                            cutBottomRightPx = 8f * density,
                            useCutCorners = false
                        )
                        setTypeface(typeface, Typeface.BOLD)
                        setPadding((8f * density).toInt(), (3f * density).toInt(), (8f * density).toInt(), (3f * density).toInt())
                    }
                }
                row.addView(tagView, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (10f * density).toInt() })
            }

            val labelView = TextView(activity).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                setTextColor(p.ink)
            }
            row.addView(labelView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            list.addView(row)

            if (index < items.lastIndex) {
                val divider = View(activity).apply {
                    setBackgroundColor(p.rule)
                }
                list.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1f * density).toInt().coerceAtLeast(1)))
            }
        }
        scroll.addView(list)
        content.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (320f * density).toInt()
        ))

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            if (endfield) setPadding(0, 0, (6f * density).toInt(), 0)
        }
        val btnCancel = EfButton(activity).apply {
            text = "关闭"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setOnClickListener { dialog.dismiss() }
        }
        actions.addView(btnCancel)
        content.addView(actions)
        root.addView(content)

        dialog = AlertDialog.Builder(activity).setView(root).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        return dialog
    }

    fun showInfoStepsDialog(
        activity: Activity,
        title: String,
        subtitle: String,
        steps: List<String>
    ): AlertDialog {
        val density = activity.resources.displayMetrics.density
        val endfield = FlashTheme.isEndfield(activity)
        val meow = FlashTheme.isMeow(activity)
        val p = FlashTheme.palette(activity)
        val pad = (20f * density).toInt()
        val extraCutClearance = if (endfield) (8f * density).toInt() else 0
        val topPad = when {
            endfield -> (22f * density).toInt()
            meow -> (28f * density).toInt()
            else -> pad
        }

        val root = buildDialogFrame(activity)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, topPad, pad, pad + extraCutClearance)
        }
        if (endfield) {
            content.addView(makeEndfieldEyebrow(activity, "// GUIDE", p))
        }
        content.addView(makeTitle(activity, title, p))

        val sub = TextView(activity).apply {
            text = subtitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(p.ink2)
            setLineSpacing(0f, 1.4f)
            setPadding(0, (6f * density).toInt(), 0, (12f * density).toInt())
        }
        content.addView(sub)

        val scroll = ScrollView(activity)
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        steps.forEachIndexed { idx, step ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                setPadding(0, (6f * density).toInt(), 0, (6f * density).toInt())
            }
            val num = TextView(activity).apply {
                text = if (endfield) "${idx + 1}".padStart(2, '0') else "${idx + 1}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setTextColor(p.tagFg)
                if (endfield) {
                    EfTypeface.applyField(this, bold = true)
                    background = EfCutDrawable.build(
                        fillColor = p.tagBg,
                        cutBottomRightPx = 7f * density,
                        useCutCorners = true
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        (24f * density).toInt()
                    ).apply {
                        marginEnd = (10f * density).toInt()
                        topMargin = (2f * density).toInt()
                    }
                    setPadding((7f * density).toInt(), 0, (10f * density).toInt(), 0)
                } else {
                    setTypeface(typeface, Typeface.BOLD)
                    background = EfCutDrawable.build(
                        fillColor = p.tagBg,
                        cutBottomRightPx = 12f * density,
                        useCutCorners = false
                    )
                    val size = (24f * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        marginEnd = (10f * density).toInt()
                        topMargin = (2f * density).toInt()
                    }
                }
            }
            val txt = TextView(activity).apply {
                text = step
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                setTextColor(p.ink)
                setLineSpacing(0f, 1.45f)
            }
            row.addView(num)
            row.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            list.addView(row)
        }
        scroll.addView(list)
        content.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (14f * density).toInt() })

        lateinit var dialog: AlertDialog
        val btnClose = EfButton(activity).apply {
            text = "我知道了"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setEfKind(EfButton.Kind.GO)
            setOnClickListener { dialog.dismiss() }
        }
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            if (endfield) setPadding(0, 0, (6f * density).toInt(), 0)
            addView(btnClose)
        }
        content.addView(actions)
        root.addView(content)

        dialog = AlertDialog.Builder(activity).setView(root).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        return dialog
    }

    fun showConfigAuditDialog(
        activity: Activity,
        report: ConsoleFormatter.ConfigAuditReport,
        copy: ConsoleFormatter.ThemeActionCopy,
        onAction: (ConsoleFormatter.AuditAction) -> Unit
    ): AlertDialog {
        val density = activity.resources.displayMetrics.density
        val endfield = FlashTheme.isEndfield(activity)
        val meow = FlashTheme.isMeow(activity)
        val p = FlashTheme.palette(activity)
        val pad = (20f * density).toInt()
        val extraCutClearance = if (endfield) (8f * density).toInt() else 0
        val topPad = when {
            endfield -> (22f * density).toInt()
            meow -> (28f * density).toInt()
            else -> pad
        }
        val hairlinePx = (1f * density).toInt().coerceAtLeast(1)

        val root = buildDialogFrame(activity)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, topPad, pad, pad + extraCutClearance)
        }
        if (endfield) {
            content.addView(makeEndfieldEyebrow(activity, "// ${copy.dialogEyebrow}", p))
        } else {
            content.addView(makeSubtitleTag(activity, copy.dialogEyebrow, p))
        }
        content.addView(makeTitle(activity, copy.dialogTitle, p))

        // Summary box styled per active theme
        val summaryBox = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            when {
                endfield -> {
                    background = EfCutDrawable.buildSquareBox(p.panelSubtle, p.rule, hairlinePx)
                    setPadding(0, 0, (12f * density).toInt(), 0)
                }
                meow -> {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 16f * density
                        setColor(p.panelSubtle)
                        setStroke(hairlinePx, p.rule)
                    }
                    val p12 = (12f * density).toInt()
                    setPadding(p12, p12, p12, p12)
                }
                else -> {
                    setBackgroundResource(R.drawable.fs_tile_bg)
                    val p12 = (12f * density).toInt()
                    setPadding(p12, p12, p12, p12)
                }
            }
        }
        if (endfield) {
            val accent = View(activity).apply { setBackgroundColor(p.signal) }
            summaryBox.addView(accent, LinearLayout.LayoutParams(
                (5f * density).toInt(),
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { marginEnd = (10f * density).toInt() })
        }
        val summaryCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            if (endfield) {
                val pV = (10f * density).toInt()
                setPadding(0, pV, 0, pV)
            }
        }
        val summaryTitle = TextView(activity).apply {
            text = "${report.passedCount} / ${report.totalCount} 项通过 · ${report.headline}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.ink)
        }
        val summaryBody = TextView(activity).apply {
            text = report.summary
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(p.ink2)
            setLineSpacing(0f, 1.4f)
            setPadding(0, (3f * density).toInt(), 0, 0)
        }
        summaryCol.addView(summaryTitle)
        summaryCol.addView(summaryBody)
        summaryBox.addView(summaryCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(summaryBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (10f * density).toInt()
            bottomMargin = (10f * density).toInt()
        })

        val scroll = ScrollView(activity)
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        lateinit var dialog: AlertDialog

        report.items.forEachIndexed { idx, item ->
            val itemBox = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (8f * density).toInt(), 0, (8f * density).toInt())
            }
            val headerRow = EfWrapLayout(activity)

            val indexTag = TextView(activity).apply {
                text = item.indexLabel
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(p.tagFg)
                if (endfield) {
                    EfTypeface.applyField(this, bold = true)
                    background = EfCutDrawable.build(
                        fillColor = p.tagBg,
                        cutBottomRightPx = 6f * density,
                        useCutCorners = true
                    )
                    setPadding((7f * density).toInt(), (2f * density).toInt(), (10f * density).toInt(), (2f * density).toInt())
                } else {
                    setTypeface(typeface, Typeface.BOLD)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 999f * density
                        setColor(p.tagBg)
                    }
                    setPadding((8f * density).toInt(), (2f * density).toInt(), (8f * density).toInt(), (2f * density).toInt())
                }
            }
            val titleView = TextView(activity).apply {
                text = item.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(p.ink)
            }
            val badgeView = TextView(activity).apply {
                text = item.statusLabel
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTypeface(typeface, Typeface.BOLD)
                applyAuditBadgeStyle(this, item.status, p, endfield, meow, density, hairlinePx)
            }

            headerRow.addView(indexTag)
            headerRow.addView(titleView)
            headerRow.addView(badgeView)
            itemBox.addView(headerRow)

            val detailView = TextView(activity).apply {
                text = item.detail
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(p.ink2)
                setLineSpacing(0f, 1.42f)
                setPadding(0, (6f * density).toInt(), 0, 0)
            }
            itemBox.addView(detailView)

            if (item.primaryAction != null && item.primaryActionLabel != null) {
                val btnRow = EfWrapLayout(activity)
                val pBtn = makeAuditChipButton(
                    activity = activity,
                    label = item.primaryActionLabel,
                    kind = if (item.status != ConsoleFormatter.AuditItemStatus.PASS) EfButton.Kind.GO else EfButton.Kind.NORMAL,
                    density = density
                ) {
                    dialog.dismiss()
                    onAction(item.primaryAction)
                }
                btnRow.addView(pBtn)

                if (item.secondaryAction != null && item.secondaryActionLabel != null) {
                    val sBtn = makeAuditChipButton(
                        activity = activity,
                        label = item.secondaryActionLabel,
                        kind = EfButton.Kind.NORMAL,
                        density = density
                    ) {
                        dialog.dismiss()
                        onAction(item.secondaryAction)
                    }
                    btnRow.addView(sBtn)
                }
                itemBox.addView(btnRow, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8f * density).toInt() })
            }

            list.addView(itemBox)

            if (idx < report.items.lastIndex) {
                val divider = View(activity)
                if (meow) {
                    divider.background = MeowDividerDrawable(p.rule, p.signal, density)
                    list.addView(divider, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (10f * density).toInt().coerceAtLeast(1)
                    ))
                } else {
                    divider.setBackgroundColor(p.rule)
                    list.addView(divider, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        hairlinePx
                    ))
                }
            }
        }
        scroll.addView(list)
        val maxScrollH = (activity.resources.displayMetrics.heightPixels * 0.48f).toInt()
            .coerceAtLeast((240f * density).toInt())
        content.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            maxScrollH
        ).apply { bottomMargin = (14f * density).toInt() })

        val footer = EfWrapLayout(activity).apply {
            if (endfield) setPadding(0, 0, (6f * density).toInt(), 0)
        }
        val btnLiveTest = EfButton(activity).apply {
            text = copy.liveTestLabel
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setEfKind(if (report.canRunLiveTest) EfButton.Kind.GO else EfButton.Kind.NORMAL)
            setOnClickListener {
                dialog.dismiss()
                onAction(ConsoleFormatter.AuditAction.RUN_LIVE_TEST)
            }
        }
        val btnClose = EfButton(activity).apply {
            text = copy.closeLabel
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setEfKind(EfButton.Kind.NORMAL)
            setOnClickListener { dialog.dismiss() }
        }
        footer.addView(btnLiveTest)
        footer.addView(btnClose)
        content.addView(footer)
        root.addView(content)

        dialog = AlertDialog.Builder(activity).setView(root).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        return dialog
    }

    private fun makeAuditChipButton(
        activity: Activity,
        label: String,
        kind: EfButton.Kind,
        density: Float,
        onClick: () -> Unit
    ): EfButton = EfButton(activity).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        setEfKind(kind)
        val pH = (12f * density).toInt()
        val pV = (6f * density).toInt()
        setPadding(pH, pV, pH, pV)
        minHeight = (38f * density).toInt()
        minimumHeight = minHeight
        setOnClickListener { onClick() }
    }

    private fun applyAuditBadgeStyle(
        view: TextView,
        status: ConsoleFormatter.AuditItemStatus,
        p: FlashTheme.Palette,
        endfield: Boolean,
        meow: Boolean,
        density: Float,
        strokePx: Int
    ) {
        val (bg, fg, st) = when (status) {
            ConsoleFormatter.AuditItemStatus.PASS -> Triple(p.statusOkBg, p.statusOkFg, p.statusOkStroke)
            ConsoleFormatter.AuditItemStatus.WARN -> Triple(p.statusWarnBg, p.statusWarnFg, p.statusWarnStroke)
            ConsoleFormatter.AuditItemStatus.FAIL -> Triple(p.statusAlertBg, p.statusAlertFg, p.statusAlertStroke)
        }
        view.setTextColor(fg)
        val pH = (9f * density).toInt()
        val pV = (3f * density).toInt()
        when {
            endfield -> {
                view.background = when (status) {
                    ConsoleFormatter.AuditItemStatus.PASS -> EfCutDrawable.buildSquareBox(bg, st, strokePx)
                    ConsoleFormatter.AuditItemStatus.WARN -> EfCutDrawable.buildSquareBox(bg, st, strokePx, dashed = true, dashWidthPx = 4f * density, dashGapPx = 3f * density)
                    ConsoleFormatter.AuditItemStatus.FAIL -> EfCutDrawable.build(bg, cutBottomRightPx = 6f * density, useCutCorners = true)
                }
                view.setPadding(pH, pV, (if (status == ConsoleFormatter.AuditItemStatus.FAIL) 11f * density else 9f * density).toInt(), pV)
            }
            meow -> {
                view.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 999f * density
                    setColor(bg)
                    if (st != null) setStroke(strokePx, st)
                }
                view.setPadding(pH, pV, pH, pV)
            }
            else -> {
                view.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 8f * density
                    setColor(bg)
                    if (st != null) setStroke(strokePx, st)
                }
                view.setPadding(pH, pV, pH, pV)
            }
        }
    }

    fun showDarkToast(host: FrameLayout, message: String, bottomOffsetPx: Int = 0) {
        toastHandler.removeCallbacksAndMessages(null)
        activeToast?.let { host.removeView(it) }

        val context = host.context
        val density = context.resources.displayMetrics.density
        val endfield = FlashTheme.isEndfield(context)
        val meow = FlashTheme.isMeow(context)
        val p = FlashTheme.palette(context)

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (endfield) {
                setBackgroundColor(p.toastBg)
                elevation = 0f
            } else if (meow) {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 999f * density
                    setColor(p.toastBg)
                    setStroke((1.5f * density).toInt().coerceAtLeast(1), p.signal)
                }
                elevation = 3f * density
            } else {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12f * density
                    setColor(p.toastBg)
                }
                elevation = 4f * density
            }
        }
        if (endfield) {
            val stripe = View(context).apply {
                setBackgroundColor(p.signal)
            }
            bar.addView(stripe, LinearLayout.LayoutParams(
                (6f * density).toInt(),
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        } else if (meow) {
            val pawBadge = android.widget.ImageView(context).apply {
                setImageResource(R.drawable.ic_meow_paw)
                imageTintList = android.content.res.ColorStateList.valueOf(p.signal)
            }
            val size = (20f * density).toInt()
            bar.addView(pawBadge, LinearLayout.LayoutParams(size, size).apply {
                marginStart = (14f * density).toInt()
            })
        }

        val tv = TextView(context).apply {
            text = message
            setTextColor(p.toastFg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            if (endfield) setTypeface(Typeface.DEFAULT_BOLD)
            val pH = (16f * density).toInt()
            val pV = (12f * density).toInt()
            setPadding(pH, pV, pH, pV)
        }
        bar.addView(tv, LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        ))

        val margin = (16f * density).toInt()
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ).apply {
            leftMargin = margin
            rightMargin = margin
            bottomMargin = margin + bottomOffsetPx
        }
        host.addView(bar, lp)
        activeToast = bar
        bar.announceForAccessibility(message)

        val animated = ValueAnimator.areAnimatorsEnabled()
        if (animated) {
            if (endfield) {
                bar.translationX = -24f * density
                bar.alpha = 0f
                bar.animate().translationX(0f).alpha(1f).setDuration(280L).setInterpolator(SNAP).start()
            } else {
                bar.translationY = 16f * density
                bar.alpha = 0f
                bar.animate().translationY(0f).alpha(1f).setDuration(200L).setInterpolator(SMOOTH).start()
            }
        }

        toastHandler.postDelayed({
            if (activeToast !== bar) return@postDelayed
            if (animated) {
                val exitAnim = if (endfield) {
                    bar.animate().translationX(24f * density).alpha(0f).setDuration(160L).setInterpolator(MECH)
                } else {
                    bar.animate().translationY(12f * density).alpha(0f).setDuration(150L).setInterpolator(SMOOTH)
                }
                exitAnim.withEndAction {
                    host.removeView(bar)
                    if (activeToast === bar) activeToast = null
                }.start()
            } else {
                host.removeView(bar)
                activeToast = null
            }
        }, if (endfield) 2000L else 2200L)
    }

    private fun buildDialogFrame(activity: Activity): LinearLayout {
        val density = activity.resources.displayMetrics.density
        val endfield = FlashTheme.isEndfield(activity)
        val meow = FlashTheme.isMeow(activity)
        val p = FlashTheme.palette(activity)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = when {
                endfield -> EfCutDrawable.buildEndfieldPanel(
                    fillColor = p.panel,
                    ruleColor = p.rule,
                    tabColor = p.ink,
                    strokeWidthPx = (1f * density).coerceAtLeast(1f),
                    cutBottomRightPx = 26f * density,
                    tabWidthPx = (56f * density).toInt(),
                    tabHeightPx = (5f * density).toInt(),
                    signalColor = p.signal,
                    isDark = p.isDark,
                    density = density
                )
                meow -> MeowPanelDrawable(
                    fillColor = p.panel,
                    strokeColor = p.rule,
                    lipColor = p.rule,
                    pawColor = p.signal,
                    cornerRadiusPx = 24f * density,
                    strokeWidthPx = (1.5f * density).coerceAtLeast(1f),
                    density = density,
                    showCatEars = true
                )
                else -> GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 20f * density
                    setColor(p.panel)
                    setStroke((1f * density).toInt().coerceAtLeast(1), p.rule)
                }
            }
        }
    }

    private fun makeEndfieldEyebrow(activity: Activity, label: String, p: FlashTheme.Palette): TextView =
        TextView(activity).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(p.ink2)
            EfTypeface.applyEyebrow(this)
            setPadding(0, 0, 0, (4f * activity.resources.displayMetrics.density).toInt())
        }

    private fun makeSubtitleTag(activity: Activity, label: String, p: FlashTheme.Palette): TextView =
        TextView(activity).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(p.ink2)
            setPadding(0, 0, 0, (4f * activity.resources.displayMetrics.density).toInt())
        }

    private fun makeTitle(activity: Activity, title: String, p: FlashTheme.Palette): TextView =
        TextView(activity).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.ink)
        }
}
