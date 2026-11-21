package animelovers.rebornhd.ui.player

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Environment
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import animelovers.rebornhd.R
import animelovers.rebornhd.app
import animelovers.rebornhd.data.api.RateLimitException
import animelovers.rebornhd.data.model.PlayableSource
import animelovers.rebornhd.data.model.rewritePosterHost
import animelovers.rebornhd.data.repo.CandidateTryOrder
import animelovers.rebornhd.data.repo.EpisodeSources
import animelovers.rebornhd.data.repo.HttpFailFast
import animelovers.rebornhd.data.repo.PlayerRepository
import animelovers.rebornhd.data.repo.SankaRepository
import animelovers.rebornhd.data.repo.StallTracker
import animelovers.rebornhd.databinding.ActivityVideoBinding
import animelovers.rebornhd.ui.BaseActivity
import animelovers.rebornhd.ui.main.MainActivity
import animelovers.rebornhd.util.Constants
import animelovers.rebornhd.util.DiagError
import animelovers.rebornhd.util.DiagLog
import animelovers.rebornhd.util.KillSwitch
import animelovers.rebornhd.util.KillSwitchLogic
import animelovers.rebornhd.util.isOnWifi
import animelovers.rebornhd.util.toast
import coil.load
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Player v2 (Sanka, hardsub) — adaptasi VideoPlay.java asli
 * (ExoPlayer 2.10.1 → Media3 1.2.1).
 *
 * Yang dipertahankan dari aslinya:
 * - FIT saat buka (setResizeMode 0), toggle FIT/FILL lewat `screenmode`.
 * - Buffer 50 detik / mulai 2,5 detik (DefaultLoadControl).
 * - Resume disimpan posisi − 30 detik saat keluar; orientasi `awallayar`
 *   dipulihkan saat keluar; pref orientasi "6"/"7" (SENSOR_LANDSCAPE/PORTRAIT).
 * - Dialog gerbang WiFi (bila pref wifi_switch "1" dan tidak di WiFi).
 * - Toggle kualitas biner (ikon `resolution`), panel volume (SeekBar max 255),
 *   auto-play `teruskan`, tombol next/prev dengan overlay _off, rotate.
 * - v2.0.5: dialog mode "Unduh"/"Streaming" DIHAPUS (penyimpangan disengaja
 *   dari REA §4.1 atas permintaan Faygo) — masuk player langsung memutar
 *   kandidat awal; tombol Unduh kini DI KONTROL PLAYER (menu kanan, ikon
 *   download_new); unduh TIDAK lagi menutup player (tonton lanjut sambil
 *   mengunduh).
 *
 * v2 (desain.md §14):
 * - TIDAK ADA subtitle track (hardsub menempel di video) — tombol Subtitle
 *   dihapus; TIDAK ADA polling subtitle / backend.
 * - Sumber = file MP4 langsung (bukan HLS): Pixeldrain 720p → stream token
 *   Animekompi 720p → Blogger ekstrak itag 22 (720p) → 18 (360p) → gagal
 *   jujur (toast + keluar, seperti asli). Fallback OTOMATIS antar kandidat
 *   DIRECT bila yang sedang diputar error.
 * - v2.0.7 item 14–16 (fallback lintas-sumber): rantai diperpanjang —
 *   setelah Blogger Animekompi, coba ok.ru (ekstrak tier hd → sd dari
 *   URL embed Animekompi), lalu Samehadaku (cari episode yang sama →
 *   Blogger ekstrak itag 22 → 18), baru gagal jujur (toast + keluar).
 *   Lapis 2–3 MALAS (tidak di-fetch bila lapis 1 sukses; diambil saat
 *   resolve hanya bila lapis 1 kosong, atau belakangan oleh tryLazyLayer
 *   bila semua kandidat sebelumnya gagal). Pengawas macet-buffering (#8)
 *   berlaku per kandidat di semua lapis, ambang tidak diubah.
 * - v2.0.6 (D4): player WAJIB mulai dari video LANGSUNG di kontrol
 *   aplikasi sendiri — default pref `hdOnly` kini "1" (720p direct dulu).
 *   `resolution` mengalihkan KUALITAS di player yang sama (direct 720p ↔
 *   direct 360p), BUKAN mengganti mesin pemutar — seperti
 *   aslinya yang mengganti SD↔HD di ExoPlayer yang sama. Satu tier saja →
 *   ikon `_off`, tidak bisa diketuk. Bila tier 360p belum ada di daftar
 *   (ekstrak Blogger lazy), ketuk `resolution` memicu fetch Blogger
 *   sekali — bila tetap tidak ada, ikon jadi `_off`.
 *   Label kualitas_video = "v" + tinggi video AKTUAL + "p" (mis. "v720p",
 *   dari onVideoSizeChanged, seperti asli) — bukan dari sumber; sebelum
 *   ukuran video diketahui, label sumber dipakai ("v720p"/"v360p").
 *   Label episode = "Movie" untuk film (type == "Movie" dari detail).
 * - v2.0.6 #1b (anti-flip): jaringan lambat BUKAN kegagalan. Timeout HTTP
 *   default Media3 (8 dtk connect/read) terlalu pendek untuk koneksi
 *   lambat — sekali read macet 8 dtk, error naik ke onPlayerError lalu
 *   tryNextCandidate maju ke kandidat berikut (dulu jatuh ke embed —
 *   inilah flip yang dilaporkan Faygo: kontrol aplikasi dulu, lalu
 *   pindah ke player website). Timeout dinaikkan ke 15 dtk (connect) /
 *   30 dtk (read, sama seperti timeout transport Sanka) + retry load
 *   internal 8× (default 3×) supaya kandidat direct yang sama dicoba
 *   ulang dulu sebelum dianggap gagal. Bila semua direct benar-benar
 *   habis, v2.0.8 gagal jujur — toast + keluar seperti asli; dialog
 *   "Putar lewat website?" DIHAPUS (D-web, keputusan Faygo).
 * - Loading awal = ProgressBar + teks "Memuat video…" dan kontrol TIDAK
 *   auto-hide sampai frame pertama tampil.
 * - Resume disimpan −30 detik saat seek / ganti kualitas / keluar (aturan
 *   asli — dulu hanya saat keluar).
 * - "Unduh" = file .mp4 langsung dari Pixeldrain (BUKAN .m3u8 seperti v1).
 *   Bila episode tanpa Pixeldrain, tombol Unduh tidak ditampilkan.
 *   v2.0.5: unduh dari tombol di kontrol player — player TIDAK ditutup.
 * - 429 di dalam player = toast rate limit + keluar (tanpa retry).
 * - v2.0.8: pengawas macet pakai ambang pertumbuhan minimal 500 ms/cek
 *   (item 1 — buffer yang hanya merambat dihitung macet); playback
 *   DataSource memakai UA browser + Referer per-kind (item 2); HTTP
 *   451/404/410 fail-fast tanpa retry (item 3); opsi "Putar lewat
 *   website" + jalur pemutar website DIHAPUS TOTAL (item 5, D-web).
 */
@OptIn(UnstableApi::class)
class VideoPlayActivity : BaseActivity() {

    override val applyAppTheme: Boolean = false // FullscreenTheme dari manifest.

    private lateinit var binding: ActivityVideoBinding
    private var player: ExoPlayer? = null

    /**
     * Factory sumber data HTTP playback (v2.0.8 item 2) — dibuat di
     * setupPlayer(), dipakai ulang di startMedia3() untuk memasang
     * Referer per-kind (headersFor) sebelum media item dipasang.
     * Aman karena playback strictly sequential (satu kandidat aktif).
     */
    private lateinit var httpDataSourceFactory: DefaultHttpDataSource.Factory

    private var animeSlug: String = ""
    private var animeTitle: String = ""
    private var animePoster: String = ""
    private var episode: Int = 1
    private var episodeSlug: String = ""

    /** Film (type == "Movie" dari detail) — label episode jadi "Movie". */
    private var isMovie: Boolean = false

    /**
     * Tinggi video AKTUAL (px) dari onVideoSizeChanged — label kualitas
     * "v720p" diisi dari sini (seperti asli). 0 = belum diketahui (pakai
     * label sumber dengan awalan "v"). Direset tiap ganti kandidat.
     */
    private var videoHeight: Int = 0

    /**
     * Target seek PROGRAMATIS terakhir (ms) — seekTo(resume) di
     * startMedia3 juga memicu onPositionDiscontinuity(SEEK); tanpa
     * penanda ini, simpan-resume-saat-seek akan menulis ulang posisi
     * resume (sudah −30 dtk) menjadi −60 dtk, lalu −90 dtk, dst.
     * −1 = tidak ada seek programatis yang menunggu callback.
     */
    private var lastProgrammaticSeekTargetMs: Long = -1L

    private var sources: EpisodeSources? = null
    private var candidateIndex: Int = -1
    private var currentSource: PlayableSource? = null
    /**
     * Indeks kandidat yang SUDAH dicoba untuk episode ini (v2.0.10
     * item 9) — dipakai [CandidateTryOrder.nextIndex] supaya kandidat
     * #0 ikut dicoba (dulu tryNextCandidate hanya berjalan maju dari
     * kandidat awal, jadi #0 tidak pernah dicoba bila awalnya #1).
     * Dikosongkan tiap ganti episode (loadEpisode).
     */
    private val triedCandidateIndices = mutableSetOf<Int>()
    private var episodeNumbers: List<Int> = emptyList()
    private var episodeSlugs: Map<Int, String> = emptyMap()

    /**
     * Job resolve episode yang sedang berjalan (#2): dua ketuk Berikutnya
     * cepat = resolve lama DIBATALKAN sebelum resolve baru dimulai — tanpa
     * ini, coroutine yang selesai terakhir bisa menulis sources episode
     * pertama dengan label episode kedua + resume tersimpan di nomor salah.
     */
    private var loadJob: Job? = null

    /** Job putar kandidat (baca posisi resume dari DB) — dibatalkan bila
     *  episode/sumber berganti sebelum media item dipasang. */
    private var playJob: Job? = null

    /** Job fetch lazy lintas-lapis (#6 Blogger, item 15 ok.ru, item 16
     * Samehadaku) — dibatalkan bila episode berganti. */
    private var lazyLayerJob: Job? = null

    /**
     * Ekstrak Blogger lazy sudah dicoba untuk FALLBACK episode ini
     * (v2.0.7 #8, perbaikan bug Cari Bug v2.0.6 L-1): DIPISAH dari alih
     * kualitas — fetch untuk alih kualitas (ketuk ikon resolusi) TIDAK
     * BOLEH menghabiskan jatah fetch fallback. Sebelumnya satu flag
     * dipakai kedua jalur: alih kualitas yang gagal menekan fetch
     * Blogger di tryNextCandidate, sehingga dialog embed muncul sebelum
     * itag 22/18 benar-benar dicoba.
     */
    private var bloggerAttemptedFallback: Boolean = false

    /** Ekstrak ok.ru lazy sudah dicoba untuk FALLBACK episode ini (item 15). */
    private var okRuAttemptedFallback: Boolean = false

    /** Adapter Samehadaku sudah dicoba untuk FALLBACK episode ini (item 16). */
    private var samehadakuAttemptedFallback: Boolean = false

    /** Ekstrak Blogger lazy sudah dicoba untuk ALIH KUALITAS episode ini. */
    private var bloggerAttemptedQuality: Boolean = false

    /** Job pengawas macet-buffering (v2.0.7 #8) — lihat startStallWatch(). */
    private var stallWatchJob: Job? = null

    /** Waktu mulai kandidat berjalan (elapsedRealtime) — untuk log. */
    private var candidateStartedAtMs: Long = 0L

    private var isHd: Boolean = false
    private var isFill: Boolean = false
    private var wasPlaying: Boolean = false
    private var resumeSaved: Boolean = false
    private var wifiDialogShown: Boolean = false

    /**
     * Pelacak frame pertama (F-1 Cari Bug v2.0.10 — dulu satu flag
     * `firstFrameReached` yang bersemantik per-kandidat karena di-reset
     * di `startMedia3()` juga):
     * - per-kandidat → UI/timing frame pertama (`hideInitialLoading()`);
     * - per-episode → guard `persistResume()` (item 10): riwayat/resume
     *   HANYA ditulis bila episode ini SUDAH PERNAH mencapai frame
     *   pertama — film/episode 0-kandidat yang langsung gagal TIDAK
     *   BOLEH masuk Riwayat/"Lanjutkan" — tapi tetap ditulis bila sudah
     *   (keluar saat buffering retry setelah menonton).
     */
    private val firstFrameTracker = FirstFrameTracker()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        /** Pengawas macet-buffering (v2.0.7 #8): cek tiap 10 dtk. */
        private const val STALL_CHECK_INTERVAL_MS = 10_000L

        /** Buffer tidak bertambah selama ini → kandidat dianggap macet. */
        private const val STALL_NO_GROWTH_MS = 30_000L

        /**
         * Ambang pertumbuhan minimal (v2.0.8 item 1): tumbuh < ini per
         * cek 10 dtk = MACET (bukan tumbuh). 500 ms/10 dtk = 50 ms/dtk →
         * menuntut ≥ 3,35 KB/dtk (360p) / ≥ 9 KB/dtk (720p). Baseline
         * DIMAJUKAN di setiap cek (lihat startStallWatch) — jangan ubah
         * aturan itu: varian baseline-saat-lolos gagal diam-diam untuk
         * Ep 1180 (buffer merambat 450 ms/cek).
         */
        private const val STALL_MIN_GROWTH_MS = 500L

        /** Buffering satu kandidat paling lama ini → lanjut kandidat berikut. */
        private const val STALL_MAX_MS = 120_000L

        fun start(
            context: Context,
            animeSlug: String,
            title: String,
            poster: String,
            episode: Int,
            episodeSlug: String,
            isMovie: Boolean = false
        ) {
            // v2.0.11: jangan buka player bila saklar MATI — kembali ke
            // error state Beranda (bukan toast + diam).
            if (KillSwitch.isDead()) {
                MainActivity.startDead(context)
                return
            }
            val intent = Intent(context, VideoPlayActivity::class.java).apply {
                putExtra(Constants.EXTRA_ID_TITLE, animeSlug)
                putExtra(Constants.EXTRA_TITLE, title)
                putExtra(Constants.EXTRA_POSTER, poster)
                putExtra(Constants.EXTRA_EPISODE, episode)
                putExtra(Constants.EXTRA_EPISODE_SLUG, episodeSlug)
                putExtra(Constants.EXTRA_MOVIE, isMovie)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // v2.0.11: pertahanan lapis kedua — pemanggil start() sudah
        // mengecek, tapi intent eksplisit (deep-link) tidak. Jangan
        // putar apa pun bila saklar MATI.
        if (KillSwitch.isDead()) {
            MainActivity.startDead(this)
            finish()
            return
        }

        animeSlug = intent.getStringExtra(Constants.EXTRA_ID_TITLE).orEmpty()
        animeTitle = intent.getStringExtra(Constants.EXTRA_TITLE).orEmpty()
        animePoster = intent.getStringExtra(Constants.EXTRA_POSTER).orEmpty()
        episode = intent.getIntExtra(Constants.EXTRA_EPISODE, 1)
        episodeSlug = intent.getStringExtra(Constants.EXTRA_EPISODE_SLUG).orEmpty()
        isMovie = intent.getBooleanExtra(Constants.EXTRA_MOVIE, false)

        // Simpan orientasi sebelum masuk player — dipulihkan saat keluar.
        if (prefs.awallayar.isBlank()) {
            prefs.awallayar = requestedOrientation.toString()
        }

        // Orientasi awal dari pref ("6" landscape / "7" portrait, default 6).
        val orientationPref = prefs.orientation.toIntOrNull()
            ?: ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        requestedOrientation = orientationPref

        isHd = prefs.hdOnly == "1"

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                saveAndFinish()
            }
        })

        setupPlayer()
        wireControls()
        registerWifiCallback()

        // v2.0.11 (delta F-1): jalur pemulihan proses (task restore
        // langsung ke player) melewati Splash — paksa satu kali cek di
        // proses ini SEBELUM episode dimuat. Bila MATI, kembali ke error
        // state Beranda (bukan memutar dulu lalu berhenti).
        lifecycleScope.launch {
            val decision = KillSwitch.ensureCheckedInProcess(app())
            if (!decision.allowed) {
                MainActivity.startDead(this@VideoPlayActivity)
                finish()
                return@launch
            }
            // Gerbang WiFi (pref wifi_switch "1" + tidak di WiFi).
            if (prefs.wifiSwitch == "1" && !isOnWifi()) {
                showWifiGateDialog()
            } else {
                loadEpisode(episode, episodeSlug)
            }
        }
    }

    // ---- Setup player ----

    private fun setupPlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(50_000, 50_000, 2_500, 5_000)
            .build()
        // v2.0.6 #1b — jaringan lambat BUKAN kegagalan: timeout HTTP
        // default Media3 (8 dtk connect/read) terlalu pendek untuk koneksi
        // lambat — sekali read macet 8 dtk, error naik ke onPlayerError
        // lalu tryNextCandidate maju ke kandidat berikut (dulu jatuh ke
        // embed — flip yang dilaporkan Faygo). Naikkan ke 15 dtk
        // (connect) / 30 dtk (read, sama seperti timeout transport
        // Sanka) + retry load internal 8× (default 3×) supaya kandidat
        // direct yang sama dicoba ulang dulu sebelum dianggap gagal.
        // v2.0.8 item 2: UA browser + cross-protocol redirect — Media3
        // mengirim UA bawaan ExoPlayerLib tanpa Referer; token/ok.ru
        // dilayani lebih andal sebagai browser (hardening, BUKAN
        // penyebab Ep 1180 — file itag 18 terbukti bisa dijangkau
        // request polos). Referer per-kind dipasang startMedia3()
        // (headersFor) sebelum media item dipasang.
        httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(PlayerRepository.OKRU_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory)
            .setLoadErrorHandlingPolicy(DirectLoadErrorHandlingPolicy())
        val exoPlayer = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        player = exoPlayer

        binding.exoPlayerView.player = exoPlayer
        // FIT saat buka (seperti VideoPlay.onCreate: setResizeMode(0)).
        binding.exoPlayerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        binding.progressBar.visibility = View.GONE
                        updateBufferingUi(false)
                        stopStallWatch()
                        updatePlayPauseButton()
                        if (!firstFrameTracker.hasReachedFirstFrame()) {
                            firstFrameTracker.onFirstFrame()
                            hideInitialLoading()
                            // v2.0.9 item 3: frame pertama dicatat (durasi
                            // sejak kandidat mulai) — WARN bila > 10 dtk.
                            // Cermin logcat dikerjakan DiagLogger.
                            val firstFrameMs =
                                SystemClock.elapsedRealtime() - candidateStartedAtMs
                            app().diagLogger.info(
                                DiagLog.TAG_PLAYER,
                                "Ep $episode: frame pertama setelah ${firstFrameMs}ms " +
                                    "(kandidat ${currentSource?.kind})"
                            )
                            if (firstFrameMs > DiagLog.SLOW_FIRST_FRAME_MS) {
                                app().diagLogger.warn(
                                    DiagLog.TAG_PLAYER,
                                    "Ep $episode: frame pertama lambat (${firstFrameMs}ms)"
                                )
                            }
                        }
                    }
                    Player.STATE_BUFFERING -> {
                        if (exoPlayer.playWhenReady) {
                            binding.progressBar.visibility = View.VISIBLE
                        }
                        updateBufferingUi(true)
                        updatePlayPauseButton()
                        // v2.0.7 #8: awasi macet — stream yang terbuka tapi
                        // tidak pernah cukup buffer TIDAK menghasilkan error,
                        // jadi rantai kandidat tidak maju sendiri (lihat
                        // startStallWatch).
                        if (exoPlayer.playWhenReady) {
                            startStallWatch()
                        }
                    }
                    Player.STATE_ENDED -> {
                        stopStallWatch()
                        updatePlayPauseButton()
                        onPlaybackEnded()
                    }
                    Player.STATE_IDLE -> stopStallWatch()
                }
            }

            /**
             * Tombol play/pause bergantian (v2.0.7 #6) — playWhenReady
             * berubah saat play()/pause() dipanggil (termasuk ketuk tombol
             * play/pause itu sendiri).
             */
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, @Player.PlayWhenReadyChangeReason reason: Int) {
                updatePlayPauseButton()
                if (playWhenReady) {
                    val p = exoPlayer
                    if (p.playbackState == Player.STATE_BUFFERING) {
                        startStallWatch()
                    }
                } else {
                    stopStallWatch()
                }
            }

            /**
             * Label kualitas dari tinggi video AKTUAL (seperti asli:
             * "v720p") — diukur dari video yang sedang diputar, bukan
             * dari sumber. Dipanggil tiap media item baru.
             */
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0 && videoSize.height != videoHeight) {
                    videoHeight = videoSize.height
                    updateQualityLabel()
                }
            }

            /**
             * Simpan resume saat SEEK (aturan asli — dulu hanya saat
             * keluar). Seek programatis (seekTo resume saat mulai putar)
             * DITANDAI lewat lastProgrammaticSeekTargetMs supaya tidak
             * ikut disimpan (tanpa penanda, posisi resume −30 dtk akan
             * ditulis ulang jadi −60, −90, dst. tiap buka episode).
             */
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason != Player.DISCONTINUITY_REASON_SEEK) return
                if (newPosition.positionMs == lastProgrammaticSeekTargetMs) {
                    lastProgrammaticSeekTargetMs = -1L
                    return
                }
                persistResume()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Abaikan error basi (dari media item episode sebelumnya —
                // bisa tiba setelah pengguna pindah episode).
                val currentUri = exoPlayer.currentMediaItem
                    ?.localConfiguration?.uri?.toString()
                val source = currentSource
                if (source == null || currentUri != source.url) return
                // Log TANPA URL (token stream sensitif) — hanya jenis error
                // + penyebabnya (mis. InvalidResponseCodeException = HTTP
                // 451/404: file di-takedown. v2.0.8 item 3: HTTP 451/404/410
                // fail-fast via override getRetryDelayMsFor (TIME_UNSET) —
                // tanpa retry; error transient (IO/5xx/408/429) tetap
                // DI-retry 8× oleh DefaultLoadErrorHandlingPolicy Media3
                // (jeda 0,1,2,3,4,5,5 dtk, maks ~20 dtk/kandidat).
                // onPlayerError ini baru tiba SETELAH retry itu habis
                // (untuk transient) atau langsung (untuk 451/404/410).
                // v2.0.9 item 3: error kandidat dicatat (tanpa URL — token
                // stream sensitif; hanya jenis error + penyebabnya).
                // v2.0.10 item 2: penyebab error pakai DiagError (nama
                // kelas hasil rename R8 — 1 huruf — tidak boleh masuk log).
                app().diagLogger.error(
                    DiagLog.TAG_PLAYER,
                    "Ep $episode: error kandidat ${source.kind} setelah " +
                        "${SystemClock.elapsedRealtime() - candidateStartedAtMs}ms " +
                        "(${error.errorCodeName}, ${DiagError.describe(error.cause)})"
                )
                stopStallWatch()
                tryNextCandidate()
            }
        })
        // Keadaan awal tombol play/pause (v2.0.7 #6): sebelum play(),
        // playWhenReady=false → hanya ▶ yang tampil.
        updatePlayPauseButton()
    }

    // ---- Kontrol kustom di exo_playback_control_view.xml ----

    private fun wireControls() {
        val playerView: PlayerView = binding.exoPlayerView
        playerView.showController()

        val titleView = playerView.findViewById<TextView>(R.id.textViewTITLE)
        titleView?.text = animeTitle
        updateEpisodeLabel()
        val posterView = playerView.findViewById<ImageView>(R.id.postervideo)
        posterView?.load(rewritePosterHost(animePoster)) {
            placeholder(R.drawable.splashh)
            // v2.0.6 #4: error BERBEDA dari placeholder (ikon netral) —
            // poster yang gagal muat tidak lagi tampil sebagai chibi.
            error(R.drawable.ic_poster_error)
            size(60, 80)
        }

        playerView.findViewById<View>(R.id.fabvid)?.setOnClickListener { saveAndFinish() }

        // Play/pause (v2.0.7 #6 + perbaikan H-1 Cari Bug): kedua tombol
        // ini view biasa di layout kontrol kustom — PlayerControlView
        // Media3 1.2.1 hanya mengikat SATU tombol `exo_play_pause`, yang
        // tidak ada di layout ini (grep: 0 kemunculan di res/ + java/),
        // jadi library tidak menyentuh keduanya sama sekali. Ikat
        // langsung di sini; play()/pause() mengubah playWhenReady →
        // onPlayWhenReadyChanged → updatePlayPauseButton() menukar
        // visibilitasnya (pause → hanya ▶, main → hanya ❚❚).
        playerView.findViewById<View>(R.id.exo_play)?.setOnClickListener { player?.play() }
        playerView.findViewById<View>(R.id.exo_pause)?.setOnClickListener { player?.pause() }

        // FIT/FILL.
        val screenMode = playerView.findViewById<TextView>(R.id.screenmode)
        screenMode?.text = getString(if (isFill) R.string.layar_penuh else R.string.layar_auto)
        playerView.findViewById<View>(R.id.rotateimg)?.setOnClickListener {
            toggleOrientation()
        }
        screenMode?.setOnClickListener { toggleResizeMode() }
        playerView.findViewById<View>(R.id.menu_kiri)?.setOnClickListener { toggleResizeMode() }

        // Kualitas direct 720p ↔ 360p di player yang sama (lihat onQualityToggle).
        playerView.findViewById<View>(R.id.resolution)?.setOnClickListener {
            onQualityToggle()
        }

        // Volume.
        playerView.findViewById<View>(R.id.volView)?.setOnClickListener {
            val layout = playerView.findViewById<View>(R.id.volume_layout)
            layout?.visibility =
                if (layout?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        setupVolumeSeekBar(playerView)

        // Auto-play (teruskan) — pref menyimpan "true"/"false" (pola asli).
        val playMode = playerView.findViewById<TextView>(R.id.play_mode)
        playMode?.text = getString(
            if (prefs.teruskan == "true") R.string.putar_otomatis else R.string.putar_sekali
        )
        playMode?.setOnClickListener {
            val next = if (prefs.teruskan == "true") "false" else "true"
            prefs.teruskan = next
            playMode.text = getString(
                if (next == "true") R.string.putar_otomatis else R.string.putar_sekali
            )
        }

        // Next / prev (tombolnya ada di kontrol Media3).
        playerView.findViewById<View>(R.id.next_play_png)?.setOnClickListener {
            val next = nextEpisode() ?: return@setOnClickListener
            val slug = nextEpisodeSlug() ?: return@setOnClickListener
            loadEpisode(next, slug)
        }
        playerView.findViewById<View>(R.id.prev_play_png)?.setOnClickListener {
            val prev = prevEpisode() ?: return@setOnClickListener
            val slug = prevEpisodeSlug() ?: return@setOnClickListener
            loadEpisode(prev, slug)
        }

        // Unduh (v2.0.5, D1): tombol di kontrol player — ganti jalan masuk
        // lama lewat dialog mode. Hanya tampil bila ada file .mp4
        // langsung (Pixeldrain); visibilitas diatur updateDownloadUi().
        playerView.findViewById<View>(R.id.downloadBtn)?.setOnClickListener {
            onDownloadClicked()
        }
    }

    private fun setupVolumeSeekBar(playerView: PlayerView) {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val seekBar = playerView.findViewById<SeekBar>(R.id.seekVol) ?: return
        seekBar.max = 255
        seekBar.progress =
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 255 / maxVolume.coerceAtLeast(1)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val volume = progress * maxVolume / 255
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        })
    }

    private fun toggleResizeMode() {
        isFill = !isFill
        binding.exoPlayerView.resizeMode =
            if (isFill) AspectRatioFrameLayout.RESIZE_MODE_FILL
            else AspectRatioFrameLayout.RESIZE_MODE_FIT
        binding.exoPlayerView.findViewById<TextView>(R.id.screenmode)?.text =
            getString(if (isFill) R.string.layar_penuh else R.string.layar_auto)
    }

    private fun toggleOrientation() {
        val next = if (prefs.orientation == "6") "7" else "6"
        prefs.orientation = next
        requestedOrientation = next.toInt()
    }

    /** Label episode: "Movie" untuk film (type == "Movie"), "Episode N" sisanya. */
    private fun updateEpisodeLabel() {
        binding.exoPlayerView.findViewById<TextView>(R.id.textViewEPISODE)?.text =
            if (isMovie) getString(R.string.movie_label)
            else getString(R.string.episode_label, episode)
    }

    // ---- Kualitas direct 720p ↔ direct 360p (alih kualitas, BUKAN mesin) ----

    /**
     * Tombol `resolution` (v2.0.6): alih KUALITAS di player yang sama —
     * direct 720p ↔ direct 360p (Media3 tetap Media3), seperti aslinya
     * yang mengganti SD↔HD di ExoPlayer yang sama.
     *
     * Bila tier 360p belum ada di daftar (ekstrak Blogger lazy belum
     * jalan), ketuk tombol memicu fetch Blogger sekali — bila hasilnya
     * tetap tanpa 360p, tombol jadi `_off` (updateQualityUi) dan video
     * tetap jalan di 720p.
     */
    private fun onQualityToggle() {
        val all = sources ?: return
        val current = currentSource ?: return
        val hasHdDirect = all.candidates.any { it.isHd }
        val hasSdDirect = all.candidates.any { !it.isHd }
        if (!hasHdDirect && !hasSdDirect) return
        val restore = player?.currentPosition ?: 0L
        if (current.isHd) {
            // 720p → 360p (direct).
            val sdIndex = all.candidates.indexOfFirst { !it.isHd }
            if (sdIndex >= 0) {
                persistResume()
                isHd = false
                prefs.hdOnly = "0"
                playCandidate(sdIndex, restore)
                return
            }
            // Tier 360p belum ada — coba ekstrak Blogger sekali (lazy,
            // pola yang sama seperti tryNextCandidate): bila ada itag 18,
            // sisipkan lalu putar.
            fetchBloggerForQualitySwitch(restore)
        } else {
            // 360p → 720p (direct).
            val hdIndex = all.candidates.indexOfFirst { it.isHd }
            if (hdIndex >= 0) {
                persistResume()
                isHd = true
                prefs.hdOnly = "1"
                playCandidate(hdIndex, restore)
            }
        }
    }

    /**
     * Fetch Blogger lazy untuk alih kualitas (bukan untuk fallback):
     * sisipkan kandidat Blogger hasil ekstrak di akhir daftar, lalu putar
     * 360p bila ada. Bila tidak ada (iframe kosong / ekstrak gagal),
     * tetap di kualitas sekarang dan ikon resolution jadi `_off`.
     */
    private fun fetchBloggerForQualitySwitch(restorePosition: Long) {
        val all = sources ?: return
        if (bloggerAttemptedQuality) return
        val iframeUrl = all.bloggerIframeUrl
        if (iframeUrl.isNullOrBlank()) return
        bloggerAttemptedQuality = true
        showInitialLoading()
        val snapshot = all
        lazyLayerJob?.cancel()
        lazyLayerJob = lifecycleScope.launch {
            val blogger = try {
                app().playerRepository.fetchBloggerCandidates(iframeUrl, episode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            // Episode sudah berganti selagi fetch berjalan — abaikan.
            if (sources !== snapshot) return@launch
            val current = sources ?: return@launch
            // L-14: buang kandidat yang URL-nya sudah ada (Blogger
            // mungkin sudah di-fetch eager saat resolve() — tanpa cek
            // ini itag 22 masuk dua kali lalu diputar ulang).
            val fresh = blogger.filter { c ->
                current.candidates.none { it.url == c.url }
            }
            if (fresh.isNotEmpty()) {
                val updated = current.candidates.toMutableList()
                updated.addAll(fresh)
                sources = current.copy(candidates = updated)
            }
            val sdIndex = sources?.candidates
                ?.indexOfFirst { !it.isHd } ?: -1
            if (sdIndex >= 0) {
                persistResume()
                isHd = false
                prefs.hdOnly = "0"
                playCandidate(sdIndex, restorePosition)
            } else {
                // Tetap tidak ada tier 360p — sembunyikan loading, ikon
                // resolution jadi _off lewat updateQualityUi().
                hideInitialLoading()
                updateQualityUi()
            }
        }
    }

    /** Label kualitas: "v" + tinggi video AKTUAL + "p" (mis. "v720p"). */
    private fun updateQualityLabel() {
        val current = currentSource
        val kualitas = binding.exoPlayerView.findViewById<TextView>(R.id.kualitas_video)
        kualitas?.text = if (videoHeight > 0) {
            "v${videoHeight}p"
        } else {
            "v${current?.qualityLabel ?: "720p"}"
        }
    }

    /** Label + ikon kualitas mengikuti kandidat direct yang tersedia. */
    private fun updateQualityUi() {
        updateQualityLabel()
        val all = sources
        val current = currentSource
        val resolution = binding.exoPlayerView.findViewById<ImageView>(R.id.resolution)
        val hasHd = all?.candidates?.any { it.isHd } == true
        // Tier SD = direct 360p (Blogger itag 18).
        val hasSd = all?.candidates?.any { !it.isHd } == true
        val canToggle = hasHd && hasSd
        val isHdNow = current?.isHd == true
        resolution?.setImageResource(
            when {
                !canToggle -> if (isHdNow) R.drawable.hd_icon_off else R.drawable.sd_icon_off
                isHdNow -> R.drawable.hd_icon
                else -> R.drawable.sd_icon
            }
        )
        resolution?.isClickable = canToggle
        resolution?.alpha = if (canToggle) 1.0f else 0.5f
    }

    /**
     * Perilaku BUFFERING ala asli (gap G2, v2.0.6 #5): overlay next/prev
     * TAMPIL, tombol next/prev PNG + label kualitas DISEMBUNYIKAN, klik
     * ikon resolusi DINONAKTIFKAN; READY membalikkan semuanya.
     */
    private fun updateBufferingUi(buffering: Boolean) {
        val playerView: PlayerView = binding.exoPlayerView
        val kualitas = playerView.findViewById<TextView>(R.id.kualitas_video)
        val resolution = playerView.findViewById<ImageView>(R.id.resolution)
        if (buffering) {
            playerView.findViewById<View>(R.id.next_play_png)?.visibility = View.GONE
            playerView.findViewById<View>(R.id.prev_play_png)?.visibility = View.GONE
            playerView.findViewById<View>(R.id.next_play_overlay)?.visibility = View.VISIBLE
            playerView.findViewById<View>(R.id.prev_play_overlay)?.visibility = View.VISIBLE
            kualitas?.visibility = View.GONE
            resolution?.isClickable = false
            resolution?.alpha = 0.5f
        } else {
            kualitas?.visibility = View.VISIBLE
            updateNextPrevButtons()
            updateQualityUi()
        }
    }

    /**
     * Satu tombol play/pause bergantian (v2.0.7 #6 — seperti aslinya):
     * pause → hanya ▶ (exo_play); main → hanya ❚❚ (exo_pause); tidak
     * pernah keduanya sekaligus. Dikelola eksplisit di sini (bukan
     * mengandalkan PlayerView): listener ini didaftarkan SETELAH
     * PlayerView memasang listener internalnya, jadi keputusan kita yang
     * menang. Dipanggil dari onPlaybackStateChanged +
     * onPlayWhenReadyChanged + akhir setupPlayer.
     */
    private fun updatePlayPauseButton() {
        val playerView = binding.exoPlayerView
        val exoPlayer = player ?: return
        val playing = exoPlayer.playWhenReady &&
            exoPlayer.playbackState != Player.STATE_ENDED &&
            exoPlayer.playbackState != Player.STATE_IDLE
        playerView.findViewById<View>(R.id.exo_play)?.visibility =
            if (playing) View.GONE else View.VISIBLE
        playerView.findViewById<View>(R.id.exo_pause)?.visibility =
            if (playing) View.VISIBLE else View.GONE
    }

    /**
     * Pengawas macet-buffering (v2.0.7 #8) — diagnosis + perbaikan untuk
     * episode yang mentok "Memuat video..." (Yasei S2 Ep 1, Tensei II Ep 1,
     * terverifikasi live 2026-11-19):
     * - Pixeldrain + token Animekompi kedua episode menjawab HTTP 451
     *   (file di-takedown — kontrol Terlaris #1 tetap 200, jadi bukan
     *   blok IP). v2.0.8 item 3: HTTP 451/404/410 fail-fast via
     *   override getRetryDelayMsFor (TIME_UNSET) — tanpa retry; error
     *   transient (IO/5xx/408/429) tetap DI-retry 8× oleh
     *   DefaultLoadErrorHandlingPolicy Media3 (jeda 0,1,2,3,4,5,5 dtk,
     *   maks ~20 dtk/kandidat) — onPlayerError baru tiba setelah retry
     *   itu habis (transient) atau langsung (451/404/410), lalu rantai
     *   maju ke Blogger itag 22/18 (terbukti ada).
     * - itag 22 (720p) di koneksi lambat bisa terbuka TANPA error tapi
     *   tidak pernah cukup buffer untuk frame pertama — Media3 diam di
     *   BUFFERING, tanpa error, tanpa retry. Itulah spinner tanpa ujung.
     * Pengawas: selama BUFFERING, cek tiap 10 dtk totalBufferedDuration.
     * Bila buffer TIDAK bertambah 30 dtk (jaringan macet total) ATAU
     * buffering satu kandidat sudah 120 dtk (merambat tapi tak kunjung
     * cukup — 720p di 5,5 KB/dtk butuh ~86 dtk untuk 2,5 dtk buffer),
     * anggap kandidat macet → tryNextCandidate() (ke kandidat berikut —
     * 360p / ok.ru / Samehadaku, lalu gagal jujur: toast + keluar).
     * Batas ini per kandidat, bukan per episode — kandidat berikut (360p,
     * ~1/4 byte 720p) punya peluang nyata untuk jalan di koneksi lambat.
     * Pengawas berlaku per kandidat di SEMUA lapis rantai (v2.0.7 item 17:
     * ambang TIDAK diubah untuk lapis ok.ru/Samehadaku).
     * v2.0.8 item 1 — ambang pertumbuhan minimal: buffer yang hanya
     * merambat < 500 ms per cek 10 dtk DIHITUNG MACET (kasus Ep 1180:
     * buffer tumbuh ~450 ms/cek di 3 KB/dtk — cek lama "bertambah apa
     * pun = tumbuh" me-reset timer selamanya, hanya cap 120 dtk yang
     * menyelamatkan). Baseline (lastBufferedMs) DIMAJUKAN di setiap
     * cek; hanya timer (lastGrowthAt) yang di-reset bila tumbuh ≥ 500
     * ms. Kalibrasi: 500 ms/10 dtk = 50 ms/dtk → menuntut ≥ 3,35 KB/dtk
     * (360p) / ≥ 9 KB/dtk (720p); 360p di 3 KB/dtk (~450 ms/cek)
     * dimatikan di 30 dtk; 720p di 23 KB/dtk (~1.280 ms/cek) lolos,
     * frame pertama ~20 dtk. Ambang 10/30/120 dtk TIDAK diubah.
     */
    private fun startStallWatch() {
        val exoPlayer = player ?: return
        val candidate = currentSource ?: return
        stallWatchJob?.cancel()
        val startedAt = SystemClock.elapsedRealtime()
        var lastBufferedMs = exoPlayer.totalBufferedDuration
        var lastGrowthAt = startedAt
        stallWatchJob = lifecycleScope.launch {
            while (isActive) {
                delay(STALL_CHECK_INTERVAL_MS)
                val p = player ?: break
                if (p.playbackState != Player.STATE_BUFFERING || !p.playWhenReady) break
                if (currentSource !== candidate) break
                val now = SystemClock.elapsedRealtime()
                val buffered = p.totalBufferedDuration
                val growth = buffered - lastBufferedMs
                // v2.0.8 item 1 — baseline DIMAJUKAN di setiap cek; HANYA
                // timer yang di-reset bila tumbuh ≥ ambang. Varian
                // "baseline maju hanya saat lolos" SALAH untuk Ep 1180:
                // buffer merambat 450 ms/cek — baseline lama membuat
                // growth kumulatif ≥ 500 ms di cek kedua, timer terus
                // di-reset, dan 30 dtk tidak pernah terpicu.
                lastBufferedMs = buffered
                if (StallTracker.isGrowth(growth, STALL_MIN_GROWTH_MS)) {
                    lastGrowthAt = now
                } else if (now - lastGrowthAt >= STALL_NO_GROWTH_MS) {
                    app().diagLogger.warn(
                        DiagLog.TAG_PLAYER,
                        "Ep $episode: kandidat ${candidate.kind} MACET — buffer " +
                            "tumbuh di bawah ambang ${STALL_MIN_GROWTH_MS}ms/cek " +
                            "selama ${STALL_NO_GROWTH_MS / 1000} dtk " +
                            "(total ${buffered}ms) — lanjut ke kandidat berikut"
                    )
                    tryNextCandidate()
                    break
                }
                if (now - startedAt >= STALL_MAX_MS) {
                    app().diagLogger.warn(
                        DiagLog.TAG_PLAYER,
                        "Ep $episode: kandidat ${candidate.kind} buffering " +
                            "${STALL_MAX_MS / 1000} dtk tanpa frame pertama — " +
                            "lanjut ke kandidat berikut"
                    )
                    tryNextCandidate()
                    break
                }
            }
        }
    }

    private fun stopStallWatch() {
        stallWatchJob?.cancel()
        stallWatchJob = null
    }

    /** Host URL saja (tanpa token/query) — untuk log yang aman dibaca. */
    private fun hostOf(url: String): String = try {
        java.net.URI(url).host ?: "?"
    } catch (e: Exception) {
        "?"
    }

    // ---- Loading awal ----

    /**
     * Loading awal: ProgressBar + teks "Memuat video…" + kontrol TIDAK
     * auto-hide sampai frame pertama (desain.md §14.3) — supaya tombol
     * kualitas tetap bisa diketuk selagi video dimuat.
     */
    private fun showInitialLoading() {
        binding.progressBar.visibility = View.VISIBLE
        binding.memuatVideoText.visibility = View.VISIBLE
        binding.exoPlayerView.controllerShowTimeoutMs = Int.MAX_VALUE
        binding.exoPlayerView.showController()
    }

    private fun hideInitialLoading() {
        binding.progressBar.visibility = View.GONE
        binding.memuatVideoText.visibility = View.GONE
        binding.exoPlayerView.controllerShowTimeoutMs = 5_000
    }

    // ---- Daftar episode (untuk next/prev) ----

    private fun nextEpisode(): Int? =
        episodeNumbers.firstOrNull { it > episode }

    private fun prevEpisode(): Int? =
        episodeNumbers.lastOrNull { it < episode }

    /** Slug episode berikut — dari episode_list, atau nextSlug resolve bila bersebelahan. */
    private fun nextEpisodeSlug(): String? {
        val next = nextEpisode() ?: return null
        return episodeSlugs[next]
            ?: if (next == episode + 1) sources?.nextSlug else null
    }

    private fun prevEpisodeSlug(): String? {
        val prev = prevEpisode() ?: return null
        return episodeSlugs[prev]
            ?: if (prev == episode - 1) sources?.prevSlug else null
    }

    private fun updateNextPrevButtons() {
        val playerView: PlayerView = binding.exoPlayerView
        val hasNext = nextEpisode() != null && nextEpisodeSlug() != null
        val hasPrev = prevEpisode() != null && prevEpisodeSlug() != null
        playerView.findViewById<View>(R.id.next_play_png)?.visibility =
            if (hasNext) View.VISIBLE else View.GONE
        playerView.findViewById<View>(R.id.next_play_overlay)?.visibility =
            if (hasNext) View.GONE else View.VISIBLE
        playerView.findViewById<View>(R.id.prev_play_png)?.visibility =
            if (hasPrev) View.VISIBLE else View.GONE
        playerView.findViewById<View>(R.id.prev_play_overlay)?.visibility =
            if (hasPrev) View.GONE else View.VISIBLE
    }

    // ---- Resolve + putar ----

    private fun loadEpisode(episodeNumber: Int, episodeSlug: String) {
        episode = episodeNumber
        this.episodeSlug = episodeSlug
        updateEpisodeLabel()
        // Reset state episode lama (SEBELUM resolve baru) + batalkan
        // resolve/putar/fetch Blogger episode lama (#2): coroutine yang
        // dibatalkan tidak lagi menulis sources/currentSource episode lama.
        loadJob?.cancel()
        playJob?.cancel()
        lazyLayerJob?.cancel()
        bloggerAttemptedFallback = false
        okRuAttemptedFallback = false
        samehadakuAttemptedFallback = false
        bloggerAttemptedQuality = false
        stopStallWatch()
        player?.stop()
        player?.clearMediaItems()
        sources = null
        currentSource = null
        candidateIndex = -1
        // v2.0.10 item 9: daftar "sudah dicoba" dikosongkan tiap episode.
        triedCandidateIndices.clear()
        // F-1: episode baru → kedua flag frame pertama reset.
        firstFrameTracker.onEpisodeStart()
        showInitialLoading()
        updateNextPrevButtons()
        updateDownloadUi()

        loadJob = lifecycleScope.launch {
            // v2.0.9 item 3: durasi resolve dicatat (termasuk fetch eager
            // lapis 2–3 bila lapis 1 kosong) — WARN bila > 10 dtk.
            val resolveStartedAtMs = SystemClock.elapsedRealtime()
            try {
                val resolved = app().playerRepository.resolve(episodeSlug)
                val resolveMs = SystemClock.elapsedRealtime() - resolveStartedAtMs
                app().diagLogger.info(
                    DiagLog.TAG_PLAYER,
                    // v2.0.10 item 3: slug episode dicatat — tanpa ini,
                    // resolve yang salah pilih slug (Episode 1 One Piece →
                    // Spesial) tidak bisa dibedakan dari resolve yang benar.
                    "Ep $episode: resolve selesai ${resolveMs}ms, " +
                        "slug=$episodeSlug, ${resolved.candidates.size} kandidat"
                )
                if (resolveMs > DiagLog.SLOW_RESOLVE_MS) {
                    app().diagLogger.warn(
                        DiagLog.TAG_PLAYER,
                        "Ep $episode: resolve lambat (${resolveMs}ms)"
                    )
                }
                sources = resolved
                episodeNumbers = resolved.episodeList
                    .map { it.number }.filter { it > 0 }.distinct().sorted()
                // v2.0.10 item 1: daftar episode Sanka bisa berisi DUA entri
                // bernomor sama (entri "Episode Spesial" di depan, episode
                // asli di belakang) — pilih slug per nomor lewat
                // pickEpisodeSlug (pola episode asli didahulukan), BUKAN
                // entri pertama mentah (itu yang membuat "Episode 1" One
                // Piece memutar konten Wano/Spesial di v2.0.9).
                episodeSlugs = resolved.episodeList
                    .groupBy({ it.number }, { it.slug })
                    .mapNotNull { (number, slugs) ->
                        SankaRepository.pickEpisodeSlug(
                            slugs.map { number to it }, number
                        )?.takeIf { it.isNotBlank() }?.let { number to it }
                    }.toMap()
                updateNextPrevButtons()
                val startIndex = pickStartCandidate(resolved)
                if (startIndex < 0) {
                    hideInitialLoading()
                    toast(getString(R.string.gagal_memutar, episode))
                    saveAndFinish()
                    return@launch
                }
                // v2.0.5: dialog mode DIHAPUS — langsung putar kandidat
                // awal (pref hdOnly dihormati pickStartCandidate).
                playCandidate(startIndex)
            } catch (e: RateLimitException) {
                // 429 di dalam player = toast rate limit + keluar (tanpa retry).
                app().diagLogger.error(DiagLog.TAG_PLAYER, "Ep $episode: 429 saat resolve")
                hideInitialLoading()
                toast(getString(R.string.terlalu_banyak_permintaan))
                saveAndFinish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // v2.0.10 item 2: tanpa simpleName (nama kelas 1 huruf R8).
                app().diagLogger.error(
                    DiagLog.TAG_PLAYER,
                    "Ep $episode: resolve gagal (${DiagError.describe(e)})"
                )
                hideInitialLoading()
                toast(getString(R.string.gagal_memutar, episode))
                saveAndFinish()
            }
        }
    }

    /**
     * Kandidat awal (v2.0.6, D4; v2.0.8 item 5): video LANGSUNG dulu.
     * - Pref "1" (default): direct 720p pertama; bila tidak ada, direct
     *   apa pun (360p).
     * - Pref "0": direct 360p pertama; bila tidak ada, direct 720p.
     * Tidak ada lagi kandidat embed — bila daftar kosong, -1 (pemanggil
     * gagal jujur: toast + keluar).
     */
    private fun pickStartCandidate(all: EpisodeSources): Int {
        val candidates = all.candidates
        if (candidates.isEmpty()) return -1
        val preferred = if (prefs.hdOnly == "1") {
            candidates.firstOrNull { it.isHd }
                ?: candidates.first()
        } else {
            candidates.firstOrNull { !it.isHd }
                ?: candidates.first()
        }
        return candidates.indexOf(preferred)
    }

    /** Mainkan kandidat ke-`index` (selalu Media3). */
    private fun playCandidate(index: Int, restorePosition: Long = 0L) {
        val all = sources ?: return
        val source = all.candidates.getOrNull(index) ?: return
        candidateIndex = index
        // v2.0.10 item 9: tandai sudah dicoba — tryNextCandidate hanya
        // memilih indeks yang belum dicoba (penghentian terjamin).
        triedCandidateIndices += index
        currentSource = source
        isHd = source.isHd
        candidateStartedAtMs = SystemClock.elapsedRealtime()
        stopStallWatch()
        // Log TANPA URL (token stream sensitif) — host saja cukup untuk
        // membedakan Pixeldrain / token / Blogger di Konsol.
        app().diagLogger.info(
            DiagLog.TAG_PLAYER,
            "Ep $episode: putar kandidat #$index kind=${source.kind} " +
                "label=${source.qualityLabel} host=${hostOf(source.url)}"
        )
        // Tinggi video kandidat lama tidak berlaku untuk kandidat baru —
        // label kembali ke label sumber sampai onVideoSizeChanged tiba.
        videoHeight = 0
        startMedia3(source, restorePosition)
        updateQualityUi()
        updateDownloadUi()
    }

    /**
     * Kandidat sekarang error → coba kandidat berikutnya; bila daftar
     * habis, coba SATU lapis malas yang belum dicoba (Blogger → ok.ru →
     * Samehadaku); bila tidak ada lapis tersisa → gagal jujur (toast +
     * keluar, seperti asli). TIDAK ADA LAGI dialog "Putar lewat
     * website?" (v2.0.8 item 5, D-web).
     *
     * v2.0.10 item 9: urutan coba lewat [CandidateTryOrder] — kandidat
     * yang belum dicoba, MAJU dulu lalu MUNDUR ke #0 (dulu hanya maju
     * dari kandidat awal: bila awalnya #1, #0 tidak pernah dicoba dan
     * lapis malas mengambil ulang URL yang sama dari #0).
     */
    private fun tryNextCandidate() {
        val all = sources ?: return
        val nextIndex = CandidateTryOrder.nextIndex(
            all.candidates.size, candidateIndex, triedCandidateIndices
        )
        if (nextIndex != null) {
            app().diagLogger.warn(
                DiagLog.TAG_PLAYER,
                "Ep $episode: kandidat #$candidateIndex (${currentSource?.kind}) " +
                    "habis — coba #$nextIndex"
            )
            val restore = player?.currentPosition ?: 0L
            playCandidate(nextIndex, restore)
            return
        }
        if (tryLazyLayer()) return
        app().diagLogger.error(
            DiagLog.TAG_PLAYER,
            "Ep $episode: semua kandidat habis — gagal"
        )
        toast(getString(R.string.gagal_memutar, episode))
        saveAndFinish()
    }

    /**
     * Coba SATU lapis malas berikutnya (Blogger → ok.ru → Samehadaku):
     * true = fetch diluncurkan (kelanjutan ditangani coroutine-nya);
     * false = tidak ada lapis tersisa (pemanggil gagal jujur: toast +
     * keluar). Lapis yang tidak punya bahan (tanpa URL iframe/embed,
     * tanpa judul serial) ditandai sudah dicoba lalu DILEWATI — rantai
     * tidak berhenti di lapis yang memang tidak bisa dicoba.
     */
    private fun tryLazyLayer(): Boolean {
        val all = sources ?: return false
        // Lapis 1: Blogger (Animekompi).
        if (!bloggerAttemptedFallback) {
            bloggerAttemptedFallback = true
            val iframeUrl = all.bloggerIframeUrl
            if (!iframeUrl.isNullOrBlank()) {
                app().diagLogger.warn(
                    DiagLog.TAG_PLAYER,
                    "Ep $episode: kandidat habis — lapis malas Blogger"
                )
                launchLazyLayer("Blogger") {
                    app().playerRepository.fetchBloggerCandidates(iframeUrl, episode)
                }
                return true
            }
        }
        // Lapis 2: ok.ru.
        if (!okRuAttemptedFallback) {
            okRuAttemptedFallback = true
            val okRuUrl = all.okRuEmbedUrl
            if (!okRuUrl.isNullOrBlank()) {
                app().diagLogger.warn(
                    DiagLog.TAG_PLAYER,
                    "Ep $episode: kandidat habis — lapis malas ok.ru"
                )
                launchLazyLayer("ok.ru") {
                    app().playerRepository.fetchOkRuCandidates(okRuUrl, episode)
                }
                return true
            }
        }
        // Lapis 3: Samehadaku.
        if (!samehadakuAttemptedFallback) {
            samehadakuAttemptedFallback = true
            val title = all.animeTitle
            if (title.isNotBlank()) {
                val episodeNumber = all.episodeNumber
                app().diagLogger.warn(
                    DiagLog.TAG_PLAYER,
                    "Ep $episode: kandidat habis — lapis malas Samehadaku"
                )
                launchLazyLayer("Samehadaku") {
                    app().playerRepository.fetchSamehadakuCandidates(
                        title, episodeNumber
                    )
                }
                return true
            }
        }
        return false
    }

    /**
     * Luncurkan fetch satu lapis malas; tambahkan hasilnya di AKHIR
     * daftar lalu putar kandidat pertama lapis baru. Bila lapis ini
     * tidak menghasilkan kandidat, coba lapis berikutnya (tryNextCandidate
     * lagi — flag lapis ini sudah true, jadi lanjut ke lapis berikut;
     * bila tidak ada lapis tersisa → gagal jujur: toast + keluar).
     * 429 (RateLimitException) TIDAK ditelan — toast rate-limit + keluar
     * (L-2), sama seperti jalur resolve() awal.
     */
    private fun launchLazyLayer(
        layerName: String,
        fetch: suspend () -> List<PlayableSource>
    ) {
        val snapshot = sources ?: return
        showInitialLoading()
        lazyLayerJob?.cancel()
        lazyLayerJob = lifecycleScope.launch {
            val found = try {
                fetch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RateLimitException) {
                // L-2: 429 di lapis mana pun = toast rate-limit + keluar
                // (tanpa retry).
                app().diagLogger.error(
                    DiagLog.TAG_PLAYER,
                    "Ep $episode: 429 di lapis $layerName"
                )
                hideInitialLoading()
                toast(getString(R.string.terlalu_banyak_permintaan))
                saveAndFinish()
                return@launch
            } catch (e: Exception) {
                emptyList()
            }
            app().diagLogger.info(
                DiagLog.TAG_PLAYER,
                "Ep $episode: $layerName lazy → ${found.size} kandidat"
            )
            // Episode sudah berganti selagi fetch berjalan — abaikan
            // (jangan menulis kandidat ke sources episode baru).
            if (sources !== snapshot) return@launch
            val current = sources ?: return@launch
            // L-14: lapis ini mungkin SUDAH di-fetch secara eager saat
            // resolve() (flag fallback hanya di-set di tryLazyLayer) —
            // buang kandidat yang URL-nya sudah ada supaya tidak masuk
            // dua kali lalu diputar ulang.
            val fresh = found.filter { c ->
                current.candidates.none { it.url == c.url }
            }
            if (fresh.isNotEmpty()) {
                val updated = current.candidates.toMutableList()
                updated.addAll(fresh)
                sources = current.copy(candidates = updated)
                val restore = player?.currentPosition ?: 0L
                playCandidate(updated.size - fresh.size, restore)
            } else {
                tryNextCandidate()
            }
        }
    }

    private fun startMedia3(source: PlayableSource, restorePosition: Long) {
        val exoPlayer = player ?: return
        // F-1: kandidat baru → HANYA flag per-kandidat yang reset; flag
        // per-episode TETAP (sudah menonton = tetap boleh ditulis).
        firstFrameTracker.onCandidateStart()
        showInitialLoading()
        // v2.0.8 item 2: Referer per-kind (headersFor) — dipasang di
        // factory SEBELUM media item dipasang; playback strictly
        // sequential (satu kandidat aktif) jadi aman dipakai ulang.
        // ok.ru → Referer https://ok.ru/; yang lain → tanpa Referer.
        httpDataSourceFactory.setDefaultRequestProperties(
            PlayerRepository.headersFor(source.kind)
        )
        // Tangkap nomor episode + sumber SAAT INI (sinkron): coroutine di
        // bawah berjalan terpisah — bila episode berganti selagi baca DB,
        // resume harus memakai episode sumber ini (#2), dan media item
        // lama tidak boleh menimpa media item episode baru.
        val episodeForResume = episode
        val sourceForThisPlay = source
        playJob?.cancel()
        playJob = lifecycleScope.launch {
            val resumePosition = if (restorePosition > 0) {
                restorePosition
            } else {
                resumePositionFor(episodeForResume)
            }
            // Sumber sudah berganti (kualitas diketuk / episode pindah)
            // selagi baca DB — jangan pasang media item basi ini.
            if (currentSource !== sourceForThisPlay) return@launch
            // Semua kandidat Media3 adalah file MP4 (Pixeldrain / token /
            // Blogger videoplayback) — mime type eksplisit karena URL-nya
            // tanpa ekstensi (.com/api/file/…, /stream/<token>, videoplayback).
            val item = MediaItem.Builder()
                .setUri(source.url)
                .setMimeType(MimeTypes.VIDEO_MP4)
                .build()
            exoPlayer.setMediaItem(item)
            if (resumePosition > 0) {
                // Tandai seek programatis — callback SEEK-nya harus
                // DIABAIKAN persist-resume (lihat onPositionDiscontinuity).
                lastProgrammaticSeekTargetMs = resumePosition
                exoPlayer.seekTo(resumePosition)
            }
            exoPlayer.prepare()
            exoPlayer.play()
        }
    }

    /**
     * Tombol Unduh di kontrol player (v2.0.5, D1):
     * unduh file .mp4 langsung (Pixeldrain) TANPA menutup player —
     * tonton lanjut selagi mengunduh. Tanpa downloadUrl, tombol
     * disembunyikan updateDownloadUi() (bukan toast gagal).
     */
    private fun onDownloadClicked() {
        val url = sources?.downloadUrl ?: return
        downloadSource(url, finishAfter = false)
    }

    /** Tombol Unduh tampil hanya bila ada file .mp4 langsung (Pixeldrain). */
    private fun updateDownloadUi() {
        val hasDownload = sources?.downloadUrl != null
        binding.exoPlayerView.findViewById<View>(R.id.downloadBtn)?.visibility =
            if (hasDownload) View.VISIBLE else View.GONE
    }

    private fun downloadSource(url: String, finishAfter: Boolean) {
        try {
            // Sanitasi judul: ':' '/' '?' dll. membuat tujuan DownloadManager
            // tidak valid ("Re:Zero", "Fate/stay night"). Tujuan mengikuti
            // PRD §2.1: Downloads/AnimLovers/<Judul>-Ep-<n>.mp4
            // (v2: file video .mp4 LANGSUNG dari Pixeldrain — bukan playlist
            // .m3u8 seperti v1).
            val safeTitle = animeTitle.replace(Regex("[^a-zA-Z0-9 _.-]"), "_")
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("$animeTitle - ${getString(R.string.episode_label, episode)}")
                .setDescription(getString(R.string.unduh))
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "AnimLovers/$safeTitle-Ep-$episode.mp4"
                )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                request.setAllowedNetworkTypes(
                    DownloadManager.Request.NETWORK_WIFI or
                        DownloadManager.Request.NETWORK_MOBILE
                )
            }
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            toast(getString(R.string.unduhan_dimulai))
        } catch (e: Exception) {
            toast(getString(R.string.unduhan_gagal))
        }
        // v2.0.5: unduh dari tombol player TIDAK menutup player
        // (finishAfter=false); pemanggil lama (dialog mode) sudah dihapus.
        if (finishAfter) saveAndFinish()
    }

    private suspend fun resumePositionFor(episodeNumber: Int): Long {
        return try {
            val item = app().sankaRepository.getHistoryForTitle(animeSlug)
            if (item != null && item.idepisode == episodeNumber.toString()) item.idsecond else 0L
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            0L
        }
    }

    private fun onPlaybackEnded() {
        // Aturan asli: film → keluar (walau teruskan "true"); episode
        // terakhir / Putar Sekali → keluar; sisanya → episode berikut.
        // (`lanjut` asli tidak punya padanan data di Sanka — gerbang v2
        // adalah episodeList + teruskan.)
        if (isMovie) {
            saveAndFinish()
            return
        }
        if (prefs.teruskan == "true") {
            val next = nextEpisode()
            val slug = nextEpisodeSlug()
            if (next != null && slug != null) {
                loadEpisode(next, slug)
                return
            }
        }
        // Mode Putar Sekali / film / episode terakhir → keluar player
        // (PRD §6.4). Tanpa ini player diam di frame akhir.
        saveAndFinish()
    }

    // ---- Gerbang WiFi ----

    private fun showWifiGateDialog() {
        AlertDialog.Builder(this, alertTheme())
            .setTitle(R.string.data_seluler_judul)
            .setMessage(R.string.data_seluler_isi)
            .setPositiveButton(R.string.lanjut) { _, _ ->
                loadEpisode(episode, episodeSlug)
            }
            .setNegativeButton(R.string.tidak) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun registerWifiCallback() {
        if (prefs.wifiSwitch != "1") return
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (!isOnWifi() && !wifiDialogShown && player?.isPlaying == true) {
                    app().diagLogger.warn(
                        DiagLog.TAG_PLAYER,
                        "WiFi terputus saat memutar Ep $episode"
                    )
                    wifiDialogShown = true
                    runOnUiThread { showWifiLostDialog() }
                }
            }
        }
        networkCallback = callback
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
    }

    private fun showWifiLostDialog() {
        AlertDialog.Builder(this, alertTheme())
            .setTitle(R.string.wifi_terputus_judul)
            .setMessage(R.string.wifi_terputus_isi)
            .setPositiveButton(R.string.lanjut) { _, _ -> wifiDialogShown = false }
            .setNegativeButton(R.string.tidak) { _, _ ->
                player?.pause()
                wifiDialogShown = false
            }
            .setCancelable(false)
            .show()
    }

    // ---- Siklus hidup ----

    override fun onPause() {
        super.onPause()
        wasPlaying = player?.isPlaying == true
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        // v2.0.11: saklar MATI di tengah jalan — jangan lanjutkan
        // pemutaran, kembali ke error state Beranda.
        if (KillSwitch.isDead()) {
            MainActivity.startDead(this)
            finish()
            return
        }
        if (wasPlaying) {
            player?.play()
        }
        // Cek ulang berkala (item 6 + delta F-1): bila proses ini belum
        // pernah cek ATAU cek terakhir sudah lebih dari 15 menit, cek
        // lagi di background — bila hasilnya MATI, pemutaran dihentikan.
        if (KillSwitchLogic.needsRecheck(
                KillSwitch.hasCheckedInProcess(),
                prefs.killLastCheckMs,
                System.currentTimeMillis(),
                KillSwitch.RECHECK_INTERVAL_MS
            )
        ) {
            lifecycleScope.launch {
                val decision = if (KillSwitch.hasCheckedInProcess()) {
                    KillSwitch.check(app())
                } else {
                    KillSwitch.ensureCheckedInProcess(app())
                }
                if (!decision.allowed) {
                    MainActivity.startDead(this@VideoPlayActivity)
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        networkCallback?.let { callback ->
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
        saveResume()
        stopStallWatch()
        player?.release()
        player = null
        super.onDestroy()
    }

    /** Simpan posisi − 30 detik (aturan asli), pulihkan orientasi, tutup. */
    private fun saveAndFinish() {
        saveResume()
        restoreOrientation()
        finish()
    }

    /**
     * Tulis posisi tonton SEKALI saat keluar (dijaga resumeSaved —
     * onDestroy + saveAndFinish bisa terpanggil berurutan).
     */
    private fun saveResume() {
        if (resumeSaved) return
        resumeSaved = true
        persistResume()
    }

    /**
     * Tulis posisi tonton SEKARANG (−30 dtk) — tanpa penjaga sekali-jalan,
     * dipakai simpan-saat-seek (aturan asli) + ganti kualitas.
     */
    private fun persistResume() {
        // Hanya simpan bila episode sudah ter-resolve (pernah mulai diputar).
        if (sources == null) return
        // v2.0.10 item 10: frame pertama episode ini BELUM tercapai =
        // video tidak pernah jalan (film/episode 0-kandidat langsung
        // gagal) — jangan tulis riwayat/resume. Dulu jalur ini menulis
        // posisi 0 untuk film yang gagal, sehingga film gagal muncul di
        // Riwayat + "Lanjutkan" (video v2.0.9: Tamako/Kimi no Na wa/
        // Totoro). F-1: guard memakai flag PER-EPISODE — keluar saat
        // buffering retry SETELAH menonton tetap menulis (flag itu tidak
        // di-reset saat pindah kandidat).
        if (!firstFrameTracker.hasEpisodeReachedFirstFrame()) return
        val exoPlayer = player ?: return
        val position = (exoPlayer.currentPosition - Constants.RESUME_REWIND_MS)
            .coerceAtLeast(0L)
        // appScope (bukan lifecycleScope): tulis harus selesai walau
        // Activity sudah dihancurkan.
        app().appScope.launch {
            runCatching {
                app().sankaRepository.saveResume(
                    slug = animeSlug,
                    title = animeTitle,
                    poster = animePoster,
                    episode = episode,
                    positionMs = position
                )
            }
        }
    }

    private fun restoreOrientation() {
        val awallayar = prefs.awallayar.toIntOrNull()
        if (awallayar != null) {
            requestedOrientation = awallayar
        }
        prefs.awallayar = ""
    }

    /**
     * Kebijakan retry load untuk video langsung (v2.0.6 #1b): jaringan
     * lambat BUKAN kegagalan — coba ulang kandidat direct yang sama lebih
     * lama dari default Media3 (3× → 8×, backoff 0/1/2/3/4/5/5/5 dtk
     * warisan DefaultLoadErrorHandlingPolicy). Error fatal (parse, file
     * tidak ada, posisi di luar jangkauan) tetap langsung menyerah tanpa
     * retry (warisan). v2.0.8 item 3: HTTP 451/404/410 fail-fast via
     * override getRetryDelayMsFor (TIME_UNSET) — tanpa retry; error
     * transient (IO/5xx/408/429) tetap DI-retry 8× — setelah 8× gagal,
     * error naik ke onPlayerError lalu tryNextCandidate maju ke kandidat
     * berikut; kandidat direct yang benar-benar mati tetap dihabiskan
     * dulu sebelum gagal jujur (toast + keluar).
     */
    private class DirectLoadErrorHandlingPolicy :
        DefaultLoadErrorHandlingPolicy(MIN_DIRECT_LOAD_RETRIES) {
        companion object {
            /** Retry load internal per kandidat direct (default Media3: 3). */
            const val MIN_DIRECT_LOAD_RETRIES = 8
        }

        /**
         * Fail-fast 4xx permanen (v2.0.8 item 3): HTTP 400..499 KECUALI
         * 408/429 (file di-takedown / URL mati — retry 8× hanya buang
         * waktu ~20 dtk) → C.TIME_UNSET (tanpa retry, langsung fatal →
         * onPlayerError → tryNextCandidate). 408 (timeout) / 429 (rate
         * limit) / 5xx / IO = transient → super (jeda default
         * 0/1/2/3/4/5/5/5 dtk, tetap 8×). 429 CDN ≠ 429 API Sanka
         * (kategori berbeda: yang satu pembatas host file, yang satu
         * pembatas API — L-2 mengatur API; di sini 429 tetap di-retry
         * karena pembatas CDN biasanya sesaat). Keputusan murni di
         * HttpFailFast (PlayerRepository.kt) supaya harness bisa
         * mengujinya tanpa Media3. Bentuk exception terverifikasi dari
         * sources jar Media3 1.2.1: InvalidResponseCodeException
         * (final, field responseCode) tiba LANGSUNG sebagai
         * loadErrorInfo.exception (Loader.java catch IOException →
         * MSG_IO_EXCEPTION, tanpa pembungkus) — TIDAK ada unwrap.
         */
        override fun getRetryDelayMsFor(
            loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
        ): Long {
            val code = (loadErrorInfo.exception as?
                HttpDataSource.InvalidResponseCodeException)?.responseCode
            return if (code != null && HttpFailFast.isPermanent(code)) {
                C.TIME_UNSET
            } else {
                super.getRetryDelayMsFor(loadErrorInfo)
            }
        }
    }
}
