package animelovers.rebornhd.ui.detail

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import animelovers.rebornhd.R
import animelovers.rebornhd.app
import animelovers.rebornhd.data.api.RateLimitException
import animelovers.rebornhd.data.model.SankaDetail
import animelovers.rebornhd.data.repo.SankaRepository
import animelovers.rebornhd.databinding.ActivityDetailBinding
import animelovers.rebornhd.ui.BaseActivity
import animelovers.rebornhd.ui.adapter.EpisodeAdapter
import animelovers.rebornhd.ui.adapter.HistoryDetailAdapter
import animelovers.rebornhd.ui.adapter.loadPoster
import animelovers.rebornhd.ui.episode.EpisodeActivity
import animelovers.rebornhd.ui.player.VideoPlayActivity
import animelovers.rebornhd.ui.settings.SettingActivity
import animelovers.rebornhd.util.Constants
import animelovers.rebornhd.util.toast
import animelovers.rebornhd.util.toastSankaError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Detail — adaptasi DetailActivity asli (banner iklan + checkbox notif DIHAPUS).
 * Poster 350dp FIT_CENTER + placeholder splashh, overlay hitam di bawah,
 * kartu deskripsi, baris "Lanjutkan" (bila ada riwayat), grid episode
 * 4 kolom portrait / 8 landscape, cap 40 episode + "Lainnya" bila lebih.
 *
 * v2 (Sanka): detail dari GET /anime/animekompi/detail/{slug}; daftar
 * episode dari detail.episodes (slug episode ASLI dari server — jangan
 * dirangkai sendiri, lihat SankaEpisode.kt).
 *
 * Gerbang Ecchi (§5.1): overlay kunci tampil bila genre mengandung "Ecchi"
 * DAN (belum login ATAU toggle Ecchi mati). Ketuk overlay → Pengaturan.
 */
class DetailActivity : BaseActivity() {

    override val transparentStatusBar: Boolean = true

    private lateinit var binding: ActivityDetailBinding
    private lateinit var episodeAdapter: EpisodeAdapter
    private lateinit var historyAdapter: HistoryDetailAdapter

    private var animeSlug: String = ""
    private var animeTitle: String = ""
    private var animePoster: String = ""
    private var detail: SankaDetail? = null
    /**
     * Episode yang diketuk di kartu "Lanjutkan" SEBELUM /detail kembali
     * (v2.0.10 M-4) — diputar begitu applyData tiba (lihat openPlayer).
     */
    private var pendingEpisode: Int? = null
    // F-3 Cari Bug v2.0.10: guard muat-ganda — ketuk ganda "Coba lagi" /
    // "Lanjutkan" tidak memanggil load() dua kali (buang anggaran throttle).
    private var isLoading: Boolean = false
    private var isFavorite: Boolean = false
    private var genres: List<String> = emptyList()

