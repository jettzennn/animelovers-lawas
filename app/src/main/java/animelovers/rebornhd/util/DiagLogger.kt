package animelovers.rebornhd.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Logger diagnostik aplikasi (v2.0.9 item 1).
 *
 * - Menyimpan maksimal [DiagLog.MAX_DIAG_LINES] baris terakhir di memori
 *   ([DiagRingBuffer]) + file `filesDir/diag/diag_log.txt` (maks 128 KB).
 *   File dimuat ulang saat start — log BERTAHAN antar restart aplikasi.
 * - Setiap baris juga dicerminkan ke logcat dengan tag `AnimLoversDiag`.
 * - TIDAK ADA panggilan jaringan di sini. Log TIDAK PERNAH terkirim otomatis;
 *   keluar dari HP hanya bila pengguna menekan Bagikan/Simpan di Konsol.
 * - Semua tulis file bersifat best-effort: kegagalan tulis TIDAK BOLEH
 *   melempar / crash aplikasi.
 *
 * Privasi (D-privasi-log): semua pesan lewat [DiagSanitize.sanitize] —
 * URL lengkap tidak pernah tertulis, hanya host-nya. Token, signature,
 * `raw`, dan email tidak pernah masuk ke pesan log.
 */
class DiagLogger(context: Context) {

    private val appContext = context.applicationContext
    private val logDir = File(appContext.filesDir, "diag")
    private val logFile = File(logDir, "diag_log.txt")

    /** Versi aplikasi — dari PackageManager, JANGAN BuildConfig. */
    val versionName: String

    /** Version code aplikasi — dari PackageManager, JANGAN BuildConfig. */
    val versionCode: Long

    /** Info perangkat untuk header export (model + versi Android). */
    val deviceInfo: String

    private val lock = Any()
    private val fileLock = Any()
    private val ring = DiagRingBuffer()
    /** Penjaga urutan tulis file (v2.0.10 item 12, M-1) — lihat DiagFileMirror. */
    private val fileMirror = DiagFileMirror()
    private val fileScope = CoroutineScope(
        SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    )
    private val _linesFlow = MutableStateFlow<List<String>>(emptyList())

    /** Aliran baris log terbaru — dipakai Konsol untuk tampil live. */
    val linesFlow: StateFlow<List<String>> = _linesFlow.asStateFlow()

    init {
        var vn = "?"
        var vc = 0L
        try {
            @Suppress("DEPRECATION")
            val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            vn = info.versionName ?: "?"
            @Suppress("DEPRECATION")
            vc = info.versionCode.toLong()
        } catch (e: Exception) {
            // PackageManager gagal — versi tetap "?" (bukan crash).
        }
        versionName = vn
        versionCode = vc
        deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} " +
            "(Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})"

