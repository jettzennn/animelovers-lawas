package animelovers.rebornhd.data.api

import animelovers.rebornhd.data.model.RecentResponse
import animelovers.rebornhd.data.model.SeriesResponse
import animelovers.rebornhd.util.Constants
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * Katalog Anikoto — publik, tanpa key (terverifikasi live, Riset T1).
 * Timeout 8 detik connect/read (aturan asli, PRD §3.4).
 * TIDAK ADA logging interceptor: URL upstream tidak boleh bocor ke log (PRD §3.6).
 */
interface AnikotoApi {

    /** Episode terbaru — 20 item/halaman, total 9.404 judul (471 halaman). */
    @GET("recent-anime")
    suspend fun recentAnime(@Query("page") page: Int = 1): RecentResponse

    /** Detail anime + daftar episode. Memberi mal_id + ani_id. */
    @GET("series/{id}")
    suspend fun series(@Path("id") id: String): SeriesResponse

    companion object {
        fun create(): AnikotoApi {
            val client = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()
            return Retrofit.Builder()
                .baseUrl(Constants.ANIKOTO_BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(AnikotoApi::class.java)
        }
    }
}
