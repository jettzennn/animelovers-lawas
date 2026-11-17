package animelovers.rebornhd.data.api

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
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
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "SankaWebTransport"

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

/**
 * Alasan gagal transport yang JELAS — semuanya IOException supaya
 * repository tetap me-retry 3× seperti dulu (callWithRetry hanya
 * me-retry IOException). Bedanya dari v2.0.2: jenisnya bisa dibedakan
 * (bukan satu IOException generik), jadi toast bisa menulis alasannya
 * dan logcat bisa dibaca tanpa menebak.
 */
class SankaTimeoutException : IOException("Batas waktu Sanka habis")

class SankaUnreachableException : IOException("Sanka tidak bisa dijangkau")

class SankaEmptyResponseException : IOException("Respons kosong dari Sanka")

class SankaUnreadableResponseException : IOException("Respons Sanka tidak bisa dibaca")

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
 * Token penanda WebView Android di UA default: "Version/4.0" yang
 * diikuti spasi atau akhir string. "Version/4.0.1" (bukan penanda)
 * TIDAK tersentuh.
 */
private val WEBVIEW_UA_MARKER = Regex(" ?Version/4\\.0(?= |\$)")

/**
 * Hapus penanda WebView dari UA bawaan (v2.0.3).
 *
 * UA default WebView mengandung "Version/4.0" — token yang dipakai
 * Cloudflare (dan bot-detection lain) untuk membedakan WebView dari
 * Chrome asli. Dihapus tokennya SAJA: sisa UA persis bawaan stack
 * WebView di HP itu (versi Chrome sistem, model, dsb. TIDAK di-hardcode
 * — hardcode UA Chrome statis bisa tidak cocok dengan WebView di HP user).
 * Tanpa penanda → kembali utuh.
 */
internal fun stripWebViewMarker(userAgent: String): String =
    userAgent.replace(WEBVIEW_UA_MARKER, "")

/**
 * Alasan gagal yang bisa dibaca user untuk toast (v2.0.3).
 * null = tidak diketahui → pakai toast generik seperti dulu.
 * RateLimitException → null: sudah ditangani terpisah (pesan khusus).
 */
fun sankaErrorReason(e: Exception): String? = when (e) {
    is SankaHttpException -> "server menjawab ${e.statusCode}"
    is SankaTimeoutException -> "batas waktu habis"
    is SankaUnreachableException -> "tidak bisa dijangkau"
    is SankaEmptyResponseException -> "respons kosong"
    is SankaUnreadableResponseException -> "respons tidak bisa dibaca"
    is JsonSyntaxException -> "data tidak bisa dibaca"
    else -> null
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
        throw SankaUnreadableResponseException()
    }
}

/** Kontrak transport Sanka — mengambil JSON mentah untuk satu path relatif. */
interface SankaTransport {
    /**
     * Path relatif + query, mis. "anime/animekompi/home" atau
     * "anime/animekompi/search?q=naruto&page=1".
     */
    suspend fun getJson(path: String): String

    /**
     * Tempel WebView transport ke window Activity (v2.0.3).
     * Default: tidak ada — transport yang tidak memakai WebView
     * (mis. FakeTransport di uji) tidak perlu berbuat apa-apa.
     * Boleh dipanggil berkali-kali (mis. tiap onCreate setelah rotasi).
     * Host null = lepas tempelan; transport jalan tanpa window lagi
     * (perilaku v2.0.2).
     */
    fun attachToWindow(activity: Activity?) {}
}

