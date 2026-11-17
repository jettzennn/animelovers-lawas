package animelovers.rebornhd.ui.main.browse

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import animelovers.rebornhd.R
import animelovers.rebornhd.app
import animelovers.rebornhd.data.model.Anime
import animelovers.rebornhd.databinding.FragmentBrowseBinding
import animelovers.rebornhd.ui.adapter.BrowseGridAdapter
import animelovers.rebornhd.ui.adapter.BrowseListAdapter
import animelovers.rebornhd.ui.detail.DetailActivity
import animelovers.rebornhd.ui.main.MainActivity
import animelovers.rebornhd.util.toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Jelajahi — adaptasi BrowseFragment asli (menu_browser: Spinner genre +
 * SearchView + toggle list/grid).
 *
 * Katalog dimuat halaman demi halaman dari /recent-anime (471 halaman) dan
 * difilter LOKAL (genre + judul) — pola asli: katalog dimuat sekaligus lalu
 * filter lokal. Pencarian HANYA menyaring halaman yang sudah termuat
 * (dijelaskan di laporan; sinkronisasi penuh 471 halaman berjalan progresif
 * di latar belakang, bukan instan).
 *
 * Genre Ecchi disembunyikan dari spinner + hasil bila gerbang belum terbuka.
 */
class BrowseFragment : Fragment() {

    private var _binding: FragmentBrowseBinding? = null
    private val binding get() = _binding!!

    private lateinit var gridAdapter: BrowseGridAdapter
    private lateinit var listAdapter: BrowseListAdapter

    private val allItems = mutableListOf<Anime>()
    private val genres = mutableListOf<String>()
    private var genreFilter: String? = null
    private var query: String = ""
    // Tampilan default "list" (pola asli, pref browslist) — toggle menyimpan pilihan.
    private var isGrid: Boolean = false
    private var spinnerReady = false
    private var loadJob: Job? = null
    private var loadingDone = false

    private val onAnimeClick: (Anime) -> Unit = { anime ->
        DetailActivity.start(requireContext(), anime.id.toString(), anime.displayTitle, anime.poster)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBrowseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        gridAdapter = BrowseGridAdapter(onAnimeClick)
        listAdapter = BrowseListAdapter(onAnimeClick)
        isGrid = requireContext().app().prefs.browslist == "grid"
        applyLayoutManager()

        binding.recyclerViewBrowse.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val lm = recyclerView.layoutManager
                val firstVisible = when (lm) {
                    is GridLayoutManager -> lm.findFirstVisibleItemPosition()
                    is LinearLayoutManager -> lm.findFirstVisibleItemPosition()
                    else -> 0
                }
                binding.gotop.visibility = if (firstVisible > 5) View.VISIBLE else View.GONE
            }
        })
        binding.gotop.setOnClickListener {
            binding.recyclerViewBrowse.smoothScrollToPosition(0)
        }

        binding.swipeContainer.setOnRefreshListener { reload() }

        setupToolbar()
        load()
    }

    private fun setupToolbar() {
        val toolbar = binding.toolbarBrowser
        toolbar.inflateMenu(R.menu.menu_browser)
        val menu: Menu = toolbar.menu
        // Ikon toggle mengikuti tampilan saat ini (list = tawarkan grid, dst.).
        menu.findItem(R.id.action_list_to_grid).setIcon(
            if (isGrid) R.drawable.list_view else R.drawable.ic_grid_view
        )

        // Spinner genre (action view).
        val spinner = menu.findItem(R.id.miSpinner).actionView as Spinner
        val spinnerAdapter = ArrayAdapter(
            requireContext(),
            R.layout.item_spinner,
            genres
        ).apply { setDropDownViewResource(R.layout.item_spinner_dropdown) }
        spinner.adapter = spinnerAdapter
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                if (!spinnerReady) {
                    // Pilihan pertama saat setup — abaikan.
                    spinnerReady = true
                    return
                }
                genreFilter = if (pos == 0) null else genres[pos]
                applyFilter()
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }

        // Pencarian.
        val searchView = menu.findItem(R.id.action_search).actionView as SearchView
        searchView.queryHint = getString(R.string.cari_anime)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?): Boolean {
                query = q.orEmpty()
                applyFilter()
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                query = newText.orEmpty()
                applyFilter()
                return true
            }
        })

        // Toggle list/grid.
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_list_to_grid) {
                isGrid = !isGrid
                requireContext().app().prefs.browslist = if (isGrid) "grid" else "list"
                item.setIcon(
                    if (isGrid) R.drawable.list_view else R.drawable.ic_grid_view
                )
                applyLayoutManager()
                applyFilter()
                true
            } else {
                false
            }
        }
    }

    private fun applyLayoutManager() {
        binding.recyclerViewBrowse.layoutManager =
            if (isGrid) GridLayoutManager(requireContext(), 3)
            else LinearLayoutManager(requireContext())
        binding.recyclerViewBrowse.adapter = if (isGrid) gridAdapter else listAdapter
    }

    private fun reload() {
        loadJob?.cancel()
        allItems.clear()
        genres.clear()
        genres.add(getString(R.string.semua_genre))
        loadingDone = false
        applyFilter()
        load()
    }

    private fun load() {
        val repo = requireContext().app().animeRepository
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                repo.loadCatalogPages(
                    onPage = { page ->
                        allItems.addAll(page)
                        rebuildGenres()
                        applyFilter()
                    },
                    isActive = { isAdded && _binding != null }
                )
            } catch (e: Exception) {
                if (isAdded) {
                    requireContext().toast(getString(R.string.gagal_memuat))
                }
            } finally {
                loadingDone = true
                if (isAdded) {
                    binding.swipeContainer.isRefreshing = false
                    applyFilter()
                }
            }
        }
    }

    /** Genre unik dari katalog yang sudah termuat; Ecchi disembunyikan bila terkunci. */
    private fun rebuildGenres() {
        val unlocked = (activity as? MainActivity)?.isEcchiUnlocked() ?: false
        val visible = repoVisible(allItems, unlocked)
        val found = visible.flatMap { it.genres }.distinct().sorted()
        val selected = genreFilter
        genres.clear()
        genres.add(getString(R.string.semua_genre))
        genres.addAll(found)
        (binding.toolbarBrowser.menu.findItem(R.id.miSpinner).actionView as? Spinner)
            ?.let { spinner ->
                (spinner.adapter as? ArrayAdapter<*>)?.notifyDataSetChanged()
                // Pertahankan pilihan bila genre masih ada.
                val index = if (selected == null) 0 else genres.indexOf(selected).coerceAtLeast(0)
                if (spinner.selectedItemPosition != index) {
                    spinner.setSelection(index, false)
                }
            }
    }

    private fun repoVisible(list: List<Anime>, unlocked: Boolean): List<Anime> =
        requireContext().app().animeRepository.visibleAnimes(list, unlocked)

    private fun applyFilter() {
        if (_binding == null) return
        val unlocked = (activity as? MainActivity)?.isEcchiUnlocked() ?: false
        var filtered = repoVisible(allItems, unlocked)
        genreFilter?.let { genre ->
            filtered = filtered.filter { anime ->
                anime.genres.any { it.equals(genre, ignoreCase = true) }
            }
        }
        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            filtered = filtered.filter { anime ->
                anime.displayTitle.lowercase().contains(q) ||
                    anime.title.lowercase().contains(q)
            }
        }
        if (isGrid) gridAdapter.submitList(filtered) else listAdapter.submitList(filtered)
        binding.emptyBrowse.visibility =
            if (filtered.isEmpty() && loadingDone) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        super.onDestroyView()
        _binding = null
    }
}
