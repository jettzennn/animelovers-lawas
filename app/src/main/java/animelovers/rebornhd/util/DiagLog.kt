package animelovers.rebornhd.util

import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Konstanta + utilitas log diagnostik — MURNI, tanpa satu pun import
 * dari paket Android.
 *
 * File ini sengaja tidak menyentuh Android supaya bisa diuji langsung di JVM
 * (harness `TestV209`) tanpa emulator. Semua yang butuh Android (Context,
 * file, StateFlow, logcat) tinggal di [DiagLogger].
 *
 * Aturan privasi (D-privasi-log, mengikat SEMUA baris log, tanpa kecuali):
 * URL lengkap TIDAK PERNAH ditulis — hanya host-nya (`host=<host>`) lewat
 * [DiagSanitize.sanitize]. Token, signature, `raw`, dan email tidak pernah
 * masuk ke pesan log.
 */
object DiagLog {
    /** Maksimum baris yang disimpan di ring buffer + file (cap). */
    const val MAX_DIAG_LINES = 500

    /** Ambang mulai-trim (hysteresis): saat baris mencapai 600, baris tertua
     *  dibuang sampai kembali ke 500 — jadi trim tidak jalan di setiap baris. */
    const val TRIM_AT_LINES = 600

    /** Maksimum ukuran file log (128 KB). Bila terlampaui, file ditulis ulang
     *  dari ring buffer (yang isinya sudah ≤ 500 baris). */
    const val MAX_DIAG_FILE_BYTES = 131_072L

    /** Tag logcat cermin. */
    const val LOG_TAG = "AnimLoversDiag"

    /** Tag sumber peristiwa — kumpulan tetap, JANGAN tambah tanpa kebutuhan PRD. */
    const val TAG_APP = "App"
    const val TAG_PLAYER = "Player"
    const val TAG_REPO = "Repo"
    const val TAG_HOME = "Home"
    const val TAG_JELAJAHI = "Jelajahi"
    const val TAG_POSTER = "Poster"
    const val TAG_KONSOL = "Konsol"
    const val TAG_CRASH = "Crash"

    /** Ambang lambat (ms) — di atas ini, peristiwa dicatat sebagai WARN, bukan INFO. */
    const val SLOW_FIRST_FRAME_MS = 10_000L
    const val SLOW_RESOLVE_MS = 10_000L
    const val SLOW_HOME_MS = 10_000L
    const val SLOW_SEARCH_MS = 8_000L
    const val SLOW_POSTER_MS = 5_000L
}

/**
 * Format satu baris log: `yyyy-MM-dd HH:mm:ss.SSS | <LEVEL> | <TAG> | <pesan>`.
 *
 * Level yang dikenal: `E` (error), `W` (peringatan), `I` (info). Level lain
 * dinormalisasi ke `I` — format baris harus selalu bisa di-parse.
 */
object DiagFormat {
    fun formatLine(timestampMs: Long, level: Char, tag: String, message: String): String {
        val lvl = if (level == 'E' || level == 'W' || level == 'I') level else 'I'
        // SimpleDateFormat dibuat per panggilan — BUKAN instance bersama:
        // formatter itu tidak thread-safe, dan log bisa datang dari
        // thread mana pun (player, UI, repository).
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            .format(Date(timestampMs))
        return "$ts | $lvl | $tag | $message"
    }
}

/**
 * Sanitasi pesan log: setiap URL (`http://` / `https://`) diganti dengan
 * `host=<host>` — path, query, token, dan signature TIDAK PERNAH tertulis.
 *
 * Tanda baca penutup yang menempel di akhir URL (`.`, `,`, `)`, `]` …)
 * dikembalikan utuh setelah penggantian, supaya kalimat tetap terbaca.
 * Pesan tanpa `://` dikembalikan apa adanya (jalur cepat, tanpa regex).
 */
