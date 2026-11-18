package animelovers.rebornhd.data.model

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

/**
 * Model respons Sanka Vollerei (sumber Animekompi) — BENTUK ASLI, diuji live
 * 2026-11-17 (home/search/genre/detail/episode/terbaru). Semua field
 * angka/string yang rawan dibaca sebagai JsonElement (lihat LenientJson).
 *
 * Amplop umum: {status: "success", creator, source, data, pagination?}.
 * Kunci di home datang DOBEL (camelCase + snake_case) — Gson `alternate`
 * membaca keduanya.
 */

// ---- Poster CDN (v2.0.7 #1) ----
//
// Server menjawab poster di host v10.animekompi.sbs, lalu me-redirect 301
// ke v11.animekompi.sbs — 1 round-trip + jabat tangan TLS ekstra PER
// POSTER (riset v2.0.7 Q1: 62/63 URL Beranda pakai v10). Tulis ulang
// hostnya di sisi klien, di posterUrl — semua pemuatan poster mengalir
// lewat sini (adapter, Detail, player, riwayat).
// HANYA host persis "v10.animekompi.sbs" yang ditulis ulang; URL lain
// (token stream, s4.anilist.co, dsb.) TIDAK PERNAH disentuh.
fun rewritePosterHost(url: String): String {
    if (!url.contains("v10.animekompi.sbs")) return url
    return try {
        val uri = java.net.URI(url)
        if (uri.host?.lowercase() == "v10.animekompi.sbs") {
            java.net.URI(
                uri.scheme, uri.userInfo, "v11.animekompi.sbs",
                uri.port, uri.path, uri.query, uri.fragment
            ).toString()
        } else url
    } catch (e: Exception) {
        url
    }
}

// ---- Amplop + paginasi ----

data class SankaEnvelope<T>(
    @SerializedName("status") val status: String? = null,
    @SerializedName("data") val data: T? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("pagination") val pagination: SankaPagination? = null
)

data class SankaPagination(
    @SerializedName("current_page") val currentPage: Int = 1,
    @SerializedName("max_page") val maxPage: Int = 1,
    @SerializedName("has_next") val hasNext: Boolean = false,
    @SerializedName("has_prev") val hasPrev: Boolean = false,
    @SerializedName("next_page") val nextPage: JsonElement? = null,
    @SerializedName("total") val total: JsonElement? = null
) {
    val totalInt: Int get() = total.asLenientInt(0)
}

// ---- Genre ----

data class SankaGenre(
    @SerializedName("slug") val slug: String? = null,
    @SerializedName("name") val name: String? = null
)

// ---- Anime (ringkasan: home, search, genre, popular, ongoing, ...) ----
//
// Bentuk live (home featured / search / genre — sama persis):
// {id: 4424, title, slug, poster/image/cover (URL sama), banner: null,
//  rating: 8.19 (number) atau "8.19" (string, di detail) atau null,
//  views: 1321 (0 = tidak ada data), type: "TV", status: "ongoing",
//  year: 2026, studio, synopsis, genres: [{slug, name}], episode: "",
//  url: "https://v10.animekompi.sbs/anime/<slug>/"}
data class SankaAnime(
    @SerializedName("id") val idRaw: JsonElement? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("slug") val slug: String? = null,
    @SerializedName("poster") val poster: String? = null,
    @SerializedName("image") val image: String? = null,
    @SerializedName("cover") val cover: String? = null,
    @SerializedName("rating") val ratingRaw: JsonElement? = null,
    @SerializedName("views") val viewsRaw: JsonElement? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("year") val yearRaw: JsonElement? = null,
    @SerializedName("studio") val studio: String? = null,
    @SerializedName("synopsis") val synopsis: String? = null,
    @SerializedName("genres") val genres: List<SankaGenre>? = null,
    @SerializedName("episode") val episodeText: String? = null,
    @SerializedName("url") val url: String? = null
) {
    val id: Int get() = idRaw.asLenientInt(0)

    /** Poster: ketiga kunci berisi URL yang sama — pakai yang tersedia. */
    val posterUrl: String get() = rewritePosterHost(poster ?: image ?: cover ?: "")

    val ratingText: String get() = ratingRaw.asLenientRatingText()
    val ratingValue: Double? get() = ratingRaw.asLenientDouble()

    /** 0 = Sanka tidak memberi jumlah tonton untuk item ini. */
    val views: Int get() = viewsRaw.asLenientInt(0)

    val year: Int get() = yearRaw.asLenientInt(0)
    val genreSlugs: List<String> get() = genres.orEmpty().mapNotNull { it.slug }
    val genreNames: String get() = genres.orEmpty().mapNotNull { it.name }.joinToString(", ")
    val displayTitle: String get() = title ?: ""
}

