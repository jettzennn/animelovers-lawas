package animelovers.rebornhd.ui

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import animelovers.rebornhd.R
import animelovers.rebornhd.app
import animelovers.rebornhd.util.Prefs

/**
 * Activity dasar — menerapkan tema gelap/terang dari pref `theme` SEBELUM
 * super.onCreate (pola aslinya: tiap activity memanggil setTheme(Dark/Light)
 * di onCreate; lihat MainActivity.java / DetailActivity.java hasil bedah).
 *
 * - Tema gelap (default): status bar #2b2a2a.
 * - Tema terang: status bar putih + ikon status bar gelap (flag 8192).
 * - [transparentStatusBar] (Detail): poster menembus status bar
 *   (SYSTEM_UI_FLAG_LAYOUT_STABLE | LAYOUT_FULLSCREEN = 1280 + warna 0).
 * - [applyAppTheme] = false untuk VideoPlay (memakai FullscreenTheme manifest).
 */
abstract class BaseActivity : AppCompatActivity() {

    protected lateinit var prefs: Prefs

    protected open val applyAppTheme: Boolean = true
    protected open val transparentStatusBar: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = app().prefs
        if (applyAppTheme) {
            setTheme(if (prefs.isDarkTheme) R.style.DarkTheme else R.style.LightTheme)
        }
        super.onCreate(savedInstanceState)
        applyStatusBar()
    }

    private fun applyStatusBar() {
        if (transparentStatusBar) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            window.statusBarColor = Color.TRANSPARENT
            return
        }
        if (prefs.isDarkTheme) {
            window.statusBarColor = getColor(R.color.darktheme)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            window.statusBarColor = getColor(R.color.putih)
        }
    }

    /** Style AlertDialog yang ikut tema (DarkAlert / LightAlert). */
    protected fun alertTheme(): Int =
        if (prefs.isDarkTheme) R.style.DarkAlert else R.style.LightAlert
}
