package animelovers.rebornhd.ui.splash

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import animelovers.rebornhd.R
import animelovers.rebornhd.app
import animelovers.rebornhd.databinding.ActivitySplashBinding
import animelovers.rebornhd.ui.BaseActivity
import animelovers.rebornhd.ui.main.MainActivity
import animelovers.rebornhd.util.Constants
import animelovers.rebornhd.util.KillSwitch
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Splash — adaptasi Splash.java asli (tanpa checkloc, config GitHub,
 * dan SDK iklan — semuanya DIHAPUS di v1).
 *
 * Tampilan: logo splashh.png (350×400dp) + "AnimLovers" 40sp + kredit dev
 * "Dibuat ulang oleh Hadi as Faygo" di posisi teks versi aslinya.
 * Dialog Disclaimer muncul sekali saat pertama buka (pref eulaKey).
 * v2: TIDAK ADA lagi cek backend /health (server kecil sudah dihapus —
 * sumber konten kini API Sanka langsung).
 * v2.0.11: sebelum disclaimer/Main, cek saklar jarak jauh (maksimal
 * 5 detik; gagal/timeout = jalan terus — fail-open). Bila MATI,
 * langsung ke Main dengan error state server (disclaimer dilewati).
 */
class SplashActivity : BaseActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v2.0.5: ukur rantai render Beranda (D2) — T+0 = Splash tampil.
        // Baca di logcat: AnimLoversTiming (Splash → Main → home() kembali
        // → applyData render). Target: Beranda terisi ≤2 detik setelah Main.
        Log.d("AnimLoversTiming", "T+${SystemClock.elapsedRealtime()} Splash onCreate")
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // v2.0.11: cek saklar jarak jauh DULU (maksimal 5 detik) — bila
        // MATI, langsung ke Main dengan error state server; disclaimer
        // dilewati (tidak ada gunanya menerima syarat aplikasi yang
        // sedang tidak bisa dipakai). Gagal/timeout = jalan terus
        // (fail-open — install pertama offline diizinkan).
        lifecycleScope.launch {
            val decision = withTimeoutOrNull(KillSwitch.SPLASH_TIMEOUT_MS) {
                KillSwitch.check(app())
            }
            if (decision?.allowed == false) {
                MainActivity.startDead(this@SplashActivity)
                finish()
            } else if (prefs.eulaKey != "1") {
                showDisclaimer()
            } else {
                goToMain()
            }
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
            delay(Constants.SPLASH_DELAY_MS) // v2.0.5 (D2): 0 — tanpa jeda.
            Log.d("AnimLoversTiming", "T+${SystemClock.elapsedRealtime()} Splash -> Main")
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            finish()
        }
    }
}
