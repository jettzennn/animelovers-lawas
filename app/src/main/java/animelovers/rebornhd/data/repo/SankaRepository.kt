package animelovers.rebornhd.data.repo

import animelovers.rebornhd.data.api.RateLimitException
import animelovers.rebornhd.data.api.SankaHttpException
import animelovers.rebornhd.data.api.SankaTransport
import animelovers.rebornhd.data.local.FavoriteDao
import animelovers.rebornhd.data.local.VideoDao
import animelovers.rebornhd.data.local.entity.FavoriteEntity
import animelovers.rebornhd.data.local.entity.VideoEntity
import animelovers.rebornhd.data.model.SankaAnime
import animelovers.rebornhd.data.model.SankaDetail
import animelovers.rebornhd.data.model.SankaEnvelope
import animelovers.rebornhd.data.model.SankaEpisodeData
import animelovers.rebornhd.data.model.SankaEpisodeItem
import animelovers.rebornhd.data.model.SankaHome
import animelovers.rebornhd.util.Constants
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Encode satu segmen path / nilai query (persen-encode UTF-8).
 *
 * Meniru perilaku Retrofit lama yang digantikan transport WebView:
 * huruf/angka ASCII + `-._~` dibiarkan, sisanya jadi %XX (spasi → %20,
 * `%` → %25 — double-encoding slug percent-encoded DITOLERANSI Sanka,
 * dibuktikan Cari Bug v2.0.1: detail double-encoded → 200). Murni
 * (bisa diuji tanpa Android — android.net.Uri melempar Stub di JVM).
 */
internal fun encodeSegment(value: String): String {
    val hex = "0123456789ABCDEF"
    val sb = StringBuilder()
    for (octet in value.toByteArray(Charsets.UTF_8)) {
        val c = octet.toInt() and 0xFF
        if ((c in 0x30..0x39) || (c in 0x41..0x5A) || (c in 0x61..0x7A) ||
            c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
        ) {
            sb.append(c.toChar())
        } else {
            sb.append('%')
            sb.append(hex[c shr 4])
            sb.append(hex[c and 0x0F])
        }
    }
    return sb.toString()
}

/**
 * Repository Sanka — pengganti repository v2.0.0/2.0.1 (OkHttp).
 *
 * v2.0.2: transport diganti OkHttp → WebView tersembunyi (SankaWebTransport)
 * — sidik TLS OkHttp diblokir Cloudflare dari IP HP Faygo, WebView adalah
 * Chrome asli yang terbukti lolos. API PUBLIK repository TIDAK berubah
 * (home/terbaru/search/genre/detail/episode + riwayat/favorit + gerbang
 * Ecchi) — UI + PlayerRepository tidak disentuh.
 *
 * DISIPLIN RATE LIMIT (PRD v2 §4.3 — 3 pelanggaran = BAN PERMANEN):
 *  1. Throttle ≥1,4 detik antar request (di transport, bukan lagi
 *     interceptor OkHttp).
 *  2. Cache agresif di memori (home/detail 10 menit, search/genre 5 menit).
 *  3. Dedup: fetch dengan kunci yang SAMA lewat SATU Mutex per kunci —
 *     request kedua menunggu yang pertama lalu mendapat hasil cache.
 *     Kunci BERBEDA tidak saling menahan (dulu satu Mutex global —
 *     retry/backoff satu layar menahan semua layar lain).
 *  4. Satu layar = satu request berjalan — tanpa fan-out paralel,
 *     tanpa prefetch rakus. Pembatalan (layar ditinggalkan) membatalkan
 *     coroutine pemanggil; request yang sedang jalan di jaringan tidak
 *     diulang untuk layar yang sama.
 *  5. 429 → RateLimitException, TIDAK PERNAH di-retry. Pesan jujur ke user.
 *  6. 500 (scraper Animekompi timeout — teramati live 2026-11-17, detail
 *     sempat 500 dua kali lalu 200) + gagal jaringan → retry maks 3×
 *     dengan backoff 2 dtk / 4 dtk.
 *  7. Episode TIDAK PERNAH di-cache: URL sumber kedaluwarsa (expire + sig
 *     + terikat IP) — diambil segar tiap putar.
 *
 * Riwayat & favorit: kunci idtitle kini SLUG Sanka (D4 — riwayat/favorit
 * v1 dihapus bersih oleh migrasi destruktif; skema tabel tidak berubah).
 * Method DAO sudah suspend (Room menjalankannya di luar Main) — repository
 * memanggilnya langsung tanpa withContext tambahan.
 */
