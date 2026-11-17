package animelovers.rebornhd.data.api

import animelovers.rebornhd.util.Constants
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * Backend kecil (PRD §3.6 Opsi A — Node, `server/`, T11–T13 selesai).
 * Kontrak endpoint:
 * - GET /resolve/{ani_id}/{ep}[?mega_embed_id=] →
 *   {ok, data: {source, server, server_label, hls, hls_proxy,
 *   tracks: [{lang, label, url, proxy_url}], has_indonesian_track,
 *   intro, outro}}
 *   (`data` dibungkus envelope `{ok, data}` — BUKAN top-level.)
 * - GET /subtitle/id/{ani_id}/{ep}[?mega_embed_id=] → file text/vtt,
 *   header X-Subtitle-Cache (hit/miss/native),
 *   X-Subtitle-Status (complete/partial),
 *   X-Subtitle-Source (native/translated),
 *   X-Subtitle-Translated-Cues / X-Subtitle-Total-Cues.
 *   Bila Status: partial, Frontend meminta ulang tiap ~15 detik
 *   sampai complete (kontrak `server/src/routes/subtitle.js`).
 * - GET /proxy/hls?u=<url> → isi HLS (opsional)
 * - GET /health → {"ok": true}
 *
 * Base URL di [Constants.BACKEND_BASE_URL] (`http://localhost:3100` —
 * server jalan di HP yang sama via Termux). Bila server tidak jalan,
 * PlayerRepository jatuh ke jalur langsung (VidCloud) + subtitle Inggris.
 *
 * `mega_embed_id` = `episode_embed_id` Anikoto — WAJIB dikirim supaya
 * backend bisa fallback ke MegaPlay bila VidCloud gagal semua
 * (server TIDAK memakai server 2 VidCloud untuk subtitle).
 * `hls_proxy` / `proxy_url` (path relatif `/proxy/hls?u=...`) dipakai
 * bila sumbernya MegaPlay — URL HLS/VTT MegaPlay yang diputar langsung
 * akan 403 karena butuh header Referer persis (proxy menyetelnya
 * server-side).
 */
interface SubtitleApi {

    @GET("resolve/{aniId}/{ep}")
    suspend fun resolve(
        @Path("aniId") aniId: Int,
        @Path("ep") episode: Int,
        @Query("mega_embed_id") megaEmbedId: String? = null
    ): ResolveResponse

    @GET("subtitle/id/{aniId}/{ep}")
    suspend fun subtitleId(
        @Path("aniId") aniId: Int,
        @Path("ep") episode: Int,
        @Query("mega_embed_id") megaEmbedId: String? = null
    ): Response<ResponseBody>

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

/** Envelope respons /resolve: {ok, data}. */
data class ResolveResponse(
    @SerializedName("ok") val ok: Boolean = false,
    @SerializedName("data") val data: ResolveData? = null
)

data class ResolveData(
    @SerializedName("source") val source: String = "",
    // Bisa null (MegaPlay tidak punya nomor server VidCloud).
    @SerializedName("server") val server: Int? = null,
    @SerializedName("server_label") val serverLabel: String? = null,
    @SerializedName("hls") val hls: String = "",
    // Path relatif "/proxy/hls?u=..." — gabungkan dengan base URL backend.
    @SerializedName("hls_proxy") val hlsProxy: String = "",
    @SerializedName("tracks") val tracks: List<ResolveTrack> = emptyList(),
    @SerializedName("has_indonesian_track") val hasIndonesianTrack: Boolean = false,
    @SerializedName("intro") val intro: Range? = null,
    @SerializedName("outro") val outro: Range? = null
)

data class ResolveTrack(
    @SerializedName("lang") val lang: String = "",
    @SerializedName("label") val label: String = "",
    @SerializedName("url") val url: String = "",
    // Path relatif "/proxy/hls?u=..." (untuk sumber MegaPlay).
    @SerializedName("proxy_url") val proxyUrl: String = ""
)

data class Range(
    @SerializedName("start") val start: Long = 0,
    @SerializedName("end") val end: Long = 0
)
