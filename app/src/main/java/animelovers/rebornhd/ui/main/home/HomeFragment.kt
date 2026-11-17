package animelovers.rebornhd.ui.main.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import animelovers.rebornhd.R
import animelovers.rebornhd.app
import animelovers.rebornhd.data.api.RateLimitException
import animelovers.rebornhd.data.model.SankaAnime
import animelovers.rebornhd.data.model.SankaEpisodeItem
import animelovers.rebornhd.data.model.SankaHome
import animelovers.rebornhd.databinding.FragmentHomeBinding
import animelovers.rebornhd.ui.adapter.MostViewAdapter
import animelovers.rebornhd.ui.adapter.SlideAdapter
import animelovers.rebornhd.ui.adapter.UpdateAdapter
import animelovers.rebornhd.ui.detail.DetailActivity
import animelovers.rebornhd.ui.episode.LainnyaActivity
import animelovers.rebornhd.ui.main.MainActivity
import animelovers.rebornhd.util.Constants
import animelovers.rebornhd.util.isOnline
import animelovers.rebornhd.util.toast
import animelovers.rebornhd.util.toastSankaError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Beranda — adaptasi fragment_home asli.
 * Slider (auto-scroll 3 detik) + Episode Baru (horizontal, 9 item + Lainnya)
 * + Terlaris (horizontal, 10 teratas). Info DIHAPUS di v2 (desain.md §14).
 *
 * Sumber (Sanka, satu request /home):
 * - Slider = home.featured (5 pertama).
 * - Episode Baru = home.latestEpisodes (item EPISODE: judul "… Episode N",
 *   ketuk → Detail anime-nya; disaring Ecchi dengan mencocokkan animeSlug
 *   ke daftar ber-genre di respons home yang sama — anime yang tidak ada
 *   di daftar itu tetap tampil, playback-nya tetap dikunci Detail).
 * - Terlaris = home.popular (popularWeekly, lalu popularAllTime) —
 *   badge jumlah tonton hanya bila Sanka menyediakannya (views > 0).
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var slideAdapter: SlideAdapter
    private lateinit var updateAdapter: UpdateAdapter
    private lateinit var mostViewAdapter: MostViewAdapter

    private var home: SankaHome? = null
    private var lastEcchiUnlocked: Boolean? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        slideAdapter = SlideAdapter { openDetail(it) }
        binding.simpleAdapterViewFlipper.adapter = slideAdapter
        binding.simpleAdapterViewFlipper.isUserInputEnabled = true

        updateAdapter = UpdateAdapter { openDetailFromEpisode(it) }
        binding.recyclerViewUpdate.apply {
            // Horizontal (desain.md §4.1) — seperti aslinya.
            layoutManager = LinearLayoutManager(
                requireContext(), LinearLayoutManager.HORIZONTAL, false
            )
            adapter = updateAdapter
            isNestedScrollingEnabled = false
        }

        mostViewAdapter = MostViewAdapter { openDetail(it) }
        binding.recyclerViewMostview.apply {
            layoutManager = LinearLayoutManager(
                requireContext(), LinearLayoutManager.HORIZONTAL, false
            )
            adapter = mostViewAdapter
            isNestedScrollingEnabled = false
        }

        binding.lainnyaupdate.setOnClickListener {
            LainnyaActivity.start(requireContext(), LainnyaActivity.MODE_UPDATE)
        }
        binding.lainnyamostview.setOnClickListener {
            LainnyaActivity.start(requireContext(), LainnyaActivity.MODE_MOSTVIEW)
        }

        binding.swipeContainerHome.setOnRefreshListener { load() }

        // Auto-scroll slider tiap 3 detik selama fragment terlihat.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(Constants.SLIDER_INTERVAL_MS)
                    val count = slideAdapter.itemCount
                    if (count > 1) {
                        val next = (binding.simpleAdapterViewFlipper.currentItem + 1) % count
                        binding.simpleAdapterViewFlipper.setCurrentItem(next, true)
                    }
                }
            }
        }

        load()
    }

    override fun onResume() {
        super.onResume()
        // Bila gerbang Ecchi berubah (pulang dari Pengaturan), saring ulang.
        val unlocked = (activity as? MainActivity)?.isEcchiUnlocked()
        if (unlocked != null && lastEcchiUnlocked != null && unlocked != lastEcchiUnlocked) {
            applyData()
        }
        lastEcchiUnlocked = unlocked
    }

    private fun openDetail(anime: SankaAnime) {
        val slug = anime.slug ?: return
        DetailActivity.start(requireContext(), slug, anime.displayTitle, anime.posterUrl)
    }

    private fun openDetailFromEpisode(item: SankaEpisodeItem) {
        val slug = item.animeSlug ?: return
        DetailActivity.start(
            requireContext(),
            slug,
            item.animeTitle ?: item.title ?: "",
            item.posterUrl
        )
    }

    private fun load() {
        if (!requireContext().isOnline()) {
            binding.swipeContainerHome.isRefreshing = false
            requireContext().toast(getString(R.string.tidak_ada_koneksi))
            return
        }
        // Nyalakan spinner di awal — sebelumnya spinner hanya muncul saat
        // pull-to-refresh, jadi kegagalan instan terlihat tanpa tanda loading.
        binding.swipeContainerHome.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                home = requireContext().app().sankaRepository.home()
                applyData()
            } catch (e: RateLimitException) {
                // Pesan rate limit persis (desain.md §14.3) — jangan
                // disamarkan jadi "Gagal memuat".
                requireContext().toast(getString(R.string.terlalu_banyak_permintaan))
            } catch (e: CancellationException) {
                // Pembatalan (layar ditinggalkan) harus lolos — jangan
                // ditelan jadi toast "Gagal memuat".
                throw e
            } catch (e: Exception) {
                requireContext().toastSankaError(e)
            } finally {
                if (isAdded) {
                    _binding?.swipeContainerHome?.isRefreshing = false
                }
            }
        }
    }

    private fun applyData() {
        val data = home ?: return
        val repo = requireContext().app().sankaRepository
        val unlocked = (activity as? MainActivity)?.isEcchiUnlocked() ?: false
        lastEcchiUnlocked = unlocked
        val featured = repo.visibleAnimes(data.featured.orEmpty(), unlocked)
        slideAdapter.submitList(featured.take(5))
        val latest = repo.visibleEpisodes(data.latestEpisodes.orEmpty(), data, unlocked)
        updateAdapter.submitList(latest.take(9))
        val popular = repo.visibleAnimes(data.popular, unlocked)
        mostViewAdapter.submitList(popular.take(10))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
