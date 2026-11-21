package animelovers.rebornhd.util

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration

/**
 * Pembungkus SharedPreferences "animlovers" — nama file + kunci dipertahankan
 * dari aslinya (lihat Splash.java / MainActivity.java hasil bedah).
 *
 * Nilai "1"/"0" dipakai untuk switch (pola asli), "true"/"false" untuk teruskan.
 * Pref "adm" TIDAK dipakai lagi (unduh = DownloadManager saja, PRD §12 poin 1).
 */
class Prefs(context: Context) {

    private val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences =
        context.getSharedPreferences("animlovers", Context.MODE_PRIVATE)

    /**
     * v2.0.5 (item 5): tema mengikuti SISTEM HP — bukan lagi pref manual.
     * Pref "theme" lama ("1" gelap / "0" terang) sudah DIHAPUS; nilai
     * tersimpannya di SharedPreferences diabaikan. HP dark → gelap, HP
     * terang → terang. Dibaca dari uiMode Configuration aplikasi (bukan
     * dari Activity — supaya konsisten di semua layar). BaseActivity
     * menerapkannya lewat setTheme() di onCreate; saat tema HP berubah,
     * sistem membuat ulang activity (tanpa configChanges="uiMode" di
     * manifest) sehingga tema baru langsung diterapkan.
     */
    val isDarkTheme: Boolean
        get() {
            val nightMask = appContext.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK
            return nightMask == Configuration.UI_MODE_NIGHT_YES
        }

    /** "1" = peringatkan bila streaming tanpa WiFi (default "1"). */
    var wifiSwitch: String
        get() = prefs.getString("wifi_switch", "1") ?: "1"
        set(value) = prefs.edit().putString("wifi_switch", value).apply()

    /** "1" = notifikasi episode baru (v1: penanda lokal saja, tanpa FCM). */
    var allNotif: String
        get() = prefs.getString("allnotif", "0") ?: "0"
        set(value) = prefs.edit().putString("allnotif", value).apply()

    /**
     * "1" = utamakan 720p, "0" = utamakan 480p (default direct sejak v2.0.6 —
     * instalasi baru membuka file langsung 720p dulu di kontrol aplikasi
     * sendiri; keputusan Faygo 2026-11-18 ~17:21 WIB (D4): "video anime
     * berjalan di kontrol player sendiri, kenapa harus pindah sih".
     * Dulu default "0" (embed dulu, seperti aslinya SD dulu) —
     * diubah v2.0.6 karena yang Faygo lihat di v2.0.5 adalah player
     * website Blogger, bukan kontrol aplikasi. v2.0.8 (D-web): tidak ada
     * lagi cadangan pemutar website — bila tidak ada video langsung sama
     * sekali, player gagal jujur (toast + keluar).
     * v2: 720p = sumber direct (Pixeldrain/token/Blogger itag 22);
     * 360p = Blogger itag 18 (direct).
     * Pref ini memilih kandidat AWAL saat episode dibuka; tombol
     * `resolution` di player mengalihkan kualitas DI PLAYER YANG SAMA
     * (direct 720p ↔ direct 360p).
     */
    var hdOnly: String
        get() = prefs.getString("hd_only", "1") ?: "1"
        set(value) = prefs.edit().putString("hd_only", value).apply()

    /** "true" = Putar Otomatis, "false" = Putar Sekali. */
    var teruskan: String
        get() = prefs.getString("teruskan", "true") ?: "true"
        set(value) = prefs.edit().putString("teruskan", value).apply()

    /** Orientasi player: "6" = sensor landscape, "7" = sensor portrait. */
    var orientation: String
        get() = prefs.getString("orientation", "6") ?: "6"
        set(value) = prefs.edit().putString("orientation", value).apply()

    /** Orientasi sebelum masuk player — dikembalikan saat keluar player.
     *  "" = belum disimpan (diisi saat masuk player pertama kali). */
    var awallayar: String
        get() = prefs.getString("awallayar", "") ?: ""
        set(value) = prefs.edit().putString("awallayar", value).apply()

    /** "1" = disclaimer sudah diterima. */
    var eulaKey: String
        get() = prefs.getString("eulaKey", "0") ?: "0"
        set(value) = prefs.edit().putString("eulaKey", value).apply()

    /** "1" = genre Ecchi diaktifkan (kunci asli: "hrdku87"). Dihapus saat logout. */
    var ecchiEnabled: String
        get() = prefs.getString("hrdku87", "0") ?: "0"
        set(value) = prefs.edit().putString("hrdku87", value).apply()

    fun clearEcchi() = prefs.edit().remove("hrdku87").apply()

    /** "list" / "grid" — tampilan Jelajahi (default list, seperti aslinya). */
    var browslist: String
        get() = prefs.getString("browslist", "list") ?: "list"
        set(value) = prefs.edit().putString("browslist", value).apply()

    /** Email akun yang login (gerbang Ecchi) — kosong bila belum login. */
    var authEmail: String
        get() = prefs.getString("auth_email", "") ?: ""
        set(value) = prefs.edit().putString("auth_email", value).apply()

    /**
     * v2.0.11: ID Instalasi Firebase perangkat ini (unik per install).
     * Dipakai untuk pengecualian saklar jarak jauh — ID Faygo masuk
     * daftar kill_exempt_ids di konsol. Nilai penuh TIDAK PERNAH
     * ditulis ke log (hanya 8 karakter pertama).
     */
    var installationId: String
        get() = prefs.getString("installation_id", "") ?: ""
        set(value) = prefs.edit().putString("installation_id", value).apply()

    /** v2.0.11: waktu (epoch ms) cek saklar terakhir — dasar aturan 15 menit. */
    var killLastCheckMs: Long
        get() = prefs.getLong("kill_last_check_ms", 0L)
        set(value) = prefs.edit().putLong("kill_last_check_ms", value).apply()

    /** URL foto profil akun yang login — kosong bila tidak ada. */
    var authPhotoUrl: String
        get() = prefs.getString("auth_photo", "") ?: ""
        set(value) = prefs.edit().putString("auth_photo", value).apply()

    /** Penyedia login terakhir ("facebook" / "google") — kosong bila belum login. */
    var authProvider: String
        get() = prefs.getString("auth_provider", "") ?: ""
        set(value) = prefs.edit().putString("auth_provider", value).apply()

    fun clearAuth() {
        prefs.edit()
            .remove("auth_email")
            .remove("auth_photo")
            .remove("auth_provider")
            .apply()
    }
}
