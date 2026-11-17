package animelovers.rebornhd.data.model

import com.google.gson.annotations.SerializedName

/**
 * Satu episode dari Anikoto — persis 7 key (terverifikasi live, Riset T1).
 * Contoh: {"id":25591,"title":"Roller Coaster Murder Case",
 * "jp_title":"Jet Coaster Satsujin Jiken","number":1,
 * "episode_embed_id":"6223",
 * "embed_url":{"sub":"https://megaplay.buzz/stream/s-2/6223/sub",
 * "dub":"https://megaplay.buzz/stream/s-2/6223/dub"},
 * "updated_at":"2026-06-20 17:40:55"}
 */
data class Episode(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("title") val title: String = "",
    @SerializedName("jp_title") val jpTitle: String = "",
    @SerializedName("number") val number: Int = 0,
    @SerializedName("episode_embed_id") val episodeEmbedId: String = "",
    @SerializedName("embed_url") val embedUrl: EmbedUrl? = null,
    @SerializedName("updated_at") val updatedAt: String = ""
) {
    /** Judul yang tampil: title Inggris, fallback ke judul Jepang. */
    val displayTitle: String
        get() = title.ifBlank { jpTitle }
}

data class EmbedUrl(
    @SerializedName("sub") val sub: String = "",
    @SerializedName("dub") val dub: String = ""
)
