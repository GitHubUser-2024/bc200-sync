package arpa.home.bc200sync

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.widget.Button

/**
 * Material 3 style colours. On Android 12+ they come from the phone's own (Material You) palette,
 * so the app matches the system; older versions fall back to the theme accent.
 */
class Palette(ctx: Context) {
    val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    val primary: Int
    val onPrimary: Int
    val tonal: Int
    val onTonal: Int
    val surface: Int
    val onSurface: Int
    val onSurfaceVariant: Int
    val card: Int
    val drawer: Int

    /** Frame colours for the connection state: green connected, yellow not (yet) connected. */
    val online: Int get() = if (night) Color.rgb(0x81, 0xC7, 0x84) else Color.rgb(0x2E, 0x7D, 0x32)
    val offline: Int get() = if (night) Color.rgb(0xFF, 0xD5, 0x4F) else Color.rgb(0xF9, 0xA8, 0x25)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            fun c(id: Int) = ctx.getColor(id)
            if (night) {
                primary = c(android.R.color.system_accent1_200); onPrimary = c(android.R.color.system_accent1_800)
                tonal = c(android.R.color.system_accent2_700); onTonal = c(android.R.color.system_accent2_100)
                surface = c(android.R.color.system_neutral1_900); onSurface = c(android.R.color.system_neutral1_100)
                onSurfaceVariant = c(android.R.color.system_neutral2_200)
                card = c(android.R.color.system_neutral2_800); drawer = c(android.R.color.system_neutral1_800)
            } else {
                primary = c(android.R.color.system_accent1_600); onPrimary = c(android.R.color.system_accent1_0)
                tonal = c(android.R.color.system_accent2_100); onTonal = c(android.R.color.system_accent2_900)
                surface = c(android.R.color.system_neutral1_10); onSurface = c(android.R.color.system_neutral1_900)
                onSurfaceVariant = c(android.R.color.system_neutral2_700)
                card = c(android.R.color.system_neutral2_50); drawer = c(android.R.color.system_neutral1_50)
            }
        } else {
            fun attr(a: Int, def: Int): Int {
                val t = ctx.theme.obtainStyledAttributes(intArrayOf(a))
                return try { t.getColor(0, def) } finally { t.recycle() }
            }
            primary = attr(android.R.attr.colorAccent, Color.rgb(0x3F, 0x51, 0xB5))
            onPrimary = Color.WHITE
            tonal = withAlpha(primary, 0x33)
            onTonal = primary
            surface = attr(android.R.attr.colorBackground, if (night) Color.BLACK else Color.WHITE)
            onSurface = attr(android.R.attr.textColorPrimary, if (night) Color.WHITE else Color.BLACK)
            onSurfaceVariant = attr(android.R.attr.textColorSecondary, Color.GRAY)
            card = withAlpha(onSurface, 0x10)
            drawer = surface
        }
    }

    companion object {
        fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)
    }
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
    cornerRadius = radius
    setColor(color)
}

/** Ripple on a rounded background; [bg] may be transparent for list-style rows. */
fun ripple(bg: Int, ink: Int, radius: Float) = RippleDrawable(
    ColorStateList.valueOf(Palette.withAlpha(ink, 0x33)),
    rounded(bg, radius),
    rounded(Color.WHITE, radius),
)

/** Large pill-shaped Material 3 button (filled or tonal, depending on the colours given). */
fun Context.pillButton(label: String, bg: Int, fg: Int, onClick: () -> Unit) = Button(this).apply {
    text = label
    isAllCaps = false
    textSize = 17f
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    setTextColor(fg)
    stateListAnimator = null
    minHeight = 0
    minimumHeight = 0
    background = ripple(bg, fg, dp(28).toFloat())
    setOnClickListener { onClick() }
}
