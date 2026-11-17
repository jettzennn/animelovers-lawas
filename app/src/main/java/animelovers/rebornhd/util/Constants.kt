package animelovers.rebornhd.util

/**
 * Konstanta global. Satu tempat untuk semua nilai yang dipakai lintas layar.
 *
 * v2 (Sanka): Anikoto + VidCloud + MegaPlay + backend kecil SUDAH DIHAPUS.
 * Satu-satunya sumber konten adalah API Sanka Vollerei (Animekompi).
 */
object Constants {
    /** API Sanka Vollerei (sumber Animekompi). */
    const val SANKA_BASE_URL = "https://www.sankavollerei.web.id/"

    // Timeout panggilan Sanka (alasan sama seperti v1: koneksi seluler
    // Faygo terukur 1,1–3,4 KB/dtk; JSON home ~273 KB butuh puluhan detik
    // di jaringan lambat — jangan terlalu pendek).
    const val SANKA_CONNECT_TIMEOUT_S = 15L
    const val SANKA_READ_TIMEOUT_S = 30L

    /**
     * Jeda minimum antar request Sanka (ms) — 1.400 ms ≈ 42 request/menit,
     * di bawah batas 45/menit yang terukur di riset. 3 pelanggaran = BAN
     * PERMANEN — jangan pernah mengecilkan angka ini (lihat
     * SankaThrottleInterceptor).
     */
    const val SANKA_MIN_INTERVAL_MS = 1_400L

    const val GITHUB_REPO_URL = "https://github.com/jettzennn/animelovers-lawas"

    // Extra intent (nama dipertahankan dari aslinya bila ada padanannya).
    const val EXTRA_ID_TITLE = "id_title"
    const val EXTRA_TITLE = "title"
    const val EXTRA_EPISODE = "episode"
    const val EXTRA_EPISODE_SLUG = "episode_slug"
    const val EXTRA_POSTER = "poster"
    const val EXTRA_MOVIE = "movie"
    const val EXTRA_MODE = "lainnya"

    // Mode LainnyaActivity.
    const val MODE_UPDATE = "Update Lainnya"

    // Genre yang dikunci gerbang login + toggle (keputusan Faygo 2026-11-17:
    // kunci ketiganya — Ecchi + Erotica + Hentai).
    const val GENRE_ECCHI = "Ecchi"
    const val GENRE_EROTICA = "Erotica"
    const val GENRE_HENTAI = "Hentai"
    val LOCKED_GENRES = setOf(GENRE_ECCHI, GENRE_EROTICA, GENRE_HENTAI)

    // Cap episode di Detail (aturan asli: 40) — sisanya di EpisodeActivity.
    const val DETAIL_EPISODE_CAP = 40

    // Resume: posisi tersimpan dikurangi 30 detik saat keluar player (aturan asli).
    const val RESUME_REWIND_MS = 30_000L

    // Slider Beranda: auto-scroll tiap 3 detik (aturan asli).
    const val SLIDER_INTERVAL_MS = 3_000L

    // Splash: delay sebelum ke Main (aturan asli ±2 detik).
    const val SPLASH_DELAY_MS = 2_000L
}
