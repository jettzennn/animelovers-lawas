package animelovers.rebornhd.ui.main.browse

import android.os.Bundle
import android.os.SystemClock
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
import animelovers.rebornhd.data.api.RateLimitException
import animelovers.rebornhd.data.model.SankaAnime
import animelovers.rebornhd.data.model.SankaGenre
import animelovers.rebornhd.databinding.FragmentBrowseBinding
import animelovers.rebornhd.ui.adapter.BrowseGridAdapter
import animelovers.rebornhd.ui.adapter.BrowseListAdapter
import animelovers.rebornhd.ui.detail.DetailActivity
import animelovers.rebornhd.ui.main.MainActivity
import animelovers.rebornhd.util.DiagError
import animelovers.rebornhd.util.DiagLog
import animelovers.rebornhd.util.toast
import animelovers.rebornhd.util.toastSankaError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Jelajahi — adaptasi BrowseFragment asli (menu_browser: Spinner genre +
 * SearchView + toggle list/grid).
 *
 * v2 (Sanka): pencarian + genre DILAKUKAN SERVER-SIDE — tidak ada lagi
 * katalog 471 halaman yang dimuat lalu difilter lokal.
 * - Default (genre "Semua", tanpa query): daftar dari home (ongoing +
 *   completed + movies) — tanpa request tambahan (home di-cache).
 * - Genre dipilih → GET /anime/animekompi/genre/{slug} (halaman 1).
 * - Ketik judul → GET /anime/animekompi/search?q=… dengan debounce
 *   ~0,5 detik + spinner selama meminta (desain.md §14.6).
 * - Spinner genre diisi dari genre item yang sudah termuat ("Semua"
 *   langsung, genre lain menyusul seiring data masuk).
 * - Query kosong TIDAK memanggil /search (Sanka menjawab 400).
 *
 * Genre Ecchi disembunyikan dari spinner + hasil bila gerbang belum terbuka.
 * Rate limit (429) ditampilkan dengan pesan persis, tanpa retry membabi buta.
 */
class BrowseFragment : Fragment() {

    private var _binding: FragmentBrowseBinding? = null
    private val binding get() = _binding!!

    private lateinit var gridAdapter: BrowseGridAdapter
    private lateinit var listAdapter: BrowseListAdapter

    /** Daftar yang sedang tampil (dari home / genre / search). */
    private var currentItems: List<SankaAnime> = emptyList()

    /** Opsi genre spinner (nama tampil + slug untuk /genre). */
    private val genreOptions = mutableListOf<SankaGenre>()
    private val genreNames = mutableListOf<String>()
    private var genreFilterSlug: String? = null
    private var query: String = ""

    // Tampilan default "list" (pola asli, pref browslist) — toggle menyimpan pilihan.
    private var isGrid: Boolean = false
    private var spinnerReady = false
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var loadingDone = false

    /**
     * Penanda muat pertama (v2.0.7 #4) — true setelah load() pertama
     * dijalankan dari onResume (tab pertama kali dipilih). onResume juga
     * terpanggil saat kembali dari Detail — tanpa penanda ini, tiap
     * kembali akan memuat ulang.
     */
    private var hasLoadedOnce = false

    /** SearchView di toolbar — disimpan agar reload() bisa mereset teksnya. */
    private var searchView: SearchView? = null

    /**
     * Penahan callback query saat reload() mereset teks SearchView —
     * tanpa ini, setQuery("") memicu onQueryTextChange → refresh()
     * kedua (duplikat load yang sudah dijalankan reload()).
     */
    private var suppressQueryCallback: Boolean = false

    private val onAnimeClick: (SankaAnime) -> Unit = { anime ->
        anime.slug?.let { slug ->
            DetailActivity.start(requireContext(), slug, anime.displayTitle, anime.posterUrl)
        }
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

        genreNames.add(getString(R.string.semua_genre))
        setupToolbar()
        // v2.0.7 #4: JANGAN load() di sini — fragment ini dibuat offscreen
        // sejak awal (offscreenPageLimit=10), dan load() mengikat poster
        // (~12) yang langsung berebut bandwidth dengan Beranda. Muat
        // pertama ditunda ke onResume (lihat di bawah): ViewPager2 hanya
        // me-RESUME fragment yang tampil.
    }

