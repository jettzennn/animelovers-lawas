package animelovers.rebornhd.data.repo

import animelovers.rebornhd.data.api.RateLimitException
import animelovers.rebornhd.data.model.PlayableKind
import animelovers.rebornhd.data.model.PlayableSource
import animelovers.rebornhd.data.model.SankaEpisodeData
import animelovers.rebornhd.data.model.SankaEpisodeListItem
import animelovers.rebornhd.util.Constants
import animelovers.rebornhd.util.DiagLog
import animelovers.rebornhd.util.DiagLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Hasil resolve satu episode — daftar kandidat putar + info pendukung.
 *
 * @property candidates kandidat Media3, terurut sesuai prioritas
 *   T1b (Pixeldrain 720p → token 720p → Blogger 22 → Blogger 18 →
 *   ok.ru hd → ok.ru sd → Samehadaku 22 → Samehadaku 18).
 * @property downloadUrl URL file .mp4 langsung (Pixeldrain) untuk
 *   DownloadManager — null bila episode tidak punya sumber Pixeldrain
 *   (downloads[] Sanka HANYA berisi halaman unduhan, bukan URL file).
 * @property prevSlug / nextSlug slug episode sebelum/berikutnya (null bila
 *   tidak ada) — dari respons /episode.
 * @property episodeList daftar episode seri ini (untuk next/prev player).
 * @property bloggerIframeUrl URL iframe Blogger untuk fetch lazy (#6):
 *   bila kandidat direct/token sudah ada, HTML Blogger TIDAK diambil saat
 *   resolve — player mengambilnya belakangan hanya bila semua kandidat
 *   itu gagal.
 * @property animeTitle judul serial Animekompi (untuk pencocokan episode
 *   di Samehadaku — lapis 3, v2.0.7 item 16).
 * @property okRuEmbedUrl URL embed ok.ru episode ini (untuk fetch lazy
 *   lapis 2, v2.0.7 item 15) — null bila episode tidak punya embed ok.ru.
 */
data class EpisodeSources(
    val candidates: List<PlayableSource>,
    val downloadUrl: String?,
    val episodeNumber: Int,
    val episodeTitle: String,
    val prevSlug: String?,
    val nextSlug: String?,
    val episodeList: List<SankaEpisodeListItem>,
    val bloggerIframeUrl: String? = null,
    val animeTitle: String = "",
    val okRuEmbedUrl: String? = null
)

/**
 * Keputusan tumbuh-vs-macet pengawas buffering (v2.0.8 item 1) —
 * fungsi MURNI supaya harness bisa mengujinya tanpa Media3:
 * tumbuh ≥ ambang per cek 10 dtk = TUMBUH (timer di-reset);
 * di bawah ambang = MACET (timer jalan terus). Dipakai startStallWatch
 * (VideoPlayActivity): baseline DIMAJUKAN di setiap cek — jangan ubah
 * aturan itu (varian baseline-saat-lolos gagal diam-diam untuk Ep 1180).
 */
object StallTracker {
    fun isGrowth(growthMs: Long, minGrowthMs: Long): Boolean =
        growthMs >= minGrowthMs
}

/**
 * Keputusan fail-fast 4xx permanen (v2.0.8 item 3) — fungsi MURNI
 * supaya harness bisa mengujinya tanpa Media3: HTTP 400..499 KECUALI
 * 408 (timeout) / 429 (rate limit) = permanen (file di-takedown / URL
 * mati) → tanpa retry. 408/429/5xx/IO = transient → tetap di-retry 8×.
 * 429 CDN ≠ 429 API Sanka (kategori berbeda: yang satu pembatas host
 * file, yang satu pembatas API — L-2 mengatur API).
 */
object HttpFailFast {
    fun isPermanent(code: Int): Boolean =
        code in 400..499 && code != 408 && code != 429
}

/**
 * Resolve episode Sanka menjadi sumber yang bisa diputar.
 *
 * Prioritas putar — RANTAI FALLBACK LINTAS-SUMBER (v2.0.7 item 14–16,
 * PRD v2.0.8 item 1–3; keputusan Faygo 05:28 WIB):
 *  1. Animekompi direct — Pixeldrain 720p → token stream 720p →
 *     Blogger ekstrak (itag 22 → 18).
 *  2. ok.ru ekstraksi dari URL embed Animekompi (tier hd → sd).
 *  3. Samehadaku episode → Blogger ekstrak (itag 22 → 18).
 *  4. Gagal jujur — toast + keluar, seperti asli (v2.0.8 item 5,
 *     D-web: opsi "Putar lewat website?" + jalur pemutar website
 *     DIHAPUS).
 *
 * Tiap lapis WAJIB mencoba mengambil file-nya dulu (ekstraksi) supaya
 * video tetap diputar di kontrol player aplikasi sendiri (Media3) —
 * tujuan rantai ini. Lapis 2–3 MALAS: tidak di-fetch bila lapis
 * sebelumnya sukses (nol tambahan); diambil belakangan oleh player
 * hanya bila semua kandidat sebelumnya gagal (pola
 * fetchBloggerCandidates), KECUALI bila lapis 1 kosong sama sekali —
 * saat itu diambil saat resolve seperti Blogger (kalau tidak, player
 * gagal tanpa pernah mencoba lapis berikut).
 *
 * URL hasil ekstrak/token diambil SEGAR tiap putar (di sini, saat
 * resolve) — TIDAK PERNAH disimpan di DB/riwayat (kedaluwarsa: expire +
 * sig + terikat IP). Fallback antar sumber OTOMATIS (player mencoba
 * kandidat berikutnya bila yang sekarang error) — tanpa tanya pengguna.
 * Bila semua gagal → toast + keluar (gagal jujur).
 */
class PlayerRepository(
    private val sankaRepository: SankaRepository,
    private val samehadakuRepository: SamehadakuRepository,
    /**
     * Logger diagnostik (v2.0.9 item 4) — null = tanpa log (dipakai suite
     * uji). HANYA mencatat — tidak mengubah alur / retry / pengecualian.
     */
    private val diagLogger: DiagLogger? = null
) {

    private val bloggerClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Client fetch halaman embed ok.ru (lapis 2) — timeout sama seperti
     * bloggerClient (15/30 dtk). UA browser + Referer Sanka dipasang
     * per request (riset embed-only: halaman ok.ru diambil dengan UA
     * Chrome Android + Referer halaman episode).
     */
    private val okRuClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun resolve(episodeSlug: String): EpisodeSources {
        val episode = sankaRepository.episode(episodeSlug)

        // (1) Blogger hasil ekstrak itag 22 (720p) → 18 (360p) — LAZY (#6):
        // bila kandidat prioritas lebih tinggi (direct/token) sudah ada,
        // HTML Blogger TIDAK diambil sekarang (boros — satu request +
        // ~28 KB tiap putar). Player mengambilnya belakangan hanya bila
        // semua kandidat itu gagal (lihat VideoPlayActivity.tryNextCandidate
        // + fetchBloggerCandidates).
        // Bila tidak ada direct/token sama sekali, ambil sekarang seperti
        // dulu — Blogger adalah satu-satunya jalan 720p yang tersisa.
        val bloggerUrls = if (hasDirectOrToken(episode)) {
            emptyMap()
        } else {
            val bloggerUrl = bloggerIframeUrl(episode)
            if (!bloggerUrl.isNullOrBlank()) {
                // M-5: JANGAN runCatching di sini — ia menelan
                // CancellationException. Lempar ulang pembatalan.
                try {
                    fetchBloggerUrls(bloggerUrl)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyMap()
                }
            } else {
                emptyMap()
            }
        }

        // (2) ok.ru ekstraksi — MALAS bila lapis 1 ada (nol tambahan);
        // EAGER bila lapis 1 KOSONG sama sekali (tidak ada direct/token/
        // Blogger) — seperti Blogger di atas. Tanpa ini, episode
        // embed-only langsung gagal tanpa pernah mencoba ok.ru.
        val hasLayer1 = hasDirectOrToken(episode) || bloggerUrls.isNotEmpty()
        val okRuUrls: Map<String, String> = if (hasLayer1) {
            emptyMap()
        } else {
            val okRuUrl = okRuEmbedUrl(episode)
            if (okRuUrl.isNullOrBlank()) {
                emptyMap()
            } else {
                // M-5: JANGAN runCatching di sini — ia menelan
                // CancellationException. Lempar ulang pembatalan.
                try {
                    fetchOkRuUrls(okRuUrl)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyMap()
                }
            }
        }

        // (3) Samehadaku Blogger — MALAS bila lapis 1–2 sukses; EAGER
        // bila keduanya masih kosong (alasan sama seperti ok.ru di atas).
        val hasLayer2 = okRuUrls["hd"] != null || okRuUrls["sd"] != null
        val samehadakuCandidates: List<PlayableSource> = if (hasLayer1 || hasLayer2) {
            emptyList()
        } else {
            // M-5: JANGAN runCatching di sini — ia menelan
            // CancellationException. Lempar ulang pembatalan.
            // L-2: 429 JANGAN ditelan jadi daftar kosong — lempar ulang
            // supaya sampai ke loadEpisode (toast rate-limit + keluar)
            // dan lapis malas tidak memanggil ulang Samehadaku sesudah
            // rate limit.
            try {
                fetchSamehadakuCandidates(
                    episode.animeTitle ?: "", episode.episodeNumber
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: RateLimitException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }

        // v2.0.9 item 4: hitungan per lapis — supaya Konsol menunjukkan
        // lapis mana yang mengisi kandidat dan mana yang kosong.
        val directCount = if (episode.sources.orEmpty().any { isPixeldrain(it.url) }) 1 else 0
        val tokenCount = if (episode.mirrors.orEmpty().any {
                it.type == "mp4" && isStreamToken(it.url)
            }) 1 else 0
        diagLogger?.info(
            DiagLog.TAG_REPO,
            "Ep ${episode.episodeNumber}: resolve → " +
                "${directCount + tokenCount + bloggerUrls.size + okRuUrls.size + samehadakuCandidates.size} kandidat " +
                "(direct $directCount/token $tokenCount/blogger ${bloggerUrls.size}/" +
                "ok.ru ${okRuUrls.size}/samehadaku ${samehadakuCandidates.size})"
        )
        return buildCandidates(episode, bloggerUrls, okRuUrls, samehadakuCandidates)
    }

    /**
     * Ambil + ekstrak URL Blogger (lazy — dipanggil player hanya bila
     * kandidat direct/token sudah gagal dan sebelum mencoba lapis
     * berikut). Gagal jaringan / bukan Blogger → daftar kosong.
     */
    suspend fun fetchBloggerCandidates(
        iframeUrl: String,
        episodeNumber: Int = 0
    ): List<PlayableSource> {
        // L-15: JANGAN runCatching di sini — ia menelan
        // CancellationException. Lempar ulang pembatalan.
        val t0 = System.currentTimeMillis()
        val urls = try {
            fetchBloggerUrls(iframeUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagLogger?.warn(
                DiagLog.TAG_REPO,
                "${epPrefix(episodeNumber)}Blogger lazy gagal (${e.javaClass.simpleName})"
            )
            return emptyList()
        }
        val result = buildList {
            urls[22]?.let { add(PlayableSource(it, PlayableKind.BLOGGER_720, "720p")) }
            urls[18]?.let { add(PlayableSource(it, PlayableKind.BLOGGER_360, "360p")) }
        }
        diagLogger?.info(
            DiagLog.TAG_REPO,
            "${epPrefix(episodeNumber)}Blogger lazy → ${result.size} kandidat " +
                "(${System.currentTimeMillis() - t0}ms)"
        )
        return result
    }

    /**
     * Ambil + ekstrak tier video ok.ru (lapis 2 — lazy, dipanggil player
     * hanya bila semua kandidat lapis 1 gagal dan sebelum jatuh ke
     * Samehadaku). Gagal jaringan / bukan ok.ru / tanpa tier →
     * daftar kosong (lanjut ke lapis berikut, bukan error).
     */
    suspend fun fetchOkRuCandidates(
        embedUrl: String,
        episodeNumber: Int = 0
    ): List<PlayableSource> {
        // L-15: JANGAN runCatching di sini — ia menelan
        // CancellationException. Lempar ulang pembatalan.
        val t0 = System.currentTimeMillis()
        val urls = try {
            fetchOkRuUrls(embedUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagLogger?.warn(
                DiagLog.TAG_REPO,
                "${epPrefix(episodeNumber)}ok.ru lazy gagal (${e.javaClass.simpleName})"
            )
            return emptyList()
        }
        val result = buildList {
            urls["hd"]?.let { add(PlayableSource(it, PlayableKind.OKRU_720, "720p")) }
            urls["sd"]?.let { add(PlayableSource(it, PlayableKind.OKRU_360, "480p")) }
        }
        diagLogger?.info(
            DiagLog.TAG_REPO,
            "${epPrefix(episodeNumber)}ok.ru lazy → ${result.size} kandidat " +
                "(${System.currentTimeMillis() - t0}ms)"
        )
        return result
    }

    /**
     * Ambil kandidat Samehadaku (lapis 3 — lazy, dipanggil player hanya
     * bila lapis 1–2 gagal): cari episode yang sama di Samehadaku →
     * URL Blogger-nya → ekstrak itag 22/18 (pakai ulang
     * fetchBloggerUrls). Gagal apa pun → daftar kosong (gagal jujur,
     * bukan error).
     */
    suspend fun fetchSamehadakuCandidates(
        seriesTitle: String,
        episodeNumber: Int
    ): List<PlayableSource> {
        val t0 = System.currentTimeMillis()
        val bloggerUrl = try {
            samehadakuRepository.resolveEpisodeUrl(seriesTitle, episodeNumber)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RateLimitException) {
            // L-2: 429 JANGAN ditelan jadi null — lempar ke pemanggil
            // (launchLazyLayer) supaya jadi toast rate-limit + keluar.
            throw e
        } catch (e: Exception) {
            diagLogger?.warn(
                DiagLog.TAG_REPO,
                "Ep $episodeNumber: Samehadaku lazy gagal (${e.javaClass.simpleName})"
            )
            return emptyList()
        }
        if (bloggerUrl.isNullOrBlank()) {
            diagLogger?.info(
                DiagLog.TAG_REPO,
                "Ep $episodeNumber: Samehadaku lazy → 0 kandidat " +
                    "(${System.currentTimeMillis() - t0}ms)"
            )
            return emptyList()
        }
        // L-15: JANGAN runCatching di sini — ia menelan
        // CancellationException. Lempar ulang pembatalan.
        val urls = try {
            fetchBloggerUrls(bloggerUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagLogger?.warn(
                DiagLog.TAG_REPO,
                "Ep $episodeNumber: Samehadaku lazy gagal (${e.javaClass.simpleName})"
            )
            return emptyList()
        }
        val result = buildList {
            urls[22]?.let { add(PlayableSource(it, PlayableKind.SAMEHADAKU_720, "720p")) }
            urls[18]?.let { add(PlayableSource(it, PlayableKind.SAMEHADAKU_360, "360p")) }
        }
        diagLogger?.info(
            DiagLog.TAG_REPO,
            "Ep $episodeNumber: Samehadaku lazy → ${result.size} kandidat " +
                "(${System.currentTimeMillis() - t0}ms)"
        )
        return result
    }

    /** Awalan "Ep <n>: " untuk baris log lazy — kosong bila nomor tidak
     *  diketahui (0, dari pemanggil lama / harness). */
    private fun epPrefix(episodeNumber: Int): String =
        if (episodeNumber > 0) "Ep $episodeNumber: " else ""

    /** Ada kandidat di atas Blogger (direct 720p atau token 720p)? */
    private fun hasDirectOrToken(episode: SankaEpisodeData): Boolean {
        val hasDirect = episode.sources.orEmpty().any { item -> isPixeldrain(item.url) }
        if (hasDirect) return true
        return episode.mirrors.orEmpty().any { mirror ->
            mirror.type == "mp4" && isStreamToken(mirror.url)
        }
    }

    companion object {
        /**
         * Susun kandidat putar dari respons /episode + URL Blogger hasil
         * ekstrak — fungsi MURNI (tanpa jaringan, bisa diuji unit).
         * Prioritas RANTAI FALLBACK (v2.0.7 item 14–16; v2.0.8 item 5):
         * (1) Pixeldrain direct 720p, (2) token Animekompi 720p,
         * (3) Blogger itag 22 → 18, (4) ok.ru tier hd → sd,
         * (5) Samehadaku Blogger itag 22 → 18 — lalu gagal jujur
         * (toast + keluar). Langkah (6) embed DIHAPUS (D-web).
         * (1b) "direct lain" DIHAPUS (L1): tidak ada host allowlist yang
         * bisa diberikan untuk kind "direct" generik — satu-satunya file
         * direct yang terverifikasi adalah Pixeldrain ("BerkasDrive" di
         * riset ternyata halaman player = embed, bukan file).
         */
        fun buildCandidates(
            episode: SankaEpisodeData,
            bloggerUrls: Map<Int, String>,
            okRuUrls: Map<String, String> = emptyMap(),
            samehadakuCandidates: List<PlayableSource> = emptyList()
        ): EpisodeSources {
            val candidates = mutableListOf<PlayableSource>()
            var downloadUrl: String? = null

            // (1) Pixeldrain direct 720p dari sources[].
            val pixeldrain = episode.sources.orEmpty().firstOrNull { item ->
                isPixeldrain(item.url)
            }
            val pixeldrainUrl = pixeldrain?.url?.takeIf { it.isNotBlank() }
            if (pixeldrainUrl != null) {
                candidates += PlayableSource(pixeldrainUrl, PlayableKind.DIRECT_720, "720p")
                downloadUrl = pixeldrainUrl
            }

            // (2) Stream token Animekompi 720p dari mirrors[] (type "mp4").
            val token = episode.mirrors.orEmpty().firstOrNull { mirror ->
                mirror.type == "mp4" && isStreamToken(mirror.url)
            }
            val tokenUrl = token?.url?.takeIf { it.isNotBlank() }
            if (tokenUrl != null) {
                candidates += PlayableSource(tokenUrl, PlayableKind.TOKEN_720, "720p")
            }

            // (3) Blogger hasil ekstrak itag 22 (720p) → 18 (360p).
            bloggerUrls[22]?.let { url ->
                candidates += PlayableSource(url, PlayableKind.BLOGGER_720, "720p")
            }
            bloggerUrls[18]?.let { url ->
                candidates += PlayableSource(url, PlayableKind.BLOGGER_360, "360p")
            }

            // (4) ok.ru hasil ekstrak tier hd (720p) → sd (480p) — lapis 2.
            okRuUrls["hd"]?.let { url ->
                candidates += PlayableSource(url, PlayableKind.OKRU_720, "720p")
            }
            okRuUrls["sd"]?.let { url ->
                candidates += PlayableSource(url, PlayableKind.OKRU_360, "480p")
            }

            // (5) Samehadaku Blogger hasil ekstrak (itag 22 → 18) — lapis 3.
            candidates += samehadakuCandidates

            return EpisodeSources(
                candidates = candidates,
                downloadUrl = downloadUrl,
                episodeNumber = episode.episodeNumber,
                episodeTitle = episode.title ?: "",
                prevSlug = episode.prevSlug,
                nextSlug = episode.nextSlug,
                episodeList = episode.episodeList.orEmpty(),
                bloggerIframeUrl = bloggerIframeUrl(episode),
                animeTitle = episode.animeTitle ?: "",
                okRuEmbedUrl = okRuEmbedUrl(episode)
            )
        }

        /** URL embed ok.ru episode ini (sources[] / default_iframe). */
        private fun okRuEmbedUrl(episode: SankaEpisodeData): String? =
            episode.sources.orEmpty()
                .firstOrNull { isOkRuEmbed(it.url) }?.url
                ?: episode.defaultIframe?.takeIf { isOkRuEmbed(it) }

        /** URL iframe Blogger untuk episode ini (sumber embed / default_iframe). */
        private fun bloggerIframeUrl(episode: SankaEpisodeData): String? =
            episode.sources.orEmpty()
                .firstOrNull { isBlogger(it.url) }?.url
                ?: episode.defaultIframe?.takeIf { isBlogger(it) }

        /**
         * Validasi URL dari respons Sanka (L1 — Pentest v2): semua URL
         * kandidat HARUS https + host yang diharapkan, dinilai dari URI
         * yang di-parse (java.net.URI — bukan android.net.Uri, supaya
         * tetap bisa diuji unit di JVM). Cek substring TIDAK cukup
         * ("pixeldrain.com/api/file/" di path/query host lain lolos).
         * Gagal parse / bukan https / tanpa host → null (ditolak).
         */
        private fun parseHttpsUrl(url: String?): URI? {
            val uri = try {
                URI((url ?: "").trim())
            } catch (e: Exception) {
                return null
            }
            if (uri.scheme?.lowercase() != "https") return null
            if (uri.host.isNullOrBlank()) return null
            return uri
        }

        /**
         * Pixeldrain direct = URL file langsung (bukan halaman daftar).
         * Bentuk live: kind "direct", label "Pixel HD",
         * URL https://pixeldrain.com/api/file/<id>.
         * Halaman daftar pixeldrain.com/u/<id> (yang ada di downloads[])
         * BUKAN file — tidak diterima di sini.
         */
        private fun isPixeldrain(url: String?): Boolean {
            val uri = parseHttpsUrl(url) ?: return false
            if (uri.host.lowercase() != "pixeldrain.com") return false
            return uri.path?.startsWith("/api/file/") == true
        }

        /**
         * Blogger embed: hanya URL-nya yang dinilai (L1) — label "gdrive"/
         * "blogger" dengan host sembarang TIDAK diterima lagi. Bentuk live:
         * https://www.blogger.com/video.g?token=…
         */
        private fun isBlogger(url: String?): Boolean {
            val uri = parseHttpsUrl(url) ?: return false
            val host = uri.host.lowercase()
            return host == "blogger.com" || host.endsWith(".blogger.com")
        }

        /**
         * Embed ok.ru: hanya URL-nya yang dinilai (L1) — label "OK.ru"
         * dengan host sembarang TIDAK diterima. Bentuk live:
         * https://ok.ru/videoembed/<id>. Perantara
         * anichin-player.web.id TIDAK diterima di sini (itu bagian lapis
         * Donghua yang tidak aktif — D-donghua).
         */
        private fun isOkRuEmbed(url: String?): Boolean {
            val uri = parseHttpsUrl(url) ?: return false
            if (uri.host.lowercase() != "ok.ru") return false
            return uri.path?.startsWith("/videoembed/") == true
        }

        /** Stream token Animekompi: https + host v10/v11.animekompi.sbs (L1). */
        private fun isStreamToken(url: String?): Boolean {
            val uri = parseHttpsUrl(url) ?: return false
            val host = uri.host.lowercase()
            return host == "v10.animekompi.sbs" || host == "v11.animekompi.sbs"
        }

        /**
         * Ekstrak URL videoplayback dari HTML embed Blogger — fungsi MURNI
         * (tanpa jaringan, bisa diuji unit).
         *
         * Aturan (T1b, diuji live pada HTML Ao no Hako S2 ep 1, 27.708 byte):
         * - Cari `https://<host>---sn-<…>.googlevideo.com/videoplayback…`
         * - Potong di urutan `\&quot;` (backslash + &quot;) — BUKAN di
         *   backslash pertama (URL mengandung escape \u003d DI DALAM
         *   query string; memotong di situ memenggal URL).
         * - Decode escape: \u003d → "=", \u0026 → "&", \u002F → "/".
         * - Ambil itag dari query: 22 = 720p, 18 = 360p. Tanpa itag → buang.
         * - Bila satu itag muncul berkali-kali (varian param berbeda),
         *   pakai yang pertama ditemukan.
         * - Host SUDAH dikunci regex ke `*.googlevideo.com` (L1): pola di
         *   atas memuat host itu secara literal — URL hasil ekstrak tidak
         *   perlu validasi host tambahan (ditegaskan lagi di bawah).
         */
        fun extractBloggerUrls(html: String): Map<Int, String> {
            val result = mutableMapOf<Int, String>()
            val pattern = Regex(
                """https://[a-z0-9\-]+---sn-[a-z0-9\-]+\.googlevideo\.com/videoplayback.*?\\&quot;"""
            )
            val itagPattern = Regex("[?&]itag=(\\d+)")
            for (match in pattern.findAll(html)) {
                var url = match.value.dropLast("\\&quot;".length)
                url = url
                    .replace("\\u003d", "=")
                    .replace("\\u003D", "=")
                    .replace("\\u0026", "&")
                    .replace("\\u002F", "/")
                // L1: tegaskan host hasil decode masih googlevideo (regex
                // sudah menguncinya — yang keluar dari pola dibuang).
                if (!url.startsWith("https://") ||
                    ".googlevideo.com/videoplayback" !in url
                ) continue
                val itag = itagPattern.find(url)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: continue
                if ((itag == 22 || itag == 18) && itag !in result) {
                    result[itag] = url
                }
            }
            return result
        }

        /**
         * Ekstrak URL video langsung dari HTML embed ok.ru — fungsi MURNI
         * (tanpa jaringan, bisa diuji unit).
         *
         * Teknik (riset embed-only v2.0.5 + multisumber v2.0.8 — terbukti
         * live: Martial Master 710, Shrouding the Heavens 190, One Slash
         * Ep 01 — halaman 200 ~42 KB, 6 tier):
         * - JSON player ter-encode entitas HTML: cari `"videos":[...]`
         *   (bentuk mentah ATAU `&quot;videos&quot;:` — keduanya
         *   ditangani: normalisasi entitas dulu, lalu cari bentuk mentah).
         * - Array berisi 6 tier: mobile / lowest / low / sd / hd / full
         *   → URL `https://ok<…>.vkuser.net/?expires=…&srcIp=…&sig=…`.
         * - Tiap objek `{…}`: ambil `name` + `url` (urutan apa pun),
         *   unescape JSON (`\/` → `/`, `\u0026` → `&`, `\u003d` → `=`).
         * - Host hasil ekstrak DIKUNCI ke `*.vkuser.net` (L1): URL host
         *   lain dibuang. Tier pertama menang bila satu nama muncul
         *   berkali-kali.
         * - Tanpa `videos[]` → peta kosong (bukan error).
         * Pemakaian: `fetchOkRuCandidates` memakai tier `hd` → `sd`
         * saja (`full` = 1080p — DILARANG di aplikasi ini).
         */
        fun extractOkRuUrls(html: String): Map<String, String> {
            // 1. Normalisasi entitas HTML supaya kedua bentuk (mentah /
            //    ter-encode) bisa dicari dengan satu pola.
            val normalized = html
                .replace("&quot;", "\"")
                .replace("&#34;", "\"")
                .replace("&#x22;", "\"")
                .replace("&amp;", "&")
            // 2. Cari array videos[] — pindai sampai ']' penutup dengan
            //    sadar string (lewati \" dan \\ di dalam URL).
            val videosKey = Regex(""""videos"\s*:\s*\[""").find(normalized)
                ?: return emptyMap()
            val arrayStart = videosKey.range.last
            var depth = 0
            var end = -1
            var i = arrayStart
            var inString = false
            while (i < normalized.length) {
                val ch = normalized[i]
                if (inString) {
                    if (ch == '\\') {
                        i += 2
                        continue
                    }
                    if (ch == '"') inString = false
                } else {
                    when (ch) {
                        '"' -> inString = true
                        '[' -> depth++
                        ']' -> {
                            depth--
                            if (depth == 0) {
                                end = i
                                break
                            }
                        }
                    }
                }
                i++
            }
            if (end < 0) return emptyMap()
            val arrayText = normalized.substring(arrayStart, end + 1)
            // 3. Tiap objek {…}: ambil name + url, unescape, kunci host.
            val result = mutableMapOf<String, String>()
            for (obj in Regex("""\{[^{}]*\}""").findAll(arrayText)) {
                val text = obj.value
                val name = Regex(""""name"\s*:\s*"([^"]+)""")
                    .find(text)?.groupValues?.getOrNull(1) ?: continue
                val rawUrl = Regex(""""url"\s*:\s*"((?:\\.|[^"\\])*)""")
                    .find(text)?.groupValues?.getOrNull(1) ?: continue
                val url = rawUrl
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
                    .replace("\\u003d", "=")
                    .replace("\\u003D", "=")
                    .replace("\\u002f", "/")
                    .replace("\\u002F", "/")
                    .replace("\\\\", "\\")
                val uri = try {
                    URI(url)
                } catch (e: Exception) {
                    continue
                }
                if (uri.scheme?.lowercase() != "https") continue
                val host = uri.host?.lowercase() ?: continue
                if (host != "vkuser.net" && !host.endsWith(".vkuser.net")) continue
                if (name !in result) result[name] = url
            }
            return result
        }

        /**
         * UA browser untuk fetch halaman embed ok.ru (riset embed-only:
         * halaman ok.ru diambil dengan UA Chrome Android + Referer
         * halaman episode — OkHttp default ("okhttp/…") tidak dipakai).
         */
        const val OKRU_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /**
         * Header Referer playback per-kind (v2.0.8 item 2) — fungsi
         * MURNI supaya harness bisa mengujinya tanpa Media3: ok.ru →
         * Referer https://ok.ru/ (host video; hardening — probe sandbox
         * 2026-11-19 menunjukkan tidak ada perbedaan status/TTFB
         * dengan/tanpa Referer, tapi origin jujur tidak merugikan);
         * token/Blogger/Samehadaku/Pixeldrain → TANPA Referer sampai
         * probe membuktikan perlu. UA dipasang di factory (setupPlayer),
         * bukan per-request.
         */
        fun headersFor(kind: PlayableKind): Map<String, String> =
            if (kind == PlayableKind.OKRU_720 || kind == PlayableKind.OKRU_360) {
                mapOf("Referer" to "https://ok.ru/")
            } else {
                emptyMap()
            }

        /**
         * Request GET iframe Blogger (v2.0.8 item 4) — fungsi MURNI
         * supaya harness bisa memeriksa headernya tanpa OkHttp: UA
         * browser + Referer Sanka, sama seperti fetchOkRuUrls
         * (sebelumnya request polos tanpa header — tidak konsisten).
         */
        fun buildBloggerRequest(iframeUrl: String): Request =
            Request.Builder().url(iframeUrl)
                .header("User-Agent", OKRU_USER_AGENT)
                .header("Referer", Constants.SANKA_BASE_URL)
                .build()
    }

    /**
     * Ambil HTML iframe Blogger lalu ekstrak URL videoplayback.
     * Gagal jaringan / bukan Blogger → peta kosong.
     */
    private suspend fun fetchBloggerUrls(iframeUrl: String): Map<Int, String> {
        // L1: jangan GET ke host sembarang — hanya iframe Blogger.
        if (!isBlogger(iframeUrl)) return emptyMap()
        return withContext(Dispatchers.IO) {
            // v2.0.8 item 4: UA + Referer via buildBloggerRequest (sama
            // seperti fetchOkRuUrls) — request polos sebelumnya tidak
            // konsisten.
            val request = buildBloggerRequest(iframeUrl)
            bloggerClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyMap<Int, String>()
                val html = response.body?.string() ?: return@withContext emptyMap<Int, String>()
                extractBloggerUrls(html)
            }
        }
    }

    /**
     * Ambil HTML embed ok.ru lalu ekstrak tier video (lapis 2).
     * Gagal jaringan / bukan ok.ru → peta kosong (lanjut ke lapis berikut).
     */
    private suspend fun fetchOkRuUrls(embedUrl: String): Map<String, String> {
        // L1: jangan GET ke host sembarang — hanya embed ok.ru.
        if (!isOkRuEmbed(embedUrl)) return emptyMap()
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(embedUrl)
                .header("User-Agent", OKRU_USER_AGENT)
                .header("Referer", Constants.SANKA_BASE_URL)
                .build()
            okRuClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyMap<String, String>()
                val html = response.body?.string() ?: return@withContext emptyMap<String, String>()
                extractOkRuUrls(html)
            }
        }
    }
}
