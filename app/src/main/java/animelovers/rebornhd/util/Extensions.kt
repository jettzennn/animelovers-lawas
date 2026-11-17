package animelovers.rebornhd.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
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
 */
fun Context.toastSankaError(e: Exception) {
    val reason = sankaErrorReason(e)
    if (reason != null) {
        toastLong(getString(R.string.gagal_memuat_alasan, reason))
    } else {
        toast(getString(R.string.gagal_memuat))
    }
}

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
