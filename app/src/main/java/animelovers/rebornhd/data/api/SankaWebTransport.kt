package animelovers.rebornhd.data.api

import android.content.Context
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Dilempar saat Sanka menjawab HTTP 429.
 * Pemanggil WAJIB menampilkan pesan rate limit dan TIDAK me-retry —
 * Sanka memblokir permanen setelah 3 pelanggaran (riset 2026-11-17).
 */
class RateLimitException(
    message: String = "Terlalu banyak permintaan"
) : Exception(message)

/**
 * Error HTTP Sanka yang BUKAN 429 — dilempar transport agar repository
 * bisa membedakan 5xx (retry + backoff, seperti dulu) dari kode lain
 * (403/404/dll — IOException langsung, tanpa retry).
 *
 * Sengaja BUKAN IOException: bila ini IOException, callWithRetry akan
 * me-retry SEMUA kode HTTP (termasuk 403) 3× dengan backoff — perilaku
 * berubah (gagal instan v2.0.0/2.0.1 jadi gagal lambat).
 */
class SankaHttpException(val statusCode: Int) : Exception("Sanka menjawab $statusCode")

/** Pemetaan kode HTTP → exception (sama seperti callWithRetry lama). */
internal fun exceptionForStatus(statusCode: Int): Exception =
    if (statusCode == 429) RateLimitException() else SankaHttpException(statusCode)

/**
 * Jeda throttle sebelum request berikutnya (ms): 0 bila ini request
 * pertama atau jeda sudah tercapai. Murni (bisa diuji tanpa WebView).
 */
internal fun computeThrottleWaitMs(nowMs: Long, lastStartMs: Long, minIntervalMs: Long): Long {
    if (lastStartMs <= 0L) return 0L
    return (minIntervalMs - (nowMs - lastStartMs)).coerceAtLeast(0L)
}

/**
 * Dekode hasil `evaluateJavascript` menjadi teks mentah.
 *
 * evaluateJavascript mengembalikan hasilnya sebagai literal string JSON
 * (mis. `"{\"status\":\"success\",...}"` dengan escape) — Gson yang
 * membuka bungkusnya. `"null"`/null = halaman tanpa isi (body kosong).
 */
internal fun decodeJsResult(jsResult: String?): String? {
    if (jsResult == null || jsResult == "null") return null
    return try {
        Gson().fromJson(jsResult, String::class.java)
    } catch (e: JsonSyntaxException) {
        throw IOException("Respons Sanka tidak bisa dibaca")
    }
}

/** Kontrak transport Sanka — mengambil JSON mentah untuk satu path relatif. */
interface SankaTransport {
    /**
     * Path relatif + query, mis. "anime/animekompi/home" atau
     * "anime/animekompi/search?q=naruto&page=1".
     */
    suspend fun getJson(path: String): String
}

/**
 * Transport Sanka lewat WebView tersembunyi (perbaikan v2.0.2, 2026-11-18).
 *
 * KENAPA (bukti, bukan dugaan): Beranda v2.0.0 + v2.0.1 "Gagal memuat"
 * instan di HP Faygo (data seluler + WiFi tetangga) sementara URL home
 * yang sama KE-LOAD penuh di Chrome HP itu DAN di curl Termux HP itu —
 * Tes1 (UA Chrome saja) 200, Tes2 (UA + header browser lengkap) 200,
 * Tes3 (UA Chrome TANPA header Accept) 200 — jadi UA dan header BUKAN
 * penyebabnya. Yang ditolak adalah SIDIK TLS (JA3) stack OkHttp:
 * satu-satunya yang masih berbeda antara request curl/Chrome (lolos)
 * dan request aplikasi (ditolak). WebView ADALAH Chrome asli di HP itu —
 * sidik TLS, header, dan segalanya persis Chrome yang sudah terbukti
 * lolos. Cronet ditolak (APK >10 MB), proxy ditolak (tanpa hosting),
 * SSLSocketFactory kustom ditolak (rapuh).
 *
 * CARA: satu WebView tersembunyi (tidak pernah ditempel ke layar),
 * dipakai bergantian untuk semua request Sanka. Per request: loadUrl →
 * tunggu onPageFinished → evaluateJavascript("document.body.textContent")
 * → string JSON mentah → repository mem-parse dengan model Gson yang
 * sudah ada (jalur parse tidak berubah).
 *
 * WAJIB textContent, BUKAN innerText: innerText bergantung layout dan
 * WebView tersembunyi bisa mengembalikan string kosong.
 *
 * DISIPLIN RATE LIMIT (PRD v2 §4.3 — 3 pelanggaran = BAN PERMANEN):
 * throttle 1,4 dtk antar MULAINYA request + Mutex (satu request dalam
 * satu waktu) + cache repository tetap berlaku. UA WebView TIDAK diubah:
 * default WebView = Chrome asli — itu inti perbaikan ini.
 *
 * WebView hidup selama aplikasi (dibuat sekali, malas) — tidak ada
 * destroy: sama seperti klien HTTP lama yang juga hidup selama aplikasi.
 */
