package animelovers.rebornhd.ui.main

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import animelovers.rebornhd.R
import animelovers.rebornhd.app
import animelovers.rebornhd.databinding.ActivityMainBinding
import animelovers.rebornhd.ui.BaseActivity
import animelovers.rebornhd.ui.main.browse.BrowseFragment
import animelovers.rebornhd.ui.main.history.HistoryFragment
import animelovers.rebornhd.ui.main.home.HomeFragment
import animelovers.rebornhd.util.toast
import com.google.android.material.tabs.TabLayoutMediator

/**
 * Main — 3 tab datar (Beranda / Jelajahi / Riwayat), tanpa drawer/bottom-nav.
 * ViewPager2 + TabLayout, offscreenPageLimit 10 seperti aslinya (ketiga
 * fragment selalu hidup). Tekan Back 2× untuk keluar.
 */
class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private var lastBackPress = 0L

    /** Gerbang Ecchi terbuka bila sudah login DAN toggle Ecchi aktif. */
    fun isEcchiUnlocked(): Boolean {
        val auth = app().authRepository
        return auth.isSignedIn() && prefs.ecchiEnabled == "1"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v2.0.5: tonggak ukur rantai render Beranda (D2) — lihat
        // AnimLoversTiming di logcat (T+0 = Splash onCreate).
        Log.d("AnimLoversTiming", "T+${SystemClock.elapsedRealtime()} Main onCreate")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // v2.0.3: tempel WebView transport Sanka ke window — WebView yang
        // tidak pernah ditempel ke layar tidak memicu onPageFinished di
        // sebagian HP (Vivo), sehingga Beranda/Jelajahi muter terus
        // di v2.0.2. Dipanggil tiap onCreate supaya rotasi ikut pindah.
        app().sankaTransport.attachToWindow(this)

        binding.viewpager.adapter = TabsAdapter(this)
        // v2.0.9 #9 — BUG GESER PINDAH TAB: swipe antar tab DIMATIKAN.
        // ViewPager2 tidak seperti ViewPager v1 asli: ia mencegat gestur
        // horizontal hanya berdasarkan touch slop, tanpa memeriksa apakah
        // anak di bawah jari masih bisa digeser. Akibatnya geser di baris
        // yang sudah mentok — dan SEMUA geser manual di carousel hero
        // (ViewPager2 di dalam ViewPager2, slop sama) — direbut tab dan
        // pindah ke Jelajahi. Navigasi tab tetap lewat ketuk TabLayout
        // (TabLayoutMediator) — cara yang dipakai Faygo di HP.
        binding.viewpager.isUserInputEnabled = false
        // Seperti aslinya: semua tab selalu hidup.
        // v2.0.5 (D3 — DIPUTUSKAN Frontend setelah analisis, bukan malas
        // dimuat): Jelajahi TIDAK dibuat malas. Alasannya: (a) BrowseFragment
        // memanggil repo.home() yang sama dan SUDAH di-cache + dedup
        // Mutex per kunci (SankaRepository.cached) — Home + Jelajahi hanya
        // menghasilkan SATU request jaringan, jadi Jelajahi tidak menambah
        // beban jaringan di jalur kritis Beranda; (b) offscreenPageLimit=1
        // pun tidak menunda Jelajahi — ViewPager2 selalu membuat halaman
        // tetangga (Beranda 0 → Jelajahi 1), jadi "malas" via limit tidak
        // berpengaruh apa-apa; (c) malas yang sesungguhnya (load hanya saat
        // tab dipilih) justru membuat Jelajahi kosong saat pertama dibuka
        // — persis yang dilarang D3. Yang dipotong hanya delay Splash (D2).
        // v2.0.7 #4 — KOREKSI sebagian dari D3 (riset Q3): alasan (a) benar
        // untuk JSON, tapi SALAH untuk poster — load() BrowseFragment
        // mengikat ~12 poster yang langsung berebut bandwidth dengan
        // Beranda. Yang kini ditunda hanya MUAT PERTAMA (poster) sampai tab
        // dipilih — BrowseFragment.onResume, sekali saja (hasLoadedOnce).
        // JSON tetap dari cache home() (nol request tambahan), dan
        // offscreenPageLimit TETAP 10 (tidak diubah). Jelajahi tidak kosong
        // saat pertama dibuka — posternya termuat begitu tab dipilih.
        binding.viewpager.offscreenPageLimit = 10
        TabLayoutMediator(binding.tabs, binding.viewpager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_beranda)
                1 -> getString(R.string.tab_jelajahi)
                else -> getString(R.string.tab_riwayat)
            }
        }.attach()
    }

    override fun onDestroy() {
        // v2.0.3: lepas WebView transport dari window SEBELUM super —
        // decor view masih menempel di sini, jadi removeView berhasil;
        // tanpa ini WebView tertinggal di decor Activity yang sudah mati.
        app().sankaTransport.attachToWindow(null)
        super.onDestroy()
    }

    override fun onBackPressed() {
        val now = System.currentTimeMillis()
        if (now - lastBackPress < 2000) {
            super.onBackPressed()
        } else {
            lastBackPress = now
            toast(getString(R.string.tekan_kembali_lagi))
        }
    }

    private inner class TabsAdapter(activity: MainActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 3

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> HomeFragment()
            1 -> BrowseFragment()
            else -> HistoryFragment()
        }
    }
}
