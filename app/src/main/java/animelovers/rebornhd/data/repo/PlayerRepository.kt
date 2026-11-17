package animelovers.rebornhd.data.repo

import android.content.Context
import android.net.Uri
import animelovers.rebornhd.data.api.ResolveData
import animelovers.rebornhd.data.api.SubtitleApi
import animelovers.rebornhd.data.api.VidCloudApi
import animelovers.rebornhd.data.model.VideoSource
import animelovers.rebornhd.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Resolve satu episode menjadi [VideoSource] yang bisa diputar player.
 *
 * Urutan (PRD §3.5 + Riset T1):
 * 1. Bila backend kecil terpasang ([Constants.BACKEND_BASE_URL] terisi):
 *    GET /resolve → HLS + tracks. Bila gagal, jatuh ke jalur langsung.
 *    `mega_embed_id` (episode_embed_id Anikoto) SELALU dikirim supaya
 *    backend bisa fallback ke MegaPlay bila VidCloud gagal semua.
 * 2. Jalur langsung: VidCloud getSources server 1 → 2 → 3.
 * 3. Subtitle Indonesia:
 *    a. Track berlabel "Indonesian" dari getSources (native) → pakai
 *       langsung HANYA bila isinya terverifikasi Indonesia (port
 *       `server/src/lib/native-track.js`: VTT valid, ≥5 cue, ≥3 penanda
 *       BERBEDA Bahasa Indonesia di 3000 karakter pertama). Bila tidak
 *       terverifikasi → anggap tidak ada track native (fail-closed).
 *    b. Bila tidak ada dan backend ada → GET /subtitle/id (translate + cache).
 *    c. Bila backend tidak ada/tidak terjangkau → VTT Inggris langsung.
 * 4. JANGAN PERNAH menampilkan track berlabel Indonesia yang isinya Inggris.
 *
 * Bila sumbernya MegaPlay, HLS + track VTT diputar LEWAT PROXY backend
 * (`hls_proxy` / `proxy_url`) — URL MegaPlay langsung akan 403 karena
 * butuh header Referer persis (proxy menyetelnya server-side).
 *
 * Subtitle translate bisa datang SEBAGIAN (header X-Subtitle-Status:
 * partial) — [fetchSubtitleId] mengembalikan statusnya supaya player
 * bisa meminta ulang tiap ~15 detik sampai complete.
 *
 * URL ber-token (?t=) hanya hidup di memori selama pemutaran — TIDAK PERNAH
 * disimpan di DB (PRD §3.5).
 */
