package animelovers.rebornhd.ui.episode

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import animelovers.rebornhd.R
import animelovers.rebornhd.app
import animelovers.rebornhd.data.api.RateLimitException
import animelovers.rebornhd.data.model.SankaDetail
import animelovers.rebornhd.data.repo.SankaRepository
import animelovers.rebornhd.databinding.ActivityEpisodeBinding
import animelovers.rebornhd.ui.BaseActivity
import animelovers.rebornhd.ui.adapter.EpisodeAdapter
import animelovers.rebornhd.ui.main.MainActivity
import animelovers.rebornhd.ui.player.VideoPlayActivity
import animelovers.rebornhd.util.Constants
import animelovers.rebornhd.util.KillSwitch
import animelovers.rebornhd.util.setTintedNavigationIcon
import animelovers.rebornhd.util.toast
import animelovers.rebornhd.util.toastSankaError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Semua episode satu anime — adaptasi EpisodeActivity asli
 * (activity_episode_lainnya). Grid 4 kolom portrait / 8 landscape.
 * Dibuka dari "Lainnya" di Detail bila episode > 40.
 * v2 (Sanka): daftar lengkap dari detail.episodes (tanpa cap — RecyclerView
 * ini scroll sendiri, jadi recycling jalan).
 */
class EpisodeActivity : BaseActivity() {

    private lateinit var binding: ActivityEpisodeBinding
    private lateinit var adapter: EpisodeAdapter

    private var animeSlug: String = ""
    private var animeTitle: String = ""
    private var animePoster: String = ""
    private var detail: SankaDetail? = null

    companion object {
        fun start(context: Context, animeSlug: String, title: String, poster: String) {
            val intent = Intent(context, EpisodeActivity::class.java).apply {
                putExtra(Constants.EXTRA_ID_TITLE, animeSlug)
                putExtra(Constants.EXTRA_TITLE, title)
                putExtra(Constants.EXTRA_POSTER, poster)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEpisodeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        animeSlug = intent.getStringExtra(Constants.EXTRA_ID_TITLE).orEmpty()
        animeTitle = intent.getStringExtra(Constants.EXTRA_TITLE).orEmpty()
        animePoster = intent.getStringExtra(Constants.EXTRA_POSTER).orEmpty()

        binding.toolbarEpisodeLainnya.title = animeTitle
        binding.toolbarEpisodeLainnya.setTintedNavigationIcon(R.drawable.ic_arrow_back_black_24dp)
        binding.toolbarEpisodeLainnya.setNavigationOnClickListener { finish() }

        adapter = EpisodeAdapter { episode ->
            openPlayer(episode)
        }
        binding.recyclerEpisodeLainnya.apply {
            layoutManager = GridLayoutManager(
                this@EpisodeActivity,
                if (resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
                ) 8 else 4
            )
            adapter = this@EpisodeActivity.adapter
        }

        binding.swipeContainereeee.setOnRefreshListener { load() }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            // v2.0.11 (delta F-3): gerbang MATI — jangan minta /detail.
            // Jalur pemulihan proses: bila proses ini belum pernah cek,
            // paksa satu kali cek dulu (F-1), lalu putuskan.
            KillSwitch.ensureCheckedInProcess(app())
            if (KillSwitch.isDead()) {
                MainActivity.startDead(this@EpisodeActivity)
                finish()
                return@launch
            }
            try {
                val data = app().sankaRepository.detail(animeSlug)
                detail = data
                // Gerbang Ecchi (kunci ketiganya — Ecchi + Erotica + Hentai):
                // halaman ini tidak dibuka dari Detail yang terkunci — tapi
                // tetap tutup bila gerbang belum terbuka.
                val ecchi = data.genreSlugs.any { genre ->
                    Constants.LOCKED_GENRES.any { it.equals(genre, ignoreCase = true) }
                }
                val unlocked = app().authRepository.isSignedIn() &&
                    prefs.ecchiEnabled == "1"
                if (ecchi && !unlocked) {
                    toast(getString(R.string.ecchi_perlu_login))
                    finish()
                    return@launch
                }
                val episodes = data.episodes.orEmpty()
                    .map { it.number }
                    .filter { it > 0 }
                    .distinct()
                    .sorted()
                adapter.submitList(episodes)
            } catch (e: RateLimitException) {
                toast(getString(R.string.terlalu_banyak_permintaan))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toastSankaError(e)
            } finally {
                binding.swipeContainereeee.isRefreshing = false
            }
        }
    }

    private fun openPlayer(episode: Int) {
        // v2.0.11: jangan buka player bila saklar MATI — kembali ke
        // error state Beranda (bukan toast + diam).
        if (KillSwitch.isDead()) {
            MainActivity.startDead(this)
            finish()
            return
        }
        // Slug episode ASLI dari respons detail — jangan dirangkai sendiri.
        // v2.0.10 item 1: daftar episode bisa berisi DUA entri bernomor
        // sama (entri "Episode Spesial" di depan, episode asli di
        // belakang) — pilih lewat pickEpisodeSlug, BUKAN entri pertama
        // mentah (itu yang membuat "Episode 1" One Piece memutar konten
        // Wano/Spesial di v2.0.9).
        val episodeSlug = SankaRepository.pickEpisodeSlug(
            detail?.episodes.orEmpty().map { it.number to it.slug }, episode
        )
        if (episodeSlug == null) {
            toast(getString(R.string.gagal_memutar, episode))
            return
        }
        VideoPlayActivity.start(
            this, animeSlug, animeTitle, animePoster, episode, episodeSlug,
            isMovie = detail?.type == "Movie"
        )
    }
}
