package animelovers.rebornhd.data.model

/**
 * Sumber putar yang sudah di-resolve PlayerRepository — SATU episode bisa
 * punya beberapa kandidat, diurutkan sesuai prioritas T1b (revisi PRD v2 §3.3):
 *  (1) Pixeldrain direct 720p (sources[], kind "direct")
 *  (2) Stream token Animekompi 720p (mirrors[], type "mp4" — 206 terbukti)
 *  (3) Blogger hasil ekstrak itag 22 (720p) → 18 (360p)
 *  (4) WebView embed (default_iframe / sumber embed) — fallback terakhir
 *
 * URL hasil ekstrak/token diambil SEGAR tiap putar — tidak pernah disimpan
 * di DB/riwayat (kedaluwarsa: expire + sig + terikat IP).
 */
enum class PlayableKind {
    DIRECT_720,
    TOKEN_720,
    BLOGGER_720,
    BLOGGER_360,
    EMBED
}

data class PlayableSource(
    val url: String,
    val kind: PlayableKind,
    /**
     * Label JUJUR yang tampil di pemain.
     * "720p" = file 720p (Pixeldrain/token/Blogger itag 22).
     * "360p" = Blogger itag 18 — DITAMPILKAN APA ADANYA (bukan "480p"):
     *   desain §14.2 hanya mengenal 720p/480p karena saat ditulis hanya
     *   ada dua sumber itu; mengklaim 480p untuk stream 360p adalah
     *   membesarkan kualitas. Bila Desainer memutuskan lain, ubah di sini.
     * "480p" = embed WebView (embed jalan di kualitas bawaannya ~480p).
     * "1080p" DILARANG di mana pun — tidak ada sumber 1080p yang bisa
     *   di-streaming (hanya label di shortlink unduhan).
     */
    val qualityLabel: String,
    val isWebView: Boolean = false
) {
    /** Tier HD (720p) — target tombol `resolution` saat ini di SD. */
    val isHd: Boolean get() = qualityLabel == "720p"
}