class PlayerRepository(
    private val context: Context,
    private val vidCloud: VidCloudApi = VidCloudApi(),
    private val subtitleApi: SubtitleApi? =
        Constants.BACKEND_BASE_URL.ifBlank { null }?.let { SubtitleApi.create(it) }
) {

    val hasBackend: Boolean get() = subtitleApi != null

    /** Hasil satu kali ambil subtitle Indonesia dari backend. */
    data class SubtitleFetch(
        val file: File,
        /** "complete" | "partial" (dari header X-Subtitle-Status). */
        val status: String,
        /** "native" | "translated" (dari header X-Subtitle-Source). */
        val kind: String,
        val vttText: String
    )

    /**
     * Ambil VTT Indonesia dari backend (sekali ambil; tulis ke cache file).
     * @return null bila backend tidak ada / gagal / bukan WEBVTT.
     * CancellationException TIDAK ditelan — pemanggil (polling) harus berhenti.
     */
    suspend fun fetchSubtitleId(
        aniId: Int,
        episode: Int,
        megaEmbedId: String? = null
    ): SubtitleFetch? {
        val api = subtitleApi ?: return null
        return try {
            val response = api.subtitleId(aniId, episode, megaEmbedId)
            val body = response.body() ?: return null
            val vtt = body.string()
            if (!vtt.contains("WEBVTT")) return null
            // retrofit2.Response TIDAK punya method header(name) — itu
            // milik okhttp3.Headers. Baca lewat response.headers()[nama].
            val status = response.headers()["X-Subtitle-Status"] ?: "complete"
            val kind = response.headers()["X-Subtitle-Source"] ?: "translated"
            val file = File(context.cacheDir, "sub_id_${aniId}_$episode.vtt")
            file.writeText(vtt)
            SubtitleFetch(file, status, kind, vtt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Gabungkan path proxy relatif ("/proxy/hls?u=...") dengan base URL backend. */
    private fun absoluteProxy(path: String): String {
        if (path.isBlank()) return ""
        if (!path.startsWith("/")) return path
        return Constants.BACKEND_BASE_URL.trimEnd('/') + path
    }

    // ---- Validasi track "Indonesian" native (port server) ----
    // Satu sumber kebenaran di server: `server/src/lib/native-track.js`
    // + `server/src/lib/translate.js` (looksIndonesian) +
    // `server/src/lib/vtt.js` (parseVtt). Kriteria sama persis:
    // VTT valid, ≥5 cue, dan 3000 karakter pertama mengandung ≥3 penanda
    // BERBEDA Bahasa Indonesia. Penanda yang sama berulang hanya
    // dihitung sekali — nama tokoh dalam dialog Inggris ("Dan", "Yang")
    // bisa muncul berkali-kali, dan bila dihitung total, VTT Inggris
    // lolos sebagai Indonesia (temuan Pentest T18 M1). Gagal unduh
    // (mis. 403) / timeout / isi tidak valid semuanya berarti
    // "tidak terverifikasi" (fail-closed).

    private val indonesianMarkerRe = Regex(
        "\\b(yang|dan|adalah|sebuah|ketika|dengan|untuk|dalam|seorang|mereka|tersebut|sehingga|namun|karena|kemudian)\\b",
        RegexOption.IGNORE_CASE
    )

    private val vttTimestampRe = Regex(
        "^(\\d{2}:\\d{2}:\\d{2}\\.\\d{3}|\\d{2}:\\d{2}\\.\\d{3})\\s+-->\\s+(\\d{2}:\\d{2}:\\d{2}\\.\\d{3}|\\d{2}:\\d{2}\\.\\d{3})(?:\\s+.*)?$"
    )

    // Hitung penanda BERBEDA, bukan total kemunculan (lihat komentar
    // di atas: nama tokoh "Dan"/"Yang" yang berulang hanya 1 penanda).
    private fun looksIndonesian(text: String): Boolean =
        indonesianMarkerRe.findAll(text).map { it.value.lowercase() }.toSet().size >= 3

    /** Hitung cue valid (port `vtt.parseVtt` — 0 bila bukan VTT valid). */
    private fun countCues(vttText: String): Int {
        val clean = vttText.trimStart('\uFEFF')
        if (!clean.startsWith("WEBVTT")) return 0
        val lines = clean.split('\n')
        var count = 0
        var i = 0
        // Lewati blok header (sampai baris kosong pertama).
        while (i < lines.size && lines[i].trim().isNotEmpty()) i++
        while (i < lines.size) {
            // Lewati baris kosong.
            while (i < lines.size && lines[i].trim().isEmpty()) i++
            if (i >= lines.size) break
            val first = lines[i].trim()
            // Blok NOTE / STYLE / REGION: lewati sampai baris kosong.
            if (first == "NOTE" || first.startsWith("NOTE ") ||
                first == "STYLE" || first == "REGION"
            ) {
                while (i < lines.size && lines[i].trim().isNotEmpty()) i++
                continue
            }
            // Baris pengenal cue (bukan timestamp) → baris berikut harus timestamp.
            var tsLine = first
            if (!vttTimestampRe.matches(tsLine)) {
                i++
                if (i >= lines.size) break
                tsLine = lines[i].trim()
                if (!vttTimestampRe.matches(tsLine)) {
                    // Bukan cue yang valid — lewati blok ini.
                    while (i < lines.size && lines[i].trim().isNotEmpty()) i++
                    continue
                }
            }
            i++
            // Lewati baris teks cue sampai baris kosong.
            var hasText = false
            while (i < lines.size && lines[i].trim().isNotEmpty()) {
                hasText = true
                i++
            }
            if (hasText) count++
        }
        return count
    }

    /** true hanya bila VTT terbukti Indonesia. Tidak pernah melempar. */
    private fun isIndonesianVtt(text: String): Boolean {
        return try {
            countCues(text) >= 5 && looksIndonesian(text.take(3000))
        } catch (e: Exception) {
            false
        }
    }

    /** Ambil VTT dari URL lalu validasi — false bila gagal/tidak valid. */
    private suspend fun isValidIndonesianTrack(url: String): Boolean {
        val text = try {
            vidCloud.fetchText(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return false
        return isIndonesianVtt(text)
    }

    /**
     * Resolve episode. [aniId] = ani_id Anikoto (fallback mal_id).
     * [megaEmbedId] = episode_embed_id Anikoto (untuk fallback MegaPlay).
     * @return null bila semua sumber gagal (player menampilkan pesan error).
     */
    suspend fun resolve(
        aniId: Int,
        episode: Int,
        megaEmbedId: String? = null
    ): VideoSource? {
        // 1. Backend dulu (bila ada).
        subtitleApi?.let { api ->
            try {
                val response = api.resolve(aniId, episode, megaEmbedId)
                val data = response.data
                if (data != null && data.hls.isNotBlank()) {
                    return buildFromBackend(aniId, episode, data, megaEmbedId)
                }
            } catch (e: Exception) {
                // Backend gagal = lanjut ke jalur langsung (bukan error fatal).
            }
        }
        // 2. Jalur langsung: VidCloud.
        val sources = vidCloud.getSourcesAnyServer(aniId, episode) ?: return null
        val hls = sources.sources.firstOrNull { it.file.isNotBlank() }?.file
            ?: return null
        return buildFromVidCloud(sources, hls, aniId, episode, megaEmbedId)
    }

    private suspend fun buildFromBackend(
        aniId: Int,
        episode: Int,
        data: ResolveData,
        megaEmbedId: String?
    ): VideoSource {
        val isMegaPlay = data.source.equals("megaplay", ignoreCase = true)
        // MegaPlay → lewat proxy (Referer disetel server-side).
        val hlsUrl = if (isMegaPlay && data.hlsProxy.isNotBlank()) {
            absoluteProxy(data.hlsProxy)
        } else {
            data.hls
        }
        // Track dari /resolve: bila MegaPlay, pakai proxy_url-nya.
        fun trackUrl(url: String, proxyUrl: String): String =
            if (isMegaPlay && proxyUrl.isNotBlank()) absoluteProxy(proxyUrl) else url

        var subtitleIdUri: Uri? = null
        var subtitleIdKind: String? = null
        var subtitleIdStatus: String? = null
        // Track Indonesia native dari backend (bila /resolve sudah menyediakannya).
        val nativeId = data.tracks.firstOrNull {
            it.lang.equals("id", ignoreCase = true) ||
                it.lang.equals("indonesian", ignoreCase = true)
        }
        if (nativeId != null && nativeId.url.isNotBlank()) {
            subtitleIdUri = Uri.parse(trackUrl(nativeId.url, nativeId.proxyUrl))
            subtitleIdKind = "native"
            subtitleIdStatus = "complete"
        } else {
            // Minta VTT Indonesia hasil translate + cache.
            val fetched = fetchSubtitleId(aniId, episode, megaEmbedId)
            if (fetched != null) {
                subtitleIdUri = Uri.fromFile(fetched.file)
                subtitleIdKind = fetched.kind
                subtitleIdStatus = fetched.status
            }
        }
        val enTrack = data.tracks.firstOrNull {
            it.lang.equals("en", ignoreCase = true) ||
                it.lang.equals("english", ignoreCase = true)
        }
        val enUrl = enTrack?.let { trackUrl(it.url, it.proxyUrl) }
        return VideoSource(
            hlsUrl = hlsUrl,
            subtitleIdUri = subtitleIdUri,
            subtitleIdKind = subtitleIdKind,
            subtitleIdStatus = subtitleIdStatus,
            subtitleEnUrl = enUrl,
            serverLabel = data.serverLabel ?: "",
            server = data.server ?: 0,
            introStart = data.intro?.start ?: 0,
            introEnd = data.intro?.end ?: 0,
            outroStart = data.outro?.start ?: 0,
            outroEnd = data.outro?.end ?: 0
        )
    }

    private suspend fun buildFromVidCloud(
        sources: animelovers.rebornhd.data.model.VidCloudSources,
        hls: String,
        aniId: Int,
        episode: Int,
        megaEmbedId: String?
    ): VideoSource {
        // a. Track Indonesia native — HARUS divalidasi isinya (fail-closed,
        //    port `server/src/lib/native-track.js`): label "Indonesian"
        //    belum tentu isinya Indonesia. Bila VTT tidak bisa diambil
        //    (mis. 403) atau isinya bukan Indonesia → anggap tidak ada
        //    track native; rantai fallback di bawah (translate backend,
        //    lalu Inggris) yang jalan — sama seperti perilaku server
        //    ("gagal validasi isi — lanjut translate dari Inggris").
        val nativeId = sources.tracks.firstOrNull {
            it.label.equals("Indonesian", ignoreCase = true)
        }
        var subtitleIdUri: Uri? = null
        var subtitleIdKind: String? = null
        var subtitleIdStatus: String? = null
        if (nativeId != null && nativeId.file.isNotBlank() &&
            isValidIndonesianTrack(nativeId.file)
        ) {
            subtitleIdUri = Uri.parse(nativeId.file)
            subtitleIdKind = "native"
            subtitleIdStatus = "complete"
        }
        // b. Bila tidak ada track native dan backend ada → translate.
        if (subtitleIdUri == null && subtitleApi != null) {
            val fetched = fetchSubtitleId(aniId, episode, megaEmbedId)
            if (fetched != null) {
                subtitleIdUri = Uri.fromFile(fetched.file)
                subtitleIdKind = fetched.kind
                subtitleIdStatus = fetched.status
            }
        }
        // c. Fallback Inggris (server 1 diutamakan — getSourcesAnyServer
        //    sudah mencoba server 1 dulu; bila ini server 2/3, VTT-nya
        //    bisa 403 dari IP tertentu — player tetap jalan tanpa subtitle
        //    bila unduhan VTT gagal, dan itu ditangani di VideoPlayActivity).
        val enUrl = sources.tracks.firstOrNull {
            it.label.equals("English", ignoreCase = true)
        }?.file
        return VideoSource(
            hlsUrl = hls,
            subtitleIdUri = subtitleIdUri,
            subtitleIdKind = subtitleIdKind,
            subtitleIdStatus = subtitleIdStatus,
            subtitleEnUrl = enUrl,
            serverLabel = sources.sources.firstOrNull()?.label ?: "",
            server = sources.server,
            introStart = sources.introStart,
            introEnd = sources.introEnd,
            outroStart = sources.outroStart,
            outroEnd = sources.outroEnd
        )
    }

    /** Cek hidup backend (untuk Splash) — gagal = lanjut saja, bukan error. */
    suspend fun checkBackendHealth(): Boolean {
        val api = subtitleApi ?: return false
        return try {
            withContext(Dispatchers.IO) { api.health() }
            true
        } catch (e: Exception) {
            false
        }
    }
}
