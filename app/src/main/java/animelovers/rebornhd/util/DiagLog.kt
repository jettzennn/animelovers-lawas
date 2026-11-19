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
    private val urlPattern = Regex("https?://[^\\s\"'<>]+")

    fun sanitize(text: String): String {
        if ("://" !in text) return text
        return urlPattern.replace(text) { match ->
            val rawUrl = match.value
            val trimmed = rawUrl.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
            val tail = rawUrl.substring(trimmed.length)
            "host=" + hostOf(trimmed) + tail
        }
    }

    private fun hostOf(url: String): String {
        return try {
            URI(url).host ?: "?"
        } catch (e: Exception) {
            "?"
        }
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
