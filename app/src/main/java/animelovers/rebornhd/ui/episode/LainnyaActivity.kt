package animelovers.rebornhd.ui.episode

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import animelovers.rebornhd.R
import animelovers.rebornhd.app
import animelovers.rebornhd.data.api.RateLimitException
import animelovers.rebornhd.data.model.SankaAnime
import animelovers.rebornhd.databinding.ActivityLainnyaBinding
import animelovers.rebornhd.ui.BaseActivity
import animelovers.rebornhd.ui.adapter.BrowseGridAdapter
import animelovers.rebornhd.ui.detail.DetailActivity
import animelovers.rebornhd.util.Constants
import animelovers.rebornhd.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * "Lainnya" dari Beranda — adaptasi activity_lainnya asli.
 * Dua mode: "Update Lainnya" (Episode Baru, urutan terbaru) dan
 * "Terlaris" (urutan Sanka popularWeekly — server yang mengurutkan).
 * Grid 3 kolom portrait / 6 landscape. v2: satu request /home (di-cache).
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
            val slug = anime.slug ?: return@BrowseGridAdapter
            DetailActivity.start(
                this, slug, anime.displayTitle, anime.posterUrl
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
                val repo = app().sankaRepository
                val home = repo.home()
                val unlocked = app().authRepository.isSignedIn() &&
                    prefs.ecchiEnabled == "1"
                val items: List<SankaAnime> = if (mode == MODE_MOSTVIEW) {
                    // Terlaris: urutan server (popularWeekly) — jangan diurut
                    // ulang (Sanka tidak memberi skor; views hanya badge).
                    repo.visibleAnimes(home.popular, unlocked)
                } else {
                    // Episode Baru: kartu per episode (judul "… Episode N"),
                    // ketuk → Detail anime-nya (slug = anime_slug).
                    // Disaring Ecchi DULU via visibleEpisodes — hasil peta
                    // ke SankaAnime membawa genres = null, jadi tidak bisa
                    // disaring sesudah dipetakan.
                    val visible = repo.visibleEpisodes(
                        home.latestEpisodes.orEmpty(), home, unlocked
                    )
                    visible.map { episode ->
                        SankaAnime(
                            slug = episode.animeSlug,
                            title = episode.title ?: episode.animeTitle,
                            poster = episode.posterUrl,
                            type = episode.animeType,
                            status = episode.animeStatus
                        )
                    }
                }
                adapter.submitList(items)
            } catch (e: RateLimitException) {
                toast(getString(R.string.terlalu_banyak_permintaan))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(getString(R.string.gagal_memuat))
            } finally {
                binding.swipeContainereee.isRefreshing = false
            }
        }
    }
}
