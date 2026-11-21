package animelovers.rebornhd.util

import animelovers.rebornhd.AnimeloversApp
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigFetchThrottledException
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Saklar jarak jauh (v2.0.11, PRD item 1 + 2 + 4 + 5).
 *
 * Dua mekanisme, keduanya dikendalikan Faygo dari Firebase Remote
 * Config dan keduanya bisa diubah tanpa rilis baru:
 *   (a) saklar on/off — parameter kill_switch;
 *   (b) tanggal kedaluwarsa — parameter kill_expiry_date (YYYY-MM-DD).
 * Perangkat Faygo dikecualikan dari KEDUANYA lewat daftar ID Instalasi
 * (kill_exempt_ids).
 *
 * FAIL-OPEN (disetujui Faygo): bila pengambilan pengaturan gagal —
 * tidak ada cache, belum pernah fetch, atau Firebase belum terpasang —
 * aplikasi DIIZINKAN jalan memakai nilai default. Install pertama
 * offline TIDAK dikunci.
 *
 * BAHASA: baris log jalur ini memakai bahasa NETRAL — fiksi
 * "Server Sanka tidak dapat dijangkau" + kode alasan remote-off /
 * kedaluwarsa / dikecualikan / fetch-gagal. JANGAN menulis kata kill,
 * kill switch, dimatikan, atau Firebase di layar error maupun baris
 * log jalur ini (teman Faygo bisa membuka Konsol Diagnostik).
 * Pengecualian: nama kelas/metode Firebase di kode ini tidak tampil
 * di layar maupun log.
 */
object KillSwitch {

    /** Nama-nama parameter Remote Config (persis seperti di konsol). */
    const val PARAM_SWITCH = "kill_switch"
    const val PARAM_MESSAGE = "kill_message"
    const val PARAM_EXPIRY_DATE = "kill_expiry_date"
    const val PARAM_EXEMPT_IDS = "kill_exempt_ids"

    /** Pesan default bila kill_message kosong di konsol. */
    const val DEFAULT_MESSAGE = KillSwitchLogic.DEFAULT_MESSAGE

    /** Jeda antar pengambilan pengaturan jarak jauh (PRD: 15 menit). */
    const val FETCH_INTERVAL_SECONDS = 900L

    /** Batas tunggu pengambilan pengaturan di Splash (PRD: 5 detik). */
    const val SPLASH_TIMEOUT_MS = 5_000L

    /** Batas tunggu pengambilan ID Instalasi (PRD: 3 detik). */
    const val INSTALLATION_ID_TIMEOUT_MS = 3_000L

    /** Interval cek ulang berkala di MainActivity.onResume (PRD: 15 menit). */
    const val RECHECK_INTERVAL_MS = 15 * 60 * 1000L

    /** Evaluasi terakhir (nilai awal = IZINKAN — fail-open). */
    private val _state = MutableStateFlow(
        KillEvaluation(
            allowed = true,
            reason = KillSwitchLogic.REASON_ALLOWED,
            message = DEFAULT_MESSAGE
        )
    )
    val state: StateFlow<KillEvaluation> = _state.asStateFlow()

    /** Apakah evaluasi terakhir menyatakan MATI. */
    fun isDead(): Boolean = !_state.value.allowed

    /**
     * Delta F-1: apakah proses ini SUDAH pernah menjalankan satu kali
     * cek. _state selalu mulai IZINKAN di tiap proses baru, sementara
     * killLastCheckMs persisten di Prefs — tanpa penanda ini, jalur
     * pemulihan proses (task restore langsung ke Main/Detail/Player,
     * Splash tidak ikut dipulihkan) menganggap cek "masih segar" dan
     * tidak pernah mengecek sampai 15 menit. Penanda ini yang
     * menutup jendela bypass itu.
     */
    @Volatile
    private var checkedInProcess = false

    /** Apakah proses ini sudah pernah cek (lihat [checkedInProcess]). */
    fun hasCheckedInProcess(): Boolean = checkedInProcess

