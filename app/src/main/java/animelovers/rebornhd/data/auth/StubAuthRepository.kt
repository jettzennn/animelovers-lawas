package animelovers.rebornhd.data.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import animelovers.rebornhd.util.Prefs

/**
 * Implementasi sementara AuthRepository — TANPA Firebase.
 *
 * TIDAK LAGI TERIKAT (v2.0.12): [animelovers.rebornhd.AnimeloversApp]
 * memakai [FirebaseAuthRepository]. Berkas ini dipertahankan sebagai
 * referensi/pengaman — semua panggilan sign-in gagal dengan
 * "not_configured" sehingga gerbang Ecchi tetap TERKUNCI (aman —
 * tidak ada jalan pintas) bila suatu saat dipakai lagi.
 *
 * Pesan yang tampil bila dipakai: "Login dahulu untuk akses genre
 * Ecchi." (Detail) dan "Masuk belum tersedia — proyek Firebase baru
 * belum dipasang." (tombol Masuk di Pengaturan).
 * Tanpa crash, tanpa jalan buntu: pengguna tetap bisa nonton semua
 * genre non-Ecchi seperti biasa.
 */
class StubAuthRepository(context: Context) : AuthRepository {

    private val prefs = Prefs(context.applicationContext)

    override fun isSignedIn(): Boolean = prefs.authEmail.isNotBlank()

    override fun userEmail(): String = prefs.authEmail

    override fun userPhotoUrl(): String = prefs.authPhotoUrl

    override fun provider(): String = prefs.authProvider

    override suspend fun signInFacebook(activity: Activity): Result<Unit> =
        Result.failure(IllegalStateException("not_configured"))

    override suspend fun signInGoogle(activity: Activity): Result<Unit> =
        Result.failure(IllegalStateException("not_configured"))

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ): Boolean = false

    override suspend fun signOut() {
        prefs.clearAuth()
        prefs.clearEcchi()
    }
}
