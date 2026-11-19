package animelovers.rebornhd.ui.player

/**
 * Pelacak frame pertama — dua semantik yang dulu tercampur dalam satu
 * flag `firstFrameReached` (F-1 Cari Bug v2.0.10).
 *
 * - Per-kandidat: frame pertama kandidat YANG SEDANG diputar — dipakai
 *   UI/timing frame pertama (`hideInitialLoading()` + durasi frame
 *   pertama di log). Di-reset tiap kandidat baru mulai (`startMedia3`).
 * - Per-episode: frame pertama episode ini SUDAH PERNAH tercapai —
 *   dipakai guard `persistResume()` (item 10): riwayat/resume HANYA
 *   ditulis bila episode ini sudah pernah mencapai frame pertama, dan
 *   TETAP ditulis bila sudah. Hanya di-reset tiap episode baru
 *   (`loadEpisode`), TIDAK saat pindah kandidat.
 *
 * Skenario yang dulu gagal (regresi vs v2.0.9): menonton bermenit-menit
 * → error/stall → retry kandidat berikut mulai buffering → keluar
 * sebelum frame pertama kandidat baru → guard menolak → posisi tonton +
 * entri Riwayat hilang. Dengan pemisahan ini, keluar saat buffering
 * retry tetap menulis posisi terakhir yang ditonton — sementara film /
 * episode 0-kandidat yang langsung gagal tetap TIDAK masuk Riwayat
 * (flag per-episode tidak pernah set).
 *
 * Kelas MURNI (tanpa Android) — bisa diuji harness (TestV210).
 */
class FirstFrameTracker {
    private var perCandidate: Boolean = false
    private var perEpisode: Boolean = false

    /** Frame pertama tercapai (STATE_READY) — set kedua flag. */
    fun onFirstFrame() {
        perCandidate = true
        perEpisode = true
    }

    /** Kandidat baru mulai diputar — hanya flag per-kandidat yang reset. */
    fun onCandidateStart() {
        perCandidate = false
    }

    /** Episode baru dimuat — kedua flag reset. */
    fun onEpisodeStart() {
        perCandidate = false
        perEpisode = false
    }

    /** Kandidat saat ini sudah mencapai frame pertama. */
    fun hasReachedFirstFrame(): Boolean = perCandidate

    /** Episode ini sudah pernah mencapai frame pertama. */
    fun hasEpisodeReachedFirstFrame(): Boolean = perEpisode
}
