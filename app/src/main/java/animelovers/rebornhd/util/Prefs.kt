package animelovers.rebornhd.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Pembungkus SharedPreferences "animlovers" — nama file + kunci dipertahankan
 * dari aslinya (lihat Splash.java / MainActivity.java hasil bedah).
 *
 * Nilai "1"/"0" dipakai untuk switch (pola asli), "true"/"false" untuk teruskan.
 * Pref "adm" TIDAK dipakai lagi (unduh = DownloadManager saja, PRD §12 poin 1).
 */
class Prefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("animlovers", Context.MODE_PRIVATE)

    /** "1" = tema gelap (default, seperti aslinya), "0" = tema terang. */
    var theme: String
        get() = prefs.getString("theme", "1") ?: "1"
        set(value) = prefs.edit().putString("theme", value).apply()

    val isDarkTheme: Boolean get() = theme == "1"

    /** "1" = peringatkan bila streaming tanpa WiFi (default "1"). */
    var wifiSwitch: String
        get() = prefs.getString("wifi_switch", "1") ?: "1"
        set(value) = prefs.edit().putString("wifi_switch", value).apply()

    /** "1" = notifikasi episode baru (v1: penanda lokal saja, tanpa FCM). */
    var allNotif: String
        get() = prefs.getString("allnotif", "0") ?: "0"
        set(value) = prefs.edit().putString("allnotif", value).apply()

    /** "1" = kualitas terakhir HD, "0" = SD (default SD, seperti aslinya). */
    var hdOnly: String
        get() = prefs.getString("hd_only", "0") ?: "0"
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