    override fun onResume() {
        super.onResume()
        // Visibility-gating (v2.0.7 #4): muat pertama HANYA saat tab
        // Jelajahi pertama kali dipilih (fragment di-RESUME oleh ViewPager2
        // saat menjadi tab aktif; saat offscreen hanya STARTED). Poster
        // Jelajahi termuat saat itu — bukan saat aplikasi dibuka.
        // JSON tetap dari cache home() (nol request tambahan); yang ditunda
        // hanya pengikatan poster ke RecyclerView.
        if (!hasLoadedOnce) {
            load()
        }
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
            genreNames
        ).apply { setDropDownViewResource(R.layout.item_spinner_dropdown) }
        spinner.adapter = spinnerAdapter
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                if (!spinnerReady) {
                    // Pilihan pertama saat setup — abaikan.
                    spinnerReady = true
                    return
                }
                genreFilterSlug = if (pos == 0) null else genreOptions.getOrNull(pos - 1)?.slug
                refresh()
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }

        // Pencarian server-side (debounce ~0,5 detik).
        val searchView = menu.findItem(R.id.action_search).actionView as SearchView
        this.searchView = searchView
        searchView.queryHint = getString(R.string.cari_anime)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?): Boolean {
                searchJob?.cancel()
                query = q.orEmpty()
                refresh()
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                if (suppressQueryCallback) return true
                val text = newText.orEmpty()
                searchJob?.cancel()
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(500L)
                    if (!isAdded) return@launch
                    query = text
                    refresh()
                }
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
        searchJob?.cancel()
        query = ""
        genreFilterSlug = null
        loadingDone = false
        // Reset teks SearchView juga (sebelumnya query/genre direset tapi
        // teks pencarian lama tetap tampil — menyesatkan).
        suppressQueryCallback = true
        searchView?.setQuery("", false)
        suppressQueryCallback = false
        load()
    }

    /** Muat daftar default (home) — tanpa request tambahan bila home di-cache. */
    private fun load() {
        // Observasi O-1 (Cari Bug v2.0.7): batalkan load() sebelumnya
        // supaya dua load() tidak berjalan bersamaan (mis. onResume +
        // pull-to-refresh berkejaran — hasil lama bisa menimpa hasil baru).
        loadJob?.cancel()
        val repo = requireContext().app().sankaRepository
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            binding.swipeContainer.isRefreshing = true
            try {
                currentItems = repo.home().browseDefault
                rebuildGenres()
                applyFilter()
                // Perbaikan L-1 (Cari Bug v2.0.7): flag hanya diset di
                // jalur SUKSES — bila muat pertama gagal (offline /
                // lambat / 429), onResume berikutnya mencoba lagi.
                // Sebelumnya flag diset sebelum load() jalan, jadi tab
                // tetap kosong sampai pull-to-refresh.
                hasLoadedOnce = true
            } catch (e: RateLimitException) {
                if (isAdded) {
                    requireContext().toast(getString(R.string.terlalu_banyak_permintaan))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isAdded) {
                    requireContext().toastSankaError(e)
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

    /**
     * Satu titik masuk untuk pencarian/genre: query non-kosong → search;
     * bila tidak, genre terpilih → /genre; bila tidak ada keduanya →
     * daftar default (home). Request lama dibatalkan (satu layar =
     * satu request berjalan).
     */
    private fun refresh() {
        val repo = requireContext().app().sankaRepository
        loadJob?.cancel()
        loadingDone = false
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            binding.swipeContainer.isRefreshing = true
            val t0 = SystemClock.elapsedRealtime()
            val kind = when {
                query.isNotBlank() -> "search"
                genreFilterSlug != null -> "genre"
                else -> "default"
            }
            try {
                currentItems = when {
                    query.isNotBlank() -> repo.search(query.trim())
                    genreFilterSlug != null -> repo.genre(genreFilterSlug!!)
                    else -> repo.home().browseDefault
                }
                // v2.0.9 item 5: hasil + durasi per jenis muatan.
                val ms = SystemClock.elapsedRealtime() - t0
                val logger = requireContext().app().diagLogger
                when (kind) {
                    "search" -> {
                        val q = query.trim().take(40)
                        logger.info(
                            DiagLog.TAG_JELAJAHI,
                            "Cari '$q': ${currentItems.size} hasil (${ms}ms)"
                        )
                        if (currentItems.isEmpty()) {
                            logger.warn(DiagLog.TAG_JELAJAHI, "Cari '$q': kosong")
                        }
                        if (ms > DiagLog.SLOW_SEARCH_MS) {
                            logger.warn(DiagLog.TAG_JELAJAHI, "Cari '$q': lambat (${ms}ms)")
                        }
                    }
                    "genre" -> {
                        val slug = genreFilterSlug ?: "?"
                        logger.info(
                            DiagLog.TAG_JELAJAHI,
                            "Genre '$slug': ${currentItems.size} hasil (${ms}ms)"
                        )
                        if (ms > DiagLog.SLOW_SEARCH_MS) {
                            logger.warn(
                                DiagLog.TAG_JELAJAHI,
                                "Genre '$slug': lambat (${ms}ms)"
                            )
                        }
                    }
                    else -> {
                        logger.info(
                            DiagLog.TAG_JELAJAHI,
                            "Daftar default: ${currentItems.size} item (${ms}ms)"
                        )
                    }
                }
                rebuildGenres()
                applyFilter()
            } catch (e: RateLimitException) {
                if (isAdded) {
                    requireContext().app().diagLogger.error(
                        DiagLog.TAG_JELAJAHI, "429 ($kind)"
                    )
                    requireContext().toast(getString(R.string.terlalu_banyak_permintaan))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isAdded) {
                    // v2.0.10 #6: baris gagal menyebut JENIS + query/genre
                    // (maks 40 karakter) — dulu hanya "gagal (u)": query
                    // yang gagal + penyebabnya tidak tercatat (video
                    // v2.0.9: query "one piece" gagal tanpa jejak). Pesan
                    // error juga tanpa simpleName (item 2).
                    val detail = when (kind) {
                        "search" -> "Cari '${query.trim().take(40)}': " +
                            "gagal (${DiagError.describe(e)})"
                        "genre" -> "Genre '${genreFilterSlug ?: "?"}': " +
                            "gagal (${DiagError.describe(e)})"
                        else -> "gagal (${DiagError.describe(e)})"
                    }
                    requireContext().app().diagLogger.error(
                        DiagLog.TAG_JELAJAHI, detail
                    )
                    requireContext().toastSankaError(e)
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

    /** Genre unik dari daftar yang sedang tampil; Ecchi disembunyikan bila terkunci. */
    private fun rebuildGenres() {
        val unlocked = (activity as? MainActivity)?.isEcchiUnlocked() ?: false
        val visible = requireContext().app().sankaRepository
            .visibleAnimes(currentItems, unlocked)
        val found = visible.flatMap { it.genres.orEmpty() }
            .filter { it.slug != null && it.name != null }
            .distinctBy { it.slug }
            .sortedBy { it.name }
        val selectedSlug = genreFilterSlug
        genreOptions.clear()
        genreOptions.addAll(found)
        genreNames.clear()
        genreNames.add(getString(R.string.semua_genre))
        genreNames.addAll(found.mapNotNull { it.name })
        (binding.toolbarBrowser.menu.findItem(R.id.miSpinner).actionView as? Spinner)
            ?.let { spinner ->
                (spinner.adapter as? ArrayAdapter<*>)?.notifyDataSetChanged()
                // Pertahankan pilihan bila genre masih ada.
                val index = if (selectedSlug == null) 0
                else (genreOptions.indexOfFirst { it.slug == selectedSlug } + 1)
                    .coerceAtLeast(0)
                if (spinner.selectedItemPosition != index) {
                    spinner.setSelection(index, false)
                }
            }
    }

    /** Hanya saringan Ecchi lokal — genre + query sudah disaring server. */
    private fun applyFilter() {
        if (_binding == null) return
        val unlocked = (activity as? MainActivity)?.isEcchiUnlocked() ?: false
        val filtered = requireContext().app().sankaRepository
            .visibleAnimes(currentItems, unlocked)
        if (isGrid) gridAdapter.submitList(filtered) else listAdapter.submitList(filtered)
        binding.emptyBrowse.visibility =
            if (filtered.isEmpty() && loadingDone) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        searchJob?.cancel()
        super.onDestroyView()
        _binding = null
    }
}
