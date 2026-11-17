package animelovers.rebornhd.data.auth

import android.content.Context
import animelovers.rebornhd.util.Prefs

/**
 * Implementasi sementara AuthRepository — TANPA Firebase.
 *
 * Dipakai sampai proyek Firebase baru dibuat + google-services.json dipasang
 * (lihat docs/firebase-setup.md). Semua panggilan sign-in gagal dengan
 * "not_configured" sehingga:
 * - Gerbang Ecchi tetap TERKUNCI (aman — tidak ada jalan pintas).
 * - Pesan yang tampil: "Login dahulu untuk akses genre Ecchi." (Detail)
 *   dan "Masuk belum tersedia — proyek Firebase baru belum dipasang."
 *   (tombol Masuk di Pengaturan).
 * - Tanpa crash, tanpa jalan buntu: pengguna tetap bisa nonton semua genre
 *   non-Ecchi seperti biasa.
 */
class StubAuthRepository(context: Context) : AuthRepository {

    private val prefs = Prefs(context.applicationContext)

    override fun isSignedIn(): Boolean = prefs.authEmail.isNotBlank()

    override fun userEmail(): String = prefs.authEmail

    override fun userPhotoUrl(): String = prefs.authPhotoUrl

    override fun provider(): String = prefs.authProvider

    override suspend fun signInFacebook(): Result<Unit> =
        Result.failure(IllegalStateException("not_configured"))

    override suspend fun signInGoogle(): Result<Unit> =
        Result.failure(IllegalStateException("not_configured"))

    override suspend fun signOut() {
        prefs.clearAuth()
        prefs.clearEcchi()
    }
}
