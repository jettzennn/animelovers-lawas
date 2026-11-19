package animelovers.rebornhd.ui.main.home

import android.os.Bundle
import android.os.SystemClock
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
import animelovers.rebornhd.util.DiagLog
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
            // v2.0.7 #9: nested scrolling WAJIB aktif — RecyclerView
            // horizontal di dalam ViewPager2 (yang juga horizontal). Bila
            // dimatikan, RecyclerView tidak pernah memanggil
            // requestDisallowInterceptTouchEvent saat digeser, jadi ViewPager2
            // mencegat gestur horizontal dan PINDAH TAB (ke Jelajahi).
            // Dengan nested scrolling aktif, geser horizontal dipegang baris
            // ini (kartu bergeser), geser vertikal tetap diteruskan ke
            // ScrollView induk — perilaku normal keduanya.
            isNestedScrollingEnabled = true
        }

        mostViewAdapter = MostViewAdapter { openDetail(it) }
        binding.recyclerViewMostview.apply {
            layoutManager = LinearLayoutManager(
                requireContext(), LinearLayoutManager.HORIZONTAL, false
            )
            adapter = mostViewAdapter
            // v2.0.7 #9: sama seperti recyclerViewUpdate di atas — tanpa
            // ini, geser kartu Terlaris pindah tab ke Jelajahi.
            isNestedScrollingEnabled = true
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
        // v2.0.6 #7: render dari CACHE dulu (bila ada, <10 menit) — Beranda
        // terisi instan di bukaan kedua; fetch segar tetap jalan di
        // belakang dan memperbarui tampilan bila selesai.
        // v2.0.7 #3: cache kini DUA lapis — memori (sinkron, di bawah)
        // + DISK (bila memori kosong: baca + parse JSON di IO, tanpa
        // jaringan — bukaan dingin setelah proses mati pun terisi instan;
        // fetch segar tetap jalan di belakang via homeFresh()).
        // Bila OFFLINE tapi cache ada → tampilkan cache tanpa toast (data
        // paling lama 10 menit — cukup segar); offline TANPA cache → toast
        // seperti dulu. Satu-satunya request di sini tetap repo.home() /
        // repo.homeFresh() (cache + dedup Mutex per kunci) — tidak ada
        // request tambahan, throttle 1,4 dtk tidak tersentuh.
        val repo = requireContext().app().sankaRepository
        val diagLogger = requireContext().app().diagLogger
        val start = SystemClock.elapsedRealtime()
        val memHome = repo.homeCached()
        if (memHome != null) {
            home = memHome
            applyData()
            diagLogger.info(
                DiagLog.TAG_HOME,
                "Beranda memori (${SystemClock.elapsedRealtime() - start}ms)"
            )
        }
        viewLifecycleOwner.lifecycleScope.launch {
            // Bukaan dingin (memori kosong): coba DISK dulu sebelum jaringan.
            if (home == null) {
                val diskHome = repo.homeDiskCached()
                if (diskHome != null) {
                    home = diskHome
                    applyData()
                    diagLogger.info(
                        DiagLog.TAG_HOME,
                        "Beranda disk (${SystemClock.elapsedRealtime() - start}ms)"
                    )
                }
            }
            if (!isAdded) return@launch
            if (!requireContext().isOnline()) {
                _binding?.swipeContainerHome?.isRefreshing = false
                if (home == null) {
                    requireContext().toast(getString(R.string.tidak_ada_koneksi))
                }
                return@launch
            }
            // Nyalakan spinner di awal — sebelumnya spinner hanya muncul saat
            // pull-to-refresh, jadi kegagalan instan terlihat tanpa tanda loading.
            binding.swipeContainerHome.isRefreshing = true
            try {
                // Bila tampilan berasal dari memori yang masih segar,
                // home() cukup (tanpa jaringan — datanya memang segar).
                // Bila dari disk / belum ada, homeFresh() memaksa fetch
                // jaringan — home() biasa akan mengembalikan data disk
                // (tanpa jaringan) dan tampilan tidak pernah diperbarui.
                home = if (memHome != null) repo.home() else repo.homeFresh()
                // v2.0.9 item 5: "jaringan" HANYA bila fetch jaringan
                // benar-benar jalan (memHome == null → homeFresh()).
                // Bila memHome != null, repo.home() di atas hanya baca
                // cache memori — tidak ada baris "jaringan" di sini
                // (baris "memori" sudah dicatat di atas).
                val ms = SystemClock.elapsedRealtime() - start
                if (memHome == null) {
                    diagLogger.info(DiagLog.TAG_HOME, "Beranda jaringan (${ms}ms)")
                    if (ms > DiagLog.SLOW_HOME_MS) {
                        diagLogger.warn(DiagLog.TAG_HOME, "Beranda lambat (${ms}ms)")
                    }
                }
                applyData()
            } catch (e: RateLimitException) {
                // Pesan rate limit persis (desain.md §14.3) — jangan
                // disamarkan jadi "Gagal memuat".
                diagLogger.error(DiagLog.TAG_HOME, "Beranda 429")
                requireContext().toast(getString(R.string.terlalu_banyak_permintaan))
            } catch (e: CancellationException) {
                // Pembatalan (layar ditinggalkan) harus lolos — jangan
                // ditelan jadi toast "Gagal memuat".
                throw e
            } catch (e: Exception) {
                diagLogger.error(
                    DiagLog.TAG_HOME,
                    "Beranda gagal (${e.javaClass.simpleName})"
                )
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
