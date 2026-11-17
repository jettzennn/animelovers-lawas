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
 * - Tema gelap (default): status bar #2b2a2a + ikon status bar terang.
 * - Tema terang: status bar putih + ikon status bar gelap (flag 8192).
 * - ActionBar bawaan (AppTheme = DarkActionBar) selalu disembunyikan di
 *   onCreate — aplikasi memakai Toolbar sendiri di layout (bukan ActionBar),
 *   jadi strip putih colorPrimary tidak pernah tampil.
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
        // AppTheme turunan Theme.AppCompat.Light.DarkActionBar menampilkan
        // ActionBar bawaan (latar colorPrimary putih, judul terang tak terbaca
        // di atas putih). Aplikasi ini memakai Toolbar sendiri di layout,
        // jadi ActionBar bawaan selalu disembunyikan — di sini supaya berlaku
        // untuk semua Activity (Main/Detail/Episode/Pengaturan/Splash).
        // VideoPlay aman: FullscreenTheme sudah windowActionBar=false
        // sehingga supportActionBar null dan pemanggilan ini no-op.
        supportActionBar?.hide()
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
            // Status bar gelap + ikon terang: flag LIGHT_STATUS_BAR harus
            // dibersihkan secara eksplisit (bitwise, flag lain dipertahankan).
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            window.statusBarColor = getColor(R.color.darktheme)
        } else {
            // Status bar terang + ikon gelap (flag 8192).
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            window.statusBarColor = getColor(R.color.putih)
        }
    }

    /** Style AlertDialog yang ikut tema (DarkAlert / LightAlert). */
    protected fun alertTheme(): Int =
        if (prefs.isDarkTheme) R.style.DarkAlert else R.style.LightAlert
}
