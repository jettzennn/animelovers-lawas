package animelovers.rebornhd.data.api

import android.os.SystemClock
import animelovers.rebornhd.data.model.SankaAnime
import animelovers.rebornhd.data.model.SankaDetail
import animelovers.rebornhd.data.model.SankaEnvelope
import animelovers.rebornhd.data.model.SankaEpisodeData
import animelovers.rebornhd.data.model.SankaEpisodeItem
import animelovers.rebornhd.data.model.SankaHome
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * Dilempar saat Sanka menjawab HTTP 429.
 * Pemanggil WAJIB menampilkan pesan rate limit dan TIDAK me-retry —
 * Sanka memblokir permanen setelah 3 pelanggaran (riset 2026-11-17).
 */
class RateLimitException(
    message: String = "Terlalu banyak permintaan"
) : Exception(message)

/**
 * Interceptor throttle WAJIB (PRD v2 §4.3): menjaga jarak minimum antar
 * request Sanka — 1.400 ms ≈ 42 request/menit, di bawah batas 45/menit
 * yang terukur di riset. Semua request Sanka (katalog + episode) lewat
 * satu OkHttpClient ini, jadi batas berlaku untuk SELURUH aplikasi.
 *
 * Thread yang menjalankan request ditahan (Thread.sleep) — itu thread
 * latar OkHttp, bukan UI. Jeda dihitung antar MULAINYA request.
 */
class SankaThrottleInterceptor(
    private val minIntervalMs: Long
) : Interceptor {
    private val lock = Any()

    @Volatile
    private var lastCallMs = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        synchronized(lock) {
            val now = SystemClock.uptimeMillis()
            val waitMs = minIntervalMs - (now - lastCallMs)
            if (waitMs > 0) {
                try {
                    Thread.sleep(waitMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            lastCallMs = SystemClock.uptimeMillis()
        }
        return chain.proceed(chain.request())
    }
}

/**
 * Interceptor User-Agent WAJIB (perbaikan v2.0.1, 2026-11-17): menyetel
 * header User-Agent ke UA Chrome Android di SEMUA request Sanka.
 *
 * Alasan: Cloudflare di depan Sanka memblokir User-Agent default OkHttp
 * (UA bot yang dikenal) dari IP residensial/seluler —
 * Beranda v2.0.0 "Gagal memuat" instan di HP Faygo (data seluler + WiFi
 * tetangga) sementara URL home yang sama KE-LOAD penuh di Chrome HP-nya
 * (UA browser lolos; JSON status success, featured 3+ judul). Dari IP
 * sandbox VM, UA okhttp lolos (200) — blokirnya bergantung IP + UA, jadi
 * satu-satunya perbaikan yang aman adalah memakai UA browser di semua
 * request. Throttle 1,4 dtk TETAP berlaku (interceptor terpisah) —
 * perbaikan ini tidak membuka celah rate limit.
 *
 * BridgeInterceptor OkHttp hanya menambahkan UA default bila header
 * belum ada, jadi header yang disetel di sini TIDAK ditimpa.
 */
class SankaUserAgentInterceptor(
    private val userAgent: String
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("User-Agent", userAgent)
            .build()
        return chain.proceed(request)
    }
}

/**
 * Klien Sanka Vollerei (sumber Animekompi).
 *
 * Endpoint yang DIPAKAI (semua diuji live 2026-11-17):
 * - GET /anime/animekompi/home
 * - GET /anime/animekompi/terbaru
 * - GET /anime/animekompi/search?q=… (q kosong → 400, jangan panggil)
 * - GET /anime/animekompi/genre/{slug}
 * - GET /anime/animekompi/detail/{slug}
 * - GET /anime/animekompi/episode/{slug}
 *
 * TIDAK dipakai: /list (4815 judul / 6,3 MB sekaligus — terlalu berat
 * untuk koneksi seluler lambat).
 *
 * User-Agent: Chrome Android (bukan default OkHttp — v2.0.0 membuktikan
 * UA okhttp diblokir Cloudflare dari IP HP Faygo: Beranda "Gagal memuat"
 * instan, sementara Chrome HP-nya memuat JSON home yang sama. UA ini
 * terbukti lolos dari jaringan Faygo, 2026-11-17).
 * Semua method mengembalikan Response supaya repository bisa membedakan
 * 429 (jangan retry) dari 500 (scraper timeout — retry + backoff).
 */
interface SankaApi {

    @GET("anime/animekompi/home")
    suspend fun home(): retrofit2.Response<SankaEnvelope<SankaHome>>

    @GET("anime/animekompi/terbaru")
    suspend fun terbaru(
        @Query("page") page: Int = 1
    ): retrofit2.Response<SankaEnvelope<List<SankaEpisodeItem>>>

    @GET("anime/animekompi/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("page") page: Int = 1
    ): retrofit2.Response<SankaEnvelope<List<SankaAnime>>>

    @GET("anime/animekompi/genre/{slug}")
    suspend fun genre(
        @Path("slug") slug: String,
        @Query("page") page: Int = 1
    ): retrofit2.Response<SankaEnvelope<List<SankaAnime>>>

    @GET("anime/animekompi/detail/{slug}")
    suspend fun detail(
        @Path("slug") slug: String
    ): retrofit2.Response<SankaEnvelope<SankaDetail>>

    @GET("anime/animekompi/episode/{slug}")
    suspend fun episode(
        @Path("slug") slug: String
    ): retrofit2.Response<SankaEnvelope<SankaEpisodeData>>

    companion object {
        /**
         * UA Chrome Android — sama seperti Chrome di HP (terbukti lolos
         * Cloudflare Sanka dari jaringan Faygo, 2026-11-17; UA default
         * OkHttp diblokir dari IP itu dan membuat Beranda v2.0.0
         * "Gagal memuat" instan).
         */
        const val CHROME_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        fun create(
            baseUrl: String,
            connectTimeoutS: Long,
            readTimeoutS: Long,
            minIntervalMs: Long
        ): SankaApi {
            val client = OkHttpClient.Builder()
                .addInterceptor(SankaUserAgentInterceptor(CHROME_USER_AGENT))
                .addInterceptor(SankaThrottleInterceptor(minIntervalMs))
                .connectTimeout(connectTimeoutS, TimeUnit.SECONDS)
                .readTimeout(readTimeoutS, TimeUnit.SECONDS)
                .build()
            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(SankaApi::class.java)
        }
    }
}
