package animelovers.rebornhd.util

/**
 * Konstanta global. Satu tempat untuk semua nilai yang dipakai lintas layar.
 * URL backend kecil (PRD §3.6 Opsi A) diisi Backend/Deploy — KOSONG berarti
 * backend belum dipasang dan aplikasi memakai jalur langsung (VidCloud).
 */
object Constants {
    const val ANIKOTO_BASE_URL = "https://anikotoapi.site/"
    const val VIDCLOUD_BASE_URL = "https://vidcloud.sbs/"
    const val MEGAPLAY_BASE_URL = "https://megaplay.buzz/"

    /** URL backend kecil (PRD §3.6). Server Node jalan di HP yang sama via
     *  Termux: http://localhost:3100 (lihat animelovers-lawas/server/README.md).
     *  Bila server TIDAK jalan, PlayerRepository jatuh ke jalur langsung
     *  (VidCloud) + subtitle Inggris — aplikasi tetap bisa dipakai.
     *  Bila backend dipindah ke hosting, ganti alamat ini (Deploy). */
    const val BACKEND_BASE_URL = "http://localhost:3100"

    const val GITHUB_REPO_URL = "https://github.com/jettzennn/animelovers-lawas"

    // Extra intent (nama dipertahankan dari aslinya bila ada padanannya).
    const val EXTRA_ID_TITLE = "id_title"
    const val EXTRA_TITLE = "title"
    const val EXTRA_EPISODE = "episode"
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

    // Cap episode di Detail (aturan asli: 40).
    const val DETAIL_EPISODE_CAP = 40

    // Resume: posisi tersimpan dikurangi 30 detik saat keluar player (aturan asli).
    const val RESUME_REWIND_MS = 30_000L

    // Slider Beranda: auto-scroll tiap 3 detik (aturan asli).
    const val SLIDER_INTERVAL_MS = 3_000L

    // Splash: delay sebelum ke Main (aturan asli ±2 detik).
    const val SPLASH_DELAY_MS = 2_000L
}