    /** Kunci supaya dua pemanggil ensureCheckedInProcess yang berbarengan
     *  hanya menghasilkan SATU cek (bukan dua fetch). */
    private val processCheckMutex = Mutex()

    /**
     * Delta F-1: paksa satu kali cek di proses ini — untuk jalur
     * pemulihan proses yang melewati Splash. Bila proses ini belum
     * pernah cek, jalankan satu kali cek penuh; bila sudah, kembalikan
     * evaluasi terakhir tanpa jaringan. Dipakai MainActivity /
     * DetailActivity / VideoPlayActivity / EpisodeActivity /
     * LainnyaActivity di onCreate.
     */
    suspend fun ensureCheckedInProcess(app: AnimeloversApp): KillEvaluation {
        if (checkedInProcess) return _state.value
        return processCheckMutex.withLock {
            if (checkedInProcess) _state.value else check(app)
        }
    }

    /** Evaluasi terakhir (untuk layar error — tanpa fetch baru). */
    fun current(): KillEvaluation = _state.value

    /** Nilai default Remote Config (dipakai sebelum fetch pertama). */
    private fun defaults(): Map<String, Any> = mapOf(
        PARAM_SWITCH to false,
        PARAM_MESSAGE to DEFAULT_MESSAGE,
        PARAM_EXPIRY_DATE to "",
        PARAM_EXEMPT_IDS to ""
    )

