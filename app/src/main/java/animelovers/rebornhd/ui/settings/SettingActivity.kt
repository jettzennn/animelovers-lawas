package animelovers.rebornhd.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import animelovers.rebornhd.R
import animelovers.rebornhd.app
import animelovers.rebornhd.data.auth.AuthRepository
import animelovers.rebornhd.data.auth.LoginCancelledException
import animelovers.rebornhd.databinding.ActivitySettingBinding
import animelovers.rebornhd.ui.BaseActivity
import animelovers.rebornhd.ui.console.ConsoleActivity
import animelovers.rebornhd.util.Constants
import animelovers.rebornhd.util.DiagError
import animelovers.rebornhd.util.DiagLog
import animelovers.rebornhd.util.setTintedNavigationIcon
import animelovers.rebornhd.util.toast
import coil.load
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Pengaturan — adaptasi SettingActivity asli.
 * Seksi Manajer Unduhan (ADM) DIHAPUS; switch chat DIHAPUS.
 *
 * Pola simpan: switch notif/WiFi/Ecchi bersifat "pending" sampai tombol
 * Simpan ditekan; Batal membuang perubahan.
 *
 * v2.0.5 (item 5): switch tema gelap manual DIHAPUS — tema mengikuti
 * sistem HP (Prefs.isDarkTheme dibaca dari uiMode sistem). Pengaturan
 * hanya menampilkan baris statis "Tema mengikuti sistem HP".
 *
 * Login: tombol FB/Google memanggil AuthRepository (v2.0.12:
 * FirebaseAuthRepository — masuk sungguhan via Firebase Auth).
 * Hasil masuk diteruskan lewat onActivityResult; bila pengguna
 * membatalkan sendiri, aplikasi diam saja (tetap tamu, tanpa toast).
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
        binding.toolbarSetting.setTintedNavigationIcon(R.drawable.ic_arrow_back_black_24dp)
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

        binding.notifSwitch.isChecked = pendingNotif
        binding.wifiSwitch.isChecked = pendingWifi
        binding.ecchiSwitch.isChecked = pendingEcchi

        binding.notifSwitch.setOnCheckedChangeListener { _, isChecked ->
            pendingNotif = isChecked
        }
        binding.wifiSwitch.setOnCheckedChangeListener { _, isChecked ->
            pendingWifi = isChecked
        }
        binding.ecchiSwitch.setOnCheckedChangeListener { _, isChecked ->
            // v2.0.13 (fail-safe): akses auth yang melempar → anggap
            // tamu (switch kembali mati), BUKAN crash.
            val signedIn = authOrNull()?.isSignedIn() ?: false
            if (isChecked && !signedIn) {
                // Belum login: kembalikan posisi switch + beri tahu
                // (gerbang tetap aman — saveAndFinish juga membuangnya).
                binding.ecchiSwitch.isChecked = false
                pendingEcchi = false
                toast(getString(R.string.ecchi_perlu_login))
                return@setOnCheckedChangeListener
            }
            pendingEcchi = isChecked
        }

        binding.loginButton.setOnClickListener { signIn(facebook = true) }
        binding.loginButtonGoogle.setOnClickListener { signIn(facebook = false) }
        binding.signoutFb.setOnClickListener { signOut() }

        binding.cancelText.setOnClickListener { finish() }
        binding.consoleButton.setOnClickListener { ConsoleActivity.start(this) }
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

    // v2.0.12: hasil alur masuk (Google intent + Facebook CallbackManager)
    // diteruskan ke repository — tanpa ini, hasil masuk tidak pernah sampai.
    // v2.0.13 (fail-safe): konstruksi auth yang melempar → lewatkan ke
    // super (bukan urusan login), BUKAN crash.
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val handled = runCatching {
            app().authRepository.onActivityResult(requestCode, resultCode, data)
        }
            .onFailure { e ->
                app().diagLogger.warn(
                    DiagLog.TAG_APP,
                    "init auth gagal (${DiagError.describe(e)}) — dianggap tamu",
                )
            }
            .getOrDefault(false)
        if (handled) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    /**
     * Ambil repository login dengan fail-safe (v2.0.13 — crash startup
     * v2.0.12): konstruksi lazy `authRepository` bisa melempar (mis. SDK
     * Facebook belum ter-init) dan lazy yang gagal akan melempar LAGI di
     * tiap akses — jadi TIAP pemakaian di Activity ini lewat sini dan
     * menerima null sebagai "tamu". Kegagalan dicatat satu baris log
     * diagnostik; token TIDAK PERNAH ditulis ke log.
     */
    private fun authOrNull(): AuthRepository? =
        runCatching { app().authRepository }
            .onFailure { e ->
                app().diagLogger.warn(
                    DiagLog.TAG_APP,
                    "init auth gagal (${DiagError.describe(e)}) — dianggap tamu",
                )
            }
            .getOrNull()

    private fun signIn(facebook: Boolean) {
        lifecycleScope.launch {
            // v2.0.13 (fail-safe): konstruksi auth yang gagal menjadi
            // Result.failure → toast login_gagal (kontrak v2.0.12),
            // BUKAN crash. Pengguna tetap bisa memakai aplikasi
            // sebagai tamu dan mencoba masuk lagi dari Pengaturan.
            val repo = authOrNull()
            val result = if (repo == null) {
                Result.failure(IllegalStateException("auth_init_failed"))
            } else if (facebook) {
                repo.signInFacebook(this@SettingActivity)
            } else {
                repo.signInGoogle(this@SettingActivity)
            }
            result
                .onSuccess { refreshLoginUi() }
                .onFailure { error ->
                    if (error is LoginCancelledException) {
                        // Dibatalkan pengguna — tetap tamu, tanpa toast.
                    } else {
                        toast(getString(R.string.login_gagal))
                    }
                }
        }
    }

    private fun signOut() {
        lifecycleScope.launch {
            // v2.0.13 (fail-safe): konstruksi auth yang gagal TIDAK
            // BOLEH menggagalkan keluar — sesi lokal tetap dibersihkan
            // (clearAuth + clearEcchi, sama seperti signOut() repository)
            // dan UI kembali jadi tamu. CancellationException diteruskan.
            try {
                app().authRepository.signOut()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app().diagLogger.warn(
                    DiagLog.TAG_APP,
                    "init auth gagal (${DiagError.describe(e)}) — keluar lokal saja",
                )
                prefs.clearAuth()
                prefs.clearEcchi()
            }
            pendingEcchi = false
            binding.ecchiSwitch.isChecked = false
            refreshLoginUi()
            toast(getString(R.string.sudah_keluar))
        }
    }

    private fun refreshLoginUi() {
        val auth = authOrNull()
        val signedIn = if (auth != null) auth.isSignedIn() else false
        binding.linearFbLogin.visibility =
            if (signedIn) android.view.View.GONE else android.view.View.VISIBLE
        binding.liniearFbDetail.visibility =
            if (signedIn) android.view.View.VISIBLE else android.view.View.GONE
        if (signedIn && auth != null) {
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
        // v2.0.13 (fail-safe): hanya simpan ecchiEnabled bila sudah
        // login; akses auth yang melempar → anggap tamu (jangan simpan).
        if (authOrNull()?.isSignedIn() == true) {
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
