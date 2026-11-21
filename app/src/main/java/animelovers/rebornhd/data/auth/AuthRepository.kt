package animelovers.rebornhd.data.auth

import android.app.Activity
import android.content.Intent

/**
 * Seam login — HANYA sebagai gerbang genre Ecchi (PRD §12 poin 5, koreksi Faygo).
 * Aslinya: login Facebook/Google via Firebase, lalu toggle "Aktifkan Ecchi"
 * di halaman profil. Revival memakai pola yang sama, tanpa sistem akun sendiri.
 *
 * Implementasi aktif: [FirebaseAuthRepository] (v2.0.12 — masuk Google dan
 * Facebook sungguhan via Firebase Auth). [StubAuthRepository] dipertahankan
 * sebagai berkas (tidak terikat) untuk referensi.
 *
 * SEAM ACTIVITY (v2.0.12 item 3 — keputusan Frontend): alur masuk sungguhan
 * TIDAK BISA jalan tanpa Activity (Google butuh Activity untuk intent
 * sign-in; Facebook butuh Activity untuk LoginManager + CallbackManager).
 * Bentuk seam yang dipilih — yang paling kecil: kedua fungsi sign-in
 * menerima parameter `Activity`, dan SATU forwarder
 * [onActivityResult] meneruskan hasil ke kedua provider (CallbackManager
 * Facebook + intent Google). Alternatif yang ditolak: ActivityResultLauncher
 * milik Activity — itu membelah jalur hasil jadi dua (launcher untuk
 * Google + onActivityResult untuk Facebook) dan memaksa Activity memiliki
 * launcher; startActivityForResult + satu forwarder menjaga satu jalur
 * hasil untuk kedua provider (pemakaian startActivityForResult yang
 * deprecated tetap berfungsi penuh di minSdk 21–targetSdk 34).
 *
 * JANGAN mengarang kredensial Firebase (apiKey, appId, dsb.) — tanpa config
 * asli, gerbang tetap terkunci dengan aman (pesan "Login dahulu…").
 */
interface AuthRepository {

    /** True bila sudah login (FB/Google) — gerbang Ecchi butuh ini. */
    fun isSignedIn(): Boolean

    /** Email akun yang login, "" bila belum login. */
    fun userEmail(): String

    /** URL foto profil, "" bila tidak ada. */
    fun userPhotoUrl(): String

    /** "facebook" / "google" / "" . */
    fun provider(): String

    /**
     * Coba masuk lewat Facebook. Hasil:
     * - Success bila login berhasil (email + foto terisi).
     * - Failure([LoginCancelledException]) bila pengguna membatalkan
     *   sendiri — UI diam (tetap tamu, tanpa toast).
     * - Failure lain (offline, konfigurasi, token tidak valid) —
     *   UI menampilkan pesan jujur, BUKAN crash dan BUKAN jalan buntu.
     */
    suspend fun signInFacebook(activity: Activity): Result<Unit>

    /** Sama seperti [signInFacebook], lewat Google. */
    suspend fun signInGoogle(activity: Activity): Result<Unit>

    /**
     * Teruskan `onActivityResult` dari Activity pemanggil ke repository.
     * Mengembalikan true bila hasil itu dipakai (CallbackManager
     * Facebook atau intent Google); false bila bukan urusan login.
     * Activity pemanggil WAJIB memanggil ini dari `onActivityResult`
     * miliknya — tanpa itu, hasil masuk tidak pernah sampai.
     */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean

    /**
     * Keluar: Firebase sign-out + hapus pref Ecchi (hrdku87) — seperti aslinya,
     * pengguna harus mengaktifkan ulang setelah login kembali.
     */
    suspend fun signOut()
}

/**
 * Penanda "pengguna membatalkan masuk sendiri" — dibedakan dari
 * kegagalan sungguhan supaya UI diam saja (tetap tamu, tanpa toast
 * error yang menakut-nakuti — PRD §B8).
 */
class LoginCancelledException : Exception("login dibatalkan pengguna")
