package animelovers.rebornhd.data.repo

import android.util.Log
import animelovers.rebornhd.data.api.RateLimitException
import animelovers.rebornhd.data.api.SankaHttpException
import animelovers.rebornhd.data.api.SankaTransport
import animelovers.rebornhd.data.model.SamehadakuEpisodeData
import animelovers.rebornhd.data.model.SamehadakuSearchData
import animelovers.rebornhd.data.model.SamehadakuSearchItem
import animelovers.rebornhd.data.model.SankaEnvelope
import animelovers.rebornhd.util.DiagError
import animelovers.rebornhd.util.DiagLog
import animelovers.rebornhd.util.DiagLogger
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.lang.reflect.Type

private const val TAG = "SamehadakuRepository"

/**
 * 500 dari Samehadaku = "judul tidak ada di sumber ini" (riset 05:17
 * WIB: Samehadaku anime-only — judul yang tidak ada di situsnya, mis.
 * judul donghua, dijawab HTTP 500 "Error fetching data from Samehadaku",
 * bukan 404). Bukan error fatal — lanjut ke lapis berikut TANPA retry.
 */
class SamehadakuNotFoundException : Exception("Samehadaku: judul tidak ada")

/**
 * Adapter Samehadaku — sumber kedua fallback putar (v2.0.7 item 16 /
 * PRD v2.0.8 item 2). Semua request LEWAT SankaTransport yang sama
 * (throttle 1.400 ms + Mutex berlaku untuk semua sumber — tidak ada
 * client bebas; 429 TIDAK PERNAH di-retry).
 *
 * Alur: search `q=<judul serial Animekompi>` → cocokkan SATU episode
 * (judul ternormalisasi + nomor musim + nomor episode) → endpoint
 * episode → `defaultStreamingUrl` (Blogger). Bila ragu (nol / lebih
 * dari satu kandidat cocok), JANGAN tebak — kembalikan null dan rantai
 * lanjut ke dialog fallback seperti biasa.
 *
 * Gagal apa pun (500 "tidak ada", timeout, parse rusak, tidak cocok)
 * = null, BUKAN exception — kecuali CancellationException dan
 * RateLimitException yang diteruskan. 429 (RateLimitException) TIDAK
 * ditelan jadi null (L-2): ia harus sampai ke pemanggil supaya jadi
 * toast rate-limit + keluar (PRD v2.0.7:22), bukan dialog "Putar
 * lewat website?". Pemanggil (PlayerRepository) memperlakukan null
 * sebagai "lapis ini kosong" dan lanjut ke lapis berikut.
 */
