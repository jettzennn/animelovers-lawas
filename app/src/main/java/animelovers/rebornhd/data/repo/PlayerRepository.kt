package animelovers.rebornhd.data.repo

import animelovers.rebornhd.data.model.PlayableKind
import animelovers.rebornhd.data.model.PlayableSource
import animelovers.rebornhd.data.model.SankaEpisodeData
import animelovers.rebornhd.data.model.SankaEpisodeListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Hasil resolve satu episode — daftar kandidat putar + info pendukung.
 *
 * @property candidates kandidat Media3/WebView, terurut sesuai prioritas
 *   T1b (Pixeldrain 720p → token 720p → Blogger 22 → Blogger 18 → embed).
 * @property embedUrl URL embed untuk WebView (default_iframe / sumber embed).
 * @property downloadUrl URL file .mp4 langsung (Pixeldrain) untuk
 *   DownloadManager — null bila episode tidak punya sumber Pixeldrain
 *   (downloads[] Sanka HANYA berisi halaman unduhan, bukan URL file).
 * @property prevSlug / nextSlug slug episode sebelum/berikutnya (null bila
 *   tidak ada) — dari respons /episode.
 * @property episodeList daftar episode seri ini (untuk next/prev player).
 * @property bloggerIframeUrl URL iframe Blogger untuk fetch lazy (#6):
 *   bila kandidat direct/token sudah ada, HTML Blogger TIDAK diambil saat
 *   resolve — player mengambilnya belakangan hanya bila semua kandidat
 *   itu gagal sebelum jatuh ke embed.
 */
data class EpisodeSources(
    val candidates: List<PlayableSource>,
    val embedUrl: String?,
    val downloadUrl: String?,
    val episodeNumber: Int,
    val episodeTitle: String,
    val prevSlug: String?,
    val nextSlug: String?,
    val episodeList: List<SankaEpisodeListItem>,
    val bloggerIframeUrl: String? = null
)

/**
 * Resolve episode Sanka menjadi sumber yang bisa diputar.
 *
 * Prioritas putar (REVISI T1b — riset/api-lokal-t1b-2026-11-17.md,
 * menggantikan PRD v2 §3.3):
 *  1. Pixeldrain direct 720p dari `sources[]` (kind "direct", label
 *     "Pixel HD", URL https://pixeldrain.com/api/file/<id>).
 *  2. Stream token Animekompi 720p dari `mirrors[]` (type "mp4" —
 *     URL https://v10.animekompi.sbs/api/episodes/stream/<token>,
 *     206 Partial Content video/mp4 terbukti live).
 *  3. Blogger hasil ekstrak: ambil HTML `default_iframe`
 *     (blogger.com/video.g?token=…), regex URL videoplayback,
 *     potong di `\&quot;`, decode escape \uXXXX — pilih itag 22 (720p)
 *     dulu, lalu itag 18 (360p).
 *  4. WebView embed (`default_iframe` atau sumber embed pertama) —
 *     fallback terakhir (D2 = W).
 *
 * URL hasil ekstrak/token diambil SEGAR tiap putar (di sini, saat
 * resolve) — TIDAK PERNAH disimpan di DB/riwayat (kedaluwarsa: expire +
 * sig + terikat IP). Fallback antar sumber OTOMATIS (player mencoba
 * kandidat berikutnya bila yang sekarang error) — tanpa tanya pengguna.
 * Bila semua gagal → candidates kosong + embedUrl null → player
 * menampilkan toast jujur.
 */
class PlayerRepository(
    private val sankaRepository: SankaRepository
) {

    private val bloggerClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun resolve(episodeSlug: String): EpisodeSources {
        val episode = sankaRepository.episode(episodeSlug)

        // (3) Blogger hasil ekstrak itag 22 (720p) → 18 (360p) — LAZY (#6):
        // bila kandidat prioritas lebih tinggi (direct/token) sudah ada,
        // HTML Blogger TIDAK diambil sekarang (boros — satu request +
        // ~28 KB tiap putar). Player mengambilnya belakangan hanya bila
        // semua kandidat itu gagal sebelum jatuh ke embed (lihat
        // VideoPlayActivity.tryNextCandidate + fetchBloggerCandidates).
        // Bila tidak ada direct/token sama sekali, ambil sekarang seperti
        // dulu — Blogger adalah satu-satunya jalan 720p yang tersisa.
        val bloggerUrls = if (hasDirectOrToken(episode)) {
            emptyMap()
        } else {
            val bloggerUrl = bloggerIframeUrl(episode)
            if (!bloggerUrl.isNullOrBlank()) {
                runCatching { fetchBloggerUrls(bloggerUrl) }.getOrDefault(emptyMap())
            } else {
                emptyMap()
            }
        }
        return buildCandidates(episode, bloggerUrls)
    }

    /**
     * Ambil + ekstrak URL Blogger (lazy — dipanggil player hanya bila
     * kandidat direct/token sudah gagal dan sebelum jatuh ke embed).
     * Gagal jaringan / bukan Blogger → daftar kosong (fallback ke embed).
     */
    suspend fun fetchBloggerCandidates(iframeUrl: String): List<PlayableSource> {
        val urls = runCatching { fetchBloggerUrls(iframeUrl) }.getOrDefault(emptyMap())
        return buildList {
            urls[22]?.let { add(PlayableSource(it, PlayableKind.BLOGGER_720, "720p")) }
            urls[18]?.let { add(PlayableSource(it, PlayableKind.BLOGGER_360, "360p")) }
        }
    }

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
         * Prioritas T1b: (1) Pixeldrain direct 720p, (2) token Animekompi
         * 720p, (3) Blogger itag 22 → 18, (4) WebView embed.
         * (1b) "direct lain" DIHAPUS (L1): tidak ada host allowlist yang
         * bisa diberikan untuk kind "direct" generik — satu-satunya file
         * direct yang terverifikasi adalah Pixeldrain ("BerkasDrive" di
         * riset ternyata halaman player = embed, bukan file).
         */
        fun buildCandidates(
            episode: SankaEpisodeData,
            bloggerUrls: Map<Int, String>
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

            // (4) WebView embed — fallback terakhir. URL embed wajib https +
            // host publik (L1): host literal IP/localhost ditolak.
            val embedUrl = episode.defaultIframe
                ?.takeIf { it.isNotBlank() && isHttpsPublicHost(it) }
                ?: episode.sources.orEmpty()
                    .firstOrNull {
                        it.kind == "embed" && !it.url.isNullOrBlank() &&
                            isHttpsPublicHost(it.url)
                    }?.url
            if (!embedUrl.isNullOrBlank()) {
                candidates += PlayableSource(
                    embedUrl, PlayableKind.EMBED, "480p", isWebView = true
                )
            }

            return EpisodeSources(
                candidates = candidates,
                embedUrl = embedUrl,
                downloadUrl = downloadUrl,
                episodeNumber = episode.episodeNumber,
                episodeTitle = episode.title ?: "",
                prevSlug = episode.prevSlug,
                nextSlug = episode.nextSlug,
                episodeList = episode.episodeList.orEmpty(),
                bloggerIframeUrl = bloggerIframeUrl(episode)
            )
        }

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

        /** Host literal IP / localhost — tidak boleh jadi tujuan video/unduh/embed. */
        private fun isIpOrLocalhostHost(host: String): Boolean {
            val h = host.lowercase()
            if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local")) return true
            if (':' in h) return true // literal IPv6
            return h.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$"))
        }

        /**
         * https + host BUKAN literal IP/localhost — untuk embed dan direct
         * generik yang host-nya memang bervariasi (allowlist tidak mungkin:
         * host embed di data live saja sudah blogger.com, abyssplayer.com,
         * mecdn.website, ok.ru, rumble.com).
         */
        private fun isHttpsPublicHost(url: String?): Boolean {
            val uri = parseHttpsUrl(url) ?: return false
            return !isIpOrLocalhostHost(uri.host)
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
    }

    /**
     * Ambil HTML iframe Blogger lalu ekstrak URL videoplayback.
     * Gagal jaringan / bukan Blogger → peta kosong (fallback ke embed).
     */
    private suspend fun fetchBloggerUrls(iframeUrl: String): Map<Int, String> {
        // L1: jangan GET ke host sembarang — hanya iframe Blogger.
        if (!isBlogger(iframeUrl)) return emptyMap()
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(iframeUrl).build()
            bloggerClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyMap<Int, String>()
                val html = response.body?.string() ?: return@withContext emptyMap<Int, String>()
                extractBloggerUrls(html)
            }
        }
    }
}
