package animelovers.rebornhd.util

import java.time.LocalDate
import java.time.ZoneId

/**
 * Evaluator MURNI saklar jarak jauh (v2.0.11, PRD item 2 + 4).
 *
 * Berkas ini TIDAK BOLEH mengimpor apa pun dari Android maupun Firebase —
 * harness uji unit mengompilasinya dengan kotlinc JVM polos, tanpa
 * classpath Android. Semua masukan diteruskan sebagai data biasa.
 *
 * URUTAN CEK WAJIB (PRD §11):
 *   1. pengecualian (ID perangkat ada di daftar) → IZINKAN
 *   2. saklar (kill_switch = true) → MATI
 *   3. tanggal kedaluwarsa (hari ini > kill_expiry_date) → MATI
 *   selain itu → IZINKAN.
 *
 * BAHASA: semua alasan dan pesan di sini memakai bahasa NETRAL — fiksi
 * "Server Sanka tidak dapat dijangkau" dan kode alasan remote-off /
 * kedaluwarsa / dikecualikan / diizinkan. JANGAN menulis kata kill,
 * kill switch, dimatikan, atau Firebase di pesan/alasan apa pun:
 * teman Faygo bisa membuka Konsol Diagnostik dan layar error.
 */
object KillSwitchLogic {

    /** Zona waktu pengguna — tanggal kedaluwarsa dinilai per hari lokal. */
    val ZONE_ID: ZoneId = ZoneId.of("Asia/Jakarta")

    /** Pesan default bila kill_message kosong di konsol. */
    const val DEFAULT_MESSAGE = "Server sedang tidak dapat dijangkau. Coba lagi nanti."

    /** Kode alasan netral (dipakai juga di baris log jalur ini). */
    const val REASON_EXEMPT = "dikecualikan"
    const val REASON_SWITCH_OFF = "remote-off"
    const val REASON_EXPIRED = "kedaluwarsa"
    const val REASON_ALLOWED = "diizinkan"
    const val REASON_FETCH_FAILED = "fetch-gagal"

    /**
     * Konfigurasi jarak jauh apa adanya (dari Remote Config).
     *
     * @param switchOn nilai kill_switch (default false = IZINKAN).
     * @param message nilai kill_message — teks detail layar error.
     * @param expiryDate nilai kill_expiry_date "YYYY-MM-DD"
     *        (kosong = tanpa kedaluwarsa).
     * @param exemptIds nilai kill_exempt_ids — ID dipisah koma.
     */
    data class KillSwitchConfig(
        val switchOn: Boolean = false,
        val message: String = DEFAULT_MESSAGE,
        val expiryDate: String = "",
        val exemptIds: String = ""
    )

    /**
     * Pecah daftar ID yang dipisah koma: buang spasi di ujung tiap ID,
     * buang entri kosong. "a, b ,c,, " → {a, b, c}.
     * Pencocokan ID BERSIFAT persis (case-sensitive) — ID Instalasi
     * Firebase adalah string acak yang peka huruf.
     */
    fun parseExemptIds(raw: String): Set<String> =
        raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * Apakah tanggal kedaluwarsa sudah lewat?
     *
     * Aturan: MATI bila hari ini (Asia/Jakarta) LEBIH DARI tanggal
     * kedaluwarsa. Pada tanggal itu sendiri aplikasi MASIH jalan
     * (kedaluwarsa berlaku mulai besoknya).
     * Format salah (bukan YYYY-MM-DD yang valid) = DIABAIKAN
     * (tidak pernah mematikan aplikasi karena salah ketik).
     */
    fun isExpired(expiryDate: String, today: LocalDate = LocalDate.now(ZONE_ID)): Boolean {
        val trimmed = expiryDate.trim()
        if (trimmed.isEmpty()) return false
        val parsed = runCatching { LocalDate.parse(trimmed) }.getOrNull() ?: return false
        return today.isAfter(parsed)
    }

    /**
     * Apakah jalur otomatis WAJIB cek ulang? (delta F-1 Cari Bug v2.0.11)
     *
     * Aturan: bila proses ini BELUM PERNAH cek, paksa cek — tanpa
     * peduli killLastCheckMs (nilai itu persisten di Prefs antar
     * proses, jadi "masih segar" tidak berarti proses ini sudah tahu
     * keadaan saklar — proses baru selalu mulai dari IZINKAN).
     * Bila sudah pernah cek di proses ini, hanya bila cek terakhir
     * sudah lebih dari interval (15 menit).
     */
    fun needsRecheck(
        checkedInProcess: Boolean,
        lastCheckMs: Long,
        nowMs: Long,
        intervalMs: Long
    ): Boolean = !checkedInProcess || nowMs - lastCheckMs > intervalMs

    /**
     * Nilai tanggal hari ini sebagai "YYYY-MM-DD" (Asia/Jakarta).
     * Dipakai wrapper Android untuk membandingkan tanpa java.time
     * di lapisan UI (tetap konsisten dengan [isExpired]).
     */
    fun todayString(today: LocalDate = LocalDate.now(ZONE_ID)): String = today.toString()

    /** 8 karakter pertama ID, untuk baris log (ID penuh TIDAK masuk log). */
    fun shortId(installationId: String?): String {
        val id = installationId.orEmpty()
        return if (id.isEmpty()) "" else id.take(8) + "…"
    }

    /**
     * Evaluasi penuh — satu-satunya jalan penentu IZINKAN / MATI.
     *
     * @param config konfigurasi jarak jauh.
     * @param installationId ID Instalasi perangkat ini (boleh kosong —
     *        ID kosong TIDAK PERNAH dikecualikan).
     * @param today hari ini (Asia/Jakarta); hanya untuk uji.
     */
    fun evaluate(
        config: KillSwitchConfig,
        installationId: String?,
        today: LocalDate = LocalDate.now(ZONE_ID)
    ): KillEvaluation {
        // 1. Pengecualian — berlaku untuk saklar DAN kedaluwarsa.
        if (!installationId.isNullOrBlank() &&
            parseExemptIds(config.exemptIds).contains(installationId)
        ) {
            return KillEvaluation(
                allowed = true,
                reason = REASON_EXEMPT,
                message = config.message
            )
        }
        // 2. Saklar.
        if (config.switchOn) {
            return KillEvaluation(
                allowed = false,
                reason = REASON_SWITCH_OFF,
                message = config.message
            )
        }
        // 3. Tanggal kedaluwarsa.
        if (isExpired(config.expiryDate, today)) {
            return KillEvaluation(
                allowed = false,
                reason = REASON_EXPIRED,
                message = config.message
            )
        }
        return KillEvaluation(
            allowed = true,
            reason = REASON_ALLOWED,
            message = config.message
        )
    }
}

/**
 * Hasil evaluasi saklar.
 *
 * @param allowed true = aplikasi jalan normal; false = tampilkan
 *        error state "Server Sanka Putus" (detail = [message]).
 * @param reason kode alasan netral: dikecualikan / remote-off /
 *        kedaluwarsa / diizinkan / fetch-gagal.
 * @param message teks detail dari kill_message (atau default).
 */
data class KillEvaluation(
    val allowed: Boolean,
    val reason: String,
    val message: String
)