    /**
     * Ambil ID Instalasi perangkat ini (unik per install).
     * Disimpan di Prefs supaya tersedia sebelum Firebase sempat
     * menjawab; bila Firebase gagal, pakai simpanan terakhir.
     */
    suspend fun installationId(app: AnimeloversApp): String {
        val cached = app.prefs.installationId
        if (cached.isNotBlank()) return cached
        // Delta F-7: pembatalan coroutine JANGAN ditelan — lempar ulang
        // (runCatching menelan CancellationException).
        val fresh = try {
            withTimeoutOrNull(INSTALLATION_ID_TIMEOUT_MS) {
                FirebaseInstallations.getInstance().id.await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }.orEmpty()
        if (fresh.isNotBlank()) {
            app.prefs.installationId = fresh
            return fresh
        }
        return cached
    }

    /**
     * Cek penuh: ambil pengaturan jarak jauh (maksimal 15 menit sekali
     * menurut SDK), lalu evaluasi dengan urutan WAJIB
     * pengecualian → saklar → kedaluwarsa.
     *
     * @param force true = retry MANUAL ("Coba Lagi") — paksa fetch
     *        segar dari server (fetch(0)), mengabaikan interval 15
     *        menit (delta F-2). false = cek otomatis — interval 15
     *        menit tetap berlaku (throttling SDK dihormati).
     *
     * Fail-open: pengambilan gagal → pakai nilai terakhir/default dan
     * IZINKAN. Fungsi ini TIDAK PERNAH melempar KECUALI pembatalan
     * coroutine (delta F-7: CancellationException dilempar ulang).
     * Penanda "sudah cek di proses ini" + killLastCheckMs HANYA ditulis
     * bila cek benar-benar selesai (sukses/gagal) — bukan saat
     * dibatalkan (delta F-1: penanda basi = jendela bypass).
     */
    suspend fun check(app: AnimeloversApp, force: Boolean = false): KillEvaluation {
        return try {
            val evaluation = checkInternal(app, force)
            app.prefs.killLastCheckMs = System.currentTimeMillis()
            checkedInProcess = true
            evaluation
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Gagal total (mis. Firebase belum terpasang di build ini) —
            // jangan kunci aplikasi karena kesalahannya sendiri.
            // Pesan exception TIDAK dicatat: bisa mengandung nama layanan.
            app.diagLogger.warn(
                DiagLog.TAG_SERVER,
                "Pengambilan pengaturan gagal (fetch-gagal) — memakai nilai terakhir"
            )
            val fallback = KillEvaluation(
                allowed = true,
                reason = KillSwitchLogic.REASON_FETCH_FAILED,
                message = DEFAULT_MESSAGE
            )
            _state.value = fallback
            app.prefs.killLastCheckMs = System.currentTimeMillis()
            checkedInProcess = true
            fallback
        }
    }

    private suspend fun checkInternal(app: AnimeloversApp, force: Boolean): KillEvaluation {
        val remoteConfig = FirebaseRemoteConfig.getInstance()
        remoteConfig.setConfigSettingsAsync(
            FirebaseRemoteConfigSettings.Builder()
                .setMinimumFetchIntervalInSeconds(FETCH_INTERVAL_SECONDS)
                .setFetchTimeoutInSeconds(10)
                .build()
        )
        remoteConfig.setDefaultsAsync(defaults())

        // Ambil nilai terbaru; bila gagal, pakai cache/default (fail-open).
        // Delta F-2: bedakan THROTTLING dari GAGAL — fetch dalam interval
        // 15 menit dikembalikan SDK dari cache (bukan dari server) dan
        // itu BUKAN kegagalan: catat INFO (bukan WARN) dan tanpa kode
        // fetch-gagal. Retry manual (force) memakai fetch(0) supaya
        // "Coba Lagi" benar-benar melihat perubahan konsol.
        // Delta F-7: pembatalan coroutine dilempar ulang, bukan ditelan.
        try {
            if (force) {
                remoteConfig.fetch(0).await()
                remoteConfig.activate().await()
            } else {
                remoteConfig.fetchAndActivate().await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseRemoteConfigFetchThrottledException) {
            app.diagLogger.info(
                DiagLog.TAG_SERVER,
                "Pengaturan jarak jauh masih segar (tertahan) — memakai nilai terakhir"
            )
        } catch (e: Exception) {
            app.diagLogger.warn(
                DiagLog.TAG_SERVER,
                "Pengambilan pengaturan gagal (fetch-gagal) — memakai nilai terakhir"
            )
        }

        val config = KillSwitchLogic.KillSwitchConfig(
            switchOn = remoteConfig.getBoolean(PARAM_SWITCH),
            message = remoteConfig.getString(PARAM_MESSAGE)
                .takeIf { it.isNotBlank() } ?: DEFAULT_MESSAGE,
            expiryDate = remoteConfig.getString(PARAM_EXPIRY_DATE),
            exemptIds = remoteConfig.getString(PARAM_EXEMPT_IDS)
        )
        val id = installationId(app)
        val evaluation = KillSwitchLogic.evaluate(config, id)
        _state.value = evaluation

        // Baris log NETRAL (fiksi + kode alasan; tanpa kata terlarang).
        val short = KillSwitchLogic.shortId(id)
        when {
            !evaluation.allowed && evaluation.reason == KillSwitchLogic.REASON_SWITCH_OFF ->
                app.diagLogger.warn(
                    DiagLog.TAG_SERVER,
                    "Server Sanka tidak dapat dijangkau (remote-off)" +
                        if (short.isEmpty()) "" else " — id $short"
                )
            !evaluation.allowed && evaluation.reason == KillSwitchLogic.REASON_EXPIRED ->
                app.diagLogger.warn(
                    DiagLog.TAG_SERVER,
                    "Server Sanka tidak dapat dijangkau (kedaluwarsa)" +
                        if (short.isEmpty()) "" else " — id $short"
                )
            evaluation.reason == KillSwitchLogic.REASON_EXEMPT ->
                app.diagLogger.info(
                    DiagLog.TAG_SERVER,
                    "Server Sanka dapat dijangkau (dikecualikan)" +
                        if (short.isEmpty()) "" else " — id $short"
                )
            else ->
                app.diagLogger.info(
                    DiagLog.TAG_SERVER,
                    "Server Sanka dapat dijangkau (diizinkan)"
                )
        }
        return evaluation
    }
}
