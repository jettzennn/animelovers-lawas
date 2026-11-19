package animelovers.rebornhd

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import animelovers.rebornhd.data.api.SankaTransport
import animelovers.rebornhd.data.api.SankaWebTransport
import animelovers.rebornhd.data.auth.AuthRepository
import animelovers.rebornhd.data.auth.StubAuthRepository
import animelovers.rebornhd.data.local.AppDatabase
import animelovers.rebornhd.data.repo.PlayerRepository
import animelovers.rebornhd.data.repo.SamehadakuRepository
import animelovers.rebornhd.data.repo.SankaRepository
import animelovers.rebornhd.util.Constants
import animelovers.rebornhd.util.DiagLog
import animelovers.rebornhd.util.DiagLogger
import animelovers.rebornhd.util.DiagPosterListener
import animelovers.rebornhd.util.Prefs
import coil.ImageLoader
import coil.ImageLoaderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Application — satu tempat membuat dependensi (tanpa DI framework, PRD §3.2).
 *
 * Auth: [authRepository] saat ini [StubAuthRepository] (Firebase belum dipasang).
 * Bila proyek Firebase baru sudah dibuat (docs/firebase-setup.md), ganti
 * satu baris ini ke implementasi Firebase — UI + gerbang tidak berubah.
 *
 * v2: satu-satunya sumber konten adalah Sanka (sankaTransport +
 * sankaRepository). Anikoto/VidCloud/MegaPlay/backend kecil sudah dihapus.
 * Transport v2.0.2: WebView tersembunyi (Chrome asli) — OkHttp pensiun
 * (sidik TLS-nya diblokir Cloudflare dari IP HP Faygo).
 * v2.0.3: WebView DITEMPEL ke window MainActivity (attachToWindow di
 * MainActivity.onCreate) + UA tanpa penanda "Version/4.0" — v2.0.2 yang
 * tak tertempel tidak memicu onPageFinished di Vivo Faygo.
 *
 * v2.0.6 #4: Coil memakai ImageLoaderFactory ini — timeout lebih panjang
 * dari default (baca 10 dtk): connect 15 dtk / baca 30 dtk / tulis 15 dtk.
 * 30 dtk baca = sama dengan SANKA_READ_TIMEOUT_S (transport WebView) —
 * poster 66–147 KB di koneksi 1 KB/dtk tetap tidak akan selesai, tapi
 * poster tidak lagi mati di 10 detik pertama saat koneksi sempat
 * tersendat. JANGAN ubah SankaWebTransport untuk ini — ini hanya Coil.
 */
class AnimeloversApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // v2.0.5 (item 5): tema aplikasi mengikuti sistem HP
        // (HP dark → app dark, HP terang → app terang). Dipanggil di sini
        // — sebelum activity mana pun dibuat — supaya AppCompat ikut
        // memakai mode malam sistem. Pemilihan tema gelap/terang per
        // layar tetap lewat prefs.isDarkTheme (dibaca dari uiMode sistem).
        AppCompatDelegate.setDefaultNightMode(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        )

        // v2.0.9 item 2: tangkap crash agar jejaknya tersimpan di log
        // diagnostik (bukan cuma logcat yang hilang saat aplikasi ditutup).
        // Handler lama WAJIB di-chain — tanpa itu, proses tidak mati +
        // dialog crash sistem tidak muncul, aplikasi diam dalam keadaan rusak.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                diagLogger.logCrashSync(thread.name, throwable)
            } catch (e: Exception) {
                // Gagal tulis crash log — handler lama tetap jalan.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
        diagLogger.info(
            DiagLog.TAG_APP,
            "Aplikasi dimulai v${diagLogger.versionName} (${diagLogger.versionCode}) — " +
                diagLogger.deviceInfo
        )
    }

    /**
     * ImageLoader Coil aplikasi (v2.0.6 #4) — timeout baca 30 detik.
     *
     * v2.0.7 #1b: Coil 2.5.0 TIDAK retry otomatis — request poster yang
     * gagal (putus di tengah jalan) langsung jadi ikon error sampai rebind.
     * PosterRetryInterceptor mengulang SEKALI request yang gagal karena
     * jaringan / 5xx (bukan 4xx — file memang tidak ada, retry sia-sia),
     * dengan jeda 1 detik. Terbatas 1× supaya tidak menahan antrean lama.
     * v2.0.7 #1c: maxRequestsPerHost 3 — poster yang terlihat (slider →
     * Terlaris → Episode Baru, sesuai urutan bind) selesai dulu; konkurensi
     * 5 default membuat 5 poster berebut bandwidth yang sama dan semuanya
     * lambat.
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            // v2.0.9 item 5: SATU listener poster global — gagal selalu
            // WARN, sukses > 5 dtk WARN, sukses cepat tidak dicatat.
            .eventListener(posterListener)
            .okHttpClient {
                OkHttpClient.Builder()
                    .dispatcher(Dispatcher().apply { maxRequestsPerHost = 3 })
                    .addInterceptor(PosterRetryInterceptor())
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .build()
            }
            .build()
    }

    /** Scope aplikasi — untuk kerja yang harus selesai walau Activity sudah
     *  mati (mis. simpan posisi tonton di onDestroy player). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Logger diagnostik (v2.0.9): log lokal Konsol — TIDAK PERNAH terkirim otomatis. */
    val diagLogger: DiagLogger by lazy { DiagLogger(this) }

    /** Poster error listener global (v2.0.9 item 5) — SATU instance dipakai semua Coil. */
    private val posterListener: DiagPosterListener by lazy { DiagPosterListener(diagLogger) }

    val prefs: Prefs by lazy { Prefs(this) }
    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val sankaTransport: SankaTransport by lazy {
        SankaWebTransport(
            this,
            Constants.SANKA_BASE_URL,
            Constants.SANKA_MIN_INTERVAL_MS,
            Constants.SANKA_READ_TIMEOUT_S * 1000L
        )
    }

    val authRepository: AuthRepository by lazy { StubAuthRepository(this) }

    val sankaRepository: SankaRepository by lazy {
        // v2.0.7 #3: cache disk Beranda — filesDir (bertahan antar proses,
        // bukan cacheDir yang bisa dibersihkan sistem). TTL 10 menit,
        // diukur dari lastModified file (lihat SankaRepository).
        SankaRepository(
            sankaTransport, database.videoDao(), database.favoriteDao(),
            File(filesDir, "home_cache.json"), diagLogger
        )
    }

    val samehadakuRepository: SamehadakuRepository by lazy {
        // v2.0.7 item 16: adapter Samehadaku memakai TRANSPORT YANG SAMA
        // dengan SankaRepository (throttle + Mutex + timeout 30 dtk
        // berlaku untuk semua sumber — tidak ada client bebas).
        SamehadakuRepository(sankaRepository.samehadakuTransport(), diagLogger)
    }

    val playerRepository: PlayerRepository by lazy {
        PlayerRepository(sankaRepository, samehadakuRepository, diagLogger)
    }
}

/** Akses Application dari Activity/Fragment mana pun. */
fun android.content.Context.app(): AnimeloversApp =
    applicationContext as AnimeloversApp

/**
 * Retry SATU kali untuk request gambar (poster) yang gagal karena
 * jaringan (IOException) atau server 5xx — v2.0.7 #1b.
 * 4xx tidak diulang (file memang tidak ada). Dipasang HANYA di OkHttpClient
 * Coil (poster) — TIDAK menyentuh transport Sanka (throttle 45/menit
 * tetap utuh; retry poster tidak menghitung ke kuota Sanka karena hostnya
 * CDN animekompi, bukan sankavollerei.web.id).
 */
private class PosterRetryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var lastException: IOException? = null
        var attempt = 0
        while (true) {
            var response: Response? = null
            try {
                response = chain.proceed(chain.request())
            } catch (e: IOException) {
                lastException = e
            }
            if (response != null) {
                if (response.isSuccessful) return response
                // 4xx (mis. 404: file memang tidak ada) TIDAK di-retry —
                // langsung gagal supaya tidak menahan antrean sia-sia.
                val retryable = response.code in 500..599
                val code = response.code
                response.close()
                if (!retryable) throw IOException("Poster HTTP $code (tidak di-retry)")
                lastException = IOException("Poster HTTP $code")
            }
            if (attempt >= MAX_POSTER_RETRIES) {
                throw lastException
                    ?: IOException("Poster gagal setelah ${attempt + 1} percobaan")
            }
            attempt++
            try {
                Thread.sleep(POSTER_RETRY_DELAY_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw lastException ?: IOException("Poster retry dibatalkan")
            }
        }
    }

    companion object {
        const val MAX_POSTER_RETRIES = 1
        const val POSTER_RETRY_DELAY_MS = 1_000L
    }
}
