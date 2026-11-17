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
import animelovers.rebornhd.data.model.SeriesData
import animelovers.rebornhd.databinding.ActivityDetailBinding
import animelovers.rebornhd.ui.BaseActivity
import animelovers.rebornhd.ui.adapter.EpisodeAdapter
import animelovers.rebornhd.ui.adapter.HistoryDetailAdapter
import animelovers.rebornhd.ui.episode.EpisodeActivity
import animelovers.rebornhd.ui.player.VideoPlayActivity
import animelovers.rebornhd.ui.settings.SettingActivity
import animelovers.rebornhd.util.Constants
import animelovers.rebornhd.util.toast
import coil.load
import kotlinx.coroutines.launch

/**
 * Detail — adaptasi DetailActivity asli (banner iklan + checkbox notif DIHAPUS).
 * Poster 350dp FIT_CENTER + placeholder splashh, overlay hitam di bawah,
 * kartu deskripsi, baris "Lanjutkan" (bila ada riwayat), grid episode
 * 4 kolom portrait / 8 landscape, cap 40 episode + "Lainnya" bila lebih.
 *
 * Gerbang Ecchi (§5.1): overlay kunci tampil bila genre mengandung "Ecchi"
 * DAN (belum login ATAU toggle Ecchi mati). Ketuk overlay → Pengaturan.
 */
class DetailActivity : BaseActivity() {

    override val transparentStatusBar: Boolean = true

    private lateinit var binding: ActivityDetailBinding
    private lateinit var episodeAdapter: EpisodeAdapter
    private lateinit var historyAdapter: HistoryDetailAdapter

    private var animeId: String = ""
    private var animeTitle: String = ""
    private var animePoster: String = ""
    private var series: SeriesData? = null
    private var isFavorite: Boolean = false
    private var genres: List<String> = emptyList()

    companion object {
        fun start(context: Context, animeId: String, title: String, poster: String) {
            val intent = Intent(context, DetailActivity::class.java).apply {
                putExtra(Constants.EXTRA_ID_TITLE, animeId)
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

        animeId = intent.getStringExtra(Constants.EXTRA_ID_TITLE).orEmpty()
        animeTitle = intent.getStringExtra(Constants.EXTRA_TITLE).orEmpty()
        animePoster = intent.getStringExtra(Constants.EXTRA_POSTER).orEmpty()

        // Placeholder langsung dari extras selagi /series dimuat.
        binding.textViewTitled.text = animeTitle
        binding.posterDetailView.load(animePoster) {
            placeholder(R.drawable.splashh)
            error(R.drawable.splashh)
        }
        binding.imageDetThumbView.load(animePoster) {
            placeholder(R.drawable.splashh)
            error(R.drawable.splashh)
        }
        binding.shimmerViewContainer.startShimmer()

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
            EpisodeActivity.start(this, animeId, animeTitle, animePoster)
        }
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

    private fun load() {
        lifecycleScope.launch {
            try {
                val data = app().animeRepository.series(animeId)
                if (data != null) {
                    series = data
                    applyData(data)
                } else {
                    toast(getString(R.string.gagal_memuat))
                }
            } catch (e: Exception) {
                toast(getString(R.string.gagal_memuat))
            } finally {
                binding.shimmerViewContainer.stopShimmer()
                binding.swipeContainerDet.isRefreshing = false
            }
        }
        refreshFavorite()
        refreshHistory()
    }

    private fun applyData(data: SeriesData) {
        val anime = data.anime ?: return
        animeTitle = anime.displayTitle
        animePoster = anime.poster
        genres = anime.genres

        binding.posterDetailView.load(animePoster) {
            placeholder(R.drawable.splashh)
            error(R.drawable.splashh)
        }
        binding.imageDetThumbView.load(animePoster) {
            placeholder(R.drawable.splashh)
            error(R.drawable.splashh)
        }
        binding.textViewTitled.text = animeTitle
        binding.textViewStatus.text =
            getString(R.string.status_format, anime.status, anime.rating)
        binding.textViewGenre.text =
            getString(R.string.genre_format, anime.genres.joinToString(", "))
        binding.descDetailView.text = HtmlCompat.fromHtml(
            anime.description, HtmlCompat.FROM_HTML_MODE_LEGACY
        )

        val episodes = data.episodes
            .map { it.number }
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
        if (locked && showToast && series != null) {
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
        VideoPlayActivity.start(this, animeId, animeTitle, animePoster, episode)
    }

    private fun refreshFavorite() {
        lifecycleScope.launch {
            isFavorite = app().animeRepository.isFavorite(animeId)
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
        val anime = series?.anime ?: return
        lifecycleScope.launch {
            isFavorite = app().animeRepository.toggleFavorite(anime)
            updateFavoriteIcon()
            toast(
                if (isFavorite) getString(R.string.favorit_ditambahkan)
                else getString(R.string.favorit_dihapus)
            )
        }
    }

    private fun refreshHistory() {
        lifecycleScope.launch {
            val item = app().animeRepository.getHistoryForTitle(animeId)
            historyAdapter.submitList(if (item != null) listOf(item) else emptyList())
        }
    }
}