// ---- Beranda ----
//
// data: {featured, latestEpisodes (+ latest_episodes),
//        popularWeekly (+ popular_weekly), popularMonthly (+ popular_monthly),
//        popularAllTime (+ popular_all_time), ongoing, completed, movies,
//        trendingToday (+ trending_today)}
// featured/popular/ongoing/completed/movies/trending = SankaAnime.
// latestEpisodes = SankaEpisodeItem.
data class SankaHome(
    @SerializedName("featured")
    val featured: List<SankaAnime>? = null,
    @SerializedName(value = "latestEpisodes", alternate = ["latest_episodes"])
    val latestEpisodes: List<SankaEpisodeItem>? = null,
    @SerializedName(value = "popularWeekly", alternate = ["popular_weekly"])
    val popularWeekly: List<SankaAnime>? = null,
    @SerializedName(value = "popularMonthly", alternate = ["popular_monthly"])
    val popularMonthly: List<SankaAnime>? = null,
    @SerializedName(value = "popularAllTime", alternate = ["popular_all_time"])
    val popularAllTime: List<SankaAnime>? = null,
    @SerializedName(value = "ongoing") val ongoing: List<SankaAnime>? = null,
    @SerializedName(value = "completed") val completed: List<SankaAnime>? = null,
    @SerializedName(value = "movies") val movies: List<SankaAnime>? = null,
    @SerializedName(value = "trendingToday", alternate = ["trending_today"])
    val trendingToday: List<SankaAnime>? = null
) {
    /** Terlaris: mingguan dulu (paling segar), lalu sepanjang masa. */
    val popular: List<SankaAnime>
        get() = popularWeekly?.takeIf { it.isNotEmpty() }
            ?: popularAllTime?.takeIf { it.isNotEmpty() }
            ?: popularMonthly.orEmpty()

    /** Isi default Jelajahi (genre "Semua", tanpa query): dari home, tanpa
     *  request tambahan. /list (4815 judul, 6,3 MB) sengaja TIDAK dipakai —
     *  terlalu berat untuk koneksi seluler lambat. */
    val browseDefault: List<SankaAnime>
        get() = ongoing.orEmpty() + completed.orEmpty() + movies.orEmpty()
}

// ---- Item episode terbaru (home.latestEpisodes + /terbaru) ----
//
// {id: null, title: "Martial Master Episode 710",
//  slug: "martial-master-episode-710-subtitle-indonesia" (SLUG EPISODE),
//  episode: "710", anime_title, anime_slug, anime_type, anime_status,
//  uncensored: false, poster/image/cover, released_at, created_at, url}
data class SankaEpisodeItem(
    @SerializedName("title") val title: String? = null,
    @SerializedName("slug") val slug: String? = null,
    @SerializedName("episode") val episodeRaw: JsonElement? = null,
    @SerializedName("anime_title") val animeTitle: String? = null,
    @SerializedName("anime_slug") val animeSlug: String? = null,
    @SerializedName("anime_type") val animeType: String? = null,
    @SerializedName("anime_status") val animeStatus: String? = null,
    @SerializedName("poster") val poster: String? = null,
    @SerializedName("image") val image: String? = null,
    @SerializedName("cover") val cover: String? = null,
    @SerializedName("released_at") val releasedAt: String? = null
) {
    val episodeNumber: Int get() = episodeRaw.asLenientInt(0)
    val posterUrl: String get() = rewritePosterHost(poster ?: image ?: cover ?: "")
}

// ---- Detail anime ----
//
// data: {id, title, alter_title/alt_titles/altTitles (null), slug,
//  image/cover/poster, banner (null), rating: "8.19" (STRING di sini!),
//  views, type, status, year, studio, duration: "23 min. per ep.",
//  season, director/fansub/subtitle (null), trailer (id YouTube),
//  uncensored, metadata {...}, genres: [{name, slug}], synopsis,
//  episodes: [{num, title, date, slug, url}], batch_downloads: []}
data class SankaDetail(
    @SerializedName("id") val idRaw: JsonElement? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("slug") val slug: String? = null,
    @SerializedName("poster") val poster: String? = null,
    @SerializedName("image") val image: String? = null,
    @SerializedName("cover") val cover: String? = null,
    @SerializedName("rating") val ratingRaw: JsonElement? = null,
    @SerializedName("views") val viewsRaw: JsonElement? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("year") val yearRaw: JsonElement? = null,
    @SerializedName("studio") val studio: String? = null,
    @SerializedName("duration") val duration: String? = null,
    @SerializedName("season") val season: String? = null,
    @SerializedName("synopsis") val synopsis: String? = null,
    @SerializedName("genres") val genres: List<SankaGenre>? = null,
    @SerializedName("episodes") val episodes: List<SankaDetailEpisode>? = null,
    @SerializedName("trailer") val trailer: String? = null,
    @SerializedName("uncensored") val uncensored: Boolean = false
) {
    val posterUrl: String get() = rewritePosterHost(poster ?: image ?: cover ?: "")
    val ratingText: String get() = ratingRaw.asLenientRatingText()
    val views: Int get() = viewsRaw.asLenientInt(0)
    val year: Int get() = yearRaw.asLenientInt(0)
    val genreSlugs: List<String> get() = genres.orEmpty().mapNotNull { it.slug }
    val genreNames: String get() = genres.orEmpty().mapNotNull { it.name }.joinToString(", ")
    val displayTitle: String get() = title ?: ""

    fun toAnime(): SankaAnime = SankaAnime(
        idRaw = idRaw,
        title = title,
        slug = slug,
        poster = posterUrl,
        ratingRaw = ratingRaw,
        viewsRaw = viewsRaw,
        type = type,
        status = status,
        yearRaw = yearRaw,
        studio = studio,
        synopsis = synopsis,
        genres = genres
    )
}