class SankaRepository(
    private val transport: SankaTransport,
    private val videoDao: VideoDao,
    private val favoriteDao: FavoriteDao
) {

    private val gson = Gson()

    // ---- Cache + dedup ----

    private data class CacheEntry(val atMs: Long, val value: Any)

    // ConcurrentHashMap: kunci berbeda boleh dibaca/ditulis bersamaan
    // (masing-masing dijaga Mutex per kuncinya sendiri).
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * Mutex PER KUNCI cache — fetch `search:naruto` tidak menahan
     * `detail:x` (satu Mutex global dulu menahan semua fetch selama I/O
     * + backoff retry). Throttle 1,4 detik TETAP berlaku — itu di
     * transport WebView, bukan di sini — jadi perbaikan ini tidak
     * membuka celah rate limit.
     */
    private val keyMutexes = mutableMapOf<String, Mutex>()

    private fun mutexFor(key: String): Mutex = synchronized(keyMutexes) {
        keyMutexes.getOrPut(key) { Mutex() }
    }

    companion object {
        const val TTL_HOME_MS = 10 * 60 * 1000L
        const val TTL_SEARCH_MS = 5 * 60 * 1000L
        const val TTL_DETAIL_MS = 10 * 60 * 1000L
    }

    private suspend fun <T : Any> cached(key: String, ttlMs: Long, block: suspend () -> T): T {
        val mutex = mutexFor(key)
        mutex.withLock {
            val entry = cache[key]
            if (entry != null && System.currentTimeMillis() - entry.atMs < ttlMs) {
                @Suppress("UNCHECKED_CAST")
                return entry.value as T
            }
            val result = block()
            cache[key] = CacheEntry(System.currentTimeMillis(), result)
            return result
        }
    }

    // Gson mengembalikan null (bukan throw) untuk body kosong / literal
    // `null` — samakan dengan converter Retrofit lama: itu IOException,
    // bukan NPE yang bocor ke UI.
    private inline fun <reified T> parseEnvelope(raw: String): SankaEnvelope<T> =
        gson.fromJson(raw, object : TypeToken<SankaEnvelope<T>>() {}.type)
            ?: throw IOException("Respons kosong dari Sanka")

    /**
     * Satu panggilan dengan aturan retry — SAMA seperti callWithRetry lama
     * (OkHttp Response), hanya sumbernya kini string JSON dari transport:
     * - 2xx → parse (gagal parse = JsonSyntaxException, langsung lolos —
     *   sama seperti converter Retrofit dulu).
     * - 429 → RateLimitException (TANPA retry, tanpa penundaan).
     * - 500–599 (scraper timeout) + IOException jaringan → retry maks 3×,
     *   backoff 2 dtk lalu 4 dtk.
     * - Selain itu → IOException langsung.
     * CancellationException TIDAK ditangkap — pembatalan harus lolos.
     */
    private suspend fun <T> callWithRetry(
        block: suspend () -> String,
        parse: (String) -> T
    ): T {
        var attempt = 0
        while (true) {
            attempt++
            val raw = try {
                block()
            } catch (e: SankaHttpException) {
                if (e.statusCode in 500..599 && attempt < 3) {
                    delay(2_000L * attempt)
                    continue
                }
                throw IOException("Sanka menjawab ${e.statusCode}")
            } catch (e: IOException) {
                if (attempt >= 3) throw e
                delay(2_000L * attempt)
                continue
            }
            return parse(raw)
        }
    }

    // ---- Katalog ----

    suspend fun home(): SankaHome = cached("home", TTL_HOME_MS) {
        val envelope = callWithRetry(
            { transport.getJson("anime/animekompi/home") },
            { raw -> parseEnvelope<SankaHome>(raw) }
        )
        envelope.data ?: throw IOException("Beranda Sanka kosong")
    }

    suspend fun terbaru(page: Int = 1): List<SankaEpisodeItem> =
        cached("terbaru:$page", TTL_SEARCH_MS) {
            val envelope = callWithRetry(
                { transport.getJson("anime/animekompi/terbaru?page=$page") },
                { raw -> parseEnvelope<List<SankaEpisodeItem>>(raw) }
            )
            envelope.data ?: emptyList()
        }

    suspend fun search(query: String, page: Int = 1): List<SankaAnime> =
        cached("search:$query:$page", TTL_SEARCH_MS) {
            val envelope = callWithRetry(
                { transport.getJson("anime/animekompi/search?q=${encodeSegment(query)}&page=$page") },
                { raw -> parseEnvelope<List<SankaAnime>>(raw) }
            )
            envelope.data ?: emptyList()
        }

    suspend fun genre(slug: String, page: Int = 1): List<SankaAnime> =
        cached("genre:$slug:$page", TTL_SEARCH_MS) {
            val envelope = callWithRetry(
                { transport.getJson("anime/animekompi/genre/${encodeSegment(slug)}?page=$page") },
                { raw -> parseEnvelope<List<SankaAnime>>(raw) }
            )
            envelope.data ?: emptyList()
        }

    suspend fun detail(slug: String): SankaDetail = cached("detail:$slug", TTL_DETAIL_MS) {
        val envelope = callWithRetry(
            { transport.getJson("anime/animekompi/detail/${encodeSegment(slug)}") },
            { raw -> parseEnvelope<SankaDetail>(raw) }
        )
        envelope.data ?: throw IOException("Detail Sanka kosong")
    }

    /** Episode: TANPA cache (URL sumber kedaluwarsa) — ambil segar tiap putar. */
    suspend fun episode(slug: String): SankaEpisodeData {
        val envelope = callWithRetry(
            { transport.getJson("anime/animekompi/episode/${encodeSegment(slug)}") },
            { raw -> parseEnvelope<SankaEpisodeData>(raw) }
        )
        return envelope.data ?: throw IOException("Episode Sanka kosong")
    }

    // ---- Gerbang Ecchi (kunci ketiganya: Ecchi + Erotica + Hentai) ----

    fun isEcchi(anime: SankaAnime): Boolean =
        Constants.LOCKED_GENRES.any { locked ->
            anime.genreSlugs.any { it.equals(locked, ignoreCase = true) }
        }

    fun isEcchi(detail: SankaDetail): Boolean =
        Constants.LOCKED_GENRES.any { locked ->
            detail.genreSlugs.any { it.equals(locked, ignoreCase = true) }
        }

    fun visibleAnimes(list: List<SankaAnime>, ecchiUnlocked: Boolean): List<SankaAnime> =
        if (ecchiUnlocked) list else list.filterNot { isEcchi(it) }

    /**
     * Saring item episode terbaru (SankaEpisodeItem tidak membawa genre):
     * cocokkan `animeSlug` ke daftar ber-genre di respons home yang sama
     * (featured/ongoing/completed/movies/popular/trending).
     *
     * SISA KEBOCORAN (terdokumentasi): anime yang TIDAK ada di daftar itu
     * tidak bisa dinilai genre-nya → tetap tampil. Playback-nya tetap
     * terkunci (overlay kunci di Detail + guard EpisodeActivity), jadi ini
     * soal visibilitas saja. Tanpa fetch /detail per item — itu melanggar
     * disiplin rate limit (satu item = satu request Sanka).
     */
    fun visibleEpisodes(
        items: List<SankaEpisodeItem>,
        home: SankaHome,
        ecchiUnlocked: Boolean
    ): List<SankaEpisodeItem> {
        if (ecchiUnlocked) return items
        val lockedSlugs = (home.featured.orEmpty() + home.ongoing.orEmpty() +
            home.completed.orEmpty() + home.movies.orEmpty() + home.popular +
            home.trendingToday.orEmpty())
            .filter { isEcchi(it) }
            .mapNotNull { it.slug }
            .toSet()
        return items.filterNot { it.animeSlug in lockedSlugs }
    }

    // Item episode tidak membawa genre — penyaringan Ecchi terjadi di
    // Detail (overlay kunci), bukan di daftar episode terbaru.

    // ---- Riwayat (tabel `video`, kunci idtitle = slug Sanka) ----

    suspend fun getHistory(): List<VideoEntity> = videoDao.getAll()

    suspend fun getHistoryForTitle(slug: String): VideoEntity? =
        videoDao.getByTitle(slug)

    suspend fun deleteHistory(idui: Int) = videoDao.deleteById(idui)

    suspend fun deleteHistoryForTitle(slug: String) =
        videoDao.deleteByTitle(slug)

    /**
     * Simpan / perbarui posisi tonton (aturan asli: posisi − 30 detik saat
     * keluar player, dihitung pemanggil). Entri riwayat dibuat bila belum
     * ada; judul/poster diperbarui tiap simpan.
     */
    suspend fun saveResume(
        slug: String,
        title: String,
        poster: String,
        episode: Int,
        positionMs: Long
    ) {
        val existing = videoDao.getByTitle(slug)
        if (existing == null) {
            videoDao.insert(
                VideoEntity(
                    idtitle = slug,
                    title = title,
                    idposter = poster,
                    idepisode = episode.toString(),
                    idsecond = positionMs,
                    waktu = System.currentTimeMillis()
                )
            )
        } else {
            videoDao.update(
                existing.copy(
                    title = title,
                    idposter = poster,
                    idepisode = episode.toString(),
                    idsecond = positionMs,
                    waktu = System.currentTimeMillis()
                )
            )
        }
    }

    // ---- Favorit (tabel `favorite`, kunci idtitle = slug Sanka) ----

    suspend fun getFavorites(): List<FavoriteEntity> = favoriteDao.getAll()

    suspend fun isFavorite(slug: String): Boolean = favoriteDao.exists(slug)

    suspend fun toggleFavorite(anime: SankaAnime): Boolean {
        val slug = anime.slug ?: return false
        val isFavorite = favoriteDao.exists(slug)
        if (isFavorite) {
            favoriteDao.deleteByTitle(slug)
        } else {
            favoriteDao.insert(
                FavoriteEntity(
                    idtitle = slug,
                    title = anime.displayTitle,
                    idposter = anime.posterUrl,
                    waktu = System.currentTimeMillis()
                )
            )
        }
        return !isFavorite
    }
}
