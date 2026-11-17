package animelovers.rebornhd

import android.app.Application
import animelovers.rebornhd.data.api.SankaApi
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
 * v2: satu-satunya sumber konten adalah Sanka (sankaApi + sankaRepository).
 * Anikoto/VidCloud/MegaPlay/backend kecil sudah dihapus.
 */
class AnimeloversApp : Application() {

    /** Scope aplikasi — untuk kerja yang harus selesai walau Activity sudah
     *  mati (mis. simpan posisi tonton di onDestroy player). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val prefs: Prefs by lazy { Prefs(this) }
    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val sankaApi: SankaApi by lazy {
        SankaApi.create(
            Constants.SANKA_BASE_URL,
            Constants.SANKA_CONNECT_TIMEOUT_S,
            Constants.SANKA_READ_TIMEOUT_S,
            Constants.SANKA_MIN_INTERVAL_MS
        )
    }

    val authRepository: AuthRepository by lazy { StubAuthRepository(this) }

    val sankaRepository: SankaRepository by lazy {
        SankaRepository(sankaApi, database.videoDao(), database.favoriteDao())
    }

    val playerRepository: PlayerRepository by lazy {
        PlayerRepository(sankaRepository)
    }
}

/** Akses Application dari Activity/Fragment mana pun. */
fun android.content.Context.app(): AnimeloversApp =
    applicationContext as AnimeloversApp
