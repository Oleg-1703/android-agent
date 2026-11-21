package com.oleg.androidagent

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Dependency-free view factory for the setup UI. All sizes are dp/sp so font scaling works. */
class Ui(private val ctx: Context) {
    enum class Tone(val bg: Int, val fg: Int) {
        OK(R.color.ok_bg, R.color.ok_fg), WARN(R.color.warn_bg, R.color.warn_fg),
        ERR(R.color.err_bg, R.color.err_fg), IDLE(R.color.idle_bg, R.color.idle_fg)
    }
    enum class Style(val bg: Int, val fg: Int) {
        PRIMARY(R.drawable.bg_btn_primary, R.color.white), SECONDARY(R.drawable.bg_btn_secondary, R.color.accent),
        DANGER(R.drawable.bg_btn_danger, R.color.err_fg)
    }
    class Field(val box: LinearLayout, val edit: EditText, val note: TextView)

    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    fun color(id: Int) = ctx.getColor(id)

    fun text(s: CharSequence, sp: Float, colorRes: Int = R.color.navy, bold: Boolean = false) = TextView(ctx).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color(colorRes))
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.1f)
    }
    fun title(s: CharSequence) = text(s, 26f, bold = true)
    fun heading(s: CharSequence) = text(s, 18f, bold = true).apply { isAccessibilityHeading = true }
    fun body(s: CharSequence) = text(s, 15f, R.color.navy)
    fun small(s: CharSequence) = text(s, 13f, R.color.muted)

    fun column() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

    fun card(): LinearLayout = column().apply {
        setBackgroundResource(R.drawable.bg_card)
        setPadding(dp(18), dp(18), dp(18), dp(18))
    }

    /** Rounded tinted panel; colours are applied in code so one drawable serves every tone. */
    fun tinted(tone: Tone): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat(); setColor(color(tone.bg)); setStroke(dp(1), color(tone.fg))
    }

    fun LinearLayout.add(v: View, top: Int = 0): View {
        addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) })
        return v
    }
    fun LinearLayout.addWeighted(v: View, endGap: Int = 0) {
        addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(endGap) })
    }

    fun button(label: CharSequence, style: Style = Style.PRIMARY, onClick: () -> Unit) = Button(ctx).apply {
        text = label; isAllCaps = false; stateListAnimator = null
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Typeface.DEFAULT_BOLD
        setTextColor(color(style.fg)); setBackgroundResource(style.bg)
        minHeight = dp(48); minimumHeight = dp(48)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setOnClickListener { onClick() }
    }

    /** Label + input + helper line. Secrets are masked and never restored from saved instance state. */
    fun field(label: CharSequence, secret: Boolean = false, numeric: Boolean = false, hint: CharSequence = ""): Field {
        val box = column()
        val edit = EditText(ctx).apply {
            id = View.generateViewId()
            isSingleLine = true; isSaveEnabled = false; this.hint = hint
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(color(R.color.navy)); setHintTextColor(color(R.color.muted))
            setBackgroundResource(R.drawable.bg_input)
            minHeight = dp(48); setPadding(dp(14), dp(10), dp(14), dp(10))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            inputType = when {
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                numeric -> InputType.TYPE_CLASS_NUMBER
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
        }
        val caption = text(label, 14f, R.color.navy, bold = true).apply { labelFor = edit.id }
        val note = small("").apply { visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        box.add(caption); box.add(edit, 6); box.add(note, 4)
        return Field(box, edit, note)
    }

    fun Field.setNote(msg: CharSequence, error: Boolean) {
        note.text = msg; note.setTextColor(color(if (error) R.color.err_fg else R.color.muted))
        note.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
    }
}