class SamehadakuRepository(
    private val transport: SankaTransport,
    /**
     * Logger diagnostik (v2.0.9 item 4) — null = tanpa log (dipakai suite
     * uji). HANYA mencatat — tidak mengubah alur / retry / pengecualian.
     */
    private val diagLogger: DiagLogger? = null
) {

    private val gson = Gson()

    // Tipe Gson untuk amplop Sanka — DIBANGUN DENGAN
    // TypeToken.getParameterized, BUKAN subclass TypeToken anonim
    // (v2.0.4, perbaikan R8 — sama seperti SankaRepository).
    private fun <T> envelopeType(dataClass: Class<T>): Type =
        TypeToken.getParameterized(SankaEnvelope::class.java, dataClass).type

    private fun <T> parseEnvelope(raw: String, dataClass: Class<T>): SankaEnvelope<T> =
        gson.fromJson(raw, envelopeType(dataClass))
            ?: throw IOException("Respons kosong dari Samehadaku")

    /**
     * Satu panggilan Samehadaku dengan aturan retry SENDIRI (bukan
     * callWithRetry SankaRepository — 500 di sini artinya "tidak ada",
     * bukan "scraper timeout"):
     * - 500 → SamehadakuNotFoundException, TANPA retry.
     * - 429 → RateLimitException, TANPA retry.
     * - HTTP lain → SankaHttpException asli, tanpa retry (gagal cepat).
     * - JsonSyntaxException → tanpa retry (data rusak).
     * - IOException jaringan → retry maks 3×, backoff 2 dtk lalu 4 dtk.
     * CancellationException TIDAK ditangkap — pembatalan harus lolos.
     */
    private suspend fun samehadakuGet(path: String): String {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return transport.getJson(path)
            } catch (e: SankaHttpException) {
                if (e.statusCode == 500) throw SamehadakuNotFoundException()
                throw e
            } catch (e: JsonSyntaxException) {
                throw e
            } catch (e: IOException) {
                if (attempt >= 3) throw e
                delay(2_000L * attempt)
                continue
            }
        }
    }

    /**
     * Cari episode yang sama di Samehadaku, kembalikan URL Blogger-nya
     * (`defaultStreamingUrl`) atau null bila lapis ini tidak punya.
     * TIDAK PERNAH melempar (kecuali CancellationException dan
     * RateLimitException — L-2).
     */
    suspend fun resolveEpisodeUrl(seriesTitle: String, episodeNumber: Int): String? {
        if (seriesTitle.isBlank() || episodeNumber <= 0) return null
        val t0 = System.currentTimeMillis()

        // 1. Search Samehadaku — itemnya EPISODE (20 hasil untuk satu judul).
        val searchRaw = try {
            samehadakuGet("anime/samehadaku/search?q=${encodeSegment(seriesTitle)}")
        } catch (e: SamehadakuNotFoundException) {
            Log.d(TAG, "search '$seriesTitle': 500 = tidak ada di Samehadaku")
            // v2.0.9 item 4: 500 = "judul tidak ada di sumber ini" (by
            // design, BUKAN error) → INFO, bukan ERROR.
            diagLogger?.info(DiagLog.TAG_REPO, "Samehadaku: '$seriesTitle' tidak ada (500)")
            return null
        } catch (e: RateLimitException) {
            // L-2: 429 JANGAN ditelan jadi null — lempar ke pemanggil
            // supaya jadi toast rate-limit + keluar (PRD v2.0.7:22),
            // bukan dialog "Putar lewat website?".
            Log.w(TAG, "search Samehadaku 429 — jangan retry")
            diagLogger?.error(DiagLog.TAG_REPO, "Samehadaku 429 (search)")
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // v2.0.10 item 2: nama kelas hasil rename R8 (1 huruf) tidak
            // boleh masuk log — pakai pesan yang bisa dibaca. Exception
            // tetap dilampirkan (stack lengkap di logcat).
            Log.w(TAG, "search Samehadaku gagal (${DiagError.describe(e)})", e)
            return null
        }
        val items = try {
            parseEnvelope(searchRaw, SamehadakuSearchData::class.java)
                .data?.animeList.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "search Samehadaku: respons tidak bisa dibaca", e)
            return null
        }
        val match = matchEpisode(items, seriesTitle, episodeNumber)
        if (match == null) {
            Log.d(
                TAG,
                "search '$seriesTitle' ep $episodeNumber: tidak ada pasangan " +
                    "unik (${items.size} item)"
            )
            diagLogger?.info(
                DiagLog.TAG_REPO,
                "Samehadaku: '$seriesTitle' ep $episodeNumber tidak ada " +
                    "pasangan unik (${items.size} item)"
            )
            return null
        }
        val slug = match.animeId ?: return null

        // 2. Episode Samehadaku → defaultStreamingUrl (Blogger).
        val epRaw = try {
            samehadakuGet("anime/samehadaku/episode/${encodeSegment(slug)}")
        } catch (e: SamehadakuNotFoundException) {
            Log.d(TAG, "episode '$slug': 500 = tidak ada di Samehadaku")
            diagLogger?.info(DiagLog.TAG_REPO, "Samehadaku: '$seriesTitle' tidak ada (500)")
            return null
        } catch (e: RateLimitException) {
            // L-2: sama seperti search di atas — 429 dilempar ulang,
            // bukan ditelan jadi null.
            Log.w(TAG, "episode Samehadaku 429 — jangan retry")
            diagLogger?.error(DiagLog.TAG_REPO, "Samehadaku 429 (episode)")
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // v2.0.10 item 2: lihat search di atas — tanpa simpleName.
            Log.w(TAG, "episode Samehadaku gagal (${DiagError.describe(e)})", e)
            return null
        }
        val url = try {
            parseEnvelope(epRaw, SamehadakuEpisodeData::class.java)
                .data?.defaultStreamingUrl
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "episode Samehadaku: respons tidak bisa dibaca", e)
            null
        }
        val result = url?.takeIf { it.isNotBlank() }
        if (result != null) {
            diagLogger?.info(
                DiagLog.TAG_REPO,
                "Samehadaku: '$seriesTitle' ep $episodeNumber ketemu " +
                    "(${System.currentTimeMillis() - t0}ms)"
            )
        }
        return result
    }

    companion object {
        /**
         * Normalisasi judul serial untuk pencocokan antar sumber — fungsi
         * MURNI (bisa diuji unit tanpa Android/jaringan).
         *
         * Urutan: huruf kecil → buang akhiran "subtitle indonesia" →
         * buang "Episode N" / "Eps N" di akhir (judul item search
         * Samehadaku membawa nomor episode) → buang penanda musim di
         * akhir ("Season 2", "2nd Season", "S2", "II") → buang tanda
         * baca → rapatkan spasi.
         *
         * Contoh: "Tensei shitara Ken deshita II" dan "Tensei shitara Ken
         * deshita S2 Episode 8" → keduanya "tensei shitara ken deshita".
         */
        fun normalizeSeriesTitle(title: String): String {
            var t = title.lowercase()
            t = t.replace("subtitle indonesia", " ")
            t = t.replace(
                Regex("""\s+(episode|eps?\.?)\s*\d+\s*$"""), " "
            )
            t = t.replace(Regex("""\s+season\s+\d+\s*$"""), " ")
            t = t.replace(Regex("""\s+\d+(st|nd|rd|th)\s+season\s*$"""), " ")
            t = t.replace(Regex("""\s+s\d+\s*$"""), " ")
            t = t.replace(Regex("""\s+(ii|iii|iv|v|vi|vii|viii|ix|x)\s*$"""), " ")
            t = t.replace(Regex("""[^a-z0-9 ]"""), " ")
            return t.replace(Regex("""\s+"""), " ").trim()
        }

        /**
         * Nomor musim dari judul — fungsi MURNI. Default 1 bila tidak ada
         * penanda (serial satu musim memang tidak membawa penanda).
         * "Season 2" / "2nd Season" / "S2" / "II" (romawi) → 2.
         * "I" saja TIDAK dihitung sebagai penanda (terlalu ambigu —
         * judul bisa berakhiran huruf "I" tanpa arti musim).
         */
        fun seasonNumberFromTitle(title: String): Int {
            // Buang "Episode N" dulu — penanda musim ada SEBELUM itu
            // ("… S2 Episode 8" → "… S2").
            val t = title.replace(
                Regex("""\s+(episode|eps?\.?)\s*\d+\s*$""", RegexOption.IGNORE_CASE),
                " "
            ).trim()
            Regex("""(?i)\bseason\s+(\d+)\s*$""").find(t)?.let {
                return it.groupValues[1].toIntOrNull() ?: 1
            }
            Regex("""(?i)\b(\d+)(st|nd|rd|th)\s+season\s*$""").find(t)?.let {
                return it.groupValues[1].toIntOrNull() ?: 1
            }
            Regex("""(?i)\bs(\d+)\s*$""").find(t)?.let {
                return it.groupValues[1].toIntOrNull() ?: 1
            }
            Regex("""(?i)\b(ii|iii|iv|v|vi|vii|viii|ix|x)\s*$""").find(t)?.let {
                return when (it.groupValues[1].lowercase()) {
                    "ii" -> 2
                    "iii" -> 3
                    "iv" -> 4
                    "v" -> 5
                    "vi" -> 6
                    "vii" -> 7
                    "viii" -> 8
                    "ix" -> 9
                    "x" -> 10
                    else -> 1
                }
            }
            return 1
        }

        /**
         * Nomor episode dari item search Samehadaku — fungsi MURNI.
         * type "Eps 8" → 8; bila type kosong, judul "… Episode 8" → 8.
         */
        fun episodeNumberFromSearchItem(item: SamehadakuSearchItem): Int? {
            Regex("""(?i)\beps?\.?\s*(\d+)""").find(item.type ?: "")?.let {
                return it.groupValues[1].toIntOrNull()
            }
            Regex("""(?i)\bepisode\s*(\d+)""").find(item.title ?: "")?.let {
                return it.groupValues[1].toIntOrNull()
            }
            return null
        }

        /**
         * Cocokkan episode yang sama antar sumber — fungsi MURNI.
         * Syarat KETAT (ketiganya harus sama):
         *  1. judul serial ternormalisasi SAMA;
         *  2. nomor musim SAMA (default 1 bila tanpa penanda);
         *  3. nomor episode SAMA.
         * Jangan cocokkan slug mentah (format beda antar sumber).
         * Tepat SATU kandidat → dipakai; nol / lebih dari satu → null
         * (JANGAN tebak — rantai lanjut ke dialog fallback seperti biasa).
         */
        fun matchEpisode(
            items: List<SamehadakuSearchItem>,
            seriesTitle: String,
            episodeNumber: Int
        ): SamehadakuSearchItem? {
            if (seriesTitle.isBlank() || episodeNumber <= 0) return null
            val wantTitle = normalizeSeriesTitle(seriesTitle)
            if (wantTitle.isEmpty()) return null
            val wantSeason = seasonNumberFromTitle(seriesTitle)
            val hits = items.filter { item ->
                val title = item.title
                title != null &&
                    normalizeSeriesTitle(title) == wantTitle &&
                    seasonNumberFromTitle(title) == wantSeason &&
                    episodeNumberFromSearchItem(item) == episodeNumber &&
                    !item.animeId.isNullOrBlank()
            }
            return if (hits.size == 1) hits[0] else null
        }
    }
}
