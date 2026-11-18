package animelovers.rebornhd.data.model

import com.google.gson.annotations.SerializedName

/**
 * Model respons Samehadaku (Sanka) — sumber kedua fallback putar
 * (v2.0.7 item 16 / PRD v2.0.8 item 2). Struktur BERBEDA TOTAL dari
 * Animekompi (riset 05:17 WIB) — adapter sendiri, bukan drop-in.
 *
 * Bukti live (18 panggilan Sanka, 0× 429):
 * - search "tensei shitara ken" → 200, 28.279 byte, 20 hasil;
 * - detail "tensei-shitara-ken-deshita-s2" → 200, 7.395 byte;
 * - episode "tensei-shitara-ken-deshita-s2-episode-1" → 200, 29.151 byte.
 *
 * Semua field nullable + @SerializedName (guard R8: model di
 * `animelovers.rebornhd.data.**` ditahan proguard-rules.pro, dan parse
 * memakai TypeToken.getParameterized — lihat SamehadakuRepository).
 */

// ---- Search Samehadaku: data: {animeList: [...]} ----
//
// Item search Samehadaku = EPISODE (bukan serial) — berbeda dengan
// search Animekompi (item = serial):
// {"title": "Tensei shitara Ken deshita S2 Episode 8",
//  "type": "Eps 8",
//  "animeId": "tensei-shitara-ken-deshita-s2-episode-8"}
data class SamehadakuSearchData(
    @SerializedName("animeList") val animeList: List<SamehadakuSearchItem>? = null
)

data class SamehadakuSearchItem(
    @SerializedName("title") val title: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("animeId") val animeId: String? = null
)

// ---- Episode Samehadaku: data: {defaultStreamingUrl, ...} ----
//
// Hanya defaultStreamingUrl yang dipakai: URL Blogger
// (video.g?token=…, origin sameguru2.blogspot.com) yang diekstrak jadi
// itag 22/18 (terbukti: halaman 200, 24.987 byte → 2 URL videoplayback
// itag 18, dur 1420,039 dtk = episode penuh; itag 22 tidak terlihat di
// sampel 1 episode ini — adapter harus tahan bila hanya 18 yang ada).
// `server` Mega (720p/1080p) hanya embed (mega.nz/embed) dan
// `downloadUrl` hanya halaman file-host (Gofile/Acefile/Mediafire) —
// keduanya BUKAN file langsung, jadi TIDAK dimodelkan dan jangan dipakai.
data class SamehadakuEpisodeData(
    @SerializedName("defaultStreamingUrl") val defaultStreamingUrl: String? = null
)
