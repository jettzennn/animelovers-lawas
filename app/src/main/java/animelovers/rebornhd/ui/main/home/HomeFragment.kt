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
import animelovers.rebornhd.data.model.Anime
import animelovers.rebornhd.databinding.FragmentHomeBinding
import animelovers.rebornhd.ui.adapter.InfoAdapter
import animelovers.rebornhd.ui.adapter.MostViewAdapter
import animelovers.rebornhd.ui.adapter.SlideAdapter
import animelovers.rebornhd.ui.adapter.UpdateAdapter
import animelovers.rebornhd.ui.detail.DetailActivity
import animelovers.rebornhd.ui.episode.LainnyaActivity
import animelovers.rebornhd.ui.main.MainActivity
import animelovers.rebornhd.util.Constants
import animelovers.rebornhd.util.isOnline
import animelovers.rebornhd.util.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Beranda — adaptasi fragment_home asli.
 * Slider (auto-scroll 3 detik) + Episode Baru (grid 3, 9 item + Lainnya)
 * + Terlaris (horizontal, 10 teratas) + Info (DISEMBUNYIKAN di v1: tidak
 * ada pengumuman dari backend).
 *
 * "Terlaris" = urutan skor tertinggi — Anikoto tidak memberi jumlah tonton
 * (proksi, didokumentasikan di MostViewAdapter).
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var slideAdapter: SlideAdapter
    private lateinit var updateAdapter: UpdateAdapter
    private lateinit var mostViewAdapter: MostViewAdapter
    private lateinit var infoAdapter: InfoAdapter

    private var recent: List<Anime> = emptyList()
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

        // Info: tidak ada pengumuman di v1 → seksi disembunyikan seluruhnya.
        binding.layoutInfo.visibility = View.GONE

        slideAdapter = SlideAdapter { openDetail(it) }
        binding.simpleAdapterViewFlipper.adapter = slideAdapter
        binding.simpleAdapterViewFlipper.isUserInputEnabled = true

        updateAdapter = UpdateAdapter { openDetail(it) }
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

        infoAdapter = InfoAdapter()
        binding.recyclerViewInfo.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = infoAdapter
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

    private fun openDetail(anime: Anime) {
        DetailActivity.start(requireContext(), anime.id.toString(), anime.displayTitle, anime.poster)
    }

    private fun load() {
        if (!requireContext().isOnline()) {
            binding.swipeContainerHome.isRefreshing = false
            requireContext().toast(getString(R.string.tidak_ada_koneksi))
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                recent = requireContext().app().animeRepository.recentAnime(1)
                applyData()
            } catch (e: Exception) {
                requireContext().toast(getString(R.string.gagal_memuat))
            } finally {
                binding.swipeContainerHome.isRefreshing = false
            }
        }
    }

    private fun applyData() {
        val unlocked = (activity as? MainActivity)?.isEcchiUnlocked() ?: false
        lastEcchiUnlocked = unlocked
        val visible = requireContext().app().animeRepository.visibleAnimes(recent, unlocked)
        slideAdapter.submitList(visible.take(5))
        updateAdapter.submitList(visible.take(9))
        val mostView = visible
            .sortedByDescending { it.score.toDoubleOrNull() ?: 0.0 }
            .take(10)
        mostViewAdapter.submitList(mostView)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
