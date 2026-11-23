package app.calmlauncher.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.view.ContextThemeWrapper
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import app.calmlauncher.CalmGraph
import app.olauncher.R
import app.olauncher.data.Prefs

/**
 * Base for Calm Launcher's own screens. It applies the same look as upstream's MainActivity:
 * the chosen light/dark theme, the text size and the bold-font option (Constitution IV), plus
 * the user's accent colour (spec FR-030).
 */
abstract class CalmActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        config.fontScale = Prefs(newBase).textSizeScale
        applyOverrideConfiguration(config)
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CalmGraph.init(this)
        AppCompatDelegate.setDefaultNightMode(Prefs(this).appTheme)
        super.onCreate(savedInstanceState)
        if (Prefs(this).boldFont) theme.applyStyle(R.style.BoldFontOverlay, true)
        CalmLook.applyTo(theme, this)
    }

    companion object {
        /**
         * A Context with upstream's theme, light/dark choice and text size, for views that are not
         * inside an Activity (the time's-up overlay).
         */
        fun themedContext(context: Context): Context {
            val prefs = Prefs(context)
            val config = Configuration(context.resources.configuration)
            val night = when (prefs.appTheme) {
                AppCompatDelegate.MODE_NIGHT_YES -> Configuration.UI_MODE_NIGHT_YES
                AppCompatDelegate.MODE_NIGHT_NO -> Configuration.UI_MODE_NIGHT_NO
                else -> Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            }
            config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            config.fontScale = prefs.textSizeScale
            return ContextThemeWrapper(context.createConfigurationContext(config), R.style.AppTheme).apply {
                if (prefs.boldFont) theme.applyStyle(R.style.BoldFontOverlay, true)
                CalmLook.applyTo(theme, context)
            }
        }
    }
}
