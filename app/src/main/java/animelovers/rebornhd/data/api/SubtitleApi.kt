package animelovers.rebornhd.data.api

import animelovers.rebornhd.util.Constants
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

/**
 * Backend kecil (PRD §3.6 Opsi A — Node, `server/`, T11–T13 selesai).
 * Kontrak endpoint:
 * - GET /resolve/{ani_id}/{ep} → {hls, tracks[], intro, outro}
 * - GET /subtitle/id/{ani_id}/{ep} → file text/vtt (header X-Subtitle-Cache: hit/miss/native)
 * - GET /proxy/hls?u=<url> → isi HLS (opsional)
 * - GET /health → {"ok": true}
 *
 * Base URL di [Constants.BACKEND_BASE_URL] (`http://localhost:3100` —
 * server jalan di HP yang sama via Termux). Bila server tidak jalan,
 * PlayerRepository jatuh ke jalur langsung (VidCloud) + subtitle Inggris.
 */
interface SubtitleApi {

    @GET("resolve/{aniId}/{ep}")
    suspend fun resolve(
        @Path("aniId") aniId: Int,
        @Path("ep") episode: Int
    ): ResolveResponse

    @GET("subtitle/id/{aniId}/{ep}")
    suspend fun subtitleId(
        @Path("aniId") aniId: Int,
        @Path("ep") episode: Int
    ): ResponseBody

    @GET("health")
    suspend fun health(): ResponseBody

    companion object {
        fun create(baseUrl: String): SubtitleApi {
            val client = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()
            val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            return Retrofit.Builder()
                .baseUrl(normalized)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(SubtitleApi::class.java)
        }
    }
}

data class ResolveResponse(
    @SerializedName("hls") val hls: String = "",
    @SerializedName("tracks") val tracks: List<ResolveTrack> = emptyList(),
    @SerializedName("intro") val intro: Range? = null,
    @SerializedName("outro") val outro: Range? = null
)

data class ResolveTrack(
    @SerializedName("lang") val lang: String = "",
    @SerializedName("url") val url: String = ""
)

data class Range(
    @SerializedName("start") val start: Long = 0,
    @SerializedName("end") val end: Long = 0
)
