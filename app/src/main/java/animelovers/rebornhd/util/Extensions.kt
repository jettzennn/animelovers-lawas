package animelovers.rebornhd.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.TypedValue
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import animelovers.rebornhd.R
import animelovers.rebornhd.data.api.sankaErrorReason
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Context.toast(message: String) =
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

fun Context.toastLong(message: String) =
    Toast.makeText(this, message, Toast.LENGTH_LONG).show()

/**
 * Toast gagal khas Sanka — alasannya ikut tertulis supaya tidak menebak
 * lagi ("Gagal memuat (server menjawab 403)..."). Bila alasannya tidak
 * diketahui, pakai toast generik seperti dulu. Rate limit TIDAK lewat
 * sini (sudah ditangani terpisah dengan pesan khusus di tiap layar).
 * Pesan panjang → toastLong supaya sempat dibaca.
 *
 * F-4 Cari Bug v2.0.10: catch-all `sankaErrorReason` (file BEKU — jangan
 * disentuh) bisa mengembalikan "y: tanpa pesan" (nama kelas 1 huruf
 * pasca-R8 + pesan kosong) dan pesan mentah yang bisa berisi URL/token.
 * Mitigasi di titik tampil (di sini): alasan disanitasi (host-only,
 * tanpa URL/token utuh — pola yang sama dengan `DiagError.describe`),
 * dan bila hasilnya tetap tak terbaca → toast generik.
 */
fun Context.toastSankaError(e: Exception) {
    val reason = sankaErrorReason(e)
    if (reason != null) {
        val safe = DiagSanitize.sanitize(reason)
        if (UNREADABLE_REASON.matches(safe)) {
            toast(getString(R.string.gagal_memuat))
        } else {
            toastLong(getString(R.string.gagal_memuat_alasan, safe))
        }
    } else {
        toast(getString(R.string.gagal_memuat))
    }
}

/**
 * Pola alasan yang tetap tak terbaca setelah sanitasi: nama kelas hasil
 * rename R8 (1–2 huruf) + "tanpa pesan" (catch-all tanpa pesan).
 * Alasan bertipe ("server menjawab 403", "Sanka: … — tanpa pesan", …)
 * tidak cocok dengan pola ini — hanya catch-all yang tak terbaca yang
 * jatuh ke toast generik.
 */
private val UNREADABLE_REASON = Regex("""^[A-Za-z$]{1,2}: tanpa pesan$""")

/** True bila perangkat terhubung ke WiFi (untuk gerbang WiFi player). */
fun Context.isOnWifi(): Boolean {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}

/** True bila ada koneksi internet apa pun (WiFi atau seluler). */
fun Context.isOnline(): Boolean {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/** Format waktu riwayat: "12 Nov 2026 09.05" (locale Indonesia). */
fun formatWaktu(epochMs: Long): String {
    val fmt = SimpleDateFormat("d MMM yyyy HH.mm", Locale("id", "ID"))
    return fmt.format(Date(epochMs))
}

/** "Episode 12" / "Film" untuk label episode. */
fun episodeLabel(number: Int, isMovie: Boolean, context: Context): String =
    if (isMovie) context.getString(R.string.film)
    else context.getString(R.string.episode_label, number)

/**
 * Ikon navigasi toolbar yang di-tint ?attr/colorControlNormal (v2.0.7 #2).
 * Drawable panah kembali (ic_arrow_back_black_24dp) berisi hitam keras
 * tanpa tint — di tema gelap tidak kelihatan di atas latar gelap. Fungsi
 * ini memasang ikon dengan tint dari tema aktif (putih di DarkTheme,
 * hitam di LightTheme) — sama seperti panah up standar AppCompat, dan
 * persis cara aslinya (LainnyaActivity asli men-tint ikon navigasi
 * dengan black_overlay). Dipakai EpisodeActivity, LainnyaActivity,
 * SettingActivity — ketiganya menampilkan panah kembali toolbar.
 */
fun Toolbar.setTintedNavigationIcon(@DrawableRes iconRes: Int) {
    val drawable = AppCompatResources.getDrawable(context, iconRes)?.mutate() ?: return
    val typedValue = TypedValue()
    context.theme.resolveAttribute(
        androidx.appcompat.R.attr.colorControlNormal, typedValue, true
    )
    val color = if (typedValue.resourceId != 0) {
        ContextCompat.getColor(context, typedValue.resourceId)
    } else {
        typedValue.data
    }
    DrawableCompat.setTint(drawable, color)
    navigationIcon = drawable
}
