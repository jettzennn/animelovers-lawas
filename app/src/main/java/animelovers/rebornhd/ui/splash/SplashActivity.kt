package animelovers.rebornhd.ui.splash

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import animelovers.rebornhd.R
import animelovers.rebornhd.databinding.ActivitySplashBinding
import animelovers.rebornhd.ui.BaseActivity
import animelovers.rebornhd.ui.main.MainActivity
import animelovers.rebornhd.util.Constants
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Splash — adaptasi Splash.java asli (tanpa Remote Config, checkloc,
 * config GitHub, kill-switch, dan SDK iklan — semuanya DIHAPUS di v1).
 *
 * Tampilan: logo splashh.png (350×400dp) + "AnimLovers" 40sp + kredit dev
 * "Dibuat ulang oleh Hadi as Faygo" di posisi teks versi aslinya.
 * Dialog Disclaimer muncul sekali saat pertama buka (pref eulaKey).
 * v2: TIDAK ADA lagi cek backend /health (server kecil sudah dihapus —
 * sumber konten kini API Sanka langsung).
 */
class SplashActivity : BaseActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // v2: tidak ada cek backend (server kecil dihapus) — langsung disclaimer/Main.
        if (prefs.eulaKey != "1") {
            showDisclaimer()
        } else {
            goToMain()
        }
    }

    private fun showDisclaimer() {
        AlertDialog.Builder(this, alertTheme())
            .setTitle(R.string.disclaimer_judul)
            .setMessage(R.string.disclaimer_isi)
            .setCancelable(false)
            .setPositiveButton(R.string.terima) { _, _ ->
                prefs.eulaKey = "1"
                goToMain()
            }
            .setNegativeButton(R.string.tolak) { _, _ ->
                finish()
            }
            .show()
    }

    private fun goToMain() {
        lifecycleScope.launch {
            delay(Constants.SPLASH_DELAY_MS)
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            finish()
        }
    }
}
