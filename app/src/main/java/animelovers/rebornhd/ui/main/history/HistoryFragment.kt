package animelovers.rebornhd.ui.main.history

import android.app.DownloadManager
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import animelovers.rebornhd.app
import animelovers.rebornhd.data.local.entity.FavoriteEntity
import animelovers.rebornhd.data.local.entity.VideoEntity
import animelovers.rebornhd.databinding.FragmentHistoryBinding
import animelovers.rebornhd.ui.adapter.FavoritesHeaderAdapter
import animelovers.rebornhd.ui.adapter.HistoryAdapter
import animelovers.rebornhd.ui.detail.DetailActivity
import animelovers.rebornhd.ui.settings.SettingActivity
import kotlinx.coroutines.launch

/**
 * Riwayat — adaptasi fragment_fav asli.
 * Blok Request DIHAPUS (PRD §12 poin 7).
 * Seksi Favorit [BARU] di atas daftar riwayat (ConcatAdapter).
 * Tombol unduhan → aplikasi Unduhan sistem (DownloadManager).
 */
class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private lateinit var favoritesAdapter: FavoritesHeaderAdapter
    private lateinit var historyAdapter: HistoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        favoritesAdapter = FavoritesHeaderAdapter { anime ->
            val slug = anime.slug ?: return@FavoritesHeaderAdapter
            DetailActivity.start(
                requireContext(), slug, anime.displayTitle, anime.posterUrl
            )
        }
        historyAdapter = HistoryAdapter(
            onClick = { item: VideoEntity ->
                DetailActivity.start(requireContext(), item.idtitle, item.title, item.idposter)
            },
            onDelete = { item: VideoEntity ->
                viewLifecycleOwner.lifecycleScope.launch {
                    requireContext().app().sankaRepository.deleteHistory(item.idui)
                    load()
                }
            }
        )
        binding.recyclerViewHistory.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = ConcatAdapter(favoritesAdapter, historyAdapter)
        }

        binding.linearLayout2.setOnClickListener {
            startActivity(Intent(requireContext(), SettingActivity::class.java))
        }
        binding.downloadTab.setOnClickListener {
            startActivity(
                Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        binding.swipeContainerFav.setOnRefreshListener { load() }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            val repo = requireContext().app().sankaRepository
            val favorites: List<FavoriteEntity> = repo.getFavorites()
            val history: List<VideoEntity> = repo.getHistory()
            favoritesAdapter.submitList(favorites)
            historyAdapter.submitList(history)
            binding.emptyHistory.visibility =
                if (favorites.isEmpty() && history.isEmpty()) View.VISIBLE else View.GONE
            binding.swipeContainerFav.isRefreshing = false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
