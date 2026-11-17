package animelovers.rebornhd.data.model

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

/**
 * Satu judul anime dari Anikoto.
 *
 * Bentuk respons terverifikasi live (Riset T1, 2026-11-17):
 * - `/recent-anime` → item punya field yang sama; `episodes` berupa STRING
 *   jumlah episode ("12"); `terms_by_type` berisi `genre`/`studios`/`type`.
 * - `/series/{id}` → `data.anime` bentuknya sama; `terms_by_type` bisa null
 *   (contoh: Case Closed) — genre boleh kosong, jangan crash.
 * - `episodes` di sini sengaja JsonElement: di konteks list ia string jumlah,
 *   di konteks lain bisa array — `episodeCount` menanganinya dua-duanya.
 */
data class Anime(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("title") val title: String = "",
    @SerializedName("alternative") val alternative: String = "",
    @SerializedName("titles") val titles: String = "",
    @SerializedName("native") val native: String = "",
    @SerializedName("slug") val slug: String = "",
    @SerializedName("rating") val rating: String = "",
    @SerializedName("poster") val poster: String = "",
    @SerializedName("is_dub") val isDub: Int = 0,
    @SerializedName("is_sub") val isSub: Int = 0,
    @SerializedName("description") val description: String = "",
    @SerializedName("aired") val aired: String = "",
    @SerializedName("season") val season: String = "",
    @SerializedName("year") val year: String = "",
    @SerializedName("duration") val duration: String = "",
    @SerializedName("status") val status: String = "",
    @SerializedName("score") val score: String = "",
    @SerializedName("mal_id") val malId: Int = 0,
    @SerializedName("episodes") val episodes: JsonElement? = null,
    @SerializedName("ani_id") val aniId: Int = 0,
    @SerializedName("source") val source: String = "",
    @SerializedName("terms_by_type") val termsByType: TermsByType? = null
) {
    /** Jumlah episode (dari string "12" atau ukuran array). */
    val episodeCount: Int?
        get() = when {
            episodes == null || episodes.isJsonNull -> null
            episodes.isJsonPrimitive -> episodes.asJsonPrimitive.let {
                when {
                    it.isString -> it.asString.toIntOrNull()
                    it.isNumber -> it.asInt
                    else -> null
                }
            }
            episodes.isJsonArray -> episodes.asJsonArray.size()
            else -> null
        }

    /** Daftar genre (kosong bila terms_by_type null — jangan crash). */
    val genres: List<String>
        get() = termsByType?.genre ?: emptyList()

    /** Judul yang tampil: title, fallback ke alternative. */
    val displayTitle: String
        get() = title.ifBlank { alternative }

    /** ID yang dipakai VidCloud (ani_id; bila 0, coba mal_id). */
    val vidCloudId: Int
        get() = if (aniId != 0) aniId else malId
}

data class TermsByType(
    @SerializedName("genre") val genre: List<String>? = null,
    @SerializedName("studios") val studios: List<String>? = null,
    @SerializedName("type") val type: List<String>? = null
)

/** Respons GET /recent-anime (20 item/halaman, total 9.404 judul). */
data class RecentResponse(
    @SerializedName("ok") val ok: Boolean = false,
    @SerializedName("data") val data: List<Anime> = emptyList(),
    @SerializedName("pagination") val pagination: Pagination? = null
)

data class Pagination(
    @SerializedName("page") val page: Int = 1,
    @SerializedName("per_page") val perPage: Int = 20,
    @SerializedName("total") val total: Int = 0,
    @SerializedName("total_pages") val totalPages: Int = 0
)

/** Respons GET /series/{id}: anime + daftar episode. */
data class SeriesResponse(
    @SerializedName("ok") val ok: Boolean = false,
    @SerializedName("data") val data: SeriesData? = null
)

data class SeriesData(
    @SerializedName("anime") val anime: Anime? = null,
    @SerializedName("episodes") val episodes: List<Episode> = emptyList()
)