object DiagSanitize {
    // Skema TIDAK peka huruf (L-3 Pentest v2.0.9: "HTTPS://…" lolos dulu).
    private val urlPattern = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)

    // Host telanjang + path (L-3: tanpa skema, mis. "host/path?sig=…").
    // Dibatasi TLD yang dikenal supaya teks biasa ("v2.0.10", tanggal,
    // "Ep 1180: gagal") tidak ikut tersapu.
    private val bareHostPattern = Regex(
        """\b(?:[a-z0-9\-]+\.)+(?:com|net|org|id|sbs|to|xyz|io|co|me|tv|cc|app|dev|site|online|fun|live|icu|vip|ink)/[^\s"'<>]*""",
        RegexOption.IGNORE_CASE
    )

    fun sanitize(text: String): String {
        var out = text
        // Normalisasi escape JSON (\/ → /) SEBELUM dicocokkan (L-3:
        // "https:\/\/host\/path" lolos dulu karena pola menuntut "://").
        if ("\\/" in out) out = out.replace("\\/", "/")
        if ("://" in out) {
            out = urlPattern.replace(out) { match ->
                val rawUrl = match.value
                val trimmed = rawUrl.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
                val tail = rawUrl.substring(trimmed.length)
                "host=" + hostOf(trimmed) + tail
            }
        }
        // Host telanjang: hanya host-nya yang dicatat, path/query dibuang.
        out = bareHostPattern.replace(out) { match ->
            "host=" + match.value.substringBefore('/').lowercase()
        }
        return out
    }

    private fun hostOf(url: String): String {
        return try {
            URI(url).host?.lowercase() ?: "?"
        } catch (e: Exception) {
            "?"
        }
    }
}

/**
 * Pesan error yang bisa dibaca manusia untuk baris log (v2.0.10 item 2).
 *
 * Masalah: `e.javaClass.simpleName` mengembalikan nama kelas hasil rename
 * R8 (1 huruf, mis. "y"/"u") di APK Release — baris log jadi tidak bisa
 * dibaca ("gagal (u)"). Formatter ini memakai PESAN exception (yang sudah
 * disanitasi) sebagai sumber utama, dan hanya memakai nama kelas bila
 * pesannya kosong DAN namanya bisa dibaca (bukan 1 huruf hasil rename).
 */
object DiagError {
    /** Panjang maksimum pesan error di baris log (PRD v2.0.10 item 2). */
    const val MAX_ERROR_MESSAGE_CHARS = 120

    /** Pesan error singkat yang bisa dibaca — tanpa nama kelas 1 huruf. */
    fun describe(e: Throwable?): String {
        if (e == null) return "tidak diketahui"
        val msg = DiagSanitize.sanitize(firstMessage(e).orEmpty())
        if (msg.isNotBlank()) return msg.take(MAX_ERROR_MESSAGE_CHARS)
        return readableKind(e) ?: "galat"
    }

    /** "Jenis: pesan" untuk header crash — jenis dihilangkan bila hasil
     *  rename R8 (1 huruf). Bila pesan kosong, hanya jenisnya. */
    fun describeWithKind(e: Throwable?): String {
        if (e == null) return "tidak diketahui"
        val kind = readableKind(e)
        val msg = DiagSanitize.sanitize(firstMessage(e).orEmpty())
            .take(MAX_ERROR_MESSAGE_CHARS)
        return when {
            kind != null && msg.isNotBlank() -> "$kind: $msg"
            kind != null -> kind
            msg.isNotBlank() -> msg
            else -> "galat"
        }
    }

    /** Nama kelas yang bisa dibaca — null bila hasil rename R8 (1 huruf
     *  atau mengandung '$' dari kelas dalam yang di-rename). */
    private fun readableKind(e: Throwable): String? {
        val name = e.javaClass.name.substringAfterLast('.')
        return if (name.length > 1 && '$' !in name) name else null
    }

    /** Pesan pertama yang tidak kosong di rantai cause (e → cause → …). */
    private fun firstMessage(e: Throwable?): String? {
        var cur = e
        while (cur != null) {
            val m = cur.message?.trim()
            if (!m.isNullOrEmpty()) return m
            cur = cur.cause
        }
        return null
    }
}

/**
 * Cermin tulis file log (v2.0.10 item 12 — perbaikan M-1 Cari Bug v2.0.9).
 *
 * Masalah lama: `log()` menghitung `dropped` (trim) di bawah `lock`, lalu
 * tugas tulis async di executor memutuskan rewrite/append dari nilai itu.
 * Bila trim terpicu dan baris berikut masuk SEBELUM tugas rewrite jalan,
 * rewrite menulis snapshot yang SUDAH berisi baris berikut itu — lalu
 * tugas baris berikut tetap meng-append baris yang sama → baris duplikat
 * di file. `clear()` juga menghapus file secara async — force-stop dalam
 * jendela milidetik setelah Hapus bisa menghidupkan kembali file lama.
 *
 * Perbaikan: tiap baris mendapat nomor urut (seq) saat masuk ring; tugas
 * tulis memutuskan dari keadaan SAAT ITU (bukan nilai saat `log()`
 * dipanggil): rewrite → tulis seluruh snapshot + catat seq terakhirnya;
 * append → hanya bila seq baris ini belum tertulis (lewat rewrite
 * sebelumnya). `clear()` menaikkan generasi (tugas tulis generasi lama
 * jadi basi) + menghapus file secara SINKRON.
 *
 * Semua method @Synchronized — dipakai dari thread mana pun (UI,
 * player, executor file) tanpa lock luar.
 */
