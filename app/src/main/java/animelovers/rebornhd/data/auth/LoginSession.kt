package animelovers.rebornhd.data.auth

/**
 * Logika MURNI sesi login (v2.0.12) — TANPA impor Android/Firebase,
 * supaya harness kotlinc JVM bisa mengompilasi + mengujinya tanpa
 * classpath Android (pola KillSwitchLogic v2.0.11).
 *
 * Dipakai [FirebaseAuthRepository]: pemetaan provider dari
 * `providerData` Firebase, dan keputusan sinkronisasi Prefs terhadap
 * `currentUser` (isi ulang bila Prefs kosong, bersihkan bila Prefs
 * basi — PRD §B7). Token Google/Facebook TIDAK PERNAH lewat sini
 * (hanya email + URL foto + nama provider).
 */
object LoginSession {

    /** Pengguna masuk menurut Firebase — email/foto boleh kosong. */
    data class SignedInUser(
        val email: String,
        val photoUrl: String,
        val provider: String,
    )

    /** Isi Prefs auth yang tersimpan (kunci lama: auth_email/auth_photo/auth_provider). */
    data class SavedAuth(
        val email: String,
        val photoUrl: String,
        val provider: String,
    )

    /** Hasil keputusan sinkronisasi Prefs terhadap currentUser. */
    sealed interface SyncAction {
        /** Tulis (ulang) Prefs dari pengguna Firebase. */
        data class Save(val user: SignedInUser) : SyncAction

        /** Prefs berisi sisa login lama padahal Firebase sudah tidak
         *  punya pengguna — hapus sisa itu, anggap tamu. */
        object ClearStale : SyncAction

        /** Prefs sudah cocok dengan Firebase — tidak ada yang diubah. */
        object NoOp : SyncAction
    }

    /** Kode status Google Sign-In: pengguna membatalkan sendiri
     *  (GoogleSignInStatusCodes.SIGN_IN_CANCELLED). Ditulis sebagai
     *  angka supaya berkas murni ini tidak mengimpor play-services. */
    const val GOOGLE_SIGN_IN_CANCELLED = 12501

    /** Request code alur masuk Google (startActivityForResult). */
    const val RC_GOOGLE_SIGN_IN = 9001

    /**
     * Nama provider dari `providerData` Firebase — entri pertama
     * ("firebase") diabaikan. "facebook.com" menang bila keduanya
     * ada (tidak mungkin dalam praktik — satu akun satu provider
     * masuk terakhir — tapi deterministik bila terjadi).
     */
    fun providerFromIds(providerIds: List<String>): String =
        when {
            providerIds.any { it == "facebook.com" } -> "facebook"
            providerIds.any { it == "google.com" } -> "google"
            else -> ""
        }

    /** Bangun [SignedInUser] dari data FirebaseUser. Hanya dipanggil
     *  bila currentUser != null — email/foto boleh kosong. */
    fun fromUser(
        email: String?,
        photoUrl: String?,
        providerIds: List<String>,
    ): SignedInUser {
        return SignedInUser(
            email = email.orEmpty(),
            photoUrl = photoUrl.orEmpty(),
            provider = providerFromIds(providerIds),
        )
    }

    /**
     * Keputusan sinkronisasi Prefs (PRD §B7):
     * - Pengguna Firebase ada + Prefs berbeda/kosong → [SyncAction.Save].
     * - Pengguna Firebase ada + Prefs sudah sama persis → [SyncAction.NoOp].
     * - Pengguna Firebase TIDAK ada + Prefs masih berisi sisa login
     *   lama → [SyncAction.ClearStale] (jangan tampilkan profil basi).
     * - Keduanya kosong → [SyncAction.NoOp].
     */
    fun syncAction(user: SignedInUser?, saved: SavedAuth): SyncAction {
        if (user == null) {
            return if (saved.email.isNotEmpty() ||
                saved.photoUrl.isNotEmpty() ||
                saved.provider.isNotEmpty()
            ) {
                SyncAction.ClearStale
            } else {
                SyncAction.NoOp
            }
        }
        return if (user.email == saved.email &&
            user.photoUrl == saved.photoUrl &&
            user.provider == saved.provider
        ) {
            SyncAction.NoOp
        } else {
            SyncAction.Save(user)
        }
    }

    /** True bila kode status Google berarti "pengguna membatalkan". */
    fun isGoogleUserCancelled(statusCode: Int): Boolean =
        statusCode == GOOGLE_SIGN_IN_CANCELLED
}
