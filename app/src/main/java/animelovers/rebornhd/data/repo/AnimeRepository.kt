package animelovers.rebornhd.data.repo

import animelovers.rebornhd.data.api.AnikotoApi
import animelovers.rebornhd.data.local.AppDatabase
import animelovers.rebornhd.data.local.entity.FavoriteEntity
import animelovers.rebornhd.data.local.entity.VideoEntity
import animelovers.rebornhd.data.model.Anime
import animelovers.rebornhd.data.model.SeriesData
import animelovers.rebornhd.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * Katalog + detail + riwayat + favorit.
 *
 * - Katalog dari Anikoto langsung (publik, tanpa key).
 * - Riwayat + favorit dari Room lokal (PRD §5).
 * - Gerbang Ecchi: [isEcchi] + [isEcchiUnlocked] + [visibleAnimes] — satu tempat
 *   untuk aturan "Ecchi disembunyikan bila gerbang belum terbuka", dipakai
 *   Beranda + Jelajahi + Lainnya.
 */
class AnimeRepository(
    private val anikoto: AnikotoApi = AnikotoApi.create(),
    private val db: AppDatabase
) {

    /**
     * Ulangi panggilan Anikoto yang gagal karena jaringan (timeout, koneksi
     * putus, DNS) — hingga [Constants.ANIKOTO_MAX_ATTEMPTS] percobaan dengan
     * jeda [Constants.ANIKOTO_RETRY_DELAY_MS]. Hanya IOException yang diulang
     * (SocketTimeoutException, ConnectException, UnknownHostException);
     * HttpException (server menjawab dengan status error) dan
     * CancellationException (coroutine dibatalkan) langsung diteruskan.
     */
    private suspend fun <T> withAnikotoRetry(block: suspend () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (attempt >= Constants.ANIKOTO_MAX_ATTEMPTS) throw e
                attempt++
                delay(Constants.ANIKOTO_RETRY_DELAY_MS)
            }
        }
    }

    /** Episode terbaru, satu halaman (20 item). */
    suspend fun recentAnime(page: Int = 1): List<Anime> =
        withAnikotoRetry { anikoto.recentAnime(page).data }

    /** Total halaman katalog (471) — dibaca dari paginasi /recent-anime. */
    suspend fun totalPages(): Int =
        withAnikotoRetry { anikoto.recentAnime(1).pagination?.totalPages } ?: 1

    /** Detail anime + daftar episode. */
    suspend fun series(id: String): SeriesData? =
        withAnikotoRetry { anikoto.series(id).data }

    /**
     * Muat katalog halaman demi halaman (1..totalPages) untuk Jelajahi.
     * Tiap halaman yang selesai dilaporkan lewat [onPage] supaya UI bisa
     * menampilkan hasil sambil jalan (pola asli: katalog dimuat sekaligus,
     * lalu filter lokal). Batalkan lewat [isActive] (mis. fragment dihancurkan).
     * Jeda kecil antar-halaman supaya tidak membanjiri API.
     */
    suspend fun loadCatalogPages(
        onPage: (List<Anime>) -> Unit,
        isActive: () -> Boolean
    ) {
        val pages = try {
            totalPages()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            1
        }
        var page = 1
        while (page <= pages && isActive()) {
            try {
                val items = recentAnime(page)
                if (items.isEmpty()) break
                onPage(items)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Satu halaman gagal = lewati, lanjut ke halaman berikut.
            }
            page++
            delay(150)
        }
    }

    // ---- Gerbang Ecchi (keputusan Faygo 2026-11-17: kunci Ecchi + Erotica + Hentai) ----

    fun isEcchi(anime: Anime): Boolean =
        anime.genres.any { genre ->
            Constants.LOCKED_GENRES.any { it.equals(genre, ignoreCase = true) }
        }

    /** Gerbang terbuka bila sudah login DAN toggle Ecchi aktif. */
    fun isEcchiUnlocked(isSignedIn: Boolean, ecchiEnabled: Boolean): Boolean =
        isSignedIn && ecchiEnabled

    /** Saring judul Ecchi bila gerbang belum terbuka (dipakai semua daftar). */
    fun visibleAnimes(list: List<Anime>, ecchiUnlocked: Boolean): List<Anime> =
        if (ecchiUnlocked) list else list.filter { !isEcchi(it) }

    // ---- Riwayat (tabel video) ----

    suspend fun getHistory(): List<VideoEntity> = db.videoDao().getAll()

    suspend fun getHistoryForTitle(idTitle: String): VideoEntity? =
        db.videoDao().getByTitle(idTitle)

    suspend fun deleteHistory(idui: Int) = db.videoDao().deleteById(idui)

    /**
     * Aturan tulis resume — sama seperti aslinya (PRD §5.1):
     * - Tidak ada baris untuk idtitle ini → INSERT baru (idsecond = 0).
     * - Ada baris dan episode sama → UPDATE posisi + waktu.
     * - Ada baris tapi episode berbeda → DELETE baris lama + INSERT baru (mulai 0).
     */
    suspend fun saveResume(
        idTitle: String,
        title: String,
        poster: String,
        episode: String,
        positionMs: Long
    ) {
        val dao = db.videoDao()
        val existing = dao.getByTitle(idTitle)
        val now = System.currentTimeMillis()
        when {
            existing == null -> dao.insert(
                VideoEntity(idTitle, title, poster, episode, positionMs, 0, now)
            )
            existing.idepisode == episode -> dao.update(
                existing.copy(idsecond = positionMs, waktu = now)
            )
            else -> {
                dao.deleteByTitle(idTitle)
                dao.insert(
                    VideoEntity(idTitle, title, poster, episode, 0L, 0, now)
                )
            }
        }
    }

    // ---- Favorit (tabel favorite) ----

    suspend fun getFavorites(): List<FavoriteEntity> = db.favoriteDao().getAll()

    suspend fun isFavorite(idTitle: String): Boolean = db.favoriteDao().exists(idTitle)

    /** Toggle favorit; kembalikan true bila sekarang tersimpan. */
    suspend fun toggleFavorite(anime: Anime): Boolean {
        val dao = db.favoriteDao()
        return if (dao.exists(anime.id.toString())) {
            dao.deleteByTitle(anime.id.toString())
            false
        } else {
            dao.insert(
                FavoriteEntity(
                    anime.id.toString(),
                    anime.displayTitle,
                    anime.poster,
                    System.currentTimeMillis()
                )
            )
            true
        }
    }
}