class DiagFileMirror {
    private var nextSeq = 0L
    private var persistedThroughSeq = 0L
    private var rewriteNeeded = false
    private var generation = 0L

    /** Baris lama yang sudah ada di file saat start (dibaca ulang ke ring). */
    @Synchronized
    fun seed(existingLines: Int) {
        repeat(existingLines) { nextSeq++ }
        persistedThroughSeq = nextSeq
    }

    /** Dipanggil tiap baris baru masuk ring — kembalikan nomor urutnya. */
    @Synchronized
    fun onLineAdded(dropped: Int): Long {
        if (dropped > 0) rewriteNeeded = true
        return ++nextSeq
    }

    /** Generasi tulis saat ini — naik tiap clear(). */
    @Synchronized
    fun generation(): Long = generation

    /** Ambil + kosongkan flag rewrite (sekali per tugas tulis). */
    @Synchronized
    fun takeRewriteNeeded(): Boolean {
        val r = rewriteNeeded
        rewriteNeeded = false
        return r
    }

    /** Nomor urut terakhir yang pernah diberikan. */
    @Synchronized
    fun lastAssignedSeq(): Long = nextSeq

    /** Baris ini sudah tertulis (langsung / lewat rewrite) — jangan tulis ulang. */
    @Synchronized
    fun isPersisted(seq: Long): Boolean = seq <= persistedThroughSeq

    /** Rewrite menulis ulang seluruh ring sampai [lastSeq] ini. */
    @Synchronized
    fun noteRewrote(lastSeq: Long) {
        if (lastSeq > persistedThroughSeq) persistedThroughSeq = lastSeq
    }

    /** Append satu baris ini. */
    @Synchronized
    fun noteAppended(seq: Long) {
        if (seq > persistedThroughSeq) persistedThroughSeq = seq
    }

    /** Kosongkan log — semua tugas tulis generasi lama jadi basi. */
    @Synchronized
    fun reset() {
        rewriteNeeded = false
        persistedThroughSeq = 0L
        generation++
    }
}

/**
 * Ring buffer baris log dengan cap [DiagLog.MAX_DIAG_LINES] + hysteresis
 * [DiagLog.TRIM_AT_LINES]: trim hanya jalan saat ukuran mencapai 600,
 * membuang baris tertua sampai tersisa 500. [add] mengembalikan jumlah
 * baris tertua yang dibuang (0 bila tidak ada trim).
 */
class DiagRingBuffer(private val maxLines: Int = DiagLog.MAX_DIAG_LINES) {
    private val deque = ArrayDeque<String>()

    fun add(line: String): Int {
        deque.addLast(line)
        if (deque.size < DiagLog.TRIM_AT_LINES) return 0
        val drop = deque.size - maxLines
        if (drop <= 0) return 0
        repeat(drop) { deque.removeFirst() }
        return drop
    }

    fun snapshot(): List<String> = deque.toList()

    val size: Int get() = deque.size

    fun clear() {
        deque.clear()
    }
}

/**
 * Header file export log — MURNI (bisa diuji di JVM tanpa Context):
 * versi aplikasi, info perangkat, waktu export, jumlah baris.
 * Dipakai [DiagLogger.exportSnapshot].
 */
object DiagExport {
    fun buildHeader(
        versionName: String,
        versionCode: Long,
        deviceInfo: String,
        exportedAtMs: Long,
        lineCount: Int
    ): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .format(Date(exportedAtMs))
        return buildString {
            appendLine("AnimeLovers log diagnostik")
            appendLine("Aplikasi: $versionName (versionCode $versionCode)")
            appendLine("Perangkat: $deviceInfo")
            appendLine("Diekspor: $stamp (waktu perangkat)")
            appendLine("Baris: $lineCount")
            appendLine("---")
        }
    }
}
