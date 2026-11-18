package animelovers.rebornhd.data.repo

import android.util.Log
import animelovers.rebornhd.data.api.RateLimitException
import animelovers.rebornhd.data.api.SankaApiErrorException
import animelovers.rebornhd.data.api.SankaEmptyResponseException
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
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "SankaRepository"

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
 * v2.0.3: WebView DITEMPEL ke decor view MainActivity (1×1 px, tak
 * terlihat) + UA tanpa "Version/4.0" — v2.0.2 yang tak tertempel tidak
 * memicu onPageFinished di Vivo Faygo. Alasan gagal kini terlihat
 * (exception transport berjenis + Log.e + toast beralasan).
 *
 * v2.0.4 — DIAGNOSTIK: data-null amplop (home/detail/episode) kini
 * SankaApiErrorException — status + message Sanka ikut dibawa ke toast,
 * dan 300 karakter pertama raw response di-Log.e. Dulu IOException
 * polos ("...kosong") yang tak terpetakan → toast generik buta di HP
 * (v2.0.3). Terbaru/search/genre TETAP `data ?: emptyList()` (daftar
 * kosong = layar kosong tanpa toast — perilaku lama, sengaja).
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
    private val favoriteDao: FavoriteDao,
    /**
     * File cache disk Beranda (v2.0.7 #3) — null = tanpa disk (dipakai
     * suite uji). Di aplikasi diisi AnimeloversApp dengan
     * File(filesDir, "home_cache.json"). Isi: JSON mentah /home.
     * TTL = TTL_HOME_MS (10 menit), diukur dari lastModified file.
     */
    private val homeDiskFile: File? = null
) {

    private val gson = Gson()

    /**
     * Transport yang sama untuk adapter Samehadaku (v2.0.7 item 16) —
     * throttle 1.400 ms + Mutex + timeout 30 dtk berlaku untuk semua
     * sumber; Samehadaku TIDAK membawa client sendiri.
     */
    fun samehadakuTransport(): SankaTransport = transport

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

        /**
         * Sortir hasil pencarian client-side (v2.0.6 #3, diperkuat v2.0.7
         * #13) — urutan server BUKAN relevansi (live "one piece": judul
         * utama "One Piece" (TV) ada di posisi 19 dari 24, di atasnya 16
         * Movie + 5 Special; live "nanatsu no taizai": Season 2 SEBELUM
         * Season 1, Movie di atas — urutan server acak).
         * Aturan STABIL (urutan relatif di dalam tiap kelompok
         * dipertahankan — sortedWith Kotlin stabil):
         *  1. judul persis sama dengan query (case-insensitive, trim);
         *  2. entri awal seri: "Season 1" / judul dasar — lewat nomor
         *     musim (seasonNumberFor): Season 1, lalu 2, 3, 4…;
         *  3. type: TV dulu, lalu Special, ONA/OVA, Movie terakhir;
         *  4. seri TV lanjutan/spinoff lain (tanpa nomor musim yang
         *     dikenali) setelahnya, urutan server dipertahankan.
         * Fungsi MURNI (bisa diuji unit tanpa Android/jaringan).
         */
        fun sortSearchResults(query: String, list: List<SankaAnime>): List<SankaAnime> {
            val q = query.trim()
            return list.sortedWith(
                compareBy<SankaAnime>(
                    { if (it.displayTitle.trim().equals(q, ignoreCase = true)) 0 else 1 },
                    {
                        when (it.type?.trim()?.lowercase()) {
                            "movie" -> 4
                            "special" -> 2
                            "ona", "ova" -> 3
                            else -> 1 // "TV" + type kosong/tak dikenal
                        }
                    },
                    { seasonNumberFor(q, it.displayTitle) ?: Int.MAX_VALUE }
                )
            )
        }

        /**
         * Nomor musim dari judul — HANYA bila sisa judul setelah query
         * persis penanda musim ("Season 2", "2nd Season"). Contoh:
         * query "nanatsu no taizai" + "Nanatsu no Taizai Season 2" → 2;
         * "Nanatsu no Taizai: Mokushiroku no Yonkishi Season 2" → null
         * (spinoff — ada subjudul sebelum "Season", jadi bukan Season 2
         * seri utama); "Tokyo Ghoul √A Season 2" → null (ada "√A" di
         * tengah). null = bukan seri bernomor → diurutkan setelah seri
         * bernomor, urutan server dipertahankan.
         */
        private fun seasonNumberFor(query: String, title: String): Int? {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return null
            val t = title.trim()
            if (!t.lowercase().startsWith(q)) return null
            val rest = t.substring(q.length).trim()
            Regex("""^Season\s+(\d+)$""", RegexOption.IGNORE_CASE).find(rest)?.let {
                return it.groupValues[1].toIntOrNull()
            }
            Regex("""^(\d+)(st|nd|rd|th)\s+Season$""", RegexOption.IGNORE_CASE)
                .find(rest)?.let {
                    return it.groupValues[1].toIntOrNull()
                }
            return null
        }
    }

    /**
     * Data null di amplop = Sanka menjawab TAPI tanpa data — mis. envelope
     * {"status":"error","message":"..."} dengan HTTP 200 (v2.0.4).
     * Bukan IOException generik: status + message Sanka ikut dibawa
     * (SankaApiErrorException) supaya toast bisa menulis penyebabnya,
     * dan 300 karakter pertama raw response di-Log.e supaya penyebabnya
     * bisa dibaca di logcat tanpa menebak.
     *
     * Dilempar DI LUAR callWithRetry (seperti dulu) — error API yang
     * deterministik TIDAK di-retry 3×; satu request, langsung toast.
     */
    private fun <T> requireData(envelope: SankaEnvelope<T>, raw: String, label: String): T {
        val data = envelope.data
        if (data != null) return data
        Log.e(
            TAG,
            "$label: data null (status=${envelope.status}, message=${envelope.message}); " +
                "raw=${raw.take(300)}"
        )
        throw SankaApiErrorException(envelope.status, envelope.message)
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

    // Tipe Gson untuk amplop Sanka — DIBANGUN DENGAN TypeToken.getParameterized,
    // BUKAN subclass TypeToken anonim (v2.0.4, perbaikan R8): R8 Full Mode di
    // build Release menggabungkan subclass TypeToken anonim + menghapus
    // generic Signature, sehingga constructor TypeToken melempar
    // IllegalStateException di APK Release (bug v2.0.0–v2.0.3 —
    // Beranda + Jelajahi "Gagal memuat" padahal server sehat). getParameterized
    // membangun tipe dari Class biasa — tidak ada subclass anonim yang bisa
    // digabung/dihapus R8. Aturan keep Gson di proguard-rules.pro adalah
    // lapis kedua untuk model + anotasi SerializedName.
    private fun <T> envelopeType(dataClass: Class<T>): Type =
        TypeToken.getParameterized(SankaEnvelope::class.java, dataClass).type

    private fun <T> envelopeListType(itemClass: Class<T>): Type {
        val listType = TypeToken.getParameterized(List::class.java, itemClass).type
        return TypeToken.getParameterized(SankaEnvelope::class.java, listType).type
    }

    // Gson mengembalikan null (bukan throw) untuk body kosong / literal
    // `null` — samakan dengan converter Retrofit lama: itu IOException,
    // bukan NPE yang bocor ke UI. Jenisnya SankaEmptyResponseException
    // (v2.0.3) supaya toast bisa menulis alasannya ("respons kosong").
    private fun <T> parseEnvelope(raw: String, dataClass: Class<T>): SankaEnvelope<T> =
        gson.fromJson(raw, envelopeType(dataClass))
            ?: throw SankaEmptyResponseException()

    private fun <T> parseEnvelopeList(raw: String, itemClass: Class<T>): SankaEnvelope<List<T>> =
        gson.fromJson(raw, envelopeListType(itemClass))
            ?: throw SankaEmptyResponseException()

    /**
     * Satu panggilan dengan aturan retry — SAMA seperti callWithRetry lama
     * (OkHttp Response), hanya sumbernya kini string JSON dari transport:
     * - 2xx → parse DI DALAM try: SankaEmptyResponseException (body
     *   kosong) ikut di-retry 3× seperti IOException transport;
     *   JsonSyntaxException (data rusak) langsung lolos tanpa retry —
     *   sama seperti converter Retrofit dulu.
     * - 429 → RateLimitException (TANPA retry, tanpa penundaan).
     * - 500–599 (scraper timeout) + IOException jaringan → retry maks 3×,
     *   backoff 2 dtk lalu 4 dtk.
     * - Selain itu → SankaHttpException ASLI dilempar ulang (bukan
     *   IOException) supaya toast bisa menulis alasannya
     *   (sankaErrorReason: "server menjawab 403"); 403/404 gagal cepat,
     *   tanpa retry.
     * CancellationException TIDAK ditangkap — pembatalan harus lolos.
     */
    private suspend fun <T> callWithRetry(
        block: suspend () -> String,
        parse: (String) -> T
    ): T {
        var attempt = 0
        while (true) {
            attempt++
            try {
                val raw = block()
                return parse(raw)
            } catch (e: SankaHttpException) {
                if (e.statusCode in 500..599 && attempt < 3) {
                    delay(2_000L * attempt)
                    continue
                }
                throw e
            } catch (e: JsonSyntaxException) {
                // Gagal parse = data rusak, bukan gangguan jaringan —
                // jangan retry (sama seperti converter Retrofit dulu).
                throw e
            } catch (e: IOException) {
                if (attempt >= 3) throw e
                delay(2_000L * attempt)
                continue
            }
        }
    }

    // ---- Katalog ----

    suspend fun home(): SankaHome = cached("home", TTL_HOME_MS) {
        // v2.0.7 #3: bukaan dingin — memori kosong, tapi disk mungkin
        // masih segar (TTL 10 menit). Pakai disk dulu (tanpa jaringan);
        // fetch jaringan HANYA bila disk juga basi/tidak ada. Fetch segar
        // yang dipaksa (setelah render dari disk) lewat homeFresh().
        homeDiskCached() ?: fetchHomeFromNetwork()
    }

    /**
     * Fetch /home dari jaringan + tulis ke disk (v2.0.7 #3). Dipanggil
     * home() bila disk basi, dan homeFresh() bila UI meminta data segar.
     */
    private suspend fun fetchHomeFromNetwork(): SankaHome {
        // Raw disimpan di parse lambda (v2.0.4) supaya requireData bisa
        // me-Log.e 300 karakter pertamanya saat data null.
        var lastRaw = ""
        val envelope = callWithRetry(
            { transport.getJson("anime/animekompi/home") },
            { raw -> lastRaw = raw; parseEnvelope(raw, SankaHome::class.java) }
        )
        val data = requireData(envelope, lastRaw, "home")
        writeHomeToDisk(lastRaw)
        return data
    }

    /**
     * Fetch /home SEGAR dari jaringan, mengabaikan cache memori + disk
     * (v2.0.7 #3). Dipakai Beranda: render dulu dari disk, lalu fetch
     * segar di belakang dan perbarui tampilan bila selesai. Hasilnya
     * ditulis ke memori + disk seperti fetch biasa.
     */
    suspend fun homeFresh(): SankaHome {
        val mutex = mutexFor("home")
        return mutex.withLock {
            val data = fetchHomeFromNetwork()
            cache["home"] = CacheEntry(System.currentTimeMillis(), data)
            data
        }
    }

    /**
     * Isi cache `home` MEMORI bila masih segar (TTL_HOME_MS) — TANPA
     * jaringan, tanpa menyentuh throttle, tanpa baca disk.
     */
    fun homeMemoryCached(): SankaHome? {
        val entry = cache["home"] ?: return null
        if (System.currentTimeMillis() - entry.atMs >= TTL_HOME_MS) return null
        return entry.value as? SankaHome
    }

    /**
     * Isi cache `home` DISK bila masih segar (TTL_HOME_MS dari
     * lastModified file) — baca + parse JSON di Dispatchers.IO (v2.0.7
     * #3). null = file tidak ada / basi / rusak (file rusak dihapus
     * supaya tidak dibaca ulang tiap bukaan). TIDAK PERNAH melempar —
     * kegagalan disk tidak boleh menggagalkan render Beranda.
     */
    suspend fun homeDiskCached(): SankaHome? {
        val file = homeDiskFile ?: return null
        return try {
            withContext(Dispatchers.IO) {
                if (!file.exists()) return@withContext null
                if (System.currentTimeMillis() - file.lastModified() >= TTL_HOME_MS) {
                    return@withContext null
                }
                val raw = file.readText()
                parseEnvelope(raw, SankaHome::class.java).data
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cache disk home rusak / tidak bisa dibaca — diabaikan", e)
            try {
                withContext(Dispatchers.IO) { file.delete() }
            } catch (ignored: Exception) {
            }
            null
        }
    }

    /**
     * Tulis JSON mentah /home ke disk (v2.0.7 #3) — best-effort: gagal
     * tulis (penyimpanan penuh, dsb.) hanya di-Log.w, TIDAK menggagalkan
     * fetch yang sudah berhasil.
     */
    private suspend fun writeHomeToDisk(raw: String) {
        val file = homeDiskFile ?: return
        try {
            withContext(Dispatchers.IO) { file.writeText(raw) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Gagal menulis cache disk home — diabaikan", e)
        }
    }

    suspend fun terbaru(page: Int = 1): List<SankaEpisodeItem> =
        cached("terbaru:$page", TTL_SEARCH_MS) {
            val envelope = callWithRetry(
                { transport.getJson("anime/animekompi/terbaru?page=$page") },
                { raw -> parseEnvelopeList(raw, SankaEpisodeItem::class.java) }
            )
            envelope.data ?: emptyList()
        }

    suspend fun search(query: String, page: Int = 1): List<SankaAnime> =
        cached("search:$query:$page", TTL_SEARCH_MS) {
            val envelope = callWithRetry(
                { transport.getJson("anime/animekompi/search?q=${encodeSegment(query)}&page=$page") },
                { raw -> parseEnvelopeList(raw, SankaAnime::class.java) }
            )
            // v2.0.6 #3: urutan server bukan relevansi — sortir di sini
            // (sebelum diserahkan ke UI + sebelum di-cache) supaya judul
            // utama selalu di atas, movie/special di bawah.
            sortSearchResults(query, envelope.data ?: emptyList())
        }

    /**
     * Isi cache `home` bila masih segar (TTL_HOME_MS) — TANPA jaringan,
     * tanpa menyentuh throttle. Dipakai Beranda v2.0.6 #7 (render dari
     * cache dulu sambil fetch segar di belakang). null = belum ada /
     * sudah basi → jalur fetch biasa.
     */
    fun homeCached(): SankaHome? {
        val entry = cache["home"] ?: return null
        if (System.currentTimeMillis() - entry.atMs >= TTL_HOME_MS) return null
        return entry.value as? SankaHome
    }

    suspend fun genre(slug: String, page: Int = 1): List<SankaAnime> =
        cached("genre:$slug:$page", TTL_SEARCH_MS) {
            val envelope = callWithRetry(
                { transport.getJson("anime/animekompi/genre/${encodeSegment(slug)}?page=$page") },
                { raw -> parseEnvelopeList(raw, SankaAnime::class.java) }
            )
            envelope.data ?: emptyList()
        }

    suspend fun detail(slug: String): SankaDetail = cached("detail:$slug", TTL_DETAIL_MS) {
        var lastRaw = ""
        val envelope = callWithRetry(
            { transport.getJson("anime/animekompi/detail/${encodeSegment(slug)}") },
            { raw -> lastRaw = raw; parseEnvelope(raw, SankaDetail::class.java) }
        )
        requireData(envelope, lastRaw, "detail")
    }

    /** Episode: TANPA cache (URL sumber kedaluwarsa) — ambil segar tiap putar. */
    suspend fun episode(slug: String): SankaEpisodeData {
        var lastRaw = ""
        val envelope = callWithRetry(
            { transport.getJson("anime/animekompi/episode/${encodeSegment(slug)}") },
            { raw -> lastRaw = raw; parseEnvelope(raw, SankaEpisodeData::class.java) }
        )
        return requireData(envelope, lastRaw, "episode")
    }

    // ---- Gerbang Ecchi (kunci ketiganya: Ecchi + Erotica + Hentai) ----
    // ---- + Sembunyikan donghua total (v2.0.7 #10) ----

    fun isEcchi(anime: SankaAnime): Boolean =
        Constants.LOCKED_GENRES.any { locked ->
            anime.genreSlugs.any { it.equals(locked, ignoreCase = true) }
        }

    fun isEcchi(detail: SankaDetail): Boolean =
        Constants.LOCKED_GENRES.any { locked ->
            detail.genreSlugs.any { it.equals(locked, ignoreCase = true) }
        }

    /**
     * Donghua (v2.0.7 #10 — keputusan Faygo: "Semua donghua kamu
     * sembunyikan total"): genre slug "donghua" (atau nama genre
     * "Donghua"). Dicocokkan seperti isEcchi — slug dulu, nama sebagai
     * lapis kedua (Sanka kadang hanya mengisi salah satunya).
     * JUJUR: ini hanya menyembunyikan tampilan di aplikasi kita — tidak
     * menghapus dari katalog Sanka (server mereka, bukan milik kita).
     * Tanpa tombol pengalih: donghua tidak pernah tampil di Beranda,
     * Jelajahi, maupun pencarian. Riwayat/Favorit milik pengguna sendiri
     * TIDAK disaring (bukan katalog).
     */
    fun isDonghua(anime: SankaAnime): Boolean =
        anime.genreSlugs.any { it.equals("donghua", ignoreCase = true) } ||
            anime.genres.orEmpty().any { it.name?.equals("Donghua", ignoreCase = true) == true }

    fun isDonghua(detail: SankaDetail): Boolean =
        detail.genreSlugs.any { it.equals("donghua", ignoreCase = true) } ||
            detail.genres.orEmpty().any { it.name?.equals("Donghua", ignoreCase = true) == true }

    /**
     * Daftar anime yang boleh tampil — donghua SELALU disaring (v2.0.7
     * #10, tanpa pengecualian), Ecchi disaring bila gerbang terkunci.
     */
    fun visibleAnimes(list: List<SankaAnime>, ecchiUnlocked: Boolean): List<SankaAnime> =
        list.filterNot { isDonghua(it) || (!ecchiUnlocked && isEcchi(it)) }

    /**
     * Saring item episode terbaru (SankaEpisodeItem tidak membawa genre):
     * cocokkan `animeSlug` ke daftar ber-genre di respons home yang sama
     * (featured/ongoing/completed/movies/popular/trending).
     * Donghua SELALU disaring (v2.0.7 #10); Ecchi disaring bila gerbang
     * terkunci.
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
        val hiddenSlugs = (home.featured.orEmpty() + home.ongoing.orEmpty() +
            home.completed.orEmpty() + home.movies.orEmpty() + home.popular +
            home.trendingToday.orEmpty())
            .filter { isDonghua(it) || (!ecchiUnlocked && isEcchi(it)) }
            .mapNotNull { it.slug }
            .toSet()
        return items.filterNot { it.animeSlug in hiddenSlugs }
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
