package animelovers.rebornhd.data.model

import android.net.Uri

/**
 * Hasil resolve satu episode menjadi sesuatu yang bisa diputar player.
 * Dibangun PlayerRepository dari backend kecil (/resolve) atau langsung
 * dari VidCloud getSources (lihat PRD §3.6).
 *
 * Aturan subtitle (Riset T1 — WAJIB):
 * - Utamakan track berlabel "Indonesian" (native dari VidCloud bila ada).
 * - Bila tidak ada: VTT Indonesia dari backend (translate + cache).
 * - Bila backend tidak terjangkau: VTT Inggris langsung.
 * - JANGAN PERNAH menampilkan track berlabel Indonesia yang isinya Inggris.
 * - Subtitle diambil dari server 1 (Sora); fallback MegaPlay, BUKAN server 2
 *   (VTT server 2 → 403 dari IP sandbox).
 * - URL ber-token (?t=) TIDAK BOLEH disimpan di DB — ambil segar per request.
 */
data class VideoSource(
    /** URL master.m3u8 (HLS). */
    val hlsUrl: String,
    /** Track subtitle Indonesia — Uri file lokal (hasil backend) atau URL remote. */
    val subtitleIdUri: Uri? = null,
    /** "native" (dari VidCloud) / "translated" (dari backend) / null (tidak ada). */
    val subtitleIdKind: String? = null,
    /** URL VTT Inggris (fallback). */
    val subtitleEnUrl: String? = null,
    /** Label server video (mis. "Sora") — untuk log saja. */
    val serverLabel: String = "",
    /** Nomor server VidCloud yang dipakai (1/2/3). */
    val server: Int = 1,
    val introStart: Long = 0,
    val introEnd: Long = 0,
    val outroStart: Long = 0,
    val outroEnd: Long = 0
) {
    val hasIndonesian: Boolean get() = subtitleIdUri != null
    val hasEnglish: Boolean get() = !subtitleEnUrl.isNullOrBlank()
}

/** Satu track subtitle dari getSources (VidCloud/MegaPlay). */
data class SubtitleTrack(
    val label: String,
    val file: String,
    val isDefault: Boolean = false
)

/** Hasil decode getSources VidCloud (setelah XOR). */
data class VidCloudSources(
    val sources: List<VidCloudSource> = emptyList(),
    val tracks: List<SubtitleTrack> = emptyList(),
    val introStart: Long = 0,
    val introEnd: Long = 0,
    val outroStart: Long = 0,
    val outroEnd: Long = 0,
    val server: Int = 1
)

data class VidCloudSource(
    val file: String = "",
    val type: String = "",
    val label: String = "",
    val isDefault: Boolean = false
)
