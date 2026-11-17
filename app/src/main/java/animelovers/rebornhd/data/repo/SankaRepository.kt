package animelovers.rebornhd.data.repo

import animelovers.rebornhd.data.api.RateLimitException
import animelovers.rebornhd.data.api.SankaApi
import animelovers.rebornhd.data.local.FavoriteDao
import animelovers.rebornhd.data.local.VideoDao
import animelovers.rebornhd.data.local.entity.FavoriteEntity
import animelovers.rebornhd.data.local.entity.VideoEntity
import animelovers.rebornhd.data.model.SankaAnime
import animelovers.rebornhd.data.model.SankaDetail
import animelovers.rebornhd.data.model.SankaEpisodeData
import animelovers.rebornhd.data.model.SankaEpisodeItem
import animelovers.rebornhd.data.model.SankaHome
import animelovers.rebornhd.util.Constants
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository Sanka — pengganti repository v1 (Anikoto).
 *
 * DISIPLIN RATE LIMIT (PRD v2 §4.3 — 3 pelanggaran = BAN PERMANEN):
 *  1. Throttle ≥1,4 detik antar request (SankaThrottleInterceptor, di client).
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
    private val api: SankaApi,
    private val videoDao: VideoDao,
    private val favoriteDao: FavoriteDao
) {

    // ---- Cache + dedup ----

    private data class CacheEntry(val atMs: Long, val value: Any)

    // ConcurrentHashMap: kunci berbeda boleh dibaca/ditulis bersamaan
    // (masing-masing dijaga Mutex per kuncinya sendiri).
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * Mutex PER KUNCI cache — fetch `search:naruto` tidak menahan
     * `detail:x` (satu Mutex global dulu menahan semua fetch selama I/O
     * + backoff retry). Throttle 1,4 detik TETAP berlaku — itu di
     * interceptor OkHttp, bukan di sini — jadi perbaikan ini tidak
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

    /**
     * Satu panggilan HTTP dengan aturan retry:
     * - 2xx → isi (body null = error).
     * - 429 → RateLimitException (TANPA retry, tanpa penundaan).
     * - 500–599 (scraper timeout) + IOException jaringan → retry maks 3×,
     *   backoff 2 dtk lalu 4 dtk.
     * - Selain itu → IOException langsung.
     * CancellationException TIDAK ditangkap — pembatalan harus lolos.
     */
    private suspend fun <T> callWithRetry(block: suspend () -> Response<T>): T {
        var attempt = 0
        while (true) {
            attempt++
            val response = try {
                block()
            } catch (e: IOException) {
                if (attempt >= 3) throw e
                delay(2_000L * attempt)
                continue
            }
            when {
                response.isSuccessful -> {
                    return response.body()
                        ?: throw IOException("Respons kosong dari Sanka")
                }
                response.code() == 429 -> throw RateLimitException()
                response.code() in 500..599 && attempt < 3 -> {
                    delay(2_000L * attempt)
                }
                else -> throw IOException("Sanka menjawab ${response.code()}")
            }
        }
    }

    // ---- Katalog ----

    suspend fun home(): SankaHome = cached("home", TTL_HOME_MS) {
        val envelope = callWithRetry { api.home() }
        envelope.data ?: throw IOException("Beranda Sanka kosong")
    }

    suspend fun terbaru(page: Int = 1): List<SankaEpisodeItem> =
        cached("terbaru:$page", TTL_SEARCH_MS) {
            val envelope = callWithRetry { api.terbaru(page) }
            envelope.data ?: emptyList()
        }

    suspend fun search(query: String, page: Int = 1): List<SankaAnime> =
        cached("search:$query:$page", TTL_SEARCH_MS) {
            val envelope = callWithRetry { api.search(query, page) }
            envelope.data ?: emptyList()
        }

    suspend fun genre(slug: String, page: Int = 1): List<SankaAnime> =
        cached("genre:$slug:$page", TTL_SEARCH_MS) {
            val envelope = callWithRetry { api.genre(slug, page) }
            envelope.data ?: emptyList()
        }

    suspend fun detail(slug: String): SankaDetail = cached("detail:$slug", TTL_DETAIL_MS) {
        val envelope = callWithRetry { api.detail(slug) }
        envelope.data ?: throw IOException("Detail Sanka kosong")
    }

    /** Episode: TANPA cache (URL sumber kedaluwarsa) — ambil segar tiap putar. */
    suspend fun episode(slug: String): SankaEpisodeData {
        val envelope = callWithRetry { api.episode(slug) }
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
