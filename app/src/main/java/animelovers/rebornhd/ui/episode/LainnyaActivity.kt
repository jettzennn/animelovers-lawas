package animelovers.rebornhd.ui.episode

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import animelovers.rebornhd.R
import animelovers.rebornhd.app
import animelovers.rebornhd.data.model.Anime
import animelovers.rebornhd.databinding.ActivityLainnyaBinding
import animelovers.rebornhd.ui.BaseActivity
import animelovers.rebornhd.ui.adapter.BrowseGridAdapter
import animelovers.rebornhd.ui.detail.DetailActivity
import animelovers.rebornhd.util.Constants
import animelovers.rebornhd.util.toast
import kotlinx.coroutines.launch

/**
 * "Lainnya" dari Beranda — adaptasi activity_lainnya asli.
 * Dua mode: "Update Lainnya" (Episode Baru, urutan terbaru) dan
 * "Terlaris" (urutan skor tertinggi).
 * Grid 3 kolom portrait / 6 landscape. v1: 3 halaman pertama katalog
 * (60 judul) — cukup untuk daftar "Lainnya"; katalog penuh ada di Jelajahi.
 */
class LainnyaActivity : BaseActivity() {

    private lateinit var binding: ActivityLainnyaBinding
    private lateinit var adapter: BrowseGridAdapter

    private var mode: String = MODE_UPDATE

    companion object {
        const val MODE_UPDATE = "update"
        const val MODE_MOSTVIEW = "mostview"

        fun start(context: Context, mode: String) {
            val intent = Intent(context, LainnyaActivity::class.java).apply {
                putExtra(Constants.EXTRA_MODE, mode)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLainnyaBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = intent.getStringExtra(Constants.EXTRA_MODE) ?: MODE_UPDATE

        binding.toolbarLainnya.title = getString(
            if (mode == MODE_MOSTVIEW) R.string.terlaris else R.string.episode_baru
        )
        binding.toolbarLainnya.setNavigationIcon(R.drawable.ic_arrow_back_black_24dp)
        binding.toolbarLainnya.setNavigationOnClickListener { finish() }

        adapter = BrowseGridAdapter { anime ->
            DetailActivity.start(
                this, anime.id.toString(), anime.displayTitle, anime.poster
            )
        }
        binding.recyclerViewLainnya.apply {
            layoutManager = GridLayoutManager(
                this@LainnyaActivity,
                if (resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
                ) 6 else 3
            )
            adapter = this@LainnyaActivity.adapter
        }

        binding.swipeContainereee.setOnRefreshListener { load() }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            try {
                val repo = app().animeRepository
                val items = mutableListOf<Anime>()
                for (page in 1..3) {
                    items.addAll(repo.recentAnime(page))
                }
                val unlocked = app().authRepository.isSignedIn() &&
                    prefs.ecchiEnabled == "1"
                val visible = repo.visibleAnimes(items, unlocked)
                val sorted = if (mode == MODE_MOSTVIEW) {
                    visible.sortedByDescending { it.score.toDoubleOrNull() ?: 0.0 }
                } else {
                    visible
                }
                adapter.submitList(sorted)
            } catch (e: Exception) {
                toast(getString(R.string.gagal_memuat))
            } finally {
                binding.swipeContainereee.isRefreshing = false
            }
        }
    }
}