class SankaWebTransport(
    private val context: Context,
    private val baseUrl: String,
    private val minIntervalMs: Long,
    private val timeoutMs: Long
) : SankaTransport {

    private val mutex = Mutex()

    @Volatile
    private var lastStartMs = 0L

    @Volatile
    private var webView: WebView? = null

    override suspend fun getJson(path: String): String {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val waitMs = computeThrottleWaitMs(now, lastStartMs, minIntervalMs)
            if (waitMs > 0) delay(waitMs)
            lastStartMs = System.currentTimeMillis()
            val url = baseUrl.trimEnd('/') + "/" + path.trimStart('/')
            try {
                return withTimeout(timeoutMs) { fetchOnce(url) }
            } catch (e: TimeoutCancellationException) {
                // Timeout manual = read timeout lama (IOException → retry
                // 3× di repository). Pembatalan asli (layar ditinggalkan)
                // tetap CancellationException — withTimeout hanya melempar
                // TimeoutCancellationException saat WAKTUNYA habis.
                throw IOException("Batas waktu Sanka habis")
            }
        }
    }

    /**
     * Satu kali muat halaman JSON. Semua panggilan WebView dari thread
     * utama; hasil dijembatani ke coroutine lewat suspendCancellableCoroutine.
     */
    private suspend fun fetchOnce(url: String): String {
        val wv = withContext(Dispatchers.Main) { ensureWebView() }
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { wv.post { wv.stopLoading() } }
            wv.post {
                if (cont.isActive) {
                    wv.webViewClient = object : WebViewClient() {
                    private var done = false

                    private fun finishOnce(block: () -> Unit) {
                        if (!done) {
                            done = true
                            block()
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        // evaluateJavascript berjalan asinkron — hasil
                        // (atau error baca) yang me-resume continuation.
                        view.evaluateJavascript("document.body.textContent") { jsResult ->
                            finishOnce {
                                try {
                                    val raw = decodeJsResult(jsResult)
                                        ?: throw IOException("Respons kosong dari Sanka")
                                    cont.resume(raw)
                                } catch (e: Exception) {
                                    cont.resumeWithException(e)
                                }
                            }
                        }
                    }

                    // Hanya frame utama yang dihitung — error sub-resource
                    // bukan kegagalan muat (halaman JSON tidak punya
                    // sub-resource, tapi jangan percaya buta).
                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: WebResourceResponse
                    ) {
                        if (!request.isForMainFrame) return
                        finishOnce {
                            cont.resumeWithException(exceptionForStatus(errorResponse.statusCode))
                        }
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError
                    ) {
                        if (!request.isForMainFrame) return
                        finishOnce {
                            cont.resumeWithException(IOException("Sanka tidak bisa dijangkau"))
                        }
                    }

                    @Suppress("DEPRECATION")
                    override fun onReceivedError(
                        view: WebView,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?
                    ) {
                        // API < 23: callback ini hanya dipanggil untuk frame utama.
                        finishOnce {
                            cont.resumeWithException(IOException("Sanka tidak bisa dijangkau"))
                        }
                    }
                }
                wv.loadUrl(url)
            }
        }
    }
}

    /**
     * Buat WebView sekali, di thread utama (wajib). Application context
     * boleh — WebView tidak pernah butuh Activity (tanpa dialog/pemilih
     * file). JavaScript WAJIB hidup (evaluateJavascript).
     * userAgentString SENGAJA tidak disentuh: default = Chrome asli.
     */
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        return WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            webView = this
        }
    }
}
