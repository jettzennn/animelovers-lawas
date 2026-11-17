package animelovers.rebornhd.data.auth

/**
 * Seam login — HANYA sebagai gerbang genre Ecchi (PRD §12 poin 5, koreksi Faygo).
 * Aslinya: login Facebook/Google via Firebase, lalu toggle "Aktifkan Ecchi"
 * di halaman profil. Revival memakai pola yang sama, tanpa sistem akun sendiri.
 *
 * Implementasi aktif: [StubAuthRepository] (selalu belum login — Firebase belum
 * dipasang). Bila proyek Firebase baru sudah dibuat (lihat docs/firebase-setup.md),
 * ganti binding di [animelovers.rebornhd.AnimeloversApp] ke implementasi
 * Firebase — antarmuka ini TIDAK berubah, jadi UI + gerbang tidak tersentuh.
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
     * - Failure("not_configured") bila Firebase belum dipasang — UI menampilkan
     *   pesan jujur, BUKAN crash dan BUKAN jalan buntu.
     */
    suspend fun signInFacebook(): Result<Unit>

    /** Sama seperti [signInFacebook], lewat Google. */
    suspend fun signInGoogle(): Result<Unit>

    /**
     * Keluar: Firebase sign-out + hapus pref Ecchi (hrdku87) — seperti aslinya,
     * pengguna harus mengaktifkan ulang setelah login kembali.
     */
    suspend fun signOut()
}
