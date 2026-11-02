package dev.learnime.android

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

/** Shared MD3 color roles and sizing for the activity and the separately themed IME. */
internal class MaterialUi(val context: Context) {
    val surface = color(com.google.android.material.R.attr.colorSurface)
    val container = color(com.google.android.material.R.attr.colorSurfaceContainer)
    val low = color(com.google.android.material.R.attr.colorSurfaceContainerLow)
    val lowest = color(com.google.android.material.R.attr.colorSurfaceContainerLowest)
    val high = color(com.google.android.material.R.attr.colorSurfaceContainerHigh)
    val onSurface = color(com.google.android.material.R.attr.colorOnSurface)
    val muted = color(com.google.android.material.R.attr.colorOnSurfaceVariant)
    val primary = color(com.google.android.material.R.attr.colorPrimary)
    val onPrimary = color(com.google.android.material.R.attr.colorOnPrimary)
    val primaryContainer = color(com.google.android.material.R.attr.colorPrimaryContainer)
    val onPrimaryContainer = color(com.google.android.material.R.attr.colorOnPrimaryContainer)
    val secondaryContainer = color(com.google.android.material.R.attr.colorSecondaryContainer)
    val onSecondaryContainer = color(com.google.android.material.R.attr.colorOnSecondaryContainer)
    val outline = color(com.google.android.material.R.attr.colorOutlineVariant)
    val error = color(com.google.android.material.R.attr.colorError)

    fun dp(value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()
    private fun color(attr: Int) = MaterialColors.getColor(context, attr, javaClass.simpleName)

    fun text(value: String, size: Float, ink: Int = onSurface, medium: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(ink)
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        includeFontPadding = false
    }

    fun rounded(fill: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
    }

    fun card(fill: Int = low, radius: Int = 24) = MaterialCardView(context).apply {
        setCardBackgroundColor(fill)
        this.radius = dp(radius).toFloat()
        cardElevation = 0f
        strokeWidth = 0
    }

    fun button(
        label: String,
        fill: Int = lowest,
        ink: Int = onSurface,
        radius: Int = 12,
        size: Float = 16f,
        action: () -> Unit,
    ) = MaterialButton(context).apply {
        text = label
        textSize = size
        isAllCaps = false
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        insetTop = 0
        insetBottom = 0
        setPadding(dp(4), 0, dp(4), 0)
        cornerRadius = dp(radius)
        backgroundTintList = ColorStateList.valueOf(fill)
        setTextColor(ink)
        rippleColor = ColorStateList.valueOf(MaterialColors.layer(fill, ink, 0.12f))
        strokeWidth = 0
        elevation = 0f
        stateListAnimator = null
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setOnClickListener { action() }
    }

    fun iconButton(drawable: Int, description: String, fill: Int = Color.TRANSPARENT, ink: Int = muted, action: () -> Unit) =
        button("", fill, ink, 20, action = action).apply {
            setIconResource(drawable)
            iconTint = ColorStateList.valueOf(ink)
            iconSize = dp(22)
            iconPadding = 0
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            contentDescription = description
        }
}