    companion object {
        private const val KEY_PENDING_EPISODE = "pending_episode"
        fun start(context: Context, animeSlug: String, title: String, poster: String) {
            val intent = Intent(context, DetailActivity::class.java).apply {
                putExtra(Constants.EXTRA_ID_TITLE, animeSlug)
                putExtra(Constants.EXTRA_TITLE, title)
                putExtra(Constants.EXTRA_POSTER, poster)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        animeSlug = intent.getStringExtra(Constants.EXTRA_ID_TITLE).orEmpty()
        animeTitle = intent.getStringExtra(Constants.EXTRA_TITLE).orEmpty()
        animePoster = intent.getStringExtra(Constants.EXTRA_POSTER).orEmpty()

        // F-3: pendingEpisode bertahan lewat rotasi (field biasa hilang
        // saat Activity dibuat ulang) — dipulihkan di sini, dikonsumsi
        // applyData begitu /detail kembali seperti biasa.
        pendingEpisode = savedInstanceState?.getInt(KEY_PENDING_EPISODE, -1)
            ?.takeIf { it > 0 }

        // Placeholder langsung dari extras selagi /detail dimuat.
        // v2.0.10 M-1: pakai helper loadPoster yang sama dengan Beranda —
        // poster gagal menampilkan ikon error + bisa dicoba ulang dengan
        // ketuk (dulu error(R.drawable.splashh): tampak seperti
        // placeholder biasa, tanpa retry). Ketuk saat sukses tidak
        // melakukan apa-apa (onPosterClick = {}).
        binding.textViewTitled.text = animeTitle
        loadPoster(binding.posterDetailView, animePoster, widthDp = -1, heightDp = 350) {}
        loadPoster(binding.imageDetThumbView, animePoster, widthDp = 100, heightDp = 130) {}
        binding.shimmerViewContainer.startShimmer() // showShimmer() penuh di load().

        episodeAdapter = EpisodeAdapter { episode ->
            openPlayer(episode)
        }
        binding.recycleEpisodeDetailView.apply {
            layoutManager = GridLayoutManager(
                this@DetailActivity, spanCountForOrientation()
            )
            adapter = episodeAdapter
            isNestedScrollingEnabled = false
        }

        historyAdapter = HistoryDetailAdapter { item ->
            openPlayer(item.idepisode.toIntOrNull() ?: 1)
        }
        binding.recyclerViewHistoryDetail.apply {
            layoutManager = LinearLayoutManager(this@DetailActivity)
            adapter = historyAdapter
            isNestedScrollingEnabled = false
        }

        binding.fab.setOnClickListener { finish() }
        binding.favButton.setOnClickListener { toggleFavorite() }
        binding.lainnyaepisodeTxt.setOnClickListener {
            EpisodeActivity.start(this, animeSlug, animeTitle, animePoster)
        }
        // State error menetap (v2.0.5, temuan Minor Cari Bug): tombol
        // "Coba lagi" memuat ulang detail — user tidak lagi harus
        // pull-to-refresh setelah toast error hilang.
        binding.cobaLagiButton.setOnClickListener { load() }
        binding.swipeContainerDet.setOnRefreshListener { load() }

        val openSettings = {
            startActivity(Intent(this, SettingActivity::class.java))
        }
        binding.lock.setOnClickListener { openSettings() }
        binding.overlays.setOnClickListener { openSettings() }
        binding.unlocknow.setOnClickListener { openSettings() }
        binding.unlockimg.setOnClickListener { openSettings() }

        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // F-3: simpan pendingEpisode supaya rotasi tidak menghilangkannya.
        pendingEpisode?.let { outState.putInt(KEY_PENDING_EPISODE, it) }
    }

    override fun onResume() {
        super.onResume()
        applyLock()
        refreshFavorite()
        refreshHistory()
    }

    private fun spanCountForOrientation(): Int =
        if (resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        ) 8 else 4

    /**
     * Skeleton loading (v2.0.5): shimmer HANYA overlay saat loading —
     * konten TIDAK LAGI di dalam ShimmerFrameLayout (lihat
     * activity_detail.xml). Tampil saat load pertama / rotasi
     * (detail == null); saat pull-to-refresh konten sudah ada, jadi
     * skeleton tidak dimunculkan lagi (spinner swipe saja).
     */
    private fun showShimmer() {
        binding.shimmerViewContainer.visibility = View.VISIBLE
        binding.shimmerViewContainer.startShimmer()
    }

    /**
     * Shimmer MATI SECARA VISUAL (v2.0.5): stopShimmer() saja TIDAK cukup
     * — dulu wadah shimmer tetap membungkus konten sehingga konten tampak
     * gelap/redup setelah load. Kini wadahnya di-GONE, jadi konten tampil
     * bersih. Dipanggil di finally load(): sukses MAUPUN gagal.
     */
    private fun hideShimmer() {
        binding.shimmerViewContainer.stopShimmer()
        binding.shimmerViewContainer.visibility = View.GONE
    }

    /**
     * State error menetap (v2.0.5, temuan Minor Cari Bug): Detail gagal
     * dulu hanya toast — setelah toast hilang layar cuma judul/poster +
     * grid kosong dan user harus pull-to-refresh. Kini pesan error +
     * tombol "Coba lagi" tampil MENETAP sampai load berhasil.
     * Hanya bila belum ada data (detail == null): bila konten sudah
     * termuat lalu refresh gagal, konten tetap tampil + toast saja
     * (jangan tutupi konten bagus dengan layar error).
     */
    private fun showError() {
        binding.errorDetailContainer.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.errorDetailContainer.visibility = View.GONE
    }

    private fun load() {
        if (isLoading) return
        isLoading = true
        hideError()
        if (detail == null) showShimmer()
        lifecycleScope.launch {
            try {
                val data = app().sankaRepository.detail(animeSlug)
                detail = data
                applyData(data)
            } catch (e: RateLimitException) {
                // F-3: load gagal → pendingEpisode basi, bersihkan supaya
                // "Coba lagi" berikutnya tidak memutar episode yang sudah
                // tidak diinginkan. (CancellationException dikecualikan:
                // pemanggil membatalkan, bukan gagal.)
                pendingEpisode = null
                toast(getString(R.string.terlalu_banyak_permintaan))
                if (detail == null) showError()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // F-3: sama — pendingEpisode basi dibersihkan saat gagal.
                pendingEpisode = null
                toastSankaError(e)
                if (detail == null) showError()
            } finally {
                isLoading = false
                hideShimmer()
                binding.swipeContainerDet.isRefreshing = false
            }
        }
        refreshFavorite()
        refreshHistory()
    }

    private fun applyData(data: SankaDetail) {
        animeTitle = data.displayTitle
        animePoster = data.posterUrl
        genres = data.genreSlugs

        // v2.0.10 M-1: lihat onCreate — helper yang sama (ikon error + retry).
        loadPoster(binding.posterDetailView, animePoster, widthDp = -1, heightDp = 350) {}
        loadPoster(binding.imageDetThumbView, animePoster, widthDp = 100, heightDp = 130) {}
        binding.textViewTitled.text = animeTitle
        // Baris status disembunyikan bila status + rating sama-sama kosong
        // — kalau tidak, tampil label kosong "Status :   Rating : ".
        val hasStatus = !data.status.isNullOrBlank() || data.ratingText.isNotBlank()
        binding.textViewStatus.visibility = if (hasStatus) View.VISIBLE else View.GONE
        if (hasStatus) {
            binding.textViewStatus.text =
                getString(R.string.status_format, data.status ?: "", data.ratingText)
        }
        binding.textViewGenre.text =
            getString(R.string.genre_format, data.genreNames)
        // v2.0.7 #11: sinopsis Sanka ditampilkan APA ADANYA, penuh
        // (layout descDetailView tanpa maxLines — tidak ada pemotongan).
        // Bila Sanka memberi sinopsis kosong / satu kalimat (mis. Tensei
        // shitara Ken deshita II: "Musim kedua dari Tensei shitara Ken
        // deshita."), tidak ada sumber Indonesia lain yang layak —
        // sinopsis MAL/Jikan berbahasa Inggris (butuh keputusan Faygo soal
        // bahasa bila dipakai). Itu keterbatasan SUMBER, bukan bug aplikasi
        // — jangan isi teks palsu. Bila kosong, sembunyikan barisnya
        // supaya tidak ada blok kosong.
        val synopsis = data.synopsis.orEmpty().trim()
        binding.descDetailView.visibility =
            if (synopsis.isBlank()) View.GONE else View.VISIBLE
        binding.descDetailView.text = HtmlCompat.fromHtml(
            synopsis, HtmlCompat.FROM_HTML_MODE_LEGACY
        )

        val episodes = data.episodes.orEmpty()
            .map { it.number }
            .filter { it > 0 }
            .distinct()
            .sorted()
        val shown = episodes.take(Constants.DETAIL_EPISODE_CAP)
        episodeAdapter.submitList(shown)
        binding.lainnyaepisodeTxt.visibility =
            if (episodes.size > Constants.DETAIL_EPISODE_CAP) View.VISIBLE else View.GONE

        applyLock(showToast = true)

        // v2.0.10 M-4: ketukan "Lanjutkan" yang ditahan selagi detail
        // dimuat — putar sekarang (detail sudah ada).
        pendingEpisode?.let { ep ->
            pendingEpisode = null
            openPlayer(ep)
        }
    }

    /** Overlay kunci + toast penjelasan (sekali saat data dimuat). */
    private fun applyLock(showToast: Boolean = false) {
        val locked = isLocked()
        binding.lock.visibility = if (locked) View.VISIBLE else View.GONE
        if (locked && showToast && detail != null) {
            if (!app().authRepository.isSignedIn()) {
                toast(getString(R.string.ecchi_perlu_login))
            } else {
                toast(getString(R.string.ecchi_belum_aktif))
            }
        }
    }

    private fun isLocked(): Boolean {
        val ecchi = genres.any { genre ->
            Constants.LOCKED_GENRES.any { it.equals(genre, ignoreCase = true) }
        }
        if (!ecchi) return false
        val auth = app().authRepository
        return !(auth.isSignedIn() && prefs.ecchiEnabled == "1")
    }

    private fun openPlayer(episode: Int) {
        if (isLocked()) {
            toast(getString(R.string.ecchi_perlu_login))
            return
        }
        val d = detail
        // v2.0.10 M-4: kartu "Lanjutkan" (riwayat lokal, diisi
        // refreshHistory dari DB di onResume) bisa diketuk SEBELUM
        // /detail kembali — saat itu `detail` masih null dan openPlayer
        // lama langsung toast "gagal memutar". Kini ketukan DITAHAN:
        // simpan nomornya, muat ulang detail, dan putar begitu applyData
        // tiba (toast memberitahu pengguna untuk menunggu sebentar).
        if (d == null) {
            pendingEpisode = episode
            load()
            toast(getString(R.string.detail_memuat))
            return
        }
        // Slug episode ASLI dari respons detail — jangan dirangkai sendiri.
        // v2.0.10 item 1: daftar episode bisa berisi DUA entri bernomor
        // sama (entri "Episode Spesial" di depan, episode asli di
        // belakang) — pilih lewat pickEpisodeSlug, BUKAN entri pertama
        // mentah (itu yang membuat "Episode 1" One Piece memutar konten
        // Wano/Spesial di v2.0.9).
        val episodeSlug = SankaRepository.pickEpisodeSlug(
            d.episodes.orEmpty().map { it.number to it.slug }, episode
        )
        if (episodeSlug == null) {
            toast(getString(R.string.gagal_memutar, episode))
            return
        }
        VideoPlayActivity.start(
            this, animeSlug, animeTitle, animePoster, episode, episodeSlug,
            isMovie = d.type == "Movie"
        )
    }

    private fun refreshFavorite() {
        lifecycleScope.launch {
            isFavorite = app().sankaRepository.isFavorite(animeSlug)
            updateFavoriteIcon()
        }
    }

    private fun updateFavoriteIcon() {
        binding.favButton.imageTintList =
            if (isFavorite) {
                ColorStateList.valueOf(Color.parseColor("#33DDFF"))
            } else {
                null
            }
    }

    private fun toggleFavorite() {
        val anime = detail?.toAnime() ?: return
        lifecycleScope.launch {
            isFavorite = app().sankaRepository.toggleFavorite(anime)
            updateFavoriteIcon()
            toast(
                if (isFavorite) getString(R.string.favorit_ditambahkan)
                else getString(R.string.favorit_dihapus)
            )
        }
    }

    private fun refreshHistory() {
        lifecycleScope.launch {
            val item = app().sankaRepository.getHistoryForTitle(animeSlug)
            historyAdapter.submitList(if (item != null) listOf(item) else emptyList())
        }
    }
}
