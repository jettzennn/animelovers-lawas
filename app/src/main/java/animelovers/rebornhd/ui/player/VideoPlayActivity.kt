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
import android.util.Log
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import animelovers.rebornhd.R
import animelovers.rebornhd.app
import animelovers.rebornhd.data.api.RateLimitException
import animelovers.rebornhd.data.model.PlayableSource
import animelovers.rebornhd.data.model.rewritePosterHost
import animelovers.rebornhd.data.repo.EpisodeSources
import animelovers.rebornhd.databinding.ActivityVideoBinding
import animelovers.rebornhd.ui.BaseActivity
import animelovers.rebornhd.util.Constants
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
 *   download_new) + bilah atas mode WebView; unduh TIDAK lagi menutup
 *   player (tonton lanjut sambil mengunduh).
 *
 * v2 (desain.md §14):
 * - TIDAK ADA subtitle track (hardsub menempel di video) — tombol Subtitle
 *   dihapus; TIDAK ADA polling subtitle / backend.
 * - Sumber = file MP4 langsung (bukan HLS): Pixeldrain 720p → stream token
 *   Animekompi 720p → Blogger ekstrak itag 22 (720p) → 18 (360p) → WebView
 *   embed (fallback terakhir). Fallback OTOMATIS antar kandidat DIRECT
 *   bila yang sedang diputar error; ke WebView (embed) TIDAK otomatis —
 *   ditanya dulu lewat dialog (v2.0.6 #1b): pindah ke website hanya lewat
 *   ketukan eksplisit pengguna, tidak pernah karena buffering lambat.
 * - v2.0.7 item 14–16 (fallback lintas-sumber): rantai diperpanjang —
 *   setelah Blogger Animekompi, coba ok.ru (ekstrak tier hd → sd dari
 *   URL embed Animekompi), lalu Samehadaku (cari episode yang sama →
 *   Blogger ekstrak itag 22 → 18), baru dialog WebView. Lapis 2–3 MALAS
 *   (tidak di-fetch bila lapis 1 sukses; diambil saat resolve hanya bila
 *   lapis 1 kosong, atau belakangan oleh tryLazyLayer bila semua
 *   kandidat sebelumnya gagal). Pengawas macet-buffering (#8) berlaku
 *   per kandidat di semua lapis, ambang tidak diubah.
 * - v2.0.6 (D4): player WAJIB mulai dari video LANGSUNG di kontrol
 *   aplikasi sendiri — default pref `hdOnly` kini "1" (720p direct dulu);
 *   embed WebView HANYA bila tidak ada kandidat direct sama sekali.
 *   `resolution` mengalihkan KUALITAS di player yang sama (direct 720p ↔
 *   direct 360p), BUKAN mengganti mesin (Media3 ↔ WebView) — seperti
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
 *   tryNextCandidate jatuh ke embed (inilah flip yang dilaporkan Faygo:
 *   kontrol aplikasi dulu, lalu pindah ke player website). Timeout
 *   dinaikkan ke 15 dtk (connect) / 30 dtk (read, sama seperti timeout
 *   transport Sanka) + retry load internal 8× (default 3×) supaya
 *   kandidat direct yang sama dicoba ulang dulu sebelum dianggap gagal.
 *   Bila semua direct benar-benar habis, fallback ke WebView DITANYAKAN
 *   lewat dialog — tidak pernah otomatis.
 * - Loading awal = ProgressBar + teks "Memuat video…" dan kontrol TIDAK
 *   auto-hide sampai frame pertama tampil.
 * - Mode WebView: bilah atas minimal (FAB kembali + judul + label kualitas
 *   sumber embed + tombol "720p" bila ada kandidat 720p — jalan pindah ke
 *   direct). Next/Prev + auto-play TIDAK berlaku di WebView (aplikasi tidak
 *   bisa mendeteksi akhir video — auto-next DINONAKTIFKAN di embed, jujur:
 *   tidak ada event ENDED dari halaman embed; polling durasi per-embed
 *   rapuh dan tidak dipakai). Posisi resume saat pindah Media3↔WebView =
 *   best-effort (posisi saat masuk WebView — posisi di dalam WebView tidak
 *   bisa dibaca).
 * - Resume disimpan −30 detik saat seek / ganti kualitas / keluar (aturan
 *   asli — dulu hanya saat keluar).
 * - "Unduh" = file .mp4 langsung dari Pixeldrain (BUKAN .m3u8 seperti v1).
 *   Bila episode tanpa Pixeldrain, tombol Unduh tidak ditampilkan.
 *   v2.0.5: unduh dari tombol di kontrol player — player TIDAK ditutup.
 * - 429 di dalam player = toast rate limit + keluar (tanpa retry).
 */
@OptIn(UnstableApi::class)
class VideoPlayActivity : BaseActivity() {

    override val applyAppTheme: Boolean = false // FullscreenTheme dari manifest.

    private lateinit var binding: ActivityVideoBinding
    private var player: ExoPlayer? = null

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

    /** Error WebView sudah ditangani untuk load embed ini (sekali saja). */
    private var webViewErrorShown: Boolean = false

    /** Dialog tanya-fallback-ke-WebView sudah tampil untuk episode ini (sekali saja). */
    private var webViewFallbackDialogShown: Boolean = false

    /** Host embed yang sedang dimuat di WebView (M1) — satu-satunya host yang boleh dinavigasi. */
    private var webViewAllowedHost: String? = null

    private var isHd: Boolean = false
    private var isFill: Boolean = false
    private var wasPlaying: Boolean = false
    private var resumeSaved: Boolean = false
    private var wifiDialogShown: Boolean = false

    /** Mode WebView (embed) sedang tampil — player Media3 dijeda. */
    private var isWebViewMode: Boolean = false

    /** Posisi Media3 saat masuk mode WebView (best-effort untuk resume). */
    private var webViewEntryPositionMs: Long = 0L

    /** Frame pertama sudah tampil (loading awal selesai). */
    private var hasFirstFrame: Boolean = false

    /** Fullscreen di dalam WebView (video embed minta fullscreen). */
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        /** Tag logcat rantai putar (v2.0.7 #8) — kandidat + transisi state + stall. */
        private const val TAG_PLAYER = "AnimLoversPlayer"

        /** Pengawas macet-buffering (v2.0.7 #8): cek tiap 10 dtk. */
        private const val STALL_CHECK_INTERVAL_MS = 10_000L

        /** Buffer tidak bertambah selama ini → kandidat dianggap macet. */
        private const val STALL_NO_GROWTH_MS = 30_000L

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
        setupWebView()
        wireControls()
        registerWifiCallback()

        // Gerbang WiFi (pref wifi_switch "1" + tidak di WiFi).
        if (prefs.wifiSwitch == "1" && !isOnWifi()) {
            showWifiGateDialog()
        } else {
            loadEpisode(episode, episodeSlug)
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
        // lalu tryNextCandidate jatuh ke embed (flip yang dilaporkan
        // Faygo). Naikkan ke 15 dtk (connect) / 30 dtk (read, sama seperti
        // timeout transport Sanka) + retry load internal 8× (default 3×)
        // supaya kandidat direct yang sama dicoba ulang dulu sebelum
        // dianggap gagal.
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
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
                        if (!hasFirstFrame) {
                            hasFirstFrame = true
                            hideInitialLoading()
                            Log.d(
                                TAG_PLAYER,
                                "Ep $episode: frame pertama setelah " +
                                    "${SystemClock.elapsedRealtime() - candidateStartedAtMs}ms " +
                                    "(kandidat ${currentSource?.kind})"
                            )
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
                        if (exoPlayer.playWhenReady && !isWebViewMode) {
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
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean) {
                updatePlayPauseButton()
                if (playWhenReady) {
                    val p = exoPlayer
                    if (p.playbackState == Player.STATE_BUFFERING && !isWebViewMode) {
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
                if (isWebViewMode) return
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
                if (source == null || source.isWebView || currentUri != source.url) return
                // Log TANPA URL (token stream sensitif) — hanya jenis error
                // + penyebabnya (mis. InvalidResponseCodeException = HTTP
                // 451/404: file di-takedown. KOREKSI v2.0.7 (O-1 Cari Bug):
                // HTTP 451 DI-retry 8× oleh DefaultLoadErrorHandlingPolicy
                // Media3 (jeda 0,1,2,3,4,5,5 dtk, maks ~20 dtk/kandidat) —
                // "gagal cepat / tanpa retry" untuk 451 adalah SALAH.
                // onPlayerError ini baru tiba SETELAH retry itu habis.
                Log.w(
                    TAG_PLAYER,
                    "Ep $episode: error kandidat ${source.kind} setelah " +
                        "${SystemClock.elapsedRealtime() - candidateStartedAtMs}ms " +
                        "(${error.errorCodeName}, ${error.cause?.javaClass?.simpleName})"
                )
                stopStallWatch()
                tryNextCandidate()
            }
        })
        // Keadaan awal tombol play/pause (v2.0.7 #6): sebelum play(),
        // playWhenReady=false → hanya ▶ yang tampil.
        updatePlayPauseButton()
    }

    // ---- Setup WebView (mode embed) ----

    private fun setupWebView() {
        val webView = binding.webView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        // Embed butuh autoplay tanpa gestur (video dimulai oleh pengguna
        // lewat tombol play di halaman embed itu sendiri).
        webView.settings.mediaPlaybackRequiresUserGesture = false
        // L3 — embed remote tidak butuh akses file: matikan file:// +
        // content:// (default keduanya true).
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.webViewClient = object : WebViewClient() {
            // EMBED selalu kandidat terakhir (#3): bila halaman embed gagal
            // dimuat, tidak ada kandidat berikut untuk dicoba — toast jujur
            // + keluar (jangan diam dengan layar WebView kosong).
            // Hanya frame utama yang dihitung — error sub-resource (gambar,
            // iklan di halaman embed) bukan kegagalan putar.
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) onWebViewError()
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                // API < 23: callback ini hanya dipanggil untuk frame utama.
                onWebViewError()
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                if (request.isForMainFrame) onWebViewError()
            }

            // M1 — navigasi WebView dikunci ke host embed asal: halaman
            // embed (atau iklan/redirect di dalamnya) TIDAK BOLEH membawa
            // pengguna ke situs lain tanpa bilah URL (phishing). Hanya
            // navigasi frame utama yang dicek — sub-resource (gambar/iklan)
            // bukan navigasi. Yang diizinkan = host URL embed yang dimuat
            // (+ subdomainnya); sisanya diblokir (return true, tanpa memuat,
            // tanpa toast — redirect iklan bisa berulang).
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                if (!request.isForMainFrame) return false
                return !isAllowedEmbedHost(request.url.host)
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                // API < 24: callback ini hanya dipanggil untuk frame utama.
                return !isAllowedEmbedHost(Uri.parse(url).host)
            }

            private fun isAllowedEmbedHost(host: String?): Boolean {
                val allowed = webViewAllowedHost ?: return false
                val h = host?.lowercase() ?: return false
                return h == allowed || h.endsWith(".$allowed")
            }

            private fun onWebViewError() {
                if (!isWebViewMode || webViewErrorShown) return
                webViewErrorShown = true
                toast(getString(R.string.gagal_memutar, episode))
                saveAndFinish()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                // Video di dalam embed minta fullscreen.
                if (fullscreenView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                fullscreenView = view
                fullscreenCallback = callback
                binding.videoLayout.addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                binding.webViewContainer.visibility = View.GONE
            }

            override fun onHideCustomView() {
                fullscreenView?.let { binding.videoLayout.removeView(it) }
                fullscreenView = null
                fullscreenCallback?.onCustomViewHidden()
                fullscreenCallback = null
                if (isWebViewMode) binding.webViewContainer.visibility = View.VISIBLE
            }
        }

        binding.webViewBack.setOnClickListener { saveAndFinish() }
        // Unduh di bilah atas mode WebView (v2.0.5, D1) — tombol yang sama
        // seperti di kontrol Media3; visibilitas diatur updateDownloadUi().
        binding.webViewDownload.setOnClickListener { onDownloadClicked() }
        binding.webViewQuality720.setOnClickListener {
            val all = sources ?: return@setOnClickListener
            val hdIndex = all.candidates.indexOfFirst { it.isHd && !it.isWebView }
            if (hdIndex >= 0) {
                isHd = true
                prefs.hdOnly = "1"
                playCandidate(hdIndex, webViewEntryPositionMs)
            }
        }
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

        // Next / prev (tidak berlaku di mode WebView — tombolnya ada di
        // kontrol Media3 saja; di WebView pengguna keluar lewat FAB).
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
     * yang mengganti SD↔HD di ExoPlayer yang sama. TIDAK PERNAH pindah ke
     * WebView dari tombol ini (perilaku lama: 720p direct ↔ embed — itu
     * yang membuat Faygo melihat kontrol player website).
     *
     * Bila tier 360p belum ada di daftar (ekstrak Blogger lazy belum
     * jalan), ketuk tombol memicu fetch Blogger sekali — bila hasilnya
     * tetap tanpa 360p, tombol jadi `_off` (updateQualityUi) dan video
     * tetap jalan di 720p.
     */
    private fun onQualityToggle() {
        val all = sources ?: return
        val current = currentSource ?: return
        if (isWebViewMode) return // Di embed tidak ada toggle kualitas.
        val hasHdDirect = all.candidates.any { it.isHd && !it.isWebView }
        val hasSdDirect = all.candidates.any { !it.isHd && !it.isWebView }
        if (!hasHdDirect && !hasSdDirect) return
        val restore = player?.currentPosition ?: 0L
        if (current.isHd) {
            // 720p → 360p (direct).
            val sdIndex = all.candidates.indexOfFirst { !it.isHd && !it.isWebView }
            if (sdIndex >= 0) {
                persistResume()
                isHd = false
                prefs.hdOnly = "0"
                playCandidate(sdIndex, restore)
                return
            }
            // Tier 360p belum ada — coba ekstrak Blogger sekali (lazy,
            // pola yang sama seperti tryNextCandidate sebelum jatuh ke
            // embed): bila ada itag 18, sisipkan lalu putar.
            fetchBloggerForQualitySwitch(restore)
        } else {
            // 360p → 720p (direct).
            val hdIndex = all.candidates.indexOfFirst { it.isHd && !it.isWebView }
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
     * sisipkan kandidat Blogger hasil ekstrak sebelum embed, lalu putar
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
                app().playerRepository.fetchBloggerCandidates(iframeUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            // Episode sudah berganti selagi fetch berjalan — abaikan.
            if (sources !== snapshot) return@launch
            val current = sources ?: return@launch
            val embedIndex = current.candidates.indexOfFirst { it.isWebView }
            // L-14: buang kandidat yang URL-nya sudah ada (Blogger
            // mungkin sudah di-fetch eager saat resolve() — tanpa cek
            // ini itag 22 masuk dua kali lalu diputar ulang).
            val fresh = blogger.filter { c ->
                current.candidates.none { it.url == c.url }
            }
            if (fresh.isNotEmpty() && embedIndex >= 0) {
                val updated = current.candidates.toMutableList()
                updated.addAll(embedIndex, fresh)
                sources = current.copy(candidates = updated)
            }
            val sdIndex = sources?.candidates
                ?.indexOfFirst { !it.isHd && !it.isWebView } ?: -1
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
        val hasHd = all?.candidates?.any { it.isHd && !it.isWebView } == true
        // Tier SD = direct 360p (Blogger itag 18) — BUKAN embed WebView.
        val hasSd = all?.candidates?.any { !it.isHd && !it.isWebView } == true
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
     * Hanya mode Media3 — di mode WebView kontrol aplikasi tidak tampil.
     */
    private fun updateBufferingUi(buffering: Boolean) {
        if (isWebViewMode) return
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
     *   blok IP). KOREKSI v2.0.7 (O-1 Cari Bug): HTTP 451 DI-retry 8×
     *   oleh DefaultLoadErrorHandlingPolicy Media3 (jeda 0,1,2,3,4,5,5
     *   dtk, maks ~20 dtk/kandidat) — "gagal cepat / tanpa retry" untuk
     *   451 adalah SALAH; onPlayerError baru tiba setelah retry itu
     *   habis, lalu rantai maju ke Blogger itag 22/18 (terbukti ada).
     * - itag 22 (720p) di koneksi lambat bisa terbuka TANPA error tapi
     *   tidak pernah cukup buffer untuk frame pertama — Media3 diam di
     *   BUFFERING, tanpa error, tanpa retry, tanpa dialog. Itulah spinner
     *   tanpa ujung.
     * Pengawas: selama BUFFERING, cek tiap 10 dtk totalBufferedDurationMs.
     * Bila buffer TIDAK bertambah 30 dtk (jaringan macet total) ATAU
     * buffering satu kandidat sudah 120 dtk (merambat tapi tak kunjung
     * cukup — 720p di 5,5 KB/dtk butuh ~86 dtk untuk 2,5 dtk buffer),
     * anggap kandidat macet → tryNextCandidate() (ke kandidat berikut —
     * 360p / ok.ru / Samehadaku, lalu dialog).
     * Batas ini per kandidat, bukan per episode — kandidat berikut (360p,
     * ~1/4 byte 720p) punya peluang nyata untuk jalan di koneksi lambat.
     * Pengawas berlaku per kandidat di SEMUA lapis rantai (v2.0.7 item 17:
     * ambang TIDAK diubah untuk lapis ok.ru/Samehadaku).
     */
    private fun startStallWatch() {
        val exoPlayer = player ?: return
        val candidate = currentSource ?: return
        if (isWebViewMode) return
        stallWatchJob?.cancel()
        val startedAt = SystemClock.elapsedRealtime()
        var lastBufferedMs = exoPlayer.totalBufferedDurationMs
        var lastGrowthAt = startedAt
        stallWatchJob = lifecycleScope.launch {
            while (isActive) {
                delay(STALL_CHECK_INTERVAL_MS)
                val p = player ?: break
                if (p.playbackState != Player.STATE_BUFFERING || !p.playWhenReady) break
                if (currentSource !== candidate) break
                val now = SystemClock.elapsedRealtime()
                val buffered = p.totalBufferedDurationMs
                if (buffered > lastBufferedMs) {
                    lastBufferedMs = buffered
                    lastGrowthAt = now
                } else if (now - lastGrowthAt >= STALL_NO_GROWTH_MS) {
                    Log.w(
                        TAG_PLAYER,
                        "Ep $episode: kandidat ${candidate.kind} MACET — buffer " +
                            "tidak bertambah ${STALL_NO_GROWTH_MS / 1000} dtk " +
                            "(total ${buffered}ms) — lanjut ke kandidat berikut"
                    )
                    tryNextCandidate()
                    break
                }
                if (now - startedAt >= STALL_MAX_MS) {
                    Log.w(
                        TAG_PLAYER,
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
        webViewFallbackDialogShown = false
        exitWebViewMode()
        player?.stop()
        player?.clearMediaItems()
        sources = null
        currentSource = null
        candidateIndex = -1
        hasFirstFrame = false
        showInitialLoading()
        updateNextPrevButtons()
        updateDownloadUi()

        loadJob = lifecycleScope.launch {
            try {
                val resolved = app().playerRepository.resolve(episodeSlug)
                sources = resolved
                episodeNumbers = resolved.episodeList
                    .map { it.number }.filter { it > 0 }.distinct().sorted()
                episodeSlugs = resolved.episodeList
                    .mapNotNull { item ->
                        item.slug?.takeIf { it.isNotBlank() }?.let { item.number to it }
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
                hideInitialLoading()
                toast(getString(R.string.terlalu_banyak_permintaan))
                saveAndFinish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hideInitialLoading()
                toast(getString(R.string.gagal_memutar, episode))
                saveAndFinish()
            }
        }
    }

    /**
     * Kandidat awal (v2.0.6, D4): video LANGSUNG dulu, embed terakhir.
     * - Pref "1" (default): direct 720p pertama; bila tidak ada, direct
     *   apa pun (360p); bila tidak ada direct sama sekali, embed.
     * - Pref "0": direct 360p pertama; bila tidak ada, direct 720p;
     *   bila tidak ada direct sama sekali, embed.
     * Embed TIDAK PERNAH dipilih bila masih ada kandidat direct — yang
     * Faygo lihat di v2.0.5 (player website Blogger) berasal dari aturan
     * lama yang memilih embed dulu saat pref "0".
     */
    private fun pickStartCandidate(all: EpisodeSources): Int {
        val candidates = all.candidates
        if (candidates.isEmpty()) return -1
        val direct = candidates.filter { !it.isWebView }
        if (direct.isEmpty()) return candidates.indexOfFirst { it.isWebView }
        val preferred = if (prefs.hdOnly == "1") {
            direct.firstOrNull { it.isHd }
                ?: direct.first()
        } else {
            direct.firstOrNull { !it.isHd }
                ?: direct.first()
        }
        return candidates.indexOf(preferred)
    }

    /** Mainkan kandidat ke-`index` (Media3 atau WebView). */
    private fun playCandidate(index: Int, restorePosition: Long = 0L) {
        val all = sources ?: return
        val source = all.candidates.getOrNull(index) ?: return
        candidateIndex = index
        currentSource = source
        isHd = source.isHd
        candidateStartedAtMs = SystemClock.elapsedRealtime()
        stopStallWatch()
        // Log TANPA URL (token stream sensitif) — host saja cukup untuk
        // membedakan Pixeldrain / token / Blogger / embed di logcat.
        Log.d(
            TAG_PLAYER,
            "Ep $episode: putar kandidat #$index kind=${source.kind} " +
                "label=${source.qualityLabel} host=${hostOf(source.url)}"
        )
        // Tinggi video kandidat lama tidak berlaku untuk kandidat baru —
        // label kembali ke label sumber sampai onVideoSizeChanged tiba.
        videoHeight = 0
        if (source.isWebView) {
            enterWebViewMode(source, restorePosition)
        } else {
            exitWebViewMode()
            startMedia3(source, restorePosition)
        }
        updateQualityUi()
        updateDownloadUi()
    }

    /** Kandidat sekarang error → coba kandidat berikutnya; habis → keluar jujur. */
    private fun tryNextCandidate() {
        val all = sources ?: return
        val nextIndex = candidateIndex + 1
        Log.d(
            TAG_PLAYER,
            "Ep $episode: kandidat #$candidateIndex (${currentSource?.kind}) " +
                "habis — coba #$nextIndex"
        )
        if (nextIndex >= all.candidates.size) {
            toast(getString(R.string.gagal_memutar, episode))
            saveAndFinish()
            return
        }
        val next = all.candidates[nextIndex]
        // Rantai fallback lintas-sumber (v2.0.7 item 14–16): sebelum jatuh
        // ke embed (kandidat terakhir), coba SATU lapis malas yang belum
        // dicoba — urutan Blogger (lapis 1) → ok.ru (lapis 2) →
        // Samehadaku (lapis 3). Tiap lapis sekali per episode; hasil
        // disisipkan sebelum embed supaya urutan prioritas tetap utuh,
        // tanpa membayar fetch lapis itu di tiap resolve.
        if (next.isWebView && tryLazyLayer(nextIndex)) return
        val restore = player?.currentPosition ?: 0L
        playNextOrAsk(nextIndex, restore)
    }

    /**
     * Coba SATU lapis malas berikutnya (Blogger → ok.ru → Samehadaku):
     * true = fetch diluncurkan (kelanjutan ditangani coroutine-nya);
     * false = tidak ada lapis tersisa (pemanggil lanjut ke embed/dialog).
     * Lapis yang tidak punya bahan (tanpa URL iframe/embed, tanpa judul
     * serial) ditandai sudah dicoba lalu DILEWATI — rantai tidak berhenti
     * di lapis yang memang tidak bisa dicoba.
     */
    private fun tryLazyLayer(nextIndex: Int): Boolean {
        val all = sources ?: return false
        // Lapis 1: Blogger (Animekompi).
        if (!bloggerAttemptedFallback) {
            bloggerAttemptedFallback = true
            val iframeUrl = all.bloggerIframeUrl
            if (!iframeUrl.isNullOrBlank()) {
                launchLazyLayer(nextIndex, "Blogger") {
                    app().playerRepository.fetchBloggerCandidates(iframeUrl)
                }
                return true
            }
        }
        // Lapis 2: ok.ru.
        if (!okRuAttemptedFallback) {
            okRuAttemptedFallback = true
            val okRuUrl = all.okRuEmbedUrl
            if (!okRuUrl.isNullOrBlank()) {
                launchLazyLayer(nextIndex, "ok.ru") {
                    app().playerRepository.fetchOkRuCandidates(okRuUrl)
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
                launchLazyLayer(nextIndex, "Samehadaku") {
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
     * Luncurkan fetch satu lapis malas; sisipkan hasilnya, lanjut putar.
     * Bila lapis ini tidak menghasilkan kandidat, nextIndex masih embed —
     * JANGAN masuk WebView otomatis (lihat playNextOrAsk). Bila
     * menghasilkan, nextIndex kini menunjuk kandidat pertama lapis baru.
     * Lapis berikut belum dicoba saat itu — tidak masalah: bila kandidat
     * baru ini gagal, tryNextCandidate dipanggil lagi dan tryLazyLayer
     * melanjutkan dari lapis berikutnya (flag lapis ini sudah true).
     * 429 (RateLimitException) TIDAK ditelan — toast rate-limit + keluar
     * (L-2), sama seperti jalur resolve() awal.
     */
    private fun launchLazyLayer(
        nextIndex: Int,
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
                // (tanpa retry) — jangan telan jadi dialog
                // "Putar lewat website?" (penyebabnya bukan "tidak ada
                // file", melainkan API dibatasi).
                hideInitialLoading()
                toast(getString(R.string.terlalu_banyak_permintaan))
                saveAndFinish()
                return@launch
            } catch (e: Exception) {
                emptyList()
            }
            Log.d(TAG_PLAYER, "Ep $episode: $layerName lazy → ${found.size} kandidat")
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
                updated.addAll(nextIndex, fresh)
                sources = current.copy(candidates = updated)
            }
            val restore = player?.currentPosition ?: 0L
            playNextOrAsk(nextIndex, restore)
        }
    }

    /**
     * Lanjutkan ke kandidat `index` — dengan SATU pengecualian (v2.0.6
     * #1b): bila kandidat itu EMBED, JANGAN masuk WebView otomatis dari
     * kontrol aplikasi. Semua kandidat direct sudah dicoba (termasuk
     * retry internal Media3 8× + ekstrak lazy Blogger/ok.ru/Samehadaku)
     * — tanya pengguna dulu lewat dialog; hanya ketukan eksplisit "Putar
     * lewat website" yang boleh memindahkan player ke website. Tanpa
     * ketukan, player keluar dengan toast jujur (seperti asli).
     */
    private fun playNextOrAsk(index: Int, restore: Long) {
        val all = sources ?: return
        val next = all.candidates.getOrNull(index) ?: return
        if (next.isWebView) {
            showWebViewFallbackDialog(index, restore)
        } else {
            playCandidate(index, restore)
        }
    }

    /**
     * Semua kandidat direct habis, kandidat berikut adalah embed — tanya
     * pengguna sebelum pindah ke website (v2.0.6 #1b, D4b). Dialog sekali
     * per episode (webViewFallbackDialogShown). "Tidak" / batal (tombol
     * kembali) = toast jujur + keluar, seperti asli saat video gagal.
     */
    private fun showWebViewFallbackDialog(embedIndex: Int, restorePosition: Long) {
        // L-12: jangan tampilkan dialog di Activity yang sedang selesai
        // (jendela finish() → onDestroy() — lifecycleScope belum batal
        // di situ; dialog di Activity yang selesai = window-leak/crash).
        if (isFinishing || isDestroyed) return
        if (webViewFallbackDialogShown) return
        webViewFallbackDialogShown = true
        Log.d(TAG_PLAYER, "Ep $episode: semua direct habis — dialog fallback WebView")
        hideInitialLoading()
        binding.progressBar.visibility = View.GONE
        AlertDialog.Builder(this, alertTheme())
            .setTitle(R.string.webview_fallback_judul)
            .setMessage(R.string.webview_fallback_isi)
            .setPositiveButton(R.string.putar_lewat_website) { _, _ ->
                playCandidate(embedIndex, restorePosition)
            }
            .setNegativeButton(R.string.tidak) { _, _ ->
                toast(getString(R.string.gagal_memutar, episode))
                saveAndFinish()
            }
            .setOnCancelListener {
                toast(getString(R.string.gagal_memutar, episode))
                saveAndFinish()
            }
            .setCancelable(true)
            .show()
    }

    private fun startMedia3(source: PlayableSource, restorePosition: Long) {
        val exoPlayer = player ?: return
        hasFirstFrame = false
        showInitialLoading()
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

    private fun enterWebViewMode(source: PlayableSource, restorePosition: Long) {
        webViewEntryPositionMs = if (restorePosition > 0) {
            restorePosition
        } else {
            player?.currentPosition ?: 0L
        }
        isWebViewMode = true
        webViewErrorShown = false
        player?.pause()
        hideInitialLoading()
        binding.exoPlayerView.visibility = View.GONE
        binding.webViewContainer.visibility = View.VISIBLE
        binding.webViewTitle.text =
            "$animeTitle — " + if (isMovie) getString(R.string.movie_label)
            else getString(R.string.episode_label, episode)
        // v2.0.6 #2: label kualitas bilah WebView = label sumber embed yang
        // sebenarnya (dari model kandidat) — JANGAN hardcode. Bila sumber
        // tidak punya label, sembunyikan (jangan berbohong).
        binding.webViewQualityLabel.text = source.qualityLabel
        binding.webViewQualityLabel.visibility =
            if (source.qualityLabel.isBlank()) View.GONE else View.VISIBLE
        val hasHd = sources?.candidates?.any { it.isHd && !it.isWebView } == true
        binding.webViewQuality720.visibility =
            if (hasHd) View.VISIBLE else View.GONE
        binding.webView.onResume()
        // M1: kunci navigasi ke host URL embed ini sebelum memuat.
        webViewAllowedHost = Uri.parse(source.url).host?.lowercase()
        binding.webView.loadUrl(source.url)
    }

    private fun exitWebViewMode() {
        if (!isWebViewMode) return
        isWebViewMode = false
        webViewAllowedHost = null
        binding.webView.onPause()
        binding.webView.stopLoading()
        binding.webViewContainer.visibility = View.GONE
        binding.exoPlayerView.visibility = View.VISIBLE
    }

    /**
     * Tombol Unduh di kontrol player / bilah WebView (v2.0.5, D1):
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
        binding.webViewDownload.visibility =
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
        // Tidak berlaku di mode WebView (tidak ada event akhir video).
        // v2.0.6 #6 — KEPUTUSAN: auto-next DINONAKTIFKAN di mode WebView.
        // Tidak ada event ENDED dari halaman embed (tanpa JS bridge), dan
        // polling durasi/posisi per-embed (Blogger, abyssplayer, VidHide,
        // mp4upload — masing-masing beda) rapuh + menambah permukaan
        // serangan; pengguna di mode embed keluar lewat FAB / kembali ke
        // daftar episode. Jangan pura-pura ada auto-next di embed.
        if (isWebViewMode) return
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
        if (isWebViewMode) {
            binding.webView.onPause()
        } else {
            wasPlaying = player?.isPlaying == true
            player?.pause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isWebViewMode) {
            binding.webView.onResume()
        } else if (wasPlaying) {
            player?.play()
        }
    }

    override fun onDestroy() {
        networkCallback?.let { callback ->
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
        saveResume()
        // WebView dihancurkan eksplisit (hindari kebocoran memori).
        fullscreenView?.let { binding.videoLayout.removeView(it) }
        fullscreenView = null
        runCatching {
            binding.webViewContainer.removeView(binding.webView)
            binding.webView.destroy()
        }
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
     * dipakai simpan-saat-seek (aturan asli) + ganti kualitas. Posisi di
     * mode WebView tetap best-effort (posisi saat masuk WebView).
     */
    private fun persistResume() {
        // Hanya simpan bila episode sudah ter-resolve (pernah mulai diputar).
        if (sources == null) return
        val position = if (isWebViewMode) {
            // Mode WebView: posisi video tidak bisa dibaca — pakai posisi
            // saat masuk WebView (best-effort, desain.md §14.5).
            (webViewEntryPositionMs - Constants.RESUME_REWIND_MS).coerceAtLeast(0L)
        } else {
            val exoPlayer = player ?: return
            (exoPlayer.currentPosition - Constants.RESUME_REWIND_MS)
                .coerceAtLeast(0L)
        }
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
     * retry (warisan). HTTP 4xx/5xx ikut di-retry internal (bukan daftar
     * no-retry di Media3 1.2.1) — setelah 8× gagal, error naik ke
     * onPlayerError lalu tryNextCandidate maju ke kandidat berikut;
     * kandidat direct yang benar-benar mati tetap dihabiskan dulu sebelum
     * fallback ke WebView ditawarkan (lewat dialog, bukan otomatis).
     */
    private class DirectLoadErrorHandlingPolicy :
        DefaultLoadErrorHandlingPolicy(MIN_DIRECT_LOAD_RETRIES) {
        companion object {
            /** Retry load internal per kandidat direct (default Media3: 3). */
            const val MIN_DIRECT_LOAD_RETRIES = 8
        }
    }
}