/**
 * Transport Sanka lewat WebView tersembunyi (perbaikan v2.0.2, 2026-11-18;
 * ditempel ke window + UA dibersihkan di v2.0.3, 2026-11-18).
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
 * v2.0.3 — DUA TERSANGKA dari uji HP v2.0.2 (Beranda + Jelajahi muter
 * terus, toast "Gagal memuat" muncul, server sehat untuk semua UA yang
 * bisa dites — Chrome, WebView "Version/4.0", curl: semua 200 + JSON):
 *  (a) WebView v2.0.2 TIDAK PERNAH ditempel ke window (dibuat dari
 *      applicationContext, tanpa hierarki view) — di Vivo Faygo
 *      onPageFinished kemungkinan tidak pernah jalan, jadi muatnya
 *      mati di timeout 30 dtk × 3. Kini WebView DITEMPEL ke decor view
 *      MainActivity (1×1 px, INVISIBLE, tidak bisa difokuskan).
 *  (b) UA default WebView mengandung penanda "Version/4.0" yang dipakai
 *      Cloudflare untuk membedakan WebView dari Chrome asli — kini
 *      token itu dihapus; sisanya persis bawaan stack WebView di HP itu.
 *
 * CARA: satu WebView (dibuat sekali, malas), dipakai bergantian untuk
 * semua request Sanka. Per request: loadUrl → tunggu onPageFinished →
 * evaluateJavascript("document.body.textContent") → string JSON mentah →
 * repository mem-parse dengan model Gson yang sudah ada (jalur parse
 * tidak berubah).
 *
 * WAJIB textContent, BUKAN innerText: innerText bergantung layout dan
 * WebView tersembunyi bisa mengembalikan string kosong.
 *
 * ALASAN GAGAL TERLIHAT (v2.0.3): tiap kegagalan di-Log.e (path + kode
 * HTTP / timeout / respons kosong / tidak bisa dijangkau) dan exception
 * transport punya jenis sendiri (SankaTimeoutException dsb.) supaya
 * toast bisa menulis alasannya (sankaErrorReason) — tidak lagi toast
 * generik buta.
 *
 * DISIPLIN RATE LIMIT (PRD v2 §4.3 — 3 pelanggaran = BAN PERMANEN):
 * throttle 1,4 dtk antar MULAINYA request + Mutex (satu request dalam
 * satu waktu) + cache repository tetap berlaku. UA WebView TIDAK diubah
 * kecuali penanda "Version/4.0" di atas.
 *
 * WebView hidup selama aplikasi (dibuat sekali, malas) — tidak ada
 * destroy: sama seperti klien HTTP lama yang juga hidup selama aplikasi.
 * Tempelan pindah sendiri tiap MainActivity.onCreate (rotasi).
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

    /**
     * Host window untuk tempelan WebView — WeakReference supaya rotasi
     * Activity tidak bocor (transport hidup selama aplikasi, Activity
     * datang-pergi).
     */
    @Volatile
    private var hostRef: WeakReference<Activity>? = null

    override fun attachToWindow(activity: Activity?) {
        if (activity == null) {
            hostRef = null
            // Lepas WebView dari parent-nya (decor view Activity yang
            // sudah mati) — tanpa ini transport (seumur aplikasi) →
            // webView → parent DecorView menahan Activity mati.
            // Hapus langsung bila sudah di thread utama; kalau tidak,
            // lewat Handler main — BUKAN View.post: post pada view yang
            // sudah terlepas ditunda sampai view ditempel lagi, yang
            // tidak pernah terjadi untuk Activity yang mati.
            val wv = webView ?: return
            if (Looper.myLooper() == Looper.getMainLooper()) {
                (wv.parent as? ViewGroup)?.removeView(wv)
            } else {
                Handler(Looper.getMainLooper()).post {
                    (wv.parent as? ViewGroup)?.removeView(wv)
                }
            }
            return
        }
        hostRef = WeakReference(activity)
        // Bila WebView sudah dibuat (request pertama sudah lewat),
        // tempel sekarang juga — jangan tunggu request berikutnya.
        val wv = webView ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            attachToHost(wv, activity)
        } else {
            // Lewat Handler main — BUKAN View.post: post pada view yang
            // belum tertempel ditunda sampai view ditempel, jadi tempelan
            // tidak pernah terjadi bila dipanggil dari background thread.
            Handler(Looper.getMainLooper()).post { attachToHost(wv, activity) }
        }
    }

    /**
     * Tempel WebView ke decor view Activity: 1×1 px, INVISIBLE (tetap
     * ikut layout — GONE bisa membuat WebView tidak di-layout),
     * tidak bisa difokuskan, tidak masuk aksesibilitas. HANYA di thread
     * utama. Bila sudah tertempel di decor view yang sama, diam saja;
     * bila tertempel di window lama (rotasi), pindahkan.
     */
    private fun attachToHost(wv: WebView, activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val parent = wv.parent as? ViewGroup
        if (parent === decor) return
        parent?.removeView(wv)
        decor.addView(wv, ViewGroup.LayoutParams(1, 1))
        Log.d(TAG, "WebView ditempel ke window ${activity.localClassName}")
    }

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
                Log.e(TAG, "getJson $url: timeout ${timeoutMs}ms", e)
                throw SankaTimeoutException()
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
                                            ?: throw SankaEmptyResponseException()
                                        cont.resume(raw)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "getJson $url: gagal baca textContent (${e.message})", e)
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
                            Log.e(TAG, "getJson $url: HTTP ${errorResponse.statusCode}")
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
                            Log.e(TAG, "getJson $url: WebView error ${error.errorCode}: ${error.description}")
                            finishOnce {
                                cont.resumeWithException(SankaUnreachableException())
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
                            Log.e(TAG, "getJson $url: WebView error $errorCode: $description")
                            finishOnce {
                                cont.resumeWithException(SankaUnreachableException())
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
     *
     * v2.0.3: (a) UA dibersihkan dari penanda "Version/4.0" — sisa UA
     * persis bawaan stack WebView di HP itu, TIDAK di-hardcode;
     * (b) WebView ditempel ke decor view Activity host (1×1 px, tak
     * terlihat) — v2.0.2 yang tak tertempel tidak pernah memicu
     * onPageFinished di Vivo Faygo.
     */
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        return WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            val defaultUa = settings.userAgentString
            if (!defaultUa.isNullOrEmpty()) {
                val cleanedUa = stripWebViewMarker(defaultUa)
                if (cleanedUa != defaultUa && cleanedUa.isNotEmpty()) {
                    settings.userAgentString = cleanedUa
                    Log.d(TAG, "Penanda Version/4.0 dihapus dari UA WebView")
                }
            }
            isFocusable = false
            isFocusableInTouchMode = false
            visibility = View.INVISIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            webView = this
            hostRef?.get()?.let { attachToHost(this, it) }
        }
    }
}
