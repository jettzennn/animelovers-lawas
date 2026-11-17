package animelovers.rebornhd.data.model

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

/**
 * Model respons episode Sanka (GET /anime/animekompi/episode/:slug) —
 * diuji live 2026-11-17 (Ao no Hako S2 ep 1 + Martial Master ep 710).
 *
 * PERINGATAN SLUG: slug episode WAJIB diambil dari respons detail
 * (episodes[].slug) atau episode_list — JANGAN dirangkai sendiri.
 * Buktinya: ep 1–3, 5, 6 Ao no Hako berakhiran "-subtitle-indonesia",
 * tapi ep 4 slug-nya "ao-no-hako-season-2-episode-4" (tanpa akhiran).
 * Slug yang ditebak (termasuk dari judul) bisa 404 "Episode not found".
 */

// ---- Sumber video (sources[]) ----
//
// {id: 208427, label: "Pixel HD" | "Server GDrive" | "HD Pahe",
//  url, kind: "direct" | "embed", quality: null (SELALU null — baca label)}
data class SankaSourceItem(
    @SerializedName("id") val idRaw: JsonElement? = null,
    @SerializedName("label") val label: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("kind") val kind: String? = null,
    @SerializedName("quality") val quality: String? = null
)

// ---- Mirror (mirrors[]) ----
//
// {name, server, url, type: "embed" | "mp4", quality: ""}
// type "mp4" = stream token Animekompi
// (https://v10.animekompi.sbs/api/episodes/stream/<token>) — file 720p
// yang sama dengan Pixeldrain (206 Partial Content, video/mp4, 264 MB).
data class SankaMirror(
    @SerializedName("name") val name: String? = null,
    @SerializedName("server") val server: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("quality") val quality: String? = null
)

// ---- Episode sebelum/berikutnya (prev/next) ----
//
// {id, slug, number, numberLabel: null, title} atau null.
data class SankaPrevNext(
    @SerializedName("slug") val slug: String? = null,
    @SerializedName("number") val numberRaw: JsonElement? = null,
    @SerializedName("title") val title: String? = null
) {
    val number: Int get() = numberRaw.asLenientInt(0)
}

// ---- Item daftar episode (episode_list[]) ----
//
// {id, title, slug, number} — number di sini NUMBER (311), tapi di
// tempat lain bisa string: tetap lewat asLenientInt.
data class SankaEpisodeListItem(
    @SerializedName("id") val idRaw: JsonElement? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("slug") val slug: String? = null,
    @SerializedName("number") val numberRaw: JsonElement? = null
) {
    val number: Int get() = numberRaw.asLenientInt(0)
}

// ---- Item episode di respons detail (detail.episodes[]) ----
//
// {num: "1" (STRING — "01" juga muncul di judul), title,
//  date: "2026-10-08T10:39:28.000Z", slug, url}
data class SankaDetailEpisode(
    @SerializedName("num") val numRaw: JsonElement? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("date") val date: String? = null,
    @SerializedName("slug") val slug: String? = null,
    @SerializedName("url") val url: String? = null
) {
    val number: Int get() = numRaw.asLenientInt(0)
}

// ---- Respons episode lengkap ----
//
// data: {id, title, episode: "710" (string), slug, detail_slug,
//  anime_slug, anime_title (+ animeTitle dobel), prev_episode (slug|null),
//  next_episode (slug|null), prev (SankaPrevNext|null), next (…|null),
//  default_iframe (URL embed — Blogger ATAU vip69/Abyssplayer, lihat isi),
//  mirrors[], sources[], downloads[] (bisa KOSONG — Martial Master 710: 0;
//  bila ada, isinya HALAMAN unduhan (acefile/gofile/pixeldrain /u/),
//  BUKAN URL file langsung — jangan dipakai untuk unduh),
//  anime: SankaAnime (ringkas), episode_list[]}
data class SankaEpisodeData(
    @SerializedName("id") val idRaw: JsonElement? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("episode") val episodeRaw: JsonElement? = null,
    @SerializedName("slug") val slug: String? = null,
    @SerializedName("detail_slug") val detailSlug: String? = null,
    @SerializedName("anime_slug") val animeSlug: String? = null,
    @SerializedName("anime_title") val animeTitle: String? = null,
    @SerializedName("prev_episode") val prevEpisode: String? = null,
    @SerializedName("next_episode") val nextEpisode: String? = null,
    @SerializedName("prev") val prev: SankaPrevNext? = null,
    @SerializedName("next") val next: SankaPrevNext? = null,
    @SerializedName("default_iframe") val defaultIframe: String? = null,
    @SerializedName("mirrors") val mirrors: List<SankaMirror>? = null,
    @SerializedName("sources") val sources: List<SankaSourceItem>? = null,
    @SerializedName("anime") val anime: SankaAnime? = null,
    @SerializedName("episode_list") val episodeList: List<SankaEpisodeListItem>? = null
) {
    val episodeNumber: Int get() = episodeRaw.asLenientInt(0)
    val prevSlug: String? get() = prevEpisode?.takeIf { it.isNotBlank() } ?: prev?.slug
    val nextSlug: String? get() = nextEpisode?.takeIf { it.isNotBlank() } ?: next?.slug
}
