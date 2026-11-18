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
import android.os.Environment
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import animelovers.rebornhd.R
import animelovers.rebornhd.app
import animelovers.rebornhd.data.api.RateLimitException
import animelovers.rebornhd.data.model.PlayableSource
import animelovers.rebornhd.data.repo.EpisodeSources
import animelovers.rebornhd.databinding.ActivityVideoBinding
import animelovers.rebornhd.ui.BaseActivity
import animelovers.rebornhd.util.Constants
import animelovers.rebornhd.util.isOnWifi
import animelovers.rebornhd.util.toast
import coil.load
import kotlinx.coroutines.Job
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
 *   embed (fallback terakhir). Fallback OTOMATIS antar kandidat bila yang
 *   sedang diputar error — tanpa tanya pengguna.
 * - `resolution` mengalihkan SUMBER 720p ↔ 480p (bukan cap HLS):
 *   720p = kandidat direct (Pixeldrain/token/Blogger-22), 480p = embed
 *   WebView. Satu tier saja → ikon `_off`, tidak bisa diketuk.
 *   Label kualitas_video = label jujur sumber ("720p" / "360p" / "480p").
 * - Loading awal = ProgressBar + teks "Memuat video…" dan kontrol TIDAK
 *   auto-hide sampai frame pertama tampil.
 * - Mode WebView: bilah atas minimal (FAB kembali + judul + label "480p" +
 *   tombol "720p" bila ada kandidat 720p). Next/Prev + auto-play TIDAK
 *   berlaku di WebView (aplikasi tidak bisa mendeteksi akhir video).
 *   Posisi resume saat pindah Media3↔WebView = best-effort (posisi saat
 *   masuk WebView — posisi di dalam WebView tidak bisa dibaca).
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

    /** Job fetch lazy Blogger (#6) — dibatalkan bila episode berganti. */
    private var bloggerJob: Job? = null

    /** Ekstrak Blogger lazy sudah dicoba untuk episode ini (sekali saja). */
    private var bloggerAttempted: Boolean = false

    /** Error WebView sudah ditangani untuk load embed ini (sekali saja). */
    private var webViewErrorShown: Boolean = false

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
        fun start(
            context: Context,
            animeSlug: String,
            title: String,
            poster: String,
            episode: Int,
            episodeSlug: String
        ) {
            val intent = Intent(context, VideoPlayActivity::class.java).apply {
                putExtra(Constants.EXTRA_ID_TITLE, animeSlug)
                putExtra(Constants.EXTRA_TITLE, title)
                putExtra(Constants.EXTRA_POSTER, poster)
                putExtra(Constants.EXTRA_EPISODE, episode)
                putExtra(Constants.EXTRA_EPISODE_SLUG, episodeSlug)
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
        val exoPlayer = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
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
                        if (!hasFirstFrame) {
                            hasFirstFrame = true
                            hideInitialLoading()
                        }
                    }
                    Player.STATE_BUFFERING -> {
                        if (exoPlayer.playWhenReady) {
                            binding.progressBar.visibility = View.VISIBLE
                        }
                    }
                    Player.STATE_ENDED -> onPlaybackEnded()
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Abaikan error basi (dari media item episode sebelumnya —
                // bisa tiba setelah pengguna pindah episode).
                val currentUri = exoPlayer.currentMediaItem
                    ?.localConfiguration?.uri?.toString()
                val source = currentSource
                if (source == null || source.isWebView || currentUri != source.url) return
                tryNextCandidate()
            }
        })
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
        val episodeView = playerView.findViewById<TextView>(R.id.textViewEPISODE)
        episodeView?.text = getString(R.string.episode_label, episode)
        val posterView = playerView.findViewById<ImageView>(R.id.postervideo)
        posterView?.load(animePoster) {
            placeholder(R.drawable.splashh)
            error(R.drawable.splashh)
        }

        playerView.findViewById<View>(R.id.fabvid)?.setOnClickListener { saveAndFinish() }

        // FIT/FILL.
        val screenMode = playerView.findViewById<TextView>(R.id.screenmode)
        screenMode?.text = getString(if (isFill) R.string.layar_penuh else R.string.layar_auto)
        playerView.findViewById<View>(R.id.rotateimg)?.setOnClickListener {
            toggleOrientation()
        }
        screenMode?.setOnClickListener { toggleResizeMode() }
        playerView.findViewById<View>(R.id.menu_kiri)?.setOnClickListener { toggleResizeMode() }

        // Kualitas 720p ↔ 480p (alih SUMBER — lihat onQualityToggle).
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

    // ---- Kualitas 720p ↔ 480p (alih sumber) ----

    /**
     * Tombol `resolution`: 720p (direct) ↔ 480p (embed WebView).
     * Bila hanya satu tier tersedia, tombol menampilkan ikon `_off` dan
     * tidak bisa diketuk (diatur updateQualityUi).
     */
    private fun onQualityToggle() {
        val all = sources ?: return
        val current = currentSource ?: return
        val hasHd = all.candidates.any { it.isHd && !it.isWebView }
        val hasSd = all.candidates.any { it.isWebView }
        if (!hasHd || !hasSd) return
        val restore = if (isWebViewMode) webViewEntryPositionMs
        else (player?.currentPosition ?: 0L)
        if (current.isHd) {
            // 720p → 480p (embed).
            isHd = false
            prefs.hdOnly = "0"
            val embedIndex = all.candidates.indexOfFirst { it.isWebView }
            if (embedIndex >= 0) playCandidate(embedIndex, restore)
        } else {
            // 480p/360p → 720p terbaik.
            isHd = true
            prefs.hdOnly = "1"
            val hdIndex = all.candidates.indexOfFirst { it.isHd && !it.isWebView }
            if (hdIndex >= 0) playCandidate(hdIndex, restore)
        }
    }

    /** Label + ikon kualitas mengikuti sumber yang sedang diputar. */
    private fun updateQualityUi() {
        val all = sources
        val current = currentSource
        val kualitas = binding.exoPlayerView.findViewById<TextView>(R.id.kualitas_video)
        kualitas?.text = current?.qualityLabel ?: "720p"
        val resolution = binding.exoPlayerView.findViewById<ImageView>(R.id.resolution)
        val hasHd = all?.candidates?.any { it.isHd && !it.isWebView } == true
        val hasSd = all?.candidates?.any { it.isWebView } == true
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
        binding.exoPlayerView.findViewById<TextView>(R.id.textViewEPISODE)?.text =
            getString(R.string.episode_label, episode)
        // Reset state episode lama (SEBELUM resolve baru) + batalkan
        // resolve/putar/fetch Blogger episode lama (#2): coroutine yang
        // dibatalkan tidak lagi menulis sources/currentSource episode lama.
        loadJob?.cancel()
        playJob?.cancel()
        bloggerJob?.cancel()
        bloggerAttempted = false
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

    /** Kandidat awal: pref hdOnly ("1" = utamakan 720p, "0" = utamakan 480p). */
    private fun pickStartCandidate(all: EpisodeSources): Int {
        val candidates = all.candidates
        if (candidates.isEmpty()) return -1
        return if (prefs.hdOnly == "1") {
            candidates.indexOfFirst { it.isHd && !it.isWebView }
                .takeIf { it >= 0 } ?: 0
        } else {
            candidates.indexOfFirst { it.isWebView }
                .takeIf { it >= 0 } ?: 0
        }
    }

    /** Mainkan kandidat ke-`index` (Media3 atau WebView). */
    private fun playCandidate(index: Int, restorePosition: Long = 0L) {
        val all = sources ?: return
        val source = all.candidates.getOrNull(index) ?: return
        candidateIndex = index
        currentSource = source
        isHd = source.isHd
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
        if (nextIndex >= all.candidates.size) {
            toast(getString(R.string.gagal_memutar, episode))
            saveAndFinish()
            return
        }
        val next = all.candidates[nextIndex]
        // Lazy Blogger (#6): sebelum jatuh ke embed (kandidat terakhir),
        // coba ekstrak URL Blogger sekali — bila ada, sisipkan sebelum
        // embed supaya urutan prioritas T1b tetap utuh (direct → token →
        // Blogger → embed), tanpa membayar fetch Blogger di tiap resolve.
        if (next.isWebView && !bloggerAttempted) {
            bloggerAttempted = true
            val iframeUrl = all.bloggerIframeUrl
            if (!iframeUrl.isNullOrBlank()) {
                showInitialLoading()
                val snapshot = all
                bloggerJob?.cancel()
                bloggerJob = lifecycleScope.launch {
                    val blogger = try {
                        app().playerRepository.fetchBloggerCandidates(iframeUrl)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                    // Episode sudah berganti selagi fetch berjalan — abaikan
                    // (jangan menulis kandidat ke sources episode baru).
                    if (sources !== snapshot) return@launch
                    val current = sources ?: return@launch
                    if (blogger.isNotEmpty()) {
                        val updated = current.candidates.toMutableList()
                        updated.addAll(nextIndex, blogger)
                        sources = current.copy(candidates = updated)
                    }
                    val restore = player?.currentPosition ?: 0L
                    playCandidate(nextIndex, restore)
                }
                return
            }
        }
        val restore = player?.currentPosition ?: 0L
        playCandidate(nextIndex, restore)
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
            "$animeTitle — ${getString(R.string.episode_label, episode)}"
        binding.webViewQualityLabel.text = "480p"
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
        if (isWebViewMode) return
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

    private fun saveResume() {
        if (resumeSaved) return
        resumeSaved = true
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
}
