package animelovers.rebornhd.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import animelovers.rebornhd.R
import animelovers.rebornhd.app
import animelovers.rebornhd.databinding.ActivitySettingBinding
import animelovers.rebornhd.ui.BaseActivity
import animelovers.rebornhd.util.Constants
import animelovers.rebornhd.util.toast
import coil.load
import kotlinx.coroutines.launch

/**
 * Pengaturan — adaptasi SettingActivity asli.
 * Seksi Manajer Unduhan (ADM) DIHAPUS; switch chat DIHAPUS.
 *
 * Pola simpan: switch notif/WiFi/Ecchi bersifat "pending" sampai tombol
 * Simpan ditekan; Batal membuang perubahan. PENGECUALIAN: tema gelap
 * langsung diterapkan (recreate, tanpa restart aplikasi) saat switch
 * digeser — sesuai desain.md §8.
 *
 * Login: tombol FB/Google memanggil AuthRepository. Selama Firebase belum
 * dipasang (StubAuthRepository), ketukan menampilkan pesan jujur
 * "Masuk belum tersedia…" — gerbang Ecchi tetap terkunci, tanpa crash.
 * "Beri Rating" → repo GitHub (PRD §12 poin 9).
 */
class SettingActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingBinding

    // Nilai pending (baru diterapkan saat Simpan).
    private var pendingNotif: Boolean = false
    private var pendingWifi: Boolean = false
    private var pendingEcchi: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarSetting.title = getString(R.string.pengaturan)
        binding.toolbarSetting.setNavigationIcon(R.drawable.ic_arrow_back_black_24dp)
        binding.toolbarSetting.setNavigationOnClickListener { finish() }

        if (savedInstanceState != null) {
            pendingNotif = savedInstanceState.getBoolean(KEY_NOTIF)
            pendingWifi = savedInstanceState.getBoolean(KEY_WIFI)
            pendingEcchi = savedInstanceState.getBoolean(KEY_ECCHI)
        } else {
            pendingNotif = prefs.allNotif == "1"
            pendingWifi = prefs.wifiSwitch == "1"
            pendingEcchi = prefs.ecchiEnabled == "1"
        }

        binding.themeSwitch.isChecked = prefs.isDarkTheme
        binding.notifSwitch.isChecked = pendingNotif
        binding.wifiSwitch.isChecked = pendingWifi
        binding.ecchiSwitch.isChecked = pendingEcchi

        // Tema: langsung diterapkan (recreate) saat digeser.
        // Pref menyimpan "1" (gelap) / "0" (terang) — pola asli.
        binding.themeSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (prefs.isDarkTheme != isChecked) {
                prefs.theme = if (isChecked) "1" else "0"
                recreate()
            }
        }
        binding.notifSwitch.setOnCheckedChangeListener { _, isChecked ->
            pendingNotif = isChecked
        }
        binding.wifiSwitch.setOnCheckedChangeListener { _, isChecked ->
            pendingWifi = isChecked
        }
        binding.ecchiSwitch.setOnCheckedChangeListener { _, isChecked ->
            pendingEcchi = isChecked
        }

        binding.loginButton.setOnClickListener { signIn(facebook = true) }
        binding.loginButtonGoogle.setOnClickListener { signIn(facebook = false) }
        binding.signoutFb.setOnClickListener { signOut() }

        binding.cancelText.setOnClickListener { finish() }
        binding.rateus.setOnClickListener {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(Constants.GITHUB_REPO_URL)
            )
            startActivity(intent)
        }
        binding.saveText.setOnClickListener { saveAndFinish() }

        refreshLoginUi()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_NOTIF, pendingNotif)
        outState.putBoolean(KEY_WIFI, pendingWifi)
        outState.putBoolean(KEY_ECCHI, pendingEcchi)
    }

    private fun signIn(facebook: Boolean) {
        lifecycleScope.launch {
            val result = if (facebook) {
                app().authRepository.signInFacebook()
            } else {
                app().authRepository.signInGoogle()
            }
            result
                .onSuccess { refreshLoginUi() }
                .onFailure { error ->
                    if (error.message == "not_configured") {
                        toast(getString(R.string.login_belum_tersedia))
                    } else {
                        toast(getString(R.string.login_gagal))
                    }
                }
        }
    }

    private fun signOut() {
        lifecycleScope.launch {
            app().authRepository.signOut()
            pendingEcchi = false
            binding.ecchiSwitch.isChecked = false
            refreshLoginUi()
            toast(getString(R.string.sudah_keluar))
        }
    }

    private fun refreshLoginUi() {
        val auth = app().authRepository
        val signedIn = auth.isSignedIn()
        binding.linearFbLogin.visibility =
            if (signedIn) android.view.View.GONE else android.view.View.VISIBLE
        binding.liniearFbDetail.visibility =
            if (signedIn) android.view.View.VISIBLE else android.view.View.GONE
        if (signedIn) {
            binding.emailUser.text = auth.userEmail().ifBlank {
                getString(R.string.email_placeholder)
            }
            binding.photofb.load(auth.userPhotoUrl()) {
                placeholder(R.drawable.ic_person)
                error(R.drawable.ic_person)
                crossfade(true)
            }
            // Toggle Ecchi hanya bermakna bila login — nilainya dari pref.
            pendingEcchi = prefs.ecchiEnabled == "1"
            binding.ecchiSwitch.isChecked = pendingEcchi
        }
    }

    private fun saveAndFinish() {
        prefs.allNotif = if (pendingNotif) "1" else "0"
        prefs.wifiSwitch = if (pendingWifi) "1" else "0"
        if (app().authRepository.isSignedIn()) {
            prefs.ecchiEnabled = if (pendingEcchi) "1" else "0"
        }
        finish()
    }

    companion object {
        private const val KEY_NOTIF = "pending_notif"
        private const val KEY_WIFI = "pending_wifi"
        private const val KEY_ECCHI = "pending_ecchi"
    }
}
