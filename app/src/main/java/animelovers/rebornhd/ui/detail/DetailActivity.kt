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
import animelovers.rebornhd.databinding.ActivityDetailBinding
import animelovers.rebornhd.ui.BaseActivity
import animelovers.rebornhd.ui.adapter.EpisodeAdapter
import animelovers.rebornhd.ui.adapter.HistoryDetailAdapter
import animelovers.rebornhd.ui.episode.EpisodeActivity
import animelovers.rebornhd.ui.player.VideoPlayActivity
import animelovers.rebornhd.ui.settings.SettingActivity
import animelovers.rebornhd.util.Constants
import animelovers.rebornhd.util.toast
import animelovers.rebornhd.util.toastSankaError
import coil.load
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
    private var isFavorite: Boolean = false
    private var genres: List<String> = emptyList()

    companion object {
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

        // Placeholder langsung dari extras selagi /detail dimuat.
        binding.textViewTitled.text = animeTitle
        binding.posterDetailView.load(animePoster) {
            placeholder(R.drawable.splashh)
            error(R.drawable.splashh)
        }
        binding.imageDetThumbView.load(animePoster) {
            placeholder(R.drawable.splashh)
            error(R.drawable.splashh)
        }
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
        hideError()
        if (detail == null) showShimmer()
        lifecycleScope.launch {
            try {
                val data = app().sankaRepository.detail(animeSlug)
                detail = data
                applyData(data)
            } catch (e: RateLimitException) {
                toast(getString(R.string.terlalu_banyak_permintaan))
                if (detail == null) showError()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toastSankaError(e)
                if (detail == null) showError()
            } finally {
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

        binding.posterDetailView.load(animePoster) {
            placeholder(R.drawable.splashh)
            error(R.drawable.splashh)
        }
        binding.imageDetThumbView.load(animePoster) {
            placeholder(R.drawable.splashh)
            error(R.drawable.splashh)
        }
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
        binding.descDetailView.text = HtmlCompat.fromHtml(
            data.synopsis ?: "", HtmlCompat.FROM_HTML_MODE_LEGACY
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
        // Slug episode ASLI dari respons detail — jangan dirangkai sendiri.
        val episodeSlug = detail?.episodes.orEmpty()
            .firstOrNull { it.number == episode }?.slug
            ?.takeIf { it.isNotBlank() }
        if (episodeSlug == null) {
            toast(getString(R.string.gagal_memutar, episode))
            return
        }
        VideoPlayActivity.start(
            this, animeSlug, animeTitle, animePoster, episode, episodeSlug,
            isMovie = detail?.type == "Movie"
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
