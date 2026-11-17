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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import animelovers.rebornhd.R
import animelovers.rebornhd.app
import animelovers.rebornhd.data.model.VideoSource
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
 * Player — adaptasi VideoPlay.java asli (ExoPlayer 2.10.1 → Media3 1.2.1).
 *
 * Yang dipertahankan dari aslinya:
 * - FIT saat buka (setResizeMode 0), toggle FIT/FILL lewat `screenmode`.
 * - Buffer 50 detik / mulai 2,5 detik (DefaultLoadControl).
 * - Resume disimpan posisi − 30 detik saat keluar; orientasi `awallayar`
 *   dipulihkan saat keluar; pref orientasi "6"/"7" (SENSOR_LANDSCAPE/PORTRAIT).
 * - Dialog gerbang WiFi (bila pref wifi_switch "1" dan tidak di WiFi).
 * - Dialog mode "Unduh"/"Streaming" saat masuk dari Detail.
 * - Toggle SD/HD biner, panel volume (SeekBar max 255), auto-play `teruskan`,
 *   tombol next/prev dengan overlay _off, rotate.
 *
 * Penyimpangan yang disengaja (terdokumentasi):
 * - SD/HD memakai DefaultTrackSelector max-video-size — TIDAK perlu reload
 *   karena sumbernya HLS (aslinya reload karena mp4 terpisah).
 * - "Unduh" memakai DownloadManager pada URL master.m3u8 — yang terunduh
 *   HANYA file playlist, bukan video offline (HLS butuh segmen). Unduhan
 *   offline HLS yang benar butuh Media3 DownloadService — di luar cakupan
 *   v1 (PRD §12: DownloadManager saja).
 * - Dialog WiFi terputus di tengah putar memakai NetworkCallback (API 21+).
 */
@OptIn(UnstableApi::class)
class VideoPlayActivity : BaseActivity() {

    override val applyAppTheme: Boolean = false // FullscreenTheme dari manifest.

    private lateinit var binding: ActivityVideoBinding
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null

    private var animeId: String = ""
    private var animeTitle: String = ""
    private var animePoster: String = ""
    private var episode: Int = 1
    private var episodeNumbers: List<Int> = emptyList()
    private var currentSource: VideoSource? = null

    // Untuk polling subtitle (re-poll tiap ~15 detik bila status partial).
    private var currentAniId: Int = 0
    private var currentMegaEmbedId: String? = null
    private var subtitlePollJob: Job? = null
    private var lastSubtitleText: String? = null

    // Mode subtitle yang dipilih manual: "id" | "en" | "off".
    private var subtitleMode: String = "id"

    private var isHd: Boolean = false
    private var isFill: Boolean = false
    private var wasPlaying: Boolean = false
    private var resumeSaved: Boolean = false
    private var wifiDialogShown: Boolean = false

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        /** Polling subtitle: tiap 15 detik (kontrak server), maks 40×. */
        private const val SUBTITLE_POLL_INTERVAL_MS = 15_000L
        private const val MAX_SUBTITLE_POLL_ATTEMPTS = 40

