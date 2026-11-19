package animelovers.rebornhd.util

import coil.EventListener
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult

/**
 * Listener poster global (v2.0.9 item 5) — SATU instance untuk semua
 * request Coil (dipasang di `AnimeloversApp.newImageLoader()`).
 *
 * Aturan catat (supaya Konsol tidak banjir):
 * - Gagal → SELALU WARN (`poster gagal (<ExceptionClass>) (<ms>ms) host=<host>`).
 * - Sukses > 5 dtk → WARN (`poster lambat (<ms>ms) host=<host>`).
 * - Sukses cepat → TIDAK dicatat.
 * - Dibatalkan → hanya buang jejak waktu mulai (tanpa baris log).
 *
 * Privasi: URL poster TIDAK PERNAH ditulis utuh — hanya host-nya
 * (lewat [DiagSanitize.sanitize]). Peta waktu-mulai dipegang dengan
 * kunci [ImageRequest] dan selalu dibersihkan di peristiwa terminal
 * (sukses/gagal/batal) supaya tidak bocor.
 */
class DiagPosterListener(
    private val diagLogger: DiagLogger? = null
) : EventListener {

    private val startedAtMs = HashMap<ImageRequest, Long>()

    override fun onStart(request: ImageRequest) {
        synchronized(startedAtMs) {
            startedAtMs[request] = System.currentTimeMillis()
        }
    }

    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
        val started = removeStarted(request)
        val ms = System.currentTimeMillis() - started
        if (ms > DiagLog.SLOW_POSTER_MS) {
            diagLogger?.warn(
                DiagLog.TAG_POSTER,
                "poster lambat (${ms}ms) ${hostOf(request)}"
            )
        }
        // Sukses cepat TIDAK dicatat — Konsol hanya untuk yang lambat/gagal.
    }

    override fun onError(request: ImageRequest, result: ErrorResult) {
        val started = removeStarted(request)
        val ms = System.currentTimeMillis() - started
        // v2.0.10 item 2: nama kelas hasil rename R8 (1 huruf) tidak
        // boleh masuk log — pakai pesan yang bisa dibaca.
        val reason = DiagError.describe(result.throwable)
        diagLogger?.warn(
            DiagLog.TAG_POSTER,
            "poster gagal ($reason) (${ms}ms) ${hostOf(request)}"
        )
    }

    override fun onCancel(request: ImageRequest) {
        removeStarted(request)
    }

    private fun removeStarted(request: ImageRequest): Long {
        synchronized(startedAtMs) {
            return startedAtMs.remove(request) ?: System.currentTimeMillis()
        }
    }

    private fun hostOf(request: ImageRequest): String {
        val data = request.data.toString()
        return if ("://" in data) DiagSanitize.sanitize(data) else "host=?"
    }
}