        // Muat log sesi lama (maks 500 baris terakhir) — log bertahan antar restart.
        try {
            if (logFile.exists()) {
                val old = logFile.readLines().takeLast(DiagLog.MAX_DIAG_LINES)
                for (line in old) ring.add(line)
                _linesFlow.value = ring.snapshot()
            }
        } catch (e: Exception) {
            // File rusak / tidak bisa dibaca — mulai dari kosong; tulis
            // berikutnya menimpa file lama.
        }
        // v2.0.10 item 12: baris lama yang sudah ada di file dianggap
        // sudah tertulis (append berikut menempel di belakangnya).
        fileMirror.seed(ring.size)
    }

    fun info(tag: String, message: String) = log('I', tag, message)
    fun warn(tag: String, message: String) = log('W', tag, message)
    fun error(tag: String, message: String) = log('E', tag, message)

    private fun log(level: Char, tag: String, message: String) {
        val line = DiagFormat.formatLine(
            System.currentTimeMillis(), level, tag, DiagSanitize.sanitize(message)
        )
        // Nomor urut + generasi dicatat SEKARANG (di bawah lock, bersama
        // penambahan ke ring) — keputusan rewrite/append dibuat NANTI di
        // tugas tulis, dari keadaan ring saat itu (v2.0.10 item 12, M-1).
        val seq: Long
        val gen: Long
        synchronized(lock) {
            val dropped = ring.add(line)
            seq = fileMirror.onLineAdded(dropped)
            gen = fileMirror.generation()
            _linesFlow.value = ring.snapshot()
        }
        when (level) {
            'E' -> Log.e(DiagLog.LOG_TAG, line)
            'W' -> Log.w(DiagLog.LOG_TAG, line)
            else -> Log.i(DiagLog.LOG_TAG, line)
        }
        fileScope.launch {
            synchronized(fileLock) {
                try {
                    // Baris dari generasi lama (sebelum clear) — basi.
                    if (gen != fileMirror.generation()) return@launch
                    val needRewrite = fileMirror.takeRewriteNeeded()
                    if (needRewrite || logFile.length() > DiagLog.MAX_DIAG_FILE_BYTES) {
                        // Snapshot + seq terakhir diambil BERSAMA di bawah
                        // lock — supaya tidak ada baris yang terlewat atau
                        // tercatat dua kali.
                        val snapshot: List<String>
                        val lastSeq: Long
                        synchronized(lock) {
                            snapshot = ring.snapshot()
                            lastSeq = fileMirror.lastAssignedSeq()
                        }
                        rewriteFileLocked(snapshot)
                        fileMirror.noteRewrote(lastSeq)
                    } else if (!fileMirror.isPersisted(seq)) {
                        // Baris ini belum tertulis (tidak ikut rewrite
                        // sebelumnya) — append seperti biasa.
                        logDir.mkdirs()
                        logFile.appendText(line + "\n")
                        fileMirror.noteAppended(seq)
                    }
                    // else: baris sudah tertulis lewat rewrite sebelumnya —
                    // lewati (inilah yang dulu menduplikasi baris).
                } catch (e: Exception) {
                    // Tulis log gagal diam-diam (best-effort) — jangan crash
                    // aplikasi hanya karena log tidak bisa ditulis.
                }
            }
        }
    }

    /** Tulis ulang file dari isi ring buffer (dipakai saat trim / file > 128 KB). */
    private fun rewriteFileLocked(lines: List<String>) {
        try {
            logDir.mkdirs()
            logFile.writeText(
                if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
            )
        } catch (e: Exception) {
            // Best-effort — abaikan.
        }
    }

    /**
     * Tulis jejak crash SECARA SINKRON ke file — dipanggil dari
     * `UncaughtExceptionHandler` saat proses sedang mati, jadi tidak boleh
     * menunggu coroutine / dispatcher. TIDAK PERNAH melempar.
     */
    fun logCrashSync(threadName: String, throwable: Throwable) {
        try {
            val now = System.currentTimeMillis()
            val header = DiagFormat.formatLine(
                now, 'E', DiagLog.TAG_CRASH,
                "CRASH $threadName ${DiagError.describeWithKind(throwable)}"
            )
            val frames = throwable.stackTrace.take(10).joinToString("\n") { frame ->
                DiagFormat.formatLine(
                    now, 'E', DiagLog.TAG_CRASH,
                    "    at ${frame.className}.${frame.methodName}" +
                        "(${frame.fileName ?: "?"}:${frame.lineNumber})"
                )
            }
            val text = header + "\n" + frames + "\n"
            synchronized(fileLock) {
                try {
                    logDir.mkdirs()
                    logFile.appendText(text)
                } catch (e: Exception) {
                    // Best-effort — proses sedang mati, tidak ada yang bisa dilakukan.
                }
            }
            Log.e(DiagLog.LOG_TAG, header)
        } catch (e: Exception) {
            // Gagal tulis crash log TIDAK BOLEH melempar — handler lama tetap jalan.
        }
    }

    /** Salinan baris log saat ini (tertua → terbaru). */
    fun snapshot(): List<String> = synchronized(lock) { ring.snapshot() }

    /** Kosongkan log (memori + file). Tanpa dialog konfirmasi (keputusan PRD). */
    fun clear() {
        synchronized(lock) {
            ring.clear()
            // v2.0.10 item 12: naikkan generasi — tugas tulis lama jadi
            // basi dan tidak akan menulis lagi ke file yang sudah bersih.
            fileMirror.reset()
            _linesFlow.value = emptyList()
        }
        // Hapus file SECARA SINKRON (bukan async seperti dulu): file kecil,
        // unlink cepat — dan force-stop tepat setelah Hapus tidak bisa
        // lagi menghidupkan kembali file lama (M-1).
        synchronized(fileLock) {
            try {
                logFile.delete()
            } catch (e: Exception) {
                // Best-effort — abaikan.
            }
        }
    }

    /**
     * Buat file export `animelovers-log-yyyyMMdd-HHmmss.txt` di `filesDir/diag/`
     * dengan header (versi, perangkat, waktu, jumlah baris) + semua baris log.
     * File inilah yang dibagikan/disimpan Konsol — BUKAN `diag_log.txt` langsung
     * (supaya snapshot tidak berubah saat log terus berjalan).
     */
    fun exportSnapshot(): File {
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        val file = File(logDir, "animelovers-log-$stamp.txt")
        val lines = snapshot()
        val header = DiagExport.buildHeader(
            versionName, versionCode, deviceInfo, now, lines.size
        )
        logDir.mkdirs()
        file.writeText(header + if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n")
        return file
    }
}
