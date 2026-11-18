package animelovers.rebornhd

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import animelovers.rebornhd.data.api.SankaTransport
import animelovers.rebornhd.data.api.SankaWebTransport
import animelovers.rebornhd.data.auth.AuthRepository
import animelovers.rebornhd.data.auth.StubAuthRepository
import animelovers.rebornhd.data.local.AppDatabase
import animelovers.rebornhd.data.repo.PlayerRepository
import animelovers.rebornhd.data.repo.SankaRepository
import animelovers.rebornhd.util.Constants
import animelovers.rebornhd.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application — satu tempat membuat dependensi (tanpa DI framework, PRD §3.2).
 *
 * Auth: [authRepository] saat ini [StubAuthRepository] (Firebase belum dipasang).
 * Bila proyek Firebase baru sudah dibuat (docs/firebase-setup.md), ganti
 * satu baris ini ke implementasi Firebase — UI + gerbang tidak berubah.
 *
 * v2: satu-satunya sumber konten adalah Sanka (sankaTransport +
 * sankaRepository). Anikoto/VidCloud/MegaPlay/backend kecil sudah dihapus.
 * Transport v2.0.2: WebView tersembunyi (Chrome asli) — OkHttp pensiun
 * (sidik TLS-nya diblokir Cloudflare dari IP HP Faygo).
 * v2.0.3: WebView DITEMPEL ke window MainActivity (attachToWindow di
 * MainActivity.onCreate) + UA tanpa penanda "Version/4.0" — v2.0.2 yang
 * tak tertempel tidak memicu onPageFinished di Vivo Faygo.
 */
class AnimeloversApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // v2.0.5 (item 5): tema aplikasi mengikuti sistem HP
        // (HP dark → app dark, HP terang → app terang). Dipanggil di sini
        // — sebelum activity mana pun dibuat — supaya AppCompat ikut
        // memakai mode malam sistem. Pemilihan tema gelap/terang per
        // layar tetap lewat prefs.isDarkTheme (dibaca dari uiMode sistem).
        AppCompatDelegate.setDefaultNightMode(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        )
    }

    /** Scope aplikasi — untuk kerja yang harus selesai walau Activity sudah
     *  mati (mis. simpan posisi tonton di onDestroy player). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val prefs: Prefs by lazy { Prefs(this) }
    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val sankaTransport: SankaTransport by lazy {
        SankaWebTransport(
            this,
            Constants.SANKA_BASE_URL,
            Constants.SANKA_MIN_INTERVAL_MS,
            Constants.SANKA_READ_TIMEOUT_S * 1000L
        )
    }

    val authRepository: AuthRepository by lazy { StubAuthRepository(this) }

    val sankaRepository: SankaRepository by lazy {
        SankaRepository(sankaTransport, database.videoDao(), database.favoriteDao())
    }

    val playerRepository: PlayerRepository by lazy {
        PlayerRepository(sankaRepository)
    }
}

/** Akses Application dari Activity/Fragment mana pun. */
fun android.content.Context.app(): AnimeloversApp =
    applicationContext as AnimeloversApp
