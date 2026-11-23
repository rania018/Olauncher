package app.calmlauncher.ui

import android.content.Context
import android.content.res.Resources
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.core.content.ContextCompat
import app.olauncher.R

/**
 * The one colour Calm Launcher uses on everything it draws: buttons, chips, the time-left pill and
 * bar, the time's-up pop-up and notifications (spec FR-030). The home screen keeps upstream's
 * black or white text, which reads on any wallpaper.
 *
 * [PHONE] follows the phone's own palette: on Android 12+ that is the system accent colour, which
 * One UI sets under Wallpaper and style → Color palette. No app can recolour other apps.
 */
enum class Accent(@StyleRes val overlay: Int, @ColorRes val color: Int, @StringRes val label: Int) {
    PHONE(R.style.CalmAccent_Phone, R.color.calm_accent_phone, R.string.calm_accent_phone),
    TEAL(R.style.CalmAccent_Teal, R.color.calm_accent_teal, R.string.calm_accent_teal),
    BLUE(R.style.CalmAccent_Blue, R.color.calm_accent_blue, R.string.calm_accent_blue),
    PURPLE(R.style.CalmAccent_Purple, R.color.calm_accent_purple, R.string.calm_accent_purple),
    PINK(R.style.CalmAccent_Pink, R.color.calm_accent_pink, R.string.calm_accent_pink),
    ORANGE(R.style.CalmAccent_Orange, R.color.calm_accent_orange, R.string.calm_accent_orange),
    GREEN(R.style.CalmAccent_Green, R.color.calm_accent_green, R.string.calm_accent_green),
    MONO(R.style.CalmAccent_Mono, R.color.calm_accent_mono, R.string.calm_accent_mono);

    companion object {
        val DEFAULT = PHONE

        /** Unknown or missing values fall back to the phone's colour. */
        fun parse(value: String?): Accent = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

/**
 * Where the accent choice is kept. It is a look preference that every screen needs *before* it
 * draws its first frame, so it lives in a tiny SharedPreferences file that can be read at once
 * (DataStore is read asynchronously). Like our other files it is excluded from backup.
 */
object CalmLook {

    const val FILE_NAME = "calm_look"
    private const val KEY_ACCENT = "accent"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun accent(context: Context): Accent = Accent.parse(prefs(context).getString(KEY_ACCENT, null))

    fun setAccent(context: Context, accent: Accent) {
        prefs(context).edit().putString(KEY_ACCENT, accent.name).apply()
    }

    /** Adds the chosen accent to [theme]; call before any view is inflated. */
    fun applyTo(theme: Resources.Theme, context: Context) {
        theme.applyStyle(accent(context).overlay, true)
    }

    /** The accent as a colour, for code that draws or tints by hand (notifications, overlays). */
    @ColorInt
    fun accentColor(context: Context): Int = ContextCompat.getColor(context, accent(context).color)
}