        fun start(
            context: Context,
            animeId: String,
            title: String,
            poster: String,
            episode: Int
        ) {
            val intent = Intent(context, VideoPlayActivity::class.java).apply {
                putExtra(Constants.EXTRA_ID_TITLE, animeId)
                putExtra(Constants.EXTRA_TITLE, title)
                putExtra(Constants.EXTRA_POSTER, poster)
                putExtra(Constants.EXTRA_EPISODE, episode)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        animeId = intent.getStringExtra(Constants.EXTRA_ID_TITLE).orEmpty()
        animeTitle = intent.getStringExtra(Constants.EXTRA_TITLE).orEmpty()
        animePoster = intent.getStringExtra(Constants.EXTRA_POSTER).orEmpty()
        episode = intent.getIntExtra(Constants.EXTRA_EPISODE, 1)

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

        // Gerbang WiFi (pref wifi_switch "1" + tidak di WiFi).
        if (prefs.wifiSwitch == "1" && !isOnWifi()) {
            showWifiGateDialog()
        } else {
            loadEpisode(episode, showModeDialog = true)
        }
    }

    // ---- Setup player ----

    private fun setupPlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(50_000, 50_000, 2_500, 5_000)
            .build()
        val selector = DefaultTrackSelector(this)
        trackSelector = selector
        val exoPlayer = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setTrackSelector(selector)
            .build()
        player = exoPlayer

        binding.exoPlayerView.player = exoPlayer
        // FIT saat buka (seperti VideoPlay.onCreate: setResizeMode(0)).
        binding.exoPlayerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> binding.progressBar.visibility = View.GONE
                    Player.STATE_BUFFERING -> {
                        if (exoPlayer.playWhenReady) {
                            binding.progressBar.visibility = View.VISIBLE
                        }
                    }
                    Player.STATE_ENDED -> onPlaybackEnded()
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                toast(getString(R.string.gagal_memutar, episode))
                saveAndFinish()
            }
        })

        applyQuality()
        updateQualityLabel()
    }

    /** SD = cap 480p; HD = tanpa cap (HLS adaptif memilih varian terbaik). */
    private fun applyQuality() {
        val selector = trackSelector ?: return
        selector.setParameters(
            selector.buildUponParameters().apply {
                if (isHd) {
                    clearVideoSizeConstraints()
                } else {
                    setMaxVideoSize(854, 480)
                }
            }
        )
    }

    private fun updateQualityLabel() {
        val kualitas = binding.exoPlayerView.findViewById<TextView>(R.id.kualitas_video)
        kualitas?.text = getString(if (isHd) R.string.kualitas_hd else R.string.kualitas_sd)
        val resolution = binding.exoPlayerView.findViewById<ImageView>(R.id.resolution)
        resolution?.setImageResource(if (isHd) R.drawable.hd_icon else R.drawable.sd_icon)
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

        // SD/HD.
        playerView.findViewById<View>(R.id.resolution)?.setOnClickListener {
            isHd = !isHd
            prefs.hdOnly = if (isHd) "1" else "0"
            applyQuality()
            updateQualityLabel()
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

        // Next / prev.
        playerView.findViewById<View>(R.id.next_play_png)?.setOnClickListener {
            nextEpisode()?.let { loadEpisode(it, showModeDialog = false) }
        }
        playerView.findViewById<View>(R.id.prev_play_png)?.setOnClickListener {
            prevEpisode()?.let { loadEpisode(it, showModeDialog = false) }
        }

        // Subtitle manual: Indonesia → English → Mati (jalan keluar bila
        // subtitle Indonesia belum lengkap / salah — lihat #1).
        playerView.findViewById<View>(R.id.subtitle_button)?.setOnClickListener {
            cycleSubtitle()
        }

        loadEpisodeList()
    }

    /** Siklus tombol subtitle: Indonesia → English → Mati → Indonesia. */
    private fun cycleSubtitle() {
        val hasId = currentSource?.subtitleIdUri != null
        val hasEn = currentSource?.subtitleEnUrl != null
        subtitleMode = when (subtitleMode) {
            "id" -> if (hasEn) "en" else "off"
            "en" -> "off"
            else -> if (hasId) "id" else "en"
        }
        applySubtitleMode()
        toast(
            getString(
                when (subtitleMode) {
                    "id" -> R.string.subtitle_pilihan_indonesia
                    "en" -> R.string.subtitle_pilihan_inggris
                    else -> R.string.subtitle_pilihan_mati
                }
            )
        )
    }

    private fun applySubtitleMode() {
        val selector = trackSelector ?: return
        selector.setParameters(
            selector.buildUponParameters().apply {
                when (subtitleMode) {
                    "id" -> {
                        setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        setPreferredTextLanguage("id")
                    }
                    "en" -> {
                        setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        setPreferredTextLanguage("en")
                    }
                    else -> setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                }
            }
        )
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

    // ---- Daftar episode (untuk next/prev) ----

    private fun loadEpisodeList() {
        lifecycleScope.launch {
            try {
                val data = app().animeRepository.series(animeId) ?: return@launch
                episodeNumbers = data.episodes
                    .map { it.number }
                    .distinct()
                    .sorted()
                updateNextPrevButtons()
            } catch (e: Exception) {
                // Daftar episode gagal dimuat — next/prev tetap disembunyikan.
            }
        }
    }

    private fun nextEpisode(): Int? =
        episodeNumbers.firstOrNull { it > episode }

    private fun prevEpisode(): Int? =
        episodeNumbers.lastOrNull { it < episode }

    private fun updateNextPrevButtons() {
        val playerView: PlayerView = binding.exoPlayerView
        val hasNext = nextEpisode() != null
        val hasPrev = prevEpisode() != null
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

    private fun loadEpisode(episodeNumber: Int, showModeDialog: Boolean) {
        episode = episodeNumber
        binding.exoPlayerView.findViewById<TextView>(R.id.textViewEPISODE)?.text =
            getString(R.string.episode_label, episode)
        updateNextPrevButtons()
        binding.progressBar.visibility = View.VISIBLE
        // Episode berganti = polling subtitle lama berhenti.
        subtitlePollJob?.cancel()
        subtitlePollJob = null

        lifecycleScope.launch {
            // /series sekali jalan: ani_id (fallback mal_id) + episode_embed_id
            // untuk fallback MegaPlay di backend.
            val data = try {
                app().animeRepository.series(animeId)
            } catch (e: Exception) {
                null
            }
            currentAniId = data?.anime?.vidCloudId ?: animeId.toIntOrNull() ?: 0
            currentMegaEmbedId = data?.episodes
                ?.firstOrNull { it.number == episode }
                ?.episodeEmbedId
                ?.takeIf { it.isNotBlank() }
            val source = app().playerRepository.resolve(
                aniId = currentAniId,
                episode = episode,
                megaEmbedId = currentMegaEmbedId
            )
            binding.progressBar.visibility = View.GONE
            if (source == null) {
                toast(getString(R.string.gagal_memutar, episode))
                saveAndFinish()
                return@launch
            }
            currentSource = source
            if (showModeDialog) {
                showModeDialog(source)
            } else {
                startPlayback(source)
            }
        }
    }

    private fun showModeDialog(source: VideoSource) {
        AlertDialog.Builder(this, alertTheme())
            .setTitle(R.string.pilih_mode)
            .setPositiveButton(R.string.streaming) { _, _ -> startPlayback(source) }
            .setNegativeButton(R.string.unduh) { _, _ -> downloadSource(source) }
            .setNeutralButton(R.string.batal) { _, _ -> saveAndFinish() }
            .setCancelable(false)
            .show()
    }

    private fun downloadSource(source: VideoSource) {
        try {
            // Sanitasi judul: ':' '/' '?' dll. membuat tujuan DownloadManager
            // tidak valid ("Re:Zero", "Fate/stay night"). Tujuan mengikuti
            // PRD §2.1: Downloads/AnimLovers/<Judul>-Ep-<n>.m3u8
            // (ekstensi .m3u8 — v1 hanya mengunduh playlist, bukan video
            // offline; sudah disetujui).
            val safeTitle = animeTitle.replace(Regex("[^a-zA-Z0-9 _.-]"), "_")
            val request = DownloadManager.Request(Uri.parse(source.hlsUrl))
                .setTitle("$animeTitle - ${getString(R.string.episode_label, episode)}")
                .setDescription(getString(R.string.unduh))
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "AnimLovers/$safeTitle-Ep-$episode.m3u8"
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
        saveAndFinish()
    }

    private fun startPlayback(source: VideoSource) {
        val exoPlayer = player ?: return
        // Mode subtitle awal mengikuti track yang tersedia.
        subtitleMode = if (source.subtitleIdUri != null) "id" else "en"
        // Isi subtitle yang SUDAH dimuat (file lokal) — polling hanya
        // memuat ulang bila isi bertambah dari titik ini.
        lastSubtitleText = try {
            source.subtitleIdUri?.path
                ?.let { java.io.File(it) }
                ?.takeIf { it.exists() }
                ?.readText()
        } catch (e: Exception) {
            null
        }
        // Tombol subtitle hanya tampil bila ada track subtitle.
        binding.exoPlayerView.findViewById<View>(R.id.subtitle_button)?.visibility =
            if (source.subtitleIdUri != null || source.subtitleEnUrl != null) {
                View.VISIBLE
            } else {
                View.GONE
            }
        lifecycleScope.launch {
            val resumePosition = resumePositionFor(episode)
            exoPlayer.setMediaItem(buildMediaItem(source))
            if (resumePosition > 0) {
                exoPlayer.seekTo(resumePosition)
            }
            exoPlayer.prepare()
            exoPlayer.play()
            if (source.subtitleIdUri == null && source.subtitleEnUrl != null) {
                toast(getString(R.string.subtitle_indonesia_belum_tersedia))
            }
            // Subtitle translate masih sebagian → minta ulang tiap ~15 detik
            // sampai complete (kontrak server; lihat #1).
            if (source.subtitleIdStatus == "partial") {
                startSubtitlePolling()
            }
        }
    }

    /** MediaItem video + track subtitle (Indonesia + Inggris). */
    private fun buildMediaItem(source: VideoSource): MediaItem {
        val subtitleConfigs = mutableListOf<MediaItem.SubtitleConfiguration>()
        if (source.subtitleIdUri != null) {
            subtitleConfigs.add(
                MediaItem.SubtitleConfiguration.Builder(source.subtitleIdUri)
                    .setMimeType(MimeTypes.TEXT_VTT)
                    .setLanguage("id")
                    .setLabel("Indonesia")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            )
        }
        if (source.subtitleEnUrl != null) {
            subtitleConfigs.add(
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(source.subtitleEnUrl))
                    .setMimeType(MimeTypes.TEXT_VTT)
                    .setLanguage("en")
                    .setLabel("English")
                    .build()
            )
        }
        return MediaItem.Builder()
            .setUri(source.hlsUrl)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setSubtitleConfigurations(subtitleConfigs)
            .build()
    }

    /**
     * Polling subtitle Indonesia (maks 40× ≈ 10 menit — lebih dari cukup:
     * backend menyelesaikan translate ~25 detik menurut uji live).
     * Tiap putaran: ambil ulang VTT dari backend; bila isinya bertambah,
     * muat ulang track subtitle ke player yang sedang jalan (posisi
     * dipulihkan). Berhenti bila status complete / episode berganti /
     * Activity dihancurkan.
     */
    private fun startSubtitlePolling() {
        subtitlePollJob?.cancel()
        subtitlePollJob = lifecycleScope.launch {
            var attempts = 0
            while (isActive && attempts < MAX_SUBTITLE_POLL_ATTEMPTS) {
                attempts++
                delay(SUBTITLE_POLL_INTERVAL_MS)
                if (!isActive) break
                val fetched = try {
                    app().playerRepository.fetchSubtitleId(
                        currentAniId, episode, currentMegaEmbedId
                    )
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    null
                } ?: continue
                // Isi bertambah → muat ulang track subtitle.
                if (fetched.vttText != lastSubtitleText) {
                    lastSubtitleText = fetched.vttText
                    reloadSubtitleTrack()
                }
                if (fetched.status == "complete") break
            }
        }
    }

    /** Ganti media item dengan file subtitle terbaru, posisi tetap. */
    private fun reloadSubtitleTrack() {
        val exoPlayer = player ?: return
        val source = currentSource ?: return
        if (exoPlayer.currentMediaItemIndex != 0) return
        val position = exoPlayer.currentPosition
        exoPlayer.replaceMediaItem(0, buildMediaItem(source))
        exoPlayer.seekTo(position)
        exoPlayer.play()
    }

    private suspend fun resumePositionFor(episodeNumber: Int): Long {
        return try {
            val item = app().animeRepository.getHistoryForTitle(animeId)
            if (item != null && item.idepisode == episodeNumber.toString()) item.idsecond else 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun onPlaybackEnded() {
        if (prefs.teruskan == "true") {
            val next = nextEpisode()
            if (next != null) {
                loadEpisode(next, showModeDialog = false)
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
                loadEpisode(episode, showModeDialog = true)
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
        wasPlaying = player?.isPlaying == true
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        if (wasPlaying) {
            player?.play()
        }
    }

    override fun onDestroy() {
        subtitlePollJob?.cancel()
        subtitlePollJob = null
        networkCallback?.let { callback ->
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
        saveResume()
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
        val exoPlayer = player ?: return
        // Hanya simpan bila episode sudah ter-resolve (pernah mulai diputar).
        if (currentSource == null) return
        val position = (exoPlayer.currentPosition - Constants.RESUME_REWIND_MS)
            .coerceAtLeast(0L)
        // appScope (bukan lifecycleScope): tulis harus selesai walau
        // Activity sudah dihancurkan.
        app().appScope.launch {
            runCatching {
                app().animeRepository.saveResume(
                    idTitle = animeId,
                    title = animeTitle,
                    poster = animePoster,
                    episode = episode.toString(),
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
